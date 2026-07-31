package edu.ipcmax.core.pcmax;

import java.util.BitSet;

import edu.ipcmax.core.function.Domain;

/**
 * Query-scoped forward/suffix connector-label facade.
 *
 * <p>Static forward and reverse lower-bound labels are retained once for the
 * corridor. Exact temporal alternatives are generated online by
 * {@link BoundedConnectorGenerator} and reused through its bounded
 * single-flight caches. A destination suffix is always evaluated in the
 * original forward temporal direction; this class never treats a
 * time-dependent edge as an ordinary reversed edge.</p>
 */
final class QueryScopedConnectorLabelStore {
    private final BoundedConnectorGenerator connectors;
    private final QueryLowerBounds.Distances fromSource;
    private final QueryLowerBounds.Distances toDestination;
    private final PaceExecutionMetrics metrics;

    QueryScopedConnectorLabelStore(
            BoundedConnectorGenerator connectors,
            QueryLowerBounds.Distances fromSource,
            QueryLowerBounds.Distances toDestination,
            PaceExecutionMetrics metrics) {
        this.connectors = java.util.Objects.requireNonNull(
                connectors, "connectors");
        this.fromSource = java.util.Objects.requireNonNull(
                fromSource, "fromSource");
        this.toDestination = java.util.Objects.requireNonNull(
                toDestination, "toDestination");
        this.metrics = java.util.Objects.requireNonNull(
                metrics, "metrics");
        metrics.observeCounter(
                "forward_lower_bound_labels",
                fromSource.size());
        metrics.observeCounter(
                "backward_lower_bound_labels",
                toDestination.size());
    }

    QueryLowerBounds.Distances fromSource() {
        return fromSource;
    }

    QueryLowerBounds.Distances toDestination() {
        return toDestination;
    }

    ConnectorResult prefixLabels(
            int source,
            int target,
            Domain entryDomain,
            BitSet visitedVertices,
            BitSet visitedEdges,
            double residualBudget,
            String workItem) {
        metrics.increment("forward_connector_label_joins");
        return connectors.connect(
                source,
                target,
                entryDomain,
                visitedVertices,
                visitedEdges,
                residualBudget,
                workItem);
    }

    ConnectorResult suffixLabels(
            int source,
            int destination,
            Domain entryDomain,
            BitSet visitedVertices,
            BitSet visitedEdges,
            double residualBudget,
            String workItem) {
        metrics.increment("backward_suffix_label_joins");
        return connectors.connect(
                source,
                destination,
                entryDomain,
                visitedVertices,
                visitedEdges,
                residualBudget,
                workItem);
    }
}
