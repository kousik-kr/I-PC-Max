package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.graph.TDGraph;

/**
 * Backward-compatible facade for {@link PACE}.
 *
 * @deprecated use {@link PACE}; this class delegates to the same implementation
 */
@Deprecated
public final class IPCMax {
    private final PACE delegate;

    /**
     * Creates an I-PC-Max runner.
     */
    public IPCMax(TDGraph graph, IPCMaxOptions options) {
        this.delegate = new PACE(graph, options.toPaceOptions());
    }

    /**
     * Runs the exact interval query and returns the legacy best point result.
     */
    public IPCMaxResult run(QuerySpec query) {
        return delegate.bestPointResult(query);
    }

    /**
     * Runs PACE and returns the departure-time-to-path envelope.
     */
    public EnvelopeProfile runProfile(QuerySpec query) {
        return delegate.run(query);
    }

    /**
     * Legacy-shaped statistics populated from the PACE generator.
     */
    public IPCMaxStats stats() {
        PaceGenerationStats stats = delegate.stats();
        return new IPCMaxStats(
                stats.anchorsRetained(),
                0,
                stats.connectorCandidates() + stats.stitchedCandidates(),
                0,
                0,
                stats.cacheHits(),
                stats.cacheMisses(),
                stats.recursionCalls());
    }
}
