package edu.ipcmax.experiments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import edu.ipcmax.core.pcmax.PACE;
import edu.ipcmax.core.pcmax.PaceOptions;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.experiments.algorithms.ExhaustiveProfileAlgorithm;
import edu.ipcmax.experiments.algorithms.IntervalBestAlgorithm;
import edu.ipcmax.experiments.algorithms.KspProfileAlgorithm;
import edu.ipcmax.experiments.algorithms.PaceExperimentAlgorithm;
import edu.ipcmax.experiments.algorithms.ProfileLabelingAlgorithm;
import edu.ipcmax.experiments.algorithms.RpqAlgorithm;
import edu.ipcmax.experiments.framework.Ablation;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.LimitExceededException;
import edu.ipcmax.experiments.framework.ProfileSupport;

class ExperimentAlgorithmsTest {
    private static final QuerySpec QUERY = new QuerySpec(1, 4, 420, 430, 60, 1);

    @Test
    void independentExactAlgorithmsMatchPaceX() {
        var graph = ExperimentDatasets.demo();
        var exh = new ExhaustiveProfileAlgorithm().run(
                graph, QUERY, config("exh-profile"), new ExperimentInstrumentation());
        var labeling = new ProfileLabelingAlgorithm().run(
                graph, QUERY, config("pl-exact"), new ExperimentInstrumentation());
        var pace = new PACE(graph, PaceOptions.exhaustive(4)).run(QUERY);

        assertEquals(ExperimentStatus.COMPLETED, exh.status());
        assertEquals(ExactnessScope.NOT_CERTIFIED, exh.exactnessScope());
        assertEquals(ExactnessScope.NOT_CERTIFIED, labeling.exactnessScope());
        assertEquals(ProfileSupport.checksum(exh.profile()), ProfileSupport.checksum(labeling.profile()));
        assertEquals(ProfileSupport.checksum(exh.profile()), ProfileSupport.checksum(pace));
        assertEquals(3, exh.scalars().get("paths_enumerated"));
    }

    @Test
    void pacePoliciesReportPolicySpecificExactness() {
        var graph = ExperimentDatasets.demo();
        for (String policy : List.of("pace-x", "pace-b")) {
            var result = new PaceExperimentAlgorithm(policy).run(
                    graph, QUERY, config(policy), new ExperimentInstrumentation());

            if (policy.equals("pace-x")) {
                assertEquals(ExactnessScope.GLOBAL_CERTIFIED, result.exactnessScope());
                assertTrue(result.completeProfile());
            } else {
                assertEquals(ExactnessScope.RETAINED_FRONTIER, result.exactnessScope());
                assertFalse(result.completeProfile());
            }
        }
    }

    @Test
    void exactGuardsFailWithoutReturningPartialProfiles() {
        var graph = ExperimentDatasets.demo();
        assertThrows(LimitExceededException.class, () -> new ExhaustiveProfileAlgorithm().run(
                graph, QUERY, config("exh-profile", 1, 100, 100), new ExperimentInstrumentation()));
        assertThrows(LimitExceededException.class, () -> new ProfileLabelingAlgorithm().run(
                graph, QUERY, config("pl-exact", 100, 100, 1), new ExperimentInstrumentation()));
    }

    @Test
    void rpqUsesEverySampleAndSeparateFinalEndpoint() {
        var graph = ExperimentDatasets.demo();
        for (int[] expectation : List.of(new int[] {1, 11}, new int[] {5, 3}, new int[] {15, 2})) {
            ExperimentInstrumentation instrumentation = new ExperimentInstrumentation();
            var result = new RpqAlgorithm().run(
                    graph, QUERY, config("rpq", expectation[0], 0), instrumentation);
            assertEquals(expectation[1], instrumentation.counters().get("point_queries"));
            assertFalse(result.completeProfile());
            assertNotNull(result.profile().segmentAt(430));
        }
    }

    @Test
    void kspRetainsAtMostKAndDeclaresRetainedFrontierSemantics() {
        ExperimentInstrumentation instrumentation = new ExperimentInstrumentation();
        var result = new KspProfileAlgorithm().run(
                ExperimentDatasets.demo(), QUERY, config("ksp-profile", 0, 2), instrumentation);
        assertEquals(2L, instrumentation.counters().get("ksp_paths_retained"));
        assertEquals(ExactnessScope.RETAINED_FRONTIER, result.exactnessScope());
        assertFalse(result.completeProfile());
        assertThrows(LimitExceededException.class, () -> new KspProfileAlgorithm().run(
                ExperimentDatasets.demo(), QUERY,
                config("ksp-profile", Ablation.NONE, 4, 8, 8, 1, 0, 2, 1, 100, 100),
                new ExperimentInstrumentation()));
    }

    @Test
    void intervalBestReturnsOneSelectionAndEvaluationOnlyProfile() {
        var result = new IntervalBestAlgorithm().run(
                ExperimentDatasets.demo(), QUERY, config("interval-best"), new ExperimentInstrumentation());
        assertEquals(ExperimentStatus.COMPLETED, result.status());
        assertEquals(ExactnessScope.NOT_CERTIFIED, result.exactnessScope());
        assertFalse(result.completeProfile());
        assertNotNull(result.scalars().get("selected_departure_time"));
        assertNotNull(result.scalars().get("selected_path_id"));
        assertTrue(result.profile().segments().stream().anyMatch(segment -> segment.found()));
    }

    @Test
    void finalAblationsToggleOnlyTheirDeclaredProductionFeatures() {
        var base = config("pace-b").paceOptions();

        assertFalse(config("pace-b", Ablation.NO_SAFE_CORRIDOR, 4, 8, 8, 1,
                5, 2, 10_000, 10_000, 50_000).paceOptions().features().safeCorridorEnabled());
        assertFalse(config("pace-b", Ablation.NO_PIVOT_DIVERSIFICATION, 4, 8, 8, 1,
                5, 2, 10_000, 10_000, 50_000).paceOptions().features().pivotDiversificationEnabled());
        assertFalse(config("pace-b", Ablation.FAST_ONLY_CONNECTOR, 4, 8, 8, 1,
                5, 2, 10_000, 10_000, 50_000).paceOptions().features().connectorPortfolioEnabled());
        assertFalse(config("pace-b", Ablation.NO_CONNECTOR_CACHE, 4, 8, 8, 1,
                5, 2, 10_000, 10_000, 50_000).paceOptions().features().connectorCacheEnabled());
        assertFalse(config("pace-b", Ablation.NO_SCORE_UPPER_BOUND, 4, 8, 8, 1,
                5, 2, 10_000, 10_000, 50_000).paceOptions().features().scoreUpperBoundEnabled());
        var noMemo = config("pace-b", Ablation.NO_MEMO, 4, 8, 8, 1,
                5, 2, 10_000, 10_000, 50_000).paceOptions();
        assertFalse(noMemo.memoizationEnabled());
        assertFalse(noMemo.features().connectorCacheEnabled());
        assertFalse(noMemo.features().profileCacheEnabled());
        assertTrue(base.features().safeCorridorEnabled());
        assertTrue(base.features().pivotDiversificationEnabled());
        assertTrue(base.features().connectorPortfolioEnabled());
        assertTrue(base.features().connectorCacheEnabled());
        assertTrue(base.features().scoreUpperBoundEnabled());
    }

    private static AlgorithmConfig config(String algorithm) {
        return config(algorithm, Ablation.NONE, 4, 8, 8, 1, 5, 2, 10_000, 10_000, 50_000);
    }

    private static AlgorithmConfig config(String algorithm, long maxPaths, long maxLabels, long maxExpansions) {
        return config(algorithm, Ablation.NONE, 4, 8, 8, 1, 5, 2, maxPaths, maxLabels, maxExpansions);
    }

    private static AlgorithmConfig config(String algorithm, int rpqStep, int baselineK) {
        return config(algorithm, Ablation.NONE, 4, 8, 8, 1, rpqStep, baselineK,
                10_000, 10_000, 50_000);
    }

    private static AlgorithmConfig config(
            String algorithm, Ablation ablation, int theta, int anchorLimit, int k, int threads,
            int rpqStep, int baselineK, long maxPaths, long maxLabels, long maxExpansions) {
        return new AlgorithmConfig(algorithm, ablation, theta, anchorLimit, k, threads,
                rpqStep, baselineK, maxPaths, maxLabels, maxExpansions, 100_000, true, 42);
    }
}
