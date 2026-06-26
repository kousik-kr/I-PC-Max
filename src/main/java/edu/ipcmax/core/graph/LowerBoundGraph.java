package edu.ipcmax.core.graph;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Static graph using each edge's minimum possible travel time as an admissible lower bound.
 */
public final class LowerBoundGraph {
    private final TDGraph graph;
    private final double[] weightsByArcId;

    /**
     * Creates lower-bound weights from a time-dependent graph.
     */
    public LowerBoundGraph(TDGraph graph) {
        this.graph = graph;
        this.weightsByArcId = new double[graph.edgeCount()];
        for (Edge edge : graph.edges()) {
            weightsByArcId[edge.arcId()] = edge.travelTimeFunction().minTravelTime();
        }
    }

    /**
     * Lower-bound edge weight.
     */
    public double weight(int arcId) {
        return weightsByArcId[arcId];
    }

    /**
     * Dijkstra distances from a source over outgoing edges.
     */
    public Distances distancesFromSource(int source) {
        return dijkstra(source, true);
    }

    /**
     * Reverse Dijkstra distances to a target over incoming edges.
     */
    public Distances distancesToTarget(int target) {
        return dijkstra(target, false);
    }

    private Distances dijkstra(int start, boolean forward) {
        Map<Integer, Double> distance = new HashMap<>();
        PriorityQueue<Label> queue = new PriorityQueue<>();
        distance.put(start, 0.0);
        queue.add(new Label(start, 0.0));

        while (!queue.isEmpty()) {
            Label label = queue.poll();
            if (label.distance > distance.getOrDefault(label.node, Double.POSITIVE_INFINITY)) {
                continue;
            }
            Iterable<Edge> edges = forward ? graph.outgoingEdges(label.node) : graph.incomingEdges(label.node);
            for (Edge edge : edges) {
                int next = forward ? edge.target() : edge.source();
                double candidate = label.distance + weightsByArcId[edge.arcId()];
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
            int distanceCompare = Double.compare(distance, other.distance);
            if (distanceCompare != 0) {
                return distanceCompare;
            }
            return Integer.compare(node, other.node);
        }
    }

    /**
     * Immutable lower-bound distance map.
     */
    public static final class Distances {
        private final Map<Integer, Double> distance;

        private Distances(Map<Integer, Double> distance) {
            this.distance = Map.copyOf(distance);
        }

        /**
         * Distance for a node, or positive infinity if unreachable.
         */
        public double distance(int node) {
            return distance.getOrDefault(node, Double.POSITIVE_INFINITY);
        }

        /**
         * True when a node was reached by Dijkstra.
         */
        public boolean reached(int node) {
            return distance.containsKey(node);
        }
    }
}
