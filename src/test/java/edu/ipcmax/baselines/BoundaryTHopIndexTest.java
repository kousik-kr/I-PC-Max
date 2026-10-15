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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundaryTHopIndexTest {
    private static final Domain HORIZON = Domain.closed(0, 60);

    @TempDir
    Path temporaryDirectory;

    @Test
    void partitionMetadataAccountsForEveryDirectedArcAndIsDeterministic() {
        TDGraph graph = mainGraph();
        Map<Integer, Integer> firstAssignments = mainAssignments();
        Map<Integer, Integer> reorderedAssignments = new HashMap<>();
        List<Integer> vertices = new ArrayList<>(firstAssignments.keySet());
        vertices.sort((left, right) -> Integer.compare(right, left));
        for (int vertex : vertices) {
            reorderedAssignments.put(vertex, firstAssignments.get(vertex));
        }

        GraphPartitionMetadata first = GraphPartitionMetadata.fromAssignments(graph, firstAssignments);
        GraphPartitionMetadata second = GraphPartitionMetadata.fromAssignments(graph, reorderedAssignments);

        assertEquals(first.partitionByVertex(), second.partitionByVertex());
        assertEquals(first.verticesByPartition(), second.verticesByPartition());
        assertEquals(first.internalArcIdsByPartition(), second.internalArcIdsByPartition());
        assertEquals(first.partitionAdjacency(), second.partitionAdjacency());
        assertEquals(first.crossArcsByPartitionPair(), second.crossArcsByPartitionPair());
        assertEquals(List.of(2, 3, 4, 5, 6, 7, 8, 9, 10), first.boundaryVertexIds());
        assertEquals(5, first.partitionCount());
        assertEquals(6, first.crossPartitionArcCount());

        Set<Integer> internal = new HashSet<>(first.internalDirectedArcIds());
        Set<Integer> cross = new HashSet<>(first.crossPartitionDirectedArcIds());
        assertTrue(java.util.Collections.disjoint(internal, cross));
        Set<Integer> allClassified = new HashSet<>(internal);
        allClassified.addAll(cross);
        assertEquals(graph.edgeCount(), internal.size() + cross.size());
        assertEquals(graph.edges().stream().map(Edge::arcId).collect(java.util.stream.Collectors.toSet()),
                allClassified);
        for (int arcId : first.crossPartitionDirectedArcIds()) {
            Edge edge = graph.edges().get(arcId);
            assertTrue(first.boundaryVertexIds().contains(edge.source()));
            assertTrue(first.boundaryVertexIds().contains(edge.target()));
            int sourcePartition = first.partitionByVertex().get(edge.source());
            int targetPartition = first.partitionByVertex().get(edge.target());
            assertTrue(first.partitionAdjacency().get(sourcePartition).contains(targetPartition));
        }
        assertTrue(first.outgoingBoundaryArcIds().get(3).contains(2));
        assertTrue(first.incomingBoundaryArcIds().get(2).contains(6));
        first.validateAgainst(graph);
    }

    @Test
    void onePartitionHasNoBoundaryAndTwoPartitionsRetainTheirDirectedCutArcs() throws Exception {
        TDGraph onePartitionGraph = graph(
                List.of(1, 2, 3),
                edge(0, 1, 2, constant(1), 0),
                edge(1, 2, 3, constant(2), 0));
        GraphPartitionMetadata onePartition = GraphPartitionMetadata.fromAssignments(
                onePartitionGraph, Map.of(1, 7, 2, 7, 3, 7));
        assertEquals(List.of(), onePartition.boundaryVertexIds());
        assertEquals(List.of(0, 1), onePartition.internalDirectedArcIds());
        Path oneIndexFile = temporaryDirectory.resolve("one-partition.bthop");
        BoundaryTHopIndex emptyBoundaryIndex = BoundaryTHopIndex.build(
                onePartitionGraph, onePartition, HORIZON, oneIndexFile);
        assertEquals(0, emptyBoundaryIndex.constructionStatistics().boundaryProfileSearchCalls());
        assertTrue(BoundaryTHopIndex.load(onePartitionGraph, oneIndexFile).hubOrder().isEmpty());

        TDGraph twoPartitionGraph = graph(
                List.of(1, 2, 3),
                edge(0, 1, 2, constant(1), 0),
                edge(1, 2, 3, constant(2), 0),
                edge(2, 3, 1, constant(4), 0));
        GraphPartitionMetadata twoPartitions = GraphPartitionMetadata.fromAssignments(
                twoPartitionGraph, Map.of(1, 4, 2, 4, 3, 9));
        assertEquals(List.of(1, 2, 3), twoPartitions.boundaryVertexIds());
        assertEquals(List.of(1, 2), twoPartitions.crossPartitionDirectedArcIds());
        assertEquals(List.of(9), twoPartitions.partitionAdjacency().get(4));
        assertEquals(List.of(4), twoPartitions.partitionAdjacency().get(9));
        Path twoIndexFile = temporaryDirectory.resolve("two-partitions.bthop");
        BoundaryTHopIndex twoIndex = BoundaryTHopIndex.build(
                twoPartitionGraph, twoPartitions, HORIZON, twoIndexFile, List.of(3, 1, 2), 2);
        THopIndex twoReference = THopIndex.build(twoPartitionGraph, HORIZON);
        for (int source : twoPartitions.boundaryVertexIds()) {
            Map<Integer, ExactTemporalProfile> oracle =
                    SimplePathProfileOracle.allDestinations(twoPartitionGraph, source, HORIZON);
            for (int destination : twoPartitions.boundaryVertexIds()) {
                ExactTemporalProfile expected = oracle.get(destination).asTravelTime();
                assertTrue(expected.sameFunction(twoIndex.query(source, destination, HORIZON)
                        .travelTimeProfile()));
                assertTrue(expected.sameFunction(twoReference.query(source, destination, HORIZON)
                        .travelTimeProfile()));
            }
        }
    }

    @Test
    void twoPhaseBoundaryIndexMatchesAllFullProfilesAndRoundTripsDeterministically() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, mainAssignments());
        List<Integer> hubOrder = List.of(7, 3, 9, 2, 6, 4, 10, 5, 8);
        Path firstFile = temporaryDirectory.resolve("boundary-1.bthop");
        Path secondFile = temporaryDirectory.resolve("boundary-2.bthop");
        Path copiedFile = temporaryDirectory.resolve("boundary-copy.bthop");

        BoundaryTHopIndex first = BoundaryTHopIndex.build(
                graph, metadata, HORIZON, firstFile, hubOrder, 3);
        BoundaryTHopIndex second = BoundaryTHopIndex.build(
                graph, metadata, HORIZON, secondFile, hubOrder, 3);
        THopIndex unpruned = THopIndex.build(graph, HORIZON);
        THopIndex pruned = THopIndex.buildPruned(graph, HORIZON);
        assertEquals(hubOrder, first.hubOrder());
        assertTrue(BoundaryTHopIndex.verifyChecksum(firstFile));
        assertEquals(Files.size(firstFile), first.constructionStatistics().indexBytesOnDisk());
        assertTrue(first.constructionStatistics().phase1BytesWritten() > 0);
        assertTrue(first.constructionStatistics().diskWritingNanos() > 0);
        assertTrue(first.constructionStatistics().candidateGenerationNanos() > 0);
        assertTrue(first.constructionStatistics().phase1WallNanos() > 0);
        assertTrue(first.constructionStatistics().sampledPeakUsedHeapBytes() > 0);
        assertTrue(first.constructionStatistics().profilesGenerated() > 0);
        assertTrue(first.constructionStatistics().profilesRetained() > 0);
        assertEquals(metadata.boundaryVertexCount(),
                first.constructionStatistics().boundaryProfileSearchCalls());
        assertEquals(-1L, Files.mismatch(firstFile, secondFile),
                "runtime timings and worker scheduling must not affect serialized bytes");
        assertEquals(Files.size(firstFile), first.write(copiedFile));
        assertEquals(-1L, Files.mismatch(firstFile, copiedFile),
                "the explicit index writer must preserve canonical bytes");

        Set<Integer> boundaries = Set.copyOf(metadata.boundaryVertexIds());
        for (int source : metadata.boundaryVertexIds()) {
            Map<Integer, ExactTemporalProfile> oracle =
                    SimplePathProfileOracle.allDestinations(graph, source, HORIZON);
            for (int destination : metadata.boundaryVertexIds()) {
                ExactTemporalProfile expected = oracle.getOrDefault(
                                destination, ExactTemporalProfile.empty())
                        .restrict(HORIZON).asTravelTime();
                ExactTemporalProfile boundary = first.query(source, destination, HORIZON)
                        .travelTimeProfile();
                ExactTemporalProfile full = unpruned.query(source, destination, HORIZON)
                        .travelTimeProfile();
                ExactTemporalProfile incremental = pruned.query(source, destination, HORIZON)
                        .travelTimeProfile();
                assertTrue(expected.sameFunction(boundary), "boundary mismatch " + source + "->" + destination);
                assertTrue(expected.sameFunction(full), "unpruned mismatch " + source + "->" + destination);
                assertTrue(expected.sameFunction(incremental), "M2B mismatch " + source + "->" + destination);
            }
        }
        assertTrue(boundaries.contains(3) && boundaries.contains(2));
        ExactTemporalProfile leavesAndReenters = first.query(3, 2, HORIZON).travelTimeProfile();
        assertEquals(6.0, leavesAndReenters.valueAt(0),
                "the initial winner traverses non-boundary vertex 1 in its own partition");
        assertEquals(3.0, leavesAndReenters.valueAt(20),
                "the later winner leaves partition 1, crosses partitions 2 and 3, then re-enters 1");
        ExactTemporalProfile changesRoute = first.query(3, 2, HORIZON).travelTimeProfile();
        assertTrue(changesRoute.cells().stream().anyMatch(cell -> cell.domain().intervals().stream()
                .anyMatch(interval -> interval.start() == interval.end())),
                "the exact route-switch profile retains owned singleton breakpoint cells");
        assertThrows(IllegalArgumentException.class, () -> first.query(1, 2, HORIZON));

        BoundaryTHopIndex loaded = BoundaryTHopIndex.load(graph, firstFile);
        assertEquals(hubOrder, loaded.hubOrder());
        assertTrue(loaded.constructionStatistics().indexLoadingNanos() > 0);
        assertEquals(Files.size(firstFile), loaded.constructionStatistics().indexBytesOnDisk());
        Path loadedCopy = temporaryDirectory.resolve("boundary-loaded-copy.bthop");
        loaded.write(loadedCopy);
        assertEquals(-1L, Files.mismatch(firstFile, loadedCopy));
        for (int source : metadata.boundaryVertexIds()) {
            for (int destination : metadata.boundaryVertexIds()) {
                assertTrue(first.query(source, destination, HORIZON).travelTimeProfile().sameFunction(
                        loaded.query(source, destination, HORIZON).travelTimeProfile()),
                        "save/reload mismatch " + source + "->" + destination);
            }
        }
    }

    @Test
    void missingCorruptAndWrongGraphIndexesFailWithoutRebuilding() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, mainAssignments());
        Path indexFile = temporaryDirectory.resolve("checked.bthop");
        BoundaryTHopIndex.build(graph, metadata, HORIZON, indexFile);
        byte[] original = Files.readAllBytes(indexFile);
        byte[] corrupted = original.clone();
        corrupted[corrupted.length - 1] ^= 1;
        Path corruptedFile = temporaryDirectory.resolve("corrupt.bthop");
        Files.write(corruptedFile, corrupted);

        assertFalse(BoundaryTHopIndex.verifyChecksum(corruptedFile));
        assertThrows(IOException.class, () -> BoundaryTHopIndex.load(graph, corruptedFile));
        assertThrows(IOException.class, () -> BoundaryTHopIndex.load(
                graph, temporaryDirectory.resolve("missing.bthop")));
        TDGraph scoresChanged = new TDGraph(
                graph.nodeIds().stream().map(graph::node).toList(),
                graph.edges().stream().map(edge -> new Edge(
                        edge.arcId(), edge.source(), edge.target(), edge.distance(), edge.baseTravelTime(),
                        edge.travelTimeFunction(), PiecewiseConstFn.constant(edge.scoreFunction().domain(), 99)))
                        .toList());
        assertTrue(BoundaryTHopIndex.load(scoresChanged, indexFile)
                .query(3, 2, HORIZON).travelTimeProfile()
                .sameFunction(BoundaryTHopIndex.load(graph, indexFile)
                        .query(3, 2, HORIZON).travelTimeProfile()),
                "score functions are outside the preference-free THop index identity");
        TDGraph differentGraph = graph(List.of(1, 2), edge(0, 1, 2, constant(3), 0));
        assertThrows(IOException.class, () -> BoundaryTHopIndex.load(differentGraph, indexFile));
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

    private static Map<Integer, Integer> mainAssignments() {
        return Map.of(
                1, 10, 2, 10, 3, 10,
                4, 20, 5, 20,
                6, 30, 7, 30,
                8, 40, 9, 40,
                10, 50);
    }

    private static PiecewiseLinearFn constant(double travelTime) {
        return new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, travelTime),
                new PiecewiseLinearFn.Breakpoint(60, travelTime)));
    }

    private static Edge edge(int id, int source, int target, PiecewiseLinearFn travel, int score) {
        return new Edge(id, source, target, 1, travel.minTravelTime(), travel,
                PiecewiseConstFn.constant(travel.domain(), score));
    }

    private static TDGraph graph(List<Integer> ids, Edge... edges) {
        List<Node> nodes = ids.stream().map(id -> new Node(id, id, id * 2L)).toList();
        return new TDGraph(nodes, List.of(edges));
    }
}
