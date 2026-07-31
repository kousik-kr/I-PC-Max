package edu.ipcmax.experiments.algorithms;

import java.util.Map;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.QueryPreparationIndexes;
import edu.ipcmax.core.pcmax.PACE;
import edu.ipcmax.core.pcmax.PaceCompletion;
import edu.ipcmax.core.pcmax.PaceExactnessScope;
import edu.ipcmax.core.pcmax.PaceExecutionMetrics;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.AlgorithmResult;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentAlgorithm;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;

/** Adapter preserving the finalized shared PACE-X/PACE-B implementation. */
public final class PaceExperimentAlgorithm implements ExperimentAlgorithm {
    private final String id;
    private volatile TDGraph preparedGraph;
    private volatile QueryPreparationIndexes preparedIndexes;

    public PaceExperimentAlgorithm(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public synchronized void prepare(
            TDGraph graph,
            AlgorithmConfig config) {
        if (preparedGraph == graph && preparedIndexes != null) {
            return;
        }
        preparedIndexes = PACE.preparedIndexes(graph);
        preparedGraph = graph;
    }

    @Override
    public AlgorithmResult run(
            TDGraph graph, QuerySpec query, AlgorithmConfig config,
            ExperimentInstrumentation instrumentation) {
        prepare(graph, config);
        PaceExecutionMetrics metrics =
                PaceExecutionMetrics.live(instrumentation::accept);
        PACE pace = new PACE(
                graph,
                config.paceOptions(),
                preparedIndexes,
                metrics);
        edu.ipcmax.core.pcmax.EnvelopeProfile profile;
        try {
            profile = pace.run(query);
        } finally {
            instrumentation.accept(metrics.snapshot());
            metrics.close();
        }
        var generation = pace.lastGenerationResult();
        var stats = pace.stats();
        instrumentation.addCounter("recursive_calls", stats.recursionCalls());
        instrumentation.addCounter("anchors_examined", stats.anchorsConsidered());
        instrumentation.addCounter("anchors_retained", stats.anchorsRetained());
        instrumentation.addCounter("connector_paths_enumerated", stats.connectorCandidates());
        instrumentation.addCounter("stitch_successes", stats.stitchedCandidates());
        instrumentation.addCounter("memo_hits", stats.cacheHits());
        instrumentation.addCounter("memo_misses", stats.cacheMisses());
        instrumentation.addCounter("parallel_tasks_started", stats.parallelTasksStarted());
        instrumentation.addCounter("corridor_nodes", stats.corridorNodes());
        instrumentation.addCounter("corridor_edges", stats.corridorEdges());
        instrumentation.addCounter("corridor_cells", stats.corridorCells());
        instrumentation.addCounter("score_relevant_edges", stats.scoreRelevantEdges());
        instrumentation.addCounter("selected_pivots", stats.selectedPivots());
        instrumentation.addCounter("connector_calls", stats.connectorCalls());
        instrumentation.addCounter("connector_expansions", stats.connectorExpansions());
        instrumentation.addCounter("valid_connectors", stats.validConnectors());
        instrumentation.addCounter("invalid_connectors", stats.invalidConnectors());
        instrumentation.addCounter("connector_cap_hits", stats.connectorCapHits());
        instrumentation.addCounter("candidates_generated", stats.candidatesGenerated());
        instrumentation.addCounter("candidates_retained", stats.candidatesRetained());
        instrumentation.addCounter("breakpoint_cap_hits", stats.breakpointCapHits());
        instrumentation.addCounter("total_candidate_work", stats.totalWork());
        instrumentation.addCounter("query_work_cap_hits", stats.queryWorkCapHits());
        instrumentation.addCounter("frontier_cells", stats.frontierCells());
        instrumentation.addCounter("peak_frontier_size", stats.peakFrontierSize());
        instrumentation.addCounter("memo_lookups", stats.cacheLookups());
        instrumentation.addCounter("memo_waits", stats.cacheWaits());
        instrumentation.addCounter("requested_workers", stats.requestedWorkers());
        instrumentation.addCounter("observed_workers", stats.observedWorkers());
        boolean feasible = profile.segments().stream().anyMatch(segment -> segment.found());
        ExperimentStatus status = switch (generation.completion()) {
            case COMPLETE -> feasible
                    ? ExperimentStatus.COMPLETED
                    : ExperimentStatus.NO_FEASIBLE_PATH;
            case NO_FEASIBLE_PATH -> ExperimentStatus.NO_FEASIBLE_PATH;
            case RESOURCE_TRUNCATED, ABORTED ->
                    ExperimentStatus.LIMIT_EXCEEDED;
        };
        ExactnessScope exactness = switch (generation.exactnessScope()) {
            case GLOBAL_CERTIFIED -> ExactnessScope.GLOBAL_CERTIFIED;
            case RETAINED_FRONTIER -> ExactnessScope.RETAINED_FRONTIER;
            case NOT_CERTIFIED -> ExactnessScope.NOT_CERTIFIED;
        };
        Map<String, Object> scalars = new java.util.LinkedHashMap<>();
        scalars.put("generation_completion", generation.completion().name());
        scalars.put("cap_triggered", generation.capStatus().triggered().stream()
                .map(Enum::name).sorted().toList());
        scalars.put("first_cap_work_item",
                generation.capStatus().firstCanonicalWorkItem());
        scalars.put("corridor_checksum", generation.corridorChecksum());
        scalars.put("selected_pivot_arc_ids",
                generation.selectedPivotArcIds());
        scalars.put("output_checksum", generation.outputChecksum());
        addRetainedPathStatistics(
                scalars, generation.frontier());
        return new AlgorithmResult(
                status,
                profile,
                exactness,
                scalars,
                null,
                null);
    }

    private static void addRetainedPathStatistics(
            Map<String, Object> scalars,
            edu.ipcmax.core.profile.CandidateSet frontier) {
        java.util.Map<java.util.List<Integer>, Integer> distinct =
                new java.util.TreeMap<>(
                        edu.ipcmax.core.profile.PathPointer
                                .STABLE_PATH_ORDER);
        for (edu.ipcmax.core.profile.CandidateProfile candidate :
                frontier.candidates()) {
            distinct.putIfAbsent(
                    candidate.stablePathId(),
                    candidate.edgeCount());
        }
        java.util.List<Integer> counts =
                distinct.values().stream().sorted().toList();
        scalars.put(
                "final_retained_candidate_count",
                frontier.size());
        scalars.put("distinct_path_count", distinct.size());
        if (counts.isEmpty()) {
            scalars.put("path_edge_count_min", 0);
            scalars.put("path_edge_count_sum", 0);
            scalars.put("path_edge_count_mean", 0.0);
            scalars.put("path_edge_count_median", 0.0);
            scalars.put("path_edge_count_p95", 0);
            scalars.put("path_edge_count_max", 0);
            return;
        }
        long sum = counts.stream()
                .mapToLong(Integer::longValue).sum();
        double median = counts.size() % 2 == 1
                ? counts.get(counts.size() / 2)
                : (counts.get(counts.size() / 2 - 1)
                    + counts.get(counts.size() / 2)) / 2.0;
        int p95Index = Math.max(
                0,
                (int) Math.ceil(
                        0.95 * counts.size()) - 1);
        scalars.put("path_edge_count_min", counts.get(0));
        scalars.put("path_edge_count_sum", sum);
        scalars.put(
                "path_edge_count_mean",
                (double) sum / counts.size());
        scalars.put("path_edge_count_median", median);
        scalars.put(
                "path_edge_count_p95",
                counts.get(p95Index));
        scalars.put(
                "path_edge_count_max",
                counts.get(counts.size() - 1));
    }
}
