package edu.ipcmax.core.pcmax;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;

/**
 * Deterministic helper for parallel independent tasks.
 */
public final class IPCMaxParallelExecutor implements AutoCloseable {
    private final ForkJoinPool pool;

    /**
     * Creates an executor with the requested thread count.
     */
    public IPCMaxParallelExecutor(int threadCount) {
        if (threadCount < 1) {
            throw new IllegalArgumentException("thread count must be positive");
        }
        this.pool = new ForkJoinPool(threadCount);
    }

    /**
     * Executes tasks and returns results in the same order as input tasks.
     */
    public <T> List<T> invokeAllDeterministic(List<Callable<T>> tasks) {
        try {
            List<Future<T>> futures = pool.invokeAll(tasks);
            List<T> results = new ArrayList<>(futures.size());
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return List.copyOf(results);
        } catch (Exception ex) {
            throw new IllegalStateException("parallel task execution failed", ex);
        }
    }

    @Override
    public void close() {
        pool.shutdown();
    }
}
