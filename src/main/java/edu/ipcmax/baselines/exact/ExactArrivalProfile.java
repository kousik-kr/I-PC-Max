package edu.ipcmax.baselines.exact;

import edu.ipcmax.baselines.ExactTemporalProfile;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.profile.TemporalProfileWork;
import edu.ipcmax.core.profile.TimeProfile;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Piecewise-affine function whose support and all coefficients are rational. */
public final class ExactArrivalProfile {
    private static final Comparator<ExactAffineSegment> SEGMENT_ORDER = Comparator
            .comparing((ExactAffineSegment segment) -> segment.interval().start())
            .thenComparing(segment -> !segment.interval().startInclusive())
            .thenComparing(segment -> segment.interval().end());

    private final List<ExactAffineSegment> segments;
    private final ExactTemporalDomain domain;

    private ExactArrivalProfile(Collection<ExactAffineSegment> supplied) {
        List<ExactAffineSegment> ordered = new ArrayList<>(supplied);
        ordered.sort(SEGMENT_ORDER);
        ExactTemporalDomain all = ExactTemporalDomain.empty();
        ExactTime furthestEnd = null;
        boolean furthestEndIncluded = false;
        for (ExactAffineSegment segment : ordered) {
            ExactTemporalDomain.Interval interval = segment.interval();
            if (furthestEnd != null) {
                int begins = interval.start().compareTo(furthestEnd);
                if (begins < 0 || (begins == 0 && interval.startInclusive() && furthestEndIncluded)) {
                    throw new IllegalArgumentException("exact profile segments must have disjoint owned domains");
                }
                int extendsEnd = interval.end().compareTo(furthestEnd);
                if (extendsEnd > 0) {
                    furthestEnd = interval.end();
                    furthestEndIncluded = interval.endInclusive();
                } else if (extendsEnd == 0) {
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

    public static ExactArrivalProfile empty() {
        return new ExactArrivalProfile(List.of());
    }

    public static ExactArrivalProfile ofSegments(Collection<ExactAffineSegment> segments) {
        return new ExactArrivalProfile(segments);
    }

    public static ExactArrivalProfile identity(Domain domain) {
        return identity(ExactTemporalDomain.fromLegacy(domain));
    }

    public static ExactArrivalProfile identity(ExactTemporalDomain domain) {
        List<ExactAffineSegment> result = new ArrayList<>();
        for (ExactTemporalDomain.Interval interval : domain.intervals()) {
            result.add(new ExactAffineSegment(ExactTime.ONE, ExactTime.ZERO, interval));
        }
        return ofSegments(result);
    }

    /** Converts the accepted edge decimals into exact affine arrival pieces. */
    public static ExactArrivalProfile edgeArrival(Edge edge) {
        Objects.requireNonNull(edge, "edge");
        PiecewiseLinearFn function = edge.travelTimeFunction();
        List<PiecewiseLinearFn.Breakpoint> points = function.breakpoints();
        List<ExactTime> knotTimes = points.stream()
                .map(point -> ExactTime.fromCanonicalDouble(point.minute())).toList();
        List<ExactTime> knotValues = points.stream()
                .map(point -> ExactTime.fromCanonicalDouble(point.value())).toList();
        List<ExactAffineSegment> result = new ArrayList<>();
        ExactTemporalDomain edgeDomain = ExactTemporalDomain.fromLegacy(function.domain());
        for (ExactTemporalDomain.Interval component : edgeDomain.intervals()) {
            TreeSet<ExactTime> cuts = new TreeSet<>();
            cuts.add(component.start());
            cuts.add(component.end());
            for (ExactTime knot : knotTimes) {
                if (knot.compareTo(component.start()) > 0 && knot.compareTo(component.end()) < 0) cuts.add(knot);
            }
            List<ExactTime> orderedCuts = List.copyOf(cuts);
            if (orderedCuts.size() == 1) {
                ExactTime time = orderedCuts.getFirst();
                ExactTime travel = exactTravelAt(time, knotTimes, knotValues);
                result.add(new ExactAffineSegment(ExactTime.ZERO, time.add(travel), component));
                continue;
            }
            for (int i = 0; i + 1 < orderedCuts.size(); i++) {
                ExactTime start = orderedCuts.get(i);
                ExactTime end = orderedCuts.get(i + 1);
                if (start.equals(end)) continue;
                ExactTime midpoint = start.add(end).divide(ExactTime.of(2));
                int knot = interpolationSegment(midpoint, knotTimes);
                ExactTime travelSlope = knotValues.get(knot + 1).subtract(knotValues.get(knot))
                        .divide(knotTimes.get(knot + 1).subtract(knotTimes.get(knot)));
                ExactTime travelIntercept = knotValues.get(knot).subtract(travelSlope.multiply(knotTimes.get(knot)));
                boolean startIncluded = i == 0 ? component.startInclusive() : true;
                boolean endIncluded = i == orderedCuts.size() - 2 ? component.endInclusive() : false;
                ExactTemporalDomain.Interval interval = new ExactTemporalDomain.Interval(
                        start, end, startIncluded, endIncluded);
                result.add(new ExactAffineSegment(
                        ExactTime.ONE.add(travelSlope), travelIntercept, interval));
            }
        }
        return ofSegments(result);
    }

    public List<ExactAffineSegment> segments() {
        return segments;
    }

    public ExactTemporalDomain domain() {
        return domain;
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }

    public long pieceCount() {
        return segments.size();
    }

    public long breakpointCount() {
        TreeSet<ExactTime> points = new TreeSet<>();
        for (ExactAffineSegment segment : segments) {
            points.add(segment.interval().start());
            points.add(segment.interval().end());
        }
        return points.size();
    }

    /** Largest bit length among every numerator and denominator in this profile. */
    public int maximumRationalComponentBitLength() {
        int result = 0;
        for (ExactAffineSegment segment : segments) {
            result = Math.max(result, Math.max(
                    Math.max(segment.slope().numerator().abs().bitLength(), segment.slope().denominator().bitLength()),
                    Math.max(segment.intercept().numerator().abs().bitLength(), segment.intercept().denominator().bitLength())));
            result = Math.max(result, Math.max(
                    Math.max(segment.interval().start().numerator().abs().bitLength(), segment.interval().start().denominator().bitLength()),
                    Math.max(segment.interval().end().numerator().abs().bitLength(), segment.interval().end().denominator().bitLength())));
        }
        return result;
    }

    /** Double query inputs cross the existing canonicalization boundary once here. */
    public ExactTime valueAt(double publicDepartureTime) {
        double canonical = Domain.canonicalTime(publicDepartureTime);
        return valueAtExact(ExactTime.fromCanonicalDouble(canonical));
    }

    public ExactTime valueAtExact(ExactTime time) {
        for (ExactAffineSegment segment : segments) {
            if (segment.interval().contains(time)) return segment.valueAt(time);
        }
        throw new IllegalArgumentException("time is outside exact profile support: " + time);
    }

    public ExactArrivalProfile restrict(Domain requested) {
        return restrict(ExactTemporalDomain.fromLegacy(requested));
    }

    public ExactArrivalProfile restrict(ExactTemporalDomain requested) {
        List<ExactAffineSegment> result = new ArrayList<>();
        for (ExactAffineSegment segment : segments) {
            for (ExactTemporalDomain.Interval interval : requested.intervals()) {
                ExactAffineSegment restricted = segment.restrict(interval);
                if (restricted != null) result.add(restricted);
            }
        }
        return ofSegments(result);
    }

    /** Exact root domain on which this profile maps into {@code target}. */
    public ExactTemporalDomain preimage(ExactTemporalDomain target) {
        Objects.requireNonNull(target, "target");
        List<ExactTemporalDomain.Interval> result = new ArrayList<>();
        for (ExactAffineSegment segment : segments) {
            for (ExactTemporalDomain.Interval targetInterval : target.intervals()) {
                ExactTemporalDomain.Interval roots = preimage(segment, targetInterval);
                if (roots == null) continue;
                ExactTemporalDomain.Interval overlap = segment.interval().intersection(roots);
                if (overlap != null) result.add(overlap);
            }
        }
        return ExactTemporalDomain.of(result);
    }

    /** Returns {@code outer(this(t))} on exactly the preimage-supported domain. */
    public ExactArrivalProfile composeAfter(ExactArrivalProfile outer) {
        Objects.requireNonNull(outer, "outer");
        if (isEmpty() || outer.isEmpty()) return empty();
        TemporalProfileWork.increment("thop_temporal_compositions");
        List<ExactAffineSegment> result = new ArrayList<>();
        for (ExactAffineSegment inner : segments) {
            for (ExactAffineSegment after : outer.segments) {
                ExactTemporalDomain.Interval preimage = preimage(inner, after.interval());
                if (preimage == null) continue;
                ExactTemporalDomain.Interval overlap = inner.interval().intersection(preimage);
                if (overlap != null) result.add(inner.then(after, overlap));
            }
        }
        return ofSegments(result);
    }

    public ExactArrivalProfile pointwiseMinimum(ExactArrivalProfile other) {
        return ExactProfileEnvelope.minimum(this, other);
    }

    public ExactArrivalProfile asTravelTime() {
        List<ExactAffineSegment> result = segments.stream()
                .map(segment -> new ExactAffineSegment(
                        segment.slope().subtract(ExactTime.ONE), segment.intercept(), segment.interval()))
                .toList();
        return ofSegments(result);
    }

    /** Converts a travel-time function {@code w(t)} to arrival {@code t+w(t)}. */
    public ExactArrivalProfile asArrivalProfile() {
        List<ExactAffineSegment> result = segments.stream()
                .map(segment -> new ExactAffineSegment(
                        segment.slope().add(ExactTime.ONE), segment.intercept(), segment.interval()))
                .toList();
        return ofSegments(result);
    }

    public ExactTemporalDomain imageDomain() {
        List<ExactTemporalDomain.Interval> images = new ArrayList<>();
        for (ExactAffineSegment segment : segments) {
            ExactTemporalDomain.Interval input = segment.interval();
            if (segment.slope().signum() == 0) {
                images.add(ExactTemporalDomain.Interval.singleton(segment.valueAtClosure(input.start())));
                continue;
            }
            ExactTime first = segment.valueAtClosure(input.start());
            ExactTime last = segment.valueAtClosure(input.end());
            if (segment.slope().signum() > 0) {
                images.add(new ExactTemporalDomain.Interval(
                        first, last, input.startInclusive(), input.endInclusive()));
            } else {
                images.add(new ExactTemporalDomain.Interval(
                        last, first, input.endInclusive(), input.startInclusive()));
            }
        }
        return ExactTemporalDomain.of(images);
    }

    public ExactTime minimumValueOnClosure() {
        if (isEmpty()) throw new IllegalStateException("empty profile has no finite minimum");
        ExactTime result = null;
        for (ExactAffineSegment segment : segments) {
            ExactTime start = segment.valueAtClosure(segment.interval().start());
            ExactTime end = segment.valueAtClosure(segment.interval().end());
            if (result == null || start.compareTo(result) < 0) result = start;
            if (end.compareTo(result) < 0) result = end;
        }
        return result;
    }

    public boolean sameFunction(ExactArrivalProfile other) {
        return ExactProfileEnvelope.sameFunction(this, other);
    }

    public boolean dominates(ExactArrivalProfile other) {
        return ExactProfileEnvelope.dominates(this, other);
    }

    /** Rounded compatibility projection. Exact callers should use this profile directly. */
    public ExactTemporalProfile roundedLegacyProjection() {
        ExactTemporalProfile result = ExactTemporalProfile.empty();
        for (ExactAffineSegment segment : segments) {
            ExactTemporalDomain.Interval interval = segment.interval();
            double start = interval.start().toDisplayDouble();
            double end = interval.end().toDisplayDouble();
            boolean startIncluded = interval.startInclusive();
            boolean endIncluded = interval.endInclusive();
            if (start > end) continue;
            if (start == end) {
                if (!(startIncluded && endIncluded)) continue;
                startIncluded = true;
                endIncluded = true;
            }
            Domain domain = Domain.of(new Domain.Interval(start, end, startIncluded, endIncluded));
            List<TimeProfile.Breakpoint> points = start == end
                    ? List.of(new TimeProfile.Breakpoint(start,
                            segment.valueAtClosure(interval.start()).toDisplayDouble()))
                    : List.of(
                            new TimeProfile.Breakpoint(start, segment.valueAtClosure(interval.start()).toDisplayDouble()),
                            new TimeProfile.Breakpoint(end, segment.valueAtClosure(interval.end()).toDisplayDouble()));
            TimeProfile projected = TimeProfile.piecewise(domain, points, "rounded-exact-thop-projection");
            ExactTemporalProfile cell = ExactTemporalProfile.from(projected);
            result = result.isEmpty() ? cell : result.pointwiseMinimum(cell);
        }
        return result;
    }

    /** Writes exact segment support, affine coefficients and ownership. */
    public void writeTo(DataOutput output) throws IOException {
        output.writeInt(segments.size());
        for (ExactAffineSegment segment : segments) {
            ExactTemporalDomain.Interval interval = segment.interval();
            writeTime(output, interval.start());
            writeTime(output, interval.end());
            output.writeBoolean(interval.startInclusive());
            output.writeBoolean(interval.endInclusive());
            writeTime(output, segment.slope());
            writeTime(output, segment.intercept());
        }
    }

    public static ExactArrivalProfile readFrom(DataInput input) throws IOException {
        int count = input.readInt();
        if (count < 0) throw new IOException("negative exact profile segment count");
        List<ExactAffineSegment> segments = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ExactTime start = readTime(input);
            ExactTime end = readTime(input);
            boolean startInclusive = input.readBoolean();
            boolean endInclusive = input.readBoolean();
            ExactTime slope = readTime(input);
            ExactTime intercept = readTime(input);
            segments.add(new ExactAffineSegment(slope, intercept,
                    new ExactTemporalDomain.Interval(start, end, startInclusive, endInclusive)));
        }
        try {
            return ofSegments(segments);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("invalid exact profile segments", malformed);
        }
    }

    static ExactTemporalDomain.Interval preimage(
            ExactAffineSegment inner, ExactTemporalDomain.Interval target) {
        ExactTime slope = inner.slope();
        if (slope.signum() == 0) {
            return target.contains(inner.intercept()) ? inner.interval() : null;
        }
        ExactTime low = target.start().subtract(inner.intercept()).divide(slope);
        ExactTime high = target.end().subtract(inner.intercept()).divide(slope);
        boolean lowIncluded = target.startInclusive();
        boolean highIncluded = target.endInclusive();
        if (slope.signum() < 0) {
            ExactTime swap = low; low = high; high = swap;
            boolean included = lowIncluded; lowIncluded = highIncluded; highIncluded = included;
        }
        return new ExactTemporalDomain.Interval(low, high, lowIncluded, highIncluded);
    }

    private static ExactTime exactTravelAt(ExactTime time, List<ExactTime> knots, List<ExactTime> values) {
        int segment = interpolationSegment(time, knots);
        ExactTime slope = values.get(segment + 1).subtract(values.get(segment))
                .divide(knots.get(segment + 1).subtract(knots.get(segment)));
        return values.get(segment).add(slope.multiply(time.subtract(knots.get(segment))));
    }

    private static int interpolationSegment(ExactTime time, List<ExactTime> knots) {
        for (int index = 0; index + 1 < knots.size(); index++) {
            if (time.compareTo(knots.get(index)) >= 0 && time.compareTo(knots.get(index + 1)) <= 0) return index;
        }
        if (time.compareTo(knots.getFirst()) < 0 || time.compareTo(knots.getLast()) > 0) {
            throw new IllegalArgumentException("edge temporal domain exceeds its canonical breakpoint range");
        }
        return Math.max(0, knots.size() - 2);
    }

    private static void writeTime(DataOutput output, ExactTime value) throws IOException {
        writeBigInteger(output, value.numerator());
        writeBigInteger(output, value.denominator());
    }

    private static ExactTime readTime(DataInput input) throws IOException {
        BigInteger numerator = readBigInteger(input);
        BigInteger denominator = readBigInteger(input);
        try {
            return ExactTime.of(numerator, denominator);
        } catch (ArithmeticException invalid) {
            throw new IOException("invalid exact rational", invalid);
        }
    }

    private static void writeBigInteger(DataOutput output, BigInteger value) throws IOException {
        byte[] bytes = value.toByteArray();
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static BigInteger readBigInteger(DataInput input) throws IOException {
        int length = input.readInt();
        if (length <= 0) throw new IOException("invalid exact integer byte count");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new BigInteger(bytes);
    }

    @Override
    public String toString() {
        return segments.toString();
    }
}
