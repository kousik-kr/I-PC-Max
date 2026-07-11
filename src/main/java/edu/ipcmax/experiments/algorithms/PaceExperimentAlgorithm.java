package edu.ipcmax.experiments.algorithms;

import java.util.Map;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.PACE;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.AlgorithmResult;
import edu.ipcmax.experiments.framework.ExperimentAlgorithm;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;

/** Adapter preserving the finalized shared PACE-X/PACE-B implementation. */
public final class PaceExperimentAlgorithm implements ExperimentAlgorithm {
    private final String id;

    public PaceExperimentAlgorithm(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public AlgorithmResult run(
            TDGraph graph, QuerySpec query, AlgorithmConfig config,
            ExperimentInstrumentation instrumentation) {
        PACE pace = new PACE(graph, config.paceOptions());
        var profile = pace.run(query);
        var stats = pace.stats();
        instrumentation.addCounter("recursive_calls", stats.recursionCalls());
        instrumentation.addCounter("anchors_examined", stats.anchorsConsidered());
        instrumentation.addCounter("anchors_retained", stats.anchorsRetained());
        instrumentation.addCounter("connector_paths_enumerated", stats.connectorCandidates());
        instrumentation.addCounter("stitch_successes", stats.stitchedCandidates());
        instrumentation.addCounter("memo_hits", stats.cacheHits());
        instrumentation.addCounter("memo_misses", stats.cacheMisses());
        boolean feasible = profile.segments().stream().anyMatch(segment -> segment.found());
        return new AlgorithmResult(
                feasible ? ExperimentStatus.COMPLETED : ExperimentStatus.NO_FEASIBLE_PATH,
                profile, id.equals("pace-x"), Map.of(), null, null);
    }
}
