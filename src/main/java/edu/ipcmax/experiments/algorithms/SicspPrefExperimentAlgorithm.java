package edu.ipcmax.experiments.algorithms;

import edu.ipcmax.baselines.sicsp.SicspPrefQuery;
import edu.ipcmax.baselines.sicsp.SicspPrefResult;
import edu.ipcmax.baselines.sicsp.SicspPreferenceEngine;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.EnvelopeExtractor;
import edu.ipcmax.core.pcmax.EnvelopeProfile;
import edu.ipcmax.core.pcmax.ExactPathProfileBuilder;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.AlgorithmResult;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentAlgorithm;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Research runner for Yuan et al.'s SICSP core with PACE preference semantics. */
public final class SicspPrefExperimentAlgorithm implements ExperimentAlgorithm {
    private static final int ORACLE_MAX_NODES = 12;
    private static final int ORACLE_MAX_EDGES = 64;

    private record OracleOutcome(
            EnvelopeProfile profile,
            long simplePaths,
            boolean pathCapReached,
            long elapsedNanos) {
    }

    @Override
    public String id() {
        return "sicsp-pref";
    }

    @Override
    public AlgorithmResult run(
            TDGraph graph,
            QuerySpec query,
            AlgorithmConfig config,
            ExperimentInstrumentation instrumentation) {
        SicspPrefResult result = new SicspPreferenceEngine(graph).query(new SicspPrefQuery(
                query.source(), query.destination(), query.departureDomain(), query.maxTravelTime()));
        var stats = result.statistics();
        instrumentation.setTiming("query_execution", stats.queryExecutionNanos());
        instrumentation.setTiming("candidate_generation",
                result.candidateGeneration().candidateGenerationNanos());
        instrumentation.setTiming("candidate_adaptation", stats.candidateAdaptationNanos());
        instrumentation.setTiming("profile_replay", stats.profileReplayNanos());
        instrumentation.setTiming("envelope_construction", stats.envelopeConstructionNanos());
        instrumentation.addCounter("edges_considered", stats.edgesConsidered());
        instrumentation.addCounter("forward_search_calls", stats.forwardSearchCalls());
        instrumentation.addCounter("reverse_search_calls", stats.reverseSearchCalls());
        instrumentation.addCounter("o_temporal_events", stats.oTemporalEvents());
        instrumentation.addCounter("w_temporal_events", stats.wTemporalEvents());
        instrumentation.addCounter("duplicate_candidate_events", stats.duplicateCandidateEvents());
        instrumentation.addCounter("raw_o_points", stats.rawOPoints());
        instrumentation.addCounter("raw_w_points", stats.rawWPoints());
        instrumentation.addCounter("candidates_removed_by_dominance", stats.candidatesRemovedByDominance());
        instrumentation.addCounter("non_simple_event_candidates_rejected",
                stats.nonSimpleEventCandidatesRejected());
        instrumentation.addCounter("distinct_non_simple_routes_rejected",
                stats.distinctNonSimpleRoutesRejected());
        instrumentation.addCounter("raw_candidate_routes", stats.rawCandidateRoutes());
        instrumentation.addCounter("simple_candidate_routes", stats.simpleCandidateRoutes());
        instrumentation.addCounter("out_of_query_candidate_routes", stats.outOfQueryCandidateRoutes());
        instrumentation.addCounter("retained_distinct_candidates", stats.retainedDistinctCandidates());
        instrumentation.addCounter("candidates_with_nonempty_feasible_domains",
                stats.candidatesWithNonemptyFeasibleDomains());
        instrumentation.addCounter("candidates_with_empty_feasible_domains",
                stats.candidatesWithEmptyFeasibleDomains());
        instrumentation.addCounter("profile_cells", stats.profileCellCount());
        instrumentation.addCounter("feasible_profile_cells", stats.feasibleProfileCellCount());
        instrumentation.addCounter("sampled_heap_bytes", stats.sampledHeapBytes());

        Map<String, Object> scalars = new LinkedHashMap<>();
        // P1-B is not an optimality-scope issue: scalar replay and composed
        // profiles disagree at fractional temporal boundaries. Preserve the
        // research output, but do not certify even the retained frontier.
        scalars.put("objective", "PREFERENCE_MAX_PROFILE");
        scalars.put("numerical_contract", "LEGACY_MIXED_PROFILE_AND_SCALAR_12DP-v1");
        scalars.put("scalar_replay_numerical_contract", "CANONICAL_PER_OPERATION_12DP-v1");
        scalars.put("profile_numerical_contract", "CANONICAL_CONTINUOUS_PWL_12DP-v1");
        scalars.put("known_temporal_issue", "P1-B");
        scalars.put("temporal_correctness_verified", false);
        scalars.put("numerical_repair_status", "UNRESOLVED");
        scalars.put("candidate_scope", "NATIVE_SICSP_RETAINED_SIMPLE_ROUTES");
        scalars.put("reported_feasibility_model", "LEGACY_COMPOSED_PROFILE");
        scalars.put("oracle_numerical_contract", "CANONICAL_CONTINUOUS_PWL_12DP-v1");
        scalars.put("oracle_is_independent_temporal_validation", false);
        scalars.put("oracle_quality_gap_interpretation", "LEGACY_PROFILE_MODEL_DIAGNOSTIC_ONLY");
        scalars.put("query_execution_nanos", stats.queryExecutionNanos());
        scalars.put("candidate_generation_nanos", stats.candidateGenerationNanos());
        scalars.put("candidate_adaptation_nanos", stats.candidateAdaptationNanos());
        scalars.put("forward_search_calls", stats.forwardSearchCalls());
        scalars.put("reverse_search_calls", stats.reverseSearchCalls());
        scalars.put("o_temporal_events", stats.oTemporalEvents());
        scalars.put("w_temporal_events", stats.wTemporalEvents());
        scalars.put("duplicate_candidate_events", stats.duplicateCandidateEvents());
        scalars.put("raw_o_points", stats.rawOPoints());
        scalars.put("raw_w_points", stats.rawWPoints());
        scalars.put("raw_candidate_routes", stats.rawCandidateRoutes());
        scalars.put("simple_candidate_routes", stats.simpleCandidateRoutes());
        scalars.put("out_of_query_candidate_routes", stats.outOfQueryCandidateRoutes());
        scalars.put("candidates_removed_by_dominance", stats.candidatesRemovedByDominance());
        scalars.put("non_simple_event_candidates_rejected", stats.nonSimpleEventCandidatesRejected());
        scalars.put("distinct_non_simple_routes_rejected", stats.distinctNonSimpleRoutesRejected());
        Map<String, Long> rejectionReasons = new LinkedHashMap<>();
        result.rejectedNonSimpleEvents().forEach(candidate -> {
            String reason = candidate.paceRejectionReason();
            String key = reason.contains(":") ? reason.substring(0, reason.indexOf(':')) : reason;
            rejectionReasons.merge(key, 1L, Long::sum);
        });
        scalars.put("non_simple_rejection_reasons", Map.copyOf(rejectionReasons));
        scalars.put("retained_distinct_candidates", stats.retainedDistinctCandidates());
        scalars.put("candidates_with_nonempty_feasible_domains",
                stats.candidatesWithNonemptyFeasibleDomains());
        scalars.put("candidates_with_empty_feasible_domains",
                stats.candidatesWithEmptyFeasibleDomains());
        scalars.put("profile_replay_nanos", stats.profileReplayNanos());
        scalars.put("envelope_construction_nanos", stats.envelopeConstructionNanos());
        scalars.put("profile_cell_count", stats.profileCellCount());
        scalars.put("feasible_profile_cell_count", stats.feasibleProfileCellCount());
        scalars.put("feasible_coverage", stats.feasibleCoverage());
        scalars.put("integrated_preference_score", stats.integratedPreferenceScore());
        scalars.put("minimum_selected_travel_time", stats.minimumSelectedTravelTime());
        scalars.put("average_selected_travel_time", stats.averageSelectedTravelTime());
        scalars.put("maximum_selected_travel_time", stats.maximumSelectedTravelTime());
        scalars.put("sampled_heap_bytes", stats.sampledHeapBytes());

        String oracleStatus;
        long oracleNanos = 0;
        if (graph.nodeCount() > ORACLE_MAX_NODES || graph.edgeCount() > ORACLE_MAX_EDGES) {
            oracleStatus = "not_computed_graph_too_large";
        } else {
            OracleOutcome oracle = exhaustiveOracle(graph, query, config.maxEnumeratedPaths());
            oracleNanos = oracle.elapsedNanos();
            if (oracle.pathCapReached()) {
                oracleStatus = "not_computed_path_or_work_cap";
            } else {
                oracleStatus = "computed_exhaustive_vertex_simple_paths";
                double oracleScore = integratedScore(oracle.profile());
                double gap = Math.max(0.0, oracleScore - stats.integratedPreferenceScore());
                scalars.put("oracle_integrated_preference_score", oracleScore);
                scalars.put("oracle_quality_gap", gap);
                scalars.put("oracle_relative_quality_gap",
                        oracleScore == 0 ? 0.0 : gap / oracleScore);
                scalars.put("oracle_simple_paths", oracle.simplePaths());
            }
        }
        scalars.put("oracle_quality_gap_status", oracleStatus);
        scalars.put("oracle_validation_nanos", oracleNanos);

        boolean feasible = result.envelope().segments().stream().anyMatch(segment -> segment.found());
        return new AlgorithmResult(
                feasible ? ExperimentStatus.COMPLETED
                        : ExperimentStatus.UNRESOLVED_NO_GENERATED_CANDIDATE,
                result.envelope(),
                ExactnessScope.NOT_CERTIFIED,
                scalars,
                null,
                null);
    }

    private static OracleOutcome exhaustiveOracle(TDGraph graph, QuerySpec query, long maxPaths) {
        long started = System.nanoTime();
        CandidateSet candidates = new CandidateSet();
        var replay = ExactPathProfileBuilder.budgetContext(
                graph, query.departureDomain(), query.maxTravelTime(), () -> false);
        long[] pathCount = {0};
        long[] searchStates = {0};
        long maxSearchStates = maxPaths >= 125_000
                ? 2_000_000L : Math.max(1_000L, Math.max(0L, maxPaths) * 16L);
        boolean[] capped = {false};
        if (query.source() == query.destination()) {
            pathCount[0] = 1;
            replay.replay(List.of(), query.source(), query.destination()).ifPresent(candidates::add);
        } else {
            Set<Integer> visited = new HashSet<>();
            visited.add(query.source());
            enumerate(graph, query.source(), query.source(), query.destination(), visited, new ArrayList<>(),
                    maxPaths, maxSearchStates, pathCount, searchStates, capped, replay, candidates);
        }
        EnvelopeProfile profile = EnvelopeExtractor.extract(candidates, query.departureDomain());
        return new OracleOutcome(profile, pathCount[0], capped[0], System.nanoTime() - started);
    }

    private static void enumerate(
            TDGraph graph,
            int source,
            int current,
            int destination,
            Set<Integer> visited,
            List<Integer> path,
            long maxPaths,
            long maxSearchStates,
            long[] pathCount,
            long[] searchStates,
            boolean[] capped,
            ExactPathProfileBuilder.HorizonReplayContext replay,
            CandidateSet candidates) {
        if (capped[0]) {
            return;
        }
        for (Edge edge : graph.outgoingEdges(current)) {
            searchStates[0]++;
            if (searchStates[0] > maxSearchStates) {
                capped[0] = true;
                return;
            }
            if (visited.contains(edge.target())) {
                continue;
            }
            path.add(edge.arcId());
            if (edge.target() == destination) {
                pathCount[0]++;
                if (pathCount[0] > maxPaths) {
                    capped[0] = true;
                } else {
                    replay.replay(path, source, destination).ifPresent(candidates::add);
                }
            } else {
                visited.add(edge.target());
                enumerate(graph, source, edge.target(), destination, visited, path,
                        maxPaths, maxSearchStates, pathCount, searchStates, capped, replay, candidates);
                visited.remove(edge.target());
            }
            path.remove(path.size() - 1);
            if (capped[0]) {
                return;
            }
        }
    }

    private static double integratedScore(EnvelopeProfile profile) {
        double total = 0;
        for (var segment : profile.segments()) {
            if (!segment.found()) {
                continue;
            }
            for (var score : segment.candidate().scoreProfile().intervals()) {
                double left = Math.max(segment.interval().start(), score.startMinute());
                double right = Math.min(segment.interval().end(), score.endMinute());
                if (right > left) {
                    total += (right - left) * score.value();
                }
            }
        }
        return total;
    }
}
