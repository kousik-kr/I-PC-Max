package edu.ipcmax.experiments;

import edu.ipcmax.experiments.framework.QueryManifestIO;
import edu.ipcmax.experiments.framework.ExperimentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** A terminal anytime result must not be counted as completed algorithm work. */
class ExperimentCompletionAccountingTest {
    @TempDir Path temporary;

    @Test void completionMappingSeparatesTimeCapsFromFinishedSearches() {
        assertFalse(ExperimentStatus.TIME_CAPPED_NOT_CERTIFIED.completedExecution());
        assertFalse(ExperimentStatus.PATH_CAPPED_NOT_CERTIFIED.completedExecution());
        assertTrue(ExperimentStatus.COMPLETED.completedExecution());
        assertTrue(ExperimentStatus.CERTIFIED_COMPLETE.completedExecution());
        assertTrue(ExperimentStatus.UNRESOLVED_NO_GENERATED_CANDIDATE.completedExecution());
        assertTrue(ExperimentStatus.NO_FEASIBLE_PATH.completedExecution());
    }

    @Test void pathCappedAnytimeRowsAreTerminalAndIncomplete() throws Exception {
        Path output = run("capped.jsonl", "1");
        boolean sawCapped = false;
        for (String line : Files.readAllLines(output)) {
            var row = QueryManifestIO.mapper().readTree(line);
            String status = row.path("status").path("status_code").asText();
            if (status.equals("PATH_CAPPED_NOT_CERTIFIED")) {
                sawCapped = true;
                assertFalse(row.path("status").path("completed").asBoolean(),
                        "A valid partial profile is terminal but the enumeration did not complete");
                assertTrue(row.path("status").path("terminal").asBoolean());
                assertEquals("NOT_CERTIFIED", row.path("status").path("exactness_scope").asText());
            }
        }
        assertTrue(sawCapped, "The fixed tiny graph has several paths, so max-paths=1 must cap it");
    }

    @Test void exhaustedFeasibleAndCertifiedNoPathRowsRemainCompletedAndTerminal() throws Exception {
        Path output = run("complete.jsonl", "100");
        boolean sawFeasible = false;
        boolean sawNoPath = false;
        for (String line : Files.readAllLines(output)) {
            var row = QueryManifestIO.mapper().readTree(line);
            String status = row.path("status").path("status_code").asText();
            if (status.equals("COMPLETED") || status.equals("CERTIFIED_COMPLETE")
                    || status.equals("NO_FEASIBLE_PATH")) {
                assertTrue(row.path("status").path("completed").asBoolean());
                assertTrue(row.path("status").path("terminal").asBoolean());
                sawFeasible |= status.equals("COMPLETED")
                        || status.equals("CERTIFIED_COMPLETE");
                sawNoPath |= status.equals("NO_FEASIBLE_PATH");
            }
        }
        assertTrue(sawFeasible);
        assertTrue(sawNoPath);
    }

    private Path run(String filename, String pathLimit) throws Exception {
        Path output = temporary.resolve(filename);
        assertEquals(0, PaceBench.execute(new String[]{"--algorithm", "iscope", "--dataset", "demo",
                "--query-file", "experiments/manifests/tiny.jsonl", "--output-jsonl", output.toString(),
                "--timeout-seconds", "5", "--max-enumerated-paths", pathLimit,
                "--shared-preprocessing", "--threads", "1", "--deterministic"}));
        return output;
    }
}
