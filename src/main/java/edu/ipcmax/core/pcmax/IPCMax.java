package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.validate.ExactPathValidator;

/**
 * Public I-PC-Max runner.
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
     * Runs the exact interval query and returns the legacy best point result.
     */
    public IPCMaxResult run(QuerySpec query) {
        EnvelopeProfile profile = runProfile(query);
        return profile.bestResult(new ExactPathValidator(graph), query.source(), query.destination(), query.maxTravelTime());
    }

    /**
     * Runs PACE and returns the departure-time-to-path envelope.
     */
    public EnvelopeProfile runProfile(QuerySpec query) {
        if (!options.exactMode()) {
            throw new IllegalStateException("PACE bounded/scalable mode is not implemented; exactMode=false is unavailable");
        }
        CandidateSet frontier = new PaceFrontierGenerator(graph)
                .generateFrontier(query.source(), query.destination(), query.departureDomain(), query.maxTravelTime(), options.theta());
        return EnvelopeExtractor.extract(frontier, query.departureDomain());
    }

    /**
     * Conservative run stats for the exact delegate.
     */
    public IPCMaxStats stats() {
        return IPCMaxStats.empty();
    }
}
