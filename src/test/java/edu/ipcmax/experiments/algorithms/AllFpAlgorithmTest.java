package edu.ipcmax.experiments.algorithms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.labeling.PointForwardLabeling;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.pcmax.ExactPathProfileBuilder;
import edu.ipcmax.core.pcmax.FastestEnvelopeExtractor;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.experiments.framework.Ablation;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;
import edu.ipcmax.experiments.framework.ProfileSupport;

class AllFpAlgorithmTest {
    @Test
    void findsKnownContinuousCrossingAndStableTieBoundary() {
        TDGraph graph = crossingGraph(false);
        var result = run(graph, 100);

        assertEquals(ExperimentStatus.CERTIFIED_COMPLETE, result.status());
        assertEquals(ExactnessScope.GLOBAL_CERTIFIED, result.exactnessScope());
        assertEquals(List.of(0), result.profile().segmentAt(2).path().arcIds());
        assertEquals(List.of(0), result.profile().segmentAt(5).path().arcIds());
        assertEquals(List.of(1, 2), result.profile().segmentAt(8).path().arcIds());
        assertEquals(2L, result.scalars().get("distinct_fastest_paths"));
        assertTrue((Boolean) result.scalars().get("full_interval_coverage"));
    }

    @Test
    void scoreAndPcMaxBudgetDoNotAffectFastestPathSelection() {
        var lowBudget = run(crossingGraph(false), 1);
        var highBudget = run(crossingGraph(false), 1000);
        var highBudgetDifferentScores = run(crossingGraph(true), 1000);

        assertEquals(selectedPaths(lowBudget), selectedPaths(highBudget));
        assertEquals(
                ProfileSupport.checksum(lowBudget.profile()),
                ProfileSupport.checksum(highBudget.profile()));
        assertEquals(
                selectedPaths(lowBudget),
                selectedPaths(highBudgetDifferentScores));
        assertEquals(false, lowBudget.scalars().get("preference_score_used_for_search"));
        assertEquals(false, lowBudget.scalars().get("pcmax_budget_used_for_search"));
        assertEquals(0.0,
                (Double) lowBudget.scalars().get(
                        "posthoc_budget_feasible_coverage_fraction"),
                1e-9);
        assertEquals(1.0,
                (Double) highBudget.scalars().get(
                        "posthoc_budget_feasible_coverage_fraction"),
                1e-9);
    }

    @Test
    void singlePathCoversWholeIntervalAndPropagatesMultipleBreakpoints() {
        TDGraph graph = new TDGraph(
                List.of(new Node(1, 0, 0), new Node(4, 1, 0)),
                List.of(edge(0, 1, 4, List.of(
                        point(0, 2), point(3, 4), point(7, 3),
                        point(10, 5), point(1440, 5)),
                        PiecewiseConstFn.zeroFullDay())));
        var result = run(graph, 100);

        assertEquals(ExperimentStatus.CERTIFIED_COMPLETE, result.status());
        assertTrue((Boolean) result.scalars().get("full_interval_coverage"));
        assertEquals(1L, result.scalars().get("distinct_fastest_paths"));
        assertTrue((Long) result.scalars().get("profile_breakpoints") >= 4L);
    }

    @Test
    void certifiedProfileMatchesPointShortestPathsAtBoundariesAndRandomTimes() {
        TDGraph graph = crossingGraph(false);
        var result = run(graph, 100);
        PointForwardLabeling point = new PointForwardLabeling(graph);
        Set<Integer> probes = new TreeSet<>(List.of(0, 10));
        result.profile().segments().forEach(segment -> {
            addRepresentableBoundaryProbes(
                    probes, segment.interval().start(), 0, 10);
            addRepresentableBoundaryProbes(
                    probes, segment.interval().end(), 0, 10);
        });
        Random random = new Random(42);
        for (int index = 0; index < 20; index++) {
            probes.add(random.nextInt(11));
        }
        for (int departure : probes) {
            var labels = point.run(1, departure, 1000);
            double expected = labels.arrivalAt(4) - departure;
            double actual = result.profile().segmentAt(departure)
                    .candidate().travelTimeAt(departure);
            assertEquals(expected, actual, 1e-9, "departure=" + departure);
        }
    }

    private static void addRepresentableBoundaryProbes(
            Set<Integer> probes,
            double boundary,
            int start,
            int end) {
        for (int probe : List.of(
                (int) Math.floor(boundary),
                (int) Math.ceil(boundary))) {
            if (probe >= start && probe <= end) {
                probes.add(probe);
            }
        }
    }

    @Test
    void retainsAPathThatIsFastestOnlyOnAnInteriorSubinterval() {
        TDGraph graph = interiorFastestGraph();
        var result = run(graph, 100);

        assertEquals(List.of(0), result.profile().segmentAt(0).path().arcIds());
        assertEquals(List.of(1), result.profile().segmentAt(5).path().arcIds());
        assertEquals(List.of(0), result.profile().segmentAt(10).path().arcIds());
        assertEquals(2L, result.scalars().get("distinct_fastest_paths"));
        assertEquals(0L, result.scalars().get("dominated_labels"));
    }

    @Test
    void aHigherScalarMinimumLabelCanStillBeFastestInsideTheInterval() {
        TDGraph graph = misleadingScalarGraph();
        var result = new AllFpAlgorithm().run(
                graph,
                new QuerySpec(1, 4, 0, 20, 1, 1),
                config(10_000_000_000L),
                new ExperimentInstrumentation());

        assertEquals(List.of(0), result.profile().segmentAt(0).path().arcIds());
        assertEquals(List.of(1), result.profile().segmentAt(10).path().arcIds());
        assertEquals(List.of(0), result.profile().segmentAt(20).path().arcIds());
        assertTrue((Long) result.scalars().get("dominance_comparisons") > 0L);
        assertEquals(0L, result.scalars().get("dominated_labels"));
    }

    @Test
    void parallelCompositionIsDeterministicAndScoringIsPostHoc() {
        TDGraph graph = crossingGraph(false);
        QuerySpec query = new QuerySpec(1, 4, 0, 10, 100, 1);
        var serial = new AllFpAlgorithm().run(
                graph, query, config(10_000_000_000L, 1),
                new ExperimentInstrumentation());
        AllFpAlgorithm parallelAlgorithm = new AllFpAlgorithm();
        parallelAlgorithm.prepare(graph, config(10_000_000_000L, 4));
        ExperimentInstrumentation instrumentation =
                new ExperimentInstrumentation();
        var parallel = parallelAlgorithm.run(
                graph, query, config(10_000_000_000L, 4),
                instrumentation);

        assertEquals(
                ProfileSupport.checksum(serial.profile()),
                ProfileSupport.checksum(parallel.profile()));
        assertEquals(4L, parallel.scalars().get("requested_workers"));
        assertTrue((Long) parallel.scalars().get(
                "parallel_functional_tasks") > 0L);
        assertEquals(0L, parallel.scalars().get(
                "score_profiles_constructed_during_search"));
        assertTrue((Long) parallel.scalars().get("posthoc_scored_paths") > 0L);
        assertEquals("DenseDijkstraLowerBoundOracle",
                parallel.scalars().get("lower_bound_oracle"));
        assertTrue(instrumentation.timings().containsKey(
                "functional_search_control"));
        assertTrue(instrumentation.timings().get("functional_composition")
                <= instrumentation.timings().get("algorithm_total"));
    }

    @Test
    void matchesIndependentLooplessPathEnumerationOracle() {
        TDGraph graph = crossingGraph(false);
        QuerySpec query = new QuerySpec(1, 4, 0, 10, 100, 1);
        CandidateSet oracleCandidates = new CandidateSet();
        var replay = ExactPathProfileBuilder.horizonContext(
                graph, query.departureDomain(), 1440, () -> false);
        var paths = SimplePathSearch.exhaustive(
                graph, 1, 4, 1000, 100).paths();
        paths.forEach(path -> replay.replay(path.arcs(), 1, 4)
                .ifPresent(oracleCandidates::add));
        var oracle = FastestEnvelopeExtractor.extract(
                oracleCandidates, query.departureDomain());
        var result = new AllFpAlgorithm().run(
                graph, query, config(10_000_000_000L),
                new ExperimentInstrumentation());

        assertEquals(
                ProfileSupport.checksum(oracle),
                ProfileSupport.checksum(result.profile()));
    }

    @Test
    void matchesIndependentOracleOnSeededTemporalDags() {
        Random random = new Random(20260805L);
        for (int graphCase = 0; graphCase < 25; graphCase++) {
            TDGraph graph = randomTemporalDag(random);
            QuerySpec query = new QuerySpec(1, 5, 0, 10, 100, 1);
            CandidateSet oracleCandidates = new CandidateSet();
            var replay = ExactPathProfileBuilder.horizonContext(
                    graph, query.departureDomain(), 1440, () -> false);
            SimplePathSearch.exhaustive(
                    graph, 1, 5, 10_000, 1_000).paths()
                    .forEach(path -> replay.replay(path.arcs(), 1, 5)
                            .ifPresent(oracleCandidates::add));
            var oracle = FastestEnvelopeExtractor.extract(
                    oracleCandidates, query.departureDomain());

            var actual = new AllFpAlgorithm().run(
                    graph, query, config(10_000_000_000L),
                    new ExperimentInstrumentation());

            assertEquals(
                    ExperimentStatus.CERTIFIED_COMPLETE,
                    actual.status(),
                    "graph_case=" + graphCase);
            assertEquals(
                    ProfileSupport.checksum(oracle),
                    ProfileSupport.checksum(actual.profile()),
                    "graph_case=" + graphCase
                            + " edges=" + graph.edges()
                            + " expected=" + ProfileSupport.canonical(oracle)
                            + " actual="
                            + ProfileSupport.canonical(actual.profile()));
        }
    }

    @Test
    void injectableDeadlineProducesExplicitUncertifiedStatus() {
        AtomicLong time = new AtomicLong();
        AllFpAlgorithm algorithm = new AllFpAlgorithm(
                () -> time.getAndIncrement());
        var result = algorithm.run(
                crossingGraph(false),
                new QuerySpec(1, 4, 0, 10, 100, 1),
                config(12),
                new ExperimentInstrumentation());

        assertEquals(ExperimentStatus.TIME_CAPPED_NOT_CERTIFIED, result.status());
        assertEquals(ExactnessScope.NOT_CERTIFIED, result.exactnessScope());
        assertTrue((Boolean) result.scalars().get("deadline_cap_triggered"));
    }

    private static edu.ipcmax.experiments.framework.AlgorithmResult run(
            TDGraph graph,
            double budget) {
        return new AllFpAlgorithm().run(
                graph,
                new QuerySpec(1, 4, 0, 10, budget, 1),
                config(10_000_000_000L),
                new ExperimentInstrumentation());
    }

    private static List<List<Integer>> selectedPaths(
            edu.ipcmax.experiments.framework.AlgorithmResult result) {
        return result.profile().segments().stream()
                .filter(segment -> segment.found())
                .map(segment -> segment.path().arcIds())
                .distinct()
                .toList();
    }

    private static TDGraph crossingGraph(boolean reverseScores) {
        PiecewiseConstFn directScore = constantScore(
                reverseScores ? 100 : 0);
        PiecewiseConstFn viaScore = constantScore(
                reverseScores ? 0 : 100);
        List<Node> nodes = List.of(
                new Node(1, 0, 0),
                new Node(2, 1, 0),
                new Node(4, 2, 0));
        List<Edge> edges = List.of(
                edge(0, 1, 4, List.of(
                        point(0, 2), point(10, 4), point(1440, 4)), directScore),
                edge(1, 1, 2, List.of(point(0, 1.5), point(1440, 1.5)), viaScore),
                edge(2, 2, 4, List.of(point(0, 1.5), point(1440, 1.5)),
                        PiecewiseConstFn.zeroFullDay()));
        return new TDGraph(nodes, edges);
    }

    private static TDGraph interiorFastestGraph() {
        List<Node> nodes = List.of(new Node(1, 0, 0), new Node(4, 1, 0));
        List<Edge> edges = List.of(
                edge(0, 1, 4,
                        List.of(point(0, 5), point(1440, 5)),
                        PiecewiseConstFn.zeroFullDay()),
                edge(1, 1, 4,
                        List.of(
                                point(0, 6), point(5, 4), point(10, 6),
                                point(1440, 6)),
                        PiecewiseConstFn.zeroFullDay()));
        return new TDGraph(nodes, edges);
    }

    private static TDGraph misleadingScalarGraph() {
        List<Node> nodes = List.of(new Node(1, 0, 0), new Node(4, 1, 0));
        return new TDGraph(nodes, List.of(
                edge(0, 1, 4,
                        List.of(
                                point(0, 2), point(10, 10), point(20, 2),
                                point(1440, 2)),
                        PiecewiseConstFn.zeroFullDay()),
                edge(1, 1, 4,
                        List.of(point(0, 9), point(1440, 9)),
                        PiecewiseConstFn.zeroFullDay())));
    }

    private static TDGraph randomTemporalDag(Random random) {
        List<Node> nodes = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(node -> new Node(node, node, 0))
                .toList();
        List<Edge> edges = new java.util.ArrayList<>();
        int arcId = 0;
        for (int source = 1; source < 5; source++) {
            for (int target = source + 1; target <= 5; target++) {
                if (target != source + 1 && !random.nextBoolean()) {
                    continue;
                }
                double atZero = 1 + random.nextInt(6);
                double atFive = 1 + random.nextInt(6);
                double atTen = 1 + random.nextInt(6);
                edges.add(edge(
                        arcId++,
                        source,
                        target,
                        List.of(
                                point(0, atZero),
                                point(5, atFive),
                                point(10, atTen),
                                point(1440, atTen)),
                        constantScore(random.nextInt(20))));
            }
        }
        return new TDGraph(nodes, edges);
    }

    private static PiecewiseLinearFn.Breakpoint point(double time, double value) {
        return new PiecewiseLinearFn.Breakpoint(time, value);
    }

    private static PiecewiseConstFn constantScore(int value) {
        return new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 1440, value)));
    }

    private static Edge edge(
            int id,
            int source,
            int target,
            List<PiecewiseLinearFn.Breakpoint> travel,
            PiecewiseConstFn score) {
        PiecewiseLinearFn function = new PiecewiseLinearFn(travel);
        assertTrue(function.isFifo());
        return new Edge(id, source, target, 1, function.minTravelTime(), function, score);
    }

    private static AlgorithmConfig config(long deadlineNanos) {
        return config(deadlineNanos, 1);
    }

    private static AlgorithmConfig config(
            long deadlineNanos,
            int threads) {
        return new AlgorithmConfig(
                "allfp",
                Ablation.NONE,
                edu.ipcmax.core.pcmax.PaceEngineMode.SCALABLE,
                0,
                1,
                1,
                1,
                1,
                1,
                1,
                threads,
                0,
                0,
                100_000,
                100_000,
                100_000,
                100_000,
                true,
                42,
                deadlineNanos);
    }
}
