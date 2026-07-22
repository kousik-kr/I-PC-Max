package edu.ipcmax.experiments.querygen;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import edu.ipcmax.core.loader.GeneratedGraphDataset;
import edu.ipcmax.core.loader.GeneratedGraphLoader;
import edu.ipcmax.experiments.framework.QueryManifestEntry;

/**
 * Deterministically expands one generated graph into the Phase 5 PACE query sets.
 *
 * <p>Sets remain separate because sensitivity, appendix, and parallelism deliberately reuse
 * main-query IDs. A caller can therefore write each set as an independently valid manifest
 * without treating cross-set reuse as a duplicate.</p>
 */
public final class DatasetQueryGenerator {
    /** Stable generator version written into every schema-version-2 row. */
    public static final String GENERATOR_VERSION = "pace-querygen-v1";

    private static final String QUERY_SEED_DOMAIN = "PACE-QUERY-SEED-v1";
    private static final int REGIMES_PER_PAIR = TemporalRegime.values().length;

    private final GeneratedGraphLoader loader;
    private final QueryCandidateSampler sampler;

    /** Uses the repository's generated-graph loader and deterministic candidate sampler. */
    public DatasetQueryGenerator() {
        this(new GeneratedGraphLoader(), new QueryCandidateSampler());
    }

    DatasetQueryGenerator(GeneratedGraphLoader loader, QueryCandidateSampler sampler) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
    }

    /**
     * Loads and samples one dataset below {@code dataRoot}, then expands it into all query sets.
     */
    public DatasetQuerySets generate(
            java.nio.file.Path dataRoot,
            String datasetId,
            QueryGenerationConfig configuration,
            long globalSeed,
            String generatorConfigHash) throws IOException {
        Objects.requireNonNull(dataRoot, "dataRoot");
        Objects.requireNonNull(configuration, "configuration").validate();
        String normalizedDataset = ManifestChecksum.normalizeDatasetId(datasetId);
        GeneratedGraphDataset dataset = loader.load(dataRoot.resolve(normalizedDataset));
        String checksum = ManifestChecksum.graphChecksum(dataset.directory());
        QueryCandidateSampler.SamplingResult sampling = sampler.sample(
                dataset.graph(), normalizedDataset, checksum, globalSeed, configuration.candidatePool());
        return generate(dataset, normalizedDataset, checksum, generatorConfigHash,
                configuration, globalSeed, sampling);
    }

    /**
     * Expands an already loaded/sampled dataset. This is the testable in-memory Phase 5 API.
     */
    public DatasetQuerySets generate(
            GeneratedGraphDataset dataset,
            String datasetId,
            String graphChecksum,
            String generatorConfigHash,
            QueryGenerationConfig configuration,
            long globalSeed,
            QueryCandidateSampler.SamplingResult sampling) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(configuration, "configuration").validate();
        Objects.requireNonNull(sampling, "sampling");
        String normalizedDataset = ManifestChecksum.normalizeDatasetId(datasetId);
        requireText(graphChecksum, "graph checksum");
        requireText(generatorConfigHash, "generator config hash");
        if (!normalizedDataset.equals(ManifestChecksum.normalizeDatasetId(sampling.datasetId()))) {
            throw new IllegalArgumentException("sampling dataset does not match requested dataset: "
                    + sampling.datasetId() + " vs " + normalizedDataset);
        }
        if (!graphChecksum.equalsIgnoreCase(sampling.graphChecksum())) {
            throw new IllegalArgumentException("sampling graph checksum does not match requested checksum");
        }

        EnumMap<RejectionReason, Long> rejections = emptyRejectionCounts();
        mergeSamplerCounts(rejections, sampling.eventCounts());
        QueryBudgetBuilder budgets = new QueryBudgetBuilder(dataset, configuration);
        EnumMap<DistanceBin, BinSelection> selections = new EnumMap<>(DistanceBin.class);
        EnumMap<DistanceBin, Integer> candidateCounts = new EnumMap<>(DistanceBin.class);

        for (DistanceBin bin : DistanceBin.values()) {
            List<QueryPairCandidate> candidates = sampling.candidates(bin);
            candidateCounts.put(bin, candidates.size());
            selections.put(bin, selectBin(
                    bin, candidates, budgets, configuration, rejections));
        }

        BalanceReport preliminary = balanceReport(
                normalizedDataset, candidateCounts, selections, configuration, rejections);
        if (!preliminary.balanced()) {
            throw new BalanceException(preliminary);
        }

        MaterializedSets materialized = materialize(
                normalizedDataset,
                normalizedDatasetPath(dataset.directory()),
                graphChecksum.toLowerCase(Locale.ROOT),
                generatorConfigHash,
                sampling.datasetSeed(),
                configuration,
                selections,
                rejections);
        BalanceReport report = balanceReport(
                normalizedDataset, candidateCounts, selections, configuration, rejections,
                materialized.counts());
        if (!report.balanced()) {
            throw new BalanceException(report);
        }
        return new DatasetQuerySets(
                normalizedDataset,
                normalizedDatasetPath(dataset.directory()),
                graphChecksum.toLowerCase(Locale.ROOT),
                sampling.datasetSeed(),
                sampling.pairsExamined(),
                sampling.candidates().size(),
                materialized.main(),
                materialized.pilot(),
                materialized.sensitivity(),
                materialized.appendix(),
                materialized.parallelism(),
                materialized.tightBudget(),
                materialized.windowSensitivity(),
                materialized.budgetSensitivity(),
                rejectionIds(rejections),
                report);
    }

    private BinSelection selectBin(
            DistanceBin bin,
            List<QueryPairCandidate> candidates,
            QueryBudgetBuilder budgets,
            QueryGenerationConfig configuration,
            EnumMap<RejectionReason, Long> rejections) {
        List<CharacterizedCandidate> valid = new ArrayList<>();
        Set<OrderedPair> seenPairs = new TreeSet<>();
        int rejectedBaseFamilies = 0;
        for (QueryPairCandidate candidate : candidates) {
            OrderedPair ordered = new OrderedPair(candidate.source(), candidate.destination());
            if (!seenPairs.add(ordered)) {
                increment(rejections, RejectionReason.DUPLICATE_PAIRS);
                rejectedBaseFamilies++;
                continue;
            }
            QueryBudgetBuilder.FamilyBuildResult attempted = budgets.buildFamilyDetailed(candidate);
            if (!attempted.succeeded()) {
                increment(rejections, attempted.failure().orElseThrow().reasonId());
                rejectedBaseFamilies++;
                continue;
            }
            QueryBudgetBuilder.TemporalQueryFamily family = attempted.family().orElseThrow();
            int corridorScore = minimumMainCorridorCount(family);
            if (configuration.candidatePool().requireAnchorCorridor() && corridorScore <= 0) {
                increment(rejections, RejectionReason.NO_ANCHOR_CORRIDOR);
                rejectedBaseFamilies++;
                continue;
            }
            valid.add(new CharacterizedCandidate(candidate, family, corridorScore));
        }
        valid.sort(CANDIDATE_PREFERENCE);

        int sensitivityTarget = configuration.sensitivity().pairsPerDistanceBin();
        List<PreparedCandidate> sensitivityReady = new ArrayList<>();
        List<CharacterizedCandidate> sensitivityRejected = new ArrayList<>();
        for (CharacterizedCandidate candidate : valid) {
            if (sensitivityReady.size() >= sensitivityTarget) {
                break;
            }
            DerivativeAttempt derivatives = deriveSensitivityFamilies(candidate, budgets, configuration);
            if (derivatives.families().isPresent()) {
                sensitivityReady.add(new PreparedCandidate(candidate, derivatives.families().orElseThrow()));
            } else {
                increment(rejections, derivatives.failure().orElseThrow().reasonId());
                sensitivityRejected.add(candidate);
            }
        }

        Set<OrderedPair> mainPairs = new TreeSet<>();
        List<SelectedPair> main = new ArrayList<>();
        for (PreparedCandidate candidate : sensitivityReady) {
            if (main.size() >= configuration.main().pairsPerDistanceBin()) {
                break;
            }
            mainPairs.add(new OrderedPair(candidate.characterized().candidate().source(),
                    candidate.characterized().candidate().destination()));
            main.add(new SelectedPair(bin, main.size() + 1, candidate.characterized(), candidate.derivatives()));
        }
        for (CharacterizedCandidate candidate : valid) {
            if (main.size() >= configuration.main().pairsPerDistanceBin()) {
                break;
            }
            OrderedPair ordered = new OrderedPair(candidate.candidate().source(), candidate.candidate().destination());
            if (mainPairs.add(ordered)) {
                main.add(new SelectedPair(bin, main.size() + 1, candidate, null));
            }
        }

        Set<OrderedPair> selectedPairs = new TreeSet<>(mainPairs);
        List<SelectedPair> pilot = new ArrayList<>();
        for (CharacterizedCandidate candidate : valid) {
            if (pilot.size() >= configuration.pilot().pairsPerDistanceBin()) {
                break;
            }
            OrderedPair ordered = new OrderedPair(candidate.candidate().source(), candidate.candidate().destination());
            if (selectedPairs.add(ordered)) {
                pilot.add(new SelectedPair(
                        bin,
                        configuration.main().pairsPerDistanceBin() + pilot.size() + 1,
                        candidate,
                        null));
            }
        }

        int requestedPairs = configuration.main().pairsPerDistanceBin()
                + configuration.pilot().pairsPerDistanceBin();
        int selectedOutsideInitialPool = 0;
        for (SelectedPair selected : concatenate(main, pilot)) {
            if (candidatePosition(candidates, selected.candidate()) >= requestedPairs) {
                selectedOutsideInitialPool++;
            }
        }
        int replacementCount = Math.min(selectedOutsideInitialPool,
                rejectedBaseFamilies + sensitivityRejected.size());
        for (int index = 0; index < replacementCount; index++) {
            increment(rejections, RejectionReason.REPLACEMENT_SELECTED);
        }
        for (int index = 0; index < main.size() + pilot.size(); index++) {
            increment(rejections, RejectionReason.SELECTED);
        }
        return new BinSelection(main, pilot, sensitivityReady.size(), rejectedBaseFamilies,
                sensitivityRejected.size());
    }

    private static List<SelectedPair> concatenate(
            List<SelectedPair> main, List<SelectedPair> pilot) {
        List<SelectedPair> result = new ArrayList<>(main.size() + pilot.size());
        result.addAll(main);
        result.addAll(pilot);
        return result;
    }

    private static int candidatePosition(
            List<QueryPairCandidate> candidates, QueryPairCandidate target) {
        for (int index = 0; index < candidates.size(); index++) {
            QueryPairCandidate candidate = candidates.get(index);
            if (candidate.source() == target.source()
                    && candidate.destination() == target.destination()) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }

    private DerivativeAttempt deriveSensitivityFamilies(
            CharacterizedCandidate candidate,
            QueryBudgetBuilder budgets,
            QueryGenerationConfig configuration) {
        List<WindowFamily> windows = new ArrayList<>();
        for (int window : configuration.windowSensitivity().valuesMinutes()) {
            QueryBudgetBuilder.FamilyBuildResult result = budgets.buildFamilyDetailed(
                    candidate.candidate(), window, configuration.main().budgetSlack());
            if (!result.succeeded()) {
                return DerivativeAttempt.failure(result.failure().orElseThrow());
            }
            windows.add(new WindowFamily(window, result.family().orElseThrow()));
        }

        List<SlackFamily> slacks = new ArrayList<>();
        for (double slack : configuration.budgetSensitivity().slackValues()) {
            QueryBudgetBuilder.FamilyBuildResult result = budgets.buildFamilyDetailed(
                    candidate.candidate(),
                    configuration.main().windowMinutes(),
                    slack);
            if (!result.succeeded()) {
                return DerivativeAttempt.failure(result.failure().orElseThrow());
            }
            slacks.add(new SlackFamily(slack, result.family().orElseThrow()));
        }
        return DerivativeAttempt.success(new DerivedFamilies(windows, slacks));
    }

    private MaterializedSets materialize(
            String datasetId,
            String datasetPath,
            String graphChecksum,
            String configHash,
            long datasetSeed,
            QueryGenerationConfig configuration,
            Map<DistanceBin, BinSelection> selections,
            EnumMap<RejectionReason, Long> rejections) {
        List<QueryManifestEntry> main = new ArrayList<>();
        List<QueryManifestEntry> pilot = new ArrayList<>();
        List<QueryManifestEntry> sensitivity = new ArrayList<>();
        List<QueryManifestEntry> appendix = new ArrayList<>();
        List<QueryManifestEntry> parallelism = new ArrayList<>();
        List<QueryManifestEntry> tight = new ArrayList<>();
        List<QueryManifestEntry> windows = new ArrayList<>();
        List<QueryManifestEntry> slacks = new ArrayList<>();
        Map<String, OrderedPair> queryFamilies = new TreeMap<>();

        for (DistanceBin bin : DistanceBin.values()) {
            BinSelection selection = selections.get(bin);
            for (SelectedPair selected : selection.main()) {
                String pairFamilyId = pairFamilyId(datasetId, bin, selected.index());
                for (TemporalRegime regime : TemporalRegime.values()) {
                    QueryBudgetBuilder.TemporalQueryBudget budget = selected.base().family().variant(regime);
                    String queryFamilyId = temporalFamilyId(pairFamilyId, regime);
                    registerQueryFamily(queryFamilies, queryFamilyId, selected.candidate());
                    QueryManifestEntry mainEntry = entry(
                            datasetId, datasetPath, graphChecksum, configHash, datasetSeed,
                            selected, pairFamilyId, queryFamilyId, budget, budget.main(),
                            mainQueryId(queryFamilyId, budget.windowLength(), budget.main().budgetSlack()));
                    main.add(mainEntry);
                    tight.add(entry(
                            datasetId, datasetPath, graphChecksum, configHash, datasetSeed,
                            selected, pairFamilyId, queryFamilyId, budget, budget.tight(),
                            tightQueryId(queryFamilyId, budget.windowLength())));

                    if (selected.index() <= configuration.sensitivity().pairsPerDistanceBin()) {
                        sensitivity.add(mainEntry);
                        if (selected.index() <= configuration.appendix().pairsPerDistanceBin()) {
                            appendix.add(mainEntry);
                        }
                        DerivedFamilies derivatives = Objects.requireNonNull(
                                selected.derivatives(), "sensitivity pair must have derived families");
                        for (WindowFamily window : derivatives.windows()) {
                            QueryBudgetBuilder.TemporalQueryBudget variant = window.family().variant(regime);
                            windows.add(entry(
                                    datasetId, datasetPath, graphChecksum, configHash, datasetSeed,
                                    selected, pairFamilyId, queryFamilyId, variant, variant.main(),
                                    mainQueryId(queryFamilyId, variant.windowLength(),
                                            variant.main().budgetSlack())));
                        }
                        for (SlackFamily slack : derivatives.slacks()) {
                            QueryBudgetBuilder.TemporalQueryBudget variant = slack.family().variant(regime);
                            slacks.add(entry(
                                    datasetId, datasetPath, graphChecksum, configHash, datasetSeed,
                                    selected, pairFamilyId, queryFamilyId, variant, variant.main(),
                                    mainQueryId(queryFamilyId, variant.windowLength(),
                                            variant.main().budgetSlack())));
                        }
                    }

                    if (configuration.parallelism().distanceBins().contains(bin)
                            && configuration.parallelism().temporalRegimes().contains(regime)
                            && selected.index() <= configuration.parallelism().pairsPerCell()) {
                        parallelism.add(mainEntry);
                    }
                }
            }
            for (SelectedPair selected : selection.pilot()) {
                String pairFamilyId = pairFamilyId(datasetId, bin, selected.index());
                for (TemporalRegime regime : TemporalRegime.values()) {
                    QueryBudgetBuilder.TemporalQueryBudget budget = selected.base().family().variant(regime);
                    String queryFamilyId = temporalFamilyId(pairFamilyId, regime);
                    registerQueryFamily(queryFamilies, queryFamilyId, selected.candidate());
                    pilot.add(entry(
                            datasetId, datasetPath, graphChecksum, configHash, datasetSeed,
                            selected, pairFamilyId, queryFamilyId, budget, budget.main(),
                            mainQueryId(queryFamilyId, budget.windowLength(), budget.main().budgetSlack())));
                }
            }
        }

        requireDistinctIds(main, "main", rejections);
        requireDistinctIds(pilot, "pilot", rejections);
        requireDistinctIds(sensitivity, "sensitivity", rejections);
        requireDistinctIds(appendix, "appendix", rejections);
        requireDistinctIds(parallelism, "parallelism", rejections);
        requireDistinctIds(tight, "tight_budget", rejections);
        requireDistinctIds(windows, "window_sensitivity", rejections);
        requireDistinctIds(slacks, "budget_sensitivity", rejections);
        return new MaterializedSets(main, pilot, sensitivity, appendix, parallelism, tight, windows, slacks);
    }

    private static QueryManifestEntry entry(
            String datasetId,
            String datasetPath,
            String graphChecksum,
            String configHash,
            long datasetSeed,
            SelectedPair selected,
            String pairFamilyId,
            String queryFamilyId,
            QueryBudgetBuilder.TemporalQueryBudget temporal,
            QueryBudgetBuilder.BudgetVariant variant,
            String queryId) {
        Map<String, Object> metadata = new TreeMap<>();
        metadata.put("sampled_source_index", selected.candidate().sampledSourceIndex());
        // Keep the potentially large aggregate as text so JSON round-trips do not narrow a
        // Java Long to an Integer inside the generic metadata map.
        metadata.put("temporal_function_complexity",
                Long.toString(selected.candidate().temporalFunctionComplexity()));
        metadata.put("selection_corridor_anchor_count", selected.base().corridorScore());
        QueryManifestEntry entry = QueryManifestEntry.version2(
                queryId,
                queryFamilyId,
                pairFamilyId,
                datasetId,
                datasetPath,
                graphChecksum,
                selected.candidate().source(),
                selected.candidate().destination(),
                selected.bin(),
                temporal.temporalRegime(),
                temporal.intervalStart(),
                temporal.intervalEnd(),
                temporal.windowLength(),
                variant.budget(),
                variant.budgetSlack(),
                variant.budgetPolicy(),
                selected.candidate().lowerBoundDistance(),
                selected.candidate().lowerBoundEdgeCount(),
                variant.corridorAnchorCount(),
                temporal.fastestTravelTimeMin(),
                temporal.fastestTravelTimeMax(),
                variant.expectedFullIntervalFeasible(),
                variant.expectedMixedFeasibility(),
                deriveQuerySeed(datasetSeed, queryId),
                GENERATOR_VERSION,
                configHash,
                metadata);
        entry.validate();
        return entry;
    }

    private static BalanceReport balanceReport(
            String datasetId,
            Map<DistanceBin, Integer> candidateCounts,
            Map<DistanceBin, BinSelection> selections,
            QueryGenerationConfig configuration,
            EnumMap<RejectionReason, Long> rejections) {
        return balanceReport(datasetId, candidateCounts, selections, configuration, rejections,
                predictedCounts(selections, configuration));
    }

    private static BalanceReport balanceReport(
            String datasetId,
            Map<DistanceBin, Integer> candidateCounts,
            Map<DistanceBin, BinSelection> selections,
            QueryGenerationConfig configuration,
            EnumMap<RejectionReason, Long> rejections,
            Map<QuerySet, Integer> actualCounts) {
        EnumMap<DistanceBin, Integer> mainPairs = new EnumMap<>(DistanceBin.class);
        EnumMap<DistanceBin, Integer> pilotPairs = new EnumMap<>(DistanceBin.class);
        for (DistanceBin bin : DistanceBin.values()) {
            BinSelection selection = selections.get(bin);
            mainPairs.put(bin, selection == null ? 0 : selection.main().size());
            pilotPairs.put(bin, selection == null ? 0 : selection.pilot().size());
        }
        return new BalanceReport(
                datasetId,
                candidateCounts,
                mainPairs,
                pilotPairs,
                configuration.main().pairsPerDistanceBin(),
                configuration.pilot().pairsPerDistanceBin(),
                expectedCounts(configuration),
                actualCounts,
                rejectionIds(rejections));
    }

    private static Map<QuerySet, Integer> expectedCounts(QueryGenerationConfig configuration) {
        int bins = DistanceBin.values().length;
        int regimes = REGIMES_PER_PAIR;
        EnumMap<QuerySet, Integer> counts = new EnumMap<>(QuerySet.class);
        int main = bins * configuration.main().pairsPerDistanceBin() * regimes;
        int sensitivity = bins * configuration.sensitivity().pairsPerDistanceBin() * regimes;
        counts.put(QuerySet.MAIN, main);
        counts.put(QuerySet.PILOT, bins * configuration.pilot().pairsPerDistanceBin() * regimes);
        counts.put(QuerySet.SENSITIVITY, sensitivity);
        counts.put(QuerySet.APPENDIX,
                bins * configuration.appendix().pairsPerDistanceBin() * regimes);
        counts.put(QuerySet.PARALLELISM,
                configuration.parallelism().distanceBins().size()
                        * configuration.parallelism().temporalRegimes().size()
                        * configuration.parallelism().pairsPerCell());
        counts.put(QuerySet.TIGHT_BUDGET, main);
        counts.put(QuerySet.WINDOW_SENSITIVITY,
                sensitivity * configuration.windowSensitivity().valuesMinutes().size());
        counts.put(QuerySet.BUDGET_SENSITIVITY,
                sensitivity * configuration.budgetSensitivity().slackValues().size());
        return counts;
    }

    private static Map<QuerySet, Integer> predictedCounts(
            Map<DistanceBin, BinSelection> selections,
            QueryGenerationConfig configuration) {
        int mainPairs = 0;
        int pilotPairs = 0;
        int sensitivityPairs = 0;
        int appendixPairs = 0;
        int parallelPairs = 0;
        for (DistanceBin bin : DistanceBin.values()) {
            BinSelection selection = selections.get(bin);
            if (selection == null) {
                continue;
            }
            mainPairs += selection.main().size();
            pilotPairs += selection.pilot().size();
            int sensitivityForBin = Math.min(selection.sensitivityReadyCount(), selection.main().size());
            sensitivityPairs += sensitivityForBin;
            appendixPairs += Math.min(sensitivityForBin,
                    configuration.appendix().pairsPerDistanceBin());
            if (configuration.parallelism().distanceBins().contains(bin)) {
                parallelPairs += Math.min(selection.main().size(), configuration.parallelism().pairsPerCell());
            }
        }
        EnumMap<QuerySet, Integer> counts = new EnumMap<>(QuerySet.class);
        counts.put(QuerySet.MAIN, mainPairs * REGIMES_PER_PAIR);
        counts.put(QuerySet.PILOT, pilotPairs * REGIMES_PER_PAIR);
        counts.put(QuerySet.SENSITIVITY, sensitivityPairs * REGIMES_PER_PAIR);
        counts.put(QuerySet.APPENDIX, appendixPairs * REGIMES_PER_PAIR);
        counts.put(QuerySet.PARALLELISM,
                parallelPairs * configuration.parallelism().temporalRegimes().size());
        counts.put(QuerySet.TIGHT_BUDGET, mainPairs * REGIMES_PER_PAIR);
        counts.put(QuerySet.WINDOW_SENSITIVITY,
                sensitivityPairs * REGIMES_PER_PAIR * configuration.windowSensitivity().valuesMinutes().size());
        counts.put(QuerySet.BUDGET_SENSITIVITY,
                sensitivityPairs * REGIMES_PER_PAIR * configuration.budgetSensitivity().slackValues().size());
        return counts;
    }

    private static int minimumMainCorridorCount(QueryBudgetBuilder.TemporalQueryFamily family) {
        int result = Integer.MAX_VALUE;
        for (TemporalRegime regime : TemporalRegime.values()) {
            result = Math.min(result, family.variant(regime).main().corridorAnchorCount());
        }
        return result == Integer.MAX_VALUE ? 0 : result;
    }

    private static void registerQueryFamily(
            Map<String, OrderedPair> queryFamilies,
            String queryFamilyId,
            QueryPairCandidate candidate) {
        OrderedPair current = new OrderedPair(candidate.source(), candidate.destination());
        OrderedPair previous = queryFamilies.putIfAbsent(queryFamilyId, current);
        if (previous != null && !previous.equals(current)) {
            throw new IllegalStateException("duplicate query family " + queryFamilyId);
        }
    }

    private static void requireDistinctIds(
            Collection<QueryManifestEntry> entries,
            String setName,
            EnumMap<RejectionReason, Long> rejections) {
        Set<String> ids = new TreeSet<>();
        for (QueryManifestEntry entry : entries) {
            if (!ids.add(entry.queryId())) {
                increment(rejections, RejectionReason.DUPLICATE_QUERY_FAMILY);
                throw new IllegalStateException("duplicate query ID in " + setName + ": " + entry.queryId());
            }
        }
    }

    /** Formats the Phase 5 indexed pair-family identifier. */
    public static String pairFamilyId(String datasetId, DistanceBin bin, int oneBasedIndex) {
        String normalized = ManifestChecksum.normalizeDatasetId(datasetId);
        Objects.requireNonNull(bin, "bin");
        if (oneBasedIndex <= 0 || oneBasedIndex > 999) {
            throw new IllegalArgumentException("pair-family index must be between 1 and 999");
        }
        return normalized + "-" + bin.name() + "-P" + String.format(Locale.ROOT, "%03d", oneBasedIndex);
    }

    /** Formats the Phase 5 temporal-family identifier. */
    public static String temporalFamilyId(String pairFamilyId, TemporalRegime regime) {
        requireText(pairFamilyId, "pair family id");
        return pairFamilyId + "-" + Objects.requireNonNull(regime, "regime").name();
    }

    /** Formats a full-interval main or sensitivity query ID. */
    public static String mainQueryId(String temporalFamilyId, int windowMinutes, double slack) {
        requireText(temporalFamilyId, "temporal family id");
        if (windowMinutes <= 0) {
            throw new IllegalArgumentException("window minutes must be positive");
        }
        return temporalFamilyId + "-W" + windowMinutes + "-RHO" + rhoToken(slack);
    }

    /** Formats a tight-budget derived query ID. */
    public static String tightQueryId(String temporalFamilyId, int windowMinutes) {
        requireText(temporalFamilyId, "temporal family id");
        if (windowMinutes <= 0) {
            throw new IllegalArgumentException("window minutes must be positive");
        }
        return temporalFamilyId + "-W" + windowMinutes + "-TIGHT";
    }

    /** Stable query-specific signed seed derived from the dataset seed and query ID. */
    public static long deriveQuerySeed(long datasetSeed, String queryId) {
        requireText(queryId, "query id");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateFramed(digest, QUERY_SEED_DOMAIN);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(datasetSeed).array());
            updateFramed(digest, queryId);
            return ByteBuffer.wrap(digest.digest()).getLong();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String rhoToken(double slack) {
        if (!Double.isFinite(slack) || slack < 0) {
            throw new IllegalArgumentException("slack must be finite and nonnegative");
        }
        try {
            int percentage = BigDecimal.valueOf(slack)
                    .movePointRight(2)
                    .setScale(0, RoundingMode.UNNECESSARY)
                    .intValueExact();
            if (percentage < 0 || percentage > 999) {
                throw new IllegalArgumentException("slack token must be between 0.00 and 9.99");
            }
            return String.format(Locale.ROOT, "%03d", percentage);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("slack must have at most two decimal places", failure);
        }
    }

    private static void updateFramed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String normalizedDatasetPath(java.nio.file.Path path) {
        return Objects.requireNonNull(path, "dataset directory")
                .normalize()
                .toString()
                .replace('\\', '/');
    }

    private static void mergeSamplerCounts(
            EnumMap<RejectionReason, Long> target,
            Map<String, Long> samplerCounts) {
        if (samplerCounts == null) {
            return;
        }
        for (Map.Entry<String, Long> entry : samplerCounts.entrySet()) {
            RejectionReason reason = RejectionReason.fromId(entry.getKey());
            if (reason != null && reason != RejectionReason.SELECTED) {
                target.put(reason, Math.addExact(target.get(reason), entry.getValue()));
            }
        }
    }

    private static EnumMap<RejectionReason, Long> emptyRejectionCounts() {
        EnumMap<RejectionReason, Long> result = new EnumMap<>(RejectionReason.class);
        for (RejectionReason reason : RejectionReason.values()) {
            result.put(reason, 0L);
        }
        return result;
    }

    private static Map<String, Long> rejectionIds(Map<RejectionReason, Long> counts) {
        TreeMap<String, Long> result = new TreeMap<>();
        for (RejectionReason reason : RejectionReason.values()) {
            result.put(reason.id(), counts.getOrDefault(reason, 0L));
        }
        return Collections.unmodifiableMap(result);
    }

    private static void increment(EnumMap<RejectionReason, Long> counts, RejectionReason reason) {
        counts.put(reason, Math.addExact(counts.get(reason), 1L));
    }

    private static void increment(EnumMap<RejectionReason, Long> counts, String reasonId) {
        RejectionReason reason = RejectionReason.fromId(reasonId);
        if (reason == null) {
            throw new IllegalArgumentException("unknown query-generation rejection reason: " + reasonId);
        }
        increment(counts, reason);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static final Comparator<CharacterizedCandidate> CANDIDATE_PREFERENCE = Comparator
            .comparingInt(CharacterizedCandidate::corridorScore).reversed()
            .thenComparing(Comparator.comparingInt(
                    (CharacterizedCandidate item) -> item.candidate().lowerBoundEdgeCount()).reversed())
            .thenComparing(Comparator.comparingLong(
                    (CharacterizedCandidate item) -> item.candidate().temporalFunctionComplexity()).reversed())
            .thenComparingInt(item -> item.candidate().source())
            .thenComparingInt(item -> item.candidate().destination());

    /** Names of independently writable generated query sets. */
    public enum QuerySet {
        MAIN("main"),
        PILOT("pilot"),
        SENSITIVITY("sensitivity"),
        APPENDIX("appendix"),
        PARALLELISM("parallelism"),
        TIGHT_BUDGET("tight_budget"),
        WINDOW_SENSITIVITY("window_sensitivity"),
        BUDGET_SENSITIVITY("budget_sensitivity");

        private final String id;

        QuerySet(String id) {
            this.id = id;
        }

        /** Stable lowercase query-set ID and output filename stem. */
        public String id() {
            return id;
        }
    }

    /** Required accounting keys, exposed in stable lowercase form. */
    public enum RejectionReason {
        DUPLICATE_PAIRS("duplicate_pairs"),
        SOURCE_EQUALS_DESTINATION("source_equals_destination"),
        UNREACHABLE("unreachable"),
        LOWER_BOUND_PATH_TOO_SHORT("lower_bound_path_too_short"),
        BELOW_MINIMUM_DISTANCE("below_minimum_distance"),
        NO_ANCHOR_CORRIDOR("no_anchor_corridor"),
        FASTEST_PROFILE_UNAVAILABLE("fastest_profile_unavailable"),
        FULL_INTERVAL_INFEASIBLE("full_interval_infeasible"),
        FUNCTION_HORIZON_EXCEEDED("function_horizon_exceeded"),
        TEMPORAL_REGIME_UNAVAILABLE("temporal_regime_unavailable"),
        BUDGET_INVALID("budget_invalid"),
        DUPLICATE_QUERY_FAMILY("duplicate_query_family"),
        REPLACEMENT_SELECTED("replacement_selected"),
        SELECTED("selected");

        private final String id;

        RejectionReason(String id) {
            this.id = id;
        }

        /** Stable report key. */
        public String id() {
            return id;
        }

        private static RejectionReason fromId(String id) {
            if (id == null) {
                return null;
            }
            for (RejectionReason reason : values()) {
                if (reason.id.equals(id)) {
                    return reason;
                }
            }
            return null;
        }
    }

    /** Immutable result containing every generated in-memory query set for one dataset. */
    public record DatasetQuerySets(
            String datasetId,
            String datasetPath,
            String graphChecksum,
            long datasetSeed,
            long pairsExamined,
            int candidatePoolSize,
            List<QueryManifestEntry> main,
            List<QueryManifestEntry> pilot,
            List<QueryManifestEntry> sensitivity,
            List<QueryManifestEntry> appendix,
            List<QueryManifestEntry> parallelism,
            List<QueryManifestEntry> tightBudget,
            List<QueryManifestEntry> windowSensitivity,
            List<QueryManifestEntry> budgetSensitivity,
            Map<String, Long> rejectionCounts,
            BalanceReport balanceReport) {
        public DatasetQuerySets {
            requireText(datasetId, "dataset id");
            requireText(datasetPath, "dataset path");
            requireText(graphChecksum, "graph checksum");
            if (pairsExamined < 0 || candidatePoolSize < 0) {
                throw new IllegalArgumentException("dataset sampling counts cannot be negative");
            }
            main = List.copyOf(main);
            pilot = List.copyOf(pilot);
            sensitivity = List.copyOf(sensitivity);
            appendix = List.copyOf(appendix);
            parallelism = List.copyOf(parallelism);
            tightBudget = List.copyOf(tightBudget);
            windowSensitivity = List.copyOf(windowSensitivity);
            budgetSensitivity = List.copyOf(budgetSensitivity);
            rejectionCounts = immutableStringCounts(rejectionCounts);
            Objects.requireNonNull(balanceReport, "balanceReport");
        }

        /** Number of query-set memberships, including intentional cross-set reuse. */
        public int membershipCount() {
            return main.size() + pilot.size() + sensitivity.size() + appendix.size()
                    + parallelism.size() + tightBudget.size() + windowSensitivity.size()
                    + budgetSensitivity.size();
        }

        /** Entries for a named set. */
        public List<QueryManifestEntry> queries(QuerySet set) {
            return switch (Objects.requireNonNull(set, "set")) {
                case MAIN -> main;
                case PILOT -> pilot;
                case SENSITIVITY -> sensitivity;
                case APPENDIX -> appendix;
                case PARALLELISM -> parallelism;
                case TIGHT_BUDGET -> tightBudget;
                case WINDOW_SENSITIVITY -> windowSensitivity;
                case BUDGET_SENSITIVITY -> budgetSensitivity;
            };
        }
    }

    /** Detailed deterministic balance diagnostics for success or failure. */
    public record BalanceReport(
            String datasetId,
            Map<DistanceBin, Integer> candidateCountsByBin,
            Map<DistanceBin, Integer> mainPairsByBin,
            Map<DistanceBin, Integer> pilotPairsByBin,
            int requiredMainPairsPerBin,
            int requiredPilotPairsPerBin,
            Map<QuerySet, Integer> expectedQueriesBySet,
            Map<QuerySet, Integer> actualQueriesBySet,
            Map<String, Long> rejectionCounts) {
        public BalanceReport {
            requireText(datasetId, "dataset id");
            candidateCountsByBin = immutableBinCounts(candidateCountsByBin);
            mainPairsByBin = immutableBinCounts(mainPairsByBin);
            pilotPairsByBin = immutableBinCounts(pilotPairsByBin);
            if (requiredMainPairsPerBin < 0 || requiredPilotPairsPerBin < 0) {
                throw new IllegalArgumentException("required pair counts cannot be negative");
            }
            expectedQueriesBySet = immutableSetCounts(expectedQueriesBySet);
            actualQueriesBySet = immutableSetCounts(actualQueriesBySet);
            rejectionCounts = immutableStringCounts(rejectionCounts);
        }

        /** True only when every pair quota and every query-set count is exact. */
        public boolean balanced() {
            for (DistanceBin bin : DistanceBin.values()) {
                if (mainPairsByBin.get(bin) == null || pilotPairsByBin.get(bin) == null
                        || mainPairsByBin.get(bin) != requiredMainPairsPerBin
                        || pilotPairsByBin.get(bin) != requiredPilotPairsPerBin) {
                    return false;
                }
            }
            return expectedQueriesBySet.equals(actualQueriesBySet);
        }

        @Override
        public String toString() {
            StringBuilder text = new StringBuilder("dataset ").append(datasetId)
                    .append(" balance: ");
            for (DistanceBin bin : DistanceBin.values()) {
                text.append(bin).append(" candidates=")
                        .append(candidateCountsByBin.get(bin))
                        .append(" main=").append(mainPairsByBin.get(bin))
                        .append('/').append(requiredMainPairsPerBin)
                        .append(" pilot=").append(pilotPairsByBin.get(bin))
                        .append('/').append(requiredPilotPairsPerBin).append("; ");
            }
            return text.append("expected=").append(expectedQueriesBySet)
                    .append(" actual=").append(actualQueriesBySet)
                    .append(" rejections=").append(rejectionCounts).toString();
        }
    }

    /** Thrown instead of silently reducing a required query-set count. */
    public static final class BalanceException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final BalanceReport report;

        public BalanceException(BalanceReport report) {
            super(Objects.requireNonNull(report, "report").toString());
            this.report = report;
        }

        /** Full deterministic quota and rejection report. */
        public BalanceReport report() {
            return report;
        }
    }

    private record OrderedPair(int source, int destination) implements Comparable<OrderedPair> {
        @Override
        public int compareTo(OrderedPair other) {
            int comparison = Integer.compare(source, other.source);
            return comparison != 0 ? comparison : Integer.compare(destination, other.destination);
        }
    }

    private record CharacterizedCandidate(
            QueryPairCandidate candidate,
            QueryBudgetBuilder.TemporalQueryFamily family,
            int corridorScore) {
    }

    private record PreparedCandidate(
            CharacterizedCandidate characterized,
            DerivedFamilies derivatives) {
    }

    private record SelectedPair(
            DistanceBin bin,
            int index,
            CharacterizedCandidate base,
            DerivedFamilies derivatives) {
        private QueryPairCandidate candidate() {
            return base.candidate();
        }
    }

    private record BinSelection(
            List<SelectedPair> main,
            List<SelectedPair> pilot,
            int sensitivityReadyCount,
            int rejectedBaseFamilies,
            int rejectedSensitivityFamilies) {
        private BinSelection {
            main = List.copyOf(main);
            pilot = List.copyOf(pilot);
        }
    }

    private record WindowFamily(int windowMinutes, QueryBudgetBuilder.TemporalQueryFamily family) {
    }

    private record SlackFamily(double slack, QueryBudgetBuilder.TemporalQueryFamily family) {
    }

    private record DerivedFamilies(List<WindowFamily> windows, List<SlackFamily> slacks) {
        private DerivedFamilies {
            windows = List.copyOf(windows);
            slacks = List.copyOf(slacks);
        }
    }

    private record DerivativeAttempt(
            Optional<DerivedFamilies> families,
            Optional<QueryBudgetBuilder.BuildFailure> failure) {
        private DerivativeAttempt {
            families = families == null ? Optional.empty() : families;
            failure = failure == null ? Optional.empty() : failure;
            if (families.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException("derivative attempt must have one outcome");
            }
        }

        private static DerivativeAttempt success(DerivedFamilies families) {
            return new DerivativeAttempt(Optional.of(families), Optional.empty());
        }

        private static DerivativeAttempt failure(QueryBudgetBuilder.BuildFailure failure) {
            return new DerivativeAttempt(Optional.empty(), Optional.of(failure));
        }
    }

    private record MaterializedSets(
            List<QueryManifestEntry> main,
            List<QueryManifestEntry> pilot,
            List<QueryManifestEntry> sensitivity,
            List<QueryManifestEntry> appendix,
            List<QueryManifestEntry> parallelism,
            List<QueryManifestEntry> tightBudget,
            List<QueryManifestEntry> windowSensitivity,
            List<QueryManifestEntry> budgetSensitivity) {
        private MaterializedSets {
            main = List.copyOf(main);
            pilot = List.copyOf(pilot);
            sensitivity = List.copyOf(sensitivity);
            appendix = List.copyOf(appendix);
            parallelism = List.copyOf(parallelism);
            tightBudget = List.copyOf(tightBudget);
            windowSensitivity = List.copyOf(windowSensitivity);
            budgetSensitivity = List.copyOf(budgetSensitivity);
        }

        private Map<QuerySet, Integer> counts() {
            EnumMap<QuerySet, Integer> result = new EnumMap<>(QuerySet.class);
            result.put(QuerySet.MAIN, main.size());
            result.put(QuerySet.PILOT, pilot.size());
            result.put(QuerySet.SENSITIVITY, sensitivity.size());
            result.put(QuerySet.APPENDIX, appendix.size());
            result.put(QuerySet.PARALLELISM, parallelism.size());
            result.put(QuerySet.TIGHT_BUDGET, tightBudget.size());
            result.put(QuerySet.WINDOW_SENSITIVITY, windowSensitivity.size());
            result.put(QuerySet.BUDGET_SENSITIVITY, budgetSensitivity.size());
            return result;
        }
    }

    private static Map<String, Long> immutableStringCounts(Map<String, Long> counts) {
        TreeMap<String, Long> copy = new TreeMap<>();
        if (counts != null) {
            counts.forEach((key, value) -> {
                if (key == null || value == null || value < 0) {
                    throw new IllegalArgumentException("count keys and nonnegative values are required");
                }
                copy.put(key, value);
            });
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<DistanceBin, Integer> immutableBinCounts(Map<DistanceBin, Integer> counts) {
        EnumMap<DistanceBin, Integer> copy = new EnumMap<>(DistanceBin.class);
        for (DistanceBin bin : DistanceBin.values()) {
            int value = counts == null ? 0 : counts.getOrDefault(bin, 0);
            if (value < 0) {
                throw new IllegalArgumentException("bin counts cannot be negative");
            }
            copy.put(bin, value);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<QuerySet, Integer> immutableSetCounts(Map<QuerySet, Integer> counts) {
        EnumMap<QuerySet, Integer> copy = new EnumMap<>(QuerySet.class);
        for (QuerySet set : QuerySet.values()) {
            int value = counts == null ? 0 : counts.getOrDefault(set, 0);
            if (value < 0) {
                throw new IllegalArgumentException("query-set counts cannot be negative");
            }
            copy.put(set, value);
        }
        return Collections.unmodifiableMap(copy);
    }
}
