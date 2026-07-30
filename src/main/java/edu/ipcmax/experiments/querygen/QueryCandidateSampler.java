package edu.ipcmax.experiments.querygen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.function.Function;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.DenseDijkstraLowerBoundOracle;
import edu.ipcmax.core.index.LowerBoundOracle;
import edu.ipcmax.core.loader.GeneratedGraphDataset;
import edu.ipcmax.core.loader.GeneratedGraphLoader;
import edu.ipcmax.core.pcmax.IPCMaxParallelExecutor;

/**
 * Deterministic lower-bound source/destination candidate sampling.
 * The configured maximum is a cap; sparse graphs may produce a smaller final pool.
 */
public final class QueryCandidateSampler {
    private static final int DISTANCE_BIN_COUNT = DistanceBin.values().length;
    private static final int UNEVALUATED_CORRIDOR_ANCHOR_COUNT = 0;
    public static final String DUPLICATE_PAIRS = "duplicate_pairs";
    public static final String SOURCE_EQUALS_DESTINATION = "source_equals_destination";
    public static final String UNREACHABLE = "unreachable";
    public static final String LOWER_BOUND_PATH_TOO_SHORT = "lower_bound_path_too_short";
    public static final String BELOW_MINIMUM_DISTANCE = "below_minimum_distance";
    public static final String SELECTED = "selected";
    private static final List<String> SAMPLING_EVENTS = List.of(
            DUPLICATE_PAIRS,
            SOURCE_EQUALS_DESTINATION,
            UNREACHABLE,
            LOWER_BOUND_PATH_TOO_SHORT,
            BELOW_MINIMUM_DISTANCE,
            SELECTED);

    private final GeneratedGraphLoader loader;
    private final Function<TDGraph, LowerBoundOracle> lowerBoundFactory;

    /** Uses the repository's generated-graph loader. */
    public QueryCandidateSampler() {
        this(new GeneratedGraphLoader(), DenseDijkstraLowerBoundOracle::new);
    }

    QueryCandidateSampler(GeneratedGraphLoader loader) {
        this(loader, DenseDijkstraLowerBoundOracle::new);
    }

    QueryCandidateSampler(
            GeneratedGraphLoader loader,
            Function<TDGraph, LowerBoundOracle> lowerBoundFactory) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.lowerBoundFactory = Objects.requireNonNull(
                lowerBoundFactory, "lowerBoundFactory");
    }

    /**
     * Loads {@code dataRoot/datasetId}, computes its checksum, and samples its candidate pool.
     * Phase 3 always excludes unreachable pairs. Anchor-corridor evaluation is deferred, so
     * {@code requireAnchorCorridor} is not applied and the candidate anchor count remains zero.
     */
    public SamplingResult sample(
            Path dataRoot,
            String datasetId,
            long globalSeed,
            QueryGenerationConfig.CandidatePool configuration) throws IOException {
        Objects.requireNonNull(dataRoot, "dataRoot");
        String normalizedDataset = ManifestChecksum.normalizeDatasetId(datasetId);
        GeneratedGraphDataset dataset = loader.load(dataRoot.resolve(normalizedDataset));
        return sample(dataset, normalizedDataset, globalSeed, configuration);
    }

    /** Computes checksum metadata for an already loaded generated dataset and samples it. */
    public SamplingResult sample(
            GeneratedGraphDataset dataset,
            String datasetId,
            long globalSeed,
            QueryGenerationConfig.CandidatePool configuration) throws IOException {
        Objects.requireNonNull(dataset, "dataset");
        String checksum = ManifestChecksum.graphChecksum(dataset.directory());
        return sample(dataset.graph(), datasetId, checksum, globalSeed, configuration);
    }

    /**
     * Samples an already materialized graph. This overload keeps tiny-graph tests independent of file loading.
     */
    public SamplingResult sample(
            TDGraph graph,
            String datasetId,
            String graphChecksum,
            long globalSeed,
            QueryGenerationConfig.CandidatePool configuration) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(configuration, "configuration");
        configuration.validate();
        String normalizedDataset = ManifestChecksum.normalizeDatasetId(datasetId);
        long datasetSeed = ManifestChecksum.deriveDatasetSeed(globalSeed, normalizedDataset, graphChecksum);

        List<Integer> eligibleSources = eligibleSourceIds(graph);
        if (configuration.sampledSources() > eligibleSources.size()) {
            throw new IllegalArgumentException("requested " + configuration.sampledSources()
                    + " sampled sources but graph has only " + eligibleSources.size()
                    + " eligible source vertices");
        }
        List<Integer> sampledSources = sampleWithoutReplacement(
                eligibleSources, configuration.sampledSources(), datasetSeed);

        LowerBoundOracle lowerBound = lowerBoundFactory.apply(graph);
        List<LowerBoundOracle.Labels> labelsBySource =
                lowerBoundLabels(
                        lowerBound,
                        sampledSources,
                        graph.nodeCount());
        List<Integer> destinations = graph.nodeIds();
        List<QueryPairCandidate> accepted = new ArrayList<>(configuration.maximumPairs());
        TreeSet<OrderedPair> seen = new TreeSet<>();
        TreeMap<String, Long> eventCounts = emptyEventCounts();
        long pairsExamined = 0;
        int shortestPathRuns = sampledSources.size();

        for (int sampledSourceIndex = 0; sampledSourceIndex < sampledSources.size(); sampledSourceIndex++) {
            int source = sampledSources.get(sampledSourceIndex);
            LowerBoundOracle.Labels distances =
                    labelsBySource.get(sampledSourceIndex);

            int remainingSources = sampledSources.size() - sampledSourceIndex;
            int remainingCapacity = configuration.maximumPairs() - accepted.size();
            int sourceQuota = remainingCapacity == 0
                    ? 0
                    : ceilingDivide(remainingCapacity, remainingSources);
            int sourceAccepted = 0;
            DestinationPermutation permutation = DestinationPermutation.forSource(
                    destinations.size(), datasetSeed, source, sampledSourceIndex);
            while (sourceAccepted < sourceQuota && permutation.hasNext()) {
                int destination = destinations.get(permutation.nextIndex());
                pairsExamined++;
                if (source == destination) {
                    increment(eventCounts, SOURCE_EQUALS_DESTINATION);
                    continue;
                }
                if (!distances.reached(destination)) {
                    increment(eventCounts, UNREACHABLE);
                    continue;
                }
                OrderedPair pair = new OrderedPair(source, destination);
                if (!seen.add(pair)) {
                    increment(eventCounts, DUPLICATE_PAIRS);
                    continue;
                }
                int edgeCount = distances.edgeCount(destination);
                double distance = Domain.canonicalTime(distances.distance(destination));
                if (edgeCount < configuration.minimumLowerBoundEdges()) {
                    increment(eventCounts, LOWER_BOUND_PATH_TOO_SHORT);
                    continue;
                }
                if (distance < configuration.minimumDistance()) {
                    increment(eventCounts, BELOW_MINIMUM_DISTANCE);
                    continue;
                }
                edu.ipcmax.core.validate.Path witness =
                        distances.witnessPath(destination);
                accepted.add(new QueryPairCandidate(
                        normalizedDataset,
                        source,
                        destination,
                        distance,
                        edgeCount,
                        UNEVALUATED_CORRIDOR_ANCHOR_COUNT,
                        sampledSourceIndex,
                        temporalFunctionComplexity(graph, witness),
                        witness.arcIds()));
                increment(eventCounts, SELECTED);
                sourceAccepted++;
            }
        }

        BinnedCandidates binned = assignDistanceBins(accepted);
        return new SamplingResult(
                normalizedDataset,
                graphChecksum.toLowerCase(java.util.Locale.ROOT),
                datasetSeed,
                eligibleSources.size(),
                sampledSources,
                shortestPathRuns,
                pairsExamined,
                binned.candidates(),
                binned.byDistanceBin(),
                binned.quartiles(),
                eventCounts);
    }

    private static List<LowerBoundOracle.Labels> lowerBoundLabels(
            LowerBoundOracle oracle,
            List<Integer> sources,
            int graphNodeCount) {
        List<Callable<LowerBoundOracle.Labels>> tasks =
                sources.stream()
                        .<Callable<LowerBoundOracle.Labels>>map(
                                source ->
                                        () -> oracle.distancesFrom(source))
                        .toList();
        int workers = lowerBoundWorkerCount(
                tasks.size(),
                graphNodeCount,
                Runtime.getRuntime().availableProcessors());
        try (IPCMaxParallelExecutor executor =
                new IPCMaxParallelExecutor(workers)) {
            return executor.invokeAllDeterministic(tasks);
        } catch (IllegalStateException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null
                    && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            throw new IllegalStateException(
                    "deterministic lower-bound candidate sampling failed: "
                            + cause.getClass().getSimpleName()
                            + (cause.getMessage() == null
                                ? ""
                                : ": " + cause.getMessage()),
                    failure);
        }
    }

    /**
     * Bounds simultaneous dense Dijkstra priority queues on large graphs.
     * Results are reduced in source order, so this affects resource use only,
     * never sampled pairs, witnesses, IDs, or manifest bytes.
     */
    static int lowerBoundWorkerCount(
            int taskCount,
            int graphNodeCount,
            int availableProcessors) {
        if (taskCount < 0
                || graphNodeCount < 0
                || availableProcessors < 1) {
            throw new IllegalArgumentException(
                    "invalid lower-bound worker sizing input");
        }
        int memoryBound = graphNodeCount >= 10_000_000
                ? 2
                : graphNodeCount >= 1_000_000
                    ? 4
                    : 24;
        return Math.max(
                1,
                Math.min(
                        taskCount,
                        Math.min(
                                memoryBound,
                                Math.min(24, availableProcessors))));
    }

    private static long temporalFunctionComplexity(
            TDGraph graph,
            edu.ipcmax.core.validate.Path witness) {
        long complexity = 0;
        for (int arcId : witness.arcIds()) {
            complexity = Math.addExact(
                    complexity,
                    graph.edges().get(arcId).travelTimeFunction().breakpoints().size());
        }
        return complexity;
    }

    private static TreeMap<String, Long> emptyEventCounts() {
        TreeMap<String, Long> counts = new TreeMap<>();
        for (String event : SAMPLING_EVENTS) {
            counts.put(event, 0L);
        }
        return counts;
    }

    private static void increment(Map<String, Long> counts, String event) {
        counts.compute(event, (ignored, current) -> current == null ? 1L : current + 1L);
    }

    /** Canonically sorts a final pool and partitions ranks into Q1-Q4. */
    public static BinnedCandidates assignDistanceBins(List<QueryPairCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        List<QueryPairCandidate> sorted = new ArrayList<>(candidates.size());
        for (QueryPairCandidate candidate : candidates) {
            sorted.add(Objects.requireNonNull(candidate, "candidate"));
        }
        sorted.sort(QueryPairCandidate.CANONICAL_ORDER);

        EnumMap<DistanceBin, List<QueryPairCandidate>> mutableBins = new EnumMap<>(DistanceBin.class);
        for (DistanceBin bin : DistanceBin.values()) {
            mutableBins.put(bin, new ArrayList<>());
        }
        int size = sorted.size();
        for (int rank = 0; rank < size; rank++) {
            int binIndex = (int) ((long) DISTANCE_BIN_COUNT * rank / size);
            mutableBins.get(DistanceBin.values()[binIndex]).add(sorted.get(rank));
        }

        EnumMap<DistanceBin, List<QueryPairCandidate>> frozenBins = new EnumMap<>(DistanceBin.class);
        mutableBins.forEach((bin, values) -> frozenBins.put(bin, List.copyOf(values)));
        DistanceQuartiles quartiles = size == 0
                ? DistanceQuartiles.empty()
                : new DistanceQuartiles(
                        sorted.get(ceilingDivide(size, 4) - 1).lowerBoundDistance(),
                        sorted.get(ceilingDivide(size, 2) - 1).lowerBoundDistance(),
                        sorted.get((int) (((3L * size) + 3L) / 4L) - 1).lowerBoundDistance());
        return new BinnedCandidates(
                List.copyOf(sorted),
                Collections.unmodifiableMap(frozenBins),
                quartiles);
    }

    private static List<Integer> eligibleSourceIds(TDGraph graph) {
        List<Integer> result = new ArrayList<>();
        for (Integer nodeId : graph.nodeIds()) {
            if (!graph.outgoingEdges(nodeId).isEmpty()) {
                result.add(nodeId);
            }
        }
        return List.copyOf(result);
    }

    private static List<Integer> sampleWithoutReplacement(List<Integer> values, int count, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        TreeMap<Integer, Integer> replacements = new TreeMap<>();
        List<Integer> selected = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int remaining = values.size() - index;
            int draw = random.nextInt(remaining);
            int chosenIndex = replacements.getOrDefault(draw, draw);
            int finalIndex = remaining - 1;
            int replacement = replacements.getOrDefault(finalIndex, finalIndex);
            if (draw != finalIndex) {
                replacements.put(draw, replacement);
            }
            replacements.remove(finalIndex);
            selected.add(values.get(chosenIndex));
        }
        return List.copyOf(selected);
    }

    private static int ceilingDivide(int numerator, int denominator) {
        return (int) (((long) numerator + denominator - 1L) / denominator);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static int greatestCommonDivisor(int first, int second) {
        while (second != 0) {
            int remainder = first % second;
            first = second;
            second = remainder;
        }
        return first;
    }

    private record OrderedPair(int source, int destination) implements Comparable<OrderedPair> {
        @Override
        public int compareTo(OrderedPair other) {
            int sourceComparison = Integer.compare(source, other.source);
            return sourceComparison != 0
                    ? sourceComparison
                    : Integer.compare(destination, other.destination);
        }
    }

    private static final class DestinationPermutation {
        private final int size;
        private final int start;
        private final int step;
        private int rank;

        private DestinationPermutation(int size, int start, int step) {
            this.size = size;
            this.start = start;
            this.step = step;
        }

        static DestinationPermutation forSource(int size, long seed, int source, int sampledSourceIndex) {
            if (size <= 0) {
                return new DestinationPermutation(0, 0, 1);
            }
            long sourceSeed = mix64(seed ^ ((long) source << 32) ^ sampledSourceIndex);
            int start = (int) Math.floorMod(sourceSeed, (long) size);
            if (size == 1) {
                return new DestinationPermutation(size, start, 1);
            }
            int step = 1 + (int) Math.floorMod(mix64(sourceSeed), (long) size - 1L);
            while (greatestCommonDivisor(step, size) != 1) {
                step++;
                if (step == size) {
                    step = 1;
                }
            }
            return new DestinationPermutation(size, start, step);
        }

        boolean hasNext() {
            return rank < size;
        }

        int nextIndex() {
            if (!hasNext()) {
                throw new IllegalStateException("destination permutation is exhausted");
            }
            int result = (int) ((start + (long) rank * step) % size);
            rank++;
            return result;
        }
    }

    /** Actual empirical quartile values; all values are null for an empty candidate pool. */
    public record DistanceQuartiles(Double q25, Double q50, Double q75) {
        public DistanceQuartiles {
            boolean allNull = q25 == null && q50 == null && q75 == null;
            boolean allFinite = finite(q25) && finite(q50) && finite(q75);
            if (!allNull && !allFinite) {
                throw new IllegalArgumentException("quartile values must be all finite or all null");
            }
            if (allFinite && (q25 > q50 || q50 > q75)) {
                throw new IllegalArgumentException("quartile values must be nondecreasing");
            }
        }

        /** Empty-pool quartile reporting. */
        public static DistanceQuartiles empty() {
            return new DistanceQuartiles(null, null, null);
        }

        private static boolean finite(Double value) {
            return value != null && Double.isFinite(value);
        }
    }

    /** Canonical pool plus immutable rank-based distance-bin views. */
    public record BinnedCandidates(
            List<QueryPairCandidate> candidates,
            Map<DistanceBin, List<QueryPairCandidate>> byDistanceBin,
            DistanceQuartiles quartiles) {
        public BinnedCandidates {
            candidates = List.copyOf(candidates);
            EnumMap<DistanceBin, List<QueryPairCandidate>> bins = new EnumMap<>(DistanceBin.class);
            for (DistanceBin bin : DistanceBin.values()) {
                bins.put(bin, List.copyOf(byDistanceBin.getOrDefault(bin, List.of())));
            }
            byDistanceBin = Collections.unmodifiableMap(bins);
            Objects.requireNonNull(quartiles, "quartiles");
        }

        /** Candidates in canonical order for one rank-based bin. */
        public List<QueryPairCandidate> candidates(DistanceBin bin) {
            return byDistanceBin.get(Objects.requireNonNull(bin, "bin"));
        }
    }

    /** Fully audited result of sampling one graph. */
    public record SamplingResult(
            String datasetId,
            String graphChecksum,
            long datasetSeed,
            int eligibleSourceCount,
            List<Integer> sampledSources,
            int shortestPathRuns,
            long pairsExamined,
            List<QueryPairCandidate> candidates,
            Map<DistanceBin, List<QueryPairCandidate>> candidatesByDistanceBin,
            DistanceQuartiles quartiles,
            Map<String, Long> eventCounts) {
        public SamplingResult {
            if (datasetId == null || datasetId.isBlank() || graphChecksum == null || graphChecksum.isBlank()) {
                throw new IllegalArgumentException("sampling dataset and graph checksum are required");
            }
            if (eligibleSourceCount < 0 || shortestPathRuns < 0 || pairsExamined < 0) {
                throw new IllegalArgumentException("sampling counts cannot be negative");
            }
            sampledSources = List.copyOf(sampledSources);
            candidates = List.copyOf(candidates);
            EnumMap<DistanceBin, List<QueryPairCandidate>> bins = new EnumMap<>(DistanceBin.class);
            for (DistanceBin bin : DistanceBin.values()) {
                bins.put(bin, List.copyOf(candidatesByDistanceBin.getOrDefault(bin, List.of())));
            }
            candidatesByDistanceBin = Collections.unmodifiableMap(bins);
            Objects.requireNonNull(quartiles, "quartiles");
            eventCounts = immutableEventCounts(eventCounts);
        }

        /** Source-compatible constructor for Phase 3 callers without event counters. */
        public SamplingResult(
                String datasetId,
                String graphChecksum,
                long datasetSeed,
                int eligibleSourceCount,
                List<Integer> sampledSources,
                int shortestPathRuns,
                long pairsExamined,
                List<QueryPairCandidate> candidates,
                Map<DistanceBin, List<QueryPairCandidate>> candidatesByDistanceBin,
                DistanceQuartiles quartiles) {
            this(datasetId, graphChecksum, datasetSeed, eligibleSourceCount, sampledSources,
                    shortestPathRuns, pairsExamined, candidates, candidatesByDistanceBin,
                    quartiles, Map.of());
        }

        /** Candidates in canonical order for one rank-based bin. */
        public List<QueryPairCandidate> candidates(DistanceBin bin) {
            return candidatesByDistanceBin.get(Objects.requireNonNull(bin, "bin"));
        }

        /** Count for one exact snake-case sampling event identifier. */
        public long eventCount(String event) {
            Objects.requireNonNull(event, "event");
            Long count = eventCounts.get(event);
            if (count == null) {
                throw new IllegalArgumentException("unknown candidate-sampling event: " + event);
            }
            return count;
        }

        private static Map<String, Long> immutableEventCounts(Map<String, Long> values) {
            TreeMap<String, Long> counts = emptyEventCounts();
            if (values != null) {
                values.forEach((event, count) -> {
                    if (!counts.containsKey(event)) {
                        throw new IllegalArgumentException(
                                "unknown candidate-sampling event: " + event);
                    }
                    if (count == null || count < 0) {
                        throw new IllegalArgumentException(
                                "candidate-sampling event counts cannot be null or negative");
                    }
                    counts.put(event, count);
                });
            }
            return Collections.unmodifiableMap(counts);
        }
    }
}
