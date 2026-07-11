package edu.ipcmax.core.pcmax;

/** Runtime counters from the most recently completed PACE frontier generation. */
public record PaceGenerationStats(
        long recursionCalls,
        long anchorsConsidered,
        long anchorsRetained,
        long connectorCandidates,
        long stitchedCandidates,
        long cacheHits,
        long cacheMisses) {
    /** Zero-valued snapshot. */
    public static PaceGenerationStats empty() {
        return new PaceGenerationStats(0, 0, 0, 0, 0, 0, 0);
    }
}
