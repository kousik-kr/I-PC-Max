package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.graph.TDGraph;

/**
 * Public I-PC-Max runner.
 *
 * <p>Exact mode currently delegates to repeated exact point PC-Max. This preserves exact validation and
 * deterministic output while the interval-recursive optimizer APIs are present for further optimization.</p>
 */
public final class IPCMax {
    private final TDGraph graph;
    private final IPCMaxOptions options;

    /**
     * Creates an I-PC-Max runner.
     */
    public IPCMax(TDGraph graph, IPCMaxOptions options) {
        this.graph = graph;
        this.options = options;
    }

    /**
     * Runs the exact interval query.
     */
    public IPCMaxResult run(QuerySpec query) {
        return new RepeatedPCMaxBaseline(graph, options).solve(query);
    }

    /**
     * Conservative run stats for the exact delegate.
     */
    public IPCMaxStats stats() {
        return IPCMaxStats.empty();
    }
}
