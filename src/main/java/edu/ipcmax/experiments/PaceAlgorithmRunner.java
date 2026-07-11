package edu.ipcmax.experiments;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.EnvelopeProfile;
import edu.ipcmax.core.pcmax.PACE;
import edu.ipcmax.core.pcmax.PaceExecutionPolicy;
import edu.ipcmax.core.pcmax.PaceOptions;
import edu.ipcmax.core.pcmax.QuerySpec;

/** Experiment adapter for either public PACE execution policy. */
public final class PaceAlgorithmRunner implements ProfileAlgorithmRunner {
    private final PACE pace;
    private final String label;

    /** Creates the adapter. */
    public PaceAlgorithmRunner(TDGraph graph, PaceOptions options) {
        this.pace = new PACE(graph, options);
        this.label = options.policy() == PaceExecutionPolicy.PACE_X ? "pace-x" : "pace-b";
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public EnvelopeProfile run(QuerySpec query) {
        return pace.run(query);
    }
}
