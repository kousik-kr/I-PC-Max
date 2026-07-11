package edu.ipcmax.experiments.framework;

import java.util.Map;

import edu.ipcmax.core.pcmax.QuerySpec;

/** Canonical schema-version-1 query manifest row. */
public record QueryManifestEntry(
        int schemaVersion,
        String queryId,
        String datasetId,
        int source,
        int destination,
        int intervalStart,
        int intervalEnd,
        int windowLength,
        double budget,
        Double budgetSlack,
        String budgetPolicy,
        Integer distanceBin,
        Double lowerBoundDistance,
        long querySeed,
        Map<String, Object> metadata) {
    public QueryManifestEntry {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public void validate() {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("unsupported query schema_version: " + schemaVersion);
        }
        if (queryId == null || queryId.isBlank()) {
            throw new IllegalArgumentException("query_id is required");
        }
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("dataset_id is required for query " + queryId);
        }
        if (windowLength != intervalEnd - intervalStart) {
            throw new IllegalArgumentException("window_length mismatch for query " + queryId);
        }
        new QuerySpec(source, destination, intervalStart, intervalEnd, budget, 1);
    }

    public QuerySpec toQuerySpec() {
        return new QuerySpec(source, destination, intervalStart, intervalEnd, budget, 1);
    }
}
