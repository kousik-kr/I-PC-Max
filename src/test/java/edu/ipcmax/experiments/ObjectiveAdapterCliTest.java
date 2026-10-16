package edu.ipcmax.experiments;

import edu.ipcmax.experiments.framework.QueryManifestIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ObjectiveAdapterCliTest {
    @TempDir Path temporary;

    @Test void nativeReproductionCliSerializesObjectiveAndPreservesHeuristicStatus() throws Exception {
        Path output = temporary.resolve("native.jsonl");
        assertEquals(0, PaceBench.execute(new String[]{"--algorithm", "sicsp-native",
                "--dataset", "sicsp-figure1", "--query-file",
                "experiments/fixtures/sicsp_native_figure1/queries.jsonl",
                "--sicsp-weights", "experiments/fixtures/sicsp_native_figure1/weights.json",
                "--sicsp-weight-bound", "80", "--output-jsonl", output.toString(),
                "--serialize-profile", "--profile-output-dir", temporary.resolve("profiles").toString(),
                "--repetitions", "2", "--warmup-runs", "1"}));
        List<String> rows = Files.readAllLines(output);
        assertEquals(3, rows.size());
        String checksum = null;
        for (String row : rows) {
            var record = QueryManifestIO.mapper().readTree(row);
            assertEquals("COMPLETED", record.path("status").path("status_code").asText());
            assertEquals("NOT_CERTIFIED", record.path("status").path("exactness_scope").asText());
            assertEquals("legacy_canonical_12_decimal_per_operation-v1",
                    record.path("counters").path("numerical_contract").asText());
            assertTrue(record.path("dataset").path("fixture_semantic_identity_sha256").asText().matches("[0-9a-f]{64}"));
            assertEquals(50.0, record.path("output").path("earliest_candidate_arrival").asDouble());
            assertTrue(record.path("output").path("average_selected_score").isNull());
            assertTrue(record.path("timing_ns").path("query_total").isIntegralNumber());
            assertTrue(record.path("counters").path("reverse_search_calls").asLong() > 0);
            assertTrue(Files.isRegularFile(Path.of(record.path("output").path("profile_file").asText())));
            if (checksum == null) checksum = record.path("output").path("profile_checksum").asText();
            else assertEquals(checksum, record.path("output").path("profile_checksum").asText());
        }
    }

    @Test void thopCliBuildsOnceAndSerializesExactRationalCells() throws Exception {
        Path output = temporary.resolve("thop.jsonl");
        assertEquals(0, PaceBench.execute(new String[]{"--algorithm", "thop-partitioned",
                "--dataset", "demo", "--query-file", "experiments/manifests/tiny.jsonl",
                "--thop-index-dir", temporary.resolve("index").toString(),
                "--thop-partitions", "experiments/fixtures/thop_demo/partitions.json",
                "--thop-domain-start", "0", "--thop-domain-end", "1440", "--thop-lifecycle", "build",
                "--output-jsonl", output.toString(), "--repetitions", "2", "--warmup-runs", "1",
                "--serialize-profile", "--profile-output-dir", temporary.resolve("thop-profiles").toString()}));
        var rows = Files.readAllLines(output);
        assertEquals(9, rows.size());
        boolean sawUnreachable = false;
        for (String row : rows) {
            var record = QueryManifestIO.mapper().readTree(row);
            assertEquals("GLOBAL_CERTIFIED", record.path("status").path("exactness_scope").asText());
            assertEquals("fastest_profile", record.path("output").path("objective").asText());
            assertTrue(record.path("dataset").path("fixture_semantic_identity_sha256").asText().matches("[0-9a-f]{64}"));
            assertEquals("exact_rational", record.path("output").path("precision").asText());
            assertTrue(record.path("output").path("fastest_travel_time_profile").isArray());
            assertTrue(record.path("output").path("average_selected_score").isNull());
            assertTrue(record.path("quality").path("relative_score_gap_percent").isNull());
            assertEquals("search_first_then_prune_materialize",
                    record.path("counters").path("construction_schedule").asText());
            assertTrue(record.path("timing_ns").path("preprocessing_total").asLong() > 0);
            assertTrue(Files.isRegularFile(Path.of(record.path("output").path("profile_file").asText())));
            if (record.path("status").path("status_code").asText().equals("NO_FEASIBLE_PATH")) sawUnreachable = true;
        }
        assertTrue(sawUnreachable);
    }

    @Test void configurationRejectsImplicitNativeWeightsMissingThopLifecycleInputsAndMixedObjectives() {
        assertThrows(IllegalArgumentException.class, () -> parse("sicsp-native"));
        assertThrows(IllegalArgumentException.class, () -> parse("thop-partitioned"));
        assertThrows(IllegalArgumentException.class, () -> parse("pace-b", "--sicsp-weight-bound", "1"));
        assertThrows(IllegalArgumentException.class, () -> parse("sicsp-native", "--sicsp-weights", "weights.json",
                "--sicsp-weight-bound", "80", "--reference-algorithm", "pace-x"));
    }

    @Test void changingNativeWeightContentsAtSamePathChangesConfigIdentityAndRejectsStalePreparation() throws Exception {
        Path weights = temporary.resolve("weights.json");
        String original = Files.readString(Path.of("experiments/fixtures/sicsp_native_figure1/weights.json"));
        Files.writeString(weights, original);
        var before = parse("sicsp-native", "--sicsp-weights", weights.toString(), "--sicsp-weight-bound", "80");
        String beforeHash = edu.ipcmax.experiments.framework.ProfileSupport.sha256(
                QueryManifestIO.mapper().writeValueAsString(before.normalized()));
        Files.writeString(weights, original.replace("\"weight\": 15", "\"weight\": 16"));
        var after = parse("sicsp-native", "--sicsp-weights", weights.toString(), "--sicsp-weight-bound", "80");
        String afterHash = edu.ipcmax.experiments.framework.ProfileSupport.sha256(
                QueryManifestIO.mapper().writeValueAsString(after.normalized()));
        assertNotEquals(beforeHash, afterHash);
        assertThrows(IllegalArgumentException.class, () ->
                new edu.ipcmax.experiments.algorithms.SicspNativeExperimentAlgorithm().prepare(
                        ExperimentDatasets.sicspFigureOne(), before.algorithmConfig()));
    }

    @Test void changingPartitionContentsAtSamePathChangesConfigIdentityAndRejectsStalePreparation() throws Exception {
        Path partitions = temporary.resolve("partitions.json");
        String original = Files.readString(Path.of("experiments/fixtures/thop_demo/partitions.json"));
        Files.writeString(partitions, original);
        String[] options = {"--thop-index-dir", temporary.resolve("index").toString(),
                "--thop-partitions", partitions.toString(), "--thop-domain-start", "0",
                "--thop-domain-end", "1440", "--thop-lifecycle", "build"};
        var before = parse("thop-partitioned", options);
        String beforeHash = edu.ipcmax.experiments.framework.ProfileSupport.sha256(
                QueryManifestIO.mapper().writeValueAsString(before.normalized()));
        Files.writeString(partitions, original.replace("\"3\":1", "\"3\":0"));
        var after = parse("thop-partitioned", options);
        String afterHash = edu.ipcmax.experiments.framework.ProfileSupport.sha256(
                QueryManifestIO.mapper().writeValueAsString(after.normalized()));
        assertNotEquals(beforeHash, afterHash);
        assertThrows(IllegalArgumentException.class, () ->
                new edu.ipcmax.experiments.algorithms.ThopPartitionedExperimentAlgorithm().prepare(
                        ExperimentDatasets.demo(), before.algorithmConfig()));
    }

    private static BenchOptions parse(String algorithm, String... extra) {
        var arguments = new ArrayList<>(List.of("--algorithm", algorithm, "--dataset", "demo",
                "--query-file", "experiments/manifests/tiny.jsonl", "--output-jsonl", "target/objective-test.jsonl"));
        arguments.addAll(List.of(extra));
        return BenchOptions.parse(arguments.toArray(String[]::new));
    }
}
