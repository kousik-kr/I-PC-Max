package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.graph.TDGraph;

/**
 * Exact repeated-PC-Max interval baseline.
 */
public final class RepeatedPCMaxBaseline {
    private final TDGraph graph;
    private final IPCMaxOptions options;

    /**
     * Creates the baseline.
     */
    public RepeatedPCMaxBaseline(TDGraph graph, IPCMaxOptions options) {
        this.graph = graph;
        this.options = options;
    }

    /**
     * Runs point PC-Max for every discrete departure time and selects the best validated result.
     */
    public IPCMaxResult solve(QuerySpec query) {
        PointPCMaxRunner point = new PointPCMaxRunner(graph, options);
        IPCMaxResult best = IPCMaxResult.notFound("no feasible point PC-Max result");
        for (int departure : query.departureDomain()) {
            if (!query.isOnGrid(departure)) {
                continue;
            }
            IPCMaxResult result = point.run(query.source(), query.destination(), departure, query.maxTravelTime());
            if (ResultComparator.INSTANCE.compare(result, best) < 0) {
                best = result;
            }
        }
        return best;
    }
}
