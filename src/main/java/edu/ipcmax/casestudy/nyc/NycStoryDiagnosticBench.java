package edu.ipcmax.casestudy.nyc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import edu.ipcmax.core.loader.GeneratedGraphLoader;
import edu.ipcmax.core.pcmax.EnvelopeProfile;
import edu.ipcmax.core.pcmax.EnvelopeSegment;
import edu.ipcmax.core.pcmax.IPCMaxResult;
import edu.ipcmax.core.pcmax.PACE;
import edu.ipcmax.core.pcmax.PaceDiagnosticTrace;
import edu.ipcmax.core.pcmax.PaceEngineMode;
import edu.ipcmax.core.pcmax.PaceExecutionMetrics;
import edu.ipcmax.core.pcmax.PaceExecutionPolicy;
import edu.ipcmax.core.pcmax.PaceFeatures;
import edu.ipcmax.core.pcmax.PaceGenerationResult;
import edu.ipcmax.core.pcmax.PaceGenerationStats;
import edu.ipcmax.core.pcmax.PaceOptions;
import edu.ipcmax.core.pcmax.QueryCorridor;
import edu.ipcmax.core.pcmax.QueryLowerBounds;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.pcmax.ResultComparator;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.validate.ExactPathValidator;
import edu.ipcmax.core.validate.ValidationResult;

/** Diagnostic-only replay and stage audit for the frozen NYC result bundle. */
public final class NycStoryDiagnosticBench {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final int THETA = 2;
    private static final int PIVOT_LIMIT = 32;
    private static final int FRONTIER_LIMIT = 16;
    private static final int BREAKPOINT_CAP = 1_000_000;

    private NycStoryDiagnosticBench() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        TDGraph graph = new GeneratedGraphLoader()
                .load(options.dataset()).graph();
        QueryPreparationIndexes indexes = PACE.preparedIndexes(graph);
        List<QueryRow> queries = readQueries(
                options.queryFile(), options.queryId());
        Map<String, JsonNode> official = readOfficial(
                options.resultFile());
        Files.createDirectories(options.output().toAbsolutePath()
                .normalize().getParent());

        ExactDijkstraLowerBoundOracle lowerBoundOracle =
                new ExactDijkstraLowerBoundOracle(graph);
        Map<Integer, LowerBoundOracle.Labels> reverseCache =
                new HashMap<>();
        Map<BaselineKey, List<FreshPoint>> freshCache =
                new HashMap<>();
        BitSet queryRegionUnion = new BitSet(graph.edgeCount());

        try (BufferedWriter writer = Files.newBufferedWriter(
                options.output(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            for (QueryRow row : queries) {
                JsonNode result = official.get(row.queryId());
                if (result == null) {
                    throw new IOException(
                            "missing official result for "
                                    + row.queryId());
                }
                Map<String, Object> diagnostic = diagnose(
                        graph, indexes, lowerBoundOracle,
                        reverseCache, freshCache,
                        queryRegionUnion, row, result,
                        options.traceBounded());
                writer.write(JSON.writeValueAsString(diagnostic));
                writer.newLine();
                writer.flush();
                System.out.printf(
                        "diagnostic query=%s status=%s%n",
                        row.queryId(), result.path("status").asText());
            }
        }
        if (options.scopeOutput() != null) {
            List<Integer> union = queryRegionUnion.stream()
                    .boxed().toList();
            Files.createDirectories(options.scopeOutput()
                    .toAbsolutePath().normalize().getParent());
            JSON.writeValue(options.scopeOutput().toFile(), Map.of(
                    "schema_version",
                    "nyc-query-region-union-v1",
                    "arc_count", union.size(),
                    "arc_ids", union));
        }
    }

    private static Map<String, Object> diagnose(
            TDGraph graph,
            QueryPreparationIndexes indexes,
            ExactDijkstraLowerBoundOracle lowerBoundOracle,
            Map<Integer, LowerBoundOracle.Labels> reverseCache,
            Map<BaselineKey, List<FreshPoint>> freshCache,
            BitSet queryRegionUnion,
            QueryRow row,
            JsonNode official,
            boolean traceBounded) throws Exception {
        QuerySpec query = row.toSpec();
        QueryLowerBounds queryLowerBounds = new QueryLowerBounds(
                graph, indexes.edgeTemporalSummaries());
        QueryCorridor corridor = QueryCorridor.build(
                graph, queryLowerBounds, indexes.graphPartition(),
                row.source(), row.destination(), row.budget());
        for (int arcId : corridor.directedArcIds()) {
            queryRegionUnion.set(arcId);
        }

        List<StoredCell> fastestCells = cells(
                official.path("fastest").path("cells"));
        List<StoredCell> paceCells = official.path("pace_b").isObject()
                ? cells(official.path("pace_b").path("cells"))
                : List.of();
        LinkedHashSet<List<Integer>> targetPaths =
                new LinkedHashSet<>();
        for (StoredCell cell : fastestCells) {
            if (cell.resolved()) {
                targetPaths.add(cell.arcIds());
            }
        }

        BaselineKey baselineKey = new BaselineKey(
                row.source(), row.destination(),
                row.intervalStart(), row.intervalEnd());
        List<FreshPoint> fresh = freshCache.get(baselineKey);
        if (fresh == null) {
            LowerBoundOracle.Labels reverse =
                    reverseCache.computeIfAbsent(
                            row.destination(),
                            lowerBoundOracle::distancesTo);
            fresh = freshFastest(graph, query, reverse);
            freshCache.put(baselineKey, fresh);
        }

        PaceRun paceRun = null;
        PaceDiagnosticTrace trace =
                new PaceDiagnosticTrace(targetPaths);
        String status = official.path("status").asText();
        if (traceBounded && !"TIMEOUT".equals(status)) {
            paceRun = runBounded(graph, indexes, query, trace);
        }

        QueryLowerBounds.Distances fromSource =
                queryLowerBounds.truncatedDistancesFrom(
                        row.source(), row.budget());
        QueryLowerBounds.Distances toDestination =
                queryLowerBounds.truncatedDistancesTo(
                        row.destination(), row.budget());
        ExactPathValidator validator = new ExactPathValidator(graph);
        List<Map<String, Object>> points = new ArrayList<>();
        LinkedHashMap<List<Integer>, Map<String, Object>> pathRegistry =
                new LinkedHashMap<>();
        int freshIndex = 0;
        for (int departure = row.intervalStart();
                departure <= row.intervalEnd(); departure++) {
            StoredCell fastestCell = at(fastestCells, departure);
            StoredCell paceCell = at(paceCells, departure);
            FreshPoint freshPoint = fresh.get(freshIndex++);
            ValidationResult storedFastest = validate(
                    validator, query, departure, fastestCell);
            ValidationResult storedPace = validate(
                    validator, query, departure, paceCell);
            registerPath(graph, pathRegistry, fastestCell.arcIds());
            if (paceCell.resolved()) {
                registerPath(graph, pathRegistry, paceCell.arcIds());
            }

            List<Integer> fastestPath = fastestCell.arcIds();
            boolean inCorridor = fastestCell.resolved()
                    && fastestPath.stream().allMatch(
                            corridor::containsArc);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("departure", departure);
            point.put("stored_fastest_valid", storedFastest.valid());
            point.put("stored_fastest_reason", storedFastest.reason());
            point.put("stored_fastest_travel_time",
                    finiteOrNull(storedFastest.travelTime()));
            point.put("stored_fastest_score", storedFastest.score());
            point.put("fresh_fastest_valid", freshPoint.valid());
            point.put("fresh_fastest_travel_time",
                    finiteOrNull(freshPoint.travelTime()));
            point.put("fresh_fastest_score", freshPoint.score());
            point.put("fresh_fastest_path_agreement",
                    freshPoint.arcIds().equals(fastestPath));
            point.put("fresh_fastest_travel_agreement",
                    same(freshPoint.travelTime(),
                            storedFastest.travelTime()));
            point.put("fastest_path", fastestPath);
            point.put("fastest_in_query_region", inCorridor);
            point.put("pace_resolved", paceCell.resolved());
            point.put("pace_valid", storedPace.valid());
            point.put("pace_reason", storedPace.reason());
            point.put("pace_travel_time",
                    finiteOrNull(storedPace.travelTime()));
            point.put("pace_score", storedPace.score());
            point.put("pace_path", paceCell.arcIds());
            if (paceRun != null) {
                point.put("selected_pivots_on_fastest",
                        trace.selectedPivotCount(fastestPath));
                Map<String, Boolean> stages = new LinkedHashMap<>();
                for (PaceDiagnosticTrace.Stage stage :
                        PaceDiagnosticTrace.Stage.values()) {
                    stages.put(stage.name(), trace.contains(
                            fastestPath, stage, departure));
                }
                boolean inEnvelope = paceCell.resolved()
                        && paceCell.arcIds().equals(fastestPath);
                stages.put("FINAL_ENVELOPE", inEnvelope);
                point.put("fastest_stage_presence", stages);
            } else {
                point.put("selected_pivots_on_fastest", null);
                point.put("fastest_stage_presence", null);
            }
            points.add(point);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schema_version", "nyc-story-diagnostic-v1");
        output.put("query_id", row.queryId());
        output.put("pair_id", row.pairId());
        output.put("period_id", row.periodId());
        output.put("rho", row.rho());
        output.put("budget", row.budget());
        output.put("official_status", status);
        output.put("corridor", Map.of(
                "nodes", corridor.vertexIds().size(),
                "edges", corridor.directedArcIds().size(),
                "checksum", corridor.checksum()));
        output.put("points", points);
        output.put("path_registry", pathRegistry.values());
        if (paceRun != null) {
            PaceGenerationResult generated = paceRun.generation();
            output.put("trace_rerun", Map.ofEntries(
                    Map.entry("completion",
                            generated.completion().name()),
                    Map.entry("exactness_scope",
                            generated.exactnessScope().name()),
                    Map.entry("output_checksum",
                            generated.outputChecksum()),
                    Map.entry("official_output_checksum",
                            official.path("final_checksum").asText()),
                    Map.entry("checksum_agreement",
                            generated.outputChecksum().equals(
                                    official.path("final_checksum")
                                            .asText())),
                    Map.entry("selected_pivot_arc_ids",
                            generated.selectedPivotArcIds()),
                    Map.entry("candidate_count",
                            generated.frontier().size()),
                    Map.entry("generation_stats",
                            generationStats(generated.stats())),
                    Map.entry("metrics_timings_ns",
                            paceRun.metrics().timings()),
                    Map.entry("metrics_counters",
                            paceRun.metrics().counters()),
                    Map.entry("interval_best",
                            intervalBest(graph, query, generated)),
                    Map.entry("connector_observations",
                            connectorComparisons(
                                    graph, trace, 2))));
        } else {
            output.put("trace_rerun", null);
        }
        output.put("connector_static_witness_checks",
                staticWitnessChecks(
                        graph, row, targetPaths,
                        paceRun == null
                                ? List.of()
                                : paceRun.generation()
                                        .selectedPivotArcIds(),
                        fromSource, toDestination));
        return output;
    }

    private static PaceRun runBounded(
            TDGraph graph,
            QueryPreparationIndexes indexes,
            QuerySpec query,
            PaceDiagnosticTrace trace) {
        PaceFeatures features = new PaceFeatures(
                true, true, true, true, true, true,
                true, true, false, false, true, true);
        PaceOptions options = new PaceOptions(
                PaceExecutionPolicy.PACE_B,
                PaceEngineMode.SCALABLE,
                THETA, PIVOT_LIMIT, 1, FRONTIER_LIMIT,
                PaceOptions.UNBOUNDED_WORK,
                BREAKPOINT_CAP,
                PaceOptions.UNBOUNDED_WORK,
                1, true, features,
                PaceOptions.UNBOUNDED);
        final PaceExecutionMetrics.Snapshot[] last = {
                new PaceExecutionMetrics.Snapshot(
                        "", 0, Map.of(), Map.of())};
        EnvelopeProfile profile;
        PaceGenerationResult generation;
        try (PaceExecutionMetrics metrics =
                     PaceExecutionMetrics.live(
                             snapshot -> last[0] = snapshot)) {
            PACE pace = new PACE(
                    graph, options, indexes, metrics, trace);
            profile = pace.run(query);
            generation = pace.lastGenerationResult();
        }
        return new PaceRun(profile, generation, last[0]);
    }

    private static List<FreshPoint> freshFastest(
            TDGraph graph,
            QuerySpec query,
            LowerBoundOracle.Labels reverse) {
        PointForwardLabeling labeler =
                new PointForwardLabeling(graph);
        ExactPathValidator validator =
                new ExactPathValidator(graph);
        List<FreshPoint> result = new ArrayList<>();
        for (int departure = query.departureStart();
                departure <= query.departureEnd(); departure++) {
            PointForwardLabeling.Result labels =
                    labeler.runToTarget(
                            query.source(), query.destination(),
                            departure, query.maxTravelTime(), reverse);
            if (!labels.reached(query.destination())) {
                result.add(new FreshPoint(
                        false, Double.NaN, 0, List.of()));
                continue;
            }
            edu.ipcmax.core.validate.Path path =
                    labels.pathTo(query.destination());
            ValidationResult validation = validator.validate(
                    query.source(), query.destination(), departure,
                    query.maxTravelTime(), path);
            result.add(new FreshPoint(
                    validation.valid(), validation.travelTime(),
                    validation.score(), path.arcIds()));
        }
        return List.copyOf(result);
    }

    private static List<Map<String, Object>> connectorComparisons(
            TDGraph graph,
            PaceDiagnosticTrace trace,
            int limit) {
        List<Map<String, Object>> output = new ArrayList<>();
        PointForwardLabeling labeler = new PointForwardLabeling(graph);
        for (PaceDiagnosticTrace.ConnectorObservation observation :
                trace.connectorObservations()) {
            if (output.size() >= limit
                    || observation.returnedPaths().isEmpty()) {
                continue;
            }
            Domain.Interval interval = observation.entryDomain()
                    .intervals().get(0);
            int entry = (int) Math.ceil(interval.start());
            if (!interval.contains(entry)) {
                continue;
            }
            PointForwardLabeling.Result labels = labeler.runToTarget(
                    observation.source(), observation.target(),
                    entry, observation.residualBudget());
            List<Integer> pointPath = labels.reached(
                    observation.target())
                            ? labels.pathTo(observation.target()).arcIds()
                            : List.of();
            output.add(Map.ofEntries(
                    Map.entry("source", observation.source()),
                    Map.entry("target", observation.target()),
                    Map.entry("entry_time", entry),
                    Map.entry("entry_domain",
                            observation.entryDomain().toString()),
                    Map.entry("residual_budget",
                            observation.residualBudget()),
                    Map.entry("pace_connector_path",
                            observation.returnedPaths().get(0)),
                    Map.entry("point_fastest_path", pointPath),
                    Map.entry("path_agreement",
                            observation.returnedPaths().get(0)
                                    .equals(pointPath))));
        }
        return List.copyOf(output);
    }

    private static List<Map<String, Object>> staticWitnessChecks(
            TDGraph graph,
            QueryRow row,
            Set<List<Integer>> targetPaths,
            List<Integer> selectedPivots,
            QueryLowerBounds.Distances fromSource,
            QueryLowerBounds.Distances toDestination) {
        Set<Integer> selected = Set.copyOf(selectedPivots);
        List<Map<String, Object>> output = new ArrayList<>();
        for (List<Integer> path : targetPaths) {
            List<Integer> pivotsOnPath =
                    selectedPivotsOnPath(path, selected);
            List<Map<String, Object>> details = new ArrayList<>();
            List<Integer> segment = new ArrayList<>();
            int segmentSource = row.source();
            boolean allMatch = true;
            int segmentIndex = 0;
            for (int arcId : path) {
                if (selected.contains(arcId)) {
                    Edge pivot = graph.edges().get(arcId);
                    allMatch &= addStaticWitnessCheck(
                            details, segmentIndex++, segmentSource,
                            pivot.source(), List.copyOf(segment),
                            row, fromSource, toDestination);
                    segment.clear();
                    segmentSource = pivot.target();
                    continue;
                }
                segment.add(arcId);
            }
            allMatch &= addStaticWitnessCheck(
                    details, segmentIndex, segmentSource,
                    row.destination(), List.copyOf(segment),
                    row, fromSource, toDestination);
            output.add(Map.of(
                    "path", path,
                    "selected_pivot_count",
                    pivotsOnPath.size(),
                    "all_connector_segments_match_static_witness",
                    allMatch,
                    "segments", details));
        }
        return List.copyOf(output);
    }

    private static boolean addStaticWitnessCheck(
            List<Map<String, Object>> details,
            int segmentIndex,
            int segmentSource,
            int segmentTarget,
            List<Integer> segment,
            QueryRow row,
            QueryLowerBounds.Distances fromSource,
            QueryLowerBounds.Distances toDestination) {
        List<Integer> witness = List.of();
        String source = "NONE";
        if (segmentTarget == row.destination()
                && toDestination.reached(segmentSource)) {
            witness = toDestination.witnessArcIds(segmentSource);
            source = "QUERY_BACKWARD_LABEL";
        } else if (segmentSource == row.source()
                && fromSource.reached(segmentTarget)) {
            witness = fromSource.witnessArcIds(segmentTarget);
            source = "QUERY_FORWARD_LABEL";
        } else if (segmentSource == segmentTarget) {
            witness = List.of();
            source = "IDENTITY";
        }
        boolean match = segment.equals(witness);
        details.add(Map.of(
                "segment_index", segmentIndex,
                "source", segmentSource,
                "target", segmentTarget,
                "segment_path", segment,
                "witness_source", source,
                "lower_bound_witness", witness,
                "path_agreement", match));
        return match;
    }

    private static List<Integer> selectedPivotsOnPath(
            List<Integer> path,
            Set<Integer> selected) {
        return path.stream().filter(selected::contains).toList();
    }

    private static Map<String, Object> intervalBest(
            TDGraph graph,
            QuerySpec query,
            PaceGenerationResult generated) {
        ExactPathValidator validator = new ExactPathValidator(graph);
        IPCMaxResult best = IPCMaxResult.notFound(
                "empty diagnostic candidate registry");
        for (CandidateProfile candidate :
                generated.frontier().candidates()) {
            for (int departure = query.departureStart();
                    departure <= query.departureEnd(); departure++) {
                if (!candidate.domain().contains(departure)) {
                    continue;
                }
                ValidationResult validation = validator.validate(
                        query.source(), query.destination(), departure,
                        query.maxTravelTime(),
                        candidate.pathPointer().toPath());
                if (!validation.valid()) {
                    continue;
                }
                IPCMaxResult value = new IPCMaxResult(
                        true, departure, validation.arrivalTime(),
                        validation.travelTime(), validation.score(),
                        validation.path(), "");
                if (ResultComparator.INSTANCE.compare(value, best) < 0) {
                    best = value;
                }
            }
        }
        if (!best.found()) {
            return Map.of("found", false);
        }
        return Map.ofEntries(
                Map.entry("found", true),
                Map.entry("departure", best.departureTime()),
                Map.entry("score", best.score()),
                Map.entry("travel_time", best.travelTime()),
                Map.entry("path", best.path().arcIds()));
    }

    private static Map<String, Object> generationStats(
            PaceGenerationStats value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("corridor_nodes", value.corridorNodes());
        result.put("corridor_edges", value.corridorEdges());
        result.put("corridor_cells", value.corridorCells());
        result.put("score_relevant_edges", value.scoreRelevantEdges());
        result.put("selected_pivots", value.selectedPivots());
        result.put("connector_calls", value.connectorCalls());
        result.put("connector_expansions", value.connectorExpansions());
        result.put("valid_connectors", value.validConnectors());
        result.put("invalid_connectors", value.invalidConnectors());
        result.put("connector_cap_hits", value.connectorCapHits());
        result.put("candidates_generated", value.candidatesGenerated());
        result.put("candidates_retained", value.candidatesRetained());
        result.put("breakpoint_cap_hits", value.breakpointCapHits());
        result.put("total_work", value.totalWork());
        result.put("query_work_cap_hits", value.queryWorkCapHits());
        result.put("frontier_cells", value.frontierCells());
        result.put("peak_frontier_size", value.peakFrontierSize());
        result.put("output_checksum", value.outputChecksum());
        return result;
    }

    private static ValidationResult validate(
            ExactPathValidator validator,
            QuerySpec query,
            int departure,
            StoredCell cell) {
        if (!cell.resolved()) {
            return ValidationResult.invalid(
                    "stored assignment unresolved", departure,
                    edu.ipcmax.core.validate.Path.empty());
        }
        return validator.validate(
                query.source(), query.destination(), departure,
                query.maxTravelTime(),
                new edu.ipcmax.core.validate.Path(cell.arcIds()));
    }

    private static void registerPath(
            TDGraph graph,
            Map<List<Integer>, Map<String, Object>> registry,
            List<Integer> path) {
        if (path.isEmpty() || registry.containsKey(path)) {
            return;
        }
        long distance = 0;
        for (int arcId : path) {
            distance += graph.edges().get(arcId).distance();
        }
        registry.put(path, Map.of(
                "arc_ids", path,
                "edge_count", path.size(),
                "dimacs_distance_units", distance));
    }

    private static List<StoredCell> cells(JsonNode cells) {
        if (!cells.isArray()) {
            return List.of();
        }
        List<StoredCell> result = new ArrayList<>();
        for (JsonNode cell : cells) {
            List<Integer> arcs = new ArrayList<>();
            for (JsonNode arc : cell.path("arc_ids")) {
                arcs.add(arc.asInt());
            }
            result.add(new StoredCell(
                    cell.path("start").asInt(),
                    cell.path("end").asInt(),
                    cell.path("resolved").asBoolean(),
                    List.copyOf(arcs)));
        }
        return List.copyOf(result);
    }

    private static StoredCell at(
            List<StoredCell> cells,
            int departure) {
        for (StoredCell cell : cells) {
            if (departure >= cell.start()
                    && departure <= cell.end()) {
                return cell;
            }
        }
        return new StoredCell(
                departure, departure, false, List.of());
    }

    private static Map<String, JsonNode> readOfficial(
            Path path) throws IOException {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(
                path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    JsonNode node = JSON.readTree(line);
                    result.put(node.path("query_id").asText(), node);
                }
            }
        }
        return Map.copyOf(result);
    }

    private static List<QueryRow> readQueries(
            Path path,
            String onlyId) throws IOException {
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
                        node.path("source").asInt(),
                        node.path("destination").asInt(),
                        node.path("period_id").asText(),
                        node.path("interval_start").asInt(),
                        node.path("interval_end").asInt(),
                        node.path("budget").asDouble(),
                        node.path("rho").asDouble());
                if (onlyId == null || onlyId.equals(row.queryId())) {
                    result.add(row);
                }
            }
        }
        if (result.isEmpty()) {
            throw new IOException("no selected queries in " + path);
        }
        return List.copyOf(result);
    }

    private static boolean same(double left, double right) {
        return Double.isFinite(left) && Double.isFinite(right)
                && Math.abs(left - right) <= 1e-9;
    }

    private static Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private record StoredCell(
            int start,
            int end,
            boolean resolved,
            List<Integer> arcIds) {
    }

    private record FreshPoint(
            boolean valid,
            double travelTime,
            int score,
            List<Integer> arcIds) {
    }

    private record BaselineKey(
            int source,
            int destination,
            int intervalStart,
            int intervalEnd) {
    }

    private record PaceRun(
            EnvelopeProfile profile,
            PaceGenerationResult generation,
            PaceExecutionMetrics.Snapshot metrics) {
    }

    private record QueryRow(
            String queryId,
            String pairId,
            int source,
            int destination,
            String periodId,
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
            Path resultFile,
            Path output,
            Path scopeOutput,
            String queryId,
            boolean traceBounded) {
        static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            boolean trace = true;
            for (int index = 0; index < args.length; index++) {
                String key = args[index];
                if ("--no-trace-bounded".equals(key)) {
                    trace = false;
                    continue;
                }
                if (index + 1 >= args.length) {
                    throw usage();
                }
                values.put(key, args[++index]);
            }
            for (String required : List.of(
                    "--dataset", "--query-file",
                    "--result-file", "--output")) {
                if (!values.containsKey(required)) {
                    throw usage();
                }
            }
            return new Options(
                    Path.of(values.get("--dataset")),
                    Path.of(values.get("--query-file")),
                    Path.of(values.get("--result-file")),
                    Path.of(values.get("--output")),
                    values.containsKey("--scope-output")
                            ? Path.of(values.get("--scope-output"))
                            : null,
                    values.get("--query-id"),
                    trace);
        }

        private static IllegalArgumentException usage() {
            return new IllegalArgumentException(
                    "Usage: NycStoryDiagnosticBench --dataset DIR "
                            + "--query-file JSONL --result-file JSONL "
                            + "--output JSONL [--scope-output JSON] "
                            + "[--query-id ID] [--no-trace-bounded]");
        }
    }
}
