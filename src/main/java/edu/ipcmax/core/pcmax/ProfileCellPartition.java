package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact temporal-cell construction shared by frontier compression and envelope extraction.
 */
final class ProfileCellPartition {
    private ProfileCellPartition() {
    }

    static List<Domain.Interval> cells(
            Domain domain,
            List<CandidateProfile> candidates,
            boolean includeTravelEqualityRoots) {
        List<Double> cuts = new ArrayList<>(domain.breakpoints());
        for (CandidateProfile candidate : candidates) {
            cuts.addAll(candidate.domain().breakpoints());
            for (TimeProfile.Breakpoint breakpoint : candidate.arrivalProfile().breakpoints()) {
                cuts.add(breakpoint.minute());
            }
            cuts.addAll(candidate.scoreProfile().breakpoints());
        }
        if (includeTravelEqualityRoots) {
            for (int i = 0; i < candidates.size(); i++) {
                for (int j = i + 1; j < candidates.size(); j++) {
                    cuts.addAll(travelEqualityBreakpoints(candidates.get(i), candidates.get(j), domain));
                }
            }
        }
        return partition(domain, cuts);
    }

    static List<Domain.Interval> partition(Domain domain, List<Double> cutPoints) {
        return domain.splitAt(uniqueSorted(cutPoints)).intervals();
    }

    static List<Double> travelEqualityBreakpoints(
            CandidateProfile left,
            CandidateProfile right,
            Domain containingDomain) {
        Domain overlap = containingDomain.intersection(left.domain()).intersection(right.domain());
        if (overlap.isEmpty()) {
            return List.of();
        }
        requireExact(left, overlap);
        requireExact(right, overlap);

        List<Double> baseCuts = new ArrayList<>(overlap.breakpoints());
        for (TimeProfile.Breakpoint breakpoint : left.arrivalProfile().breakpoints()) {
            baseCuts.add(breakpoint.minute());
        }
        for (TimeProfile.Breakpoint breakpoint : right.arrivalProfile().breakpoints()) {
            baseCuts.add(breakpoint.minute());
        }
        baseCuts.addAll(left.scoreProfile().breakpoints());
        baseCuts.addAll(right.scoreProfile().breakpoints());

        List<Double> roots = new ArrayList<>();
        for (Domain.Interval cell : partition(overlap, baseCuts)) {
            if (cell.end() <= cell.start()) {
                continue;
            }
            double sample = midpoint(cell);
            if (left.scoreProfile().valueAt(sample) != right.scoreProfile().valueAt(sample)) {
                continue;
            }
            double startDifference = left.arrivalProfile().valueAtClosure(cell.start())
                    - right.arrivalProfile().valueAtClosure(cell.start());
            double endDifference = left.arrivalProfile().valueAtClosure(cell.end())
                    - right.arrivalProfile().valueAtClosure(cell.end());
            if (Domain.sameTime(startDifference, 0)) {
                roots.add(cell.start());
            }
            if (Domain.sameTime(endDifference, 0)) {
                roots.add(cell.end());
            }
            if (startDifference * endDifference < 0.0) {
                double root = cell.start()
                        + (-startDifference * (cell.end() - cell.start())
                        / (endDifference - startDifference));
                roots.add(root);
            }
        }
        return uniqueSorted(roots);
    }

    static boolean covers(Domain domain, Domain.Interval cell) {
        Domain requested = Domain.of(cell);
        return domain.intersection(requested).equals(requested);
    }

    static double midpoint(Domain.Interval interval) {
        return interval.start() + ((interval.end() - interval.start()) / 2.0);
    }

    static double measure(Domain domain) {
        double result = 0.0;
        for (Domain.Interval interval : domain.intervals()) {
            result += Math.max(0.0, interval.end() - interval.start());
        }
        return result;
    }

    static List<Double> uniqueSorted(List<Double> points) {
        List<Double> sorted = points.stream().filter(Double::isFinite).sorted().toList();
        List<Double> unique = new ArrayList<>();
        for (double point : sorted) {
            double canonical = Domain.canonicalTime(point);
            if (unique.isEmpty() || !Domain.sameTime(unique.get(unique.size() - 1), canonical)) {
                unique.add(canonical);
            }
        }
        return List.copyOf(unique);
    }

    private static void requireExact(CandidateProfile candidate, Domain domain) {
        boolean positiveMeasure = measure(domain) > 0;
        if (positiveMeasure && !candidate.arrivalProfile().isPiecewise()) {
            throw new IllegalArgumentException("exact cell partition requires piecewise arrival metadata");
        }
        if (positiveMeasure && !candidate.scoreProfile().isPiecewise()) {
            throw new IllegalArgumentException("exact cell partition requires piecewise score metadata");
        }
    }
}
