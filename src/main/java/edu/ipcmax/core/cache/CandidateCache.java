package edu.ipcmax.core.cache;

import edu.ipcmax.core.profile.CandidateSet;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Candidate-set cache with hit/miss counters.
 */
public final class CandidateCache {
    private final Map<MemoKey, CandidateSet> values = new HashMap<>();
    private long hits;
    private long misses;

    /**
     * Retrieves a cached candidate set.
     */
    public Optional<CandidateSet> get(MemoKey key) {
        if (values.containsKey(key)) {
            hits++;
            return Optional.of(values.get(key));
        }
        misses++;
        return Optional.empty();
    }

    /**
     * Stores a candidate set.
     */
    public void put(MemoKey key, CandidateSet value) {
        CandidateSet copy = new CandidateSet();
        copy.addAll(value);
        values.put(key, copy);
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
