package edu.ipcmax.core.pcmax;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.EdgeTemporalSummaryStore;

/** Query-horizon lower travel-time weights and admissible static distances. */
public final class QueryLowerBounds {
    private static final int MAX_DENSE_ID_FACTOR = 2;

    private final TDGraph graph;
    private final double[] weights;
    private final EdgeTemporalSummaryStore summaries;
    private final int maximumNodeId;
    private final boolean denseNodeIds;
    private final Map<Integer, Distances> forward =
            new ConcurrentHashMap<>();
    private final Map<Integer, Distances> reverse =
            new ConcurrentHashMap<>();
    private final Map<RadiusKey, Distances> truncatedForward =
            new ConcurrentHashMap<>();
    private final Map<RadiusKey, Distances> truncatedReverse =
            new ConcurrentHashMap<>();

    /** Builds {@code inf T_q tau_e} weights for every edge. */
    public QueryLowerBounds(TDGraph graph, Domain queryHorizon) {
        this.graph = java.util.Objects.requireNonNull(
                graph, "graph");
        this.maximumNodeId = maximumNodeId(graph);
        this.denseNodeIds = useDenseLabels(
                graph, maximumNodeId);
        this.weights = new double[graph.edgeCount()];
        this.summaries = null;
        for (Edge edge : graph.edges()) {
            double weight = PaceProfiles.minimumTravelTime(
                    edge, queryHorizon);
            if (!Double.isFinite(weight) || weight < 0) {
                throw new IllegalArgumentException(
                        "edge lower travel time must be finite and "
                                + "nonnegative: arc " + edge.arcId());
            }
            weights[edge.arcId()] = weight;
        }
    }

    /** Reuses prepared all-support edge minima. */
    public QueryLowerBounds(
            TDGraph graph,
            EdgeTemporalSummaryStore summaries) {
        this.graph = java.util.Objects.requireNonNull(
                graph, "graph");
        this.maximumNodeId = maximumNodeId(graph);
        this.denseNodeIds = useDenseLabels(
                graph, maximumNodeId);
        this.summaries = java.util.Objects.requireNonNull(
                summaries, "summaries");
        this.weights = null;
        if (summaries.size() != graph.edgeCount()) {
            throw new IllegalArgumentException(
                    "edge summary count does not match graph");
        }
    }

    public double edgeWeight(int arcId) {
        return summaries == null
                ? weights[arcId]
                : summaries.summary(arcId)
                        .lowerBoundTravelTime();
    }

    public double distance(int source, int destination) {
        return distancesFrom(source).distance(destination);
    }

    public Distances distancesFrom(int source) {
        return forward.computeIfAbsent(
                source,
                node -> dijkstra(node, true, Set.of()));
    }

    public Distances distancesTo(int destination) {
        return reverse.computeIfAbsent(
                destination,
                node -> dijkstra(node, false, Set.of()));
    }

    public Distances distancesTo(
            int destination,
            Set<Integer> excludedArcIds) {
        return excludedArcIds.isEmpty()
                ? distancesTo(destination)
                : dijkstra(
                        destination, false, excludedArcIds);
    }

    public Distances truncatedDistancesFrom(
            int source,
            double maximumDistance) {
        RadiusKey key = RadiusKey.of(
                source, maximumDistance);
        return truncatedForward.computeIfAbsent(
                key,
                ignored -> dijkstra(
                        source,
                        true,
                        Set.of(),
                        key.maximumDistance()));
    }

    public Distances truncatedDistancesTo(
            int destination,
            double maximumDistance) {
        RadiusKey key = RadiusKey.of(
                destination, maximumDistance);
        return truncatedReverse.computeIfAbsent(
                key,
                ignored -> dijkstra(
                        destination,
                        false,
                        Set.of(),
                        key.maximumDistance()));
    }

    public double distanceWithin(
            int source,
            int destination,
            double maximumDistance) {
        return truncatedDistancesFrom(
                source, maximumDistance)
                .distance(destination);
    }

    private Distances dijkstra(
            int start,
            boolean outgoing,
            Set<Integer> excludedArcIds) {
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
        if (Double.isNaN(maximumDistance)
                || maximumDistance < 0) {
            throw new IllegalArgumentException(
                    "maximum lower-bound distance must be "
                            + "nonnegative");
        }
        double maximum = Double.isFinite(maximumDistance)
                ? Domain.canonicalTime(maximumDistance)
                : Double.POSITIVE_INFINITY;
        return denseNodeIds
                ? denseDijkstra(
                        start,
                        outgoing,
                        excludedArcIds,
                        maximum)
                : sparseDijkstra(
                        start,
                        outgoing,
                        excludedArcIds,
                        maximum);
    }

    private Distances sparseDijkstra(
            int start,
            boolean outgoing,
            Set<Integer> excludedArcIds,
            double maximum) {
        Map<Integer, Double> distance = new HashMap<>();
        PriorityQueue<Label> queue =
                new PriorityQueue<>();
        distance.put(start, 0.0);
        queue.add(new Label(start, 0.0));
        while (!queue.isEmpty()) {
            Label current = queue.poll();
            if (current.distance() > distance.getOrDefault(
                    current.node(),
                    Double.POSITIVE_INFINITY)) {
                continue;
            }
            if (current.distance() > maximum) {
                break;
            }
            Iterable<Edge> adjacent = outgoing
                    ? graph.outgoingEdges(current.node())
                    : graph.incomingEdges(current.node());
            for (Edge edge : adjacent) {
                if (excludedArcIds.contains(edge.arcId())) {
                    continue;
                }
                int next = outgoing
                        ? edge.target() : edge.source();
                double candidate = Domain.canonicalTime(
                        current.distance()
                                + edgeWeight(edge.arcId()));
                if (candidate <= maximum
                        && candidate < distance.getOrDefault(
                                next,
                                Double.POSITIVE_INFINITY)) {
                    distance.put(next, candidate);
                    queue.add(new Label(next, candidate));
                }
            }
        }
        return new Distances(distance);
    }

    private Distances denseDijkstra(
            int start,
            boolean outgoing,
            Set<Integer> excludedArcIds,
            double maximum) {
        graph.node(start);
        double[] distance =
                new double[maximumNodeId + 1];
        Arrays.fill(
                distance, Double.POSITIVE_INFINITY);
        DenseNodeHeap queue =
                new DenseNodeHeap(distance);
        IntAccumulator reached = new IntAccumulator();
        distance[start] = 0;
        reached.add(start);
        queue.addOrDecrease(start);
        while (!queue.isEmpty()) {
            int current = queue.removeMinimum();
            double currentDistance = distance[current];
            if (currentDistance > maximum) {
                break;
            }
            Iterable<Edge> adjacent = outgoing
                    ? graph.outgoingEdges(current)
                    : graph.incomingEdges(current);
            for (Edge edge : adjacent) {
                if (excludedArcIds.contains(edge.arcId())) {
                    continue;
                }
                int next = outgoing
                        ? edge.target() : edge.source();
                double candidate = Domain.canonicalTime(
                        currentDistance
                                + edgeWeight(edge.arcId()));
                if (candidate > maximum
                        || candidate >= distance[next]) {
                    continue;
                }
                if (!Double.isFinite(distance[next])) {
                    reached.add(next);
                }
                distance[next] = candidate;
                queue.addOrDecrease(next);
            }
        }
        int[] reachedNodes = reached.toArray();
        Arrays.sort(reachedNodes);
        return new Distances(distance, reachedNodes);
    }

    private static int maximumNodeId(TDGraph graph) {
        List<Integer> ids = graph.nodeIds();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException(
                    "lower-bound graph cannot be empty");
        }
        return ids.get(ids.size() - 1);
    }

    private static boolean useDenseLabels(
            TDGraph graph,
            int maximumNodeId) {
        return maximumNodeId <= Math.max(
                1L,
                (long) graph.nodeCount()
                        * MAX_DENSE_ID_FACTOR);
    }

    private record Label(
            int node,
            double distance)
            implements Comparable<Label> {
        @Override
        public int compareTo(Label other) {
            int byDistance = Double.compare(
                    distance, other.distance);
            return byDistance != 0
                    ? byDistance
                    : Integer.compare(node, other.node);
        }
    }

    private record RadiusKey(
            int node,
            double maximumDistance) {
        static RadiusKey of(
                int node,
                double maximumDistance) {
            if (!Double.isFinite(maximumDistance)
                    || maximumDistance < 0) {
                throw new IllegalArgumentException(
                        "truncated distance radius must be "
                                + "finite and nonnegative");
            }
            return new RadiusKey(
                    node,
                    Domain.canonicalTime(maximumDistance));
        }
    }

    /** Immutable distance lookup. */
    public static final class Distances {
        private final Map<Integer, Double> values;
        private final double[] denseValues;
        private final int[] denseReachedNodes;

        private Distances(Map<Integer, Double> values) {
            this.values = Map.copyOf(values);
            this.denseValues = null;
            this.denseReachedNodes = null;
        }

        private Distances(
                double[] denseValues,
                int[] denseReachedNodes) {
            this.values = null;
            this.denseValues = denseValues;
            this.denseReachedNodes = denseReachedNodes;
        }

        public double distance(int node) {
            if (denseValues != null) {
                return node >= 0
                        && node < denseValues.length
                        ? denseValues[node]
                        : Double.POSITIVE_INFINITY;
            }
            return values.getOrDefault(
                    node, Double.POSITIVE_INFINITY);
        }

        public boolean reached(int node) {
            return Double.isFinite(distance(node));
        }

        public List<Integer> reachedNodes() {
            if (denseReachedNodes != null) {
                return Arrays.stream(denseReachedNodes)
                        .boxed().toList();
            }
            return values.keySet().stream()
                    .sorted().toList();
        }

        /** Visits stable ascending active vertex IDs without boxing. */
        public void forEachReached(IntConsumer consumer) {
            java.util.Objects.requireNonNull(
                    consumer, "consumer");
            if (denseReachedNodes != null) {
                for (int node : denseReachedNodes) {
                    consumer.accept(node);
                }
            } else {
                values.keySet().stream()
                        .mapToInt(Integer::intValue)
                        .sorted()
                        .forEach(consumer);
            }
        }

        public int size() {
            return denseReachedNodes != null
                    ? denseReachedNodes.length
                    : values.size();
        }
    }

    /** Allocation-stable indexed heap ordered by distance then node ID. */
    private static final class DenseNodeHeap {
        private int[] nodes;
        private final int[] positions;
        private final double[] distances;
        private int size;

        DenseNodeHeap(double[] distances) {
            this.distances = distances;
            positions = new int[distances.length];
            Arrays.fill(positions, -1);
            nodes = new int[Math.min(
                    distances.length, 1 << 20)];
        }

        boolean isEmpty() {
            return size == 0;
        }

        void addOrDecrease(int node) {
            int position = positions[node];
            if (position == -2) {
                throw new IllegalStateException(
                        "nonnegative Dijkstra improved settled "
                                + "node " + node);
            }
            if (position < 0) {
                ensureCapacity();
                position = size++;
                nodes[position] = node;
                positions[node] = position;
            }
            siftUp(position);
        }

        int removeMinimum() {
            int minimum = nodes[0];
            positions[minimum] = -2;
            int last = --size;
            if (last == 0) {
                return minimum;
            }
            int node = nodes[last];
            int index = 0;
            while (true) {
                int left = (index << 1) + 1;
                if (left >= size) {
                    break;
                }
                int right = left + 1;
                int smaller = right < size
                        && compare(
                                nodes[right],
                                nodes[left]) < 0
                                ? right : left;
                if (compare(
                        node, nodes[smaller]) <= 0) {
                    break;
                }
                move(smaller, index);
                index = smaller;
            }
            nodes[index] = node;
            positions[node] = index;
            return minimum;
        }

        private void siftUp(int position) {
            int node = nodes[position];
            int index = position;
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                if (compare(
                        node, nodes[parent]) >= 0) {
                    break;
                }
                move(parent, index);
                index = parent;
            }
            nodes[index] = node;
            positions[node] = index;
        }

        private int compare(int left, int right) {
            int byDistance = Double.compare(
                    distances[left],
                    distances[right]);
            return byDistance != 0
                    ? byDistance
                    : Integer.compare(left, right);
        }

        private void move(int source, int destination) {
            nodes[destination] = nodes[source];
            positions[nodes[destination]] = destination;
        }

        private void ensureCapacity() {
            if (size < nodes.length) {
                return;
            }
            int expanded = (int) Math.min(
                    positions.length,
                    (long) nodes.length
                            + Math.max(
                                    1, nodes.length >>> 1));
            if (expanded <= nodes.length) {
                throw new IllegalStateException(
                        "dense Dijkstra heap exceeds vertex count");
            }
            nodes = Arrays.copyOf(nodes, expanded);
        }
    }

    private static final class IntAccumulator {
        private int[] values = new int[1_024];
        private int size;

        void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(
                        values,
                        values.length
                                + (values.length >>> 1));
            }
            values[size++] = value;
        }

        int[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
