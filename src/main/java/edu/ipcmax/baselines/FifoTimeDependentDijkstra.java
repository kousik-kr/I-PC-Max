package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.validate.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Exact FIFO time-dependent Dijkstra searches for the research baselines.
 *
 * <p>Labels are ordered by arrival (or latest departure), then fewer edges,
 * lexicographic complete edge-ID sequence, and node ID. Edges whose travel
 * function is undefined at their entry time are unavailable for that
 * relaxation. Reverse searches require closed edge-function domains so the
 * latest feasible time is attained and can be represented exactly.</p>
 */
public final class FifoTimeDependentDijkstra {
    private final TDGraph graph;

    /** Creates a search object and validates FIFO on every edge domain. */
    public FifoTimeDependentDijkstra(TDGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
        validateFifoGraph(graph);
    }

    /** Computes earliest arrivals from {@code source} at {@code departureTime}. */
    public ForwardResult earliestArrival(int source, double departureTime) {
        if (!Double.isFinite(departureTime)) {
            throw new IllegalArgumentException("departure time must be finite");
        }
        graph.node(source);
        double start = Domain.canonicalTime(departureTime);
        Map<Integer, ForwardLabel> best = new HashMap<>();
        PriorityQueue<ForwardLabel> queue = new PriorityQueue<>(FORWARD_ORDER);
        ForwardLabel initial = new ForwardLabel(source, start, List.of(), List.of(source));
        best.put(source, initial);
        queue.add(initial);

        while (!queue.isEmpty()) {
            ForwardLabel label = queue.poll();
            if (!label.equals(best.get(label.node()))) {
                continue;
            }
            for (Edge edge : graph.outgoingEdges(label.node())) {
                if (label.nodeSequence().contains(edge.target())) {
                    continue;
                }
                PiecewiseLinearFn travel = edge.travelTimeFunction();
                if (!travel.domain().contains(label.time())) {
                    continue;
                }
                double arrival = travel.arrivalTimeAt(label.time());
                ForwardLabel candidate = append(label, edge, arrival);
                ForwardLabel current = best.get(edge.target());
                if (current == null || FORWARD_BEST.compare(candidate, current) < 0) {
                    best.put(edge.target(), candidate);
                    queue.add(candidate);
                }
            }
        }
        return new ForwardResult(source, start, best);
    }

    /**
     * Computes the latest feasible departures to {@code destination} by an
     * arrival deadline. Every edge function must have a closed domain.
     */
    public BackwardResult latestDeparture(int destination, double arrivalDeadline) {
        if (!Double.isFinite(arrivalDeadline)) {
            throw new IllegalArgumentException("arrival deadline must be finite");
        }
        graph.node(destination);
        requireClosedDomains(graph);
        double deadline = Domain.canonicalTime(arrivalDeadline);
        Map<Integer, BackwardLabel> best = new HashMap<>();
        PriorityQueue<BackwardLabel> queue = new PriorityQueue<>(BACKWARD_ORDER);
        BackwardLabel initial = new BackwardLabel(destination, deadline, List.of(), List.of(destination));
        best.put(destination, initial);
        queue.add(initial);

        while (!queue.isEmpty()) {
            BackwardLabel label = queue.poll();
            if (!label.equals(best.get(label.node()))) {
                continue;
            }
            for (Edge edge : graph.incomingEdges(label.node())) {
                if (label.nodeSequence().contains(edge.source())) {
                    continue;
                }
                PiecewiseLinearFn travel = edge.travelTimeFunction();
                double departure = travel.latestDepartureForArrival(label.time());
                if (departure == Double.NEGATIVE_INFINITY
                        || !travel.domain().contains(departure)
                        || travel.arrivalTimeAt(departure) > label.time()) {
                    continue;
                }
                BackwardLabel candidate = prepend(label, edge, departure);
                BackwardLabel current = best.get(edge.source());
                if (current == null || BACKWARD_BEST.compare(candidate, current) < 0) {
                    best.put(edge.source(), candidate);
                    queue.add(candidate);
                }
            }
        }
        return new BackwardResult(destination, deadline, best);
    }

    private static ForwardLabel append(ForwardLabel prefix, Edge edge, double arrival) {
        List<Integer> arcs = new ArrayList<>(prefix.arcIds());
        arcs.add(edge.arcId());
        List<Integer> nodes = new ArrayList<>(prefix.nodeSequence());
        nodes.add(edge.target());
        return new ForwardLabel(edge.target(), arrival, List.copyOf(arcs), List.copyOf(nodes));
    }

    private static BackwardLabel prepend(BackwardLabel suffix, Edge edge, double departure) {
        List<Integer> arcs = new ArrayList<>(suffix.arcIds().size() + 1);
        arcs.add(edge.arcId());
        arcs.addAll(suffix.arcIds());
        List<Integer> nodes = new ArrayList<>(suffix.nodeSequence().size() + 1);
        nodes.add(edge.source());
        nodes.addAll(suffix.nodeSequence());
        return new BackwardLabel(edge.source(), departure, List.copyOf(arcs), List.copyOf(nodes));
    }

    private static void validateFifoGraph(TDGraph graph) {
        for (Edge edge : graph.edges()) {
            PiecewiseLinearFn function = edge.travelTimeFunction();
            function.requireFifo("arc_id " + edge.arcId());
            List<Domain.Interval> intervals = function.domain().intervals();
            for (int i = 1; i < intervals.size(); i++) {
                double priorLimit = function.arrivalTimeAtClosure(intervals.get(i - 1).end());
                double nextLimit = function.arrivalTimeAtClosure(intervals.get(i).start());
                if (priorLimit > nextLimit) {
                    throw new IllegalArgumentException(
                            "arc_id " + edge.arcId() + ": arrival function is non-FIFO across a domain gap");
                }
            }
        }
    }

    private static void requireClosedDomains(TDGraph graph) {
        for (Edge edge : graph.edges()) {
            for (Domain.Interval interval : edge.travelTimeFunction().domain().intervals()) {
                if (!interval.startInclusive() || !interval.endInclusive()) {
                    throw new IllegalArgumentException(
                            "latest-departure search requires closed function domains; arc_id "
                                    + edge.arcId() + " has an open endpoint");
                }
            }
        }
    }

    private static int compareArcIds(List<Integer> left, List<Integer> right) {
        int common = Math.min(left.size(), right.size());
        for (int i = 0; i < common; i++) {
            int comparison = Integer.compare(left.get(i), right.get(i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private static final Comparator<ForwardLabel> FORWARD_BEST = Comparator
            .comparingDouble(ForwardLabel::time)
            .thenComparingInt(label -> label.arcIds().size())
            .thenComparing(ForwardLabel::arcIds, FifoTimeDependentDijkstra::compareArcIds)
            .thenComparingInt(ForwardLabel::node);

    private static final Comparator<ForwardLabel> FORWARD_ORDER = FORWARD_BEST;

    private static final Comparator<BackwardLabel> BACKWARD_BEST = Comparator
            .comparingDouble(BackwardLabel::time).reversed()
            .thenComparingInt(label -> label.arcIds().size())
            .thenComparing(BackwardLabel::arcIds, FifoTimeDependentDijkstra::compareArcIds)
            .thenComparingInt(BackwardLabel::node);

    private static final Comparator<BackwardLabel> BACKWARD_ORDER = BACKWARD_BEST;

    private record ForwardLabel(int node, double time, List<Integer> arcIds, List<Integer> nodeSequence) {
    }

    private record BackwardLabel(int node, double time, List<Integer> arcIds, List<Integer> nodeSequence) {
    }

    /** Immutable forward labels and complete edge-ID witnesses. */
    public static final class ForwardResult {
        private final int source;
        private final double departureTime;
        private final Map<Integer, ForwardLabel> labels;

        private ForwardResult(int source, double departureTime, Map<Integer, ForwardLabel> labels) {
            this.source = source;
            this.departureTime = departureTime;
            this.labels = Map.copyOf(labels);
        }

        public int source() {
            return source;
        }

        public double departureTime() {
            return departureTime;
        }

        /** Earliest arrival, or positive infinity when unreachable. */
        public double arrivalAt(int node) {
            ForwardLabel label = labels.get(node);
            return label == null ? Double.POSITIVE_INFINITY : label.time();
        }

        public boolean reached(int node) {
            return labels.containsKey(node);
        }

        public Path pathTo(int node) {
            ForwardLabel label = labels.get(node);
            if (label == null) {
                throw new IllegalArgumentException("node is unreachable from source: " + node);
            }
            return new Path(label.arcIds());
        }
    }

    /** Immutable backward labels and complete edge-ID witnesses. */
    public static final class BackwardResult {
        private final int destination;
        private final double arrivalDeadline;
        private final Map<Integer, BackwardLabel> labels;

        private BackwardResult(int destination, double arrivalDeadline, Map<Integer, BackwardLabel> labels) {
            this.destination = destination;
            this.arrivalDeadline = arrivalDeadline;
            this.labels = Map.copyOf(labels);
        }

        public int destination() {
            return destination;
        }

        public double arrivalDeadline() {
            return arrivalDeadline;
        }

        /** Latest feasible departure, or negative infinity when unreachable. */
        public double latestDepartureAt(int node) {
            BackwardLabel label = labels.get(node);
            return label == null ? Double.NEGATIVE_INFINITY : label.time();
        }

        public boolean reached(int node) {
            return labels.containsKey(node);
        }

        public Path pathFrom(int node) {
            BackwardLabel label = labels.get(node);
            if (label == null) {
                throw new IllegalArgumentException("node cannot reach destination by deadline: " + node);
            }
            return new Path(label.arcIds());
        }
    }
}
