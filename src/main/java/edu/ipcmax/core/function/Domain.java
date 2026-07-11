package edu.ipcmax.core.function;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Canonical set of disjoint real-valued intervals.
 *
 * <p>Endpoint inclusion is part of the value.  This is important for temporal
 * functions: a score piece is normally half-open while a query interval is
 * normally closed.  The two-argument {@link Interval} constructor is retained
 * as the convenient spelling for a closed interval.</p>
 */
public final class Domain implements Iterable<Integer> {
    /** Fixed decimal precision used by every temporal-domain boundary. */
    public static final int TIME_SCALE = 9;

    private final List<Interval> intervals;
    private final boolean partition;

    /**
     * Real interval with explicit endpoint inclusion.
     */
    public record Interval(
            double start,
            double end,
            boolean startInclusive,
            boolean endInclusive) {
        /**
         * Creates a closed interval {@code [start,end]}.
         */
        public Interval(double start, double end) {
            this(start, end, true, true);
        }

        /**
         * Creates a validated interval.
         */
        public Interval {
            if (!Double.isFinite(start) || !Double.isFinite(end)) {
                throw new IllegalArgumentException("domain interval bounds must be finite");
            }
            start = canonicalTime(start);
            end = canonicalTime(end);
            if (end < start) {
                throw new IllegalArgumentException("domain interval end must be >= start");
            }
            if (end == start && !(startInclusive && endInclusive)) {
                throw new IllegalArgumentException("a singleton interval must include its endpoint");
            }
        }

        /**
         * True when this interval contains {@code value}.
         */
        public boolean contains(double value) {
            if (!Double.isFinite(value)) {
                return false;
            }
            value = canonicalTime(value);
            boolean afterStart = value > start || (value == start && startInclusive);
            boolean beforeEnd = value < end || (value == end && endInclusive);
            return afterStart && beforeEnd;
        }
    }

    private Domain(List<Interval> intervals) {
        this(intervals, false);
    }

    private Domain(List<Interval> intervals, boolean preservePartition) {
        this.intervals = List.copyOf(preservePartition
                ? validatePartition(intervals)
                : canonicalize(intervals));
        this.partition = preservePartition;
    }

    /**
     * Creates a domain from intervals.
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
     * Creates one half-open interval domain {@code [start,end)}.
     */
    public static Domain halfOpen(double start, double end) {
        start = canonicalTime(start);
        end = canonicalTime(end);
        if (end <= start) {
            return empty();
        }
        return new Domain(List.of(new Interval(start, end, true, false)));
    }

    /**
     * Creates one open interval domain {@code (start,end)}.
     */
    public static Domain open(double start, double end) {
        start = canonicalTime(start);
        end = canonicalTime(end);
        if (end <= start) {
            return empty();
        }
        return new Domain(List.of(new Interval(start, end, false, false)));
    }

    /**
     * Empty domain.
     */
    public static Domain empty() {
        return new Domain(List.of());
    }

    /**
     * Returns the canonical intervals, or the ordered cells for a split view.
     */
    public List<Interval> intervals() {
        return intervals;
    }

    /**
     * Returns all distinct interval endpoints in order.
     */
    public List<Double> breakpoints() {
        List<Double> points = new ArrayList<>(intervals.size() * 2);
        for (Interval interval : intervals) {
            addDistinct(points, interval.start());
            addDistinct(points, interval.end());
        }
        points.sort(Double::compare);
        List<Double> distinct = new ArrayList<>(points.size());
        for (double point : points) {
            addDistinct(distinct, point);
        }
        return List.copyOf(distinct);
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
        if (!Double.isFinite(minute)) {
            return false;
        }
        minute = canonicalTime(minute);
        for (Interval interval : intervals) {
            if (interval.contains(minute)) {
                return true;
            }
            if (minute < interval.start()) {
                return false;
            }
        }
        return false;
    }

    /**
     * Union with another domain.
     */
    public Domain union(Domain other) {
        Objects.requireNonNull(other, "other");
        List<Interval> combined = new ArrayList<>(semanticIntervals());
        combined.addAll(other.semanticIntervals());
        return new Domain(combined);
    }

    /**
     * Intersection with another domain.
     */
    public Domain intersection(Domain other) {
        Objects.requireNonNull(other, "other");
        List<Interval> left = semanticIntervals();
        List<Interval> right = other.semanticIntervals();
        List<Interval> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < left.size() && j < right.size()) {
            Interval a = left.get(i);
            Interval b = right.get(j);
            Interval overlap = intersect(a, b);
            if (overlap != null) {
                result.add(overlap);
            }

            int endComparison = compare(a.end(), b.end());
            if (endComparison < 0) {
                i++;
            } else if (endComparison > 0) {
                j++;
            } else {
                i++;
                j++;
            }
        }
        return new Domain(result);
    }

    /**
     * Difference {@code this - other} with exact endpoint ownership.
     */
    public Domain difference(Domain other) {
        Objects.requireNonNull(other, "other");
        List<Interval> fragments = new ArrayList<>(semanticIntervals());
        for (Interval cut : other.semanticIntervals()) {
            List<Interval> next = new ArrayList<>();
            for (Interval fragment : fragments) {
                subtractOne(fragment, cut, next);
            }
            fragments = next;
            if (fragments.isEmpty()) {
                break;
            }
        }
        return new Domain(fragments);
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
     * Splits intervals at the supplied breakpoints while preserving exact
     * coverage.  Returned cells use the canonical convention {@code [a,b)};
     * the last cell inherits the original right-end inclusion.
     *
     * <p>The returned value is a partition view, so touching cells are kept
     * separate instead of immediately being canonicalized back together.</p>
     */
    public Domain splitAt(List<Double> breakpoints) {
        Objects.requireNonNull(breakpoints, "breakpoints");
        List<Interval> result = new ArrayList<>();
        for (Interval interval : semanticIntervals()) {
            List<Double> cuts = new ArrayList<>();
            for (double point : breakpoints) {
                if (!Double.isFinite(point)) {
                    throw new IllegalArgumentException("domain breakpoint must be finite");
                }
                if (compare(point, interval.start()) > 0 && compare(point, interval.end()) < 0) {
                    addDistinct(cuts, point);
                }
            }
            cuts.sort(Double::compare);

            double cursor = interval.start();
            boolean cursorInclusive = interval.startInclusive();
            for (double point : cuts) {
                result.add(new Interval(cursor, point, cursorInclusive, false));
                cursor = point;
                cursorInclusive = true;
            }
            result.add(new Interval(cursor, interval.end(), cursorInclusive, interval.endInclusive()));
        }
        return new Domain(result, true);
    }

    /**
     * Legacy helper for iterating integer minutes contained in the domain.
     */
    public Iterable<Integer> integerPoints() {
        return this;
    }

    @Override
    public Iterator<Integer> iterator() {
        List<Interval> semantic = semanticIntervals();
        return new Iterator<>() {
            private int intervalIndex;
            private int next = semantic.isEmpty() ? 0 : firstInteger(semantic.get(0));

            @Override
            public boolean hasNext() {
                while (intervalIndex < semantic.size()) {
                    Interval interval = semantic.get(intervalIndex);
                    if (next <= lastInteger(interval)) {
                        return true;
                    }
                    intervalIndex++;
                    if (intervalIndex < semantic.size()) {
                        next = firstInteger(semantic.get(intervalIndex));
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
        return semanticIntervals().equals(domain.semanticIntervals());
    }

    @Override
    public int hashCode() {
        return Objects.hash(semanticIntervals());
    }

    private List<Interval> semanticIntervals() {
        return partition ? canonicalize(intervals) : intervals;
    }

    private static List<Interval> validatePartition(List<Interval> raw) {
        List<Interval> sorted = new ArrayList<>(raw);
        sorted.sort(INTERVAL_ORDER);
        for (int i = 1; i < sorted.size(); i++) {
            Interval previous = sorted.get(i - 1);
            Interval current = sorted.get(i);
            int relation = compare(current.start(), previous.end());
            if (relation < 0 || (relation == 0 && previous.endInclusive() && current.startInclusive())) {
                throw new IllegalArgumentException("domain partition cells must not overlap");
            }
        }
        return sorted;
    }

    private static List<Interval> canonicalize(List<Interval> raw) {
        List<Interval> sorted = new ArrayList<>(raw);
        sorted.sort(INTERVAL_ORDER);
        List<Interval> result = new ArrayList<>();
        for (Interval interval : sorted) {
            if (result.isEmpty()) {
                result.add(interval);
                continue;
            }
            Interval last = result.get(result.size() - 1);
            int relation = compare(interval.start(), last.end());
            boolean connected = relation < 0
                    || (relation == 0 && (last.endInclusive() || interval.startInclusive()));
            if (!connected) {
                result.add(interval);
                continue;
            }
            result.set(result.size() - 1, merge(last, interval));
        }
        return result;
    }

    private static Interval merge(Interval left, Interval right) {
        double start = left.start();
        boolean startInclusive = left.startInclusive();
        if (compare(left.start(), right.start()) == 0) {
            start = Math.min(left.start(), right.start());
            startInclusive = left.startInclusive() || right.startInclusive();
        }

        int endComparison = compare(left.end(), right.end());
        if (endComparison > 0) {
            return new Interval(start, left.end(), startInclusive, left.endInclusive());
        }
        if (endComparison < 0) {
            return new Interval(start, right.end(), startInclusive, right.endInclusive());
        }
        return new Interval(start, Math.max(left.end(), right.end()), startInclusive,
                left.endInclusive() || right.endInclusive());
    }

    private static Interval intersect(Interval left, Interval right) {
        int startComparison = compare(left.start(), right.start());
        double start;
        boolean startInclusive;
        if (startComparison > 0) {
            start = left.start();
            startInclusive = left.startInclusive();
        } else if (startComparison < 0) {
            start = right.start();
            startInclusive = right.startInclusive();
        } else {
            start = Math.max(left.start(), right.start());
            startInclusive = left.startInclusive() && right.startInclusive();
        }

        int endComparison = compare(left.end(), right.end());
        double end;
        boolean endInclusive;
        if (endComparison < 0) {
            end = left.end();
            endInclusive = left.endInclusive();
        } else if (endComparison > 0) {
            end = right.end();
            endInclusive = right.endInclusive();
        } else {
            end = Math.min(left.end(), right.end());
            endInclusive = left.endInclusive() && right.endInclusive();
        }

        int relation = compare(start, end);
        if (relation > 0 || (relation == 0 && !(startInclusive && endInclusive))) {
            return null;
        }
        if (relation == 0) {
            double point = (start + end) / 2.0;
            return new Interval(point, point);
        }
        return new Interval(start, end, startInclusive, endInclusive);
    }

    private static void subtractOne(Interval base, Interval cut, List<Interval> output) {
        Interval overlap = intersect(base, cut);
        if (overlap == null) {
            output.add(base);
            return;
        }

        addIfNonEmpty(output, base.start(), overlap.start(), base.startInclusive(), !overlap.startInclusive());
        addIfNonEmpty(output, overlap.end(), base.end(), !overlap.endInclusive(), base.endInclusive());
    }

    private static void addIfNonEmpty(
            List<Interval> output,
            double start,
            double end,
            boolean startInclusive,
            boolean endInclusive) {
        int relation = compare(start, end);
        if (relation < 0) {
            output.add(new Interval(start, end, startInclusive, endInclusive));
        } else if (relation == 0 && startInclusive && endInclusive) {
            double point = (start + end) / 2.0;
            output.add(new Interval(point, point));
        }
    }

    private static int firstInteger(Interval interval) {
        int first = (int) Math.ceil(interval.start());
        if (!interval.startInclusive() && first == interval.start()) {
            first++;
        }
        return first;
    }

    private static int lastInteger(Interval interval) {
        int last = (int) Math.floor(interval.end());
        if (!interval.endInclusive() && last == interval.end()) {
            last--;
        }
        return last;
    }

    private static void addDistinct(List<Double> points, double point) {
        point = canonicalTime(point);
        for (double existing : points) {
            if (compare(existing, point) == 0) {
                return;
            }
        }
        points.add(point);
    }

    private static int compare(double left, double right) {
        return Double.compare(canonicalTime(left), canonicalTime(right));
    }

    /** Converts a temporal value to the repository's fixed decimal representation. */
    public static double canonicalTime(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("time value must be finite");
        }
        return BigDecimal.valueOf(value)
                .setScale(TIME_SCALE, RoundingMode.HALF_EVEN)
                .doubleValue();
    }

    /** Exact equality after conversion to the fixed decimal representation. */
    public static boolean sameTime(double left, double right) {
        return Double.compare(canonicalTime(left), canonicalTime(right)) == 0;
    }

    private static final Comparator<Interval> INTERVAL_ORDER = (left, right) -> {
        int startComparison = Double.compare(left.start(), right.start());
        if (startComparison != 0) {
            return startComparison;
        }
        if (left.startInclusive() != right.startInclusive()) {
            return left.startInclusive() ? -1 : 1;
        }
        int endComparison = Double.compare(left.end(), right.end());
        if (endComparison != 0) {
            return endComparison;
        }
        return Boolean.compare(right.endInclusive(), left.endInclusive());
    };
}
