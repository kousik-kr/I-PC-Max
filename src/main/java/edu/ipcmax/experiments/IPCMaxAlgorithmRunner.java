package edu.ipcmax.experiments;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.IPCMax;
import edu.ipcmax.core.pcmax.IPCMaxOptions;
import edu.ipcmax.core.pcmax.IPCMaxResult;
import edu.ipcmax.core.pcmax.QuerySpec;

/**
 * Experiment adapter for I-PC-Max exact mode.
 */
public final class IPCMaxAlgorithmRunner implements AlgorithmRunner {
    private final IPCMax algorithm;

    /**
     * Creates the adapter.
     */
    public IPCMaxAlgorithmRunner(TDGraph graph, IPCMaxOptions options) {
        this.algorithm = new IPCMax(graph, options);
    }

    @Override
    public String label() {
        return "i-pc-max-exact";
    }

    @Override
    public IPCMaxResult run(QuerySpec query) {
        return algorithm.run(query);
    }
}
