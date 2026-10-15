package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.TemporalProfileWork;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Function-valued forward or reverse-backward search used by incremental THop.
 * Reverse search traverses original incoming arcs and composes each arc's
 * arrival map before the already-computed suffix profile.
 */
final class PrunedFifoProfileSearch {
    enum Direction {
        FORWARD,
        BACKWARD
    }

    private PrunedFifoProfileSearch() {
    }

    static SearchResult run(
            TDGraph graph,
            int root,
            ExactTemporalProfile rootProfile,
            Direction direction,
            BiPredicate<Integer, ExactTemporalProfile> shouldExpand) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(rootProfile, "rootProfile");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(shouldExpand, "shouldExpand");
        graph.node(root);

        long started = System.nanoTime();
        long initialHeap = usedHeapBytes();
        long[] workCounts = {0, 0};
        Map<Integer, ExactTemporalProfile> best = new HashMap<>();
        best.put(root, rootProfile);
        PriorityQueue<QueueEntry> queue = new PriorityQueue<>(Comparator
                .comparingDouble(QueueEntry::minimumArrival)
                .thenComparingInt(QueueEntry::node));
        Set<Integer> queued = new HashSet<>();
        queue.add(new QueueEntry(root, rootProfile.minimumValue()));
        queued.add(root);

        long expanded = 0;
        long generated = 1;
        long skipped = 0;
        long sampledPeak = initialHeap;
        try (TemporalProfileWork.Scope ignored = TemporalProfileWork.install((name, amount) -> {
            if (name.equals("thop_temporal_compositions")) {
                workCounts[0] += amount;
            } else if (name.equals("thop_envelope_operations")) {
                workCounts[1] += amount;
            }
        })) {
            while (!queue.isEmpty()) {
                QueueEntry entry = queue.poll();
                queued.remove(entry.node());
                ExactTemporalProfile prefixOrSuffix = best.get(entry.node());
                expanded++;
                if (!shouldExpand.test(entry.node(), prefixOrSuffix)) {
                    skipped++;
                    continue;
                }

                var edges = direction == Direction.FORWARD
                        ? graph.outgoingEdges(entry.node())
                        : graph.incomingEdges(entry.node());
                for (Edge edge : edges) {
                    ExactTemporalProfile edgeArrival = ExactTemporalProfile.edgeArrival(edge);
                    int adjacent = direction == Direction.FORWARD ? edge.target() : edge.source();
                    ExactTemporalProfile candidate = direction == Direction.FORWARD
                            ? prefixOrSuffix.composeAfter(edgeArrival)
                            : edgeArrival.composeAfter(prefixOrSuffix);
                    if (candidate.isEmpty()) {
                        continue;
                    }
                    ExactTemporalProfile current = best.get(adjacent);
                    ExactTemporalProfile merged = current == null
                            ? candidate
                            : current.pointwiseMinimum(candidate);
                    if (current != null && current.sameFunction(merged)) {
                        continue;
                    }
                    best.put(adjacent, merged);
                    generated++;
                    if (queued.add(adjacent)) {
                        queue.add(new QueueEntry(adjacent, merged.minimumValue()));
                    }
                    sampledPeak = Math.max(sampledPeak, usedHeapBytes());
                }
                sampledPeak = Math.max(sampledPeak, usedHeapBytes());
            }
        }

        return new SearchResult(
                Map.copyOf(best),
                new SearchStatistics(
                        System.nanoTime() - started,
                        expanded,
                        generated,
                        skipped,
                        workCounts[0],
                        workCounts[1],
                        sampledPeak));
    }

    static Domain terminalArrivalDomain(TDGraph graph) {
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (Edge edge : graph.edges()) {
            for (var cell : ExactTemporalProfile.edgeArrival(edge).cells()) {
                for (var point : cell.breakpoints()) {
                    minimum = Math.min(minimum, point.value());
                    maximum = Math.max(maximum, point.value());
                }
            }
        }
        return minimum == Double.POSITIVE_INFINITY
                ? Domain.closed(0, 0)
                : Domain.closed(minimum, maximum);
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private record QueueEntry(int node, double minimumArrival) {
    }

    record SearchStatistics(
            long elapsedNanos,
            long searchStatesExpanded,
            long searchStatesGenerated,
            long searchExpansionsSkipped,
            long temporalCompositions,
            long envelopeOperations,
            long sampledPeakUsedHeapBytes) {
    }

    record SearchResult(
            Map<Integer, ExactTemporalProfile> profiles,
            SearchStatistics statistics) {
    }
}
