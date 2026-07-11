package edu.ipcmax.core.pcmax;

/** Feature switches used by controlled PACE-B ablations. */
public record PaceFeatures(
        boolean safeDominanceEnabled,
        boolean perCellRetentionEnabled,
        boolean representativeRetentionEnabled,
        boolean anchorLowerBoundFilterEnabled,
        boolean compressionEnabled,
        boolean adjacentMergeEnabled) {
    /** Finalized PACE behavior. */
    public static PaceFeatures defaults() {
        return new PaceFeatures(true, true, true, true, true, true);
    }
}
