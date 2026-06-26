package edu.ipcmax.experiments;

import edu.ipcmax.core.pcmax.IPCMaxResult;

/**
 * Paper-style metrics row.
 */
public record MetricsRow(
        String queryId,
        String dataset,
        String algorithm,
        String mode,
        int theta,
        int k,
        int pivotStep,
        int intervalWidth,
        double overhead,
        int score,
        double travelTime,
        double arrivalTime,
        int departureTime,
        long runtimeMs,
        long peakMemoryMb,
        long feasibleEdgeProfiles,
        long pivotProfiles,
        long candidateProfiles,
        long labelingCallsF,
        long labelingCallsB,
        long cacheHits,
        long cacheMisses,
        long breakpointsTotal,
        double compressionDeltaMax,
        boolean validated,
        boolean valid) {
    /**
     * Builds a metrics row from a result.
     */
    public static MetricsRow fromResult(String queryId, String dataset, String algorithm, String mode, int theta,
                                        int k, int pivotStep, int intervalWidth, double overhead,
                                        IPCMaxResult result, long runtimeMs) {
        return new MetricsRow(
                queryId,
                dataset,
                algorithm,
                mode,
                theta,
                k,
                pivotStep,
                intervalWidth,
                overhead,
                result.score(),
                result.travelTime(),
                result.arrivalTime(),
                result.departureTime(),
                runtimeMs,
                Runtime.getRuntime().totalMemory() / (1024 * 1024),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                result.found(),
                result.found());
    }
}
