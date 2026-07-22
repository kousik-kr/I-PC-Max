package edu.ipcmax.core.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import edu.ipcmax.core.validate.Path;

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
        Map<Integer, NodeState> states = new HashMap<>();
        PriorityQueue<Label> queue = new PriorityQueue<>();
        states.put(start, new NodeState(0.0, 0, -1));
        queue.add(new Label(start, 0.0, 0));

        while (!queue.isEmpty()) {
            Label label = queue.poll();
            NodeState best = states.get(label.node);
            if (label.distance > best.distance
                    || (label.distance == best.distance && label.edgeCount > best.edgeCount)) {
                continue;
            }
            Iterable<Edge> edges = forward ? graph.outgoingEdges(label.node) : graph.incomingEdges(label.node);
            for (Edge edge : edges) {
                int next = forward ? edge.target() : edge.source();
                double candidate = label.distance + weightsByArcId[edge.arcId()];
                int candidateEdgeCount = label.edgeCount + 1;
                NodeState current = states.get(next);
                boolean labelImproves = current == null
                        || candidate < current.distance
                        || (candidate == current.distance && candidateEdgeCount < current.edgeCount);
                boolean witnessImproves = current != null
                        && candidate == current.distance
                        && candidateEdgeCount == current.edgeCount
                        && edge.arcId() < current.witnessArc;
                if (labelImproves) {
                    states.put(next, new NodeState(candidate, candidateEdgeCount, edge.arcId()));
                    queue.add(new Label(next, candidate, candidateEdgeCount));
                } else if (witnessImproves) {
                    // The outgoing distance/hop label is unchanged, so only its path witness changes.
                    states.put(next, new NodeState(candidate, candidateEdgeCount, edge.arcId()));
                }
            }
        }

        return new Distances(start, forward, states, graph);
    }

    private record NodeState(double distance, int edgeCount, int witnessArc) {
    }

    private record Label(int node, double distance, int edgeCount) implements Comparable<Label> {
        @Override
        public int compareTo(Label other) {
            int distanceCompare = Double.compare(distance, other.distance);
            if (distanceCompare != 0) {
                return distanceCompare;
            }
            int edgeCountCompare = Integer.compare(edgeCount, other.edgeCount);
            if (edgeCountCompare != 0) {
                return edgeCountCompare;
            }
            return Integer.compare(node, other.node);
        }
    }

    /**
     * Immutable lower-bound distances and stable path witnesses.
     */
    public static final class Distances {
        private final int start;
        private final boolean forward;
        private final Map<Integer, NodeState> states;
        private final TDGraph graph;

        private Distances(
                int start,
                boolean forward,
                Map<Integer, NodeState> states,
                TDGraph graph) {
            this.start = start;
            this.forward = forward;
            this.states = Collections.unmodifiableMap(states);
            this.graph = graph;
        }

        /**
         * Distance for a node, or positive infinity if unreachable.
         */
        public double distance(int node) {
            NodeState state = states.get(node);
            return state == null ? Double.POSITIVE_INFINITY : state.distance;
        }

        /**
         * True when a node was reached by Dijkstra.
         */
        public boolean reached(int node) {
            return states.containsKey(node);
        }

        /**
         * Edge count of the selected lower-bound path, or -1 if unreachable.
         */
        public int edgeCount(int node) {
            NodeState state = states.get(node);
            return state == null ? -1 : state.edgeCount;
        }

        /**
         * Reconstructs a forward lower-bound path to a reached target.
         */
        public Path pathTo(int target) {
            if (!forward) {
                throw new IllegalStateException("pathTo is only available for forward distances");
            }
            requireReached(target);
            if (target == start) {
                return Path.empty();
            }
            List<Integer> arcs = new ArrayList<>(edgeCount(target));
            int current = target;
            while (current != start) {
                int arcId = requiredWitness(current);
                arcs.add(arcId);
                current = graph.edges().get(arcId).source();
            }
            Collections.reverse(arcs);
            return new Path(arcs);
        }

        /**
         * Reconstructs a reverse-search lower-bound path from a reached source.
         */
        public Path pathFrom(int source) {
            if (forward) {
                throw new IllegalStateException("pathFrom is only available for reverse distances");
            }
            requireReached(source);
            if (source == start) {
                return Path.empty();
            }
            List<Integer> arcs = new ArrayList<>(edgeCount(source));
            int current = source;
            while (current != start) {
                int arcId = requiredWitness(current);
                arcs.add(arcId);
                current = graph.edges().get(arcId).target();
            }
            return new Path(arcs);
        }

        private void requireReached(int node) {
            if (!reached(node)) {
                throw new IllegalArgumentException("node is unreachable in lower-bound graph: " + node);
            }
        }

        private int requiredWitness(int node) {
            NodeState state = states.get(node);
            if (state == null || state.witnessArc < 0) {
                throw new IllegalStateException("missing lower-bound path witness for node " + node);
            }
            return state.witnessArc;
        }
    }
}
