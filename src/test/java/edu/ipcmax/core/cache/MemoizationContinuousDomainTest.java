package edu.ipcmax.core.cache;

import edu.ipcmax.core.function.Domain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoizationContinuousDomainTest {
    @Test
    void distinctContinuousDomainsDoNotCollide() {
        LabelingCache<String> cache = new LabelingCache<>();
        MemoKey first = new MemoKey(1, 2, Domain.closed(0, 5), "dep", "dead", 1, true, 1, false, 0);
        MemoKey second = new MemoKey(1, 2, Domain.closed(0, 5.5), "dep", "dead", 1, true, 1, false, 0);

        cache.put(first, "value");

        assertTrue(cache.get(second).isEmpty());
        assertTrue(cache.get(first).isPresent());
    }
}