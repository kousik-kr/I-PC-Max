package edu.ipcmax.experiments.querygen;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.index.DenseDijkstraLowerBoundOracle;
import edu.ipcmax.core.index.LowerBoundOracle;
import edu.ipcmax.core.index.QueryPreparationIndexes;
import edu.ipcmax.core.index.ScoreSupportIndex;
import edu.ipcmax.core.loader.GeneratedGraphDataset;
import edu.ipcmax.core.loader.GeneratedGraphLoader;
import edu.ipcmax.experiments.framework.QueryManifestEntry;
import edu.ipcmax.experiments.framework.QueryManifestIO;

/**
 * Generates the base pairs and derived query instances consumed by PACE Q1.
 *
 * <p>All graph-dependent work goes through {@link GeneratedGraphLoader},
 * {@link QueryCandidateSampler}, and the repository's lower-bound oracle.
 * Python only derives the checked study axes and invokes this class; it does
 * not parse or represent the graph.</p>
 */
public final class PaperQuerySetGenerator {
    public static final String GENERATOR_VERSION =
            "pace-paper-query-preparation-v4";
    public static final String BUDGET_EVIDENCE =
            "lower_bound_witness_path_grid_replay-v2";
    public static final String REQUIRED_BUDGET_DEFINITION =
            "GRID_LOWER_BOUND_WITNESS_PATH_TRAVEL_TIME";
    public static final String LOWER_BOUND_ROUTING_CONTRACT =
            "EXACT_DIJKSTRA_GLOBAL_EDGE_MINIMUM_DISTANCE_HOPS_ARC_ID-v1";
    public static final String WITNESS_IDENTITY_CONTRACT =
            "SHA256_BIG_ENDIAN_ARC_ID_SEQUENCE-v1";
    public static final String WITNESS_EVIDENCE_CONTRACT =
            "SHA256_BIG_ENDIAN_DEPARTURE_AND_CANONICAL_TRAVEL_TICKS-v1";
    public static final String BUDGET_DERIVATION_RULE =
            "B=canonical_time((1+rho)*T_hat_min,Delta);"
                    + "T_hat_min,Delta=max_grid_witness_travel_time";

    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private PaperQuerySetGenerator() {
    }

    /** Command-line entry point: {@code --spec spec.json --output queries.jsonl}. */
    public static void main(String[] arguments) {
        int status = execute(arguments);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int execute(String... arguments) {
        try {
            Arguments parsed = Arguments.parse(arguments);
            GenerationSpec spec = JSON.readValue(
                    parsed.spec().toFile(), GenerationSpec.class);
            spec.validate();
            GenerationResult result = generate(spec);
            QueryManifestIO.write(parsed.output(), result.rows());
            System.out.println(JSON.writeValueAsString(result.summary()));
            return 0;
        } catch (Exception failure) {
            System.err.println(
                    "paper query generation failed: " + failure.getMessage());
            failure.printStackTrace(System.err);
            return 2;
        }
    }

    static GenerationResult generate(GenerationSpec spec) throws IOException {
        QueryGenerationConfig queryConfiguration =
                QueryGenerationConfig.load(spec.queryConfiguration());
        BaseGeneration base = generateBase(spec, queryConfiguration);
        List<QueryManifestEntry> rows = new ArrayList<>(base.rows());
        Map<String, DatasetChecksums> checksums = new TreeMap<>();
        checksums.put("base", base.checksums());
        TreeMap<Integer, ScoreSupportIndex> densityIndexes =
                new TreeMap<>();
        for (VariantSpec variant : spec.variants()) {
            VariantGeneration generated = generateVariant(
                    spec,
                    base.pairs(),
                    base.rows(),
                    base.checksums(),
                    variant);
            rows.addAll(generated.rows());
            checksums.put(variant.suffix(), generated.checksums());
            if ("score_density".equals(variant.kind())) {
                int density = Integer.parseInt(variant.value());
                if (densityIndexes.put(
                        density,
                        generated.indexes().scoreSupport()) != null) {
                    throw new IOException(
                            "duplicate score-density variant " + density);
                }
            }
        }
        if (!densityIndexes.isEmpty()) {
            Set<Integer> required = Set.of(5, 10, 20, 40);
            if (!densityIndexes.keySet().equals(required)) {
                throw new IOException(
                        "score-density indexes must define exactly "
                                + required + "; found "
                                + densityIndexes.keySet());
            }
            ScoreSupportIndex.requireNested(
                    new ArrayList<>(densityIndexes.values()));
        }
        rows.sort(Comparator.comparing(QueryManifestEntry::queryId));
        requireDistinctQueryIds(rows);
        Map<String, Integer> rowsBySplit = new TreeMap<>();
        for (QueryManifestEntry row : rows) {
            rowsBySplit.merge(
                    String.valueOf(row.metadata().get("split")),
                    1,
                    Integer::sum);
        }
        return new GenerationResult(
                List.copyOf(rows),
                new GenerationSummary(
                        spec.datasetId(),
                        rows.size(),
                        rowsBySplit,
                        checksums,
                        base.pairsExamined(),
                        base.candidatePoolSize(),
                        base.horizonSafeCandidatePoolSize(),
                        base.candidatePoolSize()
                                - base.horizonSafeCandidatePoolSize(),
                        base.pairs().size()));
    }

    private static BaseGeneration generateBase(
            GenerationSpec spec,
            QueryGenerationConfig queryConfiguration) throws IOException {
        GeneratedGraphDataset dataset = verifiedLoad(
                spec.datasetPath(), spec);
        DatasetChecksums checksums = checksums(dataset);
        QueryCandidateSampler.SamplingResult sampling =
                new QueryCandidateSampler().sample(
                        dataset.graph(),
                        spec.datasetId(),
                        checksums.datasetPayloadChecksum(),
                        spec.selectionSeed(),
                        queryConfiguration.candidatePool());
        List<QueryPairCandidate> horizonSafe =
                horizonSafeCandidates(
                        dataset, sampling.candidates(), spec);
        List<List<QueryPairCandidate>> bands = distanceBands(
                horizonSafe, spec.distanceBands());
        List<SelectedPair> pairs = selectDisjointPairs(spec, bands);
        List<QueryManifestEntry> rows = materializeBaseRows(
                spec, dataset, checksums, pairs);
        /*
         * Build the reusable query-side indexes after the memory-intensive
         * lower-bound sampling and witness replay. They are independently
         * verified here without inflating the peak live set of continental
         * query preparation.
         */
        QueryPreparationIndexes.build(dataset.graph());
        return new BaseGeneration(
                rows,
                pairs,
                checksums,
                sampling.pairsExamined(),
                sampling.candidates().size(),
                horizonSafe.size());
    }

    /**
     * Keeps only witnesses that are guaranteed to fit every configured query
     * cell. The path bound sums the maximum value of every edge's existing
     * canonical temporal function; it therefore cannot underestimate any
     * grid replay of that same witness. Distance bands are assigned only
     * after this deterministic safety filter.
     */
    private static List<QueryPairCandidate> horizonSafeCandidates(
            GeneratedGraphDataset dataset,
            List<QueryPairCandidate> candidates,
            GenerationSpec spec) {
        int latestIntervalEnd = Integer.MIN_VALUE;
        for (int center : spec.centers()) {
            latestIntervalEnd = Math.max(
                    latestIntervalEnd,
                    Math.addExact(
                            centeredStart(
                                    center,
                                    spec.defaultWindowMinutes()),
                            spec.defaultWindowMinutes()));
            for (int window : spec.windowMinutes()) {
                latestIntervalEnd = Math.max(
                        latestIntervalEnd,
                        Math.addExact(
                                centeredStart(center, window),
                                window));
            }
        }
        double maximumOverhead = spec.budgetOverheads().stream()
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(spec.defaultBudgetOverhead());
        maximumOverhead = Math.max(
                maximumOverhead,
                spec.defaultBudgetOverhead());
        double supportEnd = dataset.manifest().temporalSupport()
                .orElseThrow(() -> new IllegalArgumentException(
                        "dataset lacks temporal support"))
                .endMinute();
        double maximumSafeBudget = Domain.canonicalTime(
                supportEnd - latestIntervalEnd);
        List<QueryPairCandidate> safe = new ArrayList<>();
        for (QueryPairCandidate candidate : candidates) {
            double pathUpperBound = 0.0;
            for (int arcId : candidate.lowerBoundWitnessArcIds()) {
                Edge edge = dataset.graph().edges().get(arcId);
                double edgeMaximum = edge.travelTimeFunction()
                        .breakpoints().stream()
                        .mapToDouble(
                                breakpoint -> breakpoint.value())
                        .max()
                        .orElseThrow();
                pathUpperBound = Domain.canonicalTime(
                        pathUpperBound + edgeMaximum);
            }
            double maximumBudget = Domain.canonicalTime(
                    (1.0 + maximumOverhead) * pathUpperBound);
            if (maximumBudget <= maximumSafeBudget) {
                safe.add(candidate);
            }
        }
        return List.copyOf(safe);
    }

    private static VariantGeneration generateVariant(
            GenerationSpec spec,
            List<SelectedPair> pairs,
            List<QueryManifestEntry> baseRows,
            DatasetChecksums baseChecksums,
            VariantSpec variant) throws IOException {
        GeneratedGraphDataset dataset = verifiedLoad(variant.path(), spec);
        DatasetChecksums checksums = checksums(dataset);
        if (!checksums.datasetChecksum().equals(
                baseChecksums.datasetChecksum())) {
            throw new IOException(
                    variant.path()
                            + ": variant graph structure does not match base "
                            + spec.datasetPath());
        }
        List<SelectedPair> variantPairs = pairs.stream()
                .filter(pair -> "evaluation".equals(pair.split())
                        && pair.pairIndex() <= variant.maximumPairs())
                .toList();
        double variantOverhead = spec.budgetOverheads().stream()
                .mapToDouble(Double::doubleValue)
                .min()
                .orElseThrow();
        boolean sameTravelPayload = Files.mismatch(
                spec.datasetPath().resolve(
                        "travel_time_functions.jsonl.gz"),
                variant.path().resolve(
                        "travel_time_functions.jsonl.gz")) == -1;
        boolean reuseDefaultBudget = sameTravelPayload
                && Domain.sameTime(
                        variantOverhead, spec.defaultBudgetOverhead());
        Map<DefaultBudgetKey, GridBudget> reusedBudgets =
                reuseDefaultBudget
                        ? defaultBudgetMap(spec, baseRows)
                        : Map.of();
        GridWitnessBudgetStore computedBudgets =
                reuseDefaultBudget
                        ? null
                        : new GridWitnessBudgetStore(
                                dataset,
                                variantPairs,
                                spec.evaluationGridMinutes(),
                                variantCells(spec, variantOverhead),
                                false);
        List<QueryManifestEntry> rows = new ArrayList<>();
        for (SelectedPair pair : variantPairs) {
            for (int center : spec.centers()) {
                Cell cell = new Cell(
                        center,
                        spec.defaultWindowMinutes(),
                        variantOverhead);
                GridBudget temporal = reuseDefaultBudget
                        ? reusedBudgets.get(new DefaultBudgetKey(
                                pair.pairIndex(), center))
                        : computedBudgets.build(
                                pair.candidate(), cell);
                if (temporal == null) {
                    throw new IOException(
                            "missing reusable default budget for evaluation "
                                    + "pair " + pair.pairIndex()
                                    + " at center " + center);
                }
                rows.add(entry(
                        spec,
                        pair,
                        temporal,
                        cell,
                        variant.suffix(),
                        dataset,
                        checksums,
                        variant.path(),
                        variant));
            }
        }
        QueryPreparationIndexes indexes =
                QueryPreparationIndexes.build(dataset.graph());
        return new VariantGeneration(
                List.copyOf(rows), checksums, indexes);
    }

    private static Map<DefaultBudgetKey, GridBudget> defaultBudgetMap(
            GenerationSpec spec,
            List<QueryManifestEntry> baseRows) {
        Map<DefaultBudgetKey, GridBudget> result =
                new LinkedHashMap<>();
        for (QueryManifestEntry row : baseRows) {
            Map<String, Object> metadata = row.metadata();
            if (!"evaluation".equals(metadata.get("split"))
                    || row.windowLength()
                            != spec.defaultWindowMinutes()
                    || !Domain.sameTime(
                            row.budgetSlack(),
                            spec.defaultBudgetOverhead())) {
                continue;
            }
            int pairIndex = ((Number) metadata.get(
                    "pair_index")).intValue();
            int center = ((Number) metadata.get(
                    "interval_center")).intValue();
            GridBudget budget = new GridBudget(
                    row.intervalStart(),
                    row.intervalEnd(),
                    ((Number) metadata.get(
                            "witness_travel_time_min")).doubleValue(),
                    ((Number) metadata.get(
                            "t_hat_min_delta")).doubleValue(),
                    row.budget(),
                    ((Number) metadata.get(
                            "grid_departure_count")).intValue(),
                    ((Number) metadata.get(
                            "witness_path_lower_bound_distance")).doubleValue(),
                    ((Number) metadata.get(
                            "witness_path_edge_count")).intValue(),
                    String.valueOf(metadata.get(
                            "witness_path_checksum_sha256")),
                    String.valueOf(metadata.get(
                            "witness_travel_time_evidence_sha256")));
            DefaultBudgetKey key =
                    new DefaultBudgetKey(pairIndex, center);
            if (result.put(key, budget) != null) {
                throw new IllegalArgumentException(
                        "duplicate default budget key: " + key);
            }
        }
        return Map.copyOf(result);
    }

    private static GeneratedGraphDataset verifiedLoad(
            Path path,
            GenerationSpec spec) throws IOException {
        return new GeneratedGraphLoader().loadVerified(
                path,
                spec.conversionContractVersion(),
                spec.requiredSupportEnd());
    }

    private static DatasetChecksums checksums(
            GeneratedGraphDataset dataset) throws IOException {
        String structural =
                ManifestChecksum.datasetChecksum(dataset.directory());
        String temporal =
                ManifestChecksum.temporalAttributeChecksum(dataset.directory());
        requireDeclaredChecksum(
                dataset.directory(),
                "dataset_checksum",
                dataset.manifest().datasetChecksum(),
                structural);
        requireDeclaredChecksum(
                dataset.directory(),
                "temporal_attribute_checksum",
                dataset.manifest().temporalAttributeChecksum(),
                temporal);
        return new DatasetChecksums(
                ManifestChecksum.graphChecksum(dataset.directory()),
                structural,
                temporal);
    }

    private static void requireDeclaredChecksum(
            Path directory,
            String name,
            Optional<String> declared,
            String actual) throws IOException {
        String value = declared.orElseThrow(() -> new IOException(
                directory + ": manifest does not declare " + name));
        if (!value.equals(actual)) {
            throw new IOException(
                    directory + ": manifest " + name + " " + value
                            + " does not match payload " + actual);
        }
    }

    private static List<List<QueryPairCandidate>> distanceBands(
            List<QueryPairCandidate> candidates,
            int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("distance band count must be positive");
        }
        List<QueryPairCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(QueryPairCandidate.CANONICAL_ORDER);
        List<List<QueryPairCandidate>> bands = new ArrayList<>(count);
        for (int band = 0; band < count; band++) {
            int start = (int) ((long) band * sorted.size() / count);
            int end = (int) ((long) (band + 1) * sorted.size() / count);
            bands.add(List.copyOf(sorted.subList(start, end)));
        }
        return List.copyOf(bands);
    }

    private static List<SelectedPair> selectDisjointPairs(
            GenerationSpec spec,
            List<List<QueryPairCandidate>> bands) throws IOException {
        Map<String, Integer> totals = Map.of(
                "pilot", spec.pilotPairs(),
                "warmup", spec.warmupPairs(),
                "evaluation", spec.evaluationPairs());
        List<SelectedPair> selected = new ArrayList<>();
        Set<EndpointPair> used = new HashSet<>();
        for (int band = 0; band < bands.size(); band++) {
            List<QueryPairCandidate> candidates = bands.get(band);
            for (String split : List.of("pilot", "warmup", "evaluation")) {
                int required = exactPerBand(
                        totals.get(split), spec.distanceBands(), split);
                List<QueryPairCandidate> ranked = new ArrayList<>(candidates);
                long seed = spec.seedFor(split);
                ranked.sort(Comparator
                        .comparingInt(
                                QueryPairCandidate::sampledSourceIndex)
                        .thenComparingLong(candidate ->
                                pairRank(seed, candidate))
                        .thenComparing(QueryPairCandidate.CANONICAL_ORDER));
                int admitted = 0;
                for (QueryPairCandidate candidate : ranked) {
                    EndpointPair endpoint = new EndpointPair(
                            candidate.source(), candidate.destination());
                    if (!used.add(endpoint)) {
                        continue;
                    }
                    int pairIndex = band * required + admitted + 1;
                    selected.add(new SelectedPair(
                            split, pairIndex, band + 1, candidate));
                    admitted++;
                    if (admitted == required) {
                        break;
                    }
                }
                if (admitted != required) {
                    throw new IOException(
                            spec.datasetId() + " distance band B" + (band + 1)
                                    + " has only " + admitted
                                    + " disjoint pairs for " + split
                                    + "; requires " + required);
                }
            }
        }
        return List.copyOf(selected);
    }

    private static long pairRank(long seed, QueryPairCandidate candidate) {
        MessageDigest digest = sha256();
        updateText(digest, "PACE-PAIR-SPLIT-RANK-v1");
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(seed).array());
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(candidate.source()).array());
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(candidate.destination()).array());
        return ByteBuffer.wrap(digest.digest()).getLong();
    }

    private static List<QueryManifestEntry> materializeBaseRows(
            GenerationSpec spec,
            GeneratedGraphDataset dataset,
            DatasetChecksums checksums,
            List<SelectedPair> pairs) throws IOException {
        GridWitnessBudgetStore budgets = new GridWitnessBudgetStore(
                dataset,
                pairs,
                spec.evaluationGridMinutes(),
                baseCells(spec),
                true);
        List<QueryManifestEntry> rows = new ArrayList<>();
        for (SelectedPair pair : pairs) {
            if ("evaluation".equals(pair.split())) {
                for (int center : spec.centers()) {
                    for (int window : spec.windowMinutes()) {
                        for (double overhead : spec.budgetOverheads()) {
                            Cell cell = new Cell(
                                    center,
                                    window,
                                    overhead);
                            rows.add(entry(
                                    spec, pair,
                                    budgets.build(pair.candidate(), cell), cell, "",
                                    dataset, checksums, spec.datasetPath(), null));
                        }
                    }
                }
            } else {
                for (int center : spec.centers()) {
                    Cell cell = new Cell(
                            center,
                            spec.defaultWindowMinutes(),
                            spec.defaultBudgetOverhead());
                    rows.add(entry(
                            spec, pair,
                            budgets.build(pair.candidate(), cell), cell, "",
                            dataset, checksums, spec.datasetPath(), null));
                }
            }
        }
        return List.copyOf(rows);
    }

    private static List<Cell> baseCells(GenerationSpec spec) {
        List<Cell> cells = new ArrayList<>();
        for (int center : spec.centers()) {
            for (int window : spec.windowMinutes()) {
                cells.add(new Cell(
                        center,
                        window,
                        spec.defaultBudgetOverhead()));
            }
        }
        return List.copyOf(cells);
    }

    private static List<Cell> defaultCells(GenerationSpec spec) {
        return spec.centers().stream()
                .map(center -> new Cell(
                        center,
                        spec.defaultWindowMinutes(),
                        spec.defaultBudgetOverhead()))
                .toList();
    }

    private static List<Cell> variantCells(
            GenerationSpec spec,
            double overhead) {
        return spec.centers().stream()
                .map(center -> new Cell(
                        center,
                        spec.defaultWindowMinutes(),
                        overhead))
                .toList();
    }

    private static QueryManifestEntry entry(
            GenerationSpec spec,
            SelectedPair selected,
            GridBudget temporal,
            Cell cell,
            String suffix,
            GeneratedGraphDataset dataset,
            DatasetChecksums checksums,
            Path datasetPath,
            VariantSpec variant) {
        String pairId = pairId(
                spec.datasetId(), selected.split(), selected.pairIndex());
        String queryId = queryId(pairId, cell, suffix);
        long splitSeed = spec.seedFor(selected.split());
        Map<String, Object> metadata = new TreeMap<>();
        metadata.put("budget_definition", spec.budgetDefinition());
        metadata.put("budget_derivation_rule", BUDGET_DERIVATION_RULE);
        metadata.put("budget_evidence", BUDGET_EVIDENCE);
        metadata.put("conversion_contract_version",
                spec.conversionContractVersion());
        metadata.put("dataset_checksum", checksums.datasetChecksum());
        metadata.put("dataset_path",
                datasetPath.toString().replace('\\', '/'));
        metadata.put("delta_minutes", spec.evaluationGridMinutes());
        metadata.put("evaluation_grid_minutes", spec.evaluationGridMinutes());
        metadata.put("distance_band", "B" + selected.band());
        metadata.put("function_support_end",
                dataset.manifest().temporalSupport().orElseThrow().endMinute());
        metadata.put("generation_contract", spec.contract());
        metadata.put("generator_config_hash", spec.generatorConfigHash());
        metadata.put("generator_version", GENERATOR_VERSION);
        metadata.put("dataset_payload_checksum",
                checksums.datasetPayloadChecksum());
        metadata.put("checksum_scope_version",
                "pace-explicit-dataset-checksum-scopes-v1");
        metadata.put("graph_seed", dataset.manifest().seed());
        metadata.put("interval_center", cell.timeCenter());
        metadata.put("time_center", cell.timeCenter());
        metadata.put("pair_id", pairId);
        metadata.put("pair_index", selected.pairIndex());
        metadata.put("rho", cell.budgetOverhead());
        metadata.put("selection_seed", spec.selectionSeed());
        metadata.put("split", selected.split());
        metadata.put("split_seed", splitSeed);
        metadata.put("t_hat_min_delta", temporal.tHatMinDelta());
        metadata.put("temporal_attribute_checksum",
                checksums.temporalAttributeChecksum());
        metadata.put("witness_travel_time_min", temporal.gridMinimum());
        metadata.put("witness_travel_time_max", temporal.tHatMinDelta());
        metadata.put("grid_departure_count", temporal.gridDepartureCount());
        metadata.put("lower_bound_routing_contract",
                LOWER_BOUND_ROUTING_CONTRACT);
        metadata.put("witness_identity_contract",
                WITNESS_IDENTITY_CONTRACT);
        metadata.put("witness_evidence_contract",
                WITNESS_EVIDENCE_CONTRACT);
        metadata.put("witness_path_checksum_sha256",
                temporal.witnessPathChecksum());
        metadata.put("witness_path_edge_count",
                temporal.witnessPathEdgeCount());
        metadata.put("witness_path_lower_bound_distance",
                temporal.witnessPathLowerBoundDistance());
        metadata.put("witness_travel_time_evidence_sha256",
                temporal.witnessEvidenceChecksum());
        metadata.put("witness_evaluated_departure_start",
                temporal.intervalStart());
        metadata.put("witness_evaluated_departure_end",
                temporal.intervalEnd());
        metadata.put("final_generated_budget", temporal.budget());
        metadata.put("validation_path_expected", true);
        metadata.put("validation_source_destination_present", true);
        if (variant != null) {
            metadata.put("variant_kind", variant.kind());
            metadata.put("variant_value", variant.value());
        }
        return new QueryManifestEntry(
                3,
                queryId,
                spec.datasetId(),
                selected.candidate().source(),
                selected.candidate().destination(),
                temporal.intervalStart(),
                temporal.intervalEnd(),
                cell.windowMinutes(),
                temporal.budget(),
                cell.budgetOverhead(),
                "full-interval-feasible",
                selected.band(),
                temporal.witnessPathLowerBoundDistance(),
                deriveQuerySeed(
                        splitSeed, queryId,
                        checksums.datasetPayloadChecksum()),
                metadata);
    }

    private static String pairId(
            String dataset,
            String split,
            int pairIndex) {
        return String.format(
                Locale.ROOT,
                "%s-%s-P%03d",
                dataset,
                switch (split) {
                    case "evaluation" -> "EVAL";
                    case "pilot" -> "PILOT";
                    case "warmup" -> "WARM";
                    default -> throw new IllegalArgumentException(
                            "unknown split: " + split);
                },
                pairIndex);
    }

    private static String queryId(
            String pairId,
            Cell cell,
            String suffix) {
        return String.format(
                Locale.ROOT,
                "%s-C%d-W%d-RHO%03d%s",
                pairId,
                cell.timeCenter(),
                cell.windowMinutes(),
                (int) Math.round(cell.budgetOverhead() * 100.0),
                suffix);
    }

    private static int exactPerBand(int total, int bands, String split) {
        if (total <= 0 || total % bands != 0) {
            throw new IllegalArgumentException(
                    split + " pair count " + total
                            + " must be positive and divisible by " + bands);
        }
        return total / bands;
    }

    private static int centeredStart(int center, int window) {
        if ((window & 1) != 0) {
            throw new IllegalArgumentException(
                    "centered query windows must have even lengths");
        }
        return Math.subtractExact(center, window / 2);
    }

    private static double budget(double tHatMinDelta, double rho) {
        if (!Double.isFinite(tHatMinDelta)
                || tHatMinDelta < 0
                || !Double.isFinite(rho)
                || rho < 0) {
            throw new IllegalArgumentException(
                    "budget inputs must be finite and nonnegative");
        }
        return Domain.canonicalTime(tHatMinDelta * (1.0 + rho));
    }

    private static long deriveQuerySeed(
            long splitSeed,
            String queryId,
            String graphChecksum) {
        MessageDigest digest = sha256();
        updateText(digest, "PACE-PAPER-QUERY-SEED-v2");
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(splitSeed).array());
        updateText(digest, queryId);
        updateText(digest, graphChecksum);
        return ByteBuffer.wrap(digest.digest()).getLong();
    }

    private static void updateText(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static void requireDistinctQueryIds(
            List<QueryManifestEntry> rows) {
        Set<String> ids = new TreeSet<>();
        for (QueryManifestEntry row : rows) {
            if (!ids.add(row.queryId())) {
                throw new IllegalArgumentException(
                        "duplicate query_id: " + row.queryId());
            }
        }
    }

    private static final class GridWitnessBudgetStore {
        private final GeneratedGraphDataset dataset;
        private final int delta;
        private final Map<EndpointPair, WitnessGridEvidence> evidenceByPair;

        GridWitnessBudgetStore(
                GeneratedGraphDataset dataset,
                List<SelectedPair> pairs,
                int delta,
                List<Cell> cells,
                boolean reuseCandidateWitnesses) throws IOException {
            this.dataset = Objects.requireNonNull(dataset, "dataset");
            if (delta <= 0) {
                throw new IllegalArgumentException("Delta must be positive");
            }
            this.delta = delta;
            evidenceByPair = prepare(
                    dataset,
                    Objects.requireNonNull(pairs, "pairs"),
                    delta,
                    Objects.requireNonNull(cells, "cells"),
                    reuseCandidateWitnesses);
        }

        GridBudget build(
                QueryPairCandidate pair,
                Cell cell) throws IOException {
            int start = centeredStart(
                    cell.timeCenter(), cell.windowMinutes());
            int end = Math.addExact(start, cell.windowMinutes());
            double supportEnd = dataset.manifest().temporalSupport()
                    .orElseThrow(() -> new IOException(
                            "dataset lacks temporal support"))
                    .endMinute();
            if (start < 0 || end > supportEnd) {
                throw new IOException(
                        "query departure interval is outside function support");
            }
            GridWitnessSummary summary = evaluate(pair, start, end);
            double queryBudget = budget(
                    summary.tHatMinDelta(), cell.budgetOverhead());
            if (Domain.canonicalTime(end + queryBudget) > supportEnd) {
                throw new IOException(
                        "query horizon exceeds function support: end=" + end
                                + ", budget=" + queryBudget
                                + ", support_end=" + supportEnd);
            }
            return new GridBudget(
                    start,
                    end,
                    summary.gridMinimum(),
                    summary.tHatMinDelta(),
                    queryBudget,
                    summary.gridDepartureCount(),
                    summary.witnessPathLowerBoundDistance(),
                    summary.witnessPathEdgeCount(),
                    summary.witnessPathChecksum(),
                    summary.witnessEvidenceChecksum());
        }

        private GridWitnessSummary evaluate(
                QueryPairCandidate pair,
                int start,
                int end) {
            WitnessGridEvidence evidence =
                    evidenceByPair.get(
                            new EndpointPair(
                                    pair.source(),
                                    pair.destination()));
            if (evidence == null) {
                throw new IllegalArgumentException(
                        "pair was not prepared: " + pair.source()
                                + "->" + pair.destination());
            }
            double minimum = Double.POSITIVE_INFINITY;
            double maximum = Double.NEGATIVE_INFINITY;
            int departures = 0;
            int departure = start;
            MessageDigest evidenceDigest = sha256();
            updateText(evidenceDigest, WITNESS_EVIDENCE_CONTRACT);
            updateText(evidenceDigest, evidence.pathChecksum());
            while (true) {
                Double travelTime =
                        evidence.travelByDeparture().get(departure);
                if (travelTime == null) {
                    throw new IllegalArgumentException(
                            "departure was not prepared: " + departure);
                }
                evidenceDigest.update(ByteBuffer.allocate(Integer.BYTES)
                        .putInt(departure).array());
                evidenceDigest.update(ByteBuffer.allocate(Long.BYTES)
                        .putLong(Domain.canonicalTick(travelTime)).array());
                minimum = Math.min(minimum, travelTime);
                maximum = Math.max(maximum, travelTime);
                departures++;
                if (departure == end) {
                    break;
                }
                int next = Math.min(end, Math.addExact(departure, delta));
                if (next == departure) {
                    throw new IllegalStateException(
                            "grid departure did not advance");
                }
                departure = next;
            }
            return new GridWitnessSummary(
                    Domain.canonicalTime(minimum),
                    Domain.canonicalTime(maximum),
                    departures,
                    evidence.lowerBoundDistance(),
                    evidence.path().arcIds().size(),
                    evidence.pathChecksum(),
                    hex(evidenceDigest.digest()));
        }

        private static Map<EndpointPair, WitnessGridEvidence> prepare(
                GeneratedGraphDataset dataset,
                List<SelectedPair> pairs,
                int delta,
                List<Cell> cells,
                boolean reuseCandidateWitnesses) throws IOException {
            TreeMap<Integer, Set<Integer>> destinationsBySource =
                    new TreeMap<>();
            for (SelectedPair selected : pairs) {
                QueryPairCandidate pair = selected.candidate();
                destinationsBySource.computeIfAbsent(
                        pair.source(), ignored -> new TreeSet<>())
                        .add(pair.destination());
            }
            TreeSet<Integer> departures = new TreeSet<>();
            for (Cell cell : cells) {
                int start = centeredStart(
                        cell.timeCenter(), cell.windowMinutes());
                int end = Math.addExact(start, cell.windowMinutes());
                int departure = start;
                while (true) {
                    departures.add(departure);
                    if (departure == end) {
                        break;
                    }
                    departure = Math.min(
                            end, Math.addExact(departure, delta));
                }
            }
            List<Integer> departureGrid = List.copyOf(departures);
            DenseDijkstraLowerBoundOracle lowerBoundOracle =
                    reuseCandidateWitnesses
                            ? null
                            : new DenseDijkstraLowerBoundOracle(
                                    dataset.graph());
            Map<EndpointPair, WitnessGridEvidence> values =
                    new LinkedHashMap<>();
            for (Map.Entry<Integer, Set<Integer>> sourceEntry
                    : destinationsBySource.entrySet()) {
                int source = sourceEntry.getKey();
                LowerBoundOracle.Labels labels =
                        reuseCandidateWitnesses
                                ? null
                                : lowerBoundOracle.distancesFrom(source);
                for (int destination : sourceEntry.getValue()) {
                    SelectedPair selected = pairs.stream()
                            .filter(item ->
                                    item.candidate().source() == source
                                    && item.candidate().destination()
                                        == destination)
                            .findFirst()
                            .orElseThrow();
                    if (!reuseCandidateWitnesses
                            && !labels.reached(destination)) {
                        throw new IOException(
                                "destination " + destination
                                        + " is unreachable from " + source
                                        + " in the lower-bound graph");
                    }
                    edu.ipcmax.core.validate.Path witness =
                            reuseCandidateWitnesses
                                    ? new edu.ipcmax.core.validate.Path(
                                            selected.candidate()
                                                .lowerBoundWitnessArcIds())
                                    : labels.witnessPath(destination);
                    validateWitness(
                            dataset, source, destination, witness);
                    String pathChecksum = pathChecksum(witness);
                    Map<Integer, Double> byDeparture =
                            new LinkedHashMap<>();
                    for (int departure : departureGrid) {
                        byDeparture.put(
                                departure,
                                replayWitness(
                                        dataset, witness, departure));
                    }
                    EndpointPair endpoint =
                            new EndpointPair(source, destination);
                    WitnessGridEvidence value =
                            new WitnessGridEvidence(
                                    witness,
                                    Domain.canonicalTime(
                                            reuseCandidateWitnesses
                                                    ? selected.candidate()
                                                        .lowerBoundDistance()
                                                    : labels.distance(
                                                        destination)),
                                    pathChecksum,
                                    Map.copyOf(byDeparture));
                    if (values.put(endpoint, value) != null) {
                        throw new IOException(
                                "duplicate prepared endpoint pair: "
                                        + endpoint);
                    }
                }
            }
            return Map.copyOf(values);
        }

        private static void validateWitness(
                GeneratedGraphDataset dataset,
                int source,
                int destination,
                edu.ipcmax.core.validate.Path witness) throws IOException {
            int current = source;
            for (int arcId : witness.arcIds()) {
                if (arcId < 0 || arcId >= dataset.graph().edgeCount()) {
                    throw new IOException(
                            "lower-bound witness contains invalid arc_id "
                                    + arcId);
                }
                Edge edge = dataset.graph().edges().get(arcId);
                if (edge.source() != current) {
                    throw new IOException(
                            "lower-bound witness is discontinuous at arc_id "
                                    + arcId);
                }
                current = edge.target();
            }
            if (current != destination || witness.arcIds().isEmpty()) {
                throw new IOException(
                        "lower-bound witness does not connect "
                                + source + " to " + destination);
            }
        }

        private static double replayWitness(
                GeneratedGraphDataset dataset,
                edu.ipcmax.core.validate.Path witness,
                int departure) throws IOException {
            double arrival = departure;
            try {
                for (int arcId : witness.arcIds()) {
                    arrival = dataset.graph().edges().get(arcId)
                            .travelTimeFunction().arrivalTimeAt(arrival);
                }
            } catch (IllegalArgumentException failure) {
                throw new IOException(
                        "lower-bound witness replay exceeded temporal "
                                + "function support at departure " + departure,
                        failure);
            }
            return Domain.canonicalTime(arrival - departure);
        }

        private static String pathChecksum(
                edu.ipcmax.core.validate.Path path) {
            MessageDigest digest = sha256();
            updateText(digest, WITNESS_IDENTITY_CONTRACT);
            for (int arcId : path.arcIds()) {
                digest.update(ByteBuffer.allocate(Integer.BYTES)
                        .putInt(arcId).array());
            }
            return hex(digest.digest());
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            value.append(String.format(Locale.ROOT, "%02x", current & 0xff));
        }
        return value.toString();
    }

    record Arguments(Path spec, Path output) {
        static Arguments parse(String... arguments) {
            Path spec = null;
            Path output = null;
            for (int index = 0; index < arguments.length; index++) {
                switch (arguments[index]) {
                    case "--spec" -> spec = Path.of(
                            value(arguments, ++index, "--spec"));
                    case "--output" -> output = Path.of(
                            value(arguments, ++index, "--output"));
                    default -> throw new IllegalArgumentException(
                            "unknown query-generation argument: "
                                    + arguments[index]);
                }
            }
            if (spec == null || output == null) {
                throw new IllegalArgumentException(
                        "--spec and --output are required");
            }
            return new Arguments(spec, output);
        }

        private static String value(
                String[] arguments,
                int index,
                String option) {
            if (index >= arguments.length || arguments[index].isBlank()) {
                throw new IllegalArgumentException(
                        option + " requires a value");
            }
            return arguments[index];
        }
    }

    public record GenerationSpec(
            int schemaVersion,
            String contract,
            String conversionContractVersion,
            String datasetId,
            Path datasetPath,
            Path queryConfiguration,
            String generatorConfigHash,
            long selectionSeed,
            Map<String, Long> splitSeeds,
            int distanceBands,
            int pilotPairs,
            int warmupPairs,
            int evaluationPairs,
            List<Integer> centers,
            List<Integer> windowMinutes,
            List<Double> budgetOverheads,
            int defaultWindowMinutes,
            double defaultBudgetOverhead,
            int evaluationGridMinutes,
            String budgetDefinition,
            int requiredSupportEnd,
            List<VariantSpec> variants) {
        public GenerationSpec {
            splitSeeds = splitSeeds == null ? Map.of() : Map.copyOf(splitSeeds);
            centers = centers == null ? List.of() : List.copyOf(centers);
            windowMinutes = windowMinutes == null
                    ? List.of() : List.copyOf(windowMinutes);
            budgetOverheads = budgetOverheads == null
                    ? List.of() : List.copyOf(budgetOverheads);
            variants = variants == null ? List.of() : List.copyOf(variants);
        }

        void validate() {
            if (schemaVersion != 2) {
                throw new IllegalArgumentException(
                        "unsupported generation spec schema: " + schemaVersion);
            }
            requireText(contract, "contract");
            requireText(conversionContractVersion,
                    "conversion_contract_version");
            requireText(datasetId, "dataset_id");
            Objects.requireNonNull(datasetPath, "dataset_path");
            Objects.requireNonNull(queryConfiguration, "query_configuration");
            requireText(generatorConfigHash, "generator_config_hash");
            requireText(budgetDefinition, "budget_definition");
            if (distanceBands != 5) {
                throw new IllegalArgumentException(
                        "PACE Q1 requires exactly five distance bands");
            }
            exactPerBand(pilotPairs, distanceBands, "pilot");
            exactPerBand(warmupPairs, distanceBands, "warmup");
            exactPerBand(evaluationPairs, distanceBands, "evaluation");
            if (!splitSeeds.keySet().containsAll(
                    List.of("pilot", "warmup", "evaluation"))) {
                throw new IllegalArgumentException(
                        "split_seeds must define every split");
            }
            requireDistinctPositive(centers, "centers");
            requireDistinctPositive(windowMinutes, "window_minutes");
            if (!windowMinutes.contains(defaultWindowMinutes)) {
                throw new IllegalArgumentException(
                        "default window is not a declared window");
            }
            if (budgetOverheads.isEmpty()
                    || budgetOverheads.stream().anyMatch(value ->
                            value == null
                                    || !Double.isFinite(value)
                                    || value < 0)
                    || budgetOverheads.stream().noneMatch(value ->
                            Domain.sameTime(
                                    value, defaultBudgetOverhead))) {
                throw new IllegalArgumentException(
                        "invalid budget overheads/default");
            }
            if (evaluationGridMinutes != 1) {
                throw new IllegalArgumentException(
                        "PACE Q1 requires Delta = 1 minute");
            }
            if (!REQUIRED_BUDGET_DEFINITION.equals(budgetDefinition)) {
                throw new IllegalArgumentException(
                        "PACE Q1 requires budget_definition "
                                + REQUIRED_BUDGET_DEFINITION);
            }
            if (requiredSupportEnd < 10080) {
                throw new IllegalArgumentException(
                        "PACE Q1 requires temporal support through 10080");
            }
            Set<String> suffixes = new TreeSet<>();
            for (VariantSpec variant : variants) {
                variant.validate();
                if (!suffixes.add(variant.suffix())) {
                    throw new IllegalArgumentException(
                            "duplicate variant suffix: " + variant.suffix());
                }
            }
        }

        long seedFor(String split) {
            Long seed = splitSeeds.get(split);
            if (seed == null) {
                throw new IllegalArgumentException(
                        "missing seed for split " + split);
            }
            return seed;
        }

        private static void requireDistinctPositive(
                List<Integer> values,
                String name) {
            if (values.isEmpty()
                    || values.stream().anyMatch(value ->
                            value == null || value <= 0)
                    || new TreeSet<>(values).size() != values.size()) {
                throw new IllegalArgumentException(
                        name + " must contain distinct positive integers");
            }
        }
    }

    public record VariantSpec(
            String kind,
            String value,
            String suffix,
            Path path,
            int maximumPairs) {
        void validate() {
            if (!"score_density".equals(kind)
                    && !"graph_seed".equals(kind)) {
                throw new IllegalArgumentException(
                        "unknown variant kind: " + kind);
            }
            requireText(value, "variant value");
            requireText(suffix, "variant suffix");
            Objects.requireNonNull(path, "variant path");
            if (maximumPairs <= 0) {
                throw new IllegalArgumentException(
                        "variant maximum_pairs must be positive");
            }
        }
    }

    private record Cell(
            int timeCenter,
            int windowMinutes,
            double budgetOverhead) {
    }

    private record IntervalKey(int start, int end) {
    }

    private record GridWitnessSummary(
            double gridMinimum,
            double tHatMinDelta,
            int gridDepartureCount,
            double witnessPathLowerBoundDistance,
            int witnessPathEdgeCount,
            String witnessPathChecksum,
            String witnessEvidenceChecksum) {
    }

    private record GridBudget(
            int intervalStart,
            int intervalEnd,
            double gridMinimum,
            double tHatMinDelta,
            double budget,
            int gridDepartureCount,
            double witnessPathLowerBoundDistance,
            int witnessPathEdgeCount,
            String witnessPathChecksum,
            String witnessEvidenceChecksum) {
    }

    private record WitnessGridEvidence(
            edu.ipcmax.core.validate.Path path,
            double lowerBoundDistance,
            String pathChecksum,
            Map<Integer, Double> travelByDeparture) {
    }

    private record DefaultBudgetKey(
            int evaluationPairIndex,
            int intervalCenter) {
    }

    private record EndpointPair(int source, int destination) {
    }

    private record SelectedPair(
            String split,
            int pairIndex,
            int band,
            QueryPairCandidate candidate) {
    }

    public record DatasetChecksums(
            String datasetPayloadChecksum,
            String datasetChecksum,
            String temporalAttributeChecksum) {
    }

    private record BaseGeneration(
            List<QueryManifestEntry> rows,
            List<SelectedPair> pairs,
            DatasetChecksums checksums,
            long pairsExamined,
            int candidatePoolSize,
            int horizonSafeCandidatePoolSize) {
    }

    private record VariantGeneration(
            List<QueryManifestEntry> rows,
            DatasetChecksums checksums,
            QueryPreparationIndexes indexes) {
    }

    record GenerationResult(
            List<QueryManifestEntry> rows,
            GenerationSummary summary) {
    }

    public record GenerationSummary(
            String datasetId,
            int queryRows,
            Map<String, Integer> rowsBySplit,
            Map<String, DatasetChecksums> checksums,
            long pairsExamined,
            int candidatePoolSize,
            int horizonSafeCandidatePoolSize,
            int horizonRejectedCandidateCount,
            int basePairCount) {
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
