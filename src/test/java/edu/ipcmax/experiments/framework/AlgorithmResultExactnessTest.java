package edu.ipcmax.experiments.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.pcmax.EnvelopeProfile;
import edu.ipcmax.core.pcmax.EnvelopeSegment;

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
}
