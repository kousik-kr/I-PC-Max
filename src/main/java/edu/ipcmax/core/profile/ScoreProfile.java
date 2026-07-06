package edu.ipcmax.core.profile;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Integer score profile over continuous root departure times.
 */
public final class ScoreProfile {
    /**
     * Exact score interval.
     */
    public record Interval(double startMinute, double endMinute, int value) {
        public Interval {
            if (!Double.isFinite(startMinute) || !Double.isFinite(endMinute)) {
                throw new IllegalArgumentException("score interval bounds must be finite");
            }
            if (endMinute <= startMinute) {
                throw new IllegalArgumentException("score interval end must be > start");
            }
            if (value < 0) {
                throw new IllegalArgumentException("score value cannot be negative");
            }
        }
    }

    @FunctionalInterface
    public interface Evaluator {
        int applyAsInt(double time);
    }

    private final Domain domain;
    private final Evaluator evaluator;
    private final String fingerprint;
    private final List<Interval> intervals;

    /**
     * Creates a score profile.
     */
    public ScoreProfile(Domain domain, Evaluator evaluator, String fingerprint) {
        this(domain, evaluator, fingerprint, List.of());
    }

    private ScoreProfile(Domain domain, Evaluator evaluator, String fingerprint, List<Interval> intervals) {
        if (domain == null || domain.isEmpty()) {
            throw new IllegalArgumentException("score profile domain cannot be null or empty");
        }
        this.domain = domain;
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.intervals = List.copyOf(intervals);
    }

    /**
     * Constant score profile.
     */
    public static ScoreProfile constant(Domain domain, int score) {
        if (isSingletonDomain(domain)) {
            return new ScoreProfile(domain, ignored -> score, "score-constant:" + score + ":" + domain.intervals());
        }
        List<Interval> intervals = new ArrayList<>();
        for (Domain.Interval interval : domain.intervals()) {
            intervals.add(new Interval(interval.start(), interval.end(), score));
        }
        return piecewise(domain, intervals, "score-constant:" + score + ":" + domain.intervals());
    }

    /**
     * Builds an exact piecewise-constant score profile.
     */
    public static ScoreProfile piecewise(Domain domain, List<Interval> intervals, String fingerprint) {
        if (intervals.isEmpty()) {
            throw new IllegalArgumentException("score profile requires at least one interval");
        }
        if (intervals.size() == 1 && isSingletonDomain(domain)) {
            Interval only = intervals.get(0);
            return new ScoreProfile(domain, ignored -> only.value(), fingerprint, intervals);
        }
        List<Interval> copy = new ArrayList<>(intervals);
        copy.sort(Comparator.comparingDouble(Interval::startMinute));
        for (int i = 1; i < copy.size(); i++) {
            if (copy.get(i).startMinute() < copy.get(i - 1).endMinute()) {
                throw new IllegalArgumentException("score intervals must be non-overlapping and ordered");
            }
        }
        return new ScoreProfile(domain, time -> lookup(time, copy), fingerprint, copy);
    }

    /**
     * Evaluates score at a root departure time.
     */
    public int valueAt(int rootDepartureTime) {
        return valueAt((double) rootDepartureTime);
    }

    /**
     * Evaluates score at a start time in its domain.
     */
    public int valueAt(double rootDepartureTime) {
        if (!domain.contains(rootDepartureTime)) {
            throw new IllegalArgumentException("time is outside score profile domain: " + rootDepartureTime);
        }
        if (!intervals.isEmpty()) {
            return lookup(rootDepartureTime, intervals);
        }
        return evaluator.applyAsInt(rootDepartureTime);
    }

    /**
     * Domain where this profile is valid.
     */
    public Domain domain() {
        return domain;
    }

    /**
     * Returns the exact score intervals when available.
     */
    public List<Interval> intervals() {
        return intervals;
    }

    /**
     * Returns score breakpoints when available.
     */
    public List<Double> breakpoints() {
        List<Double> points = new ArrayList<>();
        for (Interval interval : intervals) {
            points.add(interval.startMinute());
            points.add(interval.endMinute());
        }
        return List.copyOf(points);
    }

    /**
     * True when exact piecewise metadata is available.
     */
    public boolean isPiecewise() {
        return !intervals.isEmpty();
    }

    /**
     * Stable fingerprint for memoization.
     */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * Restricts this score profile.
     */
    public ScoreProfile restrict(Domain subdomain) {
        Domain restricted = domain.intersection(subdomain);
        if (restricted.isEmpty()) {
            throw new IllegalArgumentException("restricted score profile domain is empty");
        }
        if (intervals.isEmpty()) {
            return new ScoreProfile(restricted, evaluator, fingerprint + "|restrict:" + restricted.intervals());
        }
        List<Interval> clipped = new ArrayList<>();
        for (Domain.Interval domainInterval : restricted.intervals()) {
            for (Interval interval : intervals) {
                double start = Math.max(domainInterval.start(), interval.startMinute());
                double end = Math.min(domainInterval.end(), interval.endMinute());
                if (start < end) {
                    clipped.add(new Interval(start, end, interval.value()));
                }
            }
        }
        return new ScoreProfile(restricted, evaluator, fingerprint + "|restrict:" + restricted.intervals(), clipped);
    }

    /**
     * Adds another exact score profile over the supplied domain.
     */
    public ScoreProfile add(ScoreProfile other, Domain rootDomain, String composedFingerprint) {
        if (intervals.isEmpty() || other.intervals.isEmpty()) {
            if (isSingletonDomain(rootDomain) && domain.intersection(other.domain).intersection(rootDomain).equals(rootDomain)) {
                int sum = valueAt(rootDomain.intervals().get(0).start()) + other.valueAt(rootDomain.intervals().get(0).start());
                return new ScoreProfile(rootDomain, ignored -> sum, composedFingerprint);
            }
            throw new UnsupportedOperationException("exact score addition requires piecewise metadata on both profiles");
        }
        Domain restricted = domain.intersection(other.domain).intersection(rootDomain);
        if (restricted.isEmpty()) {
            throw new IllegalArgumentException("score addition domain is empty");
        }
        List<Double> breakpoints = new ArrayList<>();
        breakpoints.addAll(restricted.breakpoints());
        breakpoints.addAll(breakpoints());
        breakpoints.addAll(other.breakpoints());
        Domain refined = restricted.splitAt(breakpoints);
        List<Interval> result = new ArrayList<>();
        for (Domain.Interval interval : refined.intervals()) {
            double sample = midpoint(interval);
            result.add(new Interval(interval.start(), interval.end(), valueAt(sample) + other.valueAt(sample)));
        }
        return ScoreProfile.piecewise(restricted, mergeAdjacent(result), composedFingerprint);
    }

    /**
     * Pulls this score profile back through an exact arrival profile.
     */
    public ScoreProfile compose(TimeProfile arrivalProfile, String fingerprint) {
        if (!arrivalProfile.isPiecewise() || intervals.isEmpty()) {
            if (isSingletonDomain(domain) && isSingletonDomain(arrivalProfile.domain())) {
                return new ScoreProfile(arrivalProfile.domain(), ignored -> valueAt(arrivalProfile.valueAt(arrivalProfile.domain().intervals().get(0).start())), fingerprint);
            }
            throw new UnsupportedOperationException("exact score pullback requires piecewise metadata on both profiles");
        }
        Domain feasible = arrivalProfile.domain();
        if (feasible.isEmpty()) {
            throw new IllegalArgumentException("score pullback domain is empty");
        }
        List<Double> cutPoints = new ArrayList<>();
        cutPoints.addAll(feasible.breakpoints());
        for (Interval interval : intervals) {
            cutPoints.addAll(arrivalProfile.preimage(Domain.closed(interval.startMinute(), interval.endMinute()), feasible).breakpoints());
        }
        Domain refined = feasible.splitAt(cutPoints);
        List<Interval> pulledBack = new ArrayList<>();
        for (Domain.Interval interval : refined.intervals()) {
            double sample = midpoint(interval);
            pulledBack.add(new Interval(interval.start(), interval.end(), valueAt(arrivalProfile.valueAt(sample))));
        }
        return ScoreProfile.piecewise(feasible, mergeAdjacent(pulledBack), fingerprint);
    }

    /**
     * Composes this score profile with a root-time arrival profile and an edge score function.
     */
    public static ScoreProfile compose(TimeProfile arrivalProfile, PiecewiseConstFn edgeScore, Domain rootDomain, String fingerprint) {
        if (!arrivalProfile.isPiecewise()) {
            if (isSingletonDomain(rootDomain)) {
                double sample = rootDomain.intervals().get(0).start();
                return new ScoreProfile(rootDomain, ignored -> edgeScore.valueAt(arrivalProfile.valueAt(sample)), fingerprint);
            }
            throw new UnsupportedOperationException("exact score composition requires an exact arrival profile");
        }
        Domain feasible = arrivalProfile.domain().intersection(rootDomain);
        if (feasible.isEmpty()) {
            throw new IllegalArgumentException("score composition domain is empty");
        }
        List<Double> breakpoints = new ArrayList<>();
        breakpoints.addAll(feasible.breakpoints());
        for (PiecewiseConstFn.Interval interval : edgeScore.intervals()) {
            breakpoints.addAll(arrivalProfile.preimage(Domain.closed(interval.startMinute(), interval.endMinute()), feasible).breakpoints());
        }
        Domain refined = feasible.splitAt(breakpoints);
        List<Interval> intervals = new ArrayList<>();
        for (Domain.Interval interval : refined.intervals()) {
            double sample = midpoint(interval);
            int value = edgeScore.valueAt(arrivalProfile.valueAt(sample));
            intervals.add(new Interval(interval.start(), interval.end(), value));
        }
        return ScoreProfile.piecewise(feasible, mergeAdjacent(intervals), fingerprint);
    }

    private static int lookup(double time, List<Interval> intervals) {
        for (int i = 0; i < intervals.size(); i++) {
            Interval interval = intervals.get(i);
            boolean last = i == intervals.size() - 1;
            if (time >= interval.startMinute() && (time < interval.endMinute() || (last && time <= interval.endMinute()))) {
                return interval.value();
            }
        }
        throw new IllegalStateException("unreachable score interval lookup");
    }

    private static double midpoint(Domain.Interval interval) {
        return interval.start() + ((interval.end() - interval.start()) / 2.0);
    }

    private static List<Interval> mergeAdjacent(List<Interval> intervals) {
        List<Interval> merged = new ArrayList<>();
        for (Interval interval : intervals) {
            if (!merged.isEmpty()) {
                Interval last = merged.get(merged.size() - 1);
                if (Math.abs(last.endMinute() - interval.startMinute()) < 1e-9 && last.value() == interval.value()) {
                    merged.set(merged.size() - 1, new Interval(last.startMinute(), interval.endMinute(), last.value()));
                    continue;
                }
            }
            merged.add(interval);
        }
        return merged;
    }

    private static boolean isSingletonDomain(Domain domain) {
        return domain.intervals().size() == 1 && domain.intervals().get(0).start() == domain.intervals().get(0).end();
    }
}
