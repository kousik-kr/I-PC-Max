package edu.ipcmax.core.pcmax;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;

/**
 * Query-horizon lower travel-time weights and admissible static distances.
 */
public final class QueryLowerBounds {
    private final TDGraph graph;
    private final double[] weights;
    private final Map<Integer, Distances> forward = new ConcurrentHashMap<>();
    private final Map<Integer, Distances> reverse = new ConcurrentHashMap<>();

    /** Builds {@code inf T_q tau_e} weights for every edge. */
    public QueryLowerBounds(TDGraph graph, Domain queryHorizon) {
        this.graph = graph;
        this.weights = new double[graph.edgeCount()];
        for (Edge edge : graph.edges()) {
            double weight = PaceProfiles.minimumTravelTime(edge, queryHorizon);
            if (!Double.isFinite(weight) || weight < 0) {
                throw new IllegalArgumentException("edge lower travel time must be finite and nonnegative: arc " + edge.arcId());
            }
            weights[edge.arcId()] = weight;
        }
    }

    /** Query-horizon lower travel time for one stable arc id. */
    public double edgeWeight(int arcId) {
        return weights[arcId];
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

    private Distances dijkstra(int start, boolean outgoing, Set<Integer> excludedArcIds) {
        Map<Integer, Double> distance = new HashMap<>();
        PriorityQueue<Label> queue = new PriorityQueue<>();
        distance.put(start, 0.0);
        queue.add(new Label(start, 0.0));
        while (!queue.isEmpty()) {
            Label current = queue.poll();
            if (current.distance() > distance.getOrDefault(current.node(), Double.POSITIVE_INFINITY)) {
                continue;
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
                        current.distance() + weights[edge.arcId()]);
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
    }
}
