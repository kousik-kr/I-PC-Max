package edu.ipcmax.experiments.algorithms;

import edu.ipcmax.experiments.AlgorithmRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Saves the M5 missing-dispatch failures before adapter implementation. */
class ObjectiveAdapterRegistrationTest {
    @Test void partitionedThopHasItsOwnObjectiveAdapter() {
        assertEquals("thop-partitioned", AlgorithmRegistry.create("thop-partitioned").id());
    }

    @Test void nativeSicspHasASeparateReproductionAdapter() {
        assertEquals("sicsp-native", AlgorithmRegistry.create("sicsp-native").id());
    }
}
