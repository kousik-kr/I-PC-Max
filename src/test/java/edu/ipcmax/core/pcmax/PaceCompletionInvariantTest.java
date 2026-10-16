package edu.ipcmax.core.pcmax;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.experiments.algorithms.PaceExperimentAlgorithm;
import edu.ipcmax.experiments.framework.Ablation;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;
import edu.ipcmax.testoracle.TinyContinuousEnvelopeOracle;

class PaceCompletionInvariantTest {
    @Test
    void completionAndScopeAreResolvedTogetherForEveryFrontierAndCertificateState() {
        PaceCapStatus cap = new PaceCapStatus(Set.of(PaceCapKind.QUERY_WORK_M_Q), "regression");
        assertOutcome(true, true, PaceCapStatus.none(), PaceCompletion.COMPLETE,
                PaceExactnessScope.GLOBAL_CERTIFIED);
        assertOutcome(false, true, PaceCapStatus.none(), PaceCompletion.NO_FEASIBLE_PATH,
                PaceExactnessScope.GLOBAL_CERTIFIED);
        assertOutcome(true, false, PaceCapStatus.none(), PaceCompletion.COMPLETE,
                PaceExactnessScope.RETAINED_FRONTIER);
        assertOutcome(false, false, PaceCapStatus.none(),
                PaceCompletion.UNRESOLVED_NO_GENERATED_CANDIDATE, PaceExactnessScope.NOT_CERTIFIED);
        for (boolean feasible : List.of(false, true)) {
            assertOutcome(feasible, true, cap, PaceCompletion.ABORTED, PaceExactnessScope.NOT_CERTIFIED);
            assertOutcome(feasible, false, cap, PaceCompletion.ABORTED, PaceExactnessScope.NOT_CERTIFIED);
            var bounded = PaceGenerationOutcome.resolve(PaceExecutionPolicy.PACE_B,
                    feasible, cap, true, true);
            assertEquals(PaceCompletion.RESOURCE_TRUNCATED, bounded.completion());
            assertEquals(PaceExactnessScope.RETAINED_FRONTIER, bounded.exactness());
            var ablated = PaceGenerationOutcome.resolve(PaceExecutionPolicy.PACE_B,
                    feasible, cap, true, false);
            assertEquals(PaceExactnessScope.NOT_CERTIFIED, ablated.exactness());
        }
    }

    @Test
    void feasibleChainWithInsufficientDepthIsUnresolvedInsteadOfThrowing() {
        TDGraph graph = scoreChain();
        QuerySpec query = new QuerySpec(1, 3, 0, 5, 5, 1);
        PACE pace = new PACE(graph, PaceOptions.exhaustive(0));
        PaceGenerationResult result = assertDoesNotThrow(() -> pace.generate(query));
        assertEquals(PaceCompletion.UNRESOLVED_NO_GENERATED_CANDIDATE, result.completion());
        assertEquals(PaceExactnessScope.NOT_CERTIFIED, result.exactnessScope());
        assertTrue(result.frontier().isEmpty());
        EnvelopeProfile profile = assertDoesNotThrow(() -> pace.run(query));
        assertEquals(query.departureDomain(), profile.domain());
        assertTrue(profile.segments().stream().allMatch(EnvelopeSegment::noPath));
        var adapted = new PaceExperimentAlgorithm("pace-x").run(
                graph, query, config(0), new ExperimentInstrumentation());
        assertEquals(ExperimentStatus.UNRESOLVED_NO_GENERATED_CANDIDATE, adapted.status());
        assertEquals(ExactnessScope.NOT_CERTIFIED, adapted.exactnessScope());
        assertFalse(adapted.completeProfile());
    }

    @Test
    void sufficientDepthMatchesIndependentOracleButAdapterRemainsUncertified() {
        TDGraph graph = scoreChain();
        QuerySpec query = new QuerySpec(1, 3, 0, 5, 5, 1);
        var expected = TinyContinuousEnvelopeOracle.solve(graph, query);
        PACE pace = new PACE(graph, PaceOptions.exhaustive(2));
        var actual = pace.run(query);
        assertEquals(PaceCompletion.COMPLETE, pace.lastGenerationResult().completion());
        assertEquals(PaceExactnessScope.GLOBAL_CERTIFIED, pace.lastGenerationResult().exactnessScope());
        assertEquals(1, expected.size());
        assertEquals(expected.get(0).path().orElseThrow(), actual.segmentAt(0).path().arcIds());
        assertEquals(actual.segmentAt(0).path(), actual.segmentAt(5).path());
        assertEquals(14, actual.segmentAt(0).candidate().scoreProfile().valueAt(0));
        assertEquals(2, actual.segmentAt(0).candidate().travelTimeAt(0));
        var adapted = new PaceExperimentAlgorithm("pace-x").run(
                graph, query, config(2), new ExperimentInstrumentation());
        assertEquals(ExperimentStatus.COMPLETED, adapted.status());
        assertEquals(ExactnessScope.NOT_CERTIFIED, adapted.exactnessScope());
        assertFalse(adapted.completeProfile());
    }

    @Test
    void disconnectedGraphHasCertifiedEmptyCoverageButIncompleteFeasibleSearchDoesNot() {
        TDGraph disconnected = new TinyGraphBuilder().node(1).node(2).node(3).edge(1, 2, 1).build();
        QuerySpec query = new QuerySpec(1, 3, 0, 5, 5, 1);
        PACE exact = new PACE(disconnected, PaceOptions.exhaustive(0));
        assertTrue(exact.run(query).segments().stream().allMatch(EnvelopeSegment::noPath));
        assertEquals(PaceCompletion.NO_FEASIBLE_PATH, exact.lastGenerationResult().completion());
        assertEquals(PaceExactnessScope.GLOBAL_CERTIFIED, exact.lastGenerationResult().exactnessScope());
        var adapted = new PaceExperimentAlgorithm("pace-x").run(
                disconnected, query, config(0), new ExperimentInstrumentation());
        assertEquals(ExperimentStatus.UNRESOLVED_NO_GENERATED_CANDIDATE, adapted.status());
        assertFalse(adapted.completeProfile());
        PiecewiseConstFn positive = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 1440, 7)));
        TDGraph alternatives = new TinyGraphBuilder().node(1).node(2).node(3)
                .edge(1, 3, 3).edge(1, 2, 1, positive).edge(2, 3, 1, positive).build();
        var partial = new PaceExperimentAlgorithm("pace-x").run(
                alternatives, query, config(0), new ExperimentInstrumentation());
        assertEquals(ExperimentStatus.COMPLETED, partial.status());
        assertEquals(ExactnessScope.NOT_CERTIFIED, partial.exactnessScope());
        assertEquals(List.of(0), partial.profile().segmentAt(0).path().arcIds());
        assertFalse(partial.completeProfile());
    }

    @Test
    void invalidQueriesAreRejectedBeforeAnyInfeasibilityCertificate() {
        assertThrows(IllegalArgumentException.class, () -> new QuerySpec(1, 3, 5, 0, 5, 1));
        PACE pace = new PACE(scoreChain(), PaceOptions.exhaustive(2));
        assertThrows(IllegalArgumentException.class, () -> pace.run(new QuerySpec(1, 99, 0, 5, 5, 1)));
        PaceException unsupported = assertThrows(PaceException.class,
                () -> pace.run(new QuerySpec(1, 3, 1439, 1440, 5, 1)));
        assertEquals(PaceStatus.FUNCTION_HORIZON_EXCEEDED, unsupported.status());
    }

    private static void assertOutcome(boolean hasCandidates, boolean exhaustive, PaceCapStatus caps,
            PaceCompletion completion, PaceExactnessScope scope) {
        var outcome = PaceGenerationOutcome.resolve(PaceExecutionPolicy.PACE_X,
                hasCandidates, caps, exhaustive, true);
        assertEquals(completion, outcome.completion());
        assertEquals(scope, outcome.exactness());
    }

    private static TDGraph scoreChain() {
        PiecewiseConstFn score = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 1440, 7)));
        return new TinyGraphBuilder().node(1).node(2).node(3)
                .edge(1, 2, 1, score).edge(2, 3, 1, score).build();
    }

    private static AlgorithmConfig config(int theta) {
        return new AlgorithmConfig("pace-x", Ablation.NONE, theta, 100, 100,
                1, 1, 10, 1000, 1000, 1000, 1000, true, 42);
    }
}
