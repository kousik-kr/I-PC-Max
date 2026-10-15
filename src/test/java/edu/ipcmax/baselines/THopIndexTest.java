package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.profile.TimeProfile;
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
        THopIndex pruned = THopIndex.buildPruned(graph, HORIZON, List.of(3, 1, 4, 2, 5));
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
                assertTrue(expectedTravel.sameFunction(pruned.query(
                                source, destination, QUERY).travelTimeProfile()),
                        "pruned THop disagrees with path oracle: " + source + "->" + destination);
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
        THopIndex pruned = THopIndex.buildPruned(graph, horizon);
        ExactTemporalProfile travel = index.query(1, 3, horizon).travelTimeProfile();
        ExactTemporalProfile prunedTravel = pruned.query(1, 3, horizon).travelTimeProfile();

        assertTrue(travel.domain().contains(0));
        assertTrue(travel.domain().contains(9));
        assertFalse(travel.domain().contains(10));
        assertEquals(2.0, travel.valueAt(9));
        assertTrue(travel.sameFunction(prunedTravel));
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
        THopIndex pruned = THopIndex.buildPruned(graph, horizon, List.of(2, 1, 3, 4));
        Map<Integer, ExactTemporalProfile> oracle =
                SimplePathProfileOracle.allDestinations(graph, 1, horizon);
        for (int destination : graph.nodeIds()) {
            ExactTemporalProfile expected = oracle.getOrDefault(
                    destination, ExactTemporalProfile.empty()).restrict(horizon).asTravelTime();
            ExactTemporalProfile actual = index.query(1, destination, horizon).travelTimeProfile();
            assertTrue(expected.sameFunction(actual), "destination=" + destination);
            assertTrue(expected.sameFunction(pruned.query(1, destination, horizon).travelTimeProfile()),
                    "pruned destination=" + destination);
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
        THopIndex prunedFirst = THopIndex.buildPruned(zeroScore, HORIZON);
        THopIndex prunedSecond = THopIndex.buildPruned(highScore, HORIZON);
        ExactTemporalProfile baseline = first.query(1, 4, QUERY).travelTimeProfile();
        assertTrue(baseline.sameFunction(second.query(1, 4, QUERY).travelTimeProfile()));
        assertTrue(baseline.sameFunction(prunedFirst.query(1, 4, QUERY).travelTimeProfile()));
        assertTrue(baseline.sameFunction(prunedSecond.query(1, 4, QUERY).travelTimeProfile()));

        QuerySpec tight = new QuerySpec(1, 4, 0, 20, 0, 1);
        QuerySpec loose = new QuerySpec(1, 4, 0, 20, 100, 1);
        ExactTemporalProfile tightContext = first.query(
                tight.source(), tight.destination(), tight.departureDomain()).travelTimeProfile();
        ExactTemporalProfile looseContext = first.query(
                loose.source(), loose.destination(), loose.departureDomain()).travelTimeProfile();
        assertTrue(tightContext.sameFunction(looseContext));
        ExactTemporalProfile prunedTight = prunedFirst.query(
                tight.source(), tight.destination(), tight.departureDomain()).travelTimeProfile();
        ExactTemporalProfile prunedLoose = prunedFirst.query(
                loose.source(), loose.destination(), loose.departureDomain()).travelTimeProfile();
        assertTrue(prunedTight.sameFunction(prunedLoose));
        assertTrue(baseline.sameFunction(prunedTight));
        assertEquals(3, THopIndex.class.getMethod(
                "query", int.class, int.class, Domain.class).getParameterCount());
    }

    @Test
    void exactDominanceChecksWholeOpenSlabsSingletonsAndOwnedEndpoints() {
        ExactTemporalProfile constantOne = ExactTemporalProfile.from(TimeProfile.piecewise(
                Domain.closed(0, 10),
                List.of(
                        new TimeProfile.Breakpoint(0, 1),
                        new TimeProfile.Breakpoint(10, 1)),
                "constant-one"));
        ExactTemporalProfile crossing = ExactTemporalProfile.from(TimeProfile.piecewise(
                Domain.closed(0, 10),
                List.of(
                        new TimeProfile.Breakpoint(0, 0),
                        new TimeProfile.Breakpoint(10, 2)),
                "crossing"));
        ExactTemporalProfile higher = ExactTemporalProfile.from(TimeProfile.piecewise(
                Domain.closed(0, 10),
                List.of(
                        new TimeProfile.Breakpoint(0, 2),
                        new TimeProfile.Breakpoint(10, 2)),
                "higher"));
        ExactTemporalProfile openOne = ExactTemporalProfile.from(TimeProfile.piecewise(
                Domain.open(0, 10),
                List.of(
                        new TimeProfile.Breakpoint(0, 1),
                        new TimeProfile.Breakpoint(10, 1)),
                "open-one"));
        ExactTemporalProfile endpointOnly = ExactTemporalProfile.from(TimeProfile.piecewise(
                Domain.closed(0, 0),
                List.of(new TimeProfile.Breakpoint(0, 1)),
                "endpoint-only"));
        Domain gapped = Domain.of(
                new Domain.Interval(0, 4),
                new Domain.Interval(6, 10));
        ExactTemporalProfile fullSupport = ExactTemporalProfile.edgeArrival(
                edge(0, 1, 2, constant(0, 10, 0), 0));
        ExactTemporalProfile gappedSupport = ExactTemporalProfile.edgeArrival(
                edge(0, 1, 2, constant(0, 10, 0).restrict(gapped), 0));

        assertTrue(constantOne.dominates(higher));
        assertTrue(constantOne.dominates(constantOne));
        assertTrue(constantOne.dominates(openOne));
        assertFalse(constantOne.dominates(crossing));
        assertFalse(openOne.dominates(constantOne));
        assertTrue(constantOne.dominates(endpointOnly));
        assertFalse(endpointOnly.dominates(constantOne));
        assertTrue(fullSupport.dominates(gappedSupport));
        assertFalse(gappedSupport.dominates(fullSupport));
    }

    @Test
    void incrementalPrunedThopMatchesUnprunedIndexAndIndependentOracleForDifferentOrders() {
        TDGraph graph = graph(
                List.of(1, 2, 3, 4, 5),
                edge(0, 1, 2, constant(0, 60, 1), 0),
                edge(1, 2, 3, constant(0, 60, 1), 0),
                edge(2, 1, 3, constant(0, 60, 2), 0),
                edge(3, 3, 4, constant(0, 60, 2), 0),
                edge(4, 2, 4, constant(0, 60, 5), 0));
        THopIndex unpruned = THopIndex.build(graph, HORIZON);
        List<List<Integer>> orders = List.of(
                List.of(2, 1, 3, 4, 5),
                List.of(4, 5, 3, 1, 2));

        for (List<Integer> order : orders) {
            THopIndex pruned = THopIndex.buildPruned(graph, HORIZON, order);
            assertTrue(pruned.isPrunedConstruction());
            assertEquals(order, pruned.hubOrder());
            assertEquals(graph.nodeCount(), pruned.preprocessingStatistics().processedHubs());
            assertEquals(graph.nodeCount(), pruned.preprocessingStatistics().forwardSearchCalls());
            assertEquals(graph.nodeCount(), pruned.preprocessingStatistics().backwardSearchCalls());
            assertTrue(pruned.preprocessingStatistics().profilesConstructed() > 0);
            assertTrue(pruned.preprocessingStatistics().labelsRetained() > 0);
            assertTrue(pruned.preprocessingStatistics().temporalFunctionPieces() > 0);
            assertTrue(pruned.preprocessingStatistics().estimatedIndexBytes() > 0);
            assertTrue(pruned.preprocessingStatistics().sampledPeakUsedHeapBytes() > 0);
            assertEquals(pruned.statistics().labelCount(),
                    pruned.preprocessingStatistics().labelsRetained());
            assertEquals(unpruned.statistics().labelCount(),
                    unpruned.preprocessingStatistics().labelsRetained());
            assertTrue(pruned.preprocessingStatistics().labelsRetained()
                    < unpruned.preprocessingStatistics().labelsRetained());
            assertTrue(pruned.preprocessingStatistics().envelopeOperations() > 0);
            THopIndex.QueryStatistics queryStats = pruned.query(1, 4, QUERY).statistics();
            assertTrue(queryStats.queryNanos() > 0);
            assertTrue(queryStats.commonHubCount() > 0);
            assertTrue(queryStats.temporalCompositions() > 0);
            assertTrue(queryStats.resultFunctionPieces() > 0);

            for (int source : graph.nodeIds()) {
                Map<Integer, ExactTemporalProfile> oracle =
                        SimplePathProfileOracle.allDestinations(graph, source, HORIZON);
                for (int destination : graph.nodeIds()) {
                    ExactTemporalProfile expected = oracle.getOrDefault(
                                    destination, ExactTemporalProfile.empty())
                            .restrict(QUERY).asTravelTime();
                    ExactTemporalProfile reference = unpruned.query(
                            source, destination, QUERY).travelTimeProfile();
                    ExactTemporalProfile actual = pruned.query(
                            source, destination, QUERY).travelTimeProfile();
                    assertTrue(expected.sameFunction(reference),
                            "unpruned oracle mismatch " + source + "->" + destination);
                    assertTrue(expected.sameFunction(actual),
                            "pruned oracle mismatch order=" + order + " " + source + "->" + destination);
                }
            }
        }

        THopIndex centerFirst = THopIndex.buildPruned(switchingGraph(0), HORIZON);
        assertEquals(List.of(3, 2, 4, 1, 5), centerFirst.hubOrder());
        assertTrue(centerFirst.preprocessingStatistics().labelsPruned() > 0);
        assertTrue(centerFirst.preprocessingStatistics().searchExpansionsSkipped() > 0);
        assertFalse(unpruned.isPrunedConstruction());
    }

    @Test
    void barycenterOrderingUsesNodeIdFallbackForCoincidentCoordinates() {
        TDGraph graph = new TDGraph(
                List.of(new Node(3, 0, 0), new Node(1, 0, 0), new Node(2, 0, 0)),
                List.of());
        THopIndex index = THopIndex.buildPruned(graph, HORIZON);
        assertEquals(List.of(1, 2, 3), index.hubOrder());
    }

    @Test
    void partialTemporalDominanceRetainsTheHubLabelAndStillAnswersExactly() {
        PiecewiseLinearFn decreasing = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 7),
                new PiecewiseLinearFn.Breakpoint(10, 1),
                new PiecewiseLinearFn.Breakpoint(60, 1)));
        TDGraph graph = graph(
                List.of(1, 2, 3, 4),
                edge(0, 1, 2, constant(0, 60, 2), 0),
                edge(1, 2, 3, constant(0, 60, 2), 0),
                edge(2, 1, 3, decreasing, 0),
                edge(3, 3, 4, constant(0, 60, 1), 0));
        THopIndex pruned = THopIndex.buildPruned(graph, HORIZON, List.of(2, 1, 3, 4));
        THopIndex unpruned = THopIndex.build(graph, HORIZON);

        assertTrue(pruned.incomingLabels(3).containsKey(1),
                "hub 1's partially dominated 1->3 profile must be retained");
        for (int source : graph.nodeIds()) {
            Map<Integer, ExactTemporalProfile> oracle =
                    SimplePathProfileOracle.allDestinations(graph, source, HORIZON);
            for (int destination : graph.nodeIds()) {
                ExactTemporalProfile expected = oracle.getOrDefault(
                                destination, ExactTemporalProfile.empty())
                        .restrict(HORIZON).asTravelTime();
                assertTrue(expected.sameFunction(
                                unpruned.query(source, destination, HORIZON).travelTimeProfile()),
                        "unpruned mismatch " + source + "->" + destination);
                assertTrue(expected.sameFunction(
                                pruned.query(source, destination, HORIZON).travelTimeProfile()),
                        "pruned mismatch " + source + "->" + destination);
            }
        }
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
