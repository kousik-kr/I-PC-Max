package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.validate.Path;

/** Exact no-wait replay of an ordered sequence of complete edge IDs. */
public final class TemporalPathReplay {
    private TemporalPathReplay() {
    }

    /**
     * Replays a path at an exact departure time, evaluating every edge at its
     * actual entry time. Undefined edge-function times and malformed paths
     * are rejected rather than extrapolated.
     */
    public static double arrivalTime(
            TDGraph graph,
            int source,
            int destination,
            double departureTime,
            Path path) {
        if (graph == null || path == null || !Double.isFinite(departureTime)) {
            throw new IllegalArgumentException("graph, path, and a finite departure time are required");
        }
        graph.node(source);
        graph.node(destination);

        double currentTime = Domain.canonicalTime(departureTime);
        int currentNode = source;
        for (int arcId : path.arcIds()) {
            if (arcId >= graph.edgeCount()) {
                throw new IllegalArgumentException("unknown arc id " + arcId);
            }
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != currentNode) {
                throw new IllegalArgumentException("path is disconnected at arc " + arcId);
            }
            if (!edge.travelTimeFunction().domain().contains(currentTime)) {
                throw new IllegalArgumentException(
                        "edge function domain excludes entry time " + currentTime + " at arc " + arcId);
            }
            currentTime = edge.travelTimeFunction().arrivalTimeAt(currentTime);
            currentNode = edge.target();
        }
        if (currentNode != destination) {
            throw new IllegalArgumentException("path does not end at destination");
        }
        return currentTime;
    }
}
