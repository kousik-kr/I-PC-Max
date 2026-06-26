package edu.ipcmax.core.pcmax;

/**
 * Execution options for PC-Max and I-PC-Max runners.
 */
public record IPCMaxOptions(
        int theta,
        int k,
        int maxPathLength,
        boolean exactMode,
        boolean scalablePivotMode,
        int pivotStep,
        boolean mergeBreakpoints,
        int mergeStep,
        int threadCount,
        long seed) {
    /**
     * Validated options.
     */
    public IPCMaxOptions {
        if (theta < 1) {
            throw new IllegalArgumentException("theta must be at least 1");
        }
        if (k < 0) {
            throw new IllegalArgumentException("K cannot be negative");
        }
        if (maxPathLength < 1) {
            throw new IllegalArgumentException("max path length must be at least 1");
        }
        if (pivotStep < 1) {
            throw new IllegalArgumentException("pivot step must be at least 1");
        }
        if (mergeStep < 1) {
            throw new IllegalArgumentException("merge step must be at least 1");
        }
        if (threadCount < 1) {
            throw new IllegalArgumentException("thread count must be at least 1");
        }
    }

    /**
     * Conservative exact defaults.
     */
    public static IPCMaxOptions defaults() {
        return new IPCMaxOptions(1, 0, 10, true, false, 1, false, 2, 1, 42);
    }
}
