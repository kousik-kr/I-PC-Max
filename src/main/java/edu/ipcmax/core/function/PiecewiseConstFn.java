package edu.ipcmax.core.function;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * Piecewise-constant integer score function.
 */
public final class PiecewiseConstFn {
    private final List<Interval> intervals;

    /**
     * A half-open score interval {@code [startMinute,endMinute)}.
     */
    public record Interval(double startMinute, double endMinute, int value) {
        /**
         * Creates a validated interval.
         */
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

    /**
     * Creates a piecewise-constant score function from contiguous intervals.
     */
    public PiecewiseConstFn(List<Interval> intervals) {
        if (intervals.isEmpty()) {
            throw new IllegalArgumentException("score function requires at least one interval");
        }
        List<Interval> copy = new ArrayList<>(intervals);
        copy.sort(Comparator.comparingDouble(Interval::startMinute));
        for (int i = 1; i < copy.size(); i++) {
            if (copy.get(i).startMinute() != copy.get(i - 1).endMinute()) {
                throw new IllegalArgumentException("score intervals must be contiguous and sorted");
            }
        }
        this.intervals = List.copyOf(copy);
    }

    /**
     * Returns a full-day zero score function.
     */
    public static PiecewiseConstFn zeroFullDay() {
        return new PiecewiseConstFn(List.of(new Interval(0, 1440, 0)));
    }

    /**
     * Returns the immutable interval list.
     */
    public List<Interval> intervals() {
        return intervals;
    }

    /**
     * Evaluates the score at {@code minute}. The final endpoint belongs to the last interval.
     */
    public int valueAt(double minute) {
        if (minute < intervals.get(0).startMinute() || minute > intervals.get(intervals.size() - 1).endMinute()) {
            throw new IllegalArgumentException("time is outside score function domain: " + minute);
        }
        for (int i = 0; i < intervals.size(); i++) {
            Interval interval = intervals.get(i);
            boolean last = i == intervals.size() - 1;
            if (minute >= interval.startMinute() && (minute < interval.endMinute() || (last && minute == interval.endMinute()))) {
                return interval.value();
            }
        }
        throw new IllegalStateException("unreachable score interval lookup");
    }

    /**
     * Returns true when score can be positive over the given range.
     */
    public boolean hasPositiveValueIn(TimeRange range) {
        for (Interval interval : intervals) {
            if (interval.value() > 0 && new TimeRange(interval.startMinute(), interval.endMinute()).overlaps(range)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Maximum score value in this function.
     */
    public int maxValue() {
        int max = 0;
        for (Interval interval : intervals) {
            max = Math.max(max, interval.value());
        }
        return max;
    }

    /**
     * Restricts this function to a discrete domain by clipping intervals.
     */
    public PiecewiseConstFn restrict(Domain domain) {
        List<Interval> restricted = new ArrayList<>();
        for (Domain.Interval domainInterval : domain.intervals()) {
            double domainStart = domainInterval.start();
            double domainEndExclusive = domainInterval.end() + 1.0;
            for (Interval interval : intervals) {
                double start = Math.max(domainStart, interval.startMinute());
                double end = Math.min(domainEndExclusive, interval.endMinute());
                if (start < end) {
                    appendInterval(restricted, new Interval(start, end, interval.value()));
                }
            }
        }
        if (restricted.isEmpty()) {
            throw new IllegalArgumentException("score restriction is empty");
        }
        return new PiecewiseConstFn(restricted);
    }

    /**
     * Adds two score functions over a discrete domain using exact point sampling.
     */
    public PiecewiseConstFn add(PiecewiseConstFn other, Domain domain) {
        List<Interval> result = new ArrayList<>();
        Integer start = null;
        Integer previous = null;
        Integer currentValue = null;
        for (int t : domain) {
            int value = valueAt(t) + other.valueAt(t);
            if (start == null) {
                start = t;
                currentValue = value;
            } else if (value != currentValue || previous + 1 != t) {
                result.add(new Interval(start, previous + 1.0, currentValue));
                start = t;
                currentValue = value;
            }
            previous = t;
        }
        if (start != null) {
            result.add(new Interval(start, previous + 1.0, currentValue));
        }
        return new PiecewiseConstFn(result);
    }

    /**
     * Composes this score function with a time profile over a discrete root domain.
     */
    public PiecewiseConstFn compose(DoubleUnaryOperator timeProfile, Domain domain) {
        List<Interval> result = new ArrayList<>();
        Integer start = null;
        Integer previous = null;
        Integer currentValue = null;
        for (int t : domain) {
            int value = valueAt(timeProfile.applyAsDouble(t));
            if (start == null) {
                start = t;
                currentValue = value;
            } else if (value != currentValue || previous + 1 != t) {
                result.add(new Interval(start, previous + 1.0, currentValue));
                start = t;
                currentValue = value;
            }
            previous = t;
        }
        if (start != null) {
            result.add(new Interval(start, previous + 1.0, currentValue));
        }
        return new PiecewiseConstFn(result);
    }

    /**
     * Maximum score over a discrete domain.
     */
    public int maxValue(Domain domain) {
        int max = 0;
        for (int t : domain) {
            max = Math.max(max, valueAt(t));
        }
        return max;
    }

    /**
     * Breakpoint times.
     */
    public List<Double> breakpoints() {
        List<Double> points = new ArrayList<>();
        points.add(intervals.get(0).startMinute());
        for (Interval interval : intervals) {
            points.add(interval.endMinute());
        }
        return List.copyOf(points);
    }

    private static void appendInterval(List<Interval> intervals, Interval interval) {
        if (!intervals.isEmpty()) {
            Interval last = intervals.get(intervals.size() - 1);
            if (last.endMinute() == interval.startMinute() && last.value() == interval.value()) {
                intervals.set(intervals.size() - 1, new Interval(last.startMinute(), interval.endMinute(), last.value()));
                return;
            }
        }
        intervals.add(interval);
    }
}
