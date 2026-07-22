package edu.ipcmax.experiments.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import edu.ipcmax.experiments.querygen.DistanceBin;
import edu.ipcmax.experiments.querygen.TemporalRegime;

class QueryManifestIOVersionTest {
    @TempDir
    Path temporary;

    @Test
    void serializesAndParsesSchemaVersion2() throws Exception {
        QueryManifestEntry expected = version2Entry();
        Path path = temporary.resolve("nested/queries.jsonl");

        QueryManifestIO.write(path, List.of(expected));

        String text = Files.readString(path);
        assertTrue(text.endsWith("\n"));
        assertFalse(text.contains("timestamp"));
        JsonNode json = QueryManifestIO.mapper().readTree(text);
        assertEquals(2, json.path("schema_version").asInt());
        assertEquals(28, json.size());
        for (String field : List.of(
                "schema_version", "query_id", "query_family_id", "pair_family_id", "dataset_id",
                "dataset_path", "graph_checksum", "source", "destination", "distance_bin",
                "temporal_regime", "interval_start", "interval_end", "window_length", "budget",
                "budget_slack", "budget_policy", "lower_bound_distance", "lower_bound_edge_count",
                "corridor_anchor_count", "fastest_travel_time_min", "fastest_travel_time_max",
                "expected_full_interval_feasible", "expected_mixed_feasibility", "query_seed",
                "generator_version", "generator_config_hash", "metadata")) {
            assertTrue(json.has(field), field);
        }
        assertEquals("main-q2-morning-peak-ny-s1-d2-v000", json.path("query_id").asText());
        assertEquals("Q2", json.path("distance_bin").asText());
        assertEquals("MORNING_PEAK", json.path("temporal_regime").asText());
        assertTrue(json.path("metadata").has("nullable"));
        assertTrue(json.path("metadata").path("nullable").isNull());

        List<QueryManifestEntry> actual = QueryManifestIO.read(path);
        assertEquals(List.of(expected), actual);
        assertEquals(DistanceBin.Q2, actual.get(0).distanceBinValue());
        assertEquals(TemporalRegime.MORNING_PEAK, actual.get(0).temporalRegime());
    }

    @Test
    void readsExistingSchemaVersion1Rows() throws Exception {
        List<QueryManifestEntry> entries = QueryManifestIO.read(Path.of("experiments/manifests/tiny.jsonl"));

        assertEquals(3, entries.size());
        QueryManifestEntry first = entries.get(0);
        assertEquals(1, first.schemaVersion());
        assertEquals(0, first.legacyDistanceBin());
        assertNull(first.queryFamilyId());
        assertNull(first.temporalRegime());
        assertNull(first.fastestTravelTimeMin());
    }

    @Test
    void version1SerializationPreservesRequiredNullsAndOmitsVersion2Fields() throws Exception {
        QueryManifestEntry legacy = new QueryManifestEntry(
                1, "legacy", "demo", 1, 2, 420, 425, 5,
                10.0, null, "tight", null, null, 7L, null);

        JsonNode json = QueryManifestIO.mapper().readTree(
                QueryManifestIO.mapper().writeValueAsString(legacy));

        assertTrue(json.has("budget_slack"));
        assertTrue(json.path("budget_slack").isNull());
        assertTrue(json.has("distance_bin"));
        assertTrue(json.path("distance_bin").isNull());
        assertTrue(json.has("lower_bound_distance"));
        assertFalse(json.has("query_family_id"));
        assertFalse(json.has("temporal_regime"));
        assertEquals(15, json.size());
        assertEquals(0, json.path("metadata").size());
    }

    @Test
    void rejectsNullVersion2FieldsAndDuplicateIds() {
        QueryManifestEntry valid = version2Entry();
        QueryManifestEntry missingFamily = new QueryManifestEntry(
                2, valid.queryId(), null, valid.pairFamilyId(), valid.datasetId(),
                valid.datasetPath(), valid.graphChecksum(), valid.source(), valid.destination(),
                valid.distanceBin(), valid.temporalRegime(), valid.intervalStart(), valid.intervalEnd(),
                valid.windowLength(), valid.budget(), valid.budgetSlack(), valid.budgetPolicy(),
                valid.lowerBoundDistance(), valid.lowerBoundEdgeCount(), valid.corridorAnchorCount(),
                valid.fastestTravelTimeMin(), valid.fastestTravelTimeMax(),
                valid.expectedFullIntervalFeasible(), valid.expectedMixedFeasibility(),
                valid.querySeed(), valid.generatorVersion(), valid.generatorConfigHash(), null);

        assertEquals(Map.of(), missingFamily.metadata());
        assertThrows(IllegalArgumentException.class, missingFamily::validate);
        assertThrows(IOException.class,
                () -> QueryManifestIO.write(temporary.resolve("duplicates.jsonl"), List.of(valid, valid)));
    }

    @Test
    void enforcesVersionSpecificFieldsAndRequiredPrimitiveValues() throws Exception {
        String version1 = Files.readAllLines(Path.of("experiments/manifests/tiny.jsonl")).get(0);
        Path extraField = temporary.resolve("v1-extra-field.jsonl");
        Files.writeString(extraField, version1.substring(0, version1.length() - 1)
                + ",\"generator_version\":\"not-v1\"}\n");
        Path nullSeed = temporary.resolve("v1-null-seed.jsonl");
        Files.writeString(nullSeed, version1.replace("\"query_seed\":1001", "\"query_seed\":null") + "\n");

        assertThrows(IOException.class, () -> QueryManifestIO.read(extraField));
        assertThrows(IOException.class, () -> QueryManifestIO.read(nullSeed));
    }

    private static QueryManifestEntry version2Entry() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("zeta", 2);
        metadata.put("nullable", null);
        metadata.put("alpha", 1);
        return QueryManifestEntry.version2(
                "main-q2-morning-peak-ny-s1-d2-v000",
                "main-q2-morning-peak-ny-s1-d2",
                "ny-s1-d2",
                "NY",
                "data/input/NY",
                "0123456789abcdef",
                1,
                2,
                DistanceBin.Q2,
                TemporalRegime.MORNING_PEAK,
                420,
                540,
                120,
                100.0,
                0.25,
                "FULL_INTERVAL_FEASIBLE",
                40.0,
                8,
                3,
                50.0,
                60.0,
                true,
                false,
                20260711L,
                "querygen-v1",
                "abcdef0123456789",
                metadata);
    }
}
