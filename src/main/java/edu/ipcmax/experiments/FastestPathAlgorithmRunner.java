package edu.ipcmax.experiments;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.IPCMaxResult;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.pcmax.RepeatedFastestPathBaseline;

/**
 * Experiment adapter for the repeated fastest-path baseline.
 */
public final class FastestPathAlgorithmRunner implements AlgorithmRunner {
    private final RepeatedFastestPathBaseline baseline;

    /**
     * Creates the adapter.
     */
    public FastestPathAlgorithmRunner(TDGraph graph) {
        this.baseline = new RepeatedFastestPathBaseline(graph);
    }

    @Override
    public String label() {
        return "td-fastest";
    }

    @Override
    public IPCMaxResult run(QuerySpec query) {
        return baseline.solve(query);
    }
}
