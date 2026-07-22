package edu.ipcmax.experiments.framework;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.experiments.querygen.DistanceBin;
import edu.ipcmax.experiments.querygen.TemporalRegime;

/** Canonical versioned query-manifest row. */
@JsonPropertyOrder({
        "schemaVersion", "queryId", "queryFamilyId", "pairFamilyId", "datasetId",
        "datasetPath", "graphChecksum", "source", "destination", "distanceBin",
        "temporalRegime", "intervalStart", "intervalEnd", "windowLength", "budget",
        "budgetSlack", "budgetPolicy", "lowerBoundDistance", "lowerBoundEdgeCount",
        "corridorAnchorCount", "fastestTravelTimeMin", "fastestTravelTimeMax",
        "expectedFullIntervalFeasible", "expectedMixedFeasibility", "querySeed",
        "generatorVersion", "generatorConfigHash", "metadata"
})
public record QueryManifestEntry(
        int schemaVersion,
        String queryId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String queryFamilyId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String pairFamilyId,
        String datasetId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String datasetPath,
        @JsonInclude(JsonInclude.Include.NON_NULL) String graphChecksum,
        int source,
        int destination,
        Object distanceBin,
        @JsonInclude(JsonInclude.Include.NON_NULL) TemporalRegime temporalRegime,
        int intervalStart,
        int intervalEnd,
        int windowLength,
        double budget,
        Double budgetSlack,
        String budgetPolicy,
        Double lowerBoundDistance,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer lowerBoundEdgeCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer corridorAnchorCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double fastestTravelTimeMin,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double fastestTravelTimeMax,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean expectedFullIntervalFeasible,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean expectedMixedFeasibility,
        long querySeed,
        @JsonInclude(JsonInclude.Include.NON_NULL) String generatorVersion,
        @JsonInclude(JsonInclude.Include.NON_NULL) String generatorConfigHash,
        Map<String, Object> metadata) {
    public QueryManifestEntry {
        if (schemaVersion == 2 && distanceBin instanceof String text) {
            distanceBin = DistanceBin.parse(text);
        }
        metadata = immutableMetadata(metadata);
    }

    /** Source-compatible schema-version-1 constructor. */
    public QueryManifestEntry(
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
        this(schemaVersion, queryId, null, null, datasetId, null, null, source, destination,
                distanceBin, null, intervalStart, intervalEnd, windowLength, budget, budgetSlack,
                budgetPolicy, lowerBoundDistance, null, null, null, null, null, null, querySeed,
                null, null, metadata);
    }

    /** Typed factory for schema-version-2 rows. */
    public static QueryManifestEntry version2(
            String queryId,
            String queryFamilyId,
            String pairFamilyId,
            String datasetId,
            String datasetPath,
            String graphChecksum,
            int source,
            int destination,
            DistanceBin distanceBin,
            TemporalRegime temporalRegime,
            int intervalStart,
            int intervalEnd,
            int windowLength,
            double budget,
            double budgetSlack,
            String budgetPolicy,
            double lowerBoundDistance,
            int lowerBoundEdgeCount,
            int corridorAnchorCount,
            double fastestTravelTimeMin,
            double fastestTravelTimeMax,
            boolean expectedFullIntervalFeasible,
            boolean expectedMixedFeasibility,
            long querySeed,
            String generatorVersion,
            String generatorConfigHash,
            Map<String, Object> metadata) {
        return new QueryManifestEntry(
                2, queryId, queryFamilyId, pairFamilyId, datasetId, datasetPath, graphChecksum,
                source, destination, distanceBin, temporalRegime, intervalStart, intervalEnd,
                windowLength, budget, budgetSlack, budgetPolicy, lowerBoundDistance,
                lowerBoundEdgeCount, corridorAnchorCount, fastestTravelTimeMin,
                fastestTravelTimeMax, expectedFullIntervalFeasible, expectedMixedFeasibility,
                querySeed, generatorVersion, generatorConfigHash, metadata);
    }

    /** Applies schema-specific and routing-query validation. */
    public void validate() {
        requireText(queryId, "query_id");
        requireText(datasetId, "dataset_id");
        if (windowLength != intervalEnd - intervalStart) {
            throw new IllegalArgumentException("window_length mismatch for query " + queryId);
        }
        new QuerySpec(source, destination, intervalStart, intervalEnd, budget, 1);
        switch (schemaVersion) {
            case 1 -> validateVersion1();
            case 2 -> validateVersion2();
            default -> throw new IllegalArgumentException(
                    "unsupported query schema_version: " + schemaVersion);
        }
    }

    /** Strongly typed version-2 distance bin. */
    public DistanceBin distanceBinValue() {
        if (distanceBin instanceof DistanceBin value) {
            return value;
        }
        if (distanceBin instanceof String value) {
            return DistanceBin.parse(value);
        }
        throw new IllegalArgumentException("query does not contain a version-2 distance bin: " + queryId);
    }

    /** Numeric version-1 distance bin, or null when the legacy row did not define one. */
    public Integer legacyDistanceBin() {
        if (distanceBin == null) {
            return null;
        }
        if (distanceBin instanceof Number value
                && Double.isFinite(value.doubleValue())
                && value.doubleValue() == Math.rint(value.doubleValue())
                && value.longValue() >= Integer.MIN_VALUE
                && value.longValue() <= Integer.MAX_VALUE) {
            return value.intValue();
        }
        throw new IllegalStateException("query does not contain a version-1 distance bin: " + queryId);
    }

    public QuerySpec toQuerySpec() {
        return new QuerySpec(source, destination, intervalStart, intervalEnd, budget, 1);
    }

    private void validateVersion1() {
        if (distanceBin != null) {
            legacyDistanceBin();
        }
        if (!"full-interval-feasible".equals(budgetPolicy) && !"tight".equals(budgetPolicy)) {
            throw new IllegalArgumentException(
                    "budget_policy must be full-interval-feasible or tight for query " + queryId);
        }
    }

    private void validateVersion2() {
        requireText(queryFamilyId, "query_family_id");
        requireText(pairFamilyId, "pair_family_id");
        requireText(datasetPath, "dataset_path");
        requireText(graphChecksum, "graph_checksum");
        requireText(generatorVersion, "generator_version");
        requireText(generatorConfigHash, "generator_config_hash");
        distanceBinValue();
        if (temporalRegime == null) {
            throw new IllegalArgumentException("temporal_regime is required for query " + queryId);
        }
        finiteNonnegative(budgetSlack, "budget_slack");
        if (!"FULL_INTERVAL_FEASIBLE".equals(budgetPolicy) && !"TIGHT".equals(budgetPolicy)) {
            throw new IllegalArgumentException(
                    "budget_policy must be FULL_INTERVAL_FEASIBLE or TIGHT for query " + queryId);
        }
        finiteNonnegative(lowerBoundDistance, "lower_bound_distance");
        nonnegative(lowerBoundEdgeCount, "lower_bound_edge_count");
        nonnegative(corridorAnchorCount, "corridor_anchor_count");
        finiteNonnegative(fastestTravelTimeMin, "fastest_travel_time_min");
        finiteNonnegative(fastestTravelTimeMax, "fastest_travel_time_max");
        if (fastestTravelTimeMin > fastestTravelTimeMax) {
            throw new IllegalArgumentException("fastest travel-time bounds are reversed for query " + queryId);
        }
        if (expectedFullIntervalFeasible == null || expectedMixedFeasibility == null) {
            throw new IllegalArgumentException("expected feasibility flags are required for query " + queryId);
        }
    }

    private static Map<String, Object> immutableMetadata(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        TreeMap<String, Object> sorted = new TreeMap<>();
        values.forEach((key, value) -> {
            if (key == null) {
                throw new IllegalArgumentException("metadata keys cannot be null");
            }
            sorted.put(key, value);
        });
        return Collections.unmodifiableMap(sorted);
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required for query " + queryId);
        }
    }

    private void finiteNonnegative(Double value, String field) {
        if (value == null || !Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(field + " must be finite and nonnegative for query " + queryId);
        }
    }

    private void nonnegative(Integer value, String field) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException(field + " must be nonnegative for query " + queryId);
        }
    }
}
