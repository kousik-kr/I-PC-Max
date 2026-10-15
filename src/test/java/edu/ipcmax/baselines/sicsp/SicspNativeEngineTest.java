package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.validate.Path;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SicspNativeEngineTest {
    private static final Domain HORIZON = Domain.closed(0, 20);

    @Test
    void nativeWeightBoundIsIndependentAndEqualityIsFeasible() {
        TDGraph temporal = graph(List.of(1, 2, 3),
                edge(0, 1, 2, constantTravel(0, 20, 5)),
                edge(1, 2, 3, constantTravel(0, 20, 5)));
        SicspNativeGraph graph = withConstantWeights(temporal, 2, 3);
        SicspNativeQuery query = new SicspNativeQuery(1, 3, 0, 10, 5);

        SicspNativeResult result = new SicspNativeEngine(graph).query(query);

        var selected = result.selection().orElseThrow();
        assertEquals(0.0, selected.sourceDeparture());
        assertEquals(10.0, selected.destinationArrival());
        assertEquals(5.0, selected.accumulatedWeight());
        assertEquals(List.of(0, 1), selected.path().arcIds());
        assertTrue(result.arrivalCandidates().generatedPoints().stream()
                .allMatch(candidate -> candidate.accumulatedWeight().isPresent()));

        SicspSmallGraphOracle.Report oracle = SicspSmallGraphOracle.compare(graph, query, result);
        assertEquals(oracle.globalBest().arrival(), selected.destinationArrival());
        assertEquals(0, oracle.candidateValueDiscrepancies());
        assertEquals(0, oracle.retainedProfileDiscrepancies());
        assertTrue(oracle.missedFeasiblePaths().isEmpty());
        assertTrue(oracle.nativeMatchesRetainedPathOracle());
        assertTrue(oracle.selectedRouteFeasible());
    }

    @Test
    void weightBreakpointMovesEarliestFeasibleDepartureAndRetainsProfiles() {
        PiecewiseLinearFn travel = constantTravel(0, 20, 5);
        TDGraph temporal = graph(List.of(1, 2), edge(0, 1, 2, travel));
        SicspEdgeWeightFn weight = SicspEdgeWeightFn.fromBreakpoints(
                travel.domain(), List.of(
                        new SicspEdgeWeightFn.Breakpoint(0, 10),
                        new SicspEdgeWeightFn.Breakpoint(5, 2)));
        SicspNativeGraph graph = nativeGraph(temporal, Map.of(0, weight));
        SicspNativeQuery query = new SicspNativeQuery(1, 2, 0, 10, 2);

        SicspNativeResult result = new SicspNativeEngine(graph).query(query);

        var selected = result.selection().orElseThrow();
        assertEquals(5.0, selected.sourceDeparture());
        assertEquals(10.0, selected.destinationArrival());
        assertEquals(2.0, selected.accumulatedWeight());
        assertTrue(result.weightCandidates().generatedPoints().stream()
                .anyMatch(point -> point.eventTime() == 5.0 && point.sourceDeparture().orElseThrow() == 5.0));
        assertTrue(result.statistics().feasibleDepartureRegions() >= 1);

        SicspSmallGraphOracle.Report oracle = SicspSmallGraphOracle.compare(graph, query, result);
        assertEquals(oracle.globalBest().departure(), selected.sourceDeparture());
        assertEquals(0, oracle.candidateValueDiscrepancies());
        assertEquals(0, oracle.retainedProfileDiscrepancies());
        assertTrue(oracle.nativeMatchesRetainedPathOracle());
        assertTrue(oracle.selectedRouteFeasible());
    }

    @Test
    void constructsPathLinkedLowerEnvelopeAndArrivalIntersection() {
        PiecewiseLinearFn directA = constantTravel(0, 20, 10);
        PiecewiseLinearFn directB = travel(0, 5, 10, 0, 20, 20);
        TDGraph temporal = graph(List.of(1, 2),
                edge(0, 1, 2, directA),
                edge(1, 1, 2, directB));
        SicspEdgeWeightFn weightA = SicspEdgeWeightFn.constant(directA.domain(), 1);
        SicspEdgeWeightFn weightB = SicspEdgeWeightFn.fromBreakpoints(
                directB.domain(), List.of(
                        new SicspEdgeWeightFn.Breakpoint(0, 2),
                        new SicspEdgeWeightFn.Breakpoint(10, 0)));
        SicspNativeGraph graph = nativeGraph(temporal, Map.of(0, weightA, 1, weightB));
        SicspNativeQuery query = new SicspNativeQuery(1, 2, 0, 20, 1);

        SicspNativeResult result = new SicspNativeEngine(graph).query(query);

        assertTrue(result.arrivalFunction().iPoints().stream()
                .anyMatch(point -> Math.abs(point.departure() - 15.0) < 1e-9));
        assertEquals(25.0, result.arrivalFunction().lowerEnvelope().valueAt(15.0));
        assertEquals(25.0, result.arrivalFunction().oPointLowerEnvelope().valueAt(15.0));
        assertTrue(result.weightCandidates().weightPieces().stream()
                .anyMatch(piece -> piece.path().arcIds().equals(List.of(1))
                        && piece.startDeparture() == 10.0
                        && piece.endDeparture() == 20.0
                        && piece.weight() == 0.0));
        var selected = result.selection().orElseThrow();
        assertEquals(10.0, selected.destinationArrival());
        assertEquals(0.0, selected.accumulatedWeight());
        assertEquals(10.0, selected.sourceDeparture());
        assertEquals(List.of(1), selected.path().arcIds());

        SicspSmallGraphOracle.Report oracle = SicspSmallGraphOracle.compare(graph, query, result);
        assertEquals(oracle.globalBest().arrival(), selected.destinationArrival());
        assertEquals(0, oracle.candidateValueDiscrepancies());
        assertEquals(0, oracle.retainedProfileDiscrepancies());
        assertTrue(oracle.nativeMatchesRetainedPathOracle());
        assertTrue(oracle.selectedRouteFeasible());
    }

    @Test
    void equalWeightAndArrivalTiesUseDeterministicRouteAndDepartureRules() {
        TDGraph temporal = graph(List.of(1, 2),
                edge(0, 1, 2, constantTravel(0, 20, 5)),
                edge(1, 1, 2, constantTravel(0, 20, 5)));
        SicspNativeGraph graph = withConstantWeights(temporal, 1, 1);
        SicspNativeQuery query = new SicspNativeQuery(1, 2, 3, 3, 1);

        SicspNativeResult result = new SicspNativeEngine(graph).query(query);

        var selected = result.selection().orElseThrow();
        assertEquals(3.0, selected.sourceDeparture());
        assertEquals(8.0, selected.destinationArrival());
        assertEquals(List.of(0), selected.path().arcIds());
        assertEquals(0, result.profileDiscrepancies().size());
    }

    @Test
    void arrivalPlateauUsesWeightThenEarliestDepartureForTies() {
        PiecewiseLinearFn plateau = travel(0, 10, 10, 0, 20, 10);
        TDGraph temporal = graph(List.of(1, 2), edge(0, 1, 2, plateau));
        SicspEdgeWeightFn weight = SicspEdgeWeightFn.fromBreakpoints(
                plateau.domain(), List.of(
                        new SicspEdgeWeightFn.Breakpoint(0, 2),
                        new SicspEdgeWeightFn.Breakpoint(5, 0)));
        SicspNativeGraph graph = nativeGraph(temporal, Map.of(0, weight));
        SicspNativeQuery query = new SicspNativeQuery(1, 2, 0, 20, 2);

        SicspNativeResult result = new SicspNativeEngine(graph).query(query);

        var selected = result.selection().orElseThrow();
        assertEquals(10.0, selected.destinationArrival());
        assertEquals(0.0, selected.accumulatedWeight());
        assertEquals(5.0, selected.sourceDeparture());
        SicspSmallGraphOracle.Report oracle = SicspSmallGraphOracle.compare(graph, query, result);
        assertEquals(oracle.globalBest().arrival(), selected.destinationArrival());
        assertEquals(oracle.globalBest().departure(), selected.sourceDeparture());
        assertTrue(oracle.nativeMatchesRetainedPathOracle());
        assertTrue(oracle.selectedRouteFeasible());
    }

    @Test
    void endpointAndUnreachableCasesAreExact() {
        TDGraph temporal = graph(List.of(1, 2, 3),
                edge(0, 1, 2, constantTravel(0, 20, 5)));
        SicspNativeGraph graph = withConstantWeights(temporal, 0);

        SicspNativeResult endpoint = new SicspNativeEngine(graph).query(
                new SicspNativeQuery(1, 2, 10, 10, 0));
        assertEquals(10.0, endpoint.selection().orElseThrow().sourceDeparture());
        assertEquals(15.0, endpoint.selection().orElseThrow().destinationArrival());
        assertTrue(endpoint.arrivalCandidates().eventDiagnostics().stream()
                .anyMatch(candidate -> candidate.status()
                        == SicspBreakpointCandidate.Status.SOURCE_DEPARTURE_OUTSIDE_QUERY));

        SicspNativeResult unreachable = new SicspNativeEngine(graph).query(
                new SicspNativeQuery(1, 3, 0, 20, 100));
        assertTrue(unreachable.selection().isEmpty());
        assertTrue(unreachable.arrivalFunction().lowerEnvelope().isEmpty());
    }

    @Test
    void inconsistentLatestPrefixIsRecordedWithoutInsertingWait() {
        PiecewiseLinearFn prefixTravel = constantTravel(0, 3, 1);
        PiecewiseLinearFn eventTravel = travel(0, 1, 5, 0, 20, 15);
        TDGraph temporal = graph(List.of(1, 2, 3),
                edge(0, 1, 2, prefixTravel),
                edge(1, 2, 3, eventTravel));
        SicspNativeGraph graph = withConstantWeights(temporal, 1, 1);
        SicspNativeQuery query = new SicspNativeQuery(1, 3, 0, 3, 2);

        SicspNativeResult result = new SicspNativeEngine(graph).query(query);

        assertTrue(result.arrivalCandidates().eventDiagnostics().stream()
                .anyMatch(candidate -> candidate.status()
                        == SicspBreakpointCandidate.Status.PREFIX_EVENT_MISMATCH));
        assertTrue(result.arrivalCandidates().eventDiagnostics().stream()
                .filter(candidate -> candidate.status()
                        == SicspBreakpointCandidate.Status.PREFIX_EVENT_MISMATCH)
                .allMatch(candidate -> candidate.detail().contains("instead of event")));
    }

    @Test
    void concatenatedWitnessMayRepeatVerticesAndIsNotDiscardedAsNonSimple() {
        PiecewiseLinearFn eventTravel = travel(0, 2, 2, 0, 10, 8);
        TDGraph temporal = graph(List.of(1, 2, 3, 4),
                edge(0, 1, 2, constantTravel(0, 10, 1)),
                edge(1, 2, 3, constantTravel(0, 10, 1)),
                edge(2, 3, 2, eventTravel),
                edge(3, 2, 4, constantTravel(0, 10, 1)));
        SicspNativeGraph graph = withConstantWeights(temporal, 0, 0, 0, 0);

        SicspNativeResult result = new SicspNativeEngine(graph).query(
                new SicspNativeQuery(1, 4, 0, 5, 0));

        assertTrue(result.arrivalCandidates().generatedPoints().stream()
                .anyMatch(candidate -> candidate.eventArcId() == 2
                        && candidate.eventTime() == 2.0
                        && candidate.path().orElseThrow().arcIds().equals(List.of(0, 1, 2, 3))
                        && candidate.detail().contains("repeats a vertex")));
        assertTrue(result.candidateRouteProfiles().stream()
                .anyMatch(profile -> profile.path().arcIds().equals(List.of(0, 1, 2, 3))));
    }

    @Test
    void oracleReportsFeasiblePathsMissedByThePublishedShortestPathHeuristic() {
        TDGraph temporal = graph(List.of(1, 2, 3, 4, 5, 6, 7),
                edge(0, 1, 2, constantTravel(0, 30, 1)),
                edge(1, 2, 4, constantTravel(0, 30, 1)),
                edge(2, 1, 3, constantTravel(0, 30, 3)),
                edge(3, 3, 4, constantTravel(0, 30, 1)),
                edge(4, 4, 5, constantTravel(0, 30, 1)),
                edge(5, 5, 7, constantTravel(0, 30, 1)),
                edge(6, 4, 6, constantTravel(0, 30, 2)),
                edge(7, 6, 7, constantTravel(0, 30, 4)));
        SicspNativeGraph graph = withConstantWeights(temporal, 2, 2, 0, 0, 2, 2, 0, 0);
        SicspNativeQuery query = new SicspNativeQuery(1, 7, 0, 20, 1);
        SicspNativeResult result = new SicspNativeEngine(graph).query(query);

        SicspSmallGraphOracle.Report report = SicspSmallGraphOracle.compare(graph, query, result);

        Path missedSlowPrefixAndSuffix = new Path(List.of(2, 3, 6, 7));
        assertTrue(report.feasibleSimplePaths().contains(missedSlowPrefixAndSuffix));
        assertTrue(report.missedFeasiblePaths().contains(missedSlowPrefixAndSuffix));
        assertTrue(result.selection().isEmpty());
        assertEquals(List.of(2, 3, 6, 7), report.globalBest().path().arcIds());
        assertTrue(Double.isInfinite(report.nativeArrivalError()));
        assertTrue(report.nativeMatchesRetainedPathOracle());
        assertTrue(report.selectedRouteFeasible());
        assertEquals(0, report.candidateValueDiscrepancies());
        assertEquals(0, report.retainedProfileDiscrepancies());
    }

    @Test
    void dominanceUsesExactStrictInequalitiesFromAlgorithmOne() {
        assertTrue(SicspDominance.strictlyDominates(5, 2, 6, 3));
        assertFalse(SicspDominance.strictlyDominates(5, 2, 5, 3));
        assertFalse(SicspDominance.strictlyDominates(5, 2, 6, 2));
        assertFalse(SicspDominance.skylineDominates(5, 2, 5, 2));
        assertTrue(SicspDominance.skylineDominates(5, 2, 5, 3));
    }

    @Test
    void paperFigureOneExampleRetainsItsFifoPlateauAndWeightProfile() {
        PiecewiseLinearFn firstTravel = travel(0, 10, 40, 30, 100, 40);
        PiecewiseLinearFn secondTravel = travel(0, 40, 50, 40, 80, 10, 100, 10);
        TDGraph temporal = graph(List.of(1, 2, 3),
                edge(0, 1, 2, firstTravel),
                edge(1, 2, 3, secondTravel));
        SicspEdgeWeightFn firstWeight = SicspEdgeWeightFn.constant(firstTravel.domain(), 15);
        SicspEdgeWeightFn secondWeight = SicspEdgeWeightFn.fromBreakpoints(
                secondTravel.domain(), List.of(
                        new SicspEdgeWeightFn.Breakpoint(0, 10),
                        new SicspEdgeWeightFn.Breakpoint(20, 30),
                        new SicspEdgeWeightFn.Breakpoint(60, 20)));
        SicspNativeGraph graph = nativeGraph(temporal, Map.of(0, firstWeight, 1, secondWeight));

        var pathProfile = SicspRouteProfile.build(
                graph, 1, 3, Domain.closed(0, 100), new Path(List.of(0, 1)));

        assertEquals(25.0, pathProfile.weightFunction().valueAt(0));
        assertEquals(45.0, pathProfile.weightFunction().valueAt(10));
        assertEquals(35.0, pathProfile.weightFunction().valueAt(50));
        assertTrue(secondTravel.isFifo());
        SicspNativeResult result = new SicspNativeEngine(graph).query(
                new SicspNativeQuery(1, 3, 0, 30, 80));
        var selected = result.selection().orElseThrow();
        assertEquals(0.0, selected.sourceDeparture());
        assertEquals(50.0, selected.destinationArrival());
        assertEquals(25.0, selected.accumulatedWeight());
        SicspSmallGraphOracle.Report oracle = SicspSmallGraphOracle.compare(
                graph, new SicspNativeQuery(1, 3, 0, 30, 80), result);
        assertEquals(0, oracle.candidateValueDiscrepancies());
        assertEquals(1, oracle.retainedProfileDiscrepancies(), oracle.discrepancyDetails().toString());
        assertTrue(oracle.discrepancyDetails().get(0).contains("departure=6.666666666667"));
        assertTrue(oracle.nativeMatchesRetainedPathOracle());
    }

    @Test
    void rejectsNativeWeightFunctionsThatDoNotCoverTravelEntryTimes() {
        PiecewiseLinearFn travel = constantTravel(0, 20, 1);
        TDGraph temporal = graph(List.of(1, 2), edge(0, 1, 2, travel));
        SicspEdgeWeightFn partial = SicspEdgeWeightFn.constant(Domain.closed(0, 10), 1);
        assertThrows(IllegalArgumentException.class,
                () -> nativeGraph(temporal, Map.of(0, partial)));
    }

    private static SicspNativeGraph withConstantWeights(TDGraph temporal, double... values) {
        Map<Integer, SicspEdgeWeightFn> weights = new HashMap<>();
        for (int i = 0; i < values.length; i++) {
            weights.put(i, SicspEdgeWeightFn.constant(
                    temporal.edges().get(i).travelTimeFunction().domain(), values[i]));
        }
        return nativeGraph(temporal, weights);
    }

    private static SicspNativeGraph nativeGraph(TDGraph graph, Map<Integer, SicspEdgeWeightFn> weights) {
        return new SicspNativeGraph(graph, weights);
    }

    private static TDGraph graph(List<Integer> nodeIds, Edge... edges) {
        List<Node> nodes = nodeIds.stream().map(id -> new Node(id, 0, 0)).toList();
        return new TDGraph(nodes, List.of(edges));
    }

    private static Edge edge(int id, int source, int target, PiecewiseLinearFn travel) {
        return new Edge(id, source, target, 1, travel.minTravelTime(), travel,
                PiecewiseConstFn.constant(travel.domain(), 0));
    }

    private static PiecewiseLinearFn constantTravel(double start, double end, double value) {
        return travel(start, value, end, value);
    }

    private static PiecewiseLinearFn travel(double... timeValuePairs) {
        if (timeValuePairs.length < 4 || timeValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("expected at least two (time,value) pairs");
        }
        List<PiecewiseLinearFn.Breakpoint> points = new ArrayList<>();
        for (int i = 0; i < timeValuePairs.length; i += 2) {
            points.add(new PiecewiseLinearFn.Breakpoint(timeValuePairs[i], timeValuePairs[i + 1]));
        }
        return new PiecewiseLinearFn(points);
    }
}
