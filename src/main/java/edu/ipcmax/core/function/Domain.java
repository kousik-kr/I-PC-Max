package edu.ipcmax.core.function;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Canonical set of disjoint closed real-valued intervals.
 */
public final class Domain implements Iterable<Integer> {
    private static final double EPSILON = 1e-9;

    private final List<Interval> intervals;

    /**
     * Closed real interval {@code [start,end]}.
     */
    public record Interval(double start, double end) {
        /**
         * Creates a validated closed interval.
         */
        public Interval {
            if (!Double.isFinite(start) || !Double.isFinite(end)) {
                throw new IllegalArgumentException("domain interval bounds must be finite");
            }
            if (end < start) {
                throw new IllegalArgumentException("domain interval end must be >= start");
            }
        }
    }

    private Domain(List<Interval> intervals) {
        this.intervals = List.copyOf(canonicalize(intervals));
    }

    /**
     * Creates a domain from closed intervals.
     */
    public static Domain of(Interval... intervals) {
        return new Domain(List.of(intervals));
    }

    /**
     * Creates one closed interval domain.
     */
    public static Domain closed(double start, double end) {
        return new Domain(List.of(new Interval(start, end)));
    }

    /**
     * Empty domain.
     */
    public static Domain empty() {
        return new Domain(List.of());
    }

    /**
     * Returns the canonical intervals.
     */
    public List<Interval> intervals() {
        return intervals;
    }

    /**
     * Returns all interval endpoints in order.
     */
    public List<Double> breakpoints() {
        List<Double> points = new ArrayList<>(intervals.size() * 2);
        for (Interval interval : intervals) {
            points.add(interval.start());
            points.add(interval.end());
        }
        return List.copyOf(points);
    }

    /**
     * True when this domain has no times.
     */
    public boolean isEmpty() {
        return intervals.isEmpty();
    }

    /**
     * True when the domain contains the minute.
     */
    public boolean contains(int minute) {
        return contains((double) minute);
    }

    /**
     * True when the domain contains the time value.
     */
    public boolean contains(double minute) {
        for (Interval interval : intervals) {
            if (minute + EPSILON >= interval.start() && minute - EPSILON <= interval.end()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Union with another domain.
     */
    public Domain union(Domain other) {
        List<Interval> combined = new ArrayList<>(intervals);
        combined.addAll(other.intervals);
        return new Domain(combined);
    }

    /**
     * Intersection with another domain.
     */
    public Domain intersection(Domain other) {
        List<Interval> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < intervals.size() && j < other.intervals.size()) {
            Interval a = intervals.get(i);
            Interval b = other.intervals.get(j);
            double start = Math.max(a.start(), b.start());
            double end = Math.min(a.end(), b.end());
            if (start <= end + EPSILON) {
                result.add(new Interval(start, end));
            }
            if (a.end() < b.end()) {
                i++;
            } else {
                j++;
            }
        }
        return new Domain(result);
    }

    /**
     * Difference {@code this - other}.
     */
    public Domain difference(Domain other) {
        if (isIntegralDomain(this) && isIntegralDomain(other)) {
            List<Interval> result = new ArrayList<>();
            for (Interval base : intervals) {
                int cursor = (int) Math.round(base.start());
                for (Interval cut : other.intervals) {
                    int cutStart = (int) Math.round(cut.start());
                    int cutEnd = (int) Math.round(cut.end());
                    if (cutEnd < cursor) {
                        continue;
                    }
                    if (cutStart > (int) Math.round(base.end())) {
                        break;
                    }
                    if (cutStart > cursor) {
                        result.add(new Interval(cursor, Math.min((int) Math.round(base.end()), cutStart - 1)));
                    }
                    cursor = Math.max(cursor, cutEnd + 1);
                    if (cursor > (int) Math.round(base.end())) {
                        break;
                    }
                }
                if (cursor <= (int) Math.round(base.end())) {
                    result.add(new Interval(cursor, (int) Math.round(base.end())));
                }
            }
            return new Domain(result);
        }
        List<Interval> result = new ArrayList<>();
        for (Interval base : intervals) {
            double cursor = base.start();
            for (Interval cut : other.intervals) {
                if (cut.end() < cursor - EPSILON) {
                    continue;
                }
                if (cut.start() > base.end() + EPSILON) {
                    break;
                }
                if (cut.start() > cursor + EPSILON) {
                    result.add(new Interval(cursor, Math.min(base.end(), Math.nextDown(cut.start()))));
                }
                cursor = Math.max(cursor, Math.nextUp(cut.end()));
                if (cursor > base.end() + EPSILON) {
                    break;
                }
            }
            if (cursor <= base.end() + EPSILON) {
                result.add(new Interval(cursor, base.end()));
            }
        }
        return new Domain(result);
    }

    /**
     * Alias for {@link #difference(Domain)}.
     */
    public Domain subtract(Domain other) {
        return difference(other);
    }

    /**
     * Restricts this domain to a closed interval.
     */
    public Domain restrict(double start, double end) {
        return intersection(Domain.closed(start, end));
    }

    /**
     * Splits intervals at the supplied breakpoints while preserving exact coverage.
     */
    public Domain splitAt(List<Double> breakpoints) {
        List<Interval> result = new ArrayList<>();
        for (Interval interval : intervals) {
            double cursor = interval.start();
            List<Double> sorted = breakpoints.stream()
                    .filter(point -> point > interval.start() + EPSILON && point < interval.end() - EPSILON)
                    .sorted()
                    .toList();
            for (double point : sorted) {
                result.add(new Interval(cursor, point));
                cursor = point;
            }
            result.add(new Interval(cursor, interval.end()));
        }
        return new Domain(result);
    }

    /**
     * Legacy helper for iterating integer minutes contained in the domain.
     */
    public Iterable<Integer> integerPoints() {
        return this;
    }

    @Override
    public Iterator<Integer> iterator() {
        return new Iterator<>() {
            private int intervalIndex = 0;
            private int next = intervals.isEmpty() ? 0 : (int) Math.ceil(intervals.get(0).start());

            @Override
            public boolean hasNext() {
                while (intervalIndex < intervals.size()) {
                    Interval interval = intervals.get(intervalIndex);
                    int upper = (int) Math.floor(interval.end());
                    if (next <= upper) {
                        return true;
                    }
                    intervalIndex++;
                    if (intervalIndex < intervals.size()) {
                        next = (int) Math.ceil(intervals.get(intervalIndex).start());
                    }
                }
                return false;
            }

            @Override
            public Integer next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return next++;
            }
        };
    }

    @Override
    public String toString() {
        return intervals.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Domain domain)) {
            return false;
        }
        return intervals.equals(domain.intervals);
    }

    @Override
    public int hashCode() {
        return Objects.hash(intervals);
    }

    private static List<Interval> canonicalize(List<Interval> raw) {
        List<Interval> sorted = raw.stream()
                .sorted((a, b) -> Double.compare(a.start(), b.start()))
                .toList();
        List<Interval> result = new ArrayList<>();
        for (Interval interval : sorted) {
            if (result.isEmpty()) {
                result.add(interval);
                continue;
            }
            Interval last = result.get(result.size() - 1);
            if (interval.start() <= last.end() + EPSILON) {
                result.set(result.size() - 1, new Interval(last.start(), Math.max(last.end(), interval.end())));
            } else {
                result.add(interval);
            }
        }
        return result;
    }

    private static boolean isIntegralDomain(Domain domain) {
        for (Interval interval : domain.intervals) {
            if (Math.rint(interval.start()) != interval.start() || Math.rint(interval.end()) != interval.end()) {
                return false;
            }
        }
        return true;
    }
}
