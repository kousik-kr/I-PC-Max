package edu.ipcmax.experiments.querygen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;

class QueryCandidateSamplerTest {
    @Test
    void continentalWorkerSizingBoundsConcurrentDenseDijkstraQueues() {
        assertEquals(
                2,
                QueryCandidateSampler.lowerBoundWorkerCount(
                        16, 23_000_000, 32));
        assertEquals(
                4,
                QueryCandidateSampler.lowerBoundWorkerCount(
                        16, 2_000_000, 32));
        assertEquals(
                16,
                QueryCandidateSampler.lowerBoundWorkerCount(
                        16, 300_000, 32));
    }

    private static final String CHECKSUM = "0123456789abcdef".repeat(4);

    @Test
    void producesDeterministicCandidatesAndReusesOneSearchPerSource() {
        QueryCandidateSampler sampler = new QueryCandidateSampler();
        QueryGenerationConfig.CandidatePool settings = settings(4, 20, 2, 2.0);

        QueryCandidateSampler.SamplingResult first = sampler.sample(
                stronglyConnectedRing(12, true), "ny", CHECKSUM, 20260711L, settings);
        QueryCandidateSampler.SamplingResult second = sampler.sample(
                stronglyConnectedRing(12, false), "NY", CHECKSUM.toUpperCase(), 20260711L, settings);

        assertEquals(first, second);
        assertEquals(4, first.sampledSources().size());
        assertEquals(4, first.shortestPathRuns());
        assertEquals(20, first.candidates().size());
        assertEquals(20, first.eventCount(QueryCandidateSampler.SELECTED));
        assertTrue(first.pairsExamined() >= first.candidates().size());
        for (QueryPairCandidate candidate : first.candidates()) {
            assertEquals(candidate.source(), first.sampledSources().get(candidate.sampledSourceIndex()));
            assertEquals(2L * candidate.lowerBoundEdgeCount(),
                    candidate.temporalFunctionComplexity());
        }
    }

    @Test
    void removesOrderedPairDuplicatesAndReturnsCanonicalOrder() {
        QueryCandidateSampler.SamplingResult result = new QueryCandidateSampler().sample(
                stronglyConnectedRing(10), "NY", CHECKSUM, 99L, settings(10, 90, 0, 0));

        Set<String> pairs = new HashSet<>();
        for (QueryPairCandidate candidate : result.candidates()) {
            assertTrue(pairs.add(candidate.source() + ":" + candidate.destination()));
            assertFalse(candidate.source() == candidate.destination());
        }
        assertEquals(90, pairs.size());
        assertEquals(result.candidates().stream().sorted(QueryPairCandidate.CANONICAL_ORDER).toList(),
                result.candidates());
    }

    @Test
    void filtersByMinimumPathEdgeCountAndDistanceInclusively() {
        TinyGraphBuilder builder = new TinyGraphBuilder();
        for (int node = 1; node <= 8; node++) {
            builder.node(node);
        }
        for (int node = 1; node < 8; node++) {
            builder.edge(node, node + 1, 1);
        }

        QueryCandidateSampler.SamplingResult result = new QueryCandidateSampler().sample(
                builder.build(), "NY", CHECKSUM, 17L, settings(7, 100, 3, 3.0));

        assertTrue(result.candidates().stream().allMatch(candidate -> candidate.lowerBoundEdgeCount() >= 3));
        assertTrue(result.candidates().stream().allMatch(candidate -> candidate.lowerBoundDistance() >= 3.0));
        assertTrue(result.candidates().stream().anyMatch(candidate ->
                candidate.source() == 1 && candidate.destination() == 4
                        && candidate.lowerBoundEdgeCount() == 3));
        assertFalse(result.candidates().stream().anyMatch(candidate ->
                candidate.source() == 1 && candidate.destination() == 3));
    }

    @Test
    void unreachableDestinationsAreAlwaysExcluded() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .node(4)
                .edge(1, 2, 1)
                .edge(3, 4, 1)
                .build();
        QueryGenerationConfig.CandidatePool settings = new QueryGenerationConfig.CandidatePool(
                2, 20, 0, 0, false, false);

        QueryCandidateSampler.SamplingResult result = new QueryCandidateSampler().sample(
                graph, "NY", CHECKSUM, 3L, settings);

        assertEquals(Set.of("1:2", "3:4"), result.candidates().stream()
                .map(candidate -> candidate.source() + ":" + candidate.destination())
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void tracksMutuallyExclusiveSamplingEventsInStableOrder() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .edge(1, 2, 1)
                .edge(2, 3, 1)
                .build();

        QueryCandidateSampler.SamplingResult result = new QueryCandidateSampler().sample(
                graph, "NY", CHECKSUM, 7L, settings(2, 100, 2, 3));

        assertEquals(List.of(
                        QueryCandidateSampler.BELOW_MINIMUM_DISTANCE,
                        QueryCandidateSampler.DUPLICATE_PAIRS,
                        QueryCandidateSampler.LOWER_BOUND_PATH_TOO_SHORT,
                        QueryCandidateSampler.SELECTED,
                        QueryCandidateSampler.SOURCE_EQUALS_DESTINATION,
                        QueryCandidateSampler.UNREACHABLE),
                new ArrayList<>(result.eventCounts().keySet()));
        assertEquals(1, result.eventCount(QueryCandidateSampler.BELOW_MINIMUM_DISTANCE));
        assertEquals(0, result.eventCount(QueryCandidateSampler.DUPLICATE_PAIRS));
        assertEquals(2, result.eventCount(QueryCandidateSampler.LOWER_BOUND_PATH_TOO_SHORT));
        assertEquals(0, result.eventCount(QueryCandidateSampler.SELECTED));
        assertEquals(2, result.eventCount(QueryCandidateSampler.SOURCE_EQUALS_DESTINATION));
        assertEquals(1, result.eventCount(QueryCandidateSampler.UNREACHABLE));
        assertEquals(result.pairsExamined(), result.eventCounts().values().stream()
                .mapToLong(Long::longValue)
                .sum());
        assertThrows(UnsupportedOperationException.class, () ->
                result.eventCounts().put(QueryCandidateSampler.SELECTED, 1L));
    }

    @Test
    void preservesCandidateConstructorsAndValidatesTemporalComplexity() {
        QueryPairCandidate phaseTwo = new QueryPairCandidate("NY", 1, 2, 3, 4, 5);
        QueryPairCandidate phaseThree = new QueryPairCandidate("NY", 1, 2, 3, 4, 5, 6);

        assertEquals(0, phaseTwo.temporalFunctionComplexity());
        assertEquals(0, phaseThree.temporalFunctionComplexity());
        assertThrows(IllegalArgumentException.class, () -> new QueryPairCandidate(
                "NY", 1, 2, 3, 4, 5, 6, -1));
    }

    @Test
    void rejectsMoreSampledSourcesThanTheGraphCanProvide() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .edge(1, 2, 1)
                .build();

        assertThrows(IllegalArgumentException.class, () -> new QueryCandidateSampler().sample(
                graph, "NY", CHECKSUM, 1L, settings(2, 5, 0, 0)));
    }

    @Test
    void constructsRankQuartilesAndBalancedBins() {
        List<QueryPairCandidate> input = candidates(8, List.of(8d, 1d, 4d, 3d, 2d, 7d, 6d, 5d));

        QueryCandidateSampler.BinnedCandidates result = QueryCandidateSampler.assignDistanceBins(input);

        assertEquals(List.of(1d, 2d, 3d, 4d, 5d, 6d, 7d, 8d), distances(result.candidates()));
        assertEquals(new QueryCandidateSampler.DistanceQuartiles(2d, 4d, 6d), result.quartiles());
        assertEquals(List.of(1d, 2d), distances(result.candidates(DistanceBin.Q1)));
        assertEquals(List.of(3d, 4d), distances(result.candidates(DistanceBin.Q2)));
        assertEquals(List.of(5d, 6d), distances(result.candidates(DistanceBin.Q3)));
        assertEquals(List.of(7d, 8d), distances(result.candidates(DistanceBin.Q4)));
    }

    @Test
    void tiedDistancesAreSplitByRankWithoutBreakingCanonicalOrder() {
        List<QueryPairCandidate> tied = candidates(8, List.of(10d, 10d, 10d, 10d, 10d, 10d, 10d, 10d));

        QueryCandidateSampler.BinnedCandidates result = QueryCandidateSampler.assignDistanceBins(tied);

        assertEquals(new QueryCandidateSampler.DistanceQuartiles(10d, 10d, 10d), result.quartiles());
        for (DistanceBin bin : DistanceBin.values()) {
            assertEquals(2, result.candidates(bin).size());
            assertCanonical(result.candidates(bin));
        }
    }

    @Test
    void q1ThroughQ4PopulationDiffersByAtMostOne() {
        QueryCandidateSampler.BinnedCandidates result = QueryCandidateSampler.assignDistanceBins(
                candidates(11, List.of(7d, 2d, 11d, 5d, 1d, 9d, 4d, 3d, 10d, 8d, 6d)));

        List<Integer> sizes = java.util.Arrays.stream(DistanceBin.values())
                .map(bin -> result.candidates(bin).size())
                .toList();
        assertTrue(sizes.stream().allMatch(size -> size > 0));
        assertTrue(java.util.Collections.max(sizes) - java.util.Collections.min(sizes) <= 1);
        for (DistanceBin bin : DistanceBin.values()) {
            assertCanonical(result.candidates(bin));
        }
    }

    @Test
    void emptyPoolHasAllBinsAndNoQuartileValues() {
        QueryCandidateSampler.BinnedCandidates result = QueryCandidateSampler.assignDistanceBins(List.of());

        assertEquals(QueryCandidateSampler.DistanceQuartiles.empty(), result.quartiles());
        for (DistanceBin bin : DistanceBin.values()) {
            assertTrue(result.candidates(bin).isEmpty());
        }
    }

    private static QueryGenerationConfig.CandidatePool settings(
            int sources, int maximumPairs, int minimumEdges, double minimumDistance) {
        return new QueryGenerationConfig.CandidatePool(
                sources, maximumPairs, minimumEdges, minimumDistance, true, true);
    }

    private static TDGraph stronglyConnectedRing(int size) {
        return stronglyConnectedRing(size, true);
    }

    private static TDGraph stronglyConnectedRing(int size, boolean reverseNodeInsertion) {
        TinyGraphBuilder builder = new TinyGraphBuilder();
        for (int index = 0; index < size; index++) {
            int node = reverseNodeInsertion ? size - index : index + 1;
            builder.node(node);
        }
        for (int node = 1; node <= size; node++) {
            int next = node == size ? 1 : node + 1;
            builder.edge(node, next, 1);
            builder.edge(next, node, 1);
        }
        return builder.build();
    }

    private static List<QueryPairCandidate> candidates(int count, List<Double> distances) {
        List<QueryPairCandidate> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(new QueryPairCandidate(
                    "NY", index + 1, 100 + index, distances.get(index), index + 1, 0, index));
        }
        return result;
    }

    private static List<Double> distances(List<QueryPairCandidate> candidates) {
        return candidates.stream().map(QueryPairCandidate::lowerBoundDistance).toList();
    }

    private static void assertCanonical(List<QueryPairCandidate> candidates) {
        assertEquals(candidates.stream().sorted(QueryPairCandidate.CANONICAL_ORDER).toList(), candidates);
    }
}
