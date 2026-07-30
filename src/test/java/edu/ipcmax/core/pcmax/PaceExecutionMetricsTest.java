package edu.ipcmax.core.pcmax;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class PaceExecutionMetricsTest {
    @Test
    void openPhaseAppearsInPeriodicCumulativeWallTime()
            throws Exception {
        AtomicReference<PaceExecutionMetrics.Snapshot> latest =
                new AtomicReference<>();
        CountDownLatch published =
                new CountDownLatch(2);
        PaceExecutionMetrics metrics =
                PaceExecutionMetrics.live(snapshot -> {
                    latest.set(snapshot);
                    published.countDown();
                });
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.CONNECTOR_GENERATION)) {
            assertTrue(published.await(
                    3, TimeUnit.SECONDS));
            PaceExecutionMetrics.Snapshot snapshot =
                    latest.get();
            assertTrue(snapshot.elapsedNanos()
                    >= 800_000_000L);
            assertTrue(snapshot.timings().getOrDefault(
                    PaceExecutionMetrics.CONNECTOR_GENERATION,
                    0L) >= 800_000_000L);
        } finally {
            metrics.close();
        }
    }
}
