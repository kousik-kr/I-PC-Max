package edu.ipcmax.core.loader;

/**
 * Minimal manifest fields needed by loaders and smoke checks.
 */
public record ManifestSummary(
        int numNodes,
        int numArcs,
        long seed,
        int selectedScoreEdgeCount,
        boolean unlistedEdgesHaveScoreZero) {
}
