package edu.ipcmax.core.profile;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Exact extension-safe dominance for PACE candidate fragments.
 */
public final class SafeProfileDominance {
    private SafeProfileDominance() {
    }

    /**
     * Compatibility overload. Without a graph Omega cannot be derived, so dominance is
     * deliberately limited to candidates representing the same path.
     */
    public static boolean dominates(CandidateProfile a, CandidateProfile b, Domain domain) {
        if (!a.stablePathId().equals(b.stablePathId())) {
            return false;
        }
        for (Domain.Interval interval : domain.intervals()) {
            if (!dominatesProfiles(a, b, interval)) {
                return false;
            }
        }
        return !domain.isEmpty();
    }

    /**
     * Tests the finalized PACE dominance rule on one positive-measure temporal cell.
     */
    public static boolean dominates(
            TDGraph graph,
            CandidateProfile a,
            CandidateProfile b,
            Domain.Interval cell,
            int subproblemSource,
            int subproblemDestination) {
        if (cell == null) {
            return false;
        }
        if (!covers(a.domain(), cell) || !covers(b.domain(), cell)) {
            return false;
        }
        Set<Integer> omegaA = a.internalVertices(graph, subproblemSource, subproblemDestination);
        Set<Integer> omegaB = b.internalVertices(graph, subproblemSource, subproblemDestination);
        if (!omegaB.containsAll(omegaA)) {
            return false;
        }
        return dominatesProfiles(a, b, cell);
    }

    /**
     * Exact equality of two arrival profiles throughout a temporal cell.
     */
    public static boolean sameArrival(CandidateProfile a, CandidateProfile b, Domain.Interval cell) {
        requireExactArrival(a, cell);
        requireExactArrival(b, cell);
        for (double point : timeCuts(cell, a.arrivalProfile(), b.arrivalProfile())) {
            if (!Domain.sameTime(
                    a.arrivalProfile().valueAtClosure(point),
                    b.arrivalProfile().valueAtClosure(point))) {
                return false;
            }
        }
        return true;
    }

    /**
     * True if A's score is no lower than B's everywhere in the cell.
     */
    public static boolean scoreNoLower(CandidateProfile a, CandidateProfile b, Domain.Interval cell) {
        requireExactScore(a, cell);
        requireExactScore(b, cell);
        if (cell.start() == cell.end()) {
            return a.scoreProfile().valueAt(cell.start()) >= b.scoreProfile().valueAt(cell.start());
        }
        List<Double> cuts = scoreCuts(cell, a.scoreProfile(), b.scoreProfile());
        for (int i = 0; i + 1 < cuts.size(); i++) {
            double sample = midpoint(cuts.get(i), cuts.get(i + 1));
            if (a.scoreProfile().valueAt(sample) < b.scoreProfile().valueAt(sample)) {
                return false;
            }
        }
        return true;
    }

    /**
     * True if the two score profiles are equal throughout the cell.
     */
    public static boolean sameScore(CandidateProfile a, CandidateProfile b, Domain.Interval cell) {
        requireExactScore(a, cell);
        requireExactScore(b, cell);
        if (cell.start() == cell.end()) {
            return a.scoreProfile().valueAt(cell.start()) == b.scoreProfile().valueAt(cell.start());
        }
        List<Double> cuts = scoreCuts(cell, a.scoreProfile(), b.scoreProfile());
        for (int i = 0; i + 1 < cuts.size(); i++) {
            double sample = midpoint(cuts.get(i), cuts.get(i + 1));
            if (a.scoreProfile().valueAt(sample) != b.scoreProfile().valueAt(sample)) {
                return false;
            }
        }
        return true;
    }

    private static boolean dominatesProfiles(CandidateProfile a, CandidateProfile b, Domain.Interval cell) {
        if (!sameArrival(a, b, cell) || !scoreNoLower(a, b, cell)) {
            return false;
        }
        if (!sameScore(a, b, cell)) {
            return true;
        }
        int edgeComparison = Integer.compare(a.edgeCount(), b.edgeCount());
        return edgeComparison < 0
                || (edgeComparison == 0
                && PathPointer.STABLE_PATH_ORDER.compare(a.stablePathId(), b.stablePathId()) <= 0);
    }

    private static boolean covers(Domain domain, Domain.Interval cell) {
        Domain requested = Domain.of(cell);
        return domain.intersection(requested).equals(requested);
    }

    private static void requireExactArrival(CandidateProfile candidate, Domain.Interval cell) {
        if (!candidate.arrivalProfile().isPiecewise() && cell.end() > cell.start()) {
            throw new IllegalArgumentException("extension-safe dominance requires exact piecewise arrival metadata");
        }
    }

    private static void requireExactScore(CandidateProfile candidate, Domain.Interval cell) {
        if (!candidate.scoreProfile().isPiecewise() && cell.end() > cell.start()) {
            throw new IllegalArgumentException("extension-safe dominance requires exact piecewise score metadata");
        }
    }

    private static List<Double> timeCuts(Domain.Interval cell, TimeProfile... profiles) {
        List<Double> points = new ArrayList<>();
        points.add(cell.start());
        points.add(cell.end());
        for (TimeProfile profile : profiles) {
            for (TimeProfile.Breakpoint breakpoint : profile.breakpoints()) {
                if (breakpoint.minute() > cell.start()
                        && breakpoint.minute() < cell.end()) {
                    points.add(breakpoint.minute());
                }
            }
        }
        return uniqueSorted(points);
    }

    private static List<Double> scoreCuts(Domain.Interval cell, ScoreProfile... profiles) {
        List<Double> points = new ArrayList<>();
        points.add(cell.start());
        points.add(cell.end());
        for (ScoreProfile profile : profiles) {
            for (double breakpoint : profile.breakpoints()) {
                if (breakpoint > cell.start() && breakpoint < cell.end()) {
                    points.add(breakpoint);
                }
            }
        }
        return uniqueSorted(points);
    }

    private static List<Double> uniqueSorted(List<Double> points) {
        List<Double> sorted = points.stream().filter(Double::isFinite).sorted().toList();
        List<Double> unique = new ArrayList<>();
        for (double point : sorted) {
            double canonical = Domain.canonicalTime(point);
            if (unique.isEmpty() || !Domain.sameTime(unique.get(unique.size() - 1), canonical)) {
                unique.add(canonical);
            }
        }
        return unique;
    }

    private static double midpoint(double start, double end) {
        return start + ((end - start) / 2.0);
    }
}
