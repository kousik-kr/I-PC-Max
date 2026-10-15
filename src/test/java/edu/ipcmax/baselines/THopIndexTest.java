package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.QuerySpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class THopIndexTest {
    private static final Domain HORIZON = Domain.closed(0, 60);
    private static final Domain QUERY = Domain.closed(0, 20);

    @Test
    void functionalSearchAndUnprunedHubQueriesMatchIndependentSimplePathOracleOnEveryCell() {
        TDGraph graph = switchingGraph(0);
        THopIndex index = THopIndex.build(graph, HORIZON);
        FifoFastestPathProfileSearch search = new FifoFastestPathProfileSearch(graph);

        for (int source : graph.nodeIds()) {
            Map<Integer, ExactTemporalProfile> oracle =
                    SimplePathProfileOracle.allDestinations(graph, source, HORIZON);
            FifoFastestPathProfileSearch.SearchResult direct = search.search(source, HORIZON);
            for (int destination : graph.nodeIds()) {
                ExactTemporalProfile expected = oracle.getOrDefault(
                        destination, ExactTemporalProfile.empty());
                ExactTemporalProfile searched = direct.profileTo(destination)
                        .orElse(ExactTemporalProfile.empty());
                assertTrue(expected.sameFunction(searched),
                        "direct profile search disagrees with path oracle: " + source + "->" + destination);

                ExactTemporalProfile expectedTravel = expected.restrict(QUERY).asTravelTime();
                THopIndex.QueryResult result = index.query(source, destination, QUERY);
                assertTrue(expectedTravel.sameFunction(result.travelTimeProfile()),
                        "THop disagrees with path oracle: " + source + "->" + destination);
            }
        }

        THopIndex.QueryResult switching = index.query(1, 4, QUERY);
        assertEquals(7.0, switching.travelTimeAt(0));
        assertEquals(2.0, switching.travelTimeAt(10));
        assertTrue(switching.travelTimeProfile().sameFunction(
                index.query(1, 4, QUERY).travelTimeProfile()));
        assertTrue(switching.statistics().commonHubCount() >= 2);
        assertEquals(7.0, index.outgoingLabels(1).get(4).valueAt(0) - 0.0);
        assertEquals(7.0, index.incomingLabels(4).get(1).valueAt(0) - 0.0);
        assertFalse(index.outgoingLabels(4).containsKey(1));
        assertFalse(index.incomingLabels(1).containsKey(4));
        assertFalse(winningHubsAt(index, 1, 4, 0).contains(3));
        assertTrue(winningHubsAt(index, 1, 4, 10).contains(3));
        assertTrue(index.statistics().labelCount() > 0);
        assertTrue(index.statistics().temporalFunctionPieces() > 0);
        assertTrue(index.statistics().estimatedIndexBytes() > 0);
        assertTrue(index.statistics().constructionNanos() > 0);
        assertTrue(index.statistics().sampledPeakUsedHeapBytes() > 0);
        assertTrue(switching.statistics().queryNanos() > 0);
        assertTrue(switching.statistics().temporalCompositions() > 0);
        assertTrue(switching.statistics().envelopeOperations() > 0);
        assertTrue(switching.statistics().sampledPeakUsedHeapBytes() > 0);
    }

    @Test
    void profileSearchHandlesPartialSupportAndEndpointOwnershipWithoutInventingDepartures() {
        PiecewiseLinearFn first = constant(0, 10, 1).restrict(Domain.closed(0, 4));
        PiecewiseLinearFn second = constant(0, 10, 1).restrict(
                Domain.of(new Domain.Interval(5, 10, false, true)));
        TDGraph graph = graph(
                List.of(1, 2, 3, 4, 5),
                edge(0, 1, 2, first, 0),
                edge(1, 2, 4, constant(0, 10, 1), 0),
                edge(2, 1, 3, second, 0),
                edge(3, 3, 4, constant(0, 10, 1), 0));
        Domain horizon = Domain.closed(0, 10);
        assertThrows(IllegalArgumentException.class, () -> THopIndex.build(graph, horizon));
        FifoFastestPathProfileSearch search = new FifoFastestPathProfileSearch(graph);
        ExactTemporalProfile searched = search.search(1, horizon).profileTo(4).orElseThrow();
        Map<Integer, ExactTemporalProfile> oracle =
                SimplePathProfileOracle.allDestinations(graph, 1, horizon);
        ExactTemporalProfile expected = oracle.get(4).restrict(horizon).asTravelTime();
        ExactTemporalProfile actual = searched.restrict(horizon).asTravelTime();

        assertTrue(expected.sameFunction(actual));
        assertTrue(actual.domain().contains(0));
        assertTrue(actual.domain().contains(4));
        assertFalse(actual.domain().contains(5));
        assertTrue(actual.domain().contains(6));
        assertFalse(actual.domain().contains(10));
        assertEquals(2.0, actual.valueAt(4));
        assertEquals(2.0, actual.valueAt(6));
        assertFalse(search.search(1, horizon).profileTo(5).isPresent());
    }

    @Test
    void thopKeepsClosedTemporalSupportBoundaryExact() {
        TDGraph graph = graph(
                List.of(1, 2, 3),
                edge(0, 1, 2, constant(0, 10, 1), 0),
                edge(1, 2, 3, constant(0, 10, 1), 0));
        Domain horizon = Domain.closed(0, 10);
        THopIndex index = THopIndex.build(graph, horizon);
        ExactTemporalProfile travel = index.query(1, 3, horizon).travelTimeProfile();

        assertTrue(travel.domain().contains(0));
        assertTrue(travel.domain().contains(9));
        assertFalse(travel.domain().contains(10));
        assertEquals(2.0, travel.valueAt(9));
        assertFalse(index.query(1, 3, Domain.closed(10, 10)).hasReachableDepartures());
    }

    @Test
    void fifoPlateausEqualRoutesZeroLengthAndMissingRoutesAreExact() {
        PiecewiseLinearFn plateau = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 10),
                new PiecewiseLinearFn.Breakpoint(10, 0),
                new PiecewiseLinearFn.Breakpoint(20, 0),
                new PiecewiseLinearFn.Breakpoint(30, 5),
                new PiecewiseLinearFn.Breakpoint(40, 5)));
        TDGraph graph = graph(
                List.of(1, 2, 3, 4),
                edge(0, 1, 2, plateau, 0),
                edge(1, 2, 4, constant(0, 40, 0), 0),
                edge(2, 1, 3, constant(0, 40, 4), 0),
                edge(3, 3, 4, constant(0, 40, 4), 0),
                edge(4, 1, 4, constant(0, 40, 8), 0));
        Domain horizon = Domain.closed(0, 40);
        THopIndex index = THopIndex.build(graph, horizon);
        Map<Integer, ExactTemporalProfile> oracle =
                SimplePathProfileOracle.allDestinations(graph, 1, horizon);
        for (int destination : graph.nodeIds()) {
            ExactTemporalProfile expected = oracle.getOrDefault(
                    destination, ExactTemporalProfile.empty()).restrict(horizon).asTravelTime();
            ExactTemporalProfile actual = index.query(1, destination, horizon).travelTimeProfile();
            assertTrue(expected.sameFunction(actual), "destination=" + destination);
        }

        ExactTemporalProfile plateauTravel = index.query(1, 2, horizon).travelTimeProfile();
        assertEquals(10.0, plateauTravel.valueAt(0));
        assertEquals(0.0, plateauTravel.valueAt(10));
        assertEquals(0.0, plateauTravel.valueAt(20));
        assertEquals(5.0, plateauTravel.valueAt(30));
        assertTrue(index.query(1, 1, horizon).hasReachableDepartures());
        assertEquals(0.0, index.query(1, 1, horizon).travelTimeAt(10));
    }

    @Test
    void preferenceScoresAndPaceBudgetsDoNotEnterThopProfileSearch() throws Exception {
        TDGraph zeroScore = switchingGraph(0);
        TDGraph highScore = switchingGraph(17);
        THopIndex first = THopIndex.build(zeroScore, HORIZON);
        THopIndex second = THopIndex.build(highScore, HORIZON);
        ExactTemporalProfile baseline = first.query(1, 4, QUERY).travelTimeProfile();
        assertTrue(baseline.sameFunction(second.query(1, 4, QUERY).travelTimeProfile()));

        QuerySpec tight = new QuerySpec(1, 4, 0, 20, 0, 1);
        QuerySpec loose = new QuerySpec(1, 4, 0, 20, 100, 1);
        ExactTemporalProfile tightContext = first.query(
                tight.source(), tight.destination(), tight.departureDomain()).travelTimeProfile();
        ExactTemporalProfile looseContext = first.query(
                loose.source(), loose.destination(), loose.departureDomain()).travelTimeProfile();
        assertTrue(tightContext.sameFunction(looseContext));
        assertEquals(3, THopIndex.class.getMethod(
                "query", int.class, int.class, Domain.class).getParameterCount());
    }

    private static TDGraph switchingGraph(int score) {
        PiecewiseLinearFn falling = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 8),
                new PiecewiseLinearFn.Breakpoint(10, 0),
                new PiecewiseLinearFn.Breakpoint(60, 0)));
        return graph(
                List.of(1, 2, 3, 4, 5),
                edge(0, 1, 2, constant(0, 60, 4), score),
                edge(1, 2, 4, constant(0, 60, 4), score),
                edge(2, 1, 3, falling, score),
                edge(3, 3, 4, constant(0, 60, 2), score),
                edge(4, 1, 4, constant(0, 60, 7), score));
    }

    private static List<Integer> winningHubsAt(THopIndex index, int source, int destination, double departure) {
        List<Integer> winners = new ArrayList<>();
        double best = Double.POSITIVE_INFINITY;
        for (int hub : index.outgoingLabels(source).keySet()) {
            ExactTemporalProfile first = index.outgoingLabels(source).get(hub);
            ExactTemporalProfile second = index.incomingLabels(destination).get(hub);
            if (second == null) {
                continue;
            }
            ExactTemporalProfile composed = first.composeAfter(second);
            if (!composed.domain().contains(departure)) {
                continue;
            }
            double travel = composed.valueAt(departure) - departure;
            if (travel < best) {
                best = travel;
                winners.clear();
                winners.add(hub);
            } else if (travel == best) {
                winners.add(hub);
            }
        }
        return List.copyOf(winners);
    }

    private static PiecewiseLinearFn constant(double start, double end, double travelTime) {
        return new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(start, travelTime),
                new PiecewiseLinearFn.Breakpoint(end, travelTime)));
    }

    private static Edge edge(int id, int source, int target, PiecewiseLinearFn travel, int score) {
        return new Edge(id, source, target, 1, travel.minTravelTime(), travel,
                PiecewiseConstFn.constant(travel.domain(), score));
    }

    private static TDGraph graph(List<Integer> nodeIds, Edge... edges) {
        List<Node> nodes = nodeIds.stream().map(id -> new Node(id, id, id)).toList();
        return new TDGraph(nodes, List.of(edges));
    }
}
