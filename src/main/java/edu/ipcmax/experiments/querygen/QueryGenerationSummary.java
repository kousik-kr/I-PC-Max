package edu.ipcmax.experiments.querygen;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic aggregate counters for one query-generation run. */
public record QueryGenerationSummary(
        long seed,
        int datasetsProcessed,
        long pairsExamined,
        long candidatesAccepted,
        long queriesGenerated,
        Map<String, Long> queriesByDataset,
        Map<String, Long> queriesByFamily) {
    public QueryGenerationSummary {
        if (datasetsProcessed < 0 || pairsExamined < 0 || candidatesAccepted < 0 || queriesGenerated < 0) {
            throw new IllegalArgumentException("query-generation summary counts cannot be negative");
        }
        queriesByDataset = immutableDatasetCounts(queriesByDataset);
        queriesByFamily = immutableFamilyCounts(queriesByFamily);
    }

    /** Empty summary for a run that has not sampled any graph pairs. */
    public static QueryGenerationSummary empty(long seed) {
        return new QueryGenerationSummary(seed, 0, 0, 0, 0, Map.of(), Map.of());
    }

    private static Map<String, Long> immutableDatasetCounts(Map<String, Long> values) {
        TreeMap<String, Long> result = new TreeMap<>();
        if (values != null) {
            values.forEach((key, value) -> putCount(result, key, value));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Long> immutableFamilyCounts(Map<String, Long> values) {
        TreeMap<String, Long> result = new TreeMap<>();
        if (values != null) {
            values.forEach((key, value) -> putCount(result, key, value));
        }
        return Collections.unmodifiableMap(result);
    }

    private static <K> void putCount(Map<K, Long> target, K key, Long value) {
        if (key == null || value == null || value < 0) {
            throw new IllegalArgumentException("summary keys and nonnegative counts are required");
        }
        target.put(key, value);
    }
}
