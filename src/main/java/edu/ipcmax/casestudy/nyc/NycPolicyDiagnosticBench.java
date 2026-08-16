package edu.ipcmax.casestudy.nyc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.QueryPreparationIndexes;
import edu.ipcmax.core.loader.GeneratedGraphLoader;
import edu.ipcmax.core.pcmax.EnvelopeProfile;
import edu.ipcmax.core.pcmax.EnvelopeSegment;
import edu.ipcmax.core.pcmax.PACE;
import edu.ipcmax.core.pcmax.PaceDiagnosticTrace;
import edu.ipcmax.core.pcmax.PaceEngineMode;
import edu.ipcmax.core.pcmax.PaceExecutionMetrics;
import edu.ipcmax.core.pcmax.PaceExecutionPolicy;
import edu.ipcmax.core.pcmax.PaceFeatures;
import edu.ipcmax.core.pcmax.PaceGenerationResult;
import edu.ipcmax.core.pcmax.PaceGenerationStats;
import edu.ipcmax.core.pcmax.PaceOptions;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.validate.ExactPathValidator;
import edu.ipcmax.core.validate.ValidationResult;

/** External-cap diagnostic runner for unchanged PACE-B and predeclared PACE-X. */
public final class NycPolicyDiagnosticBench {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private NycPolicyDiagnosticBench() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        TDGraph graph = new GeneratedGraphLoader()
                .load(options.dataset()).graph();
        QueryPreparationIndexes indexes = PACE.preparedIndexes(graph);
        Set<String> selected = readSelection(options.selectionFile());
        List<QueryRow> queries = readQueries(
                options.queryFile(), options.queryId(), selected);
        Set<String> completed = options.resume()
                ? completedIds(options.output()) : Set.of();
        if (Files.exists(options.output()) && !options.resume()) {
            throw new IOException(
                    "diagnostic output exists; use --resume or a new path: "
                            + options.output());
        }
        Files.createDirectories(options.output().toAbsolutePath()
                .normalize().getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(
                options.output(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            for (QueryRow query : queries) {
                if (completed.contains(query.queryId())) {
                    continue;
                }
                Map<String, Object> record = execute(
                        graph, indexes, query, options);
                writer.write(JSON.writeValueAsString(record));
                writer.newLine();
                writer.flush();
                System.out.printf(
                        "policy=%s cap=%ss query=%s status=%s%n",
                        options.policy(), options.capSeconds(),
                        query.queryId(), record.get("status"));
            }
        }
    }

    private static Map<String, Object> execute(
            TDGraph graph,
            QueryPreparationIndexes indexes,
            QueryRow row,
            Options options) throws Exception {
        QuerySpec query = row.toSpec();
        PaceDiagnosticTrace trace =
                new PaceDiagnosticTrace(List.of());
        AtomicReference<PaceExecutionMetrics.Snapshot> latest =
                new AtomicReference<>(new PaceExecutionMetrics.Snapshot(
                        "", 0, Map.of(), Map.of()));
        ExecutorService executor = Executors.newSingleThreadExecutor(
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "nyc-policy-diagnostic-" + row.queryId());
                    thread.setDaemon(true);
                    return thread;
                });
        long started = System.nanoTime();
        Future<Run> future = executor.submit(() -> {
            try (PaceExecutionMetrics metrics =
                         PaceExecutionMetrics.live(latest::set)) {
                PACE pace = new PACE(
                        graph, paceOptions(options.policy()), indexes,
                        metrics, trace);
                EnvelopeProfile profile = pace.run(query);
                return new Run(
                        profile, pace.lastGenerationResult());
            }
        });
        Run run = null;
        String status;
        String error = null;
        try {
            run = future.get(
                    options.capSeconds(), TimeUnit.SECONDS);
            status = run.generation().completion().name();
        } catch (TimeoutException timeout) {
            status = "EXTERNAL_TIMEOUT";
            error = "externally truncated at diagnostic cap";
            future.cancel(true);
        } catch (Exception failure) {
            status = "FAILED";
            Throwable cause = failure.getCause() == null
                    ? failure : failure.getCause();
            error = cause.toString();
            future.cancel(true);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
        long elapsed = System.nanoTime() - started;
        PaceExecutionMetrics.Snapshot snapshot = latest.get();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schema_version", "nyc-policy-diagnostic-v1");
        output.put("query_id", row.queryId());
        output.put("pair_id", row.pairId());
        output.put("period_id", row.periodId());
        output.put("rho", row.rho());
        output.put("budget", row.budget());
        output.put("policy", options.policy());
        output.put("external_cap_seconds", options.capSeconds());
        output.put("status", status);
        output.put("error", error);
        output.put("elapsed_ns", elapsed);
        output.put("last_phase", snapshot.currentPhase());
        output.put("phase_timings_ns", snapshot.timings());
        output.put("work_counters", snapshot.counters());
        output.put("observed_complete_descriptors",
                trace.completeDescriptorCount());
        output.put("observed_replayed_candidates",
                trace.replayedCandidateCount());
        output.put("observed_root_frontier_size",
                trace.currentRootFrontierSize());
        if (run == null) {
            output.put("completion", null);
            output.put("exactness_scope", "NOT_CERTIFIED");
            output.put("cap_triggered", List.of("EXTERNAL_TIME_CAP"));
            output.put("candidate_count", null);
            output.put("generation_stats", null);
            output.put("profile", null);
        } else {
            PaceGenerationResult generation = run.generation();
            output.put("completion", generation.completion().name());
            output.put("exactness_scope",
                    generation.exactnessScope().name());
            output.put("cap_triggered", generation.capStatus()
                    .triggered().stream().map(Enum::name).sorted()
                    .toList());
            output.put("candidate_count", generation.frontier().size());
            output.put("generation_stats", stats(generation.stats()));
            output.put("profile", profile(
                    graph, query, run.profile()));
        }
        return output;
    }

    private static PaceOptions paceOptions(String policy) {
        PaceExecutionPolicy executionPolicy = "PACE_X".equals(policy)
                ? PaceExecutionPolicy.PACE_X
                : PaceExecutionPolicy.PACE_B;
        PaceFeatures features = executionPolicy
                == PaceExecutionPolicy.PACE_B
                ? new PaceFeatures(
                        true, true, true, true, true, true,
                        true, true, false, false, true, true)
                : PaceFeatures.defaults();
        return new PaceOptions(
                executionPolicy,
                PaceEngineMode.SCALABLE,
                executionPolicy == PaceExecutionPolicy.PACE_X
                        ? PaceOptions.UNBOUNDED : 2,
                executionPolicy == PaceExecutionPolicy.PACE_X
                        ? PaceOptions.UNBOUNDED : 32,
                executionPolicy == PaceExecutionPolicy.PACE_X
                        ? PaceOptions.UNBOUNDED : 1,
                executionPolicy == PaceExecutionPolicy.PACE_X
                        ? PaceOptions.UNBOUNDED : 16,
                PaceOptions.UNBOUNDED_WORK,
                executionPolicy == PaceExecutionPolicy.PACE_X
                        ? PaceOptions.UNBOUNDED : 1_000_000,
                PaceOptions.UNBOUNDED_WORK,
                1, true, features, PaceOptions.UNBOUNDED);
    }

    private static Map<String, Object> profile(
            TDGraph graph,
            QuerySpec query,
            EnvelopeProfile profile) {
        ExactPathValidator validator = new ExactPathValidator(graph);
        List<Map<String, Object>> points = new ArrayList<>();
        int resolved = 0;
        double score = 0;
        double travel = 0;
        for (int departure = query.departureStart();
                departure <= query.departureEnd(); departure++) {
            EnvelopeSegment segment = profile.segmentAt(departure);
            if (segment == null || !segment.found()) {
                points.add(Map.of(
                        "departure", departure,
                        "resolved", false));
                continue;
            }
            ValidationResult value = validator.validate(
                    query.source(), query.destination(), departure,
                    query.maxTravelTime(), segment.path());
            if (!value.valid()) {
                points.add(Map.of(
                        "departure", departure,
                        "resolved", false,
                        "validation_error", value.reason()));
                continue;
            }
            resolved++;
            score += value.score();
            travel += value.travelTime();
            points.add(Map.ofEntries(
                    Map.entry("departure", departure),
                    Map.entry("resolved", true),
                    Map.entry("score", value.score()),
                    Map.entry("travel_time", value.travelTime()),
                    Map.entry("path", value.path().arcIds())));
        }
        int total = query.departureEnd()
                - query.departureStart() + 1;
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("resolved_departures", resolved);
        output.put("resolved_coverage", (double) resolved / total);
        output.put("full_interval_average_score", score / total);
        output.put("resolved_average_score",
                resolved == 0 ? null : score / resolved);
        output.put("resolved_average_travel_time",
                resolved == 0 ? null : travel / resolved);
        output.put("profile_cell_count", profile.segments().size());
        output.put("points", points);
        return output;
    }

    private static Map<String, Object> stats(
            PaceGenerationStats value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("corridor_nodes", value.corridorNodes());
        result.put("corridor_edges", value.corridorEdges());
        result.put("corridor_cells", value.corridorCells());
        result.put("score_relevant_edges", value.scoreRelevantEdges());
        result.put("selected_pivots", value.selectedPivots());
        result.put("connector_calls", value.connectorCalls());
        result.put("connector_expansions", value.connectorExpansions());
        result.put("candidates_generated", value.candidatesGenerated());
        result.put("candidates_retained", value.candidatesRetained());
        result.put("frontier_cells", value.frontierCells());
        result.put("peak_frontier_size", value.peakFrontierSize());
        result.put("breakpoint_cap_hits", value.breakpointCapHits());
        result.put("total_work", value.totalWork());
        return result;
    }

    private static Set<String> readSelection(Path path)
            throws IOException {
        if (path == null) {
            return Set.of();
        }
        JsonNode root = JSON.readTree(path.toFile());
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonNode value : root.path("query_ids")) {
            result.add(value.asText());
        }
        if (result.isEmpty()) {
            throw new IOException("empty selection file " + path);
        }
        return Set.copyOf(result);
    }

    private static List<QueryRow> readQueries(
            Path path,
            String onlyId,
            Set<String> selection) throws IOException {
        List<QueryRow> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(
                path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = JSON.readTree(line);
                QueryRow row = new QueryRow(
                        node.path("query_id").asText(),
                        node.path("pair_id").asText(),
                        node.path("period_id").asText(),
                        node.path("source").asInt(),
                        node.path("destination").asInt(),
                        node.path("interval_start").asInt(),
                        node.path("interval_end").asInt(),
                        node.path("budget").asDouble(),
                        node.path("rho").asDouble());
                if ((onlyId == null || onlyId.equals(row.queryId()))
                        && (selection.isEmpty()
                                || selection.contains(row.queryId()))) {
                    result.add(row);
                }
            }
        }
        if (result.isEmpty()) {
            throw new IOException("no selected queries in " + path);
        }
        return List.copyOf(result);
    }

    private static Set<String> completedIds(Path path)
            throws IOException {
        if (!Files.exists(path)) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(
                path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    result.add(JSON.readTree(line)
                            .path("query_id").asText());
                }
            }
        }
        return Set.copyOf(result);
    }

    private record Run(
            EnvelopeProfile profile,
            PaceGenerationResult generation) {
    }

    private record QueryRow(
            String queryId,
            String pairId,
            String periodId,
            int source,
            int destination,
            int intervalStart,
            int intervalEnd,
            double budget,
            double rho) {
        QuerySpec toSpec() {
            return new QuerySpec(
                    source, destination,
                    intervalStart, intervalEnd, budget, 1);
        }
    }

    private record Options(
            Path dataset,
            Path queryFile,
            Path output,
            String policy,
            int capSeconds,
            Path selectionFile,
            String queryId,
            boolean resume) {
        static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            boolean resume = false;
            for (int index = 0; index < args.length; index++) {
                if ("--resume".equals(args[index])) {
                    resume = true;
                    continue;
                }
                if (index + 1 >= args.length) {
                    throw usage();
                }
                values.put(args[index], args[++index]);
            }
            for (String required : List.of(
                    "--dataset", "--query-file", "--output",
                    "--policy", "--cap-seconds")) {
                if (!values.containsKey(required)) {
                    throw usage();
                }
            }
            String policy = values.get("--policy")
                    .toUpperCase(java.util.Locale.ROOT)
                    .replace('-', '_');
            if (!Set.of("PACE_B", "PACE_X").contains(policy)) {
                throw new IllegalArgumentException(
                        "--policy must be pace-b or pace-x");
            }
            int cap = Integer.parseInt(values.get("--cap-seconds"));
            if (cap < 1) {
                throw new IllegalArgumentException(
                        "--cap-seconds must be positive");
            }
            return new Options(
                    Path.of(values.get("--dataset")),
                    Path.of(values.get("--query-file")),
                    Path.of(values.get("--output")),
                    policy, cap,
                    values.containsKey("--selection-file")
                            ? Path.of(values.get("--selection-file"))
                            : null,
                    values.get("--query-id"), resume);
        }

        private static IllegalArgumentException usage() {
            return new IllegalArgumentException(
                    "Usage: NycPolicyDiagnosticBench --dataset DIR "
                            + "--query-file JSONL --output JSONL "
                            + "--policy pace-b|pace-x --cap-seconds N "
                            + "[--selection-file JSON] [--query-id ID] "
                            + "[--resume]");
        }
    }
}
