package edu.ipcmax.experiments.querygen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;

class QueryGenerationOptionsTest {
    @Test
    void defaultsToAllDatasetsAndConfiguredRepositoryPaths() {
        QueryGenerationOptions options = QueryGenerationOptions.parse();

        assertTrue(options.allDatasets());
        assertEquals(Set.of("CAL", "FLA", "NY", "NY-EXACT", "OL"), options.selectedDatasets());
        assertEquals(Path.of("data/input"), options.dataRoot());
        assertEquals(Path.of("results/manifests"), options.outputRoot());
        assertEquals(Path.of("experiments/configs/query_generation.yaml"), options.configurationPath());
        assertEquals(1, options.threads());
        assertEquals(20260711L, options.seed());
    }

    @Test
    void parsesDatasetSelectionAndEveryExecutionFlag() {
        QueryGenerationOptions options = QueryGenerationOptions.parse(
                "--dataset", "ny,FLA",
                "--data-root", "inputs",
                "--output-root", "outputs",
                "--config", "querygen.yaml",
                "--threads", "24",
                "--seed", "99",
                "--overwrite", "--dry-run", "--validate-only", "--verbose");

        assertFalse(options.allDatasets());
        assertEquals(Set.of("NY", "FLA"), options.datasets());
        assertEquals(Path.of("inputs"), options.dataRoot());
        assertEquals(Path.of("outputs"), options.outputRoot());
        assertEquals(Path.of("querygen.yaml"), options.configurationPath());
        assertEquals(24, options.threads());
        assertEquals(99L, options.seed());
        assertTrue(options.overwrite());
        assertFalse(options.resume());
        assertTrue(options.dryRun());
        assertTrue(options.validateOnly());
        assertTrue(options.verbose());

        assertTrue(QueryGenerationOptions.parse("--resume").resume());
    }

    @Test
    void enforcesThreadLimitsAndOptionConflicts() {
        assertEquals(1, QueryGenerationOptions.parse("--threads", "1").threads());
        assertEquals(24, QueryGenerationOptions.parse("--threads", "24").threads());
        assertThrows(IllegalArgumentException.class,
                () -> QueryGenerationOptions.parse("--threads", "0"));
        assertThrows(IllegalArgumentException.class,
                () -> QueryGenerationOptions.parse("--threads", "25"));
        assertThrows(IllegalArgumentException.class,
                () -> QueryGenerationOptions.parse("--overwrite", "--resume"));
        assertThrows(IllegalArgumentException.class,
                () -> QueryGenerationOptions.parse("--dataset", "NY", "--all-datasets"));
        assertThrows(IllegalArgumentException.class,
                () -> QueryGenerationOptions.parse("--dataset", "UNKNOWN"));
    }
}
