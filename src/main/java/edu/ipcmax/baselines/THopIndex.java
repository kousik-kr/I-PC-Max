package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.TemporalProfileWork;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * In-memory time-dependent 2-hop label index (THop, Section IV).
 *
 * <p>For each ordered pair (u,v), an exact profile search from u contributes
 * {@code (v,F(u,v))} to {@code Lout(u)} and {@code (u,F(u,v))} to
 * {@code Lin(v)}. {@link #build(TDGraph, Domain)} keeps the unpruned M2A
 * reference builder; {@link #buildPruned(TDGraph, Domain)} implements the
 * incremental Section IV-A pruning. Queries intersect hub IDs and compose the
 * two arrival mappings before taking their exact lower envelope. Preference
 * scores and travel-time budgets are not inputs to this class.</p>
 */
public final class THopIndex {
    private final TDGraph graph;
    private final Domain indexDomain;
    private final Map<Integer, Map<Integer, ExactTemporalProfile>> outgoingLabels;
    private final Map<Integer, Map<Integer, ExactTemporalProfile>> incomingLabels;
    private final IndexStatistics statistics;
    private final PreprocessingStatistics preprocessingStatistics;
    private final List<Integer> hubOrder;
    private final boolean prunedConstruction;

    private THopIndex(
            TDGraph graph,
            Domain indexDomain,
            Map<Integer, Map<Integer, ExactTemporalProfile>> outgoingLabels,
            Map<Integer, Map<Integer, ExactTemporalProfile>> incomingLabels,
            IndexStatistics statistics,
            PreprocessingStatistics preprocessingStatistics,
            List<Integer> hubOrder,
            boolean prunedConstruction) {
        this.graph = graph;
        this.indexDomain = indexDomain;
        this.outgoingLabels = freeze(outgoingLabels);
        this.incomingLabels = freeze(incomingLabels);
        this.statistics = statistics;
        this.preprocessingStatistics = preprocessingStatistics;
        this.hubOrder = List.copyOf(hubOrder);
        this.prunedConstruction = prunedConstruction;
    }

    /** Creates a query-ready index around labels produced by an offline builder. */
    static THopIndex fromExistingLabels(
            TDGraph graph,
            Domain indexDomain,
            Map<Integer, Map<Integer, ExactTemporalProfile>> outgoingLabels,
            Map<Integer, Map<Integer, ExactTemporalProfile>> incomingLabels,
            List<Integer> hubOrder) {
        long labels = countLabels(outgoingLabels) + countLabels(incomingLabels);
        long pieces = countPieces(outgoingLabels) + countPieces(incomingLabels);
        long breakpoints = countBreakpoints(outgoingLabels) + countBreakpoints(incomingLabels);
        long estimatedBytes = estimateBytes(labels, pieces, breakpoints);
        IndexStatistics stats = new IndexStatistics(
                0, labels, pieces, breakpoints, estimatedBytes, usedHeapBytes(), 0, 0, 0, 0);
        PreprocessingStatistics preprocessing = new PreprocessingStatistics(
                0, hubOrder.size(), 0, 0, 0, labels, 0, 0, 0, 0,
                pieces, estimatedBytes, usedHeapBytes(), 0, 0);
        return new THopIndex(
                graph, indexDomain, outgoingLabels, incomingLabels, stats, preprocessing, hubOrder, true);
    }

    /** Builds an index over the union of all edge departure-function domains. */
    public static THopIndex build(TDGraph graph) {
        Objects.requireNonNull(graph, "graph");
        Domain domain = Domain.empty();
        for (Edge edge : graph.edges()) {
            domain = domain.union(edge.travelTimeFunction().domain());
        }
        if (domain.isEmpty()) {
            throw new IllegalArgumentException(
                    "an explicit index domain is required when the graph has no temporal edges");
        }
        return build(graph, domain);
    }

    /** Builds a complete unpruned index over {@code indexDomain}. */
    public static THopIndex build(TDGraph graph, Domain indexDomain) {
        Objects.requireNonNull(graph, "graph");
        validateIndexDomain(graph, indexDomain);

        long started = System.nanoTime();
        long sampledPeak = usedHeapBytes();
        long labelsExpanded = 0;
        long labelsGenerated = 0;
        long compositions = 0;
        long envelopes = 0;
        long profilesConstructed = 0;
        Map<Integer, Map<Integer, ExactTemporalProfile>> out = new HashMap<>();
        Map<Integer, Map<Integer, ExactTemporalProfile>> in = new HashMap<>();
        for (int node : graph.nodeIds()) {
            out.put(node, new HashMap<>());
            in.put(node, new HashMap<>());
        }

        FifoFastestPathProfileSearch search = new FifoFastestPathProfileSearch(graph);
        for (int source : graph.nodeIds()) {
            FifoFastestPathProfileSearch.SearchResult result = search.search(source, indexDomain);
            FifoFastestPathProfileSearch.SearchStatistics searchStats = result.statistics();
            labelsExpanded += searchStats.searchStatesExpanded();
            labelsGenerated += searchStats.searchStatesGenerated();
            profilesConstructed += searchStats.searchStatesGenerated();
            compositions += searchStats.temporalCompositions();
            envelopes += searchStats.envelopeOperations();
            sampledPeak = Math.max(sampledPeak, searchStats.sampledPeakUsedHeapBytes());
            for (Map.Entry<Integer, ExactTemporalProfile> pair : result.profiles().entrySet()) {
                int destination = pair.getKey();
                ExactTemporalProfile profile = pair.getValue();
                out.get(source).put(destination, profile);
                in.get(destination).put(source, profile);
            }
            sampledPeak = Math.max(sampledPeak, usedHeapBytes());
        }

        long labelCount = countLabels(out) + countLabels(in);
        long pieces = countPieces(out) + countPieces(in);
        long breakpoints = countBreakpoints(out) + countBreakpoints(in);
        long estimatedBytes = estimateBytes(labelCount, pieces, breakpoints);
        IndexStatistics stats = new IndexStatistics(
                System.nanoTime() - started,
                labelCount,
                pieces,
                breakpoints,
                estimatedBytes,
                sampledPeak,
                labelsExpanded,
                labelsGenerated,
                compositions,
                envelopes);
        PreprocessingStatistics preprocessing = new PreprocessingStatistics(
                stats.constructionNanos(),
                graph.nodeCount(),
                graph.nodeCount(),
                0,
                profilesConstructed,
                labelCount,
                0,
                0,
                labelsExpanded,
                0,
                pieces,
                estimatedBytes,
                sampledPeak,
                compositions,
                envelopes);
        return new THopIndex(
                graph, indexDomain, out, in, stats, preprocessing, graph.nodeIds(), false);
    }

    /**
     * Builds the paper's incremental, dominance-pruned THop index using a
     * deterministic distance-to-barycenter hub order.
     */
    public static THopIndex buildPruned(TDGraph graph, Domain indexDomain) {
        Objects.requireNonNull(graph, "graph");
        return buildPruned(graph, indexDomain, barycenterHubOrder(graph));
    }

    /**
     * Builds a pruned index using the supplied explicit hub permutation. This
     * overload supports reproducibility and controlled hub-order comparisons.
     */
    public static THopIndex buildPruned(
            TDGraph graph, Domain indexDomain, List<Integer> suppliedHubOrder) {
        Objects.requireNonNull(graph, "graph");
        validateIndexDomain(graph, indexDomain);
        List<Integer> order = validateHubOrder(graph, suppliedHubOrder);
        new FifoFastestPathProfileSearch(graph); // validates FIFO, including temporal support gaps

        long started = System.nanoTime();
        long sampledPeak = usedHeapBytes();
        long profilesConstructed = 0;
        long forwardExpansions = 0;
        long backwardExpansions = 0;
        long searchExpansionsSkipped = 0;
        long forwardCalls = 0;
        long backwardCalls = 0;
        long forwardCompositions = 0;
        long backwardCompositions = 0;
        long forwardEnvelopes = 0;
        long backwardEnvelopes = 0;

        Map<Integer, Map<Integer, ExactTemporalProfile>> out = new HashMap<>();
        Map<Integer, Map<Integer, ExactTemporalProfile>> in = new HashMap<>();
        for (int node : graph.nodeIds()) {
            out.put(node, new HashMap<>());
            in.put(node, new HashMap<>());
        }

        Domain terminalArrivalDomain = PrunedFifoProfileSearch.terminalArrivalDomain(graph);
        Set<Integer> processedHubs = new LinkedHashSet<>();
        Set<LabelKey> prunedLabels = new HashSet<>();
        for (int hub : order) {
            ExactTemporalProfile self = ExactTemporalProfile.identity(indexDomain);
            out.get(hub).put(hub, self);
            in.get(hub).put(hub, self);

            PrunedFifoProfileSearch.SearchResult forward = PrunedFifoProfileSearch.run(
                    graph,
                    hub,
                    self,
                    PrunedFifoProfileSearch.Direction.FORWARD,
                    (vertex, profile) -> {
                        if (vertex == hub) {
                            return true;
                        }
                        ExactTemporalProfile previous = labelEnvelope(
                                out, in, hub, vertex, processedHubs);
                        if (previous.dominates(profile)) {
                            in.get(vertex).remove(hub);
                            prunedLabels.add(new LabelKey(Direction.FORWARD, hub, vertex));
                            return false;
                        }
                        in.get(vertex).put(hub, profile);
                        return true;
                    });
            forwardCalls++;
            profilesConstructed += forward.statistics().searchStatesGenerated();
            forwardExpansions += forward.statistics().searchStatesExpanded();
            searchExpansionsSkipped += forward.statistics().searchExpansionsSkipped();
            forwardCompositions += forward.statistics().temporalCompositions();
            forwardEnvelopes += forward.statistics().envelopeOperations();
            sampledPeak = Math.max(sampledPeak, forward.statistics().sampledPeakUsedHeapBytes());

            ExactTemporalProfile terminalIdentity = ExactTemporalProfile.identity(terminalArrivalDomain);
            PrunedFifoProfileSearch.SearchResult backward = PrunedFifoProfileSearch.run(
                    graph,
                    hub,
                    terminalIdentity,
                    PrunedFifoProfileSearch.Direction.BACKWARD,
                    (vertex, profile) -> {
                        if (vertex == hub) {
                            return true;
                        }
                        ExactTemporalProfile previous = labelEnvelope(
                                out, in, vertex, hub, processedHubs);
                        if (previous.dominates(profile)) {
                            out.get(vertex).remove(hub);
                            prunedLabels.add(new LabelKey(Direction.BACKWARD, hub, vertex));
                            return false;
                        }
                        out.get(vertex).put(hub, profile);
                        return true;
                    });
            backwardCalls++;
            profilesConstructed += backward.statistics().searchStatesGenerated();
            backwardExpansions += backward.statistics().searchStatesExpanded();
            searchExpansionsSkipped += backward.statistics().searchExpansionsSkipped();
            backwardCompositions += backward.statistics().temporalCompositions();
            backwardEnvelopes += backward.statistics().envelopeOperations();
            sampledPeak = Math.max(sampledPeak, backward.statistics().sampledPeakUsedHeapBytes());
            processedHubs.add(hub);
            sampledPeak = Math.max(sampledPeak, usedHeapBytes());
        }

        long constructionNanos = System.nanoTime() - started;
        long labelCount = countLabels(out) + countLabels(in);
        long pieces = countPieces(out) + countPieces(in);
        long breakpoints = countBreakpoints(out) + countBreakpoints(in);
        long estimatedBytes = estimateBytes(labelCount, pieces, breakpoints);
        long totalCompositions = forwardCompositions + backwardCompositions;
        long totalEnvelopes = forwardEnvelopes + backwardEnvelopes;
        long totalExpansions = forwardExpansions + backwardExpansions;
        long labelsPrunedCount = prunedLabels.stream()
                .filter(key -> key.direction() == Direction.FORWARD
                        ? !in.get(key.vertex()).containsKey(key.hub())
                        : !out.get(key.vertex()).containsKey(key.hub()))
                .count();
        IndexStatistics stats = new IndexStatistics(
                constructionNanos,
                labelCount,
                pieces,
                breakpoints,
                estimatedBytes,
                sampledPeak,
                totalExpansions,
                profilesConstructed,
                totalCompositions,
                totalEnvelopes);
        PreprocessingStatistics preprocessing = new PreprocessingStatistics(
                constructionNanos,
                processedHubs.size(),
                forwardCalls,
                backwardCalls,
                profilesConstructed,
                labelCount,
                labelsPrunedCount,
                searchExpansionsSkipped,
                forwardExpansions,
                backwardExpansions,
                pieces,
                estimatedBytes,
                sampledPeak,
                totalCompositions,
                totalEnvelopes);
        return new THopIndex(graph, indexDomain, out, in, stats, preprocessing, order, true);
    }

    private static void validateIndexDomain(TDGraph graph, Domain indexDomain) {
        if (indexDomain == null || indexDomain.isEmpty()) {
            throw new IllegalArgumentException("a nonempty index domain is required");
        }
        Domain commonEdgeDomain = null;
        for (Edge edge : graph.edges()) {
            Domain edgeDomain = edge.travelTimeFunction().domain();
            if (commonEdgeDomain == null) {
                commonEdgeDomain = edgeDomain;
            } else if (!commonEdgeDomain.equals(edgeDomain)) {
                throw new IllegalArgumentException(
                        "THop requires a common edge-function support domain for exact label composition");
            }
            if (!edgeDomain.difference(indexDomain).isEmpty()) {
                throw new IllegalArgumentException(
                        "index domain must cover every edge function domain; arc_id=" + edge.arcId());
            }
        }
    }

    private static List<Integer> barycenterHubOrder(TDGraph graph) {
        List<Integer> nodes = graph.nodeIds();
        if (nodes.isEmpty()) {
            return List.of();
        }
        long firstX = graph.node(nodes.get(0)).x();
        long firstY = graph.node(nodes.get(0)).y();
        boolean coincident = nodes.stream().allMatch(node ->
                graph.node(node).x() == firstX && graph.node(node).y() == firstY);
        if (coincident) {
            return nodes;
        }

        double centerX = nodes.stream().mapToDouble(node -> graph.node(node).x()).average().orElse(0);
        double centerY = nodes.stream().mapToDouble(node -> graph.node(node).y()).average().orElse(0);
        return nodes.stream().sorted((left, right) -> {
            double leftDistance = Math.hypot(
                    graph.node(left).x() - centerX, graph.node(left).y() - centerY);
            double rightDistance = Math.hypot(
                    graph.node(right).x() - centerX, graph.node(right).y() - centerY);
            int comparison = Double.compare(leftDistance, rightDistance);
            return comparison != 0 ? comparison : Integer.compare(left, right);
        }).toList();
    }

    private static List<Integer> validateHubOrder(TDGraph graph, List<Integer> suppliedHubOrder) {
        if (suppliedHubOrder == null || suppliedHubOrder.size() != graph.nodeCount()
                || new HashSet<>(suppliedHubOrder).size() != graph.nodeCount()
                || !new HashSet<>(suppliedHubOrder).equals(new HashSet<>(graph.nodeIds()))) {
            throw new IllegalArgumentException("hub order must be a permutation of all graph vertices");
        }
        suppliedHubOrder.forEach(graph::node);
        return List.copyOf(suppliedHubOrder);
    }

    private static ExactTemporalProfile labelEnvelope(
            Map<Integer, Map<Integer, ExactTemporalProfile>> out,
            Map<Integer, Map<Integer, ExactTemporalProfile>> in,
            int source,
            int destination,
            Set<Integer> eligibleHubs) {
        ExactTemporalProfile result = ExactTemporalProfile.empty();
        for (int hub : eligibleHubs) {
            ExactTemporalProfile sourceToHub = out.get(source).get(hub);
            ExactTemporalProfile hubToDestination = in.get(destination).get(hub);
            if (sourceToHub == null || hubToDestination == null) {
                continue;
            }
            result = result.pointwiseMinimum(sourceToHub.composeAfter(hubToDestination));
        }
        return result;
    }

    private enum Direction {
        FORWARD,
        BACKWARD
    }

    private record LabelKey(Direction direction, int hub, int vertex) {
    }

    public TDGraph graph() {
        return graph;
    }

    public Domain indexDomain() {
        return indexDomain;
    }

    /** Hubs h with labels (h,F(v,h,t)) for v. */
    public Map<Integer, ExactTemporalProfile> outgoingLabels(int vertex) {
        graph.node(vertex);
        return outgoingLabels.get(vertex);
    }

    /** Hubs h with labels (h,F(h,v,t)) for v. */
    public Map<Integer, ExactTemporalProfile> incomingLabels(int vertex) {
        graph.node(vertex);
        return incomingLabels.get(vertex);
    }

    public IndexStatistics statistics() {
        return statistics;
    }

    /** Detailed construction counters for the selected build mode. */
    public PreprocessingStatistics preprocessingStatistics() {
        return preprocessingStatistics;
    }

    /** Deterministic hub order used to construct this index. */
    public List<Integer> hubOrder() {
        return hubOrder;
    }

    /** Whether this index was constructed with incremental THop pruning. */
    public boolean isPrunedConstruction() {
        return prunedConstruction;
    }

    /**
     * Answers the fastest-path profile over the entire requested interval.
     * The returned profile's values are travel times; endpoint and support
     * ownership are retained exactly.
     */
    public QueryResult query(int source, int destination, Domain queryInterval) {
        graph.node(source);
        graph.node(destination);
        if (queryInterval == null || queryInterval.isEmpty()) {
            throw new IllegalArgumentException("a nonempty query interval is required");
        }
        if (!queryInterval.difference(indexDomain).isEmpty()) {
            throw new IllegalArgumentException("query interval lies outside the index temporal domain");
        }
        long started = System.nanoTime();
        Map<String, Long> work = new HashMap<>();
        long[] sampledPeak = {usedHeapBytes()};
        int commonHubs;
        ExactTemporalProfile arrivals = ExactTemporalProfile.empty();
        TreeSet<Integer> intersection = new TreeSet<>(outgoingLabels.get(source).keySet());
        intersection.retainAll(incomingLabels.get(destination).keySet());
        commonHubs = intersection.size();

        try (TemporalProfileWork.Scope ignored = TemporalProfileWork.install(
                (name, amount) -> {
                    work.merge(name, amount, Long::sum);
                    sampledPeak[0] = Math.max(sampledPeak[0], usedHeapBytes());
                })) {
            for (int hub : intersection) {
                ExactTemporalProfile sourceToHub = outgoingLabels.get(source).get(hub);
                ExactTemporalProfile hubToDestination = incomingLabels.get(destination).get(hub);
                ExactTemporalProfile candidate = sourceToHub
                        .composeAfter(hubToDestination)
                        .restrict(queryInterval);
                arrivals = arrivals.pointwiseMinimum(candidate);
            }
        }

        ExactTemporalProfile travelTimes = arrivals.asTravelTime();
        return new QueryResult(
                source,
                destination,
                queryInterval,
                travelTimes,
                new QueryStatistics(
                        System.nanoTime() - started,
                        commonHubs,
                        work.getOrDefault("thop_temporal_compositions", 0L),
                        work.getOrDefault("thop_envelope_operations", 0L),
                        travelTimes.pieceCount(),
                        Math.max(sampledPeak[0], usedHeapBytes())));
    }

    /** Arrival-time profile for internal composition by partitioned indexes. */
    ExactTemporalProfile queryArrivalProfile(
            int source, int destination, Domain queryInterval) {
        graph.node(source);
        graph.node(destination);
        if (queryInterval == null || queryInterval.isEmpty()) {
            throw new IllegalArgumentException("a nonempty query interval is required");
        }
        if (!queryInterval.difference(indexDomain).isEmpty()) {
            throw new IllegalArgumentException("query interval lies outside the index temporal domain");
        }
        ExactTemporalProfile arrivals = ExactTemporalProfile.empty();
        TreeSet<Integer> intersection = new TreeSet<>(outgoingLabels.get(source).keySet());
        intersection.retainAll(incomingLabels.get(destination).keySet());
        for (int hub : intersection) {
            ExactTemporalProfile candidate = outgoingLabels.get(source).get(hub)
                    .composeAfter(incomingLabels.get(destination).get(hub))
                    .restrict(queryInterval);
            arrivals = arrivals.pointwiseMinimum(candidate);
        }
        return arrivals;
    }

    int commonHubCount(int source, int destination) {
        graph.node(source);
        graph.node(destination);
        TreeSet<Integer> intersection = new TreeSet<>(outgoingLabels.get(source).keySet());
        intersection.retainAll(incomingLabels.get(destination).keySet());
        return intersection.size();
    }

    private static Map<Integer, Map<Integer, ExactTemporalProfile>> freeze(
            Map<Integer, Map<Integer, ExactTemporalProfile>> source) {
        Map<Integer, Map<Integer, ExactTemporalProfile>> result = new HashMap<>();
        source.forEach((vertex, labels) -> result.put(vertex, Map.copyOf(labels)));
        return Map.copyOf(result);
    }

    private static long countLabels(Map<Integer, Map<Integer, ExactTemporalProfile>> labels) {
        return labels.values().stream().mapToLong(Map::size).sum();
    }

    private static long countPieces(Map<Integer, Map<Integer, ExactTemporalProfile>> labels) {
        return labels.values().stream()
                .flatMap(map -> map.values().stream())
                .mapToLong(ExactTemporalProfile::pieceCount)
                .sum();
    }

    private static long countBreakpoints(Map<Integer, Map<Integer, ExactTemporalProfile>> labels) {
        return labels.values().stream()
                .flatMap(map -> map.values().stream())
                .mapToLong(ExactTemporalProfile::breakpointCount)
                .sum();
    }

    private static long estimateBytes(long labels, long pieces, long breakpoints) {
        // A documented payload estimate, not JVM object-layout instrumentation.
        return 48L * labels + 32L * pieces + 16L * breakpoints;
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    public record IndexStatistics(
            long constructionNanos,
            long labelCount,
            long temporalFunctionPieces,
            long temporalBreakpoints,
            long estimatedIndexBytes,
            long sampledPeakUsedHeapBytes,
            long searchStatesExpanded,
            long searchStatesGenerated,
            long temporalCompositions,
            long envelopeOperations) {
    }

    public record QueryStatistics(
            long queryNanos,
            int commonHubCount,
            long temporalCompositions,
            long envelopeOperations,
            long resultFunctionPieces,
            long sampledPeakUsedHeapBytes) {
    }

    /** Construction metrics kept separate from query latency. */
    public record PreprocessingStatistics(
            long constructionNanos,
            int processedHubs,
            long forwardSearchCalls,
            long backwardSearchCalls,
            long profilesConstructed,
            long labelsRetained,
            long labelsPruned,
            long searchExpansionsSkipped,
            long forwardSearchExpansions,
            long backwardSearchExpansions,
            long temporalFunctionPieces,
            long estimatedIndexBytes,
            long sampledPeakUsedHeapBytes,
            long temporalCompositions,
            long envelopeOperations) {
    }

    public record QueryResult(
            int source,
            int destination,
            Domain queryInterval,
            ExactTemporalProfile travelTimeProfile,
            QueryStatistics statistics) {
        public boolean hasReachableDepartures() {
            return !travelTimeProfile.isEmpty();
        }

        /** Travel time at a supported departure, or throws if unreachable. */
        public double travelTimeAt(double departureTime) {
            return travelTimeProfile.valueAt(departureTime);
        }
    }
}
