package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.graph.TDGraph;

/**
 * Exact point PC-Max runner for a single departure time.
 *
 * <p>The current exact implementation enumerates loopless tiny paths up to the configured cap and validates
 * each path against original functions. This favors correctness over scalability.</p>
 */
public final class PointPCMaxRunner {
    private final TDGraph graph;
    private final IPCMaxOptions options;

    /**
     * Creates a point runner.
     */
    public PointPCMaxRunner(TDGraph graph, IPCMaxOptions options) {
        this.graph = graph;
        this.options = options;
    }

    /**
     * Runs exact point PC-Max semantics at one departure time.
     */
    public IPCMaxResult run(int source, int destination, int departureTime, double budget) {
        QuerySpec query = new QuerySpec(source, destination, departureTime, departureTime, budget, 1);
        return new TinyGraphBruteForceOracle(graph, options.maxPathLength()).solve(query);
    }
}
