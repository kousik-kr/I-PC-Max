package edu.ipcmax.core.pcmax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class IPCMaxParallelExecutorTest {
    @Test
    void reducesInCanonicalOrderAndTerminatesWorkers() {
        IPCMaxParallelExecutor executor =
                new IPCMaxParallelExecutor(2);
        List<Integer> result = executor.invokeAllDeterministic(List.of(
                () -> 3,
                () -> 1,
                () -> 2));
        executor.close();

        assertEquals(List.of(3, 1, 2), result);
        assertTrue(executor.isTerminated());
    }

    @Test
    void failureCancelsSiblingAndDoesNotLeakThePool() {
        IPCMaxParallelExecutor executor =
                new IPCMaxParallelExecutor(2);
        CountDownLatch siblingStarted = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        assertThrows(IllegalStateException.class, () ->
                executor.invokeAllDeterministic(List.of(
                        () -> {
                            assertTrue(siblingStarted.await(
                                    5, TimeUnit.SECONDS));
                            throw new IllegalArgumentException("boom");
                        },
                        () -> {
                            siblingStarted.countDown();
                            try {
                                new CountDownLatch(1).await(
                                        30, TimeUnit.SECONDS);
                            } catch (InterruptedException expected) {
                                interrupted.set(true);
                                Thread.currentThread().interrupt();
                            }
                            return 2;
                        })));
        executor.close();

        assertTrue(interrupted.get());
        assertTrue(executor.isTerminated());
    }
}
