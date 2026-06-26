package edu.ipcmax.core.labeling;

import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.validate.Path;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Point-deadline latest-departure labeling over incoming edges.
 */
public final class PointBackwardLabeling {
    private final TDGraph graph;

    /**
     * Creates a point backward labeler.
     */
    public PointBackwardLabeling(TDGraph graph) {
        this.graph = graph;
    }

    /**
     * Computes the latest departure from each node that can still reach destination by the deadline.
     */
    public Result run(int destination, double arrivalDeadline) {
        Map<Integer, Double> latestDeparture = new HashMap<>();
        Map<Integer, Integer> nextArc = new HashMap<>();
        PriorityQueue<Label> queue = new PriorityQueue<>();

        latestDeparture.put(destination, arrivalDeadline);
        queue.add(new Label(destination, arrivalDeadline));

        while (!queue.isEmpty()) {
            Label label = queue.poll();
            if (label.latestDeparture < latestDeparture.getOrDefault(label.node, Double.NEGATIVE_INFINITY)) {
                continue;
            }
            for (Edge edge : graph.incomingEdges(label.node)) {
                double candidate = edge.travelTimeFunction().latestDepartureForArrival(label.latestDeparture);
                if (candidate == Double.NEGATIVE_INFINITY) {
                    continue;
                }
                double best = latestDeparture.getOrDefault(edge.source(), Double.NEGATIVE_INFINITY);
                if (candidate > best || (candidate == best && edge.arcId() < nextArc.getOrDefault(edge.source(), Integer.MAX_VALUE))) {
                    latestDeparture.put(edge.source(), candidate);
                    nextArc.put(edge.source(), edge.arcId());
                    queue.add(new Label(edge.source(), candidate));
                }
            }
        }

        return new Result(destination, latestDeparture, nextArc, graph);
    }

    private record Label(int node, double latestDeparture) implements Comparable<Label> {
        @Override
        public int compareTo(Label other) {
            int timeCompare = Double.compare(other.latestDeparture, latestDeparture);
            if (timeCompare != 0) {
                return timeCompare;
            }
            return Integer.compare(node, other.node);
        }
    }

    /**
     * Latest-departure labels and suffix path witnesses.
     */
    public static final class Result {
        private final int destination;
        private final Map<Integer, Double> latestDeparture;
        private final Map<Integer, Integer> nextArc;
        private final TDGraph graph;

        private Result(int destination, Map<Integer, Double> latestDeparture, Map<Integer, Integer> nextArc, TDGraph graph) {
            this.destination = destination;
            this.latestDeparture = Map.copyOf(latestDeparture);
            this.nextArc = Map.copyOf(nextArc);
            this.graph = graph;
        }

        /**
         * Latest feasible departure from a node, or negative infinity if unreachable.
         */
        public double latestDepartureAt(int node) {
            return latestDeparture.getOrDefault(node, Double.NEGATIVE_INFINITY);
        }

        /**
         * True when the node can reach destination by the deadline.
         */
        public boolean reached(int node) {
            return latestDeparture.containsKey(node);
        }

        /**
         * Reconstructs a suffix path from node to destination.
         */
        public Path pathFrom(int node) {
            if (node == destination) {
                return Path.empty();
            }
            if (!latestDeparture.containsKey(node)) {
                throw new IllegalArgumentException("node cannot reach destination by deadline: " + node);
            }
            List<Integer> arcs = new ArrayList<>();
            int current = node;
            while (current != destination) {
                Integer arcId = nextArc.get(current);
                if (arcId == null) {
                    throw new IllegalStateException("missing suffix arc for node " + current);
                }
                arcs.add(arcId);
                current = graph.edges().get(arcId).target();
            }
            return new Path(arcs);
        }
    }
}
