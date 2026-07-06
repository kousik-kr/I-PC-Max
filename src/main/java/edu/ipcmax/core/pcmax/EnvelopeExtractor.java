package edu.ipcmax.core.pcmax;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;

/**
 * Extracts the PACE departure-time-to-path envelope from a candidate frontier.
 */
public final class EnvelopeExtractor {
    private EnvelopeExtractor() {
    }

    /**
     * Extracts an envelope over the root interval by refining at domain and profile breakpoints.
     */
    public static EnvelopeProfile extract(CandidateSet frontier, Domain rootDomain) {
        List<Double> breakpoints = new ArrayList<>(rootDomain.breakpoints());
        List<CandidateProfile> candidates = frontier.candidates();
        for (CandidateProfile candidate : candidates) {
            requireExactContinuousMetadata(candidate);
            breakpoints.addAll(candidate.domain().breakpoints());
            breakpoints.addAll(candidate.arrivalProfile().breakpoints().stream().map(point -> point.minute()).toList());
            breakpoints.addAll(candidate.scoreProfile().breakpoints());
        }
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                breakpoints.addAll(travelEqualityBreakpoints(candidates.get(i), candidates.get(j), rootDomain));
            }
        }
        List<Domain.Interval> refined = refine(rootDomain, uniqueSorted(breakpoints));
        List<EnvelopeSegment> segments = new ArrayList<>();
        CandidateProfile current = null;
        Domain.Interval currentInterval = null;

        for (Domain.Interval interval : refined) {
            CandidateProfile best = bestCandidateAt(frontier, interval);
            if (current == null) {
                current = best;
                currentInterval = interval;
                continue;
            }
            if (!sameAssignment(current, best)) {
                segments.add(new EnvelopeSegment(currentInterval, current));
                current = best;
                currentInterval = interval;
                continue;
            }
            currentInterval = new Domain.Interval(currentInterval.start(), interval.end());
        }

        if (currentInterval != null) {
            segments.add(new EnvelopeSegment(currentInterval, current));
        }
        return new EnvelopeProfile(rootDomain, segments);
    }

    private static void requireExactContinuousMetadata(CandidateProfile candidate) {
        if (!candidate.arrivalProfile().isPiecewise() && !isSingleton(candidate.domain())) {
            throw new UnsupportedOperationException("exact envelope extraction requires piecewise arrival metadata for continuous domains");
        }
        if (!candidate.scoreProfile().isPiecewise() && !isSingleton(candidate.domain())) {
            throw new UnsupportedOperationException("exact envelope extraction requires piecewise score metadata for continuous domains");
        }
    }

    private static CandidateProfile bestCandidateAt(CandidateSet frontier, Domain.Interval interval) {
        double departure = interval.start() + ((interval.end() - interval.start()) / 2.0);
        CandidateProfile best = null;
        for (CandidateProfile candidate : frontier.candidates()) {
            if (!candidate.domain().contains(departure)) {
                continue;
            }
            if (best == null || compareAt(candidate, best, departure) < 0) {
                best = candidate;
            }
        }
        return best;
    }

    private static List<Double> uniqueSorted(List<Double> breakpoints) {
        Set<Double> unique = new LinkedHashSet<>();
        for (double breakpoint : breakpoints) {
            if (Double.isFinite(breakpoint)) {
                unique.add(breakpoint);
            }
        }
        return unique.stream().sorted().toList();
    }

    private static List<Domain.Interval> refine(Domain rootDomain, List<Double> breakpoints) {
        List<Domain.Interval> refined = new ArrayList<>();
        for (Domain.Interval interval : rootDomain.intervals()) {
            double cursor = interval.start();
            List<Double> sorted = breakpoints.stream()
                    .filter(point -> point > interval.start() + 1e-9 && point < interval.end() - 1e-9)
                    .sorted()
                    .toList();
            for (double point : sorted) {
                refined.add(new Domain.Interval(cursor, point));
                cursor = point;
            }
            refined.add(new Domain.Interval(cursor, interval.end()));
        }
        return refined;
    }

    private static List<Double> travelEqualityBreakpoints(CandidateProfile left, CandidateProfile right, Domain rootDomain) {
        Domain overlap = rootDomain.intersection(left.domain()).intersection(right.domain());
        if (overlap.isEmpty()) {
            return List.of();
        }

        List<Double> cutPoints = new ArrayList<>(overlap.breakpoints());
        cutPoints.addAll(left.arrivalProfile().breakpoints().stream().map(point -> point.minute()).toList());
        cutPoints.addAll(right.arrivalProfile().breakpoints().stream().map(point -> point.minute()).toList());
        Domain refined = overlap.splitAt(cutPoints);

        List<Double> equalityPoints = new ArrayList<>();
        for (Domain.Interval interval : refined.intervals()) {
            double leftStart = left.arrivalProfile().valueAt(interval.start());
            double rightStart = right.arrivalProfile().valueAt(interval.start());
            double leftEnd = left.arrivalProfile().valueAt(interval.end());
            double rightEnd = right.arrivalProfile().valueAt(interval.end());
            double diffStart = leftStart - rightStart;
            double diffEnd = leftEnd - rightEnd;

            if (Math.abs(diffStart) < 1e-9) {
                equalityPoints.add(interval.start());
            }
            if (Math.abs(diffEnd) < 1e-9) {
                equalityPoints.add(interval.end());
            }
            if (diffStart * diffEnd < 0.0) {
                double alpha = Math.abs(diffStart) / (Math.abs(diffStart) + Math.abs(diffEnd));
                equalityPoints.add(interval.start() + alpha * (interval.end() - interval.start()));
            }
        }
        return equalityPoints;
    }

    private static int compareAt(CandidateProfile left, CandidateProfile right, double departure) {
        int scoreCompare = Integer.compare(right.scoreProfile().valueAt(departure), left.scoreProfile().valueAt(departure));
        if (scoreCompare != 0) {
            return scoreCompare;
        }
        int travelCompare = Double.compare(left.travelTimeAt(departure), right.travelTimeAt(departure));
        if (travelCompare != 0) {
            return travelCompare;
        }
        int edgeCountCompare = Integer.compare(left.pathPointer().arcIds().size(), right.pathPointer().arcIds().size());
        if (edgeCountCompare != 0) {
            return edgeCountCompare;
        }
        int size = Math.min(left.pathPointer().arcIds().size(), right.pathPointer().arcIds().size());
        for (int i = 0; i < size; i++) {
            int arcCompare = Integer.compare(left.pathPointer().arcIds().get(i), right.pathPointer().arcIds().get(i));
            if (arcCompare != 0) {
                return arcCompare;
            }
        }
        return Integer.compare(left.pathPointer().arcIds().size(), right.pathPointer().arcIds().size());
    }

    private static boolean sameAssignment(CandidateProfile left, CandidateProfile right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.pathPointer().arcIds().equals(right.pathPointer().arcIds());
    }

    private static boolean isSingleton(Domain domain) {
        return domain.intervals().size() == 1 && domain.intervals().get(0).start() == domain.intervals().get(0).end();
    }
}
