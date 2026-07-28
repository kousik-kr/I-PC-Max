package edu.ipcmax.experiments.querygen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import edu.ipcmax.experiments.framework.QueryManifestEntry;
import edu.ipcmax.experiments.framework.QueryManifestIO;

class PaperQuerySetGeneratorTest {
    private static final String CONTRACT =
            "declared_centisecond_normalization-v1";

    @TempDir
    Path temporary;

    @Test
    void tinyDiskFixtureIsDeterministicDisjointAndHorizonSafe()
            throws Exception {
        Path dataset = temporary.resolve("dataset");
        Path configuration = temporary.resolve("query-generation.yaml");
        writeDataset(dataset);
        writeConfiguration(configuration);
        PaperQuerySetGenerator.GenerationSpec spec =
                spec(dataset, configuration);

        PaperQuerySetGenerator.GenerationResult first =
                PaperQuerySetGenerator.generate(spec);
        PaperQuerySetGenerator.GenerationResult second =
                PaperQuerySetGenerator.generate(spec);
        Path firstPath = temporary.resolve("first.jsonl");
        Path secondPath = temporary.resolve("second.jsonl");
        QueryManifestIO.write(firstPath, first.rows());
        QueryManifestIO.write(secondPath, second.rows());

        assertArrayEquals(
                Files.readAllBytes(firstPath),
                Files.readAllBytes(secondPath));
        assertEquals(first.rows(), second.rows());
        assertEquals(30, first.rows().size());
        assertEquals(
                Map.of("evaluation", 10, "pilot", 10, "warmup", 10),
                first.summary().rowsBySplit());
        assertEquals(15, first.summary().basePairCount());

        Map<String, Set<String>> pairIds = new HashMap<>();
        Map<String, Set<String>> bands = new HashMap<>();
        Set<String> queryIds = new HashSet<>();
        for (QueryManifestEntry row : QueryManifestIO.read(firstPath)) {
            Map<String, Object> metadata = row.metadata();
            String split = String.valueOf(metadata.get("split"));
            pairIds.computeIfAbsent(split, ignored -> new HashSet<>())
                    .add(String.valueOf(metadata.get("pair_id")));
            bands.computeIfAbsent(split, ignored -> new HashSet<>())
                    .add(String.valueOf(metadata.get("distance_band")));
            assertTrue(queryIds.add(row.queryId()));
            assertTrue(row.source() != row.destination());
            assertEquals(1, ((Number) metadata.get(
                    "delta_minutes")).intValue());
            assertTrue(row.intervalEnd() + row.budget()
                    <= ((Number) metadata.get(
                            "function_support_end")).doubleValue());
            double expected = canonical(
                    (1.0 + ((Number) metadata.get("rho")).doubleValue())
                            * ((Number) metadata.get(
                                    "t_hat_min_delta")).doubleValue());
            assertEquals(expected, canonical(row.budget()));
        }
        assertEquals(5, pairIds.get("pilot").size());
        assertEquals(5, pairIds.get("warmup").size());
        assertEquals(5, pairIds.get("evaluation").size());
        assertTrue(disjoint(pairIds.get("pilot"), pairIds.get("warmup")));
        assertTrue(disjoint(pairIds.get("pilot"), pairIds.get("evaluation")));
        assertTrue(disjoint(pairIds.get("warmup"), pairIds.get("evaluation")));
        for (String split : List.of("pilot", "warmup", "evaluation")) {
            assertEquals(Set.of("B1", "B2", "B3", "B4", "B5"),
                    bands.get(split));
        }
    }

    @Test
    void rejectsDatasetChecksumMismatchBeforeSampling() throws Exception {
        Path dataset = temporary.resolve("dataset-bad-checksum");
        Path configuration = temporary.resolve("query-generation.yaml");
        writeDataset(dataset);
        writeConfiguration(configuration);
        Path manifest = dataset.resolve("manifest.json");
        String text = Files.readString(manifest);
        Files.writeString(
                manifest,
                text.replaceFirst(
                        "\"dataset_checksum\":\"[0-9a-f]{64}\"",
                        "\"dataset_checksum\":\"" + "0".repeat(64) + "\""),
                StandardCharsets.UTF_8);

        IOException failure = assertThrows(
                IOException.class,
                () -> PaperQuerySetGenerator.generate(
                        spec(dataset, configuration)));

        assertTrue(failure.getMessage().contains(
                "dataset_checksum"));
    }

    private PaperQuerySetGenerator.GenerationSpec spec(
            Path dataset,
            Path configuration) {
        return new PaperQuerySetGenerator.GenerationSpec(
                2,
                "paper-q1-query-generation-v1",
                CONTRACT,
                "NY",
                dataset,
                configuration,
                "1".repeat(64),
                42,
                Map.of(
                        "pilot", 4201L,
                        "warmup", 4202L,
                        "evaluation", 4203L),
                5,
                5,
                5,
                5,
                List.of(510, 1110),
                List.of(120),
                List.of(0.1),
                120,
                0.1,
                1,
                "GRID_FIXED_DEPARTURE_FASTEST_TRAVEL_TIME",
                10080,
                List.of());
    }

    private void writeDataset(Path directory) throws IOException {
        Files.createDirectories(directory);
        StringBuilder nodes = new StringBuilder("node_id,x,y\n");
        for (int node = 1; node <= 20; node++) {
            nodes.append(node).append(',').append(node)
                    .append(',').append(node).append('\n');
        }
        StringBuilder edges = new StringBuilder(
                "arc_id,u,v,distance,base_travel_time\n");
        StringBuilder travel = new StringBuilder();
        StringBuilder scores = new StringBuilder();
        int arcId = 0;
        int selected = 0;
        for (int source = 1; source <= 20; source++) {
            for (int destination = 1; destination <= 20; destination++) {
                if (source == destination) {
                    continue;
                }
                int time = 1 + Math.abs(source - destination) % 3;
                edges.append(arcId).append(',').append(source).append(',')
                        .append(destination).append(',').append(time)
                        .append(',').append(time).append('\n');
                travel.append("{\"arc_id\":").append(arcId)
                        .append(",\"u\":").append(source)
                        .append(",\"v\":").append(destination)
                        .append(",\"distance\":").append(time)
                        .append(",\"base_travel_time\":").append(time)
                        .append(",\"travel_time_breakpoints\":[[0,")
                        .append(time).append("],[10080,")
                        .append(time).append("]]}\n");
                if (arcId % 10 == 0) {
                    scores.append("{\"arc_id\":").append(arcId)
                            .append(",\"u\":").append(source)
                            .append(",\"v\":").append(destination)
                            .append(",\"selected_for_score\":true,")
                            .append("\"score_intervals\":[[0,10080,")
                            .append(1 + arcId % 7).append("]]}\n");
                    selected++;
                }
                arcId++;
            }
        }
        writeGzip(directory.resolve("nodes.csv.gz"), nodes.toString());
        writeGzip(directory.resolve("edges_static.csv.gz"), edges.toString());
        writeGzip(
                directory.resolve("travel_time_functions.jsonl.gz"),
                travel.toString());
        writeGzip(
                directory.resolve("score_functions.jsonl.gz"),
                scores.toString());
        String structural = ManifestChecksum.datasetChecksum(directory);
        String temporal =
                ManifestChecksum.temporalAttributeChecksum(directory);
        Files.writeString(
                directory.resolve("manifest.json"),
                "{\"schema_version\":3,"
                        + "\"num_nodes\":20,\"num_arcs\":" + arcId + ","
                        + "\"seed\":42,"
                        + "\"selected_score_edge_count\":" + selected + ","
                        + "\"unlisted_edges_have_score_zero\":true,"
                        + "\"conversion_contract\":{\"contract_id\":\""
                        + CONTRACT + "\"},"
                        + "\"temporal_support\":{\"start\":0,\"end\":10080},"
                        + "\"dataset_checksum\":\"" + structural + "\","
                        + "\"temporal_attribute_checksum\":\"" + temporal
                        + "\"}\n",
                StandardCharsets.UTF_8);
    }

    private void writeConfiguration(Path path) throws IOException {
        Files.writeString(path, """
                schema_version: 1
                seed: 42
                candidate_pool:
                  sampled_sources: 10
                  maximum_pairs: 100
                  minimum_lower_bound_edges: 1
                  minimum_distance: 1
                  require_reachable: true
                  require_anchor_corridor: false
                main:
                  pairs_per_distance_bin: 1
                  window_minutes: 120
                  budget_slack: 0.1
                  budget_policy: FULL_INTERVAL_FEASIBLE
                pilot:
                  pairs_per_distance_bin: 1
                  disjoint_from_main: true
                sensitivity:
                  pairs_per_distance_bin: 1
                  select_from_main: true
                appendix:
                  pairs_per_distance_bin: 1
                  select_from_main: true
                parallelism:
                  distance_bins: [Q1]
                  temporal_regimes: [MORNING_PEAK]
                  pairs_per_cell: 1
                tight_budget:
                  slack: 0.05
                  derive_from_main: true
                window_sensitivity:
                  values_minutes: [120]
                  derive_from_sensitivity: true
                budget_sensitivity:
                  slack_values: [0.1]
                  derive_from_sensitivity: true
                temporal_regimes:
                  MORNING_PEAK:
                    preferred_start: 420
                  DAY_OFFPEAK:
                    preferred_start: 720
                  EVENING_PEAK:
                    preferred_start: 1020
                  LATE_OFFPEAK:
                    preferred_start: 60
                """, StandardCharsets.UTF_8);
    }

    private static void writeGzip(Path path, String content)
            throws IOException {
        try (GZIPOutputStream gzip = new GZIPOutputStream(
                     Files.newOutputStream(path));
             OutputStreamWriter writer = new OutputStreamWriter(
                     gzip, StandardCharsets.UTF_8)) {
            writer.write(content);
        }
    }

    private static boolean disjoint(Set<String> first, Set<String> second) {
        Set<String> intersection = new HashSet<>(first);
        intersection.retainAll(second);
        return intersection.isEmpty();
    }

    private static double canonical(double value) {
        return Math.rint(value * 1_000_000_000_000.0)
                / 1_000_000_000_000.0;
    }
}
