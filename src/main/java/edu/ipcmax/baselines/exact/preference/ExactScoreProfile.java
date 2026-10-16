package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.graph.Edge;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Piecewise-constant score values on exact rational, endpoint-owned domains. */
public final class ExactScoreProfile {
    public record Segment(BigInteger value, ExactTemporalDomain.Interval interval) {
        public Segment {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(interval, "interval");
            if (value.signum() < 0) throw new IllegalArgumentException("score cannot be negative");
        }
    }

    private static final Comparator<Segment> ORDER = Comparator
            .comparing((Segment segment) -> segment.interval().start())
            .thenComparing(segment -> !segment.interval().startInclusive())
            .thenComparing(segment -> segment.interval().end());

    private final List<Segment> segments;
    private final ExactTemporalDomain domain;

    private ExactScoreProfile(Collection<Segment> supplied) {
        List<Segment> ordered = new ArrayList<>(supplied);
        ordered.sort(ORDER);
        ExactTime furthestEnd = null;
        boolean furthestEndIncluded = false;
        ExactTemporalDomain all = ExactTemporalDomain.empty();
        for (Segment segment : ordered) {
            var interval = segment.interval();
            if (furthestEnd != null) {
                int order = interval.start().compareTo(furthestEnd);
                if (order < 0 || (order == 0 && interval.startInclusive() && furthestEndIncluded)) {
                    throw new IllegalArgumentException("score segments overlap on owned endpoints");
                }
                int endOrder = interval.end().compareTo(furthestEnd);
                if (endOrder > 0) {
                    furthestEnd = interval.end();
                    furthestEndIncluded = interval.endInclusive();
                } else if (endOrder == 0) {
                    furthestEndIncluded |= interval.endInclusive();
                }
            } else {
                furthestEnd = interval.end();
                furthestEndIncluded = interval.endInclusive();
            }
            all = all.union(ExactTemporalDomain.of(interval));
        }
        this.segments = List.copyOf(ordered);
        this.domain = all;
    }

    public static ExactScoreProfile empty() {
        return new ExactScoreProfile(List.of());
    }

    public static ExactScoreProfile ofSegments(Collection<Segment> segments) {
        return new ExactScoreProfile(segments);
    }

    public static ExactScoreProfile constant(ExactTemporalDomain domain, BigInteger value) {
        List<Segment> segments = domain.intervals().stream()
                .map(interval -> new Segment(value, interval)).toList();
        return ofSegments(segments);
    }

    /** Converts already-canonicalized input score pieces to exact rational supports. */
    public static ExactScoreProfile edgeScore(Edge edge) {
        PiecewiseConstFn function = edge.scoreFunction();
        List<PiecewiseConstFn.Interval> pieces = function.intervals();
        List<Segment> result = new ArrayList<>();
        ExactTemporalDomain edgeDomain = ExactTemporalDomain.fromLegacy(function.domain());
        for (int i = 0; i < pieces.size(); i++) {
            PiecewiseConstFn.Interval piece = pieces.get(i);
            ExactTime start = ExactTime.fromCanonicalDouble(piece.startMinute());
            ExactTime end = ExactTime.fromCanonicalDouble(piece.endMinute());
            boolean endIncluded = i + 1 == pieces.size()
                    || pieces.get(i + 1).startMinute() != piece.endMinute();
            ExactTemporalDomain.Interval interval = piece.startMinute() == piece.endMinute()
                    ? ExactTemporalDomain.Interval.singleton(start)
                    : new ExactTemporalDomain.Interval(start, end, true, endIncluded);
            for (ExactTemporalDomain.Interval support : edgeDomain.intervals()) {
                ExactTemporalDomain.Interval supported = interval.intersection(support);
                if (supported != null) result.add(new Segment(BigInteger.valueOf(piece.value()), supported));
            }
        }
        return ofSegments(result);
    }

    public List<Segment> segments() { return segments; }
    public ExactTemporalDomain domain() { return domain; }
    public boolean isEmpty() { return segments.isEmpty(); }
    public long pieceCount() { return segments.size(); }

    public BigInteger valueAt(ExactTime time) {
        for (Segment segment : segments) {
            if (segment.interval().contains(time)) return segment.value();
        }
        throw new IllegalArgumentException("time is outside exact score support: " + time);
    }

    /** Pulls score steps back through every exact affine cell of an arrival profile. */
    public ExactScoreProfile pullback(ExactArrivalProfile mapping) {
        List<Segment> result = new ArrayList<>();
        for (var affine : mapping.segments()) {
            for (Segment score : segments) {
                ExactTemporalDomain.Interval preimage = preimage(
                        affine.slope(), affine.intercept(), score.interval(), affine.interval());
                if (preimage == null) continue;
                ExactTemporalDomain.Interval overlap = affine.interval().intersection(preimage);
                if (overlap != null) result.add(new Segment(score.value(), overlap));
            }
        }
        return ofSegments(result);
    }

    /** Pointwise sum, using unbounded integers for the accumulated score. */
    public ExactScoreProfile plus(ExactScoreProfile other) {
        List<Segment> result = new ArrayList<>();
        for (Segment left : segments) {
            for (Segment right : other.segments) {
                ExactTemporalDomain.Interval overlap = left.interval().intersection(right.interval());
                if (overlap != null) result.add(new Segment(left.value().add(right.value()), overlap));
            }
        }
        return ofSegments(result);
    }

    public ExactScoreProfile restrict(ExactTemporalDomain requested) {
        List<Segment> result = new ArrayList<>();
        for (Segment segment : segments) {
            for (ExactTemporalDomain.Interval interval : requested.intervals()) {
                ExactTemporalDomain.Interval overlap = segment.interval().intersection(interval);
                if (overlap != null) result.add(new Segment(segment.value(), overlap));
            }
        }
        return ofSegments(result);
    }

    public int maximumRationalComponentBitLength() {
        int result = 0;
        for (Segment segment : segments) {
            result = Math.max(result, Math.max(segment.value().abs().bitLength(),
                    Math.max(segment.interval().start().numerator().abs().bitLength(),
                            Math.max(segment.interval().start().denominator().bitLength(),
                                    Math.max(segment.interval().end().numerator().abs().bitLength(),
                                            segment.interval().end().denominator().bitLength())))));
        }
        return result;
    }

    private static ExactTemporalDomain.Interval preimage(
            ExactTime slope, ExactTime intercept, ExactTemporalDomain.Interval target,
            ExactTemporalDomain.Interval source) {
        if (slope.signum() == 0) return target.contains(intercept) ? source : null;
        ExactTime low = target.start().subtract(intercept).divide(slope);
        ExactTime high = target.end().subtract(intercept).divide(slope);
        boolean lowIncluded = target.startInclusive();
        boolean highIncluded = target.endInclusive();
        if (slope.signum() < 0) {
            ExactTime swap = low; low = high; high = swap;
            boolean included = lowIncluded; lowIncluded = highIncluded; highIncluded = included;
        }
        return new ExactTemporalDomain.Interval(low, high, lowIncluded, highIncluded);
    }
}
