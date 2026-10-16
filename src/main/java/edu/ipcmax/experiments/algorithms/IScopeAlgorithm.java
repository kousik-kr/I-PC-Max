package edu.ipcmax.experiments.algorithms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.LowerBoundGraph;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.EnvelopeExtractor;
import edu.ipcmax.core.pcmax.EnvelopeProfile;
import edu.ipcmax.core.pcmax.ExactPathProfileBuilder;
import edu.ipcmax.core.pcmax.PaceExecutionMetrics;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.validate.LooplessChecker;
import edu.ipcmax.core.validate.Path;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.AlgorithmResult;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentAlgorithm;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;
import edu.ipcmax.experiments.framework.QueryDeadline;

/**
 * Five-second continuous-profile adaptation of iSCOPE's simple-path recursion.
 *
 * <p>The WISE 2025 optimized implementation composes partial label functions,
 * splits their feasible support, merges breakpoints with randomized scores, and
 * uses parallel work stealing. This implementation profiles completed paths
 * without those optimizations. Its output metadata records that deviation.</p>
 */
public final class IScopeAlgorithm implements ExperimentAlgorithm {
    static final long DEFAULT_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long MAX_FINALIZATION_RESERVE_NANOS =
            TimeUnit.SECONDS.toNanos(1);

    private final LongSupplier clock;
    private volatile TDGraph preparedGraph;
    private volatile LowerBoundGraph preparedLower;

    public IScopeAlgorithm() {
        this(System::nanoTime);
    }

    /** Injectable monotonic clock constructor for deterministic deadline tests. */
    public IScopeAlgorithm(LongSupplier clock) {
        if (clock == null) {
            throw new IllegalArgumentException("monotonic clock is required");
        }
        this.clock = clock;
    }

    @Override
    public String id() {
        return "iscope";
    }

    @Override
    public synchronized void prepare(
            TDGraph graph,
            AlgorithmConfig config) {
        if (preparedGraph == graph && preparedLower != null) {
            return;
        }
        preparedLower = new LowerBoundGraph(graph);
        preparedGraph = graph;
    }

    @Override
    public AlgorithmResult run(
            TDGraph graph,
            QuerySpec query,
            AlgorithmConfig config,
            ExperimentInstrumentation instrumentation) {
        long limit = config.queryTimeLimitOr(DEFAULT_LIMIT_NANOS);
        long reserve = Math.min(MAX_FINALIZATION_RESERVE_NANOS, limit / 10);
        QueryDeadline deadline = QueryDeadline.start(clock, limit, reserve);
        MutableStats stats = new MutableStats();
        CommittedScoreEnvelope committed = new CommittedScoreEnvelope(
                query.departureDomain());
        Set<List<Integer>> acceptedPathIds = new LinkedHashSet<>();
        boolean searchExhausted = false;

        try {
            long lowerStarted = clock.getAsLong();
            LowerBoundGraph lower = preparedGraph == graph
                    && preparedLower != null
                            ? preparedLower
                            : new LowerBoundGraph(
                                    graph, deadline::finalizationDue);
            LowerBoundGraph.Distances toTarget = lower.distancesToTarget(
                    query.destination(), deadline::finalizationDue);
            instrumentation.setTiming(
                    "lower_bound_preprocessing",
                    nonnegative(clock.getAsLong() - lowerStarted));

            ExactPathProfileBuilder.HorizonReplayContext replay =
                    ExactPathProfileBuilder.budgetContext(
                            graph,
                            query.departureDomain(),
                            query.maxTravelTime(),
                            deadline::finalizationDue);

            if (toTarget.reached(query.source())
                    && !deadline.finalizationDue()) {
                List<Integer> witness = toTarget.pathFrom(query.source()).arcIds();
                stats.witnessAttempted++;
                profilePath(
                        graph, query, replay, witness, deadline, committed,
                        acceptedPathIds, stats, instrumentation);
            }

            if (!deadline.finalizationDue()) {
                long enumerationStarted = clock.getAsLong();
                SimplePathSearch.StreamingSearchResult search =
                        SimplePathSearch.exhaustiveStreaming(
                                graph,
                                lower,
                                toTarget,
                                query.source(),
                                query.destination(),
                                query.maxTravelTime(),
                                config.maxEnumeratedPaths(),
                                deadline::finalizationDue,
                                path -> profilePath(
                                        graph, query, replay, path.arcs(), deadline,
                                        committed, acceptedPathIds, stats,
                                        instrumentation));
                instrumentation.addTiming(
                        "enumeration",
                        nonnegative(clock.getAsLong() - enumerationStarted));
                stats.generatedCompletePaths += search.completePaths();
                stats.dfsExpansions += search.dfsExpansions();
                stats.lowerBoundPrunes += search.rejectedLowerBound();
                stats.pathCapReached |= search.pathCapReached();
                stats.timeCapReached |= search.deadlineReached();
                searchExhausted = search.exhausted();
            } else {
                stats.timeCapReached = true;
            }
        } catch (CancellationException cancelled) {
            stats.timeCapReached = true;
        }

        EnvelopeProfile profile = committed.profile;
        stats.outputProfileCells = profile.segments().size();
        stats.profileBreakpoints = committed.accepted.candidates().stream()
                .mapToLong(candidate -> candidate.arrivalProfile().breakpoints().size())
                .sum();
        stats.distinctSelectedPaths = profile.segments().stream()
                .filter(segment -> segment.found())
                .map(segment -> segment.path().arcIds())
                .distinct()
                .count();
        stats.coverage = coverage(profile);
        stats.outputLoopless = profile.segments().stream()
                .filter(segment -> segment.found())
                .allMatch(segment -> LooplessChecker.isLoopless(graph, segment.path()));
        stats.outputFeasible = profile.segments().stream().anyMatch(segment -> segment.found());

        long elapsed = deadline.elapsedNanos();
        instrumentation.setTiming("algorithm_total", elapsed);
        publish(stats, instrumentation);

        ExperimentStatus status;
        ExactnessScope exactness;
        String completion;
        List<String> capTriggered = new ArrayList<>();
        if (stats.timeCapReached || (!searchExhausted && deadline.finalizationDue())) {
            status = ExperimentStatus.TIME_CAPPED_NOT_CERTIFIED;
            exactness = ExactnessScope.NOT_CERTIFIED;
            completion = "TIME_CAPPED_NOT_CERTIFIED";
            capTriggered.add("QUERY_DEADLINE");
        } else if (stats.pathCapReached) {
            status = ExperimentStatus.PATH_CAPPED_NOT_CERTIFIED;
            exactness = ExactnessScope.NOT_CERTIFIED;
            completion = "PATH_CAPPED_NOT_CERTIFIED";
            capTriggered.add("PATH_LIMIT");
        } else if (!stats.outputFeasible) {
            status = ExperimentStatus.NO_FEASIBLE_PATH;
            exactness = ExactnessScope.GLOBAL_CERTIFIED;
            completion = "NO_FEASIBLE_PATH";
        } else {
            status = ExperimentStatus.CERTIFIED_COMPLETE;
            exactness = ExactnessScope.GLOBAL_CERTIFIED;
            completion = "CERTIFIED_COMPLETE";
        }

        Map<String, Object> scalars = stats.scalars();
        scalars.put("algorithm_completion_status", completion);
        scalars.put(
                "generation_completion",
                exactness == ExactnessScope.GLOBAL_CERTIFIED
                        ? stats.outputFeasible ? "COMPLETE" : "NO_FEASIBLE_PATH"
                        : "RESOURCE_TRUNCATED");
        scalars.put("cap_triggered", List.copyOf(capTriggered));
        scalars.put("query_deadline_nanos", limit);
        scalars.put("finalization_reserve_nanos", reserve);
        scalars.put("measured_query_elapsed_ns", elapsed);
        scalars.put("deadline_overrun_ns", Math.max(0L, elapsed - limit));
        scalars.put("deadline_overrun", elapsed > limit);
        scalars.put("deadline_enforcement",
                "COOPERATIVE_MONOTONIC_WITH_FINALIZATION_RESERVE");
        scalars.put("objective", "PREFERENCE_MAX_PROFILE");
        scalars.put("numerical_contract", "LEGACY_CANONICAL_PROFILE_12DP-v1");
        scalars.put("implementation_variant",
                "CONTINUOUS_PROFILE_EXHAUSTIVE_PATH_ADAPTATION-v1");
        scalars.put("paper_fidelity", "DEVIATION");
        scalars.put("paper_doi", "10.1007/978-981-95-7251-9_12");
        scalars.put("partial_label_function_splitting", false);
        scalars.put("breakpoint_merging", false);
        scalars.put("score_randomization", false);
        scalars.put("execution_threads", 1);
        scalars.put("budget_contract", "ABSOLUTE_TRAVEL_TIME");
        scalars.put("departure_time_contract", "CONTINUOUS_CANONICAL_DOMAIN");
        scalars.put(
                "query_independent_lower_bound_weights_prepared",
                preparedGraph == graph && preparedLower != null);
        scalars.put(
                "output_validation_contract",
                "LEGACY_PROFILE_AND_VERTEX_SIMPLE-v2");
        return new AlgorithmResult(
                status, profile, exactness, scalars, null, null);
    }

    private boolean profilePath(
            TDGraph graph,
            QuerySpec query,
            ExactPathProfileBuilder.HorizonReplayContext replay,
            List<Integer> arcs,
            QueryDeadline deadline,
            CommittedScoreEnvelope committed,
            Set<List<Integer>> acceptedPathIds,
            MutableStats stats,
            ExperimentInstrumentation instrumentation) {
        if (deadline.finalizationDue()) {
            stats.timeCapReached = true;
            return false;
        }
        List<Integer> stableArcs = List.copyOf(arcs);
        if (!acceptedPathIds.add(stableArcs)) {
            stats.duplicatePaths++;
            return true;
        }

        long validationStarted = clock.getAsLong();
        boolean loopless = LooplessChecker.isLoopless(graph, new Path(stableArcs));
        instrumentation.addTiming(
                "validation",
                nonnegative(clock.getAsLong() - validationStarted));
        if (!loopless) {
            stats.rejectedPaths++;
            acceptedPathIds.remove(stableArcs);
            return true;
        }

        long profilingStarted = clock.getAsLong();
        try {
            var candidate = replay.replay(
                    stableArcs, query.source(), query.destination());
            instrumentation.addTiming(
                    "profiling",
                    nonnegative(clock.getAsLong() - profilingStarted));
            if (candidate.isEmpty()) {
                stats.rejectedPaths++;
                acceptedPathIds.remove(stableArcs);
                return !deadline.finalizationDue();
            }
            CandidateProfile completed = candidate.get();
            stats.fullyProfiledPaths++;
            long envelopeStarted = clock.getAsLong();
            try {
                committed.commit(completed, deadline);
            } catch (CancellationException cancelled) {
                instrumentation.addTiming(
                        "envelope_extraction",
                        nonnegative(clock.getAsLong() - envelopeStarted));
                instrumentation.addTiming(
                        "envelope",
                        nonnegative(clock.getAsLong() - envelopeStarted));
                stats.envelopeUpdatesDiscarded++;
                stats.timeCapReached = true;
                acceptedPathIds.remove(stableArcs);
                return false;
            }
            long envelopeNanos = nonnegative(
                    clock.getAsLong() - envelopeStarted);
            instrumentation.addTiming("envelope_extraction", envelopeNanos);
            instrumentation.addTiming("envelope", envelopeNanos);
            stats.acceptedProfiles++;
            if (stats.timeToFirstFeasibleProfileNanos < 0) {
                stats.timeToFirstFeasibleProfileNanos = deadline.elapsedNanos();
            }
            return !deadline.finalizationDue();
        } catch (CancellationException cancelled) {
            instrumentation.addTiming(
                    "profiling",
                    nonnegative(clock.getAsLong() - profilingStarted));
            stats.partialProfilesDiscarded++;
            stats.timeCapReached = true;
            acceptedPathIds.remove(stableArcs);
            return false;
        }
    }

    private static void publish(
            MutableStats stats,
            ExperimentInstrumentation instrumentation) {
        stats.scalars().forEach((name, value) -> {
            if (value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long) {
                Number number = (Number) value;
                instrumentation.addCounter(name, number.longValue());
            }
        });
    }

    private static double coverage(EnvelopeProfile profile) {
        double total = profile.domain().intervals().stream()
                .mapToDouble(interval -> interval.end() - interval.start())
                .sum();
        double feasible = profile.segments().stream()
                .filter(segment -> segment.found())
                .mapToDouble(segment -> segment.interval().end()
                        - segment.interval().start())
                .sum();
        if (total == 0) {
            return profile.segments().stream().anyMatch(segment -> segment.found())
                    ? 1.0 : 0.0;
        }
        return feasible / total;
    }

    private static long nonnegative(long value) {
        return Math.max(0L, value);
    }

    private static final class CommittedScoreEnvelope {
        private final Domain rootDomain;
        private final CandidateSet accepted = new CandidateSet();
        private EnvelopeProfile profile;

        private CommittedScoreEnvelope(Domain rootDomain) {
            this.rootDomain = rootDomain;
            this.profile = EnvelopeExtractor.extract(accepted, rootDomain);
        }

        private void commit(
                CandidateProfile candidate,
                QueryDeadline deadline) {
            Map<List<Integer>, CandidateProfile> selected = new TreeMap<>(
                    PathPointer.STABLE_PATH_ORDER);
            profile.segments().stream()
                    .filter(segment -> segment.found())
                    .map(segment -> segment.candidate())
                    .forEach(value -> selected.putIfAbsent(
                            value.stablePathId(), value));
            selected.put(candidate.stablePathId(), candidate);
            CandidateSet reduced = new CandidateSet();
            reduced.addAllCandidates(List.copyOf(selected.values()));
            EnvelopeProfile next = EnvelopeExtractor.extract(
                    reduced,
                    rootDomain,
                    PaceExecutionMetrics.none(),
                    deadline::expired);
            accepted.add(candidate);
            profile = next;
        }
    }

    private static final class MutableStats {
        private long generatedCompletePaths;
        private long fullyProfiledPaths;
        private long acceptedProfiles;
        private long rejectedPaths;
        private long dfsExpansions;
        private long lowerBoundPrunes;
        private long duplicatePaths;
        private long partialProfilesDiscarded;
        private long envelopeUpdatesDiscarded;
        private long witnessAttempted;
        private long profileBreakpoints;
        private long outputProfileCells;
        private long distinctSelectedPaths;
        private long timeToFirstFeasibleProfileNanos = -1;
        private boolean timeCapReached;
        private boolean pathCapReached;
        private boolean outputFeasible;
        private boolean outputLoopless;
        private double coverage;

        private Map<String, Object> scalars() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("generated_complete_paths", generatedCompletePaths);
            result.put("fully_profiled_paths", fullyProfiledPaths);
            result.put("accepted_profiles", acceptedProfiles);
            result.put("rejected_paths", rejectedPaths);
            result.put("dfs_expansions", dfsExpansions);
            result.put("lower_bound_prunes", lowerBoundPrunes);
            result.put("duplicate_paths", duplicatePaths);
            result.put("partial_profiles_discarded", partialProfilesDiscarded);
            result.put("completed_profile_envelope_updates_discarded",
                    envelopeUpdatesDiscarded);
            result.put("witness_paths_attempted", witnessAttempted);
            result.put("profile_breakpoints", profileBreakpoints);
            result.put("output_profile_cells", outputProfileCells);
            result.put("distinct_selected_paths", distinctSelectedPaths);
            result.put("time_to_first_feasible_profile_ns",
                    timeToFirstFeasibleProfileNanos < 0
                            ? -1L : timeToFirstFeasibleProfileNanos);
            result.put("deadline_cap_triggered", timeCapReached);
            result.put("path_cap_triggered", pathCapReached);
            result.put("departure_interval_coverage", coverage);
            result.put("output_feasible", outputFeasible);
            result.put("output_loopless", outputLoopless);
            return result;
        }
    }
}
