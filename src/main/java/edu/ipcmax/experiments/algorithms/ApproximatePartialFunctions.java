package edu.ipcmax.experiments.algorithms;

import edu.ipcmax.baselines.exact.ExactAffineSegment;
import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.preference.ExactScoreProfile;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Paper-inspired breakpoint clustering with explicitly approximate search functions. */
final class ApproximatePartialFunctions {
    record Result(ExactArrivalProfile arrival, ExactScoreProfile score,
            long mergedBreakpoints, long rouletteDraws, ExactTime maximumArrivalError,
            long piecesBefore, long piecesAfter) { }
    private record Span(ExactTime start, ExactTime end, ExactTime slope,
            ExactTime intercept, BigInteger score) { }

    private ApproximatePartialFunctions() { }

    static Result merge(ExactArrivalProfile arrival, ExactScoreProfile score,
            ExactTemporalDomain support, ExactTime threshold, SplittableRandom random,
            BooleanSupplier stopRequested) {
        List<ExactAffineSegment> arrivalOutput = new ArrayList<>();
        List<ExactScoreProfile.Segment> scoreOutput = new ArrayList<>();
        long merged = 0;
        long rouletteDraws = 0;
        ExactTime maximumArrivalError = ExactTime.ZERO;
        for (ExactTemporalDomain.Interval component : support.intervals()) {
            check(stopRequested);
            TreeSet<ExactTime> cuts = new TreeSet<>();
            cuts.add(component.start());
            cuts.add(component.end());
            for (ExactAffineSegment segment : arrival.segments()) {
                check(stopRequested);
                addIfOnClosure(cuts, segment.interval(), component);
            }
            for (ExactScoreProfile.Segment segment : score.segments()) {
                check(stopRequested);
                addIfOnClosure(cuts, segment.interval(), component);
            }
            List<ExactTime> originalCuts = List.copyOf(cuts);
            List<Span> spans = new ArrayList<>();
            int left = 0;
            while (left + 2 < originalCuts.size()) {
                check(stopRequested);
                int right = left + 2;
                if (originalCuts.get(right).subtract(originalCuts.get(left)).compareTo(threshold) > 0) {
                    left++;
                    continue;
                }
                while (right + 1 < originalCuts.size()
                        && originalCuts.get(right + 1).subtract(originalCuts.get(left)).compareTo(threshold) <= 0) {
                    right++;
                }
                ExactTime start = originalCuts.get(left);
                ExactTime end = originalCuts.get(right);
                ExactTime startArrival = valueOnClosure(arrival, start, stopRequested);
                ExactTime endArrival = valueOnClosure(arrival, end, stopRequested);
                ExactTime slope = endArrival.subtract(startArrival).divide(end.subtract(start));
                ExactTime intercept = startArrival.subtract(slope.multiply(start));
                for (int event = left + 1; event < right; event++) {
                    check(stopRequested);
                    ExactTime point = originalCuts.get(event);
                    ExactTime error = valueAt(arrival, point, stopRequested).subtract(
                            slope.multiply(point).add(intercept));
                    if (error.signum() < 0) error = error.negate();
                    if (error.compareTo(maximumArrivalError) > 0) maximumArrivalError = error;
                }
                BigInteger representative = scoreOnOpenCell(
                        score, originalCuts.get(left), originalCuts.get(left + 1), stopRequested);
                for (int event = left + 1; event < right; event++) {
                    check(stopRequested);
                    BigInteger incoming = scoreOnOpenCell(score,
                            originalCuts.get(event), originalCuts.get(event + 1), stopRequested);
                    representative = roulette(representative, incoming, random.nextInt(101));
                    rouletteDraws++;
                }
                spans.add(new Span(start, end, slope, intercept, representative));
                merged += right - left - 1L;
                left = right;
            }

            TreeSet<ExactTime> retainedCuts = new TreeSet<>(originalCuts);
            for (Span span : spans) {
                check(stopRequested);
                for (ExactTime cut : originalCuts) {
                    if (cut.compareTo(span.start()) > 0 && cut.compareTo(span.end()) < 0) retainedCuts.remove(cut);
                }
            }
            List<ExactTime> ordered = List.copyOf(retainedCuts);
            if (ordered.size() == 1) {
                ExactTime point = ordered.getFirst();
                if (support.contains(point)) {
                    arrivalOutput.add(new ExactAffineSegment(ExactTime.ZERO,
                            valueAt(arrival, point, stopRequested), ExactTemporalDomain.Interval.singleton(point)));
                    scoreOutput.add(new ExactScoreProfile.Segment(score.valueAt(point, stopRequested),
                            ExactTemporalDomain.Interval.singleton(point)));
                }
            }
            for (int i = 0; i + 1 < ordered.size(); i++) {
                check(stopRequested);
                ExactTime point = ordered.get(i);
                ExactTime end = ordered.get(i + 1);
                if (point.equals(end)) continue;
                ExactTime probe = point.add(end).divide(ExactTime.of(2));
                if (!support.contains(probe)) continue;
                Span span = spanAt(spans, probe, false, stopRequested);
                ExactTime slope;
                ExactTime intercept;
                BigInteger scoreValue;
                if (span != null) {
                    slope = span.slope();
                    intercept = span.intercept();
                    scoreValue = span.score();
                } else {
                    ExactAffineSegment original = segmentAt(arrival, probe, stopRequested);
                    slope = original.slope();
                    intercept = original.intercept();
                    scoreValue = score.valueAt(probe, stopRequested);
                }
                boolean startIncluded = point.equals(component.start())
                        ? component.startInclusive() : true;
                boolean endIncluded = end.equals(component.end())
                        ? component.endInclusive() : false;
                var cell = new ExactTemporalDomain.Interval(point, end, startIncluded, endIncluded);
                arrivalOutput.add(new ExactAffineSegment(slope, intercept, cell));
                scoreOutput.add(new ExactScoreProfile.Segment(scoreValue, cell));
            }
        }
        ExactArrivalProfile mergedArrival = ExactArrivalProfile.ofSegments(arrivalOutput);
        ExactScoreProfile mergedScore = ExactScoreProfile.ofSegments(scoreOutput);
        return new Result(mergedArrival, mergedScore, merged, rouletteDraws, maximumArrivalError,
                arrival.pieceCount() + score.pieceCount(),
                mergedArrival.pieceCount() + mergedScore.pieceCount());
    }

    private static void check(BooleanSupplier stopRequested) {
        if (stopRequested != null && stopRequested.getAsBoolean()) {
            throw new CancellationException("Opt2 breakpoint merge cancelled");
        }
    }

    private static void addIfOnClosure(TreeSet<ExactTime> cuts,
            ExactTemporalDomain.Interval event, ExactTemporalDomain.Interval component) {
        if (component.containsClosure(event.start())) cuts.add(event.start());
        if (component.containsClosure(event.end())) cuts.add(event.end());
    }

    private static ExactTime valueOnClosure(
            ExactArrivalProfile profile, ExactTime point, BooleanSupplier stopRequested) {
        ExactAffineSegment segment = null;
        for (ExactAffineSegment value : profile.segments()) {
            check(stopRequested);
            if (value.interval().contains(point)) {
                segment = value;
                break;
            }
        }
        if (segment == null) {
            for (ExactAffineSegment value : profile.segments()) {
                check(stopRequested);
                if (value.interval().containsClosure(point)
                        && (segment == null || value.interval().start().equals(point))) {
                    segment = value;
                    if (value.interval().start().equals(point)) break;
                }
            }
        }
        if (segment == null) throw new IllegalArgumentException("no arrival value at " + point);
        return segment.valueAtClosure(point);
    }

    private static ExactTime valueAt(
            ExactArrivalProfile profile, ExactTime point, BooleanSupplier stopRequested) {
        return profile.valueAtExact(point, stopRequested);
    }

    private static ExactAffineSegment segmentAt(
            ExactArrivalProfile profile, ExactTime point, BooleanSupplier stopRequested) {
        for (ExactAffineSegment segment : profile.segments()) {
            check(stopRequested);
            if (segment.interval().contains(point)) return segment;
        }
        throw new IllegalArgumentException("no arrival segment at " + point);
    }

    private static BigInteger scoreOnOpenCell(
            ExactScoreProfile score, ExactTime start, ExactTime end, BooleanSupplier stopRequested) {
        return score.valueAt(start.add(end).divide(ExactTime.of(2)), stopRequested);
    }

    private static Span spanAt(
            List<Span> spans, ExactTime point, boolean includeStart, BooleanSupplier stopRequested) {
        for (Span span : spans) {
            check(stopRequested);
            if ((includeStart ? point.compareTo(span.start()) >= 0 : point.compareTo(span.start()) > 0)
                    && point.compareTo(span.end()) < 0) return span;
        }
        return null;
    }

    static BigInteger roulette(BigInteger current, BigInteger incoming,
            int randomInclusiveZeroToOneHundred) {
        BigInteger total = current.add(incoming);
        if (total.signum() == 0) return randomInclusiveZeroToOneHundred <= 50 ? current : incoming;
        // The paper compares an integer draw in [0,100] with 100*current/total.
        return BigInteger.valueOf(randomInclusiveZeroToOneHundred).multiply(total)
                .compareTo(BigInteger.valueOf(100).multiply(current)) <= 0 ? current : incoming;
    }
}
