package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTime;
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
    void supportBoundaryCrossingOnQueryFourToOneUsesExactPreimages() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignments());
        Path boundary = temporaryDirectory.resolve("failure-a.bthop");
        Path inner = temporaryDirectory.resolve("failure-a.pthop");
        BoundaryTHopIndex.build(graph, metadata, HORIZON, boundary);
        PartitionedTHopIndex index = PartitionedTHopIndex.build(graph, boundary, inner);

        int sourcePartition = metadata.partitionByVertex().get(4);
        int destinationPartition = metadata.partitionByVertex().get(1);
        ExactArrivalProfile prefix = index.innerIndexForPartition(sourcePartition)
                .queryExactArrivalProfile(4, 5, HORIZON);
        ExactArrivalProfile middle = index.boundaryIndex().queryExactArrivalProfile(5, 3, HORIZON);
        ExactArrivalProfile suffix = index.innerIndexForPartition(destinationPartition)
                .queryExactArrivalProfile(3, 1, HORIZON);
        ExactArrivalProfile boundaryToDestination = middle.composeAfter(suffix);
        ExactArrivalProfile candidate = prefix.composeAfter(boundaryToDestination);
        ExactTime exactRoot = ExactTime.of(java.math.BigInteger.valueOf(62), java.math.BigInteger.valueOf(3));

        assertTrue(prefix.domain().contains(exactRoot));
        assertEquals(ExactTime.of(21), prefix.valueAtExact(exactRoot));
        assertTrue(boundaryToDestination.domain().contains(ExactTime.of(21)));
        assertEquals(ExactTime.of(30), boundaryToDestination.valueAtExact(ExactTime.of(21)));
        assertEquals(ExactTime.of(30), candidate.valueAtExact(exactRoot));
        assertTrue(!candidate.isEmpty());
        assertTrue(candidate.segments().stream().anyMatch(segment ->
                        segment.interval().start().equals(exactRoot) || segment.interval().end().equals(exactRoot)),
                "the composition retains the exact 62/3 event location");
        assertTrue(candidate.domain().contains(ExactTime.parseDecimal("20.666666666667")));
        assertEquals("9.333333333334", index.query(4, 1, HORIZON)
                .exactTravelTimeAt(20.666666666667).roundedDecimal(12).toPlainString());
    }

    @Test
    void fractionalDepartureMatchesEveryThopVariantAndTheRationalProfileOracle() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignments());
        Path boundary = temporaryDirectory.resolve("failure-b.bthop");
        Path inner = temporaryDirectory.resolve("failure-b.pthop");
        BoundaryTHopIndex.build(graph, metadata, HORIZON, boundary);
        PartitionedTHopIndex partitioned = PartitionedTHopIndex.build(graph, boundary, inner);
        THopIndex unpruned = THopIndex.build(graph, HORIZON);
        THopIndex pruned = THopIndex.buildPruned(graph, HORIZON);

        double departure = 20.666666666667;
        RationalTemporalReference.Q query = RationalTemporalReference.Q.decimal("20.666666666667");
        RationalTemporalReference.Profile oracleFourToOne = RationalTemporalReference
                .allDestinationProfiles(graph, 4, HORIZON).get(1).asTravelTime();
        ExactTime expectedFourToOne = exact(oracleFourToOne.valueAt(query));
        var partitionedFourToOne = partitioned.query(
                4, 1, Domain.closed(departure, departure)).exactTravelTimeProfile();
        var fullFourToOne = unpruned.query(
                4, 1, Domain.closed(departure, departure)).exactTravelTimeProfile();
        var fullHorizonFourToOne = unpruned.query(4, 1, HORIZON).exactTravelTimeProfile();
        var prunedFourToOne = pruned.query(
                4, 1, Domain.closed(departure, departure)).exactTravelTimeProfile();
        var directFourToOne = new FifoFastestPathProfileSearch(graph)
                .search(4, HORIZON).exactProfileTo(1).orElseThrow().asTravelTime();

        assertEquals("9.3333333333335", oracleFourToOne.valueAt(query).rounded(13));
        assertEquals(expectedFourToOne, partitionedFourToOne.valueAt(departure));
        assertEquals(expectedFourToOne, fullFourToOne.valueAt(departure));
        assertEquals(expectedFourToOne, fullHorizonFourToOne.restrict(
                Domain.closed(departure, departure)).valueAt(departure));
        assertEquals(expectedFourToOne, prunedFourToOne.valueAt(departure));
        assertEquals(expectedFourToOne, directFourToOne.valueAt(departure));
        assertEquals("9.333333333334", expectedFourToOne.roundedDecimal(12).toPlainString());
        assertEquals("5", partitioned.boundaryIndex().query(5, 3,
                Domain.closed(departure, departure)).exactTravelTimeAt(departure).toString());

        RationalTemporalReference.Profile oracleOneToSeven = RationalTemporalReference
                .allDestinationProfiles(graph, 1, HORIZON).get(7).asTravelTime();
        ExactTime expectedOneToSeven = exact(oracleOneToSeven.valueAt(query));
        var partitionedOneToSeven = partitioned.query(
                1, 7, Domain.closed(departure, departure)).exactTravelTimeProfile();
        var fullOneToSeven = unpruned.query(
                1, 7, Domain.closed(departure, departure)).exactTravelTimeProfile();
        var prunedOneToSeven = pruned.query(
                1, 7, Domain.closed(departure, departure)).exactTravelTimeProfile();
        var directOneToSeven = new FifoFastestPathProfileSearch(graph)
                .search(1, HORIZON).exactProfileTo(7).orElseThrow().asTravelTime();
        assertEquals(expectedOneToSeven, partitionedOneToSeven.valueAt(departure));
        assertEquals(expectedOneToSeven, fullOneToSeven.valueAt(departure));
        assertEquals(expectedOneToSeven, prunedOneToSeven.valueAt(departure));
        assertEquals(expectedOneToSeven, directOneToSeven.valueAt(departure));
        assertEquals("8.333333333334", expectedOneToSeven.roundedDecimal(12).toPlainString());
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

    private static ExactTime exact(RationalTemporalReference.Q value) {
        return ExactTime.of(value.numerator(), value.denominator());
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
