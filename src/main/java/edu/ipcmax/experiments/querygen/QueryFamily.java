package edu.ipcmax.experiments.querygen;

import java.util.Locale;

/** A query-family grouping for one pair, distance bin, and temporal regime. */
public record QueryFamily(
        String familyId,
        QueryPairCandidate pair,
        DistanceBin distanceBin,
        TemporalRegime temporalRegime) {
    public QueryFamily {
        familyId = normalizeFamilyId(familyId);
        if (pair == null || distanceBin == null || temporalRegime == null) {
            throw new IllegalArgumentException("pair, distance bin, and temporal regime are required");
        }
    }

    /** Stable ID shared by every query derived from the source/destination pair. */
    public String pairFamilyId() {
        return pair.pairFamilyId();
    }

    /** Stable ID shared by all variants in this family cell. */
    public String queryFamilyId() {
        return familyId + "-" + distanceBin.idToken() + "-" + temporalRegime.idToken()
                + "-" + pairFamilyId();
    }

    /** Stable per-query ID; the ordinal distinguishes deterministic derived variants. */
    public String queryId(int variantOrdinal) {
        if (variantOrdinal < 0) {
            throw new IllegalArgumentException("query variant ordinal cannot be negative");
        }
        return queryFamilyId() + "-v" + String.format(Locale.ROOT, "%03d", variantOrdinal);
    }

    static String formatPairFamilyId(String datasetId, int source, int destination) {
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("dataset id is required");
        }
        if (source <= 0 || destination <= 0) {
            throw new IllegalArgumentException("pair endpoints must be positive node ids");
        }
        String dataset = datasetId.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (!dataset.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("dataset id cannot be formatted safely: " + datasetId);
        }
        return dataset + "-s" + source + "-d" + destination;
    }

    private static String normalizeFamilyId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("query family id is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
        if (!normalized.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("query family id cannot be formatted safely: " + value);
        }
        return normalized;
    }
}
