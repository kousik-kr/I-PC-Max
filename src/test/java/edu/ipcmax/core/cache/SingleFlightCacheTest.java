package edu.ipcmax.core.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class SingleFlightCacheTest {
    @Test
    void concurrentLookupsComputeOnceAndRecordTheWaiter() throws Exception {
        SingleFlightCache<String, Integer> cache =
                new SingleFlightCache<>();
        AtomicInteger computations = new AtomicInteger();
        CountDownLatch computing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> cache.getOrCompute(
                    "key",
                    () -> {
                        computations.incrementAndGet();
                        computing.countDown();
                        await(release);
                        return 42;
                    }));
            assertTrue(computing.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> cache.getOrCompute(
                    "key",
                    () -> {
                        computations.incrementAndGet();
                        return -1;
                    }));
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    Duration.ofSeconds(5),
                    () -> {
                        while (cache.waits() == 0) {
                            Thread.onSpinWait();
                        }
                    });
            release.countDown();

            assertEquals(42, first.get(5, TimeUnit.SECONDS));
            assertEquals(42, second.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, computations.get());
        assertEquals(2, cache.lookups());
        assertEquals(1, cache.hits());
        assertEquals(1, cache.misses());
        assertEquals(1, cache.waits());
        assertEquals(1, cache.size());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("single-flight test timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
