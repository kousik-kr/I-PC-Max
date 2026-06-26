package edu.ipcmax.experiments;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.IPCMaxOptions;
import edu.ipcmax.core.pcmax.IPCMaxResult;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.pcmax.RepeatedPCMaxBaseline;

/**
 * Experiment adapter for repeated point PC-Max.
 */
public final class RepeatedPCMaxAlgorithmRunner implements AlgorithmRunner {
    private final RepeatedPCMaxBaseline baseline;

    /**
     * Creates the adapter.
     */
    public RepeatedPCMaxAlgorithmRunner(TDGraph graph, IPCMaxOptions options) {
        this.baseline = new RepeatedPCMaxBaseline(graph, options);
    }

    @Override
    public String label() {
        return "repeated-pc-max";
    }

    @Override
    public IPCMaxResult run(QuerySpec query) {
        return baseline.solve(query);
    }
}
