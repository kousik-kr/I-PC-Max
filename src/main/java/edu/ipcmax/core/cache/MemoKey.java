package edu.ipcmax.core.cache;

/**
 * Deterministic memoization key for interval labeling and candidate recursion.
 */
public record MemoKey(
        int source,
        int destination,
        String domainFingerprint,
        String departureProfileFingerprint,
        String deadlineProfileFingerprint,
        int theta,
        boolean exactMode,
        int pivotStep,
        boolean mergeBreakpoints,
        int k) {
}
