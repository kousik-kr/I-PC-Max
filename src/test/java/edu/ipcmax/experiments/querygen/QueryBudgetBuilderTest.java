package edu.ipcmax.experiments.querygen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.core.loader.GeneratedGraphDataset;
import edu.ipcmax.core.loader.ManifestSummary;

class QueryBudgetBuilderTest {
    @Test
    void resolvesManifestRushStartsAndDefaultOffpeakStarts() {
        TDGraph graph = constantGraph(10);
        QueryBudgetBuilder builder = new QueryBudgetBuilder(dataset(
                graph,
                new ManifestSummary.TimeWindow(0, 1440),
                Map.of(
                        "morning", new ManifestSummary.TimeWindow(330, 500),
                        "evening", new ManifestSummary.TimeWindow(930, 1100))));

        assertEquals(330, builder.resolveTemporalStart(TemporalRegime.MORNING_PEAK));
        assertEquals(720, builder.resolveTemporalStart(TemporalRegime.DAY_OFFPEAK));
        assertEquals(930, builder.resolveTemporalStart(TemporalRegime.EVENING_PEAK));
        assertEquals(60, builder.resolveTemporalStart(TemporalRegime.LATE_OFFPEAK));
        assertEquals(new ManifestSummary.TimeWindow(0, 1440), builder.temporalSupport());
    }

    @Test
    void buildsExactMainAndTightBudgetsFromContinuousProfile() {
        TDGraph graph = morningVariableGraph();
        QueryBudgetBuilder builder = new QueryBudgetBuilder(dataset(graph, support(1440), standardRush()));

        QueryBudgetBuilder.TemporalQueryBudget result = builder.build(
                pair(1, 2, 10, 1), TemporalRegime.MORNING_PEAK).orElseThrow();

        assertEquals(420, result.intervalStart());
        assertEquals(540, result.intervalEnd());
        assertEquals(120, result.windowLength());
        assertEquals(10.0, result.fastestTravelTimeMin(), 1e-9);
        assertEquals(20.0, result.fastestTravelTimeMax(), 1e-9);
        assertEquals(25.0, result.main().budget(), 1e-9);
        assertTrue(result.main().expectedFullIntervalFeasible());
        assertFalse(result.main().expectedMixedFeasibility());
        assertEquals(10.5, result.tight().budget(), 1e-9);
        assertFalse(result.tight().expectedFullIntervalFeasible());
        assertTrue(result.tight().expectedMixedFeasibility());
    }

    @Test
    void constantFastestProfileUsesFivePercentTightBudgetWithoutMixedFeasibility() {
        QueryBudgetBuilder builder = new QueryBudgetBuilder(dataset(
                constantGraph(8), support(1440), standardRush()));

        QueryBudgetBuilder.TemporalQueryBudget result = builder.build(
                pair(1, 2, 8, 1), TemporalRegime.LATE_OFFPEAK).orElseThrow();

        assertEquals(8.0, result.fastestTravelTimeMin(), 1e-9);
        assertEquals(8.0, result.fastestTravelTimeMax(), 1e-9);
        assertEquals(10.0, result.main().budget(), 1e-9);
        assertEquals(8.4, result.tight().budget(), 1e-9);
        assertTrue(result.tight().expectedFullIntervalFeasible());
        assertFalse(result.tight().expectedMixedFeasibility());
    }

    @Test
    void tightFallbackUsesQuarterOfTheObservedRange() {
        QueryBudgetBuilder.TightBudget tight = QueryBudgetBuilder.calculateTightBudget(8.0, 8.2);

        assertEquals(8.05, tight.budget(), 1e-9);
        assertFalse(tight.expectedFullIntervalFeasible());
        assertTrue(tight.expectedMixedFeasibility());
    }

    @Test
    void rejectsQueryHorizonWithoutWrappingOrShifting() {
        QueryBudgetBuilder builder = new QueryBudgetBuilder(dataset(
                constantGraph(20), support(200), standardRush()));

        assertEquals(60, builder.resolveTemporalStart(TemporalRegime.LATE_OFFPEAK));
        assertTrue(builder.build(pair(1, 2, 20, 1), TemporalRegime.LATE_OFFPEAK).isEmpty());
    }

    @Test
    void rejectsEntireFamilyWhenOneTemporalRegimeFails() {
        QueryBudgetBuilder builder = new QueryBudgetBuilder(dataset(
                constantGraph(50), support(1200), standardRush()));
        QueryPairCandidate pair = pair(1, 2, 50, 1);

        assertTrue(builder.build(pair, TemporalRegime.MORNING_PEAK).isPresent());
        assertTrue(builder.build(pair, TemporalRegime.DAY_OFFPEAK).isPresent());
        assertTrue(builder.build(pair, TemporalRegime.LATE_OFFPEAK).isPresent());
        assertTrue(builder.build(pair, TemporalRegime.EVENING_PEAK).isEmpty());
        assertTrue(builder.buildFamily(pair).isEmpty());
    }

    @Test
    void countsBudgetAdmissibleCorridorAnchorsWithoutRunningPace() {
        PiecewiseConstFn zero = PiecewiseConstFn.zeroFullDay();
        PiecewiseConstFn positive = PiecewiseConstFn.constant(Domain.closed(0, 1440), 1);
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .node(4)
                .edge(1, 2, 2, zero)
                .edge(2, 4, 2, positive)
                .edge(1, 3, 3, zero)
                .edge(3, 4, 3, positive)
                .build();
        QueryBudgetBuilder builder = new QueryBudgetBuilder(dataset(
                graph, support(1440), standardRush()));

        QueryBudgetBuilder.TemporalQueryBudget result = builder.build(
                pair(1, 4, 4, 2), TemporalRegime.LATE_OFFPEAK).orElseThrow();

        assertEquals(1, result.main().corridorAnchorCount());
        assertEquals(1, result.tight().corridorAnchorCount());
    }

    @Test
    void roundsBudgetsUpToTheExactRepositoryTimeUnit() {
        assertEquals(1.000000001, QueryBudgetBuilder.ceilToRepositoryTimeUnit(1.0000000001), 0.0);
        assertEquals(10.000000002, QueryBudgetBuilder.calculateMainBudget(8.000000001), 0.0);
    }

    @Test
    void rejectsAnImpossibleLowerBoundBeforeProfileConstruction() {
        TDGraph graph = constantGraph(1);
        QueryBudgetBuilder builder = new QueryBudgetBuilder(dataset(
                graph, support(1440), standardRush()));

        QueryBudgetBuilder.TemporalBuildResult result = builder.buildDetailed(
                pair(1, 2, 300, 1), TemporalRegime.EVENING_PEAK);

        assertFalse(result.succeeded());
        assertEquals(
                QueryBudgetBuilder.FailureReason.FUNCTION_HORIZON_EXCEEDED,
                result.failure().orElseThrow().reason());
        assertTrue(result.failure().orElseThrow().detail().contains("lower-bound budget horizon"));
    }

    @Test
    void requiresManifestTemporalSupport() {
        TDGraph graph = constantGraph(10);
        ManifestSummary manifest = new ManifestSummary(
                graph.nodeCount(), graph.edgeCount(), 42, 0, true);

        assertThrows(IllegalArgumentException.class, () -> new QueryBudgetBuilder(
                new GeneratedGraphDataset(graph, manifest, Path.of("data/input/TEST"))));
        assertEquals(Optional.empty(), manifest.temporalSupport());
    }

    @Test
    void rejectsAProfileThatCannotBeRepresentedWithoutExtrapolation() {
        TDGraph graph = new TDGraph(
                List.of(new Node(1, 0, 0), new Node(2, 1, 1), new Node(3, 2, 2)),
                List.of(
                        edge(0, 1, 2, function(0, 2, 10, 2)),
                        edge(1, 1, 3, function(5, 0)),
                        edge(2, 3, 2, function(5, 1))));
        QueryBudgetBuilder builder = new QueryBudgetBuilder(
                dataset(graph, support(20), Map.of()),
                10,
                0.25,
                0.05,
                Map.of(
                        TemporalRegime.MORNING_PEAK, 0,
                        TemporalRegime.DAY_OFFPEAK, 0,
                        TemporalRegime.EVENING_PEAK, 0,
                        TemporalRegime.LATE_OFFPEAK, 0));

        assertTrue(builder.build(pair(1, 2, 1, 1), TemporalRegime.DAY_OFFPEAK).isEmpty());
    }

    @Test
    void buildsParameterizedWindowsAndSlacksOnTheSameBuilder() {
        QueryBudgetBuilder builder = new QueryBudgetBuilder(dataset(
                constantGraph(8), support(1440), standardRush()));
        QueryPairCandidate pair = pair(1, 2, 8, 1);

        QueryBudgetBuilder.TemporalQueryBudget shortWindow = builder.buildDetailed(
                pair, TemporalRegime.MORNING_PEAK, 30, 0.50).budget().orElseThrow();
        QueryBudgetBuilder.TemporalQueryBudget longerWindow = builder.buildDetailed(
                pair, TemporalRegime.MORNING_PEAK, 60, 0.10).budget().orElseThrow();

        assertEquals(420, shortWindow.intervalStart());
        assertEquals(450, shortWindow.intervalEnd());
        assertEquals(30, shortWindow.windowLength());
        assertEquals(12.0, shortWindow.main().budget(), 0.0);
        assertEquals(0.50, shortWindow.main().budgetSlack(), 0.0);
        assertEquals(Domain.closed(420, 450), shortWindow.fastestProfile().arrivalProfile().domain());

        assertEquals(480, longerWindow.intervalEnd());
        assertEquals(60, longerWindow.windowLength());
        assertEquals(8.8, longerWindow.main().budget(), 0.0);
        assertEquals(0.10, longerWindow.main().budgetSlack(), 0.0);
        assertEquals(Domain.closed(420, 480), longerWindow.fastestProfile().arrivalProfile().domain());
        assertEquals(10.0, QueryBudgetBuilder.calculateMainBudget(8.0, 0.25), 0.0);
    }

    @Test
    void reportsMachineReadableBuildDiagnostics() {
        QueryPairCandidate pair = pair(1, 2, 1, 1);
        QueryBudgetBuilder unreachable = new QueryBudgetBuilder(dataset(
                new TinyGraphBuilder().node(1).node(2).build(), support(1440), standardRush()));
        QueryBudgetBuilder.TemporalBuildResult missingProfile = unreachable.buildDetailed(
                pair, TemporalRegime.MORNING_PEAK);
        assertFalse(missingProfile.succeeded());
        assertEquals(
                "fastest_profile_unavailable",
                missingProfile.failure().orElseThrow().reasonId());

        TDGraph partialGraph = new TDGraph(
                List.of(new Node(1, 0, 0), new Node(2, 1, 1)),
                List.of(edge(0, 1, 2, function(0, 1, 5, 1))));
        QueryBudgetBuilder partial = new QueryBudgetBuilder(
                dataset(partialGraph, support(20), Map.of()),
                10,
                0.25,
                0.05,
                allStartsAtZero());
        assertEquals(
                QueryBudgetBuilder.FailureReason.FULL_INTERVAL_INFEASIBLE,
                partial.buildDetailed(pair, TemporalRegime.DAY_OFFPEAK)
                        .failure().orElseThrow().reason());

        QueryBudgetBuilder horizon = new QueryBudgetBuilder(dataset(
                constantGraph(20), support(200), standardRush()));
        assertEquals(
                QueryBudgetBuilder.FailureReason.FUNCTION_HORIZON_EXCEEDED,
                horizon.buildDetailed(pair(1, 2, 20, 1), TemporalRegime.LATE_OFFPEAK)
                        .failure().orElseThrow().reason());

        QueryBudgetBuilder unavailableRegime = new QueryBudgetBuilder(dataset(
                constantGraph(1), new ManifestSummary.TimeWindow(100, 1440), standardRush()));
        assertEquals(
                QueryBudgetBuilder.FailureReason.TEMPORAL_REGIME_UNAVAILABLE,
                unavailableRegime.buildDetailed(pair, TemporalRegime.LATE_OFFPEAK)
                        .failure().orElseThrow().reason());

        QueryBudgetBuilder.TemporalBuildResult invalidBudget = horizon.buildDetailed(
                pair(1, 2, 20, 1), TemporalRegime.LATE_OFFPEAK, 120, -0.01);
        assertEquals(
                QueryBudgetBuilder.FailureReason.BUDGET_INVALID,
                invalidBudget.failure().orElseThrow().reason());
    }

    @Test
    void familyDiagnosticsIdentifyTheRegimeThatRejectedTheFamily() {
        QueryBudgetBuilder builder = new QueryBudgetBuilder(dataset(
                constantGraph(50), support(1200), standardRush()));

        QueryBudgetBuilder.FamilyBuildResult result = builder.buildFamilyDetailed(
                pair(1, 2, 50, 1), 120, 0.25);

        assertFalse(result.succeeded());
        assertEquals(
                QueryBudgetBuilder.FailureReason.FUNCTION_HORIZON_EXCEEDED,
                result.failure().orElseThrow().reason());
        assertEquals(
                TemporalRegime.EVENING_PEAK,
                result.failure().orElseThrow().temporalRegime());
    }

    private static GeneratedGraphDataset dataset(
            TDGraph graph,
            ManifestSummary.TimeWindow support,
            Map<String, ManifestSummary.TimeWindow> rushWindows) {
        ManifestSummary manifest = new ManifestSummary(
                graph.nodeCount(), graph.edgeCount(), 42,
                (int) graph.edges().stream().filter(edge -> edge.scoreFunction().maxValue() > 0).count(),
                true, Optional.of(support), rushWindows);
        return new GeneratedGraphDataset(graph, manifest, Path.of("data/input/TEST"));
    }

    private static ManifestSummary.TimeWindow support(double end) {
        return new ManifestSummary.TimeWindow(0, end);
    }

    private static Map<String, ManifestSummary.TimeWindow> standardRush() {
        return Map.of(
                "morning", new ManifestSummary.TimeWindow(420, 600),
                "evening", new ManifestSummary.TimeWindow(1020, 1200));
    }

    private static Map<TemporalRegime, Integer> allStartsAtZero() {
        return Map.of(
                TemporalRegime.MORNING_PEAK, 0,
                TemporalRegime.DAY_OFFPEAK, 0,
                TemporalRegime.EVENING_PEAK, 0,
                TemporalRegime.LATE_OFFPEAK, 0);
    }

    private static QueryPairCandidate pair(
            int source, int destination, double lowerBound, int edgeCount) {
        return new QueryPairCandidate("TEST", source, destination, lowerBound, edgeCount, 0);
    }

    private static TDGraph constantGraph(double travelTime) {
        return new TinyGraphBuilder()
                .node(1)
                .node(2)
                .edge(1, 2, travelTime)
                .build();
    }

    private static TDGraph morningVariableGraph() {
        PiecewiseLinearFn travel = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 10),
                new PiecewiseLinearFn.Breakpoint(420, 10),
                new PiecewiseLinearFn.Breakpoint(480, 20),
                new PiecewiseLinearFn.Breakpoint(540, 10),
                new PiecewiseLinearFn.Breakpoint(1440, 10)));
        Edge edge = new Edge(
                0, 1, 2, 10, 10, travel, PiecewiseConstFn.zeroFullDay());
        return new TDGraph(
                List.of(new Node(1, 0, 0), new Node(2, 1, 1)),
                List.of(edge));
    }

    private static Edge edge(
            int arcId, int source, int destination, PiecewiseLinearFn travel) {
        return new Edge(
                arcId,
                source,
                destination,
                Math.round(travel.minTravelTime()),
                travel.breakpoints().get(0).value(),
                travel,
                PiecewiseConstFn.constant(travel.domain(), 0));
    }

    private static PiecewiseLinearFn function(double... coordinates) {
        if (coordinates.length == 0 || coordinates.length % 2 != 0) {
            throw new IllegalArgumentException("time/value coordinates are required");
        }
        java.util.ArrayList<PiecewiseLinearFn.Breakpoint> points = new java.util.ArrayList<>();
        for (int index = 0; index < coordinates.length; index += 2) {
            points.add(new PiecewiseLinearFn.Breakpoint(
                    coordinates[index], coordinates[index + 1]));
        }
        return new PiecewiseLinearFn(points);
    }
}
