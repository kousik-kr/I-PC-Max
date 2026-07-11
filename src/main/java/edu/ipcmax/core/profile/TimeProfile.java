package edu.ipcmax.core.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleUnaryOperator;

import edu.ipcmax.core.function.Domain;

/**
 * Continuous piecewise-linear time-valued profile over an exact root domain.
 */
public final class TimeProfile {
    /**
     * Exact piecewise-linear breakpoint.
     */
    public record Breakpoint(double minute, double value) {
        public Breakpoint {
            if (!Double.isFinite(minute) || !Double.isFinite(value)) {
                throw new IllegalArgumentException("time profile breakpoints must be finite");
            }
            minute = Domain.canonicalTime(minute);
            value = Domain.canonicalTime(value);
        }
    }

    private record LinearSegment(
            double start,
            double end,
            double startValue,
            double endValue,
            boolean startInclusive,
            boolean endInclusive) {
        private boolean isPoint() {
            return start == end;
        }

        private Domain.Interval rootInterval() {
            return new Domain.Interval(start, end, startInclusive, endInclusive);
        }
    }

    private final Domain domain;
    private final String fingerprint;
    private final List<Breakpoint> breakpoints;

    /**
     * Creates a profile that is affine on each connected domain component.
     *
     * <p>This compatibility constructor materializes exact endpoint metadata;
     * callers with internal changes of slope must use {@link #piecewise}.</p>
     */
    public TimeProfile(Domain domain, DoubleUnaryOperator evaluator, String fingerprint) {
        this(domain, inferAffineBreakpoints(domain, evaluator), fingerprint);
    }

    private TimeProfile(Domain domain, List<Breakpoint> breakpoints, String fingerprint) {
        if (domain == null || domain.isEmpty()) {
            throw new IllegalArgumentException("time profile domain cannot be null or empty");
        }
        this.domain = domain;
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.breakpoints = List.copyOf(validateBreakpoints(domain, breakpoints));
    }

    /**
     * Identity departure profile {@code psi(t)=t}.
     */
    public static TimeProfile identity(Domain domain) {
        return new TimeProfile(domain, time -> time, "identity:" + domain.intervals());
    }

    /**
     * Constant time profile.
     */
    public static TimeProfile constant(Domain domain, double value) {
        return new TimeProfile(domain, ignored -> value, "constant:" + value + ":" + domain.intervals());
    }

    /**
     * Builds an exact piecewise-linear profile.
     */
    public static TimeProfile piecewise(Domain domain, List<Breakpoint> breakpoints, String fingerprint) {
        return new TimeProfile(domain, breakpoints, fingerprint);
    }

    /**
     * Evaluates the profile at a root departure time.
     */
    public double valueAt(int rootDepartureTime) {
        return valueAt((double) rootDepartureTime);
    }

    /**
     * Evaluates the profile at a contained start time.
     */
    public double valueAt(double rootDepartureTime) {
        rootDepartureTime = Domain.canonicalTime(rootDepartureTime);
        if (!domain.contains(rootDepartureTime)) {
            throw new IllegalArgumentException("time is outside profile domain: " + rootDepartureTime);
        }
        return Domain.canonicalTime(evaluateUnchecked(rootDepartureTime));
    }

    /**
     * Evaluates the continuous profile on the closure of its domain.
     *
     * <p>This is intended for exact root and integral calculations on
     * half-open cells. It does not make an excluded endpoint a valid query
     * time.</p>
     */
    public double valueAtClosure(double rootDepartureTime) {
        rootDepartureTime = Domain.canonicalTime(rootDepartureTime);
        if (!inDomainClosure(rootDepartureTime)) {
            throw new IllegalArgumentException("time is outside profile-domain closure: " + rootDepartureTime);
        }
        return Domain.canonicalTime(evaluateUnchecked(rootDepartureTime));
    }

    /**
     * Domain where this profile is valid.
     */
    public Domain domain() {
        return domain;
    }

    /**
     * Returns exact breakpoints.
     */
    public List<Breakpoint> breakpoints() {
        return breakpoints;
    }

    /**
     * Exact piecewise metadata is always available.
     */
    public boolean isPiecewise() {
        return true;
    }

    /**
     * Stable fingerprint for memoization.
     */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * Restricts this profile to a subdomain without sampling away any slope
     * changes.
     */
    public TimeProfile restrict(Domain subdomain) {
        Domain restricted = domain.intersection(subdomain);
        if (restricted.isEmpty()) {
            throw new IllegalArgumentException("restricted time profile domain is empty");
        }
        List<Breakpoint> points = breakpointsOver(restricted);
        return new TimeProfile(restricted, points, fingerprint + "|restrict:" + restricted.intervals());
    }

    /**
     * Returns the exact image domain over the supplied root domain.
     */
    public Domain imageDomain(Domain rootDomain) {
        Domain restricted = domain.intersection(rootDomain);
        if (restricted.isEmpty()) {
            return Domain.empty();
        }
        Domain image = Domain.empty();
        for (LinearSegment segment : segments(restricted)) {
            if (segment.isPoint() || approximatelyEqual(segment.startValue(), segment.endValue())) {
                image = image.union(Domain.closed(segment.startValue(), segment.startValue()));
                continue;
            }
            boolean increasing = segment.endValue() > segment.startValue();
            image = image.union(Domain.of(new Domain.Interval(
                    Math.min(segment.startValue(), segment.endValue()),
                    Math.max(segment.startValue(), segment.endValue()),
                    increasing ? segment.startInclusive() : segment.endInclusive(),
                    increasing ? segment.endInclusive() : segment.startInclusive())));
        }
        return image;
    }

    /**
     * Returns the exact root domain where this profile's value lies inside
     * {@code target}. Flat segments yield interval preimages.
     */
    public Domain preimage(Domain target, Domain rootDomain) {
        Objects.requireNonNull(target, "target");
        Domain restricted = domain.intersection(rootDomain);
        if (restricted.isEmpty() || target.isEmpty()) {
            return Domain.empty();
        }

        Domain result = Domain.empty();
        for (LinearSegment segment : segments(restricted)) {
            result = result.union(preimageOfSegment(segment, target));
        }
        return result;
    }

    /**
     * Returns the exact root domain where {@code this(t) - t <= budget}.
     */
    public Domain domainWhereTravelTimeAtMost(Domain rootDomain, double budget) {
        if (Double.isNaN(budget)) {
            throw new IllegalArgumentException("budget cannot be NaN");
        }
        Domain restricted = domain.intersection(rootDomain);
        if (restricted.isEmpty()) {
            return Domain.empty();
        }
        if (budget == Double.POSITIVE_INFINITY) {
            return restricted;
        }
        if (budget == Double.NEGATIVE_INFINITY) {
            return Domain.empty();
        }

        Domain result = Domain.empty();
        for (LinearSegment segment : segments(restricted)) {
            double leftTravel = segment.startValue() - segment.start();
            double rightTravel = segment.endValue() - segment.end();
            result = result.union(domainAtMost(segment, leftTravel, rightTravel, budget));
        }
        return result;
    }

    /**
     * Composes {@code outer(this(t))} exactly.
     */
    public TimeProfile compose(TimeProfile outer, String composedFingerprint) {
        Objects.requireNonNull(outer, "outer");
        Domain composedDomain = preimage(outer.domain, domain);
        if (composedDomain.isEmpty()) {
            throw new IllegalArgumentException("composition domain is empty");
        }

        List<Breakpoint> composed = new ArrayList<>();
        for (Domain.Interval component : composedDomain.intervals()) {
            List<Double> cuts = new ArrayList<>();
            cuts.add(component.start());
            for (Breakpoint innerBreakpoint : breakpoints) {
                if (innerBreakpoint.minute() > component.start()
                        && innerBreakpoint.minute() < component.end()) {
                    addCut(cuts, innerBreakpoint.minute());
                }
            }
            for (Breakpoint outerBreakpoint : outer.breakpoints) {
                Domain roots = preimage(
                        Domain.closed(outerBreakpoint.minute(), outerBreakpoint.minute()),
                        Domain.of(component));
                for (double root : roots.breakpoints()) {
                    if (root > component.start() && root < component.end()) {
                        addCut(cuts, root);
                    }
                }
            }
            cuts.add(component.end());
            cuts.sort(Double::compare);
            for (double cut : cuts) {
                double innerValue = evaluateUnchecked(cut);
                addBreakpoint(composed, new Breakpoint(cut, outer.evaluateUnchecked(innerValue)));
            }
        }
        return new TimeProfile(composedDomain, composed, composedFingerprint);
    }

    private Domain preimageOfSegment(LinearSegment segment, Domain target) {
        if (segment.isPoint()) {
            return target.contains(segment.startValue())
                    ? Domain.closed(segment.start(), segment.end())
                    : Domain.empty();
        }
        if (approximatelyEqual(segment.startValue(), segment.endValue())) {
            return target.contains(segment.startValue())
                    ? Domain.of(segment.rootInterval())
                    : Domain.empty();
        }

        boolean increasing = segment.endValue() > segment.startValue();
        Domain image = Domain.of(new Domain.Interval(
                Math.min(segment.startValue(), segment.endValue()),
                Math.max(segment.startValue(), segment.endValue()),
                increasing ? segment.startInclusive() : segment.endInclusive(),
                increasing ? segment.endInclusive() : segment.startInclusive()));
        Domain overlap = image.intersection(target);
        Domain roots = Domain.empty();
        for (Domain.Interval valueInterval : overlap.intervals()) {
            double rootAtStart = inverse(segment, valueInterval.start());
            double rootAtEnd = inverse(segment, valueInterval.end());
            if (increasing) {
                roots = roots.union(Domain.of(new Domain.Interval(
                        rootAtStart,
                        rootAtEnd,
                        valueInterval.startInclusive(),
                        valueInterval.endInclusive())));
            } else {
                roots = roots.union(Domain.of(new Domain.Interval(
                        rootAtEnd,
                        rootAtStart,
                        valueInterval.endInclusive(),
                        valueInterval.startInclusive())));
            }
        }
        return roots;
    }

    private static Domain domainAtMost(
            LinearSegment segment,
            double leftValue,
            double rightValue,
            double target) {
        boolean leftFeasible = lessThanOrEqual(leftValue, target);
        boolean rightFeasible = lessThanOrEqual(rightValue, target);

        if (segment.isPoint()) {
            return leftFeasible ? Domain.closed(segment.start(), segment.end()) : Domain.empty();
        }
        if (leftFeasible && rightFeasible) {
            return Domain.of(segment.rootInterval());
        }
        if (!leftFeasible && !rightFeasible) {
            return Domain.empty();
        }
        if (approximatelyEqual(leftValue, rightValue)) {
            return leftFeasible ? Domain.of(segment.rootInterval()) : Domain.empty();
        }

        double crossing = segment.start()
                + (target - leftValue) * (segment.end() - segment.start()) / (rightValue - leftValue);
        crossing = clamp(crossing, segment.start(), segment.end());
        if (leftFeasible) {
            return intervalOrEmpty(
                    segment.start(), crossing, segment.startInclusive(), true);
        }
        return intervalOrEmpty(crossing, segment.end(), true, segment.endInclusive());
    }

    private List<LinearSegment> segments(Domain restricted) {
        List<LinearSegment> result = new ArrayList<>();
        for (Domain.Interval component : restricted.intervals()) {
            if (component.start() == component.end()) {
                double value = evaluateUnchecked(component.start());
                result.add(new LinearSegment(
                        component.start(), component.end(), value, value, true, true));
                continue;
            }

            List<Double> cuts = new ArrayList<>();
            cuts.add(component.start());
            for (Breakpoint breakpoint : breakpoints) {
                if (breakpoint.minute() > component.start()
                        && breakpoint.minute() < component.end()) {
                    addCut(cuts, breakpoint.minute());
                }
            }
            cuts.add(component.end());
            cuts.sort(Double::compare);
            for (int i = 1; i < cuts.size(); i++) {
                double start = cuts.get(i - 1);
                double end = cuts.get(i);
                result.add(new LinearSegment(
                        start,
                        end,
                        evaluateUnchecked(start),
                        evaluateUnchecked(end),
                        i == 1 ? component.startInclusive() : true,
                        i == cuts.size() - 1 ? component.endInclusive() : false));
            }
        }
        return result;
    }

    private List<Breakpoint> breakpointsOver(Domain restricted) {
        List<Breakpoint> result = new ArrayList<>();
        for (Domain.Interval component : restricted.intervals()) {
            addBreakpoint(result, new Breakpoint(component.start(), evaluateUnchecked(component.start())));
            for (Breakpoint breakpoint : breakpoints) {
                if (breakpoint.minute() > component.start()
                        && breakpoint.minute() < component.end()) {
                    addBreakpoint(result, breakpoint);
                }
            }
            addBreakpoint(result, new Breakpoint(component.end(), evaluateUnchecked(component.end())));
        }
        return result;
    }

    private double evaluateUnchecked(double minute) {
        minute = Domain.canonicalTime(minute);
        if (breakpoints.size() == 1) {
            if (!approximatelyEqual(minute, breakpoints.get(0).minute())) {
                throw new IllegalArgumentException("time is outside piecewise profile closure: " + minute);
            }
            return breakpoints.get(0).value();
        }
        if (minute < breakpoints.get(0).minute()
                || minute > breakpoints.get(breakpoints.size() - 1).minute()) {
            throw new IllegalArgumentException("time is outside piecewise profile closure: " + minute);
        }
        if (approximatelyEqual(minute, breakpoints.get(0).minute())) {
            return breakpoints.get(0).value();
        }
        if (approximatelyEqual(minute, breakpoints.get(breakpoints.size() - 1).minute())) {
            return breakpoints.get(breakpoints.size() - 1).value();
        }

        int low = 0;
        int high = breakpoints.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Breakpoint breakpoint = breakpoints.get(mid);
            if (approximatelyEqual(minute, breakpoint.minute())) {
                return breakpoint.value();
            }
            if (minute < breakpoint.minute()) {
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        int rightIndex = low;
        Breakpoint left = breakpoints.get(rightIndex - 1);
        Breakpoint right = breakpoints.get(rightIndex);
        double alpha = (minute - left.minute()) / (right.minute() - left.minute());
        return Domain.canonicalTime(left.value() + alpha * (right.value() - left.value()));
    }

    private boolean inDomainClosure(double time) {
        time = Domain.canonicalTime(time);
        for (Domain.Interval component : domain.intervals()) {
            if (time >= component.start() && time <= component.end()) {
                return true;
            }
        }
        return false;
    }

    private static List<Breakpoint> inferAffineBreakpoints(
            Domain domain,
            DoubleUnaryOperator evaluator) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(evaluator, "evaluator");
        if (domain.isEmpty()) {
            throw new IllegalArgumentException("time profile domain cannot be empty");
        }
        List<Breakpoint> points = new ArrayList<>();
        for (Domain.Interval component : domain.intervals()) {
            addBreakpoint(points, evaluated(component.start(), evaluator));
            addBreakpoint(points, evaluated(component.end(), evaluator));
        }
        return points;
    }

    private static Breakpoint evaluated(double time, DoubleUnaryOperator evaluator) {
        double value = evaluator.applyAsDouble(time);
        return new Breakpoint(time, value);
    }

    private static List<Breakpoint> validateBreakpoints(
            Domain domain,
            List<Breakpoint> supplied) {
        Objects.requireNonNull(supplied, "breakpoints");
        if (supplied.isEmpty()) {
            throw new IllegalArgumentException("piecewise time profile requires at least one breakpoint");
        }
        List<Breakpoint> sorted = new ArrayList<>(supplied);
        sorted.sort(Comparator.comparingDouble(Breakpoint::minute));
        List<Breakpoint> unique = new ArrayList<>();
        for (Breakpoint breakpoint : sorted) {
            if (!unique.isEmpty()
                    && approximatelyEqual(unique.get(unique.size() - 1).minute(), breakpoint.minute())) {
                Breakpoint previous = unique.get(unique.size() - 1);
                if (!approximatelyEqual(previous.value(), breakpoint.value())) {
                    throw new IllegalArgumentException("conflicting values at one time-profile breakpoint");
                }
                continue;
            }
            unique.add(breakpoint);
        }

        double first = unique.get(0).minute();
        double last = unique.get(unique.size() - 1).minute();
        for (Domain.Interval component : domain.intervals()) {
            if (component.start() < first || component.end() > last) {
                throw new IllegalArgumentException("time profile breakpoints do not cover its domain");
            }
        }
        return unique;
    }

    private static double inverse(LinearSegment segment, double value) {
        double root = segment.start()
                + (value - segment.startValue()) * (segment.end() - segment.start())
                / (segment.endValue() - segment.startValue());
        return clamp(root, segment.start(), segment.end());
    }

    private static Domain intervalOrEmpty(
            double start,
            double end,
            boolean startInclusive,
            boolean endInclusive) {
        start = Domain.canonicalTime(start);
        end = Domain.canonicalTime(end);
        if (start < end) {
            return Domain.of(new Domain.Interval(start, end, startInclusive, endInclusive));
        }
        if (approximatelyEqual(start, end) && startInclusive && endInclusive) {
            double point = (start + end) / 2.0;
            return Domain.closed(point, point);
        }
        return Domain.empty();
    }

    private static void addBreakpoint(List<Breakpoint> points, Breakpoint point) {
        if (!points.isEmpty()) {
            Breakpoint last = points.get(points.size() - 1);
            if (approximatelyEqual(last.minute(), point.minute())) {
                if (!approximatelyEqual(last.value(), point.value())) {
                    throw new IllegalArgumentException("conflicting values at one time-profile breakpoint");
                }
                return;
            }
        }
        points.add(point);
    }

    private static void addCut(List<Double> cuts, double cut) {
        cut = Domain.canonicalTime(cut);
        for (double existing : cuts) {
            if (approximatelyEqual(existing, cut)) {
                return;
            }
        }
        cuts.add(cut);
    }

    private static boolean lessThanOrEqual(double left, double right) {
        return Domain.canonicalTime(left) <= Domain.canonicalTime(right);
    }

    private static boolean approximatelyEqual(double left, double right) {
        return Domain.sameTime(left, right);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
