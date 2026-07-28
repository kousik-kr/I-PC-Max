package edu.ipcmax.experiments.framework;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.QuerySpec;

/** One common benchmark interface for all candidate methods. */
public interface ExperimentAlgorithm {
    String id();

    /**
     * Builds immutable dataset-wide state outside measured query time.
     * Algorithms without preprocessing keep the default no-op.
     */
    default void prepare(
            TDGraph graph,
            AlgorithmConfig config) {
    }

    AlgorithmResult run(
            TDGraph graph,
            QuerySpec query,
            AlgorithmConfig config,
            ExperimentInstrumentation instrumentation);
}
