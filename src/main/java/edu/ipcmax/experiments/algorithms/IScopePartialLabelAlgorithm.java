package edu.ipcmax.experiments.algorithms;

import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceObjective;
import edu.ipcmax.baselines.exact.preference.ExactScoreProfile;
import edu.ipcmax.baselines.exact.preference.ExactTravelBudgetDomain;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.validate.Path;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.AlgorithmResult;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentAlgorithm;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;
import edu.ipcmax.experiments.framework.QueryDeadline;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Exact-function iSCOPE adaptation with Opt1 or Opt1+approximate Opt2. */
public final class IScopePartialLabelAlgorithm implements ExperimentAlgorithm {
    private static final long DEFAULT_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long MAX_FINALIZATION_RESERVE_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final String DEFAULT_OPT2_THRESHOLD_MINUTES = "2";

    private final String registryId;
    private final boolean approximateOpt2;
    private final LongSupplier clock;

    public IScopePartialLabelAlgorithm(String registryId) {
        this(registryId, System::nanoTime);
    }

    IScopePartialLabelAlgorithm(String registryId, LongSupplier clock) {
        if (!List.of("iscope-opt1", "iscope-optimized").contains(registryId)) {
            throw new IllegalArgumentException("partial-label iSCOPE ID must be iscope-opt1 or iscope-optimized");
        }
        this.registryId = registryId;
        this.approximateOpt2 = registryId.equals("iscope-optimized");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String id() {
        return registryId;
    }

    @Override
    public AlgorithmResult run(TDGraph graph, QuerySpec query, AlgorithmConfig config,
            ExperimentInstrumentation instrumentation) {
        long started = clock.getAsLong();
        long limit = config.queryTimeLimitOr(DEFAULT_LIMIT_NANOS);
        long reserve = Math.min(MAX_FINALIZATION_RESERVE_NANOS, limit / 10);
        QueryDeadline deadline = QueryDeadline.start(clock, limit, reserve);
        ExactTemporalDomain rootDomain = ExactTemporalDomain.fromLegacy(query.departureDomain());
        ExactTime budget = ExactTime.fromCanonicalDouble(Domain.canonicalTime(query.maxTravelTime()));
        ExactTime mergeThreshold = approximateOpt2 ? opt2Threshold(config) : ExactTime.ZERO;
        long querySeed = querySeed(config.seed(), query);
        SplittableRandom random = new SplittableRandom(querySeed);
        MutableStats stats = new MutableStats();
        stats.random = random;
        stats.opt2Threshold = mergeThreshold;
        Map<Integer, ExactTime> lowerBounds = Map.of();
        boolean searchExhausted = false;
        List<Path> emitted = new ArrayList<>();

        try {
            if (deadline.finalizationDue()) throw new CancellationException("deadline before lower-bound preparation");
            long lowerStarted = clock.getAsLong();
            ExactMinimumTravelLowerBounds.Result lower = ExactMinimumTravelLowerBounds.toTarget(
                    graph, query.destination(), deadline::finalizationDue);
            lowerBounds = lower.distanceToTarget();
            stats.lowerBoundEdgesScanned = lower.edgesScanned();
            stats.lowerBoundWeightsRead = lower.edgeWeightsRead();
            instrumentation.setTiming("exact_lower_bound_preprocessing", clock.getAsLong() - lowerStarted);

            ExactArrivalProfile identity = ExactArrivalProfile.identity(rootDomain);
            ExactScoreProfile zeroScore = ExactScoreProfile.constant(rootDomain, BigInteger.ZERO);
            ExactPartialPathLabel root = new ExactPartialPathLabel(query.source(), List.of(),
                    Set.of(query.source()), rootDomain, identity, zeroScore, rootDomain, false);
            stats.partialLabelsCreated++;
            stats.observeLabel(root);
            long searchStarted = clock.getAsLong();
            searchExhausted = search(graph, query, config, root, lowerBounds, budget,
                    deadline, stats, emitted);
            instrumentation.setTiming("algorithm_search", clock.getAsLong() - searchStarted);
        } catch (CancellationException cancelled) {
            stats.timeCapReached = true;
        }

        long finalizationStarted = clock.getAsLong();
        String tentativeCompletion = searchExhausted ? "EXHAUSTED"
                : stats.pathCapReached ? "PATH_CAPPED_NOT_CERTIFIED" : "TIME_CAPPED_NOT_CERTIFIED";
        ExactPreferenceObjective.Evaluation exact;
        try {
            exact = ExactPreferenceObjective.evaluateIncrementally(
                    graph, query, emitted, clock.getAsLong() - started,
                    tentativeCompletion,
                    approximateOpt2 ? ExactnessScope.NOT_CERTIFIED.name()
                            : searchExhausted && !stats.pathCapReached && !stats.timeCapReached
                                    ? ExactnessScope.GLOBAL_CERTIFIED.name()
                                    : ExactnessScope.NOT_CERTIFIED.name(),
                    approximateOpt2 ? "ISCOPE_OPT2_RETAINED_EXACTLY_REPLAYED_ROUTES"
                            : "ISCOPE_OPT1_EXACTLY_FEASIBLE_EMITTED_SIMPLE_PATHS",
                    deadline::expired);
        } catch (CancellationException cancelled) {
            // The incremental evaluator is fail-closed, but retains only whole committed profiles.
            exact = ExactPreferenceObjective.evaluateIncrementally(
                    graph, query, List.of(), clock.getAsLong() - started,
                    "TIME_CAPPED_NOT_CERTIFIED", ExactnessScope.NOT_CERTIFIED.name(),
                    "ISCOPE_NO_COMPLETED_FINAL_REPLAY", () -> false);
            stats.timeCapReached = true;
        }
        if (!exact.evaluationComplete() || deadline.expired()) stats.timeCapReached = true;
        long finalizationNanos = clock.getAsLong() - finalizationStarted;
        long totalElapsedNanos = deadline.elapsedNanos();
        if (totalElapsedNanos > limit) stats.timeCapReached = true;
        instrumentation.setTiming("exact_candidate_replay_and_envelope", finalizationNanos);

        ExactnessScope exactness = !approximateOpt2 && searchExhausted
                && !stats.pathCapReached && !stats.timeCapReached && exact.evaluationComplete()
                        ? ExactnessScope.GLOBAL_CERTIFIED : ExactnessScope.NOT_CERTIFIED;
        ExperimentStatus status;
        String completion;
        if (stats.timeCapReached) {
            status = ExperimentStatus.TIME_CAPPED_NOT_CERTIFIED;
            completion = "TIME_CAPPED_NOT_CERTIFIED";
        } else if (stats.pathCapReached) {
            status = ExperimentStatus.PATH_CAPPED_NOT_CERTIFIED;
            completion = "PATH_CAPPED_NOT_CERTIFIED";
        } else if (!searchExhausted) {
            status = ExperimentStatus.PATH_CAPPED_NOT_CERTIFIED;
            completion = "INCOMPLETE";
        } else if (approximateOpt2) {
            status = exact.profile().hasFeasibleCell()
                    ? ExperimentStatus.COMPLETED
                    : ExperimentStatus.UNRESOLVED_NO_GENERATED_CANDIDATE;
            completion = "OPT2_APPROXIMATE_SEARCH_COMPLETED_NOT_CERTIFIED";
        } else if (!exact.profile().hasFeasibleCell()) {
            status = ExperimentStatus.NO_FEASIBLE_PATH;
            completion = "NO_FEASIBLE_PATH";
        } else {
            status = ExperimentStatus.CERTIFIED_COMPLETE;
            completion = "CERTIFIED_COMPLETE";
        }

        Map<String, Object> objectiveResult = new LinkedHashMap<>(exact.objectiveResult());
        objectiveResult.put("completion_status", completion);
        objectiveResult.put("exactness_scope", exactness.name());
        objectiveResult.put("end_to_end_query_nanos", totalElapsedNanos);
        Map<String, Object> scalars = stats.toScalars();
        scalars.put("objective", ExactPreferenceObjective.OBJECTIVE);
        scalars.put("numerical_contract", ExactPreferenceObjective.CONTRACT);
        scalars.put("implementation_variant", approximateOpt2
                ? "CONTINUOUS_PARTIAL_FUNCTION_OPT1_OPT2_ADAPTATION-v1"
                : "CONTINUOUS_PARTIAL_FUNCTION_OPT1_EXACT-v1");
        scalars.put("paper_fidelity", approximateOpt2 ? "APPROXIMATE_ADAPTATION" : "OPT1_BOUND_ADAPTATION");
        scalars.put("paper_doi", "10.1007/978-981-95-7251-9_12");
        scalars.put("variant_id", registryId);
        scalars.put("historical_iscope_alias", false);
        scalars.put("search_pruning_policy", "EXACT_RATIONAL_PREFIX_BUDGET_PLUS_STATIC_EDGE_MINIMUM_DIJKSTRA-v1");
        scalars.put("lower_bound_policy", "EXACT_MINIMUM_RATIONAL_EDGE_TRAVEL_TIME_REVERSE_DIJKSTRA-v1");
        scalars.put("euclidean_speed_bound_used", false);
        scalars.put("coordinates_used", false);
        scalars.put("lower_bound_invariant", "LB(v,d)<=every_time_dependent_suffix_travel_duration_from_v_to_d");
        scalars.put("query_deadline_nanos", limit);
        scalars.put("query_time_cap_protocol_seconds", TimeUnit.NANOSECONDS.toSeconds(limit));
        scalars.put("finalization_reserve_nanos", reserve);
        scalars.put("measured_query_elapsed_ns", totalElapsedNanos);
        scalars.put("end_to_end_query_nanos", totalElapsedNanos);
        scalars.put("deadline_overrun_ns", Math.max(0L, totalElapsedNanos - limit));
        scalars.put("deadline_overrun", totalElapsedNanos > limit);
        scalars.put("deadline_cap_triggered", stats.timeCapReached);
        scalars.put("path_cap_triggered", stats.pathCapReached);
        scalars.put("algorithm_completion_status", completion);
        scalars.put("exact_profile_schema", "exact-preference-envelope-v1");
        scalars.put("exact_profile_checksum", objectiveResult.get("exact_profile_checksum"));
        scalars.put("exact_objective_evaluation_nanos", finalizationNanos);
        scalars.put("output_loopless", true);
        scalars.put("output_feasible", exact.profile().hasFeasibleCell());
        scalars.put("output_validation_contract", "CANONICAL_EXACT_PROFILE_AND_VERTEX_SIMPLE-v1");
        scalars.put("registry_variant_id", registryId);
        scalars.put("random_seed", Long.toUnsignedString(querySeed));
        if (approximateOpt2) {
            scalars.put("opt2_breakpoint_threshold_minutes", mergeThreshold.toString());
            scalars.put("opt2_score_selection", "PAPER_ROULETTE_PAIRWISE_LEFT_TO_RIGHT_ADAPTATION-v1");
            scalars.put("opt2_random_draw", "UNIFORM_INTEGER_0_TO_100_INCLUSIVE");
            scalars.put("opt2_arrival_approximation", "LINEAR_INTERPOLATION_BETWEEN_RETAINED_CLUSTER_BOUNDARIES");
            scalars.put("approximate_search_function_pieces_before", stats.approximatePiecesBefore);
            scalars.put("approximate_search_function_pieces_after", stats.approximatePiecesAfter);
            scalars.put("approximate_breakpoints_merged", stats.approximateBreakpointsMerged);
            scalars.put("approximate_roulette_draws", stats.approximateRouletteDraws);
            scalars.put("opt2_maximum_observed_arrival_error_minutes",
                    stats.maximumApproximateArrivalError.toString());
            scalars.put("exact_final_replay_restores_removed_candidates", false);
        }
        scalars.put("objective_result", objectiveResult);
        return new AlgorithmResult(status, null, exactness, scalars, null, null);
    }

    private boolean search(TDGraph graph, QuerySpec query, AlgorithmConfig config,
            ExactPartialPathLabel label, Map<Integer, ExactTime> lowerBounds,
            ExactTime budget, QueryDeadline deadline, MutableStats stats, List<Path> emitted) {
        if (deadline.finalizationDue()) {
            stats.timeCapReached = true;
            return false;
        }
        stats.dfsStatesVisited++;
        stats.observeLabel(label);
        if (label.currentVertex() == query.destination()) {
            if (stats.completedPathsEvaluated >= config.maxEnumeratedPaths()) {
                stats.pathCapReached = true;
                return false;
            }
            stats.completedPathsEvaluated++;
            if (!label.feasibleDepartureSubdomains().isEmpty()) {
                Path path = new Path(label.predecessorArcSequence());
                if (!emitted.contains(path)) emitted.add(path);
            }
            return true;
        }
        if (!lowerBounds.containsKey(label.currentVertex())) {
            stats.safelyPrunedCells += label.feasibleDepartureSubdomains().intervals().size();
            return true;
        }
        for (Edge edge : graph.outgoingEdges(label.currentVertex())) {
            if (deadline.finalizationDue()) {
                stats.timeCapReached = true;
                return false;
            }
            stats.edgesExpanded++;
            if (label.visitedVertices().contains(edge.target())) continue;
            ExactPartialPathLabel next;
            try {
                next = extend(label, edge, deadline::finalizationDue, stats);
            } catch (CancellationException cancelled) {
                stats.timeCapReached = true;
                return false;
            }
            if (next == null) continue;
            ExactTime lowerBound = lowerBounds.get(edge.target());
            if (lowerBound == null) {
                stats.safelyPrunedCells += next.validSourceDepartureSupport().intervals().size();
                continue;
            }
            ExactTemporalDomain beforeBound = next.validSourceDepartureSupport();
            final ExactTravelBudgetDomain allowed;
            try {
                allowed = ExactTravelBudgetDomain.compute(
                        next.exactPrefixArrival(), budget.subtract(lowerBound), deadline::finalizationDue);
            } catch (CancellationException cancelled) {
                stats.timeCapReached = true;
                return false;
            }
            ExactTemporalDomain feasible = beforeBound.intersection(
                    allowed.domain(), deadline::finalizationDue);
            stats.safelyPrunedCells += beforeBound.difference(
                    feasible, deadline::finalizationDue).intervals().size();
            if (feasible.isEmpty()) continue;
            ExactArrivalProfile boundedArrival = next.exactPrefixArrival().restrict(
                    feasible, deadline::finalizationDue);
            ExactScoreProfile boundedScore = next.exactPrefixScore().restrict(
                    feasible, deadline::finalizationDue);
            ExactPartialPathLabel bounded = new ExactPartialPathLabel(next.currentVertex(),
                    next.predecessorArcSequence(), next.visitedVertices(), feasible,
                    boundedArrival, boundedScore, feasible, next.approximateFunctions());
            if (!search(graph, query, config, bounded, lowerBounds, budget, deadline, stats, emitted)) {
                return false;
            }
        }
        stats.searchStatesCompleted++;
        return true;
    }

    private ExactPartialPathLabel extend(ExactPartialPathLabel label, Edge edge,
            java.util.function.BooleanSupplier stopRequested, MutableStats stats) {
        check(stopRequested);
        ExactScoreProfile edgeScore = ExactScoreProfile.edgeScore(edge, stopRequested);
        check(stopRequested);
        ExactTemporalDomain scorePreimage = label.exactPrefixArrival().preimage(
                edgeScore.domain(), stopRequested);
        check(stopRequested);
        ExactTemporalDomain scoreSupport = label.validSourceDepartureSupport().intersection(
                scorePreimage, stopRequested);
        if (scoreSupport.isEmpty()) {
            stats.temporalCellsSplit++;
            return null;
        }
        ExactArrivalProfile scoreEntryMap = label.exactPrefixArrival().restrict(scoreSupport, stopRequested);
        check(stopRequested);
        ExactScoreProfile edgeScoreOnDeparture = edgeScore.pullback(scoreEntryMap, stopRequested);
        check(stopRequested);
        ExactScoreProfile accumulated = label.exactPrefixScore()
                .restrict(scoreSupport, stopRequested).plus(edgeScoreOnDeparture, stopRequested);
        check(stopRequested);
        ExactArrivalProfile edgeArrival = ExactArrivalProfile.edgeArrival(edge, stopRequested);
        check(stopRequested);
        ExactTemporalDomain travelPreimage = label.exactPrefixArrival().preimage(
                edgeArrival.domain(), stopRequested);
        ExactTemporalDomain nextSupport = scoreSupport.intersection(travelPreimage, stopRequested);
        if (nextSupport.isEmpty()) {
            stats.temporalCellsSplit++;
            return null;
        }
        ExactArrivalProfile nextArrival = label.exactPrefixArrival()
                .restrict(nextSupport, stopRequested).composeAfter(edgeArrival, stopRequested);
        ExactScoreProfile nextScore = accumulated.restrict(nextSupport, stopRequested);
        check(stopRequested);

        if (approximateOpt2) {
            ApproximatePartialFunctions.Result merged = ApproximatePartialFunctions.merge(
                    nextArrival, nextScore, nextSupport, opt2ThresholdFromStats(stats), stats.random,
                    stopRequested);
            stats.approximateBreakpointsMerged += merged.mergedBreakpoints();
            stats.approximateRouletteDraws += merged.rouletteDraws();
            if (merged.maximumArrivalError().compareTo(stats.maximumApproximateArrivalError) > 0) {
                stats.maximumApproximateArrivalError = merged.maximumArrivalError();
            }
            stats.approximatePiecesBefore += merged.piecesBefore();
            stats.approximatePiecesAfter += merged.piecesAfter();
            nextArrival = merged.arrival();
            nextScore = merged.score();
        }
        stats.partialFunctionOperations += 6;
        stats.temporalCellsSplit += nextArrival.segments().size() + nextScore.pieceCount();
        List<Integer> path = new ArrayList<>(label.predecessorArcSequence());
        path.add(edge.arcId());
        Set<Integer> visited = new HashSet<>(label.visitedVertices());
        visited.add(edge.target());
        stats.partialLabelsCreated++;
        ExactPartialPathLabel result = new ExactPartialPathLabel(edge.target(), path, visited,
                nextSupport, nextArrival, nextScore, nextSupport, approximateOpt2);
        stats.observeLabel(result);
        return result;
    }

    private ExactTime opt2Threshold(AlgorithmConfig config) {
        if (!approximateOpt2) return ExactTime.ZERO;
        String value = config.objectiveOptions().getOrDefault(
                "iscope_opt2_breakpoint_threshold_minutes", DEFAULT_OPT2_THRESHOLD_MINUTES);
        ExactTime parsed;
        try {
            parsed = ExactTime.parseDecimal(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid Opt2 breakpoint threshold: " + value, invalid);
        }
        if (parsed.signum() <= 0) throw new IllegalArgumentException("Opt2 threshold must be positive");
        return parsed;
    }

    private ExactTime opt2ThresholdFromStats(MutableStats stats) {
        return stats.opt2Threshold;
    }

    private static long querySeed(long configSeed, QuerySpec query) {
        String key = configSeed + "|" + query.source() + "|" + query.destination() + "|"
                + query.departureDomain() + "|" + Domain.canonicalTime(query.maxTravelTime());
        long value = 0xcbf29ce484222325L;
        for (int i = 0; i < key.length(); i++) {
            value ^= key.charAt(i);
            value *= 0x100000001b3L;
        }
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ (value >>> 33);
    }

    private static void check(java.util.function.BooleanSupplier stopRequested) {
        if (stopRequested.getAsBoolean()) throw new CancellationException("iSCOPE partial-label operation cancelled");
    }

    private static final class MutableStats {
        long dfsStatesVisited;
        long partialLabelsCreated;
        long temporalCellsSplit;
        long safelyPrunedCells;
        long edgesExpanded;
        long completedPathsEvaluated;
        long partialFunctionOperations;
        long lowerBoundEdgesScanned;
        long lowerBoundWeightsRead;
        long approximateBreakpointsMerged;
        long approximateRouletteDraws;
        long approximatePiecesBefore;
        long approximatePiecesAfter;
        ExactTime maximumApproximateArrivalError = ExactTime.ZERO;
        long peakEstimatedLabelBytes;
        long peakHeapObservedBytes;
        long searchStatesCompleted;
        boolean timeCapReached;
        boolean pathCapReached;
        ExactTime opt2Threshold = ExactTime.ZERO;
        SplittableRandom random;

        void observeLabel(ExactPartialPathLabel label) {
            long estimate = 64L + 4L * label.predecessorArcSequence().size()
                    + 8L * label.visitedVertices().size()
                    + 64L * label.validSourceDepartureSupport().intervals().size()
                    + 80L * label.exactPrefixArrival().segments().size()
                    + 64L * label.exactPrefixScore().pieceCount();
            peakEstimatedLabelBytes = Math.max(peakEstimatedLabelBytes, estimate);
            long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            peakHeapObservedBytes = Math.max(peakHeapObservedBytes, heap);
        }

        Map<String, Object> toScalars() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dfs_states_visited", dfsStatesVisited);
            result.put("partial_labels_created", partialLabelsCreated);
            result.put("temporal_cells_split", temporalCellsSplit);
            result.put("cells_safely_pruned", safelyPrunedCells);
            result.put("edges_expanded", edgesExpanded);
            result.put("completed_paths_evaluated", completedPathsEvaluated);
            result.put("search_states_completed", searchStatesCompleted);
            result.put("partial_function_operations", partialFunctionOperations);
            result.put("lower_bound_edges_scanned", lowerBoundEdgesScanned);
            result.put("lower_bound_edge_weights_read", lowerBoundWeightsRead);
            result.put("approximate_breakpoints_merged", approximateBreakpointsMerged);
            result.put("approximate_roulette_draws", approximateRouletteDraws);
            result.put("opt2_maximum_observed_arrival_error_minutes",
                    maximumApproximateArrivalError.toString());
            result.put("approximate_search_function_pieces_before", approximatePiecesBefore);
            result.put("approximate_search_function_pieces_after", approximatePiecesAfter);
            result.put("peak_estimated_label_payload_bytes", peakEstimatedLabelBytes);
            result.put("peak_heap_observed_bytes", peakHeapObservedBytes);
            result.put("time_cap_triggered", timeCapReached);
            result.put("path_cap_triggered", pathCapReached);
            return result;
        }
    }
}
