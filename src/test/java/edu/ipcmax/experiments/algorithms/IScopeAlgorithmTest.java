package edu.ipcmax.experiments.algorithms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.pcmax.ExactPathProfileBuilder;
import edu.ipcmax.experiments.framework.Ablation;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;

class IScopeAlgorithmTest {
    @Test
    void streamsEveryAcceptedPathIntoAMultiPathEnvelope() {
        TDGraph graph = switchingScoreGraph();
        var result = new IScopeAlgorithm().run(
                graph,
                new QuerySpec(1, 4, 0, 10, 20, 1),
                config("iscope", 10_000, 10_000_000_000L),
                new ExperimentInstrumentation());

        assertEquals(ExperimentStatus.CERTIFIED_COMPLETE, result.status());
        assertEquals(ExactnessScope.GLOBAL_CERTIFIED, result.exactnessScope());
        assertEquals(List.of(0, 1), result.profile().segmentAt(2).path().arcIds());
        assertEquals(List.of(2, 3), result.profile().segmentAt(8).path().arcIds());
        assertEquals(2L, result.scalars().get("accepted_profiles"));
        assertEquals(
                result.scalars().get("fully_profiled_paths"),
                result.scalars().get("accepted_profiles"));
        assertEquals(0L, result.scalars().get("partial_profiles_discarded"));
        assertEquals(2L, result.scalars().get("distinct_selected_paths"));
        assertTrue((Boolean) result.scalars().get("output_loopless"));
        assertTrue((Boolean) result.scalars().get("output_feasible"));
    }

    @Test
    void pathCapReturnsAValidUncertifiedAnytimeEnvelope() {
        var result = new IScopeAlgorithm().run(
                switchingScoreGraph(),
                new QuerySpec(1, 4, 0, 10, 20, 1),
                config("iscope", 1, 10_000_000_000L),
                new ExperimentInstrumentation());

        assertEquals(ExperimentStatus.PATH_CAPPED_NOT_CERTIFIED, result.status());
        assertEquals(ExactnessScope.NOT_CERTIFIED, result.exactnessScope());
        assertTrue(result.profile().segments().stream().anyMatch(segment -> segment.found()));
        assertTrue((Boolean) result.scalars().get("path_cap_triggered"));
    }

    @Test
    void injectableMonotonicDeadlineStopsCleanly() {
        AtomicLong time = new AtomicLong();
        IScopeAlgorithm algorithm = new IScopeAlgorithm(
                () -> time.getAndIncrement());
        var result = algorithm.run(
                switchingScoreGraph(),
                new QuerySpec(1, 4, 0, 10, 20, 1),
                config("iscope", 10_000, 12),
                new ExperimentInstrumentation());

        assertEquals(ExperimentStatus.TIME_CAPPED_NOT_CERTIFIED, result.status());
        assertEquals(ExactnessScope.NOT_CERTIFIED, result.exactnessScope());
        assertTrue((Boolean) result.scalars().get("deadline_cap_triggered"));
        assertTrue(
                (Long) result.scalars().get("accepted_profiles")
                <= (Long) result.scalars().get("fully_profiled_paths"));
    }

    @Test
    void aCancelledPartialProfileIsNeverAccepted() {
        boolean observedCancellationDuringProfile = false;
        for (long limit = 8; limit < 160; limit++) {
            AtomicLong time = new AtomicLong();
            var result = new IScopeAlgorithm(
                    () -> time.getAndIncrement()).run(
                    switchingScoreGraph(),
                    new QuerySpec(1, 4, 0, 10, 20, 1),
                    config("iscope", 10_000, limit),
                    new ExperimentInstrumentation());
            assertTrue(
                    (Long) result.scalars().get("accepted_profiles")
                    <= (Long) result.scalars().get("fully_profiled_paths"));
            if ((Long) result.scalars().get(
                    "partial_profiles_discarded") > 0) {
                observedCancellationDuringProfile = true;
                break;
            }
        }
        assertTrue(observedCancellationDuringProfile);
    }

    @Test
    void budgetInfeasibleCellsAreExcludedFromTheEnvelope() {
        PiecewiseLinearFn travel = new PiecewiseLinearFn(List.of(
                point(0, 2), point(5, 2), point(6, 6), point(1440, 6)));
        TDGraph graph = new TDGraph(
                List.of(new Node(1, 0, 0), new Node(2, 1, 0)),
                List.of(new Edge(
                        0, 1, 2, 1, travel.minTravelTime(), travel,
                        PiecewiseConstFn.zeroFullDay())));
        var result = new IScopeAlgorithm().run(
                graph,
                new QuerySpec(1, 2, 0, 10, 3, 1),
                config("iscope", 10_000, 10_000_000_000L),
                new ExperimentInstrumentation());

        assertTrue(result.profile().segmentAt(1).found());
        assertTrue(result.profile().segmentAt(9).noPath());
        double coverage = (Double) result.scalars().get(
                "departure_interval_coverage");
        assertTrue(coverage > 0.0 && coverage < 1.0);
    }

    @Test
    void lowerBoundPruningRejectsOverBudgetBranchesAndOutputIsLoopless() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1).node(2).node(4)
                .edge(1, 4, 2)
                .edge(1, 2, 10)
                .edge(2, 4, 10)
                .build();
        var result = new IScopeAlgorithm().run(
                graph,
                new QuerySpec(1, 4, 0, 10, 5, 1),
                config("iscope", 10_000, 10_000_000_000L),
                new ExperimentInstrumentation());

        assertEquals(ExperimentStatus.CERTIFIED_COMPLETE, result.status());
        assertTrue((Long) result.scalars().get("lower_bound_prunes") > 0);
        assertTrue((Boolean) result.scalars().get("output_loopless"));
    }

    @Test
    void legacyIntervalBestRemainsSeparatelyRegistered() {
        assertEquals("interval-best", new IntervalBestAlgorithm().id());
        assertEquals("iscope", new IScopeAlgorithm().id());
        assertFalse(new IntervalBestAlgorithm().id().equals(new IScopeAlgorithm().id()));
    }

    @Test
    void anchorFreeBudgetReplayPreservesExactPathSemantics() {
        TDGraph graph = switchingScoreGraph();
        Domain departures = Domain.closed(0, 10);
        var indexed = ExactPathProfileBuilder.context(
                graph, departures, 20, () -> false)
                .replay(List.of(0, 1), 1, 4).orElseThrow();
        var anchorFree = ExactPathProfileBuilder.budgetContext(
                graph, departures, 20, () -> false)
                .replay(List.of(0, 1), 1, 4).orElseThrow();

        assertEquals(indexed.domain(), anchorFree.domain());
        assertEquals(indexed.stablePathId(), anchorFree.stablePathId());
        for (double departure : List.of(0.0, 2.0, 5.0, 8.0, 10.0)) {
            assertEquals(
                    indexed.arrivalProfile().valueAtClosure(departure),
                    anchorFree.arrivalProfile().valueAtClosure(departure));
            assertEquals(
                    indexed.scoreProfile().valueAtClosure(departure),
                    anchorFree.scoreProfile().valueAtClosure(departure));
        }
    }

    private static TDGraph switchingScoreGraph() {
        PiecewiseConstFn early = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 5, 10),
                new PiecewiseConstFn.Interval(5, 1440, 0)));
        PiecewiseConstFn late = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 5, 0),
                new PiecewiseConstFn.Interval(5, 1440, 10)));
        return new TinyGraphBuilder()
                .node(1).node(2).node(3).node(4)
                .edge(1, 2, 2, early)
                .edge(2, 4, 2)
                .edge(1, 3, 2, late)
                .edge(3, 4, 2)
                .build();
    }

    private static PiecewiseLinearFn.Breakpoint point(
            double time, double value) {
        return new PiecewiseLinearFn.Breakpoint(time, value);
    }

    private static AlgorithmConfig config(
            String algorithm,
            long maxPaths,
            long deadlineNanos) {
        return new AlgorithmConfig(
                algorithm,
                Ablation.NONE,
                edu.ipcmax.core.pcmax.PaceEngineMode.SCALABLE,
                0,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                0,
                0,
                maxPaths,
                100_000,
                100_000,
                100_000,
                true,
                42,
                deadlineNanos);
    }
}
