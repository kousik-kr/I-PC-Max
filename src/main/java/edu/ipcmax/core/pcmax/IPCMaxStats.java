package edu.ipcmax.core.pcmax;

/**
 * Runtime counters for algorithm runs.
 */
public record IPCMaxStats(
        long feasibleEdgeProfiles,
        long pivotProfiles,
        long candidateProfiles,
        long labelingCallsF,
        long labelingCallsB,
        long cacheHits,
        long cacheMisses,
        long recursionCalls) {
    /**
     * Empty stats.
     */
    public static IPCMaxStats empty() {
        return new IPCMaxStats(0, 0, 0, 0, 0, 0, 0, 0);
    }
}
