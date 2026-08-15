package edu.ipcmax.casestudy.nyc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.ExactDijkstraLowerBoundOracle;
import edu.ipcmax.core.index.LowerBoundOracle;
import edu.ipcmax.core.index.QueryPreparationIndexes;
import edu.ipcmax.core.labeling.PointForwardLabeling;
import edu.ipcmax.core.loader.GeneratedGraphDataset;
import edu.ipcmax.core.loader.GeneratedGraphLoader;
import edu.ipcmax.core.pcmax.EnvelopeProfile;
import edu.ipcmax.core.pcmax.EnvelopeSegment;
import edu.ipcmax.core.pcmax.PACE;
import edu.ipcmax.core.pcmax.PaceEngineMode;
import edu.ipcmax.core.pcmax.PaceExecutionPolicy;
import edu.ipcmax.core.pcmax.PaceFeatures;
import edu.ipcmax.core.pcmax.PaceGenerationResult;
import edu.ipcmax.core.pcmax.PaceGenerationStats;
import edu.ipcmax.core.pcmax.PaceOptions;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.validate.ExactPathValidator;
import edu.ipcmax.core.validate.ValidationResult;

/** Isolated PACE-B versus time-dependent-fastest NYC case-study runner. */
public final class NycShuttleCaseStudyBench {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private NycShuttleCaseStudyBench() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.timeoutSeconds() != 5) {
            throw new IllegalArgumentException("NYC bounded protocol requires exactly --timeout-seconds 5");
        }
        GeneratedGraphDataset loaded = new GeneratedGraphLoader().load(options.dataset());
        TDGraph graph = loaded.graph();
        List<QueryRow> queries = readQueries(options.queryFile(), options.queryId());
        QueryPreparationIndexes indexes = PACE.preparedIndexes(graph);
        ExactDijkstraLowerBoundOracle lowerBounds = new ExactDijkstraLowerBoundOracle(graph);
        Map<Integer, LowerBoundOracle.Labels> reverseCache = new HashMap<>();
        Map<BaselineKey, BaselineRun> baselineCache = new HashMap<>();
        if (Files.exists(options.output()) && !options.resume()) {
            throw new IllegalArgumentException(
                    "result output already exists; use a new path or explicit --resume: "
                            + options.output());
        }
        Set<String> completed = options.resume() ? completedIds(options.output()) : Set.of();
        Files.createDirectories(options.output().toAbsolutePath().normalize().getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(
                options.output(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (QueryRow query : queries) {
                if (completed.contains(query.queryId())) {
                    continue;
                }
                LowerBoundOracle.Labels reverse = reverseCache.computeIfAbsent(
                        query.destination(), lowerBounds::distancesTo);
                BaselineKey baselineKey = new BaselineKey(
                        query.source(), query.destination(),
                        query.intervalStart(), query.intervalEnd());
                BaselineRun baseline = baselineCache.get(baselineKey);
                if (baseline == null) {
                    long started = System.nanoTime();
                    baseline = new BaselineRun(
                            fastestProfile(
                                    graph, reverse,
                                    new QuerySpec(
                                            query.source(), query.destination(),
                                            query.intervalStart(), query.intervalEnd(),
                                            query.budget(), 1),
                                    options.baselineThreads()),
                            System.nanoTime() - started);
                    baselineCache.put(baselineKey, baseline);
                }
                Map<String, Object> record = execute(
                        graph, indexes, query, options,
                        baseline.summary(), baseline.timingNanos());
                writer.write(JSON.writeValueAsString(record));
                writer.newLine();
                writer.flush();
                System.out.printf("query=%s status=%s%n", query.queryId(), record.get("status"));
            }
        }
    }

    private static Map<String, Object> execute(
            TDGraph graph,
            QueryPreparationIndexes indexes,
            QueryRow row,
            Options options,
            ProfileSummary fastest,
            long baselineNanos) throws NoSuchAlgorithmException {
        QuerySpec query = new QuerySpec(
                row.source(), row.destination(), row.intervalStart(), row.intervalEnd(),
                row.budget(), 1);
        long memoryBefore = usedHeap();
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "nyc-pace-query-" + row.queryId());
            thread.setDaemon(true);
            return thread;
        });
        long paceStarted = System.nanoTime();
        Future<PaceRun> future = executor.submit(() -> runPace(graph, indexes, query, options));
        PaceRun paceRun = null;
        String status;
        String error = null;
        try {
            paceRun = future.get(options.timeoutSeconds(), TimeUnit.SECONDS);
            status = paceRun.generation().completion().name();
        } catch (TimeoutException timeout) {
            future.cancel(true);
            status = "TIMEOUT";
            error = "PACE-B exceeded the 5-second case-study execution limit";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            status = "INTERRUPTED";
            error = interrupted.toString();
        } catch (ExecutionException failure) {
            status = "FAILED";
            error = failure.getCause() == null ? failure.toString() : failure.getCause().toString();
        } finally {
            executor.shutdownNow();
        }
        long paceNanos = System.nanoTime() - paceStarted;
        long memoryAfter = usedHeap();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", "nyc-case-result-v1");
        result.put("query_id", row.queryId());
        result.put("pair_id", row.pairId());
        result.put("dataset_id", row.datasetId());
        result.put("source", row.source());
        result.put("destination", row.destination());
        result.put("period_id", row.periodId());
        result.put("interval_start", row.intervalStart());
        result.put("interval_end", row.intervalEnd());
        result.put("budget", row.budget());
        result.put("rho", row.rho());
        result.put("status", status);
        result.put("error", error);
        result.put("bounded_parameters", Map.of(
                "L", options.pivotLimitL(),
                "theta", options.theta(),
                "Kf", options.frontierLimitKf(),
                "Mb", options.breakpointCapMb()));
        result.put("execution_protocol", Map.of(
                "timeout_seconds", options.timeoutSeconds(),
                "threads", options.threads(),
                "baseline_threads", options.baselineThreads(),
                "legacy_connector_portfolio_enabled", false,
                "legacy_connector_cache_enabled", false,
                "legacy_connector_and_query_work_caps", "UNBOUNDED_NOT_STOPPING_CRITERIA"));
        result.put("timing_ns", Map.of(
                "pace_b", paceNanos,
                "fastest_profile", baselineNanos));
        result.put("memory_bytes", Map.of(
                "heap_before", memoryBefore,
                "heap_after", memoryAfter,
                "peak_heap_sample", Math.max(memoryBefore, memoryAfter)));
        result.put("fastest", fastest.toMap());
        if (paceRun != null) {
            ProfileSummary pace = summarizeEnvelope(graph, query, paceRun.profile());
            result.put("pace_b", pace.toMap());
            result.put("candidate_count", paceRun.generation().frontier().size());
            result.put("generation", generationMap(paceRun.generation()));
            result.put("score_gain_absolute", pace.averageScore() - fastest.averageScore());
            result.put("score_gain_percent", 100.0 * (pace.averageScore() - fastest.averageScore())
                    / Math.max(fastest.averageScore(), 1e-9));
            result.put("travel_time_premium_percent", 100.0
                    * (pace.averageTravelTime() - fastest.averageTravelTime())
                    / Math.max(fastest.averageTravelTime(), 1e-9));
            result.put("final_checksum", paceRun.generation().outputChecksum());
        } else {
            result.put("pace_b", null);
            result.put("candidate_count", null);
            result.put("generation", null);
            result.put("score_gain_absolute", null);
            result.put("score_gain_percent", null);
            result.put("travel_time_premium_percent", null);
            result.put("final_checksum", null);
        }
        return result;
    }

    private static PaceRun runPace(
            TDGraph graph,
            QueryPreparationIndexes indexes,
            QuerySpec query,
            Options options) {
        PaceFeatures features = new PaceFeatures(
                true, true, true, true, true, true,
                true, true,
                false, false,
                true, true);
        PaceOptions paceOptions = new PaceOptions(
                PaceExecutionPolicy.PACE_B,
                PaceEngineMode.SCALABLE,
                options.theta(),
                options.pivotLimitL(),
                1,
                options.frontierLimitKf(),
                PaceOptions.UNBOUNDED_WORK,
                options.breakpointCapMb(),
                PaceOptions.UNBOUNDED_WORK,
                options.threads(),
                true,
                features,
                PaceOptions.UNBOUNDED);
        PACE pace = new PACE(graph, paceOptions, indexes);
        EnvelopeProfile profile = pace.run(query);
        return new PaceRun(profile, pace.lastGenerationResult());
    }

    private static ProfileSummary fastestProfile(
            TDGraph graph,
            LowerBoundOracle.Labels reverse,
            QuerySpec query,
            int threads) throws NoSuchAlgorithmException {
        ForkJoinPool pool = new ForkJoinPool(threads);
        List<PointValue> values;
        try {
            values = pool.submit(() -> IntStream.rangeClosed(
                            query.departureStart(), query.departureEnd())
                    .parallel()
                    .mapToObj(departure -> fastestPoint(
                            graph, reverse, query, departure))
                    .toList()).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fastest-profile preparation interrupted", interrupted);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("fastest-profile preparation failed", failure.getCause());
        } finally {
            pool.shutdownNow();
        }
        return summarizePoints(graph, query, values, "FASTEST");
    }

    private static PointValue fastestPoint(
            TDGraph graph,
            LowerBoundOracle.Labels reverse,
            QuerySpec query,
            int departure) {
        PointForwardLabeling labeler = new PointForwardLabeling(graph);
        ExactPathValidator validator = new ExactPathValidator(graph);
        PointForwardLabeling.Result labels = labeler.runToTarget(
                query.source(), query.destination(), departure,
                query.maxTravelTime(), reverse);
        if (!labels.reached(query.destination())) {
            return PointValue.unresolved(departure);
        }
        edu.ipcmax.core.validate.Path path = labels.pathTo(query.destination());
        ValidationResult validation = validator.validate(
                query.source(), query.destination(), departure,
                query.maxTravelTime(), path);
        return PointValue.from(departure, path, validation);
    }

    private static ProfileSummary summarizeEnvelope(
            TDGraph graph,
            QuerySpec query,
            EnvelopeProfile profile) throws NoSuchAlgorithmException {
        ExactPathValidator validator = new ExactPathValidator(graph);
        List<PointValue> values = new ArrayList<>();
        for (int departure = query.departureStart(); departure <= query.departureEnd(); departure++) {
            EnvelopeSegment segment = profile.segmentAt(departure);
            if (segment == null || !segment.found()) {
                values.add(PointValue.unresolved(departure));
                continue;
            }
            edu.ipcmax.core.validate.Path path = segment.path();
            ValidationResult validation = validator.validate(
                    query.source(), query.destination(), departure,
                    query.maxTravelTime(), path);
            values.add(PointValue.from(departure, path, validation));
        }
        ProfileSummary sampled = summarizePoints(graph, query, values, "PACE_B");
        return sampled.withProfileCellCount(profile.segments().size());
    }

    private static ProfileSummary summarizePoints(
            TDGraph graph,
            QuerySpec query,
            List<PointValue> values,
            String algorithm) throws NoSuchAlgorithmException {
        int resolved = 0;
        int violations = 0;
        double scoreSum = 0;
        int maxScore = 0;
        double travelSum = 0;
        Set<List<Integer>> distinctPaths = new LinkedHashSet<>();
        Set<Integer> scoreBearingArcs = new LinkedHashSet<>();
        int routeSwitches = 0;
        List<Integer> previous = null;
        for (PointValue value : values) {
            if (!value.resolved()) {
                if (value.validationAttempted()) {
                    violations++;
                }
                previous = null;
                continue;
            }
            resolved++;
            scoreSum += value.score();
            maxScore = Math.max(maxScore, value.score());
            travelSum += value.travelTime();
            distinctPaths.add(value.arcIds());
            if (previous != null && !previous.equals(value.arcIds())) {
                routeSwitches++;
            }
            previous = value.arcIds();
            for (int arcId : value.arcIds()) {
                Edge edge = graph.edges().get(arcId);
                if (edge.scoreFunction().maxValue() > 0) {
                    scoreBearingArcs.add(arcId);
                }
            }
        }
        List<Map<String, Object>> cells = cells(values);
        String checksum = pointChecksum(values);
        int total = values.size();
        return new ProfileSummary(
                algorithm,
                resolved == 0 ? 0.0 : scoreSum / resolved,
                maxScore,
                resolved == 0 ? 0.0 : travelSum / resolved,
                cells.size(),
                distinctPaths.size(),
                routeSwitches,
                resolved,
                total - resolved,
                total == 0 ? 0.0 : (double) resolved / total,
                total == 0 ? 0.0 : (double) (total - resolved) / total,
                violations,
                scoreBearingArcs.size(),
                checksum,
                cells);
    }

    private static List<Map<String, Object>> cells(List<PointValue> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        int index = 0;
        while (index < values.size()) {
            PointValue first = values.get(index);
            int end = index;
            while (end + 1 < values.size()
                    && first.sameAssignment(values.get(end + 1))) {
                end++;
            }
            double score = 0;
            double travel = 0;
            int count = 0;
            for (int cursor = index; cursor <= end; cursor++) {
                PointValue value = values.get(cursor);
                if (value.resolved()) {
                    score += value.score();
                    travel += value.travelTime();
                    count++;
                }
            }
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("start", first.departure());
            cell.put("end", values.get(end).departure());
            cell.put("resolved", first.resolved());
            cell.put("arc_ids", first.arcIds());
            cell.put("average_score", count == 0 ? null : score / count);
            cell.put("average_travel_time", count == 0 ? null : travel / count);
            result.add(cell);
            index = end + 1;
        }
        return List.copyOf(result);
    }

    private static String pointChecksum(List<PointValue> values) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (PointValue value : values) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.departure()).array());
            digest.update((byte) (value.resolved() ? 1 : 0));
            for (int arcId : value.arcIds()) {
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(arcId).array());
            }
            digest.update(ByteBuffer.allocate(Long.BYTES)
                    .putLong(Double.doubleToLongBits(value.travelTime())).array());
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.score()).array());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Map<String, Object> generationMap(PaceGenerationResult generation) {
        PaceGenerationStats stats = generation.stats();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("completion", generation.completion().name());
        result.put("exactness_scope", generation.exactnessScope().name());
        result.put("cap_triggered", generation.capStatus().triggered().stream()
                .map(Enum::name).sorted().toList());
        result.put("corridor_checksum", generation.corridorChecksum());
        result.put("selected_pivot_arc_ids", generation.selectedPivotArcIds());
        result.put("output_checksum", generation.outputChecksum());
        result.put("counters", Map.ofEntries(
                Map.entry("corridor_nodes", stats.corridorNodes()),
                Map.entry("corridor_edges", stats.corridorEdges()),
                Map.entry("score_relevant_edges", stats.scoreRelevantEdges()),
                Map.entry("selected_pivots", stats.selectedPivots()),
                Map.entry("candidates_generated", stats.candidatesGenerated()),
                Map.entry("candidates_retained", stats.candidatesRetained()),
                Map.entry("breakpoint_cap_hits", stats.breakpointCapHits()),
                Map.entry("frontier_cells", stats.frontierCells()),
                Map.entry("peak_frontier_size", stats.peakFrontierSize())));
        return result;
    }

    private static long usedHeap() {
        var usage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return usage.getUsed();
    }

    private static List<QueryRow> readQueries(Path path, String onlyId) throws IOException {
        List<QueryRow> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = JSON.readTree(line);
                if (!"nyc-query-v1".equals(node.path("schema_version").asText())) {
                    throw new IOException(path + ":" + lineNumber + ": unsupported schema_version");
                }
                QueryRow row = new QueryRow(
                        requiredText(node, "query_id"),
                        requiredText(node, "pair_id"),
                        requiredText(node, "dataset_id"),
                        node.path("source").asInt(),
                        node.path("destination").asInt(),
                        requiredText(node, "period_id"),
                        node.path("interval_start").asInt(),
                        node.path("interval_end").asInt(),
                        node.path("budget").asDouble(),
                        node.path("rho").asDouble());
                new QuerySpec(row.source(), row.destination(), row.intervalStart(),
                        row.intervalEnd(), row.budget(), 1);
                if (onlyId == null || onlyId.equals(row.queryId())) {
                    result.add(row);
                }
            }
        }
        if (result.isEmpty()) {
            throw new IOException("no selected queries found in " + path);
        }
        return List.copyOf(result);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("query field is required: " + field);
        }
        return value;
    }

    private static Set<String> completedIds(Path path) throws IOException {
        if (!Files.exists(path)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    result.add(JSON.readTree(line).path("query_id").asText());
                }
            }
        }
        return Set.copyOf(result);
    }

    private record QueryRow(
            String queryId,
            String pairId,
            String datasetId,
            int source,
            int destination,
            String periodId,
            int intervalStart,
            int intervalEnd,
            double budget,
            double rho) {
    }

    private record PaceRun(EnvelopeProfile profile, PaceGenerationResult generation) {
    }

    private record BaselineKey(
            int source,
            int destination,
            int intervalStart,
            int intervalEnd) {
    }

    private record BaselineRun(ProfileSummary summary, long timingNanos) {
    }

    private record PointValue(
            int departure,
            boolean resolved,
            boolean validationAttempted,
            double travelTime,
            int score,
            List<Integer> arcIds) {
        static PointValue unresolved(int departure) {
            return new PointValue(departure, false, false, Double.NaN, 0, List.of());
        }

        static PointValue from(
                int departure,
                edu.ipcmax.core.validate.Path path,
                ValidationResult validation) {
            if (!validation.valid()) {
                return new PointValue(departure, false, true, Double.NaN, 0, path.arcIds());
            }
            return new PointValue(
                    departure, true, true, validation.travelTime(), validation.score(),
                    List.copyOf(path.arcIds()));
        }

        boolean sameAssignment(PointValue other) {
            return resolved == other.resolved && arcIds.equals(other.arcIds);
        }
    }

    private record ProfileSummary(
            String algorithm,
            double averageScore,
            int maxScore,
            double averageTravelTime,
            int profileCellCount,
            int distinctPathCount,
            int routeSwitches,
            int resolvedDepartures,
            int unresolvedDepartures,
            double resolvedCoverage,
            double unresolvedCoverage,
            int budgetViolationCount,
            int scoreBearingEdgeCount,
            String checksum,
            List<Map<String, Object>> cells) {
        ProfileSummary withProfileCellCount(int value) {
            return new ProfileSummary(
                    algorithm, averageScore, maxScore, averageTravelTime, value,
                    distinctPathCount, routeSwitches, resolvedDepartures,
                    unresolvedDepartures, resolvedCoverage, unresolvedCoverage,
                    budgetViolationCount, scoreBearingEdgeCount, checksum, cells);
        }

        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("algorithm", algorithm);
            result.put("average_score", averageScore);
            result.put("max_score", maxScore);
            result.put("average_travel_time", averageTravelTime);
            result.put("profile_cell_count", profileCellCount);
            result.put("distinct_path_count", distinctPathCount);
            result.put("route_switches", routeSwitches);
            result.put("resolved_departures", resolvedDepartures);
            result.put("unresolved_departures", unresolvedDepartures);
            result.put("resolved_coverage", resolvedCoverage);
            result.put("unresolved_coverage", unresolvedCoverage);
            result.put("budget_violation_count", budgetViolationCount);
            result.put("score_bearing_edge_count", scoreBearingEdgeCount);
            result.put("checksum", checksum);
            result.put("cells", cells);
            return result;
        }
    }

    private record Options(
            Path dataset,
            Path queryFile,
            Path output,
            String queryId,
            int theta,
            int pivotLimitL,
            int frontierLimitKf,
            int breakpointCapMb,
            int threads,
            int baselineThreads,
            int timeoutSeconds,
            boolean resume) {
        private static final Set<String> OBSOLETE = Set.of(
                "--connector-limit-kc", "--connector-expansion-cap-mc",
                "--query-work-cap-mq", "--connector-portfolio",
                "--connector-cache", "--k");
        private static final Set<String> ALLOWED = Set.of(
                "--dataset", "--query-file", "--output", "--query-id",
                "--theta", "--pivot-limit-l", "--frontier-limit-kf",
                "--breakpoint-cap-mb", "--threads", "--baseline-threads",
                "--timeout-seconds", "--resume");

        static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            boolean resume = false;
            for (int index = 0; index < args.length; index++) {
                String option = args[index];
                if (OBSOLETE.contains(option)) {
                    throw new IllegalArgumentException(
                            option + " is obsolete and forbidden in the NYC case-study runner");
                }
                if (!ALLOWED.contains(option)) {
                    throw new IllegalArgumentException(
                            "unsupported NYC case-study option: " + option);
                }
                if ("--resume".equals(option)) {
                    resume = true;
                    continue;
                }
                if (!option.startsWith("--") || index + 1 >= args.length) {
                    throw usage();
                }
                values.put(option, args[++index]);
            }
            for (String required : List.of("--dataset", "--query-file", "--output")) {
                if (!values.containsKey(required)) {
                    throw usage();
                }
            }
            return new Options(
                    Path.of(values.get("--dataset")),
                    Path.of(values.get("--query-file")),
                    Path.of(values.get("--output")),
                    values.get("--query-id"),
                    integer(values, "--theta", 2),
                    integer(values, "--pivot-limit-l", 32),
                    integer(values, "--frontier-limit-kf", 16),
                    integer(values, "--breakpoint-cap-mb", 1_000_000),
                    integer(values, "--threads", 1),
                    integer(values, "--baseline-threads", 8),
                    integer(values, "--timeout-seconds", 5),
                    resume);
        }

        private static int integer(Map<String, String> values, String key, int fallback) {
            int value = Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback)));
            if (value < 1 && !"--theta".equals(key) && !"--pivot-limit-l".equals(key)) {
                throw new IllegalArgumentException(key + " must be positive");
            }
            if (value < 0) {
                throw new IllegalArgumentException(key + " cannot be negative");
            }
            return value;
        }

        private static IllegalArgumentException usage() {
            return new IllegalArgumentException(
                    "Usage: NycShuttleCaseStudyBench --dataset DIR --query-file JSONL "
                            + "--output JSONL [--query-id ID] [--theta N] [--pivot-limit-l N] "
                            + "[--frontier-limit-kf N] [--breakpoint-cap-mb N] "
                            + "[--threads N] [--baseline-threads N] "
                            + "[--timeout-seconds 5] [--resume]");
        }
    }
}
