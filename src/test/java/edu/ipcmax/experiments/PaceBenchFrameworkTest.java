package edu.ipcmax.experiments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.experiments.framework.ProfileSupport;
import edu.ipcmax.experiments.framework.QueryManifestIO;

class PaceBenchFrameworkTest {
    @TempDir
    Path temporary;

    @Test
    void streamingGraphSemanticChecksumMatchesLegacyCanonicalRepresentation() {
        TDGraph graph = ExperimentDatasets.demo();
        StringBuilder legacyCanonical = new StringBuilder();
        for (Edge edge : graph.edges()) {
            legacyCanonical.append(edge.arcId()).append(':').append(edge.source()).append(':')
                    .append(edge.target()).append(':').append(edge.distance()).append(':');
            edge.travelTimeFunction().breakpoints().forEach(point -> legacyCanonical
                    .append(point.minute()).append('=').append(point.value()).append(','));
            legacyCanonical.append(':');
            edge.scoreFunction().intervals().forEach(piece -> legacyCanonical
                    .append(piece.startMinute()).append('=').append(piece.endMinute())
                    .append('=').append(piece.value()).append(','));
            legacyCanonical.append('\n');
        }

        assertEquals(ProfileSupport.sha256(legacyCanonical.toString()),
                PaceBench.graphSemanticChecksum(graph));
    }

    @Test
    void writesOneStableSchemaRecordPerQueryAndResumeDoesNotDuplicate() throws Exception {
        Path output = temporary.resolve("raw/results.jsonl");
        String[] arguments = {
                "--algorithm", "exh-profile", "--dataset", "demo",
                "--query-file", "experiments/manifests/tiny.jsonl",
                "--output-jsonl", output.toString(), "--experiment-name", "framework-test",
                "--repetitions", "2", "--deterministic", "--max-enumerated-paths", "100"
        };
        assertEquals(0, PaceBench.execute(arguments));
        List<String> lines = Files.readAllLines(output);
        assertEquals(6, lines.size());
        HashSet<String> runIds = new HashSet<>();
        HashSet<String> configHashes = new HashSet<>();
        HashSet<String> scientificConfigHashes = new HashSet<>();
        boolean sawNoPath = false;
        for (String line : lines) {
            JsonNode record = QueryManifestIO.mapper().readTree(line);
            assertEquals(3, record.path("schema_version").asInt());
            for (String section : List.of("run", "system", "dataset", "query", "configuration",
                    "status", "timing_ns", "memory_bytes", "counters", "output", "quality", "error")) {
                assertTrue(record.has(section), section);
            }
            assertTrue(runIds.add(record.path("run").path("run_id").asText()));
            configHashes.add(record.path("run").path("config_hash").asText());
            scientificConfigHashes.add(record.path("run")
                    .path("scientific_config_hash").asText());
            assertTrue(record.path("configuration").path("theta").isNull());
            assertTrue(record.path("configuration").path("baseline_k").isNull());
            assertTrue(record.path("status").path("execution_policy").isNull());
            assertEquals("NOT_CERTIFIED", record.path("status").path("exactness_scope").asText());
            assertTrue(record.path("timing_ns").path("query_total").isIntegralNumber());
            assertTrue(record.path("error").path("failing_phase").isNull());
            assertFalse(record.toString().contains("NaN"));
            if (record.path("status").path("status_code").asText().equals("NO_FEASIBLE_PATH")) {
                sawNoPath = true;
                assertTrue(record.path("status").path("completed").asBoolean());
            }
        }
        assertEquals(1, configHashes.size());
        assertEquals(1, scientificConfigHashes.size());
        assertTrue(sawNoPath);

        String[] resumed = java.util.Arrays.copyOf(arguments, arguments.length + 1);
        resumed[arguments.length] = "--resume";
        assertEquals(0, PaceBench.execute(resumed));
        assertEquals(6, Files.readAllLines(output).size());
    }

    @Test
    void paceXSerializesVerifiedExhaustiveExactnessAndCompletion() throws Exception {
        Path output = temporary.resolve("pace-x.jsonl");
        assertEquals(0, PaceBench.execute(new String[] {
                "--algorithm", "pace-x", "--dataset", "demo",
                "--query-file", "experiments/manifests/tiny.jsonl",
                "--output-jsonl", output.toString(), "--theta", "4"
        }));

        JsonNode record = QueryManifestIO.mapper().readTree(Files.readAllLines(output).get(0));
        assertEquals(3, record.path("schema_version").asInt());
        assertEquals("PACE_X_EXHAUSTIVE", record.path("configuration").path("execution_mode").asText());
        assertTrue(record.path("configuration").path("exhaustive_anchors").isNull());
        assertEquals("PACE_X", record.path("status").path("execution_policy").asText());
        assertEquals("GLOBAL_CERTIFIED", record.path("status").path("exactness_scope").asText());
        assertEquals("COMPLETE", record.path("status").path("generation_completion").asText());
        assertTrue(record.path("status").path("cap_triggered").isEmpty());
    }

    @Test
    void paceBSerializesTypedCapAndRetainedFrontierStatus() throws Exception {
        Path output = temporary.resolve("pace-b-cap.jsonl");
        assertEquals(1, PaceBench.execute(new String[] {
                "--algorithm", "pace-b", "--dataset", "demo",
                "--query-file", "experiments/manifests/tiny.jsonl",
                "--output-jsonl", output.toString(),
                "--theta", "4", "--pivot-limit-l", "32",
                "--connector-limit-kc", "16",
                "--frontier-limit-kf", "16",
                "--connector-expansion-cap-mc", "10000",
                "--breakpoint-cap-mb", "10000",
                "--query-work-cap-mq", "1"
        }));

        JsonNode capped = Files.readAllLines(output).stream()
                .map(line -> {
                    try {
                        return QueryManifestIO.mapper().readTree(line);
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                })
                .filter(record -> !record.path("status")
                        .path("cap_triggered").isEmpty())
                .findFirst()
                .orElseThrow();
        assertEquals("LIMIT_EXCEEDED",
                capped.path("status").path("status_code").asText());
        assertEquals("RESOURCE_TRUNCATED",
                capped.path("status").path("generation_completion").asText());
        assertEquals("RETAINED_FRONTIER",
                capped.path("status").path("exactness_scope").asText());
        assertTrue(StreamSupport.stream(
                capped.path("status").path("cap_triggered")
                        .spliterator(),
                false).anyMatch(value ->
                        value.asText().equals("QUERY_WORK_M_Q")));
        assertTrue(capped.path("counters")
                .path("total_candidate_work").asLong() <= 1);
    }

    @Test
    void guardFailureStillProducesFormalRecords() throws Exception {
        Path output = temporary.resolve("guard.jsonl");
        int exit = PaceBench.execute(new String[] {
                "--algorithm", "exh-profile", "--dataset", "demo",
                "--query-file", "experiments/manifests/tiny.jsonl",
                "--output-jsonl", output.toString(), "--max-enumerated-paths", "1"
        });
        assertEquals(1, exit);
        List<JsonNode> records = Files.readAllLines(output).stream().map(line -> {
            try {
                return QueryManifestIO.mapper().readTree(line);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }).toList();
        assertEquals(3, records.size());
        JsonNode limited = records.stream()
                .filter(record -> record.path("status").path("status_code").asText().equals("LIMIT_EXCEEDED"))
                .findFirst().orElseThrow();
        assertFalse(limited.path("status").path("completed").asBoolean());
        assertNotNull(limited.path("error").path("message").textValue());
        assertTrue(limited.path("output").path("profile_checksum").isNull());
    }

    @Test
    void timeoutInIsolatedCliProcessProducesAValidRecord() throws Exception {
        Path output = temporary.resolve("timeout.jsonl");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                PaceBench.class.getName(),
                "--algorithm", "exh-profile", "--dataset", "timeout-test",
                "--query-file", "experiments/manifests/tiny.jsonl",
                "--output-jsonl", output.toString(), "--max-enumerated-paths", "1000000000",
                "--timeout-seconds", "1", "--fail-fast")
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(1, process.exitValue());
        List<String> lines = Files.readAllLines(output);
        assertEquals(1, lines.size());
        JsonNode record = QueryManifestIO.mapper().readTree(lines.get(0));
        assertEquals("TIMEOUT", record.path("status").path("status_code").asText());
        assertFalse(record.path("status").path("completed").asBoolean());
        assertTrue(record.path("output").path("profile_checksum").isNull());
    }

    @Test
    void memoryThresholdProducesFormalOutOfMemoryStatus() throws Exception {
        Path output = temporary.resolve("memory.jsonl");
        assertEquals(1, PaceBench.execute(new String[] {
                "--algorithm", "pace-b", "--dataset", "demo",
                "--query-file", "experiments/manifests/tiny.jsonl",
                "--output-jsonl", output.toString(), "--memory-limit-mb", "1",
                "--fail-fast"
        }));
        JsonNode record = QueryManifestIO.mapper().readTree(Files.readString(output).strip());
        assertEquals("OUT_OF_MEMORY", record.path("status").path("status_code").asText());
        assertFalse(record.path("status").path("completed").asBoolean());
        assertFalse(record.path("system").path("process_id").asLong()
                == ProcessHandle.current().pid());
    }

    @Test
    void forcedWorkerFailureSerializesWithoutLoadingTheDataset() throws Exception {
        Path output = temporary.resolve("forced-preprocessing.jsonl");
        Path progress = temporary.resolve("missing-progress.json");
        Path query = temporary.resolve("one-query.jsonl");
        Files.writeString(
                query,
                Files.readAllLines(
                        Path.of("experiments/manifests/tiny.jsonl")).get(0)
                        + System.lineSeparator());
        assertEquals(1, PaceBench.execute(new String[] {
                "--algorithm", "pace-b",
                "--dataset", temporary.resolve("dataset-does-not-exist").toString(),
                "--query-file", query.toString(),
                "--output-jsonl", output.toString(),
                "--internal-worker",
                "--internal-repetition", "0",
                "--internal-progress-file", progress.toString(),
                "--internal-forced-status", "ERROR",
                "--internal-forced-reason", "PreprocessingTimeout"
        }));

        JsonNode record = QueryManifestIO.mapper().readTree(
                Files.readAllLines(output).get(0));
        assertEquals(
                "ERROR",
                record.path("status").path("status_code").asText());
        assertEquals(
                "PreprocessingTimeout",
                record.path("error").path("type").asText());
        assertTrue(record.path("dataset")
                .path("runtime_graph_semantic_checksum").isNull());
        assertTrue(record.path("timing_ns")
                .path("preprocessing_total").isNull());
    }
}
