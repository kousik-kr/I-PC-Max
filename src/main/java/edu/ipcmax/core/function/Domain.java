package edu.ipcmax.core.function;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Canonical set of disjoint closed integer-minute intervals.
 */
public final class Domain implements Iterable<Integer> {
    private final List<Interval> intervals;

    /**
     * Closed integer interval {@code [start,end]}.
     */
    public record Interval(int start, int end) {
        /**
         * Creates a validated closed interval.
         */
        public Interval {
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
    public static Domain closed(int start, int end) {
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
     * True when this domain has no times.
     */
    public boolean isEmpty() {
        return intervals.isEmpty();
    }

    /**
     * True when the domain contains the minute.
     */
    public boolean contains(int minute) {
        for (Interval interval : intervals) {
            if (minute >= interval.start() && minute <= interval.end()) {
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
            int start = Math.max(a.start(), b.start());
            int end = Math.min(a.end(), b.end());
            if (start <= end) {
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
        List<Interval> result = new ArrayList<>();
        for (Interval base : intervals) {
            int cursor = base.start();
            for (Interval cut : other.intervals) {
                if (cut.end() < cursor) {
                    continue;
                }
                if (cut.start() > base.end()) {
                    break;
                }
                if (cut.start() > cursor) {
                    result.add(new Interval(cursor, Math.min(base.end(), cut.start() - 1)));
                }
                cursor = Math.max(cursor, cut.end() + 1);
                if (cursor > base.end()) {
                    break;
                }
            }
            if (cursor <= base.end()) {
                result.add(new Interval(cursor, base.end()));
            }
        }
        return new Domain(result);
    }

    /**
     * Restricts this domain to a closed interval.
     */
    public Domain restrict(int start, int end) {
        return intersection(Domain.closed(start, end));
    }

    /**
     * Splits intervals at the supplied breakpoints while preserving exact coverage.
     */
    public Domain splitAt(List<Integer> breakpoints) {
        List<Interval> result = new ArrayList<>();
        for (Interval interval : intervals) {
            int cursor = interval.start();
            List<Integer> sorted = breakpoints.stream()
                    .filter(point -> point > interval.start() && point <= interval.end())
                    .sorted()
                    .toList();
            for (int point : sorted) {
                result.add(new Interval(cursor, point - 1));
                cursor = point;
            }
            result.add(new Interval(cursor, interval.end()));
        }
        return new Domain(result);
    }

    @Override
    public Iterator<Integer> iterator() {
        return new Iterator<>() {
            private int intervalIndex = 0;
            private int next = intervals.isEmpty() ? 0 : intervals.get(0).start();

            @Override
            public boolean hasNext() {
                return intervalIndex < intervals.size();
            }

            @Override
            public Integer next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                int value = next;
                Interval interval = intervals.get(intervalIndex);
                if (next == interval.end()) {
                    intervalIndex++;
                    if (intervalIndex < intervals.size()) {
                        next = intervals.get(intervalIndex).start();
                    }
                } else {
                    next++;
                }
                return value;
            }
        };
    }

    private static List<Interval> canonicalize(List<Interval> raw) {
        List<Interval> sorted = raw.stream()
                .sorted((a, b) -> Integer.compare(a.start(), b.start()))
                .toList();
        List<Interval> result = new ArrayList<>();
        for (Interval interval : sorted) {
            if (result.isEmpty()) {
                result.add(interval);
                continue;
            }
            Interval last = result.get(result.size() - 1);
            if (interval.start() <= last.end() + 1) {
                result.set(result.size() - 1, new Interval(last.start(), Math.max(last.end(), interval.end())));
            } else {
                result.add(interval);
            }
        }
        return result;
    }
}
