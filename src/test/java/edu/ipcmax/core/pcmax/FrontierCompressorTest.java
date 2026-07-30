package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontierCompressorTest {
    private static final Domain ROOT = Domain.closed(0, 10);

    @Test
    void candidateDerivesStablePathIdVerticesOmegaAndAnchorCount() {
        TDGraph graph = twoPathGraph();
        CandidateProfile candidate = candidate(List.of(1, 2), 5, ScoreProfile.constant(ROOT, 3));

        assertEquals(List.of(1, 2), candidate.stablePathId());
        assertEquals(List.of(1, 2, 3), candidate.vertexSequence(graph, 1, 3));
        assertEquals(Set.of(2), candidate.internalVertices(graph, 1, 3));
        assertTrue(candidate.isVertexSimple(graph, 1, 3));
        assertEquals(0, candidate.explicitAnchorCount());
    }

    @Test
    void safeDominanceRemovesOnlyTheDominatedTemporalFragment() {
        TDGraph graph = twoPathGraph();
        CandidateProfile direct = candidate(
                List.of(0),
                5,
                ScoreProfile.piecewise(ROOT, List.of(
                        new ScoreProfile.Interval(0, 5, 10),
                        new ScoreProfile.Interval(5, 10, 3)), "direct-score"));
        CandidateProfile detour = candidate(List.of(1, 2), 5, ScoreProfile.constant(ROOT, 8));
        CandidateSet input = set(detour, direct);

        CandidateSet compressed = FrontierCompressor.compress(
                graph, input, ROOT, 20, 99, PaceExecutionPolicy.PACE_X, 1, 3);

        CandidateProfile retainedDirect = byPath(compressed, List.of(0));
        CandidateProfile retainedDetour = byPath(compressed, List.of(1, 2));
        assertEquals(ROOT, retainedDirect.domain());
        assertEquals(Domain.closed(5, 10), retainedDetour.domain());
    }

    @Test
    void earlierButNonidenticalArrivalIsNotExtensionSafeDominance() {
        TDGraph graph = twoPathGraph();
        CandidateProfile earlier = candidate(List.of(0), 4, ScoreProfile.constant(ROOT, 10));
        CandidateProfile later = candidate(List.of(1, 2), 5, ScoreProfile.constant(ROOT, 5));

        CandidateSet compressed = FrontierCompressor.compress(
                graph, set(earlier, later), ROOT, 20, 1, PaceExecutionPolicy.PACE_X, 1, 3);

        assertEquals(2, compressed.size());
    }

    @Test
    void equalScoreTieUsesNumericStablePathIdentifierIndependentOfInsertionOrder() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1).node(3)
                .edge(1, 3, 1)
                .edge(1, 3, 1)
                .build();
        CandidateProfile pathZero = candidate(List.of(0), 5, ScoreProfile.constant(ROOT, 7));
        CandidateProfile pathOne = candidate(List.of(1), 5, ScoreProfile.constant(ROOT, 7));

        CandidateSet forward = FrontierCompressor.compress(
                graph, set(pathZero, pathOne), ROOT, 20, 5, PaceExecutionPolicy.PACE_X, 1, 3);
        CandidateSet reverse = FrontierCompressor.compress(
                graph, set(pathOne, pathZero), ROOT, 20, 5, PaceExecutionPolicy.PACE_X, 1, 3);

        assertEquals(List.of(List.of(0)), pathIds(forward));
        assertEquals(pathIds(forward), pathIds(reverse));
    }

    @Test
    void boundedRetentionUsesChampionEarliestCoverageThenLeastRestrictiveOrder() {
        TDGraph graph = representativeGraph();
        CandidateProfile champion = candidate(List.of(0, 1, 2), 8, ScoreProfile.constant(ROOT, 10));
        CandidateProfile earliest = candidate(List.of(3, 4), 2, ScoreProfile.constant(ROOT, 2));
        CandidateProfile leastRestrictive = candidate(List.of(5), 6, ScoreProfile.constant(ROOT, 1));
        CandidateProfile fillOnly = candidate(List.of(6), 7, ScoreProfile.constant(ROOT, 5));

        CandidateSet first = FrontierCompressor.compress(
                graph,
                set(fillOnly, leastRestrictive, earliest, champion),
                ROOT,
                20,
                3,
                PaceExecutionPolicy.PACE_B,
                1,
                5);
        CandidateSet second = FrontierCompressor.compress(
                graph,
                set(champion, earliest, leastRestrictive, fillOnly),
                ROOT,
                20,
                3,
                PaceExecutionPolicy.PACE_B,
                1,
                5);

        assertEquals(3, first.size());
        assertEquals(Set.of(List.of(0, 1, 2), List.of(3, 4), List.of(5)), Set.copyOf(pathIds(first)));
        assertEquals(pathIds(first), pathIds(second));

        CandidateSet four = FrontierCompressor.compress(
                graph,
                set(fillOnly, leastRestrictive, earliest, champion),
                ROOT,
                20,
                4,
                PaceExecutionPolicy.PACE_B,
                1,
                5);
        assertEquals(Set.of(
                List.of(0, 1, 2),
                List.of(3, 4),
                List.of(5),
                List.of(6)), Set.copyOf(pathIds(four)));

        List<List<Integer>> previous = List.of();
        for (int k = 1; k <= 4; k++) {
            CandidateSet retained = FrontierCompressor.compress(
                    graph,
                    set(fillOnly, leastRestrictive, earliest, champion),
                    ROOT,
                    20,
                    k,
                    PaceExecutionPolicy.PACE_B,
                    1,
                    5);
            assertTrue(pathIds(retained).containsAll(previous),
                    "K_f=" + k + " must include the K_f=" + (k - 1)
                            + " prefix");
            previous = pathIds(retained);
        }
    }

    @Test
    void retainsCandidateOwnedOnlyAtTheStartOfAnOtherwiseEmptyCell() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1).node(2)
                .edge(1, 2, 1)
                .build();
        Domain feasible = Domain.closed(0, 1);
        CandidateProfile candidate = new CandidateProfile(
                feasible,
                TimeProfile.piecewise(feasible, List.of(
                        new TimeProfile.Breakpoint(0, 2),
                        new TimeProfile.Breakpoint(1, 4)),
                        "endpoint-arrival"),
                ScoreProfile.constant(feasible, 1),
                PathPointer.of(List.of(0)),
                0,
                -1,
                false);

        CandidateSet compressed = FrontierCompressor.compress(
                graph, set(candidate), Domain.closed(0, 6), 3, 1,
                PaceExecutionPolicy.PACE_X, 1, 2);

        assertEquals(1, compressed.size());
        assertEquals(feasible, compressed.candidates().get(0).domain());
    }

    @Test
    void maximalRunMergeMatchesHistoricalPairwiseEndpointOwnership() {
        CandidateProfile canonical = candidate(
                List.of(0),
                5,
                ScoreProfile.piecewise(
                        ROOT,
                        List.of(
                                new ScoreProfile.Interval(0, 2, 1),
                                new ScoreProfile.Interval(2, 5, 4),
                                new ScoreProfile.Interval(5, 7, 2),
                                new ScoreProfile.Interval(7, 10, 9)),
                        "score-run"));
        List<CandidateProfile> fragments = List.of(
                canonical.restrict(Domain.halfOpen(0, 2)),
                canonical.restrict(Domain.halfOpen(2, 5)),
                canonical.restrict(Domain.halfOpen(5, 7)),
                canonical.restrict(Domain.closed(7, 10)));

        CandidateProfile pairwise = fragments.get(0);
        for (int index = 1; index < fragments.size(); index++) {
            pairwise = FrontierCompressor.mergeAdjacentCompatible(
                    pairwise, fragments.get(index));
        }
        CandidateSet maximal = FrontierCompressor.mergeCandidateFragments(
                fragments, PaceExecutionMetrics.none());

        assertEquals(1, maximal.size());
        CandidateProfile actual = maximal.candidates().get(0);
        assertEquals(pairwise.domain(), actual.domain());
        assertEquals(
                pairwise.arrivalProfile().breakpoints(),
                actual.arrivalProfile().breakpoints());
        assertEquals(
                pairwise.scoreProfile().intervals(),
                actual.scoreProfile().intervals());
        for (double departure :
                List.of(0.0, 2.0, 5.0, 7.0, 10.0)) {
            assertEquals(
                    pairwise.scoreProfile().valueAt(departure),
                    actual.scoreProfile().valueAt(departure));
        }
    }

    private static TDGraph twoPathGraph() {
        return new TinyGraphBuilder()
                .node(1).node(2).node(3)
                .edge(1, 3, 1)
                .edge(1, 2, 1)
                .edge(2, 3, 1)
                .build();
    }

    private static TDGraph representativeGraph() {
        return new TinyGraphBuilder()
                .node(1).node(2).node(3).node(4).node(5)
                .edge(1, 2, 1)
                .edge(2, 3, 1)
                .edge(3, 5, 1)
                .edge(1, 4, 1)
                .edge(4, 5, 1)
                .edge(1, 5, 1)
                .edge(1, 5, 1)
                .build();
    }

    private static CandidateProfile candidate(List<Integer> path, double travelTime, ScoreProfile score) {
        return new CandidateProfile(
                ROOT,
                TimeProfile.piecewise(ROOT, List.of(
                        new TimeProfile.Breakpoint(0, travelTime),
                        new TimeProfile.Breakpoint(10, 10 + travelTime)),
                        "arrival:" + path + ":" + travelTime),
                score,
                PathPointer.of(path),
                0,
                -1,
                false);
    }

    private static CandidateSet set(CandidateProfile... candidates) {
        CandidateSet result = new CandidateSet();
        for (CandidateProfile candidate : candidates) {
            result.add(candidate);
        }
        return result;
    }

    private static CandidateProfile byPath(CandidateSet set, List<Integer> path) {
        return set.candidates().stream()
                .filter(candidate -> candidate.stablePathId().equals(path))
                .findFirst()
                .orElseThrow();
    }

    private static List<List<Integer>> pathIds(CandidateSet set) {
        List<List<Integer>> result = new ArrayList<>();
        for (CandidateProfile candidate : set.candidates()) {
            result.add(candidate.stablePathId());
        }
        return result;
    }
}
