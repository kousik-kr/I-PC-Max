package edu.ipcmax.core.cache;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic labeling cache with hit/miss counters.
 */
public final class LabelingCache<T> {
    private final Map<MemoKey, T> values = new HashMap<>();
    private long hits;
    private long misses;

    /**
     * Retrieves a value.
     */
    public Optional<T> get(MemoKey key) {
        if (values.containsKey(key)) {
            hits++;
            return Optional.of(values.get(key));
        }
        misses++;
        return Optional.empty();
    }

    /**
     * Stores a value.
     */
    public void put(MemoKey key, T value) {
        values.put(key, value);
    }

    /**
     * Cache hits.
     */
    public long hits() {
        return hits;
    }

    /**
     * Cache misses.
     */
    public long misses() {
        return misses;
    }
}
