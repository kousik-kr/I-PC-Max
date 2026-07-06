package edu.ipcmax.core.cache;

import edu.ipcmax.core.function.Domain;

/**
 * Deterministic memoization key for interval labeling and candidate recursion.
 */
public record MemoKey(
        int source,
        int destination,
        Domain domain,
        String departureProfileFingerprint,
        String deadlineProfileFingerprint,
        int theta,
        boolean exactMode,
        int pivotStep,
        boolean mergeBreakpoints,
        int k) {
}
