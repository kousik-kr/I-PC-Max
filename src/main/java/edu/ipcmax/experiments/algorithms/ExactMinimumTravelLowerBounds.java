package edu.ipcmax.experiments.algorithms;

import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Exact reverse shortest paths over rational per-edge minimum travel times. */
final class ExactMinimumTravelLowerBounds {
    record Result(Map<Integer, ExactTime> distanceToTarget, long edgesScanned, long edgeWeightsRead) { }
    private record QueueEntry(int node, ExactTime distance) { }

    private ExactMinimumTravelLowerBounds() { }

    static Result toTarget(TDGraph graph, int target, BooleanSupplier stopRequested) {
        long edgeWeightsRead = 0;
        Comparator<QueueEntry> order = Comparator.comparing(QueueEntry::distance)
                .thenComparingInt(QueueEntry::node);
        PriorityQueue<QueueEntry> queue = new PriorityQueue<>(order);
        Map<Integer, ExactTime> distances = new HashMap<>();
        distances.put(target, ExactTime.ZERO);
        queue.add(new QueueEntry(target, ExactTime.ZERO));
        long edgesScanned = 0;
        while (!queue.isEmpty()) {
            check(stopRequested);
            QueueEntry current = queue.remove();
            ExactTime known = distances.get(current.node());
            if (known == null || !known.equals(current.distance())) continue;
            for (Edge edge : graph.incomingEdges(current.node())) {
                check(stopRequested);
                edgesScanned++;
                ExactTime edgeMinimum = null;
                for (var knot : edge.travelTimeFunction().breakpoints()) {
                    check(stopRequested);
                    ExactTime value = ExactTime.fromCanonicalDouble(knot.value());
                    if (edgeMinimum == null || value.compareTo(edgeMinimum) < 0) edgeMinimum = value;
                }
                if (edgeMinimum == null || edgeMinimum.signum() < 0) {
                    throw new IllegalArgumentException("edge has no nonnegative travel-time minimum: " + edge.arcId());
                }
                edgeWeightsRead++;
                ExactTime candidate = current.distance().add(edgeMinimum);
                ExactTime previous = distances.get(edge.source());
                if (previous == null || candidate.compareTo(previous) < 0) {
                    distances.put(edge.source(), candidate);
                    queue.add(new QueueEntry(edge.source(), candidate));
                }
            }
        }
        return new Result(Map.copyOf(distances), edgesScanned, edgeWeightsRead);
    }

    private static void check(BooleanSupplier stopRequested) {
        if (stopRequested != null && stopRequested.getAsBoolean()) {
            throw new CancellationException("exact lower-bound preparation cancelled");
        }
    }
}
