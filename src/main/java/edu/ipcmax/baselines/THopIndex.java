package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.TemporalProfileWork;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * In-memory, unpruned time-dependent 2-hop label index (THop, Section IV).
 *
 * <p>For each ordered pair (u,v), an exact profile search from u contributes
 * {@code (v,F(u,v))} to {@code Lout(u)} and {@code (u,F(u,v))} to
 * {@code Lin(v)}. Querying intersects hub IDs and composes the two arrival
 * mappings before taking their exact lower envelope. Preference scores and
 * travel-time budgets are not inputs to this class.</p>
 */
public final class THopIndex {
    private final TDGraph graph;
    private final Domain indexDomain;
    private final Map<Integer, Map<Integer, ExactTemporalProfile>> outgoingLabels;
    private final Map<Integer, Map<Integer, ExactTemporalProfile>> incomingLabels;
    private final IndexStatistics statistics;

    private THopIndex(
            TDGraph graph,
            Domain indexDomain,
            Map<Integer, Map<Integer, ExactTemporalProfile>> outgoingLabels,
            Map<Integer, Map<Integer, ExactTemporalProfile>> incomingLabels,
            IndexStatistics statistics) {
        this.graph = graph;
        this.indexDomain = indexDomain;
        this.outgoingLabels = freeze(outgoingLabels);
        this.incomingLabels = freeze(incomingLabels);
        this.statistics = statistics;
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

        long started = System.nanoTime();
        long sampledPeak = usedHeapBytes();
        long labelsExpanded = 0;
        long labelsGenerated = 0;
        long compositions = 0;
        long envelopes = 0;
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
        return new THopIndex(graph, indexDomain, out, in, stats);
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
