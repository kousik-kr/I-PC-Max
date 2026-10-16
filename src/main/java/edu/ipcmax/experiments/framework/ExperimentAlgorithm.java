package edu.ipcmax.experiments.framework;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.QuerySpec;

/** One common benchmark interface for all candidate methods. */
public interface ExperimentAlgorithm extends AutoCloseable {
    String id();

    /**
     * Builds immutable dataset-wide state outside measured query time.
     * Algorithms without preprocessing keep the default no-op.
     */
    default void prepare(
            TDGraph graph,
            AlgorithmConfig config) {
    }

    /** Releases prepared state. Persistent on-disk indexes remain available for reuse. */
    @Override
    default void close() {
    }

    AlgorithmResult run(
            TDGraph graph,
            QuerySpec query,
            AlgorithmConfig config,
            ExperimentInstrumentation instrumentation);
}
