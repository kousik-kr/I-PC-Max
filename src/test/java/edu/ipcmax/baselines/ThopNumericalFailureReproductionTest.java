package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic reproductions of the two M2C-B temporal failures. */
class ThopNumericalFailureReproductionTest {
    private static final Domain HORIZON = Domain.closed(0, 60);

    @TempDir
    Path temporaryDirectory;

    @Test
    void supportBoundaryCrossingOnQueryFourToOneIsReproduced() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignments());
        Path boundary = temporaryDirectory.resolve("failure-a.bthop");
        Path inner = temporaryDirectory.resolve("failure-a.pthop");
        BoundaryTHopIndex.build(graph, metadata, HORIZON, boundary);
        PartitionedTHopIndex index = PartitionedTHopIndex.build(graph, boundary, inner);

        List<String> combinationDetails = new ArrayList<>();
        ExactTemporalProfile prefixAtFailure = null;
        ExactTemporalProfile outerAtFailure = null;
        int sourcePartition = metadata.partitionByVertex().get(4);
        int destinationPartition = metadata.partitionByVertex().get(1);
        List<Integer> sourceBoundaries = boundaryVertices(metadata, sourcePartition);
        List<Integer> destinationBoundaries = boundaryVertices(metadata, destinationPartition);
        for (int sourceBoundary : sourceBoundaries) {
            ExactTemporalProfile prefix = index.innerIndexForPartition(sourcePartition)
                    .queryArrivalProfile(4, sourceBoundary, HORIZON);
            for (int destinationBoundary : destinationBoundaries) {
                ExactTemporalProfile middle = index.boundaryIndex().queryArrivalProfile(
                        sourceBoundary, destinationBoundary, HORIZON);
                ExactTemporalProfile suffix = index.innerIndexForPartition(destinationPartition)
                        .queryArrivalProfile(destinationBoundary, 1, HORIZON);
                ExactTemporalProfile boundaryToDestination;
                try {
                    boundaryToDestination = middle.composeAfter(suffix);
                } catch (IllegalArgumentException failure) {
                    combinationDetails.add("stage=middle-after-suffix boundary="
                            + sourceBoundary + "->" + destinationBoundary
                            + " prefix=" + summary(prefix)
                            + " middle=" + summary(middle)
                            + " suffix=" + summary(suffix)
                            + " cause=" + failure.getMessage());
                    continue;
                }
                try {
                    prefix.composeAfter(boundaryToDestination);
                } catch (IllegalArgumentException failure) {
                    if (sourceBoundary == 5 && destinationBoundary == 3) {
                        prefixAtFailure = prefix;
                        outerAtFailure = boundaryToDestination;
                    }
                    combinationDetails.add("stage=prefix-after-boundary-to-destination boundary="
                            + sourceBoundary + "->" + destinationBoundary
                            + " prefix=" + summary(prefix)
                            + " middle=" + summary(middle)
                            + " suffix=" + summary(suffix)
                            + " boundaryToDestination=" + summary(boundaryToDestination)
                            + " cause=" + failure.getMessage());
                }
            }
        }

        PartitionedTHopIndex.UnsupportedTemporalCompositionException failure = assertThrows(
                PartitionedTHopIndex.UnsupportedTemporalCompositionException.class,
                () -> index.query(4, 1, HORIZON));
        assertTrue(failure.getMessage().contains("4->1"));
        assertTrue(combinationDetails.stream().anyMatch(detail ->
                detail.contains("boundary=5->3")
                        && detail.contains("cause=time is outside piecewise profile closure: 21.000000000001")
                        && detail.contains("Interval[start=21.0, end=21.0")),
                "expected the 5->3 composition to cross its owned singleton at 21: " + combinationDetails);
        assertNotNull(prefixAtFailure);
        assertNotNull(outerAtFailure);
        assertEquals(HORIZON, prefixAtFailure.domain());
        assertEquals(Domain.closed(0, 55), outerAtFailure.domain());
        assertEquals(21.000000000001, prefixAtFailure.valueAt(20.666666666667));
        assertEquals(30.0, outerAtFailure.valueAt(21.0));
        assertTrue(outerAtFailure.cells().stream().anyMatch(cell ->
                cell.domain().equals(Domain.closed(21, 21))
                        && cell.valueAt(21) == 30.0));
    }

    @Test
    void fractionalDepartureMismatchIsReproducedForBothRouteProfiles() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignments());
        Path boundary = temporaryDirectory.resolve("failure-b.bthop");
        Path inner = temporaryDirectory.resolve("failure-b.pthop");
        BoundaryTHopIndex.build(graph, metadata, HORIZON, boundary);
        PartitionedTHopIndex partitioned = PartitionedTHopIndex.build(graph, boundary, inner);
        THopIndex unpruned = THopIndex.build(graph, HORIZON);
        THopIndex pruned = THopIndex.buildPruned(graph, HORIZON);

        double departure = 20.666666666667;
        ExactTemporalProfile oracleFourToOneArrival = SimplePathProfileOracle
                .allDestinations(graph, 4, HORIZON).get(1);
        ExactTemporalProfile oracleFourToOne = oracleFourToOneArrival.asTravelTime();
        ExactTemporalProfile partitionedFourToOne = partitioned.query(
                4, 1, Domain.closed(departure, departure)).travelTimeProfile();
        ExactTemporalProfile fullFourToOne = unpruned.query(
                4, 1, Domain.closed(departure, departure)).travelTimeProfile();
        ExactTemporalProfile fullHorizonFourToOne = unpruned.query(
                4, 1, HORIZON).travelTimeProfile();
        ExactTemporalProfile prunedFourToOne = pruned.query(
                4, 1, Domain.closed(departure, departure)).travelTimeProfile();
        ExactTemporalProfile directFourToOne = new FifoFastestPathProfileSearch(graph)
                .search(4, HORIZON).profiles().get(1).asTravelTime();
        double boundaryFiveToThree = partitioned.boundaryIndex().query(
                5, 3, Domain.closed(departure, departure)).travelTimeProfile().valueAt(departure);

        assertEquals(30.0, oracleFourToOneArrival.valueAt(departure));
        assertEquals(9.333333333333,
                Domain.canonicalTime(oracleFourToOneArrival.valueAt(departure) - departure));
        assertEquals(9.333333333334, oracleFourToOne.valueAt(departure),
                "breakpoint-wise A(t)-t conversion and query-time A(t)-t round differently");
        assertEquals(9.333333333333, partitionedFourToOne.valueAt(departure));
        assertEquals(9.333333333333, fullFourToOne.valueAt(departure));
        assertEquals(9.333333333334, fullHorizonFourToOne.valueAt(departure),
                "the unpruned result also changes with the query domain");
        assertEquals(9.333333333333, prunedFourToOne.valueAt(departure));
        assertEquals(9.333333333334, directFourToOne.valueAt(departure));
        assertEquals(5.0, boundaryFiveToThree);

        ExactTemporalProfile directOneToSeven = new FifoFastestPathProfileSearch(graph)
                .search(1, HORIZON).profiles().get(7).asTravelTime();

        ExactTemporalProfile oracleOneToSeven = SimplePathProfileOracle
                .allDestinations(graph, 1, HORIZON).get(7).asTravelTime();
        ExactTemporalProfile partitionedOneToSeven = partitioned.query(
                1, 7, Domain.closed(departure, departure)).travelTimeProfile();
        ExactTemporalProfile fullOneToSeven = unpruned.query(
                1, 7, Domain.closed(departure, departure)).travelTimeProfile();
        ExactTemporalProfile prunedOneToSeven = pruned.query(
                1, 7, Domain.closed(departure, departure)).travelTimeProfile();
        assertEquals(8.333333333334, oracleOneToSeven.valueAt(departure));
        assertEquals(8.333333333333, partitionedOneToSeven.valueAt(departure));
        assertEquals(8.333333333333, fullOneToSeven.valueAt(departure));
        assertEquals(8.333333333333, prunedOneToSeven.valueAt(departure));
        assertEquals(8.333333333334, directOneToSeven.valueAt(departure));
    }

    @Test
    void independentRationalReplayKeepsRootsAndIntermediateArrivalsExact() {
        TDGraph graph = mainGraph();
        RationalTemporalReference.Q departure = RationalTemporalReference.Q.decimal("20.666666666667");

        RationalTemporalReference.Replay fourToOne = RationalTemporalReference.bestSimpleRoute(
                graph, 4, 1, departure);
        assertEquals(List.of(3, 4, 5, 6, 1, 7), fourToOne.arcIds());
        assertEquals("21.0000000000005", fourToOne.steps().get(0).arrivalTime().rounded(13));
        assertEquals("20.666666666667", fourToOne.steps().get(0).entryTime().rounded(12));
        assertEquals("21.0000000000005", fourToOne.steps().get(1).entryTime().rounded(13));
        assertEquals("22.0000000000005", fourToOne.steps().get(2).entryTime().rounded(13));
        assertEquals("23.0000000000005", fourToOne.steps().get(3).entryTime().rounded(13));
        assertEquals("24.0000000000005", fourToOne.steps().get(4).entryTime().rounded(13));
        assertEquals("26.0000000000005", fourToOne.steps().get(5).entryTime().rounded(13));
        assertEquals("1/2", fourToOne.steps().get(0).travelFunction().slope().toString());
        assertEquals("-10", fourToOne.steps().get(0).travelFunction().intercept().toString());
        assertEquals("20", fourToOne.steps().get(0).segmentStart().toString());
        assertEquals("30", fourToOne.steps().get(0).segmentEnd().toString());
        assertEquals("22.0000000000005", fourToOne.steps().get(1).arrivalTime().rounded(13));
        assertEquals("23.0000000000005", fourToOne.steps().get(2).arrivalTime().rounded(13));
        assertEquals("24.0000000000005", fourToOne.steps().get(3).arrivalTime().rounded(13));
        assertEquals("26.0000000000005", fourToOne.steps().get(4).arrivalTime().rounded(13));
        assertEquals("30.0000000000005", fourToOne.arrival().rounded(13));
        assertEquals("9.3333333333335", fourToOne.travelTime().rounded(13));
        assertEquals("9.333333333334", fourToOne.travelTime().rounded(12));
        Edge firstRouteEdge = graph.edges().get(3);
        assertEquals(0.333333333333, firstRouteEdge.travelTimeFunction()
                .travelTimeAt(Double.parseDouble("20.666666666667")));
        assertEquals(21.0, firstRouteEdge.travelTimeFunction()
                .arrivalTimeAt(Double.parseDouble("20.666666666667")));
        double functionalFirstArrival = TemporalArrivalProfiles.pathArrivalProfile(
                graph, 4, 5, Domain.closed(20.666666666667, 20.666666666667),
                new edu.ipcmax.core.validate.Path(List.of(3)))
                .orElseThrow().valueAt(20.666666666667);
        assertEquals(21.0, functionalFirstArrival);

        RationalTemporalReference.Replay oneToSeven = RationalTemporalReference.bestSimpleRoute(
                graph, 1, 7, departure);
        assertEquals(List.of(0, 1, 2, 3, 4, 5), oneToSeven.arcIds());
        assertEquals("27.0000000000005", oneToSeven.steps().get(3).arrivalTime().rounded(13));
        assertEquals("29.0000000000005", oneToSeven.arrival().rounded(13));
        assertEquals("8.3333333333335", oneToSeven.travelTime().rounded(13));
        assertEquals("8.333333333334", oneToSeven.travelTime().rounded(12));

        RationalTemporalReference.Affine prefix = RationalTemporalReference.arrivalAffine(
                graph, 4, List.of(3), departure);
        assertEquals(new RationalTemporalReference.Q(
                java.math.BigInteger.valueOf(3), java.math.BigInteger.valueOf(2)), prefix.slope());
        assertEquals("-10", prefix.intercept().toString());
        RationalTemporalReference.Q exactRoot = prefix.rootFor(RationalTemporalReference.Q.of(21));
        assertEquals("62/3", exactRoot.toString());
        assertEquals("20.666666666667", exactRoot.rounded(12));
        assertEquals("1/3000000000000", departure.subtract(exactRoot).toString());
        assertEquals("21.0000000000005", prefix.at(departure).rounded(13));
        RationalTemporalReference.Q storedEnd = RationalTemporalReference.Q.decimal("23.333333333333");
        RationalTemporalReference.Affine storedPrefix = new RationalTemporalReference.Affine(
                RationalTemporalReference.Q.of(5).divide(storedEnd.subtract(
                        RationalTemporalReference.Q.of(20))),
                RationalTemporalReference.Q.of(20).subtract(
                        RationalTemporalReference.Q.of(5).divide(storedEnd.subtract(
                                RationalTemporalReference.Q.of(20)))
                                .multiply(RationalTemporalReference.Q.of(20))));
        RationalTemporalReference.Q storedRoot = storedPrefix.rootFor(
                RationalTemporalReference.Q.of(21));
        assertEquals("103333333333333/5000000000000", storedRoot.toString());
        assertEquals("20.666666666667", storedRoot.rounded(12));
        assertEquals("21.0000000000006", storedPrefix.at(departure).rounded(13));
        assertEquals("20", prefix.crossing(new RationalTemporalReference.Affine(
                RationalTemporalReference.Q.ONE, RationalTemporalReference.Q.ZERO)).toString());

        assertTrue(RationalTemporalReference.supports(Domain.closed(21, 21),
                RationalTemporalReference.Q.of(21)));
        assertTrue(!RationalTemporalReference.supports(Domain.open(21, 25),
                RationalTemporalReference.Q.of(21)));
        assertTrue(RationalTemporalReference.supports(Domain.open(21, 25),
                RationalTemporalReference.Q.decimal("21.000000000001")));

        double scalarArrival = TemporalPathReplay.arrivalTime(
                graph, 4, 1, Double.parseDouble("20.666666666667"),
                new edu.ipcmax.core.validate.Path(List.of(3, 4, 5, 6, 1, 7)));
        assertEquals(30.0, scalarArrival,
                "edge-by-edge tick rounding differs from exact route replay before output rounding");
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
        return graph(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
                edge(0, 1, 2, constant(2)), edge(1, 2, 3, constant(2)),
                edge(2, 3, 4, changing), edge(3, 4, 5, plateau),
                edge(4, 5, 6, constant(1)), edge(5, 6, 7, constant(1)),
                edge(6, 7, 2, constant(1)), edge(7, 3, 1, constant(4)),
                edge(8, 1, 2, constant(4)), edge(9, 3, 2, constant(9)),
                edge(10, 8, 9, constant(2)), edge(11, 8, 9, constant(2)),
                edge(12, 9, 10, constant(2)), edge(13, 10, 8, constant(2)),
                edge(14, 8, 10, constant(4)));
    }

    private static Map<Integer, Integer> assignments() {
        return Map.of(1, 10, 2, 10, 3, 10, 4, 20, 5, 20, 6, 30, 7, 30,
                8, 40, 9, 40, 10, 50);
    }

    private static List<Integer> boundaryVertices(GraphPartitionMetadata metadata, int partition) {
        TreeSet<Integer> result = new TreeSet<>();
        for (int vertex : metadata.verticesByPartition().get(partition)) {
            if (metadata.boundaryVertexIds().contains(vertex)) {
                result.add(vertex);
            }
        }
        return List.copyOf(result);
    }

    private static String summary(ExactTemporalProfile profile) {
        return profile.cells().stream()
                .map(cell -> cell.domain().intervals() + " => " + cell.breakpoints())
                .toList().toString();
    }

    private static PiecewiseLinearFn constant(double value) {
        return new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, value),
                new PiecewiseLinearFn.Breakpoint(60, value)));
    }

    private static Edge edge(int id, int source, int target, PiecewiseLinearFn travel) {
        return new Edge(id, source, target, 1, travel.minTravelTime(), travel,
                PiecewiseConstFn.constant(travel.domain(), 0));
    }

    private static TDGraph graph(List<Integer> vertices, Edge... edges) {
        List<Node> nodes = vertices.stream().map(id -> new Node(id, id, 0)).toList();
        return new TDGraph(nodes, List.of(edges));
    }
}
