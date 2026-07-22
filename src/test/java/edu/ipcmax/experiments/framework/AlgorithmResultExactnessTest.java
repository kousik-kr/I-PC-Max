package edu.ipcmax.experiments.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;

import org.junit.jupiter.api.Test;

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
}
