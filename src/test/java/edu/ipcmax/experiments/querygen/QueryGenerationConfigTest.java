package edu.ipcmax.experiments.querygen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueryGenerationConfigTest {
    @TempDir
    Path temporary;

    @Test
    void loadsTheCheckedInGenerationConfiguration() throws Exception {
        QueryGenerationConfig config = QueryGenerationConfig.load(
                Path.of("experiments/configs/query_generation.yaml"));

        assertEquals(1, config.schemaVersion());
        assertEquals(20260711L, config.seed());
        assertEquals(16, config.candidatePool().sampledSources());
        assertEquals(2_000, config.candidatePool().maximumPairs());
        assertEquals(5, config.candidatePool().minimumLowerBoundEdges());
        assertTrue(config.candidatePool().requireReachable());
        assertTrue(config.candidatePool().requireAnchorCorridor());
        assertEquals(6, config.main().pairsPerDistanceBin());
        assertEquals(120, config.main().windowMinutes());
        assertEquals(0.25, config.main().budgetSlack());
        assertEquals("FULL_INTERVAL_FEASIBLE", config.main().budgetPolicy());
        assertEquals(List.of(DistanceBin.Q3, DistanceBin.Q4), config.parallelism().distanceBins());
        assertEquals(
                List.of(TemporalRegime.MORNING_PEAK, TemporalRegime.EVENING_PEAK),
                config.parallelism().temporalRegimes());
        assertEquals(List.of(30, 60, 120, 240, 360), config.windowSensitivity().valuesMinutes());
        assertEquals(List.of(0.05, 0.10, 0.25, 0.50), config.budgetSensitivity().slackValues());
        assertEquals(420, config.temporalRegimes().get(TemporalRegime.MORNING_PEAK).preferredStart());
        assertEquals(60, config.temporalRegimes().get(TemporalRegime.LATE_OFFPEAK).preferredStart());
    }

    @Test
    void rejectsMissingPrimitiveConfigurationFields() throws Exception {
        String source = Files.readString(Path.of("experiments/configs/query_generation.yaml"));
        String missingBoolean = source.replaceAll("(?m)^  require_reachable: true\\R", "");
        Path path = temporary.resolve("missing-required-field.yaml");
        Files.writeString(path, missingBoolean);

        assertThrows(IOException.class, () -> QueryGenerationConfig.load(path));
    }
}
