package edu.ipcmax.experiments.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import edu.ipcmax.core.pcmax.PaceExecutionMetrics;

class ExperimentInstrumentationProgressTest {
    @TempDir
    Path temporary;

    @Test
    void atomicSnapshotSurvivesWorkerTerminationAndRecoversCumulativeWork()
            throws Exception {
        Path progress = temporary.resolve("progress.json");
        ExperimentInstrumentation live =
                new ExperimentInstrumentation(progress);
        live.accept(new PaceExecutionMetrics.Snapshot(
                "equality_root_computation",
                123_456_789L,
                Map.of(
                        "breakpoint_processing", 17L,
                        "equality_root_computation", 23L),
                Map.of(
                        "candidate_offers", 11L,
                        "candidate_pair_root_checks", 29L)));

        assertTrue(Files.isRegularFile(progress));
        try (var files = Files.list(temporary)) {
            assertFalse(files.anyMatch(path ->
                    path.getFileName().toString()
                            .startsWith("progress.json.tmp-")));
        }

        ExperimentInstrumentation recovered =
                new ExperimentInstrumentation();
        assertTrue(recovered.recover(progress));
        assertEquals(
                "equality_root_computation",
                recovered.currentPhase());
        assertEquals(123_456_789L, recovered.elapsedNanos());
        assertEquals(
                23L,
                recovered.timings().get(
                        "equality_root_computation"));
        assertEquals(
                29L,
                recovered.counters().get(
                        "candidate_pair_root_checks"));
    }
}
