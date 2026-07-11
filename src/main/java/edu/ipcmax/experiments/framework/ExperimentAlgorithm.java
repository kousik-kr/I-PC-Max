package edu.ipcmax.experiments.framework;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.QuerySpec;

/** One common benchmark interface for all candidate methods. */
public interface ExperimentAlgorithm {
    String id();

    AlgorithmResult run(
            TDGraph graph,
            QuerySpec query,
            AlgorithmConfig config,
            ExperimentInstrumentation instrumentation);
}
