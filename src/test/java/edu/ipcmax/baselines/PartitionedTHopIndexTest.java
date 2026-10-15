package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartitionedTHopIndexTest {
    private static final Domain HORIZON = Domain.closed(0, 60);

    @TempDir
    Path temporaryDirectory;

    @Test
    void everyTinyGraphPairMatchesBothThopReferencesAndTheIndependentOracle() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignments());
        Path boundaryFile = temporaryDirectory.resolve("boundary.bthop");
        Path innerFile = temporaryDirectory.resolve("partitioned.pthop");
        BoundaryTHopIndex.build(graph, metadata, HORIZON, boundaryFile);
        PartitionedTHopIndex index = PartitionedTHopIndex.build(graph, boundaryFile, innerFile);
        THopIndex unpruned = THopIndex.build(graph, HORIZON);
        THopIndex pruned = THopIndex.buildPruned(graph, HORIZON);

        assertTrue(index.constructionStatistics().boundaryShortcutArcCount() > 0);
        assertEquals(metadata.partitionCount(), index.partitionConstructionStatistics().size());
        for (Map.Entry<Integer, List<Integer>> partition : metadata.verticesByPartition().entrySet()) {
            THopIndex inner = index.innerIndexForPartition(partition.getKey());
            for (int source : partition.getValue()) {
                Map<Integer, ExactTemporalProfile> oracle =
                        SimplePathProfileOracle.allDestinations(graph, source, HORIZON);
                for (int destination : partition.getValue()) {
                    ExactTemporalProfile expected = oracle.getOrDefault(
                                    destination, ExactTemporalProfile.empty())
                            .restrict(HORIZON).asTravelTime();
                    ExactTemporalProfile actual = inner.query(source, destination, HORIZON)
                            .travelTimeProfile();
                    assertTrue(expected.sameFunction(actual),
                            "inner augmented mismatch " + partition.getKey() + ": "
                                    + source + "->" + destination + " "
                                    + firstMismatch(expected, actual));
                }
            }
        }
        for (int source : graph.nodeIds()) {
            Map<Integer, ExactTemporalProfile> oracle =
                    SimplePathProfileOracle.allDestinations(graph, source, HORIZON);
            for (int destination : graph.nodeIds()) {
                ExactTemporalProfile expected = oracle.getOrDefault(
                                destination, ExactTemporalProfile.empty())
                        .restrict(HORIZON).asTravelTime();
                ExactTemporalProfile partitioned = index.query(source, destination, HORIZON)
                        .travelTimeProfile();
                ExactTemporalProfile safelyPruned = index.queryWithSafePruning(
                                source, destination, HORIZON)
                        .travelTimeProfile();
                ExactTemporalProfile fullUnpruned = unpruned.query(source, destination, HORIZON)
                        .travelTimeProfile();
                ExactTemporalProfile fullPruned = pruned.query(source, destination, HORIZON)
                        .travelTimeProfile();
                assertTrue(expected.sameFunction(partitioned), "partitioned mismatch " + source + "->" + destination
                        + " " + firstMismatch(expected, partitioned)
                        + " matchesFull=" + fullUnpruned.sameFunction(partitioned));
                assertTrue(expected.sameFunction(safelyPruned), "safe-prune mismatch " + source + "->" + destination);
                assertTrue(expected.sameFunction(fullUnpruned), "unpruned mismatch " + source + "->" + destination);
                assertTrue(expected.sameFunction(fullPruned), "pruned mismatch " + source + "->" + destination);
            }
        }

    }

    @Test
    void fastestRouteCanLeaveAndReenterItsPartition() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignments());
        Path boundaryFile = temporaryDirectory.resolve("counterexample-boundary.bthop");
        Path innerFile = temporaryDirectory.resolve("counterexample-inner.pthop");
        BoundaryTHopIndex.build(graph, metadata, HORIZON, boundaryFile);
        PartitionedTHopIndex index = PartitionedTHopIndex.build(graph, boundaryFile, innerFile);

        TDGraph inducedGraph = inducedPartitionGraph(graph, metadata, 10);
        ExactTemporalProfile induced = SimplePathProfileOracle.allDestinations(
                        inducedGraph, 3, HORIZON).get(2).asTravelTime();
        ExactTemporalProfile leavesAndReenters = index.query(3, 2, HORIZON).travelTimeProfile();
        assertEquals(6.0, induced.valueAt(20), "the fastest induced route stays inside partition 10");
        assertEquals(3.0, leavesAndReenters.valueAt(20),
                "the fastest route leaves and re-enters partition 10");
    }

    @Test
    void saveReloadIsDeterministicLazyAndValidatesChecksumsAndGraphIdentity() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignments());
        Path boundaryFile = temporaryDirectory.resolve("storage-boundary.bthop");
        Path innerFile = temporaryDirectory.resolve("storage-inner.pthop");
        BoundaryTHopIndex.build(graph, metadata, HORIZON, boundaryFile);
        PartitionedTHopIndex index = PartitionedTHopIndex.build(graph, boundaryFile, innerFile);
        PartitionedTHopIndex loaded = PartitionedTHopIndex.load(graph, boundaryFile, innerFile);
        Path copied = temporaryDirectory.resolve("storage-copy.pthop");
        assertEquals(Files.size(innerFile), loaded.write(copied));
        assertEquals(-1L, Files.mismatch(innerFile, copied));
        assertTrue(index.query(1, 6, Domain.closed(0, 20)).travelTimeProfile().sameFunction(
                loaded.query(1, 6, Domain.closed(0, 20)).travelTimeProfile()));

        byte[] corrupted = Files.readAllBytes(innerFile);
        corrupted[corrupted.length / 2] ^= 1;
        Path corruptedFile = temporaryDirectory.resolve("storage-corrupted.pthop");
        Files.write(corruptedFile, corrupted);
        assertThrows(IOException.class,
                () -> PartitionedTHopIndex.load(graph, boundaryFile, corruptedFile));
        assertThrows(IOException.class,
                () -> PartitionedTHopIndex.load(withChangedTravelTime(graph), boundaryFile, innerFile));
    }

    @Test
    void onePartitionWithoutBoundariesAndAnIsolatedPartitionAreQueryable() throws Exception {
        TDGraph graph = graph(
                List.of(1, 2, 3, 4),
                edge(0, 1, 2, constant(2), 0),
                edge(1, 2, 3, constant(3), 0));
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(
                graph, Map.of(1, 7, 2, 7, 3, 7, 4, 9));
        Path boundaryFile = temporaryDirectory.resolve("no-boundary.bthop");
        Path innerFile = temporaryDirectory.resolve("no-boundary.pthop");
        BoundaryTHopIndex.build(graph, metadata, HORIZON, boundaryFile);
        PartitionedTHopIndex index = PartitionedTHopIndex.build(graph, boundaryFile, innerFile);

        ExactTemporalProfile route = index.query(1, 3, HORIZON).travelTimeProfile();
        assertEquals(5.0, route.valueAt(10));
        assertTrue(index.query(1, 4, HORIZON).travelTimeProfile().isEmpty());
        assertEquals("same-partition", index.query(4, 4, HORIZON).queryStatistics().queryType());
    }

    @Test
    void crossPartitionQueriesReportWorkAndRejectDepartureDomainsOutsideTheIndex() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignments());
        Path boundaryFile = temporaryDirectory.resolve("work-boundary.bthop");
        Path innerFile = temporaryDirectory.resolve("work-inner.pthop");
        BoundaryTHopIndex.build(graph, metadata, HORIZON, boundaryFile);
        PartitionedTHopIndex index = PartitionedTHopIndex.build(graph, boundaryFile, innerFile);
        PartitionedTHopIndex.QueryResult result = index.query(1, 10, Domain.closed(0, 20));
        PartitionedTHopIndex.QueryResult optimized = index.queryWithSafePruning(
                1, 10, Domain.closed(0, 20));
        assertEquals("cross-partition", result.queryStatistics().queryType());
        assertTrue(result.queryStatistics().boundaryCombinationsExamined() > 0);
        assertTrue(result.queryStatistics().boundaryCombinationsSkipped() > 0);
        assertTrue(result.travelTimeProfile().sameFunction(optimized.travelTimeProfile()));
        assertThrows(IllegalArgumentException.class,
                () -> index.query(1, 10, Domain.closed(-1, 20)));
    }

    @Test
    void preferenceScoresDoNotChangeTheFastestPathProfile() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignments());
        Path boundaryFile = temporaryDirectory.resolve("score-boundary.bthop");
        Path innerFile = temporaryDirectory.resolve("score-inner.pthop");
        BoundaryTHopIndex.build(graph, metadata, HORIZON, boundaryFile);
        PartitionedTHopIndex original = PartitionedTHopIndex.build(graph, boundaryFile, innerFile);
        TDGraph scoreChangedGraph = withUniformScore(graph, 1234);
        PartitionedTHopIndex changed = PartitionedTHopIndex.load(
                scoreChangedGraph, boundaryFile, innerFile);

        assertTrue(original.query(1, 10, Domain.closed(0, 20)).travelTimeProfile().sameFunction(
                changed.query(1, 10, Domain.closed(0, 20)).travelTimeProfile()));
    }

    @Test
    void unsupportedTemporalCompositionIsRejectedWithTheCombinationCount() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignments());
        Path boundaryFile = temporaryDirectory.resolve("support-boundary.bthop");
        Path innerFile = temporaryDirectory.resolve("support-inner.pthop");
        BoundaryTHopIndex.build(graph, metadata, HORIZON, boundaryFile);
        PartitionedTHopIndex index = PartitionedTHopIndex.build(graph, boundaryFile, innerFile);

        PartitionedTHopIndex.UnsupportedTemporalCompositionException failure = assertThrows(
                PartitionedTHopIndex.UnsupportedTemporalCompositionException.class,
                () -> index.query(4, 1, HORIZON));
        assertTrue(failure.rejectedCombinations() > 0);
    }

    private static TDGraph mainGraph() {
        PiecewiseLinearFn changing = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 8),
                new PiecewiseLinearFn.Breakpoint(10, 0),
                new PiecewiseLinearFn.Breakpoint(20, 0),
                new PiecewiseLinearFn.Breakpoint(60, 0)));
        PiecewiseLinearFn plateau = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 10),
                new PiecewiseLinearFn.Breakpoint(10, 0),
                new PiecewiseLinearFn.Breakpoint(20, 0),
                new PiecewiseLinearFn.Breakpoint(30, 5),
                new PiecewiseLinearFn.Breakpoint(60, 5)));
        return graph(
                List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
                edge(0, 1, 2, constant(2), 0),
                edge(1, 2, 3, constant(2), 0),
                edge(2, 3, 4, changing, 0),
                edge(3, 4, 5, plateau, 0),
                edge(4, 5, 6, constant(1), 0),
                edge(5, 6, 7, constant(1), 0),
                edge(6, 7, 2, constant(1), 0),
                edge(7, 3, 1, constant(4), 0),
                edge(8, 1, 2, constant(4), 0),
                edge(9, 3, 2, constant(9), 0),
                edge(10, 8, 9, constant(2), 0),
                edge(11, 8, 9, constant(2), 0),
                edge(12, 9, 10, constant(2), 0),
                edge(13, 10, 8, constant(2), 0),
                edge(14, 8, 10, constant(4), 0));
    }

    private static Map<Integer, Integer> assignments() {
        return Map.of(
                1, 10, 2, 10, 3, 10,
                4, 20, 5, 20,
                6, 30, 7, 30,
                8, 40, 9, 40,
                10, 50);
    }

    private static PiecewiseLinearFn constant(double value) {
        return new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, value),
                new PiecewiseLinearFn.Breakpoint(60, value)));
    }

    private static Edge edge(int id, int source, int target, PiecewiseLinearFn travel, int score) {
        return new Edge(id, source, target, 1, travel.minTravelTime(), travel,
                PiecewiseConstFn.constant(travel.domain(), score));
    }

    private static TDGraph graph(List<Integer> vertices, Edge... edges) {
        List<Node> nodes = new ArrayList<>();
        for (int id : vertices) {
            nodes.add(new Node(id, id, 0));
        }
        return new TDGraph(nodes, List.of(edges));
    }

    private static TDGraph inducedPartitionGraph(
            TDGraph graph, GraphPartitionMetadata metadata, int partitionId) {
        List<Node> nodes = metadata.verticesByPartition().get(partitionId).stream()
                .map(graph::node)
                .toList();
        List<Edge> edges = new ArrayList<>();
        for (int arcId : metadata.internalArcIdsByPartition().get(partitionId)) {
            Edge original = graph.edges().get(arcId);
            edges.add(new Edge(
                    edges.size(), original.source(), original.target(), original.distance(),
                    original.baseTravelTime(), original.travelTimeFunction(), original.scoreFunction()));
        }
        return new TDGraph(nodes, edges);
    }

    private static TDGraph withUniformScore(TDGraph graph, int score) {
        List<Edge> edges = graph.edges().stream()
                .map(edge -> new Edge(
                        edge.arcId(), edge.source(), edge.target(), edge.distance(),
                        edge.baseTravelTime(), edge.travelTimeFunction(),
                        PiecewiseConstFn.constant(edge.scoreFunction().domain(), score)))
                .toList();
        return new TDGraph(graph.nodeIds().stream().map(graph::node).toList(), edges);
    }

    private static TDGraph withChangedTravelTime(TDGraph graph) {
        List<Edge> edges = graph.edges().stream()
                .map(edge -> {
                    PiecewiseLinearFn travel = edge.arcId() == 0
                            ? constant(3)
                            : edge.travelTimeFunction();
                    return new Edge(
                            edge.arcId(), edge.source(), edge.target(), edge.distance(),
                            travel.minTravelTime(), travel, edge.scoreFunction());
                })
                .toList();
        return new TDGraph(graph.nodeIds().stream().map(graph::node).toList(), edges);
    }

    private static List<String> summarize(ExactTemporalProfile profile) {
        return profile.cells().stream()
                .map(cell -> cell.domain().intervals() + " " + cell.breakpoints())
                .toList();
    }

    private static String firstMismatch(ExactTemporalProfile expected, ExactTemporalProfile actual) {
        if (!expected.domain().equals(actual.domain())) {
            return "domain expected=" + expected.domain().intervals()
                    + " actual=" + actual.domain().intervals();
        }
        TreeSet<Double> cuts = new TreeSet<>(expected.domain().breakpoints());
        expected.cells().forEach(cell -> cell.breakpoints().forEach(point -> cuts.add(point.minute())));
        actual.cells().forEach(cell -> cell.breakpoints().forEach(point -> cuts.add(point.minute())));
        for (double cut : cuts) {
            if (!expected.domain().contains(cut) || !actual.domain().contains(cut)) {
                return "support at " + cut + " expected=" + expected.domain().contains(cut)
                        + " actual=" + actual.domain().contains(cut);
            }
            if (Domain.canonicalTime(expected.valueAt(cut))
                    != Domain.canonicalTime(actual.valueAt(cut))) {
                return "at " + cut + " expected=" + expected.valueAt(cut)
                        + " actual=" + actual.valueAt(cut);
            }
        }
        return "profile cells differ: expected=" + summarize(expected)
                + " actual=" + summarize(actual);
    }

}
