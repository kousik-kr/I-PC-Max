package edu.ipcmax.core.pcmax;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.EdgeTemporalSummaryStore;

/**
 * Query-horizon lower travel-time weights and admissible static distances.
 */
public final class QueryLowerBounds {
    private final TDGraph graph;
    private final double[] weights;
    private final EdgeTemporalSummaryStore summaries;
    private final Map<Integer, Distances> forward = new ConcurrentHashMap<>();
    private final Map<Integer, Distances> reverse = new ConcurrentHashMap<>();
    private final Map<RadiusKey, Distances> truncatedForward =
            new ConcurrentHashMap<>();
    private final Map<RadiusKey, Distances> truncatedReverse =
            new ConcurrentHashMap<>();

    /** Builds {@code inf T_q tau_e} weights for every edge. */
    public QueryLowerBounds(TDGraph graph, Domain queryHorizon) {
        this.graph = graph;
        this.weights = new double[graph.edgeCount()];
        this.summaries = null;
        for (Edge edge : graph.edges()) {
            double weight = PaceProfiles.minimumTravelTime(edge, queryHorizon);
            if (!Double.isFinite(weight) || weight < 0) {
                throw new IllegalArgumentException("edge lower travel time must be finite and nonnegative: arc " + edge.arcId());
            }
            weights[edge.arcId()] = weight;
        }
    }

    /**
     * Reuses the prepared all-support edge minima. These weights can be
     * looser than a query-horizon minimum but remain admissible and avoid an
     * all-edge scan during corridor construction.
     */
    public QueryLowerBounds(
            TDGraph graph,
            EdgeTemporalSummaryStore summaries) {
        this.graph = java.util.Objects.requireNonNull(graph, "graph");
        this.summaries =
                java.util.Objects.requireNonNull(summaries, "summaries");
        this.weights = null;
        if (summaries.size() != graph.edgeCount()) {
            throw new IllegalArgumentException(
                    "edge summary count does not match graph");
        }
    }

    /** Query-horizon lower travel time for one stable arc id. */
    public double edgeWeight(int arcId) {
        return summaries == null
                ? weights[arcId]
                : summaries.summary(arcId).lowerBoundTravelTime();
    }

    /** Full-graph admissible lower distance. */
    public double distance(int source, int destination) {
        return distancesFrom(source).distance(destination);
    }

    /** Full-graph admissible distances from a source. */
    public Distances distancesFrom(int source) {
        return forward.computeIfAbsent(source, node -> dijkstra(node, true, Set.of()));
    }

    /** Full-graph admissible reverse distances to a destination. */
    public Distances distancesTo(int destination) {
        return reverse.computeIfAbsent(destination, node -> dijkstra(node, false, Set.of()));
    }

    /** Distances to a destination in a graph view excluding the supplied arc ids. */
    public Distances distancesTo(int destination, Set<Integer> excludedArcIds) {
        if (excludedArcIds.isEmpty()) {
            return distancesTo(destination);
        }
        return dijkstra(destination, false, excludedArcIds);
    }

    /**
     * Budget-truncated admissible distances from a source.
     *
     * <p>This is the corridor construction path: only labels no greater than
     * the query budget are settled, so corridor assembly can iterate active
     * vertices instead of scanning every graph edge.</p>
     */
    public Distances truncatedDistancesFrom(int source, double maximumDistance) {
        RadiusKey key = RadiusKey.of(source, maximumDistance);
        return truncatedForward.computeIfAbsent(
                key,
                ignored -> dijkstra(
                        source,
                        true,
                        Set.of(),
                        key.maximumDistance()));
    }

    /** Budget-truncated admissible reverse distances to a destination. */
    public Distances truncatedDistancesTo(
            int destination,
            double maximumDistance) {
        RadiusKey key = RadiusKey.of(destination, maximumDistance);
        return truncatedReverse.computeIfAbsent(
                key,
                ignored -> dijkstra(
                        destination,
                        false,
                        Set.of(),
                        key.maximumDistance()));
    }

    /** Admissible source-target distance settled only within the radius. */
    public double distanceWithin(
            int source,
            int destination,
            double maximumDistance) {
        return truncatedDistancesFrom(
                source, maximumDistance).distance(destination);
    }

    private Distances dijkstra(int start, boolean outgoing, Set<Integer> excludedArcIds) {
        return dijkstra(
                start,
                outgoing,
                excludedArcIds,
                Double.POSITIVE_INFINITY);
    }

    private Distances dijkstra(
            int start,
            boolean outgoing,
            Set<Integer> excludedArcIds,
            double maximumDistance) {
        if (Double.isNaN(maximumDistance) || maximumDistance < 0) {
            throw new IllegalArgumentException(
                    "maximum lower-bound distance must be nonnegative");
        }
        Map<Integer, Double> distance = new HashMap<>();
        PriorityQueue<Label> queue = new PriorityQueue<>();
        double canonicalMaximum = Double.isFinite(maximumDistance)
                ? Domain.canonicalTime(maximumDistance)
                : Double.POSITIVE_INFINITY;
        distance.put(start, 0.0);
        queue.add(new Label(start, 0.0));
        while (!queue.isEmpty()) {
            Label current = queue.poll();
            if (current.distance() > distance.getOrDefault(current.node(), Double.POSITIVE_INFINITY)) {
                continue;
            }
            if (current.distance() > canonicalMaximum) {
                break;
            }
            Iterable<Edge> adjacent = outgoing
                    ? graph.outgoingEdges(current.node())
                    : graph.incomingEdges(current.node());
            for (Edge edge : adjacent) {
                if (excludedArcIds.contains(edge.arcId())) {
                    continue;
                }
                int next = outgoing ? edge.target() : edge.source();
                double candidate = Domain.canonicalTime(
                        current.distance() + edgeWeight(edge.arcId()));
                if (candidate > canonicalMaximum) {
                    continue;
                }
                if (candidate < distance.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    distance.put(next, candidate);
                    queue.add(new Label(next, candidate));
                }
            }
        }
        return new Distances(distance);
    }

    private record Label(int node, double distance) implements Comparable<Label> {
        @Override
        public int compareTo(Label other) {
            int byDistance = Double.compare(distance, other.distance);
            return byDistance != 0 ? byDistance : Integer.compare(node, other.node);
        }
    }

    private record RadiusKey(int node, double maximumDistance) {
        static RadiusKey of(int node, double maximumDistance) {
            if (!Double.isFinite(maximumDistance)
                    || maximumDistance < 0) {
                throw new IllegalArgumentException(
                        "truncated distance radius must be finite and nonnegative");
            }
            return new RadiusKey(
                    node,
                    Domain.canonicalTime(maximumDistance));
        }
    }

    /** Immutable distance lookup. */
    public static final class Distances {
        private final Map<Integer, Double> values;

        private Distances(Map<Integer, Double> values) {
            this.values = Map.copyOf(values);
        }

        /** Distance or positive infinity when unreachable. */
        public double distance(int node) {
            return values.getOrDefault(node, Double.POSITIVE_INFINITY);
        }

        /** True when a finite label was settled within the requested radius. */
        public boolean reached(int node) {
            return values.containsKey(node);
        }

        /** Stable ascending active-vertex IDs. */
        public List<Integer> reachedNodes() {
            return values.keySet().stream().sorted().toList();
        }

        /** Number of active labels retained by this search. */
        public int size() {
            return values.size();
        }
    }
}
