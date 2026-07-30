package edu.ipcmax.core.pcmax;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import edu.ipcmax.core.cache.SingleFlightCache;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.EdgeTemporalSummaryStore;
import edu.ipcmax.core.index.GraphPartitionMetadata;
import edu.ipcmax.core.index.QueryPreparationIndexes;
import edu.ipcmax.core.index.ScoreSupportIndex;
import edu.ipcmax.core.pcmax.PivotIndex.Pivot;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.TemporalProfileWork;

/**
 * Final non-recursive PACE candidate engine.
 */
public final class ForwardLayeredFrontierGenerator {
    private final TDGraph graph;
    private final PaceOptions options;
    private final EdgeTemporalSummaryStore summaries;
    private final GraphPartitionMetadata partition;
    private final ScoreSupportIndex scoreIndex;
    private final PaceExecutionMetrics metrics;
    private volatile PaceGenerationResult lastResult;

    public ForwardLayeredFrontierGenerator(
            TDGraph graph,
            PaceOptions options) {
        this(
                graph,
                options,
                QueryPreparationIndexes.buildAllowingZero(graph),
                PaceExecutionMetrics.none());
    }

    public ForwardLayeredFrontierGenerator(
            TDGraph graph,
            PaceOptions options,
            QueryPreparationIndexes indexes) {
        this(graph, options, indexes, PaceExecutionMetrics.none());
    }

    public ForwardLayeredFrontierGenerator(
            TDGraph graph,
            PaceOptions options,
            QueryPreparationIndexes indexes,
            PaceExecutionMetrics metrics) {
        this.graph = graph;
        this.options = options;
        this.metrics = java.util.Objects.requireNonNull(
                metrics, "metrics");
        if (indexes == null) {
            throw new IllegalArgumentException(
                    "query preparation indexes are required");
        }
        summaries = indexes.edgeTemporalSummaries();
        partition = indexes.graphPartition();
        scoreIndex = indexes.scoreSupport();
        lastResult = emptyResult();
    }

    public synchronized PaceGenerationResult generate(QuerySpec query) {
        if (!metrics.enabled()) {
            return generateMeasured(query);
        }
        try (TemporalProfileWork.Scope ignored =
                TemporalProfileWork.install(
                        metrics::addCounterQuiet)) {
            return generateMeasured(query);
        }
    }

    private PaceGenerationResult generateMeasured(QuerySpec query) {
        observeMemory("memory_after_preprocess_used_heap_bytes");
        Domain rootDomain = query.departureDomain();
        double horizonEnd = Domain.canonicalTime(
                query.departureEnd() + query.maxTravelTime());
        Domain queryHorizon = Domain.closed(
                query.departureStart(), horizonEnd);
        QueryLowerBounds lowerBounds =
                new QueryLowerBounds(graph, summaries);
        QueryCorridor corridor;
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.CORRIDOR_CONSTRUCTION)) {
            corridor = options.features().safeCorridorEnabled()
                    ? QueryCorridor.build(
                            graph,
                            lowerBounds,
                            partition,
                            query.source(),
                            query.destination(),
                            query.maxTravelTime())
                    : QueryCorridor.unpruned(
                            graph,
                            partition,
                            query.source(),
                            query.destination(),
                            query.maxTravelTime());
        }
        QueryLowerBounds.Distances toDestination =
                lowerBounds.truncatedDistancesTo(
                        query.destination(),
                        query.maxTravelTime());
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.HORIZON_VALIDATION)) {
            requireCorridorCoverage(corridor, queryHorizon);
        }
        PivotIndex pivots = PivotSelector.select(
                graph,
                corridor,
                lowerBounds,
                partition,
                summaries,
                scoreIndex,
                queryHorizon,
                options.effectiveAnchorLimit(),
                options.features()
                        .pivotDiversificationEnabled(),
                metrics);
        PaceWorkLedger ledger = new PaceWorkLedger(options);
        BoundedConnectorGenerator connectors =
                new BoundedConnectorGenerator(
                        graph,
                        corridor,
                        pivots,
                        lowerBounds,
                        summaries,
                        queryHorizon,
                        options,
                        ledger,
                        metrics);
        SafeScoreUpperBound scoreUpperBound =
                new SafeScoreUpperBound(corridor, summaries);
        MutableStats stats = new MutableStats(
                corridor, pivots, options.threadCount());
        IPCMaxParallelExecutor executor =
                new IPCMaxParallelExecutor(
                        options.threadCount());
        ReplayStore replayStore = new ReplayStore(
                query,
                queryHorizon,
                pivots,
                ledger,
                executor,
                stats);
        IncrementalFrontier completed =
                new IncrementalFrontier(
                        graph,
                        rootDomain,
                        query.maxTravelTime(),
                        query.source(),
                        query.destination(),
                        options,
                        ledger,
                        metrics,
                        executor);

        Map<StateKey, IncrementalFrontier> current =
                new TreeMap<>();
        StateKey identityKey = StateKey.of(
                query.source(), 0, new BitSet());
        IncrementalFrontier identityFrontier =
                stateFrontier(
                        query,
                        query.source(),
                        ledger,
                        executor);
        identityFrontier.insert(
                PartialCandidate.identity(
                        query.source(), rootDomain).profile(),
                "layer=0:identity");
        current.put(identityKey, identityFrontier);

        boolean queryWorkStopped = false;
        try (executor) {
            int maximumDepth = Math.min(
                    options.theta(), pivots.selected().size());
            for (int depth = 0;
                    depth <= maximumDepth && !current.isEmpty();
                    depth++) {
                PaceCancellation.checkpoint();
                Map<StateKey, IncrementalFrontier> next =
                        new TreeMap<>();
                outer:
                for (Map.Entry<StateKey, IncrementalFrontier> state :
                        current.entrySet()) {
                    PaceCancellation.checkpoint();
                    StateKey key = state.getKey();
                    for (CandidateProfile profile :
                            state.getValue().candidates().candidates()) {
                        PartialCandidate partial = partial(
                                query.source(), key, profile);
                        Domain finalDomain = residualDomain(
                                partial,
                                toDestination.distance(
                                        partial.endpoint()),
                                query.maxTravelTime());
                        if (!finalDomain.isEmpty()) {
                            String finalWork = workItem(
                                    depth,
                                    partial,
                                    "FINAL");
                            if (!ledger.reserveQueryWork(finalWork)) {
                                queryWorkStopped = true;
                                break outer;
                            }
                            stats.connectorCalls++;
                            ConnectorResult result = trackedConnect(
                                    connectors,
                                    partial,
                                    query.destination(),
                                    finalDomain,
                                    query.maxTravelTime(),
                                    0,
                                    finalWork,
                                    stats);
                            reduceFinal(
                                    partial,
                                    finalDomain,
                                    result,
                                    completed,
                                    stats,
                                    replayStore);
                            if (ledger.capStatus().reached(
                                    PaceCapKind.QUERY_WORK_M_Q)) {
                                queryWorkStopped = true;
                                break outer;
                            }
                        } else {
                            stats.residualBudgetRejections++;
                        }
                        if (depth >= maximumDepth) {
                            continue;
                        }
                        if (options.features()
                                .scoreUpperBoundEnabled()
                                && scoreUpperBound.cannotImprove(
                                partial.profile(),
                                completed.candidates(),
                                query.maxTravelTime())) {
                            stats.scoreUpperBoundRejections++;
                            continue;
                        }
                        List<PivotExpansion> expansions =
                                pivotExpansions(
                                        query,
                                        lowerBounds,
                                        toDestination,
                                        pivots,
                                        partial,
                                        depth,
                                        ledger,
                                        stats);
                        if (expansions.isEmpty()) {
                            if (ledger.capStatus().reached(
                                    PaceCapKind.QUERY_WORK_M_Q)) {
                                queryWorkStopped = true;
                                break outer;
                            }
                            continue;
                        }
                        List<Callable<PivotConnector>> tasks =
                                new ArrayList<>();
                        for (PivotExpansion expansion : expansions) {
                            tasks.add(() -> {
                                stats.workerEntered();
                                try {
                                    ConnectorResult result =
                                            connectors.connect(
                                                    partial.endpoint(),
                                                    expansion.pivot().source(),
                                                    expansion.entryDomain(),
                                                    partial.visitedVertices(),
                                                    expansion.connectorBudget(),
                                                    expansion.workItem());
                                    return new PivotConnector(
                                            expansion, result);
                                } finally {
                                    stats.workerExited();
                                }
                            });
                        }
                        List<PivotConnector> results;
                        if (options.threadCount() == 1) {
                            results = new ArrayList<>(tasks.size());
                            for (Callable<PivotConnector> task : tasks) {
                                try {
                                    results.add(task.call());
                                } catch (Exception failure) {
                                    throw new IllegalStateException(
                                            "pivot expansion failed",
                                            failure);
                                }
                            }
                        } else {
                            stats.parallelTasksStarted += tasks.size();
                            results = executor.invokeAllDeterministic(tasks);
                        }
                        for (PivotConnector result : results) {
                            stats.addConnector(result.result());
                            reducePivot(
                                    partial,
                                    result,
                                    next,
                                    stats,
                                    executor,
                                    replayStore);
                            if (ledger.capStatus().reached(
                                    PaceCapKind.QUERY_WORK_M_Q)) {
                                queryWorkStopped = true;
                                break outer;
                            }
                        }
                    }
                }
                current = next;
                stats.observeFrontiers(current, completed);
                if (queryWorkStopped) {
                    break;
                }
            }
        }
        PaceCapStatus caps = ledger.capStatus();
        CandidateSet rootFrontier = completed.candidates();
        completed.releaseCaches();
        current.values().forEach(
                IncrementalFrontier::releaseCaches);
        PaceCompletion completion = completion(
                rootFrontier, caps, options.policy());
        PaceExactnessScope exactness = exactness(
                completion, caps, pivots);
        String outputChecksum = outputChecksum(rootFrontier);
        PaceGenerationStats snapshot;
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.STATISTICS)) {
            for (PaceWorkKind kind :
                    PaceWorkKind.values()) {
                metrics.addCounter(
                        "mq_" + kind.name().toLowerCase(
                                java.util.Locale.ROOT),
                        ledger.typedWork(kind));
            }
            snapshot = stats.snapshot(
                    completed,
                    connectors,
                    ledger,
                    outputChecksum);
        }
        replayStore.release();
        connectors.releaseCaches();
        observeMemory("memory_after_query_used_heap_bytes");
        lastResult = new PaceGenerationResult(
                rootFrontier,
                completion,
                exactness,
                caps,
                snapshot,
                corridor.checksum(),
                pivots.selectedArcIds(),
                outputChecksum);
        return lastResult;
    }

    public PaceGenerationResult lastResult() {
        return lastResult;
    }

    private ConnectorResult trackedConnect(
            BoundedConnectorGenerator connectors,
            PartialCandidate partial,
            int target,
            Domain rootDomain,
            double budget,
            double reservedLowerBound,
            String workItem,
            MutableStats stats) {
        Domain entryDomain = partial.profile().arrivalProfile()
                .imageDomain(rootDomain);
        double spentMinimum = partial.profile().arrivalProfile()
                .minimumTravelTime(rootDomain);
        double connectorBudget = Domain.canonicalTime(Math.max(
                0,
                budget - spentMinimum - reservedLowerBound));
        stats.workerEntered();
        try {
            ConnectorResult result = connectors.connect(
                    partial.endpoint(),
                    target,
                    entryDomain,
                    partial.visitedVertices(),
                    connectorBudget,
                    workItem);
            stats.addConnector(result);
            return result;
        } finally {
            stats.workerExited();
        }
    }

    private List<PivotExpansion> pivotExpansions(
            QuerySpec query,
            QueryLowerBounds lowerBounds,
            QueryLowerBounds.Distances toDestination,
            PivotIndex pivots,
            PartialCandidate partial,
            int depth,
            PaceWorkLedger ledger,
            MutableStats stats) {
        List<PivotExpansion> result = new ArrayList<>();
        for (Pivot pivot : pivots.selected()) {
            if (partial.usedPivot(pivot.canonicalRank())
                    || partial.visited(pivot.target())
                    || (partial.visited(pivot.source())
                        && partial.endpoint() != pivot.source())) {
                continue;
            }
            double connectorLower = lowerBounds.distanceWithin(
                    partial.endpoint(),
                    pivot.source(),
                    query.maxTravelTime());
            double suffix = toDestination.distance(
                    pivot.target());
            double requiredAfterConnector = Domain.canonicalTime(
                    lowerBounds.edgeWeight(pivot.arcId()) + suffix);
            if (!Double.isFinite(connectorLower)
                    || !Double.isFinite(requiredAfterConnector)) {
                stats.residualBudgetRejections++;
                continue;
            }
            double required = Domain.canonicalTime(
                    connectorLower + requiredAfterConnector);
            Domain feasible = residualDomain(
                    partial, required, query.maxTravelTime());
            if (feasible.isEmpty()) {
                stats.residualBudgetRejections++;
                continue;
            }
            String item = workItem(
                    depth,
                    partial,
                    "PIVOT-" + pivot.arcId());
            if (!ledger.reserveQueryWork(item)) {
                break;
            }
            double spentMinimum = partial.profile().arrivalProfile()
                    .minimumTravelTime(feasible);
            double connectorBudget = Domain.canonicalTime(Math.max(
                    0,
                    query.maxTravelTime()
                            - spentMinimum
                            - requiredAfterConnector));
            result.add(new PivotExpansion(
                    pivot,
                    feasible,
                    partial.profile().arrivalProfile()
                            .imageDomain(feasible),
                    connectorBudget,
                    item));
            stats.connectorCalls++;
        }
        return List.copyOf(result);
    }

    private void reduceFinal(
            PartialCandidate partial,
            Domain rootDomain,
            ConnectorResult connectors,
            IncrementalFrontier completed,
            MutableStats stats,
            ReplayStore replayStore) {
        observeMemory(
                "memory_before_final_reduction_used_heap_bytes");
        List<ReplayRequest> requests = new ArrayList<>();
        for (CandidateProfile connector : connectors.connectors()) {
            PathPointer pointer = PathPointer.concat(
                    partial.profile().pathPointer(),
                    connector.pathPointer());
            requests.add(new ReplayRequest(
                    partial.profile(),
                    partial.endpoint(),
                    connector.pathPointer(),
                    pointer,
                    rootDomain,
                    replayStore.query.destination(),
                    "complete"));
        }
        metrics.addCounter(
                "final_reduction_input_candidates",
                requests.size());
        metrics.addCounter(
                "final_reduction_distinct_path_ids",
                requests.stream()
                        .map(request ->
                                request.pointer()
                                        .stablePathId())
                        .distinct()
                        .count());
        List<Optional<CandidateProfile>> replayedBatch;
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.FINAL_REDUCTION)) {
            replayedBatch = replayStore.replayBatch(requests);
        }
        for (int index = 0; index < requests.size(); index++) {
            ReplayRequest request = requests.get(index);
            Optional<CandidateProfile> replayed =
                    replayStore.accept(
                            request,
                            replayedBatch.get(index));
            if (replayed.isEmpty()) {
                stats.invalidConnectors++;
                continue;
            }
            stats.candidatesGenerated++;
            if (completed.insert(
                    replayed.orElseThrow(),
                    "root:" + request.pointer().stablePathId())) {
                stats.candidatesRetained++;
            }
        }
        observeMemory(
                "memory_after_final_reduction_used_heap_bytes");
    }

    private void reducePivot(
            PartialCandidate partial,
            PivotConnector connectorResult,
            Map<StateKey, IncrementalFrontier> next,
            MutableStats stats,
            IPCMaxParallelExecutor executor,
            ReplayStore replayStore) {
        PivotExpansion expansion = connectorResult.expansion();
        Pivot pivot = expansion.pivot();
        Edge pivotEdge = graph.edges().get(pivot.arcId());
        List<ReplayRequest> requests = new ArrayList<>();
        for (CandidateProfile connector :
                connectorResult.result().connectors()) {
            PathPointer suffix = PathPointer.concat(
                    connector.pathPointer(),
                    PathPointer.arc(pivot.arcId()));
            PathPointer pointer = PathPointer.concat(
                    partial.profile().pathPointer(),
                    suffix);
            if (!isSimple(
                    replayStore.query.source(),
                    pointer,
                    pivotEdge.target())) {
                stats.invalidConnectors++;
                continue;
            }
            requests.add(new ReplayRequest(
                    partial.profile(),
                    partial.endpoint(),
                    suffix,
                    pointer,
                    expansion.rootDomain(),
                    pivot.target(),
                    "pivot-" + pivot.arcId()));
        }
        List<CandidateProfile> layerOffers =
                new ArrayList<>();
        List<Optional<CandidateProfile>> replayedBatch =
                replayStore.replayBatch(requests);
        for (int index = 0; index < requests.size(); index++) {
            Optional<CandidateProfile> replayed =
                    replayStore.accept(
                            requests.get(index),
                            replayedBatch.get(index));
            if (replayed.isEmpty()) {
                stats.invalidConnectors++;
                continue;
            }
            stats.candidatesGenerated++;
            layerOffers.add(replayed.orElseThrow());
        }
        if (layerOffers.isEmpty()) {
            return;
        }
        BitSet used = partial.usedPivots();
        used.set(pivot.canonicalRank());
        StateKey key = StateKey.of(
                pivot.target(),
                partial.pivotDepth() + 1,
                used);
        IncrementalFrontier frontier = next.computeIfAbsent(
                key,
                ignored -> stateFrontier(
                        replayStore.query,
                        pivot.target(),
                        replayStore.ledger,
                        executor));
        stats.candidatesRetained += frontier.insertLayer(
                layerOffers,
                "layer=" + key.depth()
                        + ":pivot=" + pivot.arcId());
    }

    private Domain residualDomain(
            PartialCandidate candidate,
            double requiredLowerBound,
            double budget) {
        if (!Double.isFinite(requiredLowerBound)) {
            return Domain.empty();
        }
        return candidate.profile().arrivalProfile()
                .domainWhereTravelTimeAtMost(
                        candidate.profile().domain(),
                        Domain.canonicalTime(
                                budget - requiredLowerBound));
    }

    private boolean isSimple(
            int source,
            PathPointer pointer,
            int expectedEndpoint) {
        BitSet visited = new BitSet();
        visited.set(source);
        int current = source;
        for (int arcId : pointer.arcIds()) {
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != current
                    || visited.get(edge.target())) {
                return false;
            }
            visited.set(edge.target());
            current = edge.target();
        }
        return current == expectedEndpoint;
    }

    private PartialCandidate partial(
            int rootSource,
            StateKey key,
            CandidateProfile profile) {
        BitSet visited = new BitSet();
        visited.set(rootSource);
        int current = rootSource;
        for (int arcId : profile.stablePathId()) {
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != current) {
                throw new IllegalStateException(
                        "retained prefix is discontinuous");
            }
            visited.set(edge.target());
            current = edge.target();
        }
        return new PartialCandidate(
                key.endpoint(),
                profile,
                visited,
                key.usedPivots(),
                key.depth(),
                Set.of());
    }

    private IncrementalFrontier stateFrontier(
            QuerySpec query,
            int endpoint,
            PaceWorkLedger ledger,
            IPCMaxParallelExecutor executor) {
        return new IncrementalFrontier(
                graph,
                query.departureDomain(),
                query.maxTravelTime(),
                query.source(),
                endpoint,
                options,
                ledger,
                metrics,
                executor);
    }

    private void requireCorridorCoverage(
            QueryCorridor corridor,
            Domain queryHorizon) {
        for (int arcId : corridor.directedArcIds()) {
            Edge edge = graph.edges().get(arcId);
            if (!queryHorizon.difference(
                    edge.travelTimeFunction().domain()).isEmpty()
                    || !queryHorizon.difference(
                            edge.scoreFunction().domain()).isEmpty()) {
                throw new PaceException(
                        PaceStatus.FUNCTION_HORIZON_EXCEEDED,
                        "FUNCTION_HORIZON_EXCEEDED: arc_id "
                                + arcId + " does not cover "
                                + queryHorizon);
            }
        }
    }

    private static PaceCompletion completion(
            CandidateSet frontier,
            PaceCapStatus caps,
            PaceExecutionPolicy policy) {
        if (caps.any()) {
            return policy == PaceExecutionPolicy.PACE_X
                    ? PaceCompletion.ABORTED
                    : PaceCompletion.RESOURCE_TRUNCATED;
        }
        return frontier.isEmpty()
                ? PaceCompletion.NO_FEASIBLE_PATH
                : PaceCompletion.COMPLETE;
    }

    private PaceExactnessScope exactness(
            PaceCompletion completion,
            PaceCapStatus caps,
            PivotIndex pivots) {
        if (completion == PaceCompletion.ABORTED) {
            return PaceExactnessScope.NOT_CERTIFIED;
        }
        if (options.policy() == PaceExecutionPolicy.PACE_B) {
            return PaceExactnessScope.RETAINED_FRONTIER;
        }
        boolean exhaustiveConditions =
                !caps.any()
                && pivots.selected().size()
                    == pivots.scoreRelevantArcIds().size()
                && options.theta() >= pivots.selected().size();
        return exhaustiveConditions
                ? PaceExactnessScope.GLOBAL_CERTIFIED
                : PaceExactnessScope.RETAINED_FRONTIER;
    }

    private static String workItem(
            int depth,
            PartialCandidate candidate,
            String action) {
        return "layer=" + depth
                + ":candidate=" + candidate.candidateId()
                + ":action=" + action;
    }

    private static String outputChecksum(CandidateSet frontier) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
        update(digest, "PACE-CANDIDATE-OUTPUT-v1");
        for (CandidateProfile candidate : frontier.candidates()) {
            update(digest, candidate.stablePathId().toString());
            update(digest, candidate.domain().toString());
            candidate.arrivalProfile().breakpoints().forEach(point -> {
                digest.update(ByteBuffer.allocate(Long.BYTES)
                        .putLong(Double.doubleToLongBits(
                                point.minute())).array());
                digest.update(ByteBuffer.allocate(Long.BYTES)
                        .putLong(Double.doubleToLongBits(
                                point.value())).array());
            });
            candidate.scoreProfile().intervals().forEach(interval -> {
                digest.update(ByteBuffer.allocate(Long.BYTES)
                        .putLong(Double.doubleToLongBits(
                                interval.startMinute())).array());
                digest.update(ByteBuffer.allocate(Long.BYTES)
                        .putLong(Double.doubleToLongBits(
                                interval.endMinute())).array());
                digest.update(ByteBuffer.allocate(Integer.BYTES)
                        .putInt(interval.value()).array());
            });
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(
            MessageDigest digest,
            String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes.length).array());
        digest.update(bytes);
    }

    private void observeMemory(String counter) {
        if (!metrics.enabled()) {
            return;
        }
        metrics.observeCounter(
                counter,
                Runtime.getRuntime().totalMemory()
                        - Runtime.getRuntime().freeMemory());
    }

    private PaceGenerationResult emptyResult() {
        return new PaceGenerationResult(
                new CandidateSet(),
                PaceCompletion.NO_FEASIBLE_PATH,
                PaceExactnessScope.NOT_CERTIFIED,
                PaceCapStatus.none(),
                PaceGenerationStats.empty(),
                "",
                List.of(),
                "");
    }

    /**
     * Query-local, bounded replay coordinator. Unique suffix computations may
     * run concurrently; their results are always returned and inserted in the
     * original deterministic producer order.
     */
    private final class ReplayStore {
        private static final int MAXIMUM_ENTRIES = 32_768;

        private final QuerySpec query;
        private final Domain queryHorizon;
        private final List<Integer> selectedPivotArcIds;
        private final Set<Integer> selectedPivotArcIdSet;
        private final PaceWorkLedger ledger;
        private final IPCMaxParallelExecutor executor;
        private final MutableStats stats;
        private final boolean cacheEnabled;
        private final Set<Long> replayWorkerThreads =
                ConcurrentHashMap.newKeySet();
        private final AtomicInteger activeReplayWorkers =
                new AtomicInteger();
        private final AtomicInteger maximumReplayWorkers =
                new AtomicInteger();
        private final LinkedHashMap<ReplayKey,
                Optional<CandidateProfile>> cache =
                new LinkedHashMap<>(256, 0.75f, true);
        private final SingleFlightCache<ReplayPrefixKey,
                Optional<CandidateProfile>> prefixCache =
                new SingleFlightCache<>(8_192);
        private long peakEntries;

        ReplayStore(
                QuerySpec query,
                Domain queryHorizon,
                PivotIndex pivots,
                PaceWorkLedger ledger,
                IPCMaxParallelExecutor executor,
                MutableStats stats) {
            this.query = query;
            this.queryHorizon = queryHorizon;
            this.selectedPivotArcIds =
                    List.copyOf(pivots.selectedArcIds());
            this.selectedPivotArcIdSet =
                    Set.copyOf(selectedPivotArcIds);
            this.ledger = ledger;
            this.executor = executor;
            this.stats = stats;
            this.cacheEnabled =
                    options.memoizationEnabled()
                    && options.features().profileCacheEnabled();
        }

        List<Optional<CandidateProfile>> replayBatch(
                List<ReplayRequest> requests) {
            metrics.increment("canonical_replay_batches");
            metrics.addCounter(
                    "canonical_replay_requests", requests.size());
            if (requests.isEmpty()) {
                return List.of();
            }
            if (!cacheEnabled) {
                metrics.addCounter(
                        "canonical_replay_unique_requests",
                        requests.size());
                metrics.addCounter(
                        "canonical_replay_cache_misses",
                        requests.size());
                return executeUnique(requests);
            }

            List<Optional<CandidateProfile>> answers =
                    new ArrayList<>(requests.size());
            for (int index = 0; index < requests.size(); index++) {
                answers.add(null);
            }
            LinkedHashMap<ReplayKey, PendingReplay> pending =
                    new LinkedHashMap<>();
            Set<ReplayPrefixKey> distinctPrefixes =
                    new java.util.HashSet<>();
            long hits = 0;
            for (int index = 0;
                    index < requests.size();
                    index++) {
                ReplayRequest request = requests.get(index);
                ReplayKey key = key(request);
                distinctPrefixes.add(prefixKey(request));
                Optional<CandidateProfile> cached =
                        cache.get(key);
                if (cached != null) {
                    answers.set(index, cached);
                    hits++;
                    continue;
                }
                PendingReplay existing = pending.get(key);
                if (existing != null) {
                    existing.indices().add(index);
                    hits++;
                    continue;
                }
                List<Integer> indices = new ArrayList<>();
                indices.add(index);
                pending.put(
                        key,
                        new PendingReplay(request, indices));
            }
            metrics.addCounter(
                    "canonical_replay_cache_hits", hits);
            metrics.addCounter(
                    "canonical_replay_cache_misses",
                    pending.size());
            metrics.addCounter(
                    "canonical_replay_unique_requests",
                    pending.size());
            metrics.addCounter(
                    "canonical_replay_repeated_prefixes",
                    Math.max(
                            0,
                            requests.size()
                                    - distinctPrefixes.size()));
            List<PendingReplay> unique =
                    List.copyOf(pending.values());
            List<Optional<CandidateProfile>> computed =
                    executeUnique(unique.stream()
                            .map(PendingReplay::request)
                            .toList());
            int resultIndex = 0;
            for (Map.Entry<ReplayKey, PendingReplay> entry :
                    pending.entrySet()) {
                Optional<CandidateProfile> value =
                        computed.get(resultIndex++);
                for (int index : entry.getValue().indices()) {
                    answers.set(index, value);
                }
                cache.put(entry.getKey(), value);
                peakEntries = Math.max(peakEntries, cache.size());
                trimCache();
            }
            metrics.observeCounter(
                    "canonical_replay_cache_peak_entries",
                    peakEntries);
            return List.copyOf(answers);
        }

        Optional<CandidateProfile> accept(
                ReplayRequest request,
                Optional<CandidateProfile> replayed) {
            if (replayed.isEmpty()) {
                return replayed;
            }
            CandidateProfile flat = replayed.orElseThrow();
            int breakpoints =
                    flat.arrivalProfile().breakpoints().size()
                    + flat.scoreProfile().breakpoints().size();
            if (!ledger.acceptsBreakpoints(
                    breakpoints,
                    request.context() + ":"
                            + request.pointer().stablePathId())) {
                return Optional.empty();
            }
            return Optional.of(new CandidateProfile(
                    flat.domain(),
                    flat.arrivalProfile(),
                    flat.scoreProfile(),
                    request.pointer(),
                    flat.explicitAnchorCount(),
                    flat.pivotId(),
                    flat.compressed(),
                    flat.usedPivotArcIds()));
        }

        void release() {
            metrics.observeCounter(
                    "canonical_replay_cache_peak_entries",
                    peakEntries);
            cache.clear();
            metrics.observeCounter(
                    "canonical_prefix_cache_hits",
                    prefixCache.hits());
            metrics.observeCounter(
                    "canonical_prefix_cache_misses",
                    prefixCache.misses());
            metrics.observeCounter(
                    "canonical_prefix_cache_waits",
                    prefixCache.waits());
            metrics.observeCounter(
                    "canonical_prefix_cache_evictions",
                    prefixCache.evictions());
            metrics.observeCounter(
                    "canonical_prefix_cache_peak_entries",
                    prefixCache.peakSize());
            prefixCache.clear();
            metrics.checkpoint("query_local_caches_released");
        }

        private List<Optional<CandidateProfile>> executeUnique(
                List<ReplayRequest> requests) {
            List<Callable<Optional<CandidateProfile>>> tasks =
                    new ArrayList<>(requests.size());
            for (ReplayRequest request : requests) {
                tasks.add(() -> {
                    stats.workerEntered();
                    replayWorkerThreads.add(
                            Thread.currentThread().getId());
                    int active =
                            activeReplayWorkers.incrementAndGet();
                    maximumReplayWorkers.accumulateAndGet(
                            active, Math::max);
                    metrics.observeCounter(
                            "final_reduction_observed_workers",
                            replayWorkerThreads.size());
                    metrics.observeCounter(
                            "final_reduction_maximum_active_workers",
                            maximumReplayWorkers.get());
                    try {
                        return replayUncached(request);
                    } finally {
                        activeReplayWorkers.decrementAndGet();
                        stats.workerExited();
                    }
                });
            }
            if (options.threadCount() == 1
                    || tasks.size() < 2) {
                List<Optional<CandidateProfile>> result =
                        new ArrayList<>(tasks.size());
                for (Callable<Optional<CandidateProfile>> task :
                        tasks) {
                    try {
                        result.add(task.call());
                    } catch (Exception failure) {
                        throw new IllegalStateException(
                                "canonical replay failed",
                                failure);
                    }
                }
                return List.copyOf(result);
            }
            stats.parallelTasksStarted += tasks.size();
            metrics.addCounter(
                    "parallel_canonical_replay_tasks",
                    tasks.size());
            return executor.invokeAllDeterministic(tasks);
        }

        private Optional<CandidateProfile> replayUncached(
                ReplayRequest request) {
            observeMemory(
                    "memory_during_replay_used_heap_bytes");
            try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                    PaceExecutionMetrics.PATH_REPLAY)) {
                if (!cacheEnabled) {
                    return CanonicalPathProfileBuilder.replay(
                            graph,
                            queryHorizon,
                            selectedPivotArcIdSet,
                            request.pointer().stablePathId(),
                            query.source(),
                            request.endpoint(),
                            request.domain(),
                            query.maxTravelTime(),
                            -1,
                            false);
                }
                Optional<CandidateProfile> canonicalPrefix =
                        prefixCache.getOrCompute(
                                prefixKey(request),
                                () -> CanonicalPathProfileBuilder.replay(
                                        graph,
                                        queryHorizon,
                                        selectedPivotArcIdSet,
                                        request.prefix()
                                                .stablePathId(),
                                        query.source(),
                                        request.prefixEndpoint(),
                                        request.domain(),
                                        query.maxTravelTime(),
                                        -1,
                                        false));
                observePrefixCacheMetrics();
                if (canonicalPrefix.isEmpty()) {
                    return Optional.empty();
                }
                if (request.suffix().edgeCount() == 0) {
                    return canonicalPrefix;
                }
                return CanonicalPathProfileBuilder.extend(
                        graph,
                        queryHorizon,
                        selectedPivotArcIdSet,
                        canonicalPrefix.orElseThrow(),
                        query.source(),
                        request.prefixEndpoint(),
                        request.suffix().stablePathId(),
                        request.endpoint(),
                        request.domain(),
                        query.maxTravelTime(),
                        -1,
                        false);
            }
        }

        private ReplayPrefixKey prefixKey(
                ReplayRequest request) {
            return new ReplayPrefixKey(
                    request.prefix().stablePathId(),
                    query.source(),
                    request.prefixEndpoint(),
                    request.domain(),
                    queryHorizon,
                    Domain.canonicalTime(
                            query.maxTravelTime()),
                    selectedPivotArcIds);
        }

        private void observePrefixCacheMetrics() {
            metrics.observeCounter(
                    "canonical_prefix_cache_hits",
                    prefixCache.hits());
            metrics.observeCounter(
                    "canonical_prefix_cache_misses",
                    prefixCache.misses());
            metrics.observeCounter(
                    "canonical_prefix_cache_waits",
                    prefixCache.waits());
            metrics.observeCounter(
                    "canonical_prefix_cache_evictions",
                    prefixCache.evictions());
            metrics.observeCounter(
                    "canonical_prefix_cache_peak_entries",
                    prefixCache.peakSize());
        }

        private ReplayKey key(ReplayRequest request) {
            return new ReplayKey(
                    request.pointer().stablePathId(),
                    query.source(),
                    request.endpoint(),
                    request.domain(),
                    queryHorizon,
                    Domain.canonicalTime(
                            query.maxTravelTime()),
                    selectedPivotArcIds);
        }

        private void trimCache() {
            while (cache.size() > MAXIMUM_ENTRIES) {
                ReplayKey eldest =
                        cache.keySet().iterator().next();
                cache.remove(eldest);
                metrics.increment(
                        "canonical_replay_cache_evictions");
            }
        }
    }

    private record ReplayRequest(
            CandidateProfile prefix,
            int prefixEndpoint,
            PathPointer suffix,
            PathPointer pointer,
            Domain domain,
            int endpoint,
            String context) {
    }

    private record ReplayKey(
            List<Integer> stablePathId,
            int source,
            int endpoint,
            Domain domain,
            Domain queryHorizon,
            double budget,
            List<Integer> selectedPivotArcIds) {
        private ReplayKey {
            stablePathId = List.copyOf(stablePathId);
            selectedPivotArcIds =
                    List.copyOf(selectedPivotArcIds);
        }
    }

    private record ReplayPrefixKey(
            List<Integer> stablePathId,
            int source,
            int endpoint,
            Domain domain,
            Domain queryHorizon,
            double budget,
            List<Integer> selectedPivotArcIds) {
        private ReplayPrefixKey {
            stablePathId = List.copyOf(stablePathId);
            selectedPivotArcIds =
                    List.copyOf(selectedPivotArcIds);
        }
    }

    private record PendingReplay(
            ReplayRequest request,
            List<Integer> indices) {
    }

    private record PivotExpansion(
            Pivot pivot,
            Domain rootDomain,
            Domain entryDomain,
            double connectorBudget,
            String workItem) {
    }

    private record PivotConnector(
            PivotExpansion expansion,
            ConnectorResult result) {
    }

    private record StateKey(
            int endpoint,
            int depth,
            List<Integer> usedPivotRanks)
            implements Comparable<StateKey> {
        static StateKey of(
                int endpoint,
                int depth,
                BitSet used) {
            return new StateKey(
                    endpoint,
                    depth,
                    used.stream().boxed().toList());
        }

        private StateKey {
            usedPivotRanks = List.copyOf(usedPivotRanks);
        }

        BitSet usedPivots() {
            BitSet result = new BitSet();
            usedPivotRanks.forEach(result::set);
            return result;
        }

        @Override
        public int compareTo(StateKey other) {
            int comparison = Integer.compare(depth, other.depth);
            if (comparison == 0) {
                comparison = Integer.compare(endpoint, other.endpoint);
            }
            if (comparison != 0) {
                return comparison;
            }
            int common = Math.min(
                    usedPivotRanks.size(),
                    other.usedPivotRanks.size());
            for (int index = 0; index < common; index++) {
                comparison = Integer.compare(
                        usedPivotRanks.get(index),
                        other.usedPivotRanks.get(index));
                if (comparison != 0) {
                    return comparison;
                }
            }
            return Integer.compare(
                    usedPivotRanks.size(),
                    other.usedPivotRanks.size());
        }
    }

    private static final class MutableStats {
        private final long corridorNodes;
        private final long corridorEdges;
        private final long corridorCells;
        private final long scoreRelevantEdges;
        private final long selectedPivots;
        private final int requestedWorkers;
        private final AtomicInteger activeWorkers =
                new AtomicInteger();
        private final AtomicInteger maximumWorkers =
                new AtomicInteger();
        private final Set<Long> workerThreads =
                ConcurrentHashMap.newKeySet();
        private long connectorCalls;
        private long validConnectors;
        private long invalidConnectors;
        private long candidatesGenerated;
        private long candidatesRetained;
        private long residualBudgetRejections;
        private long scoreUpperBoundRejections;
        private long parallelTasksStarted;
        private long frontierCells;
        private long peakFrontierSize;

        MutableStats(
                QueryCorridor corridor,
                PivotIndex pivots,
                int requestedWorkers) {
            corridorNodes = corridor.vertexIds().size();
            corridorEdges = corridor.directedArcIds().size();
            corridorCells = corridor.activeCellIds().size();
            scoreRelevantEdges =
                    pivots.scoreRelevantArcIds().size();
            selectedPivots = pivots.selected().size();
            this.requestedWorkers = requestedWorkers;
        }

        void addConnector(ConnectorResult result) {
            validConnectors += result.connectors().size();
            invalidConnectors += result.invalidConnectors();
        }

        void workerEntered() {
            workerThreads.add(Thread.currentThread().getId());
            int active = activeWorkers.incrementAndGet();
            maximumWorkers.accumulateAndGet(active, Math::max);
        }

        void workerExited() {
            activeWorkers.decrementAndGet();
        }

        void observeFrontiers(
                Map<StateKey, IncrementalFrontier> states,
                IncrementalFrontier completed) {
            long cells = completed.cellCount();
            long peak = completed.peakSize();
            for (IncrementalFrontier frontier : states.values()) {
                cells += frontier.cellCount();
                peak = Math.max(peak, frontier.peakSize());
            }
            frontierCells = Math.max(frontierCells, cells);
            peakFrontierSize = Math.max(
                    peakFrontierSize, peak);
        }

        PaceGenerationStats snapshot(
                IncrementalFrontier completed,
                BoundedConnectorGenerator connectors,
                PaceWorkLedger ledger,
                String checksum) {
            peakFrontierSize = Math.max(
                    peakFrontierSize, completed.peakSize());
            frontierCells = Math.max(
                    frontierCells, completed.cellCount());
            return new PaceGenerationStats(
                    0,
                    scoreRelevantEdges,
                    selectedPivots,
                    validConnectors,
                    candidatesGenerated,
                    connectors.cacheHits(),
                    connectors.cacheMisses(),
                    parallelTasksStarted,
                    corridorNodes,
                    corridorEdges,
                    corridorCells,
                    scoreRelevantEdges,
                    selectedPivots,
                    connectorCalls,
                    ledger.connectorExpansions(),
                    validConnectors,
                    invalidConnectors,
                    ledger.connectorCapHits(),
                    candidatesGenerated,
                    candidatesRetained,
                    ledger.breakpointCapHits(),
                    ledger.queryWork(),
                    ledger.queryWorkCapHits(),
                    frontierCells,
                    peakFrontierSize,
                    connectors.cacheLookups(),
                    connectors.cacheWaits(),
                    requestedWorkers,
                    Math.max(
                            maximumWorkers.get(),
                            workerThreads.isEmpty() ? 0 : 1),
                    checksum);
        }
    }
}
