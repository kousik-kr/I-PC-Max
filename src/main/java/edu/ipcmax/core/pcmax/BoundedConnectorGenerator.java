package edu.ipcmax.core.pcmax;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import edu.ipcmax.core.cache.SingleFlightCache;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.EdgeTemporalSummaryStore;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.PathPointer;

/**
 * Deterministic target-directed loopless connector portfolio.
 *
 * <p>The canonical bounded stream interleaves lower-bound-fast,
 * score-aware-positive-weight, and overlap-penalized queues with the fixed
 * schedule FAST, SCORE, DIVERSITY, FAST. Only selected pivots are forbidden;
 * every non-selected score edge remains in the connector graph and is replayed
 * exactly.</p>
 */
public final class BoundedConnectorGenerator {
    private static final List<StreamKind> SCHEDULE = List.of(
            StreamKind.FAST,
            StreamKind.SCORE,
            StreamKind.DIVERSITY,
            StreamKind.FAST);

    private final TDGraph graph;
    private final QueryCorridor corridor;
    private final PivotIndex pivots;
    private final QueryLowerBounds lowerBounds;
    private final EdgeTemporalSummaryStore summaries;
    private final Domain queryHorizon;
    private final PaceOptions options;
    private final PaceWorkLedger ledger;
    private final PaceExecutionMetrics metrics;
    private final SingleFlightCache<ConnectorKey, ConnectorResult>
            connectorCache;
    private final SingleFlightCache<ProfileKey, Optional<CandidateProfile>>
            profileCache;
    private final Map<Integer, Map<Integer, Double>> targetHeuristics =
            new ConcurrentHashMap<>();

    public BoundedConnectorGenerator(
            TDGraph graph,
            QueryCorridor corridor,
            PivotIndex pivots,
            QueryLowerBounds lowerBounds,
            EdgeTemporalSummaryStore summaries,
            Domain queryHorizon,
            PaceOptions options,
            PaceWorkLedger ledger) {
        this(
                graph, corridor, pivots, lowerBounds, summaries,
                queryHorizon, options, ledger, PaceExecutionMetrics.none());
    }

    public BoundedConnectorGenerator(
            TDGraph graph,
            QueryCorridor corridor,
            PivotIndex pivots,
            QueryLowerBounds lowerBounds,
            EdgeTemporalSummaryStore summaries,
            Domain queryHorizon,
            PaceOptions options,
            PaceWorkLedger ledger,
            PaceExecutionMetrics metrics) {
        this.graph = graph;
        this.corridor = corridor;
        this.pivots = pivots;
        this.lowerBounds = lowerBounds;
        this.summaries = summaries;
        this.queryHorizon = queryHorizon;
        this.options = options;
        this.ledger = ledger;
        this.metrics = java.util.Objects.requireNonNull(
                metrics, "metrics");
        this.connectorCache = new SingleFlightCache<>(4_096);
        this.profileCache = new SingleFlightCache<>(16_384);
    }

    public ConnectorResult connect(
            int source,
            int target,
            Domain entryDomain,
            BitSet prefixVisited,
            double residualBudget,
            String workItem) {
        return connect(
                source,
                target,
                entryDomain,
                prefixVisited,
                new BitSet(),
                residualBudget,
                workItem);
    }

    public ConnectorResult connect(
            int source,
            int target,
            Domain entryDomain,
            BitSet prefixVisited,
            BitSet prefixVisitedEdges,
            double residualBudget,
            String workItem) {
        metrics.increment("connector_requests");
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.CONNECTOR_GENERATION)) {
            return connectTimed(
                    source, target, entryDomain, prefixVisited,
                    prefixVisitedEdges,
                    residualBudget, workItem);
        }
    }

    private ConnectorResult connectTimed(
            int source,
            int target,
            Domain entryDomain,
            BitSet prefixVisited,
            BitSet prefixVisitedEdges,
            double residualBudget,
            String workItem) {
        if (entryDomain == null
                || entryDomain.isEmpty()
                || prefixVisited == null
                || prefixVisitedEdges == null
                || !Double.isFinite(residualBudget)
                || residualBudget < 0) {
            throw new IllegalArgumentException(
                    "invalid connector request");
        }
        ConnectorKey key = new ConnectorKey(
                source,
                target,
                entryDomain.toString(),
                Domain.canonicalTime(residualBudget),
                java.util.HexFormat.of().formatHex(
                        prefixVisited.toByteArray()),
                java.util.HexFormat.of().formatHex(
                        prefixVisitedEdges.toByteArray()),
                queryHorizon.toString(),
                corridor.checksum(),
                pivots.version(),
                TemporalPathVersion.SEMANTICS_VERSION,
                options.policy().name(),
                options.effectiveConnectorLimit(),
                options.connectorExpansionCapMc());
        if (!options.memoizationEnabled()
                || !options.features().connectorCacheEnabled()) {
            return compute(
                    source,
                    target,
                    entryDomain,
                    prefixVisited,
                    prefixVisitedEdges,
                    residualBudget,
                    workItem);
        }
        ConnectorResult result = connectorCache.getOrCompute(
                key,
                () -> compute(
                        source,
                        target,
                        entryDomain,
                        prefixVisited,
                        prefixVisitedEdges,
                        residualBudget,
                        workItem));
        observeCacheMetrics();
        return result;
    }

    private ConnectorResult compute(
            int source,
            int target,
            Domain entryDomain,
            BitSet prefixVisited,
            BitSet prefixVisitedEdges,
            double residualBudget,
            String workItem) {
        if (source == target) {
            Optional<CandidateProfile> identity = replay(
                    List.of(),
                    source,
                    target,
                    entryDomain,
                    residualBudget);
            return new ConnectorResult(
                    identity.stream().toList(), 0,
                    identity.isPresent() ? 0 : 1, false);
        }
        if (prefixVisited.get(target)) {
            return new ConnectorResult(List.of(), 0, 1, false);
        }
        return options.policy() == PaceExecutionPolicy.PACE_X
                ? exhaustive(
                        source,
                        target,
                        entryDomain,
                        prefixVisited,
                        prefixVisitedEdges,
                        residualBudget)
                : bounded(
                        source,
                        target,
                        entryDomain,
                        prefixVisited,
                        prefixVisitedEdges,
                        residualBudget,
                        workItem);
    }

    private ConnectorResult exhaustive(
            int source,
            int target,
            Domain entryDomain,
            BitSet prefixVisited,
            BitSet prefixVisitedEdges,
            double residualBudget) {
        List<WeightedPath> paths = new ArrayList<>();
        Counter expansions = new Counter();
        BitSet visited = (BitSet) prefixVisited.clone();
        BitSet visitedEdges = (BitSet) prefixVisitedEdges.clone();
        visited.set(source);
        Map<Integer, Double> heuristic = heuristicTo(target);
        enumerateDepthFirst(
                source,
                target,
                residualBudget,
                0,
                visited,
                visitedEdges,
                PathPointer.empty(),
                paths,
                expansions,
                heuristic);
        paths.sort(weightedPathOrder());
        List<CandidateProfile> valid = new ArrayList<>();
        long invalid = 0;
        Set<List<Integer>> deduplicated = new HashSet<>();
        for (WeightedPath path : paths) {
            List<Integer> arcIds = path.pointer().stablePathId();
            if (!deduplicated.add(arcIds)) {
                continue;
            }
            Optional<CandidateProfile> profile = replay(
                    arcIds,
                    source,
                    target,
                    entryDomain,
                    residualBudget);
            if (profile.isPresent()) {
                valid.add(profile.orElseThrow());
            } else {
                invalid++;
            }
        }
        ledger.addConnectorExpansions(expansions.value);
        return new ConnectorResult(
                valid, expansions.value, invalid, false);
    }

    private void enumerateDepthFirst(
            int current,
            int target,
            double budget,
            double pathWeight,
            BitSet visited,
            BitSet visitedEdges,
            PathPointer path,
            List<WeightedPath> output,
            Counter expansions,
            Map<Integer, Double> heuristic) {
        PaceCancellation.checkpoint();
        expansions.value++;
        if (current == target) {
            output.add(new WeightedPath(path, pathWeight));
            return;
        }
        for (Edge edge : corridor.outgoingEdges(current)) {
            if (pivots.isSelectedPivot(edge.arcId())
                    || visitedEdges.get(edge.arcId())
                    || visited.get(edge.target())) {
                continue;
            }
            double next = Domain.canonicalTime(
                    pathWeight
                            + lowerBounds.edgeWeight(edge.arcId()));
            double remaining = heuristic.getOrDefault(
                    edge.target(), Double.POSITIVE_INFINITY);
            if (!Double.isFinite(remaining)) {
                continue;
            }
            double completion = Domain.canonicalTime(
                    next + remaining);
            if (completion > Domain.canonicalTime(budget)) {
                continue;
            }
            visited.set(edge.target());
            visitedEdges.set(edge.arcId());
            enumerateDepthFirst(
                    edge.target(),
                    target,
                    budget,
                    next,
                    visited,
                    visitedEdges,
                    PathPointer.concat(
                            path,
                            PathPointer.arc(edge.arcId())),
                    output,
                    expansions,
                    heuristic);
            visitedEdges.clear(edge.arcId());
            visited.clear(edge.target());
        }
    }

    private ConnectorResult bounded(
            int source,
            int target,
            Domain entryDomain,
            BitSet prefixVisited,
            BitSet prefixVisitedEdges,
            double residualBudget,
            String workItem) {
        Map<Integer, Double> heuristic = heuristicTo(target);
        double sourceHeuristic = heuristic.getOrDefault(
                source, Double.POSITIVE_INFINITY);
        if (!Double.isFinite(sourceHeuristic)
                || sourceHeuristic
                    > Domain.canonicalTime(residualBudget)) {
            return new ConnectorResult(List.of(), 0, 0, false);
        }
        Map<StreamKind, PriorityQueue<PathState>> streams =
                new java.util.EnumMap<>(StreamKind.class);
        List<StreamKind> enabledStreams =
                options.features().connectorPortfolioEnabled()
                        ? List.of(StreamKind.values())
                        : List.of(StreamKind.FAST);
        for (StreamKind kind : enabledStreams) {
            PriorityQueue<PathState> queue =
                    new PriorityQueue<>(stateOrder(kind));
            BitSet visited = (BitSet) prefixVisited.clone();
            BitSet visitedEdges =
                    (BitSet) prefixVisitedEdges.clone();
            visited.set(source);
            queue.add(PathState.root(
                    source,
                    visited,
                    visitedEdges,
                    sourceHeuristic));
            streams.put(kind, queue);
        }
        List<CandidateProfile> valid = new ArrayList<>();
        Set<List<Integer>> emitted = new HashSet<>();
        Map<Integer, Integer> emittedEdgeUse = new HashMap<>();
        long invalid = 0;
        long expansions = 0;
        int scheduleIndex = 0;
        boolean capReached = false;
        List<StreamKind> schedule =
                options.features().connectorPortfolioEnabled()
                        ? SCHEDULE
                        : List.of(StreamKind.FAST);
        while (hasWork(streams)
                && valid.size() < options.connectorLimitKc()) {
            PaceCancellation.checkpoint();
            StreamKind kind = schedule.get(
                    scheduleIndex++ % schedule.size());
            PriorityQueue<PathState> queue = streams.get(kind);
            if (queue.isEmpty()) {
                continue;
            }
            if (expansions >= options.connectorExpansionCapMc()) {
                capReached = true;
                break;
            }
            PathState state = queue.poll();
            expansions++;
            if (state.node() == target) {
                List<Integer> arcIds =
                        state.path().stablePathId();
                if (!emitted.add(arcIds)) {
                    continue;
                }
                Optional<CandidateProfile> profile = replay(
                        arcIds,
                        source,
                        target,
                        entryDomain,
                        residualBudget);
                if (profile.isPresent()) {
                    valid.add(profile.orElseThrow());
                    for (int arcId : arcIds) {
                        emittedEdgeUse.merge(arcId, 1, Integer::sum);
                    }
                } else {
                    invalid++;
                }
                continue;
            }
            for (Edge edge : corridor.outgoingEdges(state.node())) {
                if (pivots.isSelectedPivot(edge.arcId())
                        || state.visitedEdges().get(edge.arcId())
                        || state.visited().get(edge.target())) {
                    continue;
                }
                double nextWeight = Domain.canonicalTime(
                        state.lowerWeight()
                                + lowerBounds.edgeWeight(edge.arcId()));
                double remaining =
                        heuristic.getOrDefault(
                                edge.target(),
                                Double.POSITIVE_INFINITY);
                if (!Double.isFinite(remaining)) {
                    continue;
                }
                double completion = Domain.canonicalTime(
                        nextWeight + remaining);
                if (completion
                            > Domain.canonicalTime(residualBudget)) {
                    continue;
                }
                BitSet nextVisited =
                        (BitSet) state.visited().clone();
                BitSet nextVisitedEdges =
                        (BitSet) state.visitedEdges().clone();
                nextVisited.set(edge.target());
                nextVisitedEdges.set(edge.arcId());
                double scoreCost = Domain.canonicalTime(
                        state.scoreCost()
                                + lowerBounds.edgeWeight(edge.arcId())
                                / (1.0
                                   + summaries.summary(
                                           edge.arcId()).maximumScore()));
                double diversityCost = Domain.canonicalTime(
                        state.diversityCost()
                                + lowerBounds.edgeWeight(edge.arcId())
                                * (1.0 + emittedEdgeUse.getOrDefault(
                                        edge.arcId(), 0)));
                queue.add(new PathState(
                        edge.target(),
                        PathPointer.concat(
                                state.path(),
                                PathPointer.arc(edge.arcId())),
                        nextVisited,
                        nextVisitedEdges,
                        nextWeight,
                        completion,
                        scoreCost,
                        diversityCost));
            }
        }
        ledger.addConnectorExpansions(expansions);
        metrics.addCounter("connector_expansions", expansions);
        if (capReached) {
            ledger.connectorCapReached(workItem);
        }
        return new ConnectorResult(
                valid, expansions, invalid, capReached);
    }

    private Optional<CandidateProfile> replay(
            List<Integer> arcIds,
            int source,
            int target,
            Domain domain,
            double budget) {
        ProfileKey key = new ProfileKey(
                arcIds,
                source,
                target,
                domain.toString(),
                queryHorizon.toString(),
                Domain.canonicalTime(budget),
                TemporalPathVersion.hash(graph, arcIds),
                pivots.version(),
                "CONNECTOR_REPLAY");
        Optional<CandidateProfile> result;
        if (options.memoizationEnabled()
                && options.features().profileCacheEnabled()) {
            result = profileCache.getOrCompute(key, () ->
                    replayUncached(
                            arcIds,
                            source,
                            target,
                            domain,
                            budget));
        } else {
            result = replayUncached(
                    arcIds,
                    source,
                    target,
                    domain,
                    budget);
        }
        if (result.isEmpty()) {
            return result;
        }
        CandidateProfile candidate = result.orElseThrow();
        int breakpoints = candidate.arrivalProfile()
                .breakpoints().size()
                + candidate.scoreProfile().breakpoints().size();
        if (!ledger.acceptsBreakpoints(
                breakpoints,
                "connector-profile:" + arcIds)) {
            return Optional.empty();
        }
        observeCacheMetrics();
        return result;
    }

    private Optional<CandidateProfile> replayUncached(
            List<Integer> arcIds,
            int source,
            int target,
            Domain domain,
            double budget) {
        /*
         * Connector profile construction is part of connector generation.
         * Do not also charge it to canonical full-candidate replay: the
         * launch report's top-level phase categories must be disjoint.
         */
        return CanonicalPathProfileBuilder.replay(
                graph,
                queryHorizon,
                Set.copyOf(pivots.selectedArcIds()),
                arcIds,
                source,
                target,
                domain,
                budget,
                -1,
                false);
    }

    public long cacheLookups() {
        return connectorCache.lookups() + profileCache.lookups();
    }

    public long cacheHits() {
        return connectorCache.hits() + profileCache.hits();
    }

    public long cacheMisses() {
        return connectorCache.misses() + profileCache.misses();
    }

    public long cacheWaits() {
        return connectorCache.waits() + profileCache.waits();
    }

    public long cacheEvictions() {
        return connectorCache.evictions()
                + profileCache.evictions();
    }

    public long cachePeakEntries() {
        return connectorCache.peakSize()
                + profileCache.peakSize();
    }

    /** Releases all query-local connector and profile-index state. */
    public void releaseCaches() {
        observeCacheMetrics();
        metrics.observeCounter(
                "cache_evictions", cacheEvictions());
        metrics.observeCounter(
                "cache_peak_entries", cachePeakEntries());
        connectorCache.clear();
        profileCache.clear();
        targetHeuristics.clear();
    }

    private void observeCacheMetrics() {
        metrics.observeCounter(
                "cache_hits", cacheHits());
        metrics.observeCounter(
                "cache_misses", cacheMisses());
        metrics.observeCounter(
                "cache_lookups", cacheLookups());
        metrics.observeCounter(
                "cache_waits", cacheWaits());
        metrics.observeCounter(
                "cache_evictions", cacheEvictions());
        metrics.observeCounter(
                "cache_peak_entries", cachePeakEntries());
    }

    private Map<Integer, Double> heuristicTo(int target) {
        return targetHeuristics.computeIfAbsent(
                target, this::buildHeuristic);
    }

    /**
     * Exact static reverse distances in the query connector graph. The search
     * touches only corridor incoming arcs and excludes selected pivots.
     */
    private Map<Integer, Double> buildHeuristic(int target) {
        Map<Integer, Double> distances = new HashMap<>();
        PriorityQueue<HeuristicLabel> queue =
                new PriorityQueue<>();
        distances.put(target, 0.0);
        queue.add(new HeuristicLabel(target, 0.0));
        while (!queue.isEmpty()) {
            PaceCancellation.checkpoint();
            HeuristicLabel current = queue.poll();
            if (current.distance() > distances.getOrDefault(
                    current.node(), Double.POSITIVE_INFINITY)) {
                continue;
            }
            for (Edge edge : corridor.incomingEdges(current.node())) {
                if (pivots.isSelectedPivot(edge.arcId())) {
                    continue;
                }
                double candidate = Domain.canonicalTime(
                        current.distance()
                                + lowerBounds.edgeWeight(edge.arcId()));
                if (candidate < distances.getOrDefault(
                        edge.source(), Double.POSITIVE_INFINITY)) {
                    distances.put(edge.source(), candidate);
                    queue.add(new HeuristicLabel(
                            edge.source(), candidate));
                }
            }
        }
        return Map.copyOf(distances);
    }

    private static boolean hasWork(
            Map<StreamKind, PriorityQueue<PathState>> streams) {
        return streams.values().stream().anyMatch(queue -> !queue.isEmpty());
    }

    private static Comparator<WeightedPath> weightedPathOrder() {
        return Comparator
                .comparingDouble(WeightedPath::lowerWeight)
                .thenComparingInt(value -> value.pointer().edgeCount())
                .thenComparing(
                        value -> value.pointer().stablePathId(),
                        PathPointer.STABLE_PATH_ORDER);
    }

    private static Comparator<PathState> stateOrder(StreamKind kind) {
        return (left, right) -> {
            int comparison = switch (kind) {
                case FAST -> Double.compare(
                        left.estimatedCompletion(),
                        right.estimatedCompletion());
                case SCORE -> Double.compare(
                        left.scoreCost(), right.scoreCost());
                case DIVERSITY -> Double.compare(
                        left.diversityCost(),
                        right.diversityCost());
            };
            if (comparison == 0) {
                comparison = Double.compare(
                        left.lowerWeight(), right.lowerWeight());
            }
            if (comparison == 0) {
                comparison = Integer.compare(
                        left.path().edgeCount(),
                        right.path().edgeCount());
            }
            if (comparison == 0) {
                comparison = PathPointer.STABLE_PATH_ORDER.compare(
                        left.path().stablePathId(),
                        right.path().stablePathId());
            }
            return comparison != 0
                    ? comparison
                    : Integer.compare(left.node(), right.node());
        };
    }

    private enum StreamKind {
        FAST,
        SCORE,
        DIVERSITY
    }

    private record WeightedPath(
            PathPointer pointer,
            double lowerWeight) {
    }

    private record HeuristicLabel(
            int node,
            double distance)
            implements Comparable<HeuristicLabel> {
        @Override
        public int compareTo(HeuristicLabel other) {
            int comparison = Double.compare(
                    distance, other.distance);
            return comparison != 0
                    ? comparison
                    : Integer.compare(node, other.node);
        }
    }

    private record PathState(
            int node,
            PathPointer path,
            BitSet visited,
            BitSet visitedEdges,
            double lowerWeight,
            double estimatedCompletion,
            double scoreCost,
            double diversityCost) {
        static PathState root(
                int source,
                BitSet visited,
                BitSet visitedEdges,
                double estimate) {
            return new PathState(
                    source,
                    PathPointer.empty(),
                    visited,
                    visitedEdges,
                    0,
                    estimate,
                    0,
                    0);
        }
    }

    private record ConnectorKey(
            int source,
            int target,
            String domain,
            double budget,
            String visited,
            String visitedEdges,
            String queryHorizon,
            String corridorChecksum,
            String pivotContext,
            String temporalSemanticsVersion,
            String mode,
            int connectorLimit,
            long expansionCap) {
    }

    private record ProfileKey(
            List<Integer> arcIds,
            int source,
            int target,
            String domain,
            String queryHorizon,
            double budget,
            String temporalPathVersion,
            String pivotContext,
            String mode) {
        private ProfileKey {
            arcIds = List.copyOf(arcIds);
        }
    }

    private static final class Counter {
        private long value;
    }
}
