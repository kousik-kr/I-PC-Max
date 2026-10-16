package edu.ipcmax.experiments.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.pcmax.EnvelopeProfile;
import edu.ipcmax.core.pcmax.EnvelopeSegment;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.validate.Path;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceObjective;

class AlgorithmResultExactnessTest {
    @Test
    void failureCannotCarryAnExactnessCertificate() {
        AlgorithmResult result = new AlgorithmResult(
                ExperimentStatus.ERROR,
                null,
                ExactnessScope.GLOBAL_CERTIFIED,
                Map.of(),
                "Failure",
                "failed");

        assertEquals(ExactnessScope.NOT_CERTIFIED, result.exactnessScope());
        assertFalse(result.completeProfile());
    }

    @SuppressWarnings("deprecation")
    @Test
    void legacyBooleanConstructorRemainsSourceCompatible() {
        AlgorithmResult result = new AlgorithmResult(
                ExperimentStatus.COMPLETED, null, true, Map.of(), null, null);

        assertEquals(ExactnessScope.GLOBAL_CERTIFIED, result.exactnessScope());
    }

    @Test
    void profileQualityIncludesRelativeScoreGapPercent() {
        EnvelopeProfile noPath = new EnvelopeProfile(
                Domain.closed(0, 1),
                List.of(new EnvelopeSegment(new Domain.Interval(0, 1, true, true), null)));

        Map<String, Object> quality = ProfileSupport.quality(noPath, noPath);

        assertEquals(0.0, quality.get("integrated_score_regret"));
        assertEquals(0.0, quality.get("relative_score_gap_percent"));
        assertEquals(1.0, quality.get("score_agreement_fraction"));
    }

    @Test
    void exactObjectiveProfileIsAuthoritativeBesideLegacyProjection() {
        var graph = new TinyGraphBuilder().node(1).node(2).edge(1, 2, 1).build();
        var query = new QuerySpec(1, 2, 0, 10, 3, 1);
        var exact = ExactPreferenceObjective.evaluate(graph, query, List.of(new Path(List.of(0))),
                123L, "COMPLETED", "RETAINED_FRONTIER", "test-registry");
        EnvelopeProfile legacy = new EnvelopeProfile(Domain.closed(0, 10), List.of(
                new EnvelopeSegment(new Domain.Interval(0, 10, true, true), null)));
        AlgorithmResult result = new AlgorithmResult(ExperimentStatus.COMPLETED, legacy,
                ExactnessScope.RETAINED_FRONTIER,
                Map.of("objective_result", exact.objectiveResult()), null, null);

        Map<String, Object> output = ProfileSupport.output(result);
        assertEquals("exact-preference-envelope-v1", output.get("exact_profile_schema"));
        assertEquals(exact.objectiveResult().get("exact_profile_checksum"), output.get("profile_checksum"));
        assertEquals(true, output.get("feasible"));
        assertTrue(output.containsKey("legacy_projection"));
        assertEquals(exact.objectiveResult().get("exact_profile_checksum"),
                ProfileSupport.resultChecksum(result));
    }
}
