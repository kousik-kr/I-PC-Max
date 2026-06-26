package edu.ipcmax.core.labeling;

import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.validate.Path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Point-departure earliest-arrival labeling for FIFO time-dependent graphs.
 */
public final class PointForwardLabeling {
    private final TDGraph graph;

    /**
     * Creates a point forward labeler.
     */
    public PointForwardLabeling(TDGraph graph) {
        this.graph = graph;
    }

    /**
     * Computes earliest arrivals from a source at a fixed departure time.
     */
    public Result run(int source, int departureTime, double maxTravelTime) {
        Map<Integer, Double> arrival = new HashMap<>();
        Map<Integer, Integer> predecessorArc = new HashMap<>();
        PriorityQueue<Label> queue = new PriorityQueue<>();
        arrival.put(source, (double) departureTime);
        queue.add(new Label(source, departureTime));
        double deadline = departureTime + maxTravelTime;

        while (!queue.isEmpty()) {
            Label label = queue.poll();
            if (label.arrivalTime > arrival.getOrDefault(label.node, Double.POSITIVE_INFINITY)) {
                continue;
            }
            for (Edge edge : graph.outgoingEdges(label.node)) {
                double nextArrival;
                try {
                    nextArrival = edge.travelTimeFunction().arrivalTimeAt(label.arrivalTime);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                if (nextArrival > deadline) {
                    continue;
                }
                double best = arrival.getOrDefault(edge.target(), Double.POSITIVE_INFINITY);
                if (nextArrival < best || (nextArrival == best && edge.arcId() < predecessorArc.getOrDefault(edge.target(), Integer.MAX_VALUE))) {
                    arrival.put(edge.target(), nextArrival);
                    predecessorArc.put(edge.target(), edge.arcId());
                    queue.add(new Label(edge.target(), nextArrival));
                }
            }
        }

        return new Result(source, arrival, predecessorArc, graph);
    }

    private record Label(int node, double arrivalTime) implements Comparable<Label> {
        @Override
        public int compareTo(Label other) {
            int timeCompare = Double.compare(arrivalTime, other.arrivalTime);
            if (timeCompare != 0) {
                return timeCompare;
            }
            return Integer.compare(node, other.node);
        }
    }

    /**
     * Earliest-arrival labels and path witnesses.
     */
    public static final class Result {
        private final int source;
        private final Map<Integer, Double> arrival;
        private final Map<Integer, Integer> predecessorArc;
        private final TDGraph graph;

        private Result(int source, Map<Integer, Double> arrival, Map<Integer, Integer> predecessorArc, TDGraph graph) {
            this.source = source;
            this.arrival = Map.copyOf(arrival);
            this.predecessorArc = Map.copyOf(predecessorArc);
            this.graph = graph;
        }

        /**
         * Earliest known arrival at a node, or positive infinity if unreachable.
         */
        public double arrivalAt(int node) {
            return arrival.getOrDefault(node, Double.POSITIVE_INFINITY);
        }

        /**
         * True when the node was reached.
         */
        public boolean reached(int node) {
            return arrival.containsKey(node);
        }

        /**
         * Reconstructs the fastest path to a target.
         */
        public Path pathTo(int target) {
            if (target == source) {
                return Path.empty();
            }
            if (!arrival.containsKey(target)) {
                throw new IllegalArgumentException("target is unreachable: " + target);
            }
            List<Integer> arcs = new ArrayList<>();
            int current = target;
            while (current != source) {
                Integer arcId = predecessorArc.get(current);
                if (arcId == null) {
                    throw new IllegalStateException("missing predecessor for node " + current);
                }
                arcs.add(arcId);
                current = graph.edges().get(arcId).source();
            }
            Collections.reverse(arcs);
            return new Path(arcs);
        }
    }
}
