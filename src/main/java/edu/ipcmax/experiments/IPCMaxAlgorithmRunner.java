package edu.ipcmax.experiments;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.IPCMax;
import edu.ipcmax.core.pcmax.IPCMaxOptions;
import edu.ipcmax.core.pcmax.IPCMaxResult;
import edu.ipcmax.core.pcmax.QuerySpec;

/**
 * Legacy single-point experiment adapter backed by the PACE implementation.
 *
 * @deprecated use {@link PaceAlgorithmRunner} for full profile results
 */
@Deprecated
public final class IPCMaxAlgorithmRunner implements AlgorithmRunner {
    private final IPCMax algorithm;
    private final String label;

    /**
     * Creates the adapter.
     */
    public IPCMaxAlgorithmRunner(TDGraph graph, IPCMaxOptions options) {
        this.algorithm = new IPCMax(graph, options);
        this.label = options.exactMode() ? "pace-x-compat" : "pace-b-compat";
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public IPCMaxResult run(QuerySpec query) {
        return algorithm.run(query);
    }
}
