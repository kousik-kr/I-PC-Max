package edu.ipcmax.experiments.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

class ResultRecordSchemaTest {
    @Test
    void schemaKeepsVersionOneStatusAndRequiresExactnessMetadataInVersionTwo() throws Exception {
        JsonNode schema = QueryManifestIO.mapper().readTree(
                Path.of("experiments/schemas/result_record.schema.json").toFile());

        assertEquals(2, schema.path("properties").path("schema_version").path("enum").size());
        assertTrue(contains(schema.path("properties").path("schema_version").path("enum"), "1"));
        assertTrue(contains(schema.path("properties").path("schema_version").path("enum"), "2"));

        JsonNode versionOneRequired = schema.path("$defs").path("statusV1").path("required");
        JsonNode versionTwoRequired = schema.path("$defs").path("statusV2").path("required");
        assertFalse(contains(versionOneRequired, "exactness_scope"));
        assertFalse(contains(versionOneRequired, "execution_policy"));
        assertTrue(contains(versionTwoRequired, "exactness_scope"));
        assertTrue(contains(versionTwoRequired, "execution_policy"));
    }

    private static boolean contains(JsonNode array, String value) {
        return StreamSupport.stream(array.spliterator(), false)
                .anyMatch(item -> item.asText().equals(value));
    }
}
