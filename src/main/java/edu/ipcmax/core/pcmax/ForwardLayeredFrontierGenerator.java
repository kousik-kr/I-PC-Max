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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
                Map<StateKey, IncrementalFrontier> next =
                        new TreeMap<>();
                outer:
                for (Map.Entry<StateKey, IncrementalFrontier> state :
                        current.entrySet()) {
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
                                    query,
                                    queryHorizon,
                                    pivots,
                                    ledger,
                                    partial,
                                    finalDomain,
                                    result,
                                    completed,
                                    stats);
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
                                    query,
                                    queryHorizon,
                                    pivots,
                                    ledger,
                                    partial,
                                    result,
                                    next,
                                    stats,
                                    executor);
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
            QuerySpec query,
            Domain queryHorizon,
            PivotIndex pivots,
            PaceWorkLedger ledger,
            PartialCandidate partial,
            Domain rootDomain,
            ConnectorResult connectors,
            IncrementalFrontier completed,
            MutableStats stats) {
        for (CandidateProfile connector : connectors.connectors()) {
            PathPointer pointer = PathPointer.concat(
                    partial.profile().pathPointer(),
                    connector.pathPointer());
            Optional<CandidateProfile> replayed = replay(
                    query,
                    queryHorizon,
                    pivots,
                    ledger,
                    pointer,
                    query.destination(),
                    rootDomain,
                    "complete");
            if (replayed.isEmpty()) {
                stats.invalidConnectors++;
                continue;
            }
            stats.candidatesGenerated++;
            if (completed.insert(
                    replayed.orElseThrow(),
                    "root:" + pointer.stablePathId())) {
                stats.candidatesRetained++;
            }
        }
    }

    private void reducePivot(
            QuerySpec query,
            Domain queryHorizon,
            PivotIndex pivots,
            PaceWorkLedger ledger,
            PartialCandidate partial,
            PivotConnector connectorResult,
            Map<StateKey, IncrementalFrontier> next,
            MutableStats stats,
            IPCMaxParallelExecutor executor) {
        PivotExpansion expansion = connectorResult.expansion();
        Pivot pivot = expansion.pivot();
        Edge pivotEdge = graph.edges().get(pivot.arcId());
        List<CandidateProfile> layerOffers =
                new ArrayList<>();
        for (CandidateProfile connector :
                connectorResult.result().connectors()) {
            PathPointer pointer = PathPointer.concat(
                    partial.profile().pathPointer(),
                    connector.pathPointer(),
                    PathPointer.arc(pivot.arcId()));
            if (!isSimple(
                    query.source(),
                    pointer,
                    pivotEdge.target())) {
                stats.invalidConnectors++;
                continue;
            }
            Optional<CandidateProfile> replayed = replay(
                    query,
                    queryHorizon,
                    pivots,
                    ledger,
                    pointer,
                    pivot.target(),
                    expansion.rootDomain(),
                    "pivot-" + pivot.arcId());
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
                        query,
                        pivot.target(),
                        ledger,
                        executor));
        stats.candidatesRetained += frontier.insertLayer(
                layerOffers,
                "layer=" + key.depth()
                        + ":pivot=" + pivot.arcId());
    }

    private Optional<CandidateProfile> replay(
            QuerySpec query,
            Domain queryHorizon,
            PivotIndex pivots,
            PaceWorkLedger ledger,
            PathPointer pointer,
            int endpoint,
            Domain domain,
            String context) {
        Optional<CandidateProfile> replayed =
                replayPath(
                        query,
                        queryHorizon,
                        pivots,
                        pointer,
                        endpoint,
                        domain);
        if (replayed.isEmpty()) {
            return replayed;
        }
        CandidateProfile flat = replayed.orElseThrow();
        int breakpoints =
                flat.arrivalProfile().breakpoints().size()
                + flat.scoreProfile().breakpoints().size();
        if (!ledger.acceptsBreakpoints(
                breakpoints,
                context + ":" + pointer.stablePathId())) {
            return Optional.empty();
        }
        return Optional.of(new CandidateProfile(
                flat.domain(),
                flat.arrivalProfile(),
                flat.scoreProfile(),
                pointer,
                flat.explicitAnchorCount(),
                flat.pivotId(),
                flat.compressed(),
                flat.usedPivotArcIds()));
    }

    private Optional<CandidateProfile> replayPath(
            QuerySpec query,
            Domain queryHorizon,
            PivotIndex pivots,
            PathPointer pointer,
            int endpoint,
            Domain domain) {
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.PATH_REPLAY)) {
            return CanonicalPathProfileBuilder.replay(
                    graph,
                    queryHorizon,
                    Set.copyOf(pivots.selectedArcIds()),
                    pointer.stablePathId(),
                    query.source(),
                    endpoint,
                    domain,
                    query.maxTravelTime(),
                    -1,
                    false);
        }
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
