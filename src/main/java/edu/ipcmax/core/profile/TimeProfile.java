package edu.ipcmax.core.profile;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseLinearFn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleUnaryOperator;

/**
 * Time-valued profile over a continuous root departure domain.
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
        }
    }

    private final Domain domain;
    private final DoubleUnaryOperator evaluator;
    private final String fingerprint;
    private final List<Breakpoint> breakpoints;

    /**
     * Creates a profile with a stable fingerprint.
     */
    public TimeProfile(Domain domain, DoubleUnaryOperator evaluator, String fingerprint) {
        this(domain, evaluator, fingerprint, List.of());
    }

    private TimeProfile(Domain domain, DoubleUnaryOperator evaluator, String fingerprint, List<Breakpoint> breakpoints) {
        if (domain == null || domain.isEmpty()) {
            throw new IllegalArgumentException("time profile domain cannot be null or empty");
        }
        this.domain = domain;
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.breakpoints = List.copyOf(breakpoints);
    }

    /**
     * Identity departure profile {@code psi(t)=t}.
     */
    public static TimeProfile identity(Domain domain) {
        if (isSingletonDomain(domain)) {
            double point = domain.intervals().get(0).start();
            return new TimeProfile(domain, ignored -> point, "identity:" + domain.intervals());
        }
        return piecewise(domain, domain.breakpoints().stream()
                .map(point -> new Breakpoint(point, point))
                .toList(),
                "identity:" + domain.intervals());
    }

    /**
     * Constant time profile.
     */
    public static TimeProfile constant(Domain domain, double value) {
        if (isSingletonDomain(domain)) {
            return new TimeProfile(domain, ignored -> value, "constant:" + value + ":" + domain.intervals());
        }
        List<Breakpoint> points = new ArrayList<>();
        for (Domain.Interval interval : domain.intervals()) {
            points.add(new Breakpoint(interval.start(), value));
            points.add(new Breakpoint(interval.end(), value));
        }
        return piecewise(domain, points, "constant:" + value + ":" + domain.intervals());
    }

    /**
     * Builds an exact piecewise-linear profile.
     */
    public static TimeProfile piecewise(Domain domain, List<Breakpoint> breakpoints, String fingerprint) {
        if (breakpoints.size() == 1 && isSingletonDomain(domain)) {
            Breakpoint only = breakpoints.get(0);
            return new TimeProfile(domain, ignored -> only.value(), fingerprint, breakpoints);
        }
        if (breakpoints.size() < 2) {
            throw new IllegalArgumentException("piecewise time profile requires at least two breakpoints");
        }
        List<Breakpoint> sorted = new ArrayList<>(breakpoints);
        sorted.sort(Comparator.comparingDouble(Breakpoint::minute));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).minute() <= sorted.get(i - 1).minute()) {
                throw new IllegalArgumentException("time profile breakpoints must be strictly increasing");
            }
        }
        return new TimeProfile(domain, minute -> evaluatePiecewise(minute, sorted), fingerprint, sorted);
    }

    /**
     * Evaluates the profile at a root departure time.
     */
    public double valueAt(int rootDepartureTime) {
        return valueAt((double) rootDepartureTime);
    }

    /**
     * Evaluates the profile at a start time in its domain.
     */
    public double valueAt(double rootDepartureTime) {
        if (!domain.contains(rootDepartureTime)) {
            throw new IllegalArgumentException("time is outside profile domain: " + rootDepartureTime);
        }
        if (!breakpoints.isEmpty()) {
            return evaluatePiecewise(rootDepartureTime, breakpoints);
        }
        return evaluator.applyAsDouble(rootDepartureTime);
    }

    /**
     * Domain where this profile is valid.
     */
    public Domain domain() {
        return domain;
    }

    /**
     * Returns the exact breakpoints when this profile is piecewise-defined.
     */
    public List<Breakpoint> breakpoints() {
        return breakpoints;
    }

    /**
     * True when exact piecewise breakpoints are available.
     */
    public boolean isPiecewise() {
        return !breakpoints.isEmpty();
    }

    /**
     * Stable fingerprint for memoization.
     */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * Restricts this profile to a subdomain.
     */
    public TimeProfile restrict(Domain subdomain) {
        Domain restricted = domain.intersection(subdomain);
        if (restricted.isEmpty()) {
            throw new IllegalArgumentException("restricted time profile domain is empty");
        }
        if (breakpoints.isEmpty()) {
            return new TimeProfile(restricted, evaluator, fingerprint + "|restrict:" + restricted.intervals());
        }
        List<Breakpoint> restrictedBreakpoints = new ArrayList<>();
        for (Domain.Interval interval : restricted.intervals()) {
            addBreakpoint(restrictedBreakpoints, new Breakpoint(interval.start(), valueAt(interval.start())));
            for (Breakpoint breakpoint : breakpoints) {
                if (breakpoint.minute() > interval.start() && breakpoint.minute() < interval.end()) {
                    addBreakpoint(restrictedBreakpoints, breakpoint);
                }
            }
            addBreakpoint(restrictedBreakpoints, new Breakpoint(interval.end(), valueAt(interval.end())));
        }
        return new TimeProfile(restricted, evaluator, fingerprint + "|restrict:" + restricted.intervals(), restrictedBreakpoints);
    }

    /**
     * Returns the image domain over the supplied root domain.
     */
    public Domain imageDomain(Domain rootDomain) {
        Domain restricted = domain.intersection(rootDomain);
        if (restricted.isEmpty()) {
            return Domain.empty();
        }
        if (!breakpoints.isEmpty() || isSingletonDomain(restricted)) {
            List<Domain.Interval> images = new ArrayList<>();
            for (Domain.Interval interval : restricted.intervals()) {
                double startValue = valueAt(interval.start());
                double endValue = valueAt(interval.end());
                images.add(new Domain.Interval(Math.min(startValue, endValue), Math.max(startValue, endValue)));
            }
            return Domain.of(images.toArray(Domain.Interval[]::new));
        }
        List<Domain.Interval> images = new ArrayList<>();
        for (Domain.Interval interval : restricted.intervals()) {
            double startValue = valueAt(interval.start());
            double endValue = valueAt(interval.end());
            images.add(new Domain.Interval(Math.min(startValue, endValue), Math.max(startValue, endValue)));
        }
        return Domain.of(images.toArray(Domain.Interval[]::new));
    }

    /**
     * Returns the root domain where this profile's value lies inside {@code target}.
     */
    public Domain preimage(Domain target, Domain rootDomain) {
        Domain restricted = domain.intersection(rootDomain);
        if (restricted.isEmpty()) {
            return Domain.empty();
        }
        if (breakpoints.isEmpty() && isSingletonDomain(restricted)) {
            Domain.Interval only = restricted.intervals().get(0);
            double value = valueAt(only.start());
            return target.contains(value) ? restricted : Domain.empty();
        }
        List<Domain.Interval> result = new ArrayList<>();
        for (Domain.Interval interval : restricted.intervals()) {
            result.addAll(preimageInInterval(interval, target));
        }
        return result.isEmpty() ? Domain.empty() : Domain.of(result.toArray(Domain.Interval[]::new));
    }

    /**
     * Returns the root domain where {@code this(t) - t <= budget}.
     */
    public Domain domainWhereTravelTimeAtMost(Domain rootDomain, double budget) {
        Domain restricted = domain.intersection(rootDomain);
        if (restricted.isEmpty()) {
            return Domain.empty();
        }
        if (breakpoints.isEmpty() && isSingletonDomain(restricted)) {
            Domain.Interval only = restricted.intervals().get(0);
            return valueAt(only.start()) - only.start() <= budget + 1e-9 ? restricted : Domain.empty();
        }
        List<Domain.Interval> result = new ArrayList<>();
        for (Domain.Interval interval : restricted.intervals()) {
            List<Breakpoint> local = new ArrayList<>();
            local.add(new Breakpoint(interval.start(), valueAt(interval.start()) - interval.start()));
            for (Breakpoint breakpoint : breakpoints) {
                if (breakpoint.minute() > interval.start() && breakpoint.minute() < interval.end()) {
                    local.add(new Breakpoint(breakpoint.minute(), breakpoint.value() - breakpoint.minute()));
                }
            }
            local.add(new Breakpoint(interval.end(), valueAt(interval.end()) - interval.end()));
            result.addAll(preimageAtMostLinear(local, budget));
        }
        return result.isEmpty() ? Domain.empty() : Domain.of(result.toArray(Domain.Interval[]::new));
    }

    /**
     * Composes {@code outer(this(t))} for exact piecewise-linear profiles.
     */
    public TimeProfile compose(TimeProfile outer, String composedFingerprint) {
        if (breakpoints.isEmpty() || outer.breakpoints.isEmpty()) {
            if (!isSingletonDomain(domain)) {
                throw new UnsupportedOperationException("exact piecewise composition requires breakpoint metadata on both profiles");
            }
            return new TimeProfile(domain, t -> outer.valueAt(valueAt(t)), composedFingerprint);
        }
        if ((breakpoints.isEmpty() || outer.breakpoints.isEmpty()) && !(isSingletonDomain(domain) && isSingletonDomain(outer.domain))) {
            throw new UnsupportedOperationException("exact piecewise composition requires breakpoint metadata on both profiles");
        }
        Domain composedDomain = preimage(outer.domain, domain);
        if (composedDomain.isEmpty()) {
            throw new IllegalArgumentException("composition domain is empty");
        }
        List<Breakpoint> composed = composeBreakpoints(outer, composedDomain);
        return TimeProfile.piecewise(composedDomain, composed, composedFingerprint);
    }

    private List<Domain.Interval> preimageInInterval(Domain.Interval interval, Domain target) {
        List<Domain.Interval> result = new ArrayList<>();
        List<Breakpoint> segmentBreakpoints = sliceBreakpoints(interval);
        for (Domain.Interval targetInterval : target.intervals()) {
            result.addAll(preimageLinear(segmentBreakpoints, targetInterval, interval));
        }
        return result;
    }

    private List<Breakpoint> sliceBreakpoints(Domain.Interval interval) {
        List<Breakpoint> local = new ArrayList<>();
        local.add(new Breakpoint(interval.start(), valueAt(interval.start())));
        for (Breakpoint breakpoint : breakpoints) {
            if (breakpoint.minute() > interval.start() && breakpoint.minute() < interval.end()) {
                local.add(breakpoint);
            }
        }
        local.add(new Breakpoint(interval.end(), valueAt(interval.end())));
        return local;
    }

    private List<Domain.Interval> preimageAtMostLinear(List<Breakpoint> localBreakpoints, double target) {
        List<Domain.Interval> result = new ArrayList<>();
        for (int i = 0; i < localBreakpoints.size() - 1; i++) {
            Breakpoint left = localBreakpoints.get(i);
            Breakpoint right = localBreakpoints.get(i + 1);
            double x0 = left.minute();
            double x1 = right.minute();
            double y0 = left.value();
            double y1 = right.value();
            boolean leftFeasible = y0 <= target + 1e-9;
            boolean rightFeasible = y1 <= target + 1e-9;

            if (leftFeasible && rightFeasible) {
                result.add(new Domain.Interval(x0, x1));
                continue;
            }
            if (!leftFeasible && !rightFeasible) {
                continue;
            }
            if (Math.abs(y1 - y0) < 1e-9) {
                continue;
            }

            double slope = (y1 - y0) / (x1 - x0);
            double crossing = x0 + (target - y0) / slope;
            crossing = Math.max(x0, Math.min(x1, crossing));
            if (leftFeasible) {
                result.add(new Domain.Interval(x0, crossing));
            } else {
                result.add(new Domain.Interval(crossing, x1));
            }
        }
        return result;
    }

    private List<Domain.Interval> preimageLinear(List<Breakpoint> localBreakpoints, Domain.Interval target, Domain.Interval interval) {
        List<Domain.Interval> result = new ArrayList<>();
        for (int i = 0; i < localBreakpoints.size() - 1; i++) {
            Breakpoint left = localBreakpoints.get(i);
            Breakpoint right = localBreakpoints.get(i + 1);
            double x0 = left.minute();
            double x1 = right.minute();
            double y0 = left.value();
            double y1 = right.value();
            double segmentMin = Math.min(y0, y1);
            double segmentMax = Math.max(y0, y1);
            double targetMin = target.start();
            double targetMax = target.end();
            if (segmentMax < targetMin - 1e-9 || segmentMin > targetMax + 1e-9) {
                continue;
            }
            if (Math.abs(y1 - y0) < 1e-9) {
                result.add(new Domain.Interval(x0, x1));
                continue;
            }
            double slope = (y1 - y0) / (x1 - x0);
            double enter = x0 + (targetMin - y0) / slope;
            double exit = x0 + (targetMax - y0) / slope;
            double start = Math.max(x0, Math.min(enter, exit));
            double end = Math.min(x1, Math.max(enter, exit));
            if (start <= end + 1e-9) {
                result.add(new Domain.Interval(start, end));
            }
        }
        return result;
    }

    private List<Breakpoint> composeBreakpoints(TimeProfile outer, Domain composedDomain) {
        List<Double> cutPoints = new ArrayList<>();
        for (Breakpoint breakpoint : breakpoints) {
            cutPoints.add(breakpoint.minute());
        }
        for (Breakpoint outerBreakpoint : outer.breakpoints) {
            cutPoints.addAll(preimagePoints(outerBreakpoint.minute(), composedDomain));
        }
        Domain refined = composedDomain.splitAt(cutPoints);
        List<Breakpoint> result = new ArrayList<>();
        for (Domain.Interval interval : refined.intervals()) {
            double start = interval.start();
            double end = interval.end();
            addBreakpoint(result, new Breakpoint(start, outer.valueAt(valueAt(start))));
            addBreakpoint(result, new Breakpoint(end, outer.valueAt(valueAt(end))));
        }
        return result;
    }

    private List<Double> preimagePoints(double target, Domain rootDomain) {
        List<Double> points = new ArrayList<>();
        for (Domain.Interval interval : rootDomain.intervals()) {
            List<Breakpoint> local = sliceBreakpoints(interval);
            for (int i = 0; i < local.size() - 1; i++) {
                Breakpoint left = local.get(i);
                Breakpoint right = local.get(i + 1);
                double min = Math.min(left.value(), right.value());
                double max = Math.max(left.value(), right.value());
                if (target < min - 1e-9 || target > max + 1e-9) {
                    continue;
                }
                if (left.value() == right.value()) {
                    points.add(left.minute());
                    points.add(right.minute());
                    continue;
                }
                double slope = (right.value() - left.value()) / (right.minute() - left.minute());
                double x = left.minute() + (target - left.value()) / slope;
                if (x >= interval.start() - 1e-9 && x <= interval.end() + 1e-9) {
                    points.add(x);
                }
            }
        }
        return points;
    }

    private static double evaluatePiecewise(double minute, List<Breakpoint> breakpoints) {
        if (minute == breakpoints.get(breakpoints.size() - 1).minute()) {
            return breakpoints.get(breakpoints.size() - 1).value();
        }
        int low = 0;
        int high = breakpoints.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Breakpoint breakpoint = breakpoints.get(mid);
            if (minute < breakpoint.minute()) {
                high = mid - 1;
            } else if (minute > breakpoint.minute()) {
                low = mid + 1;
            } else {
                return breakpoint.value();
            }
        }
        int index = Math.max(1, low) - 1;
        Breakpoint left = breakpoints.get(index);
        Breakpoint right = breakpoints.get(index + 1);
        double alpha = (minute - left.minute()) / (right.minute() - left.minute());
        return left.value() + alpha * (right.value() - left.value());
    }

    private static void addBreakpoint(List<Breakpoint> points, Breakpoint point) {
        if (!points.isEmpty()) {
            Breakpoint last = points.get(points.size() - 1);
            if (Math.abs(last.minute() - point.minute()) < 1e-9) {
                points.set(points.size() - 1, point);
                return;
            }
        }
        points.add(point);
    }

    private static boolean isSingletonDomain(Domain domain) {
        return domain.intervals().size() == 1 && domain.intervals().get(0).start() == domain.intervals().get(0).end();
    }
}
