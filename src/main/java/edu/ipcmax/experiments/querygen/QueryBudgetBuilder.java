package edu.ipcmax.experiments.querygen;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.LowerBoundGraph;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.labeling.IntervalForwardLabeling;
import edu.ipcmax.core.loader.GeneratedGraphDataset;
import edu.ipcmax.core.loader.ManifestSummary;
import edu.ipcmax.core.pcmax.Anchor;
import edu.ipcmax.core.pcmax.AnchorIndex;
import edu.ipcmax.core.pcmax.PaceException;
import edu.ipcmax.core.pcmax.PaceStatus;

/** Builds exact temporal query budgets and characterization metadata. */
public final class QueryBudgetBuilder {
    public static final int DEFAULT_WINDOW_MINUTES = 120;
    public static final double DEFAULT_MAIN_SLACK = 0.25;
    public static final double DEFAULT_TIGHT_SLACK = 0.05;

    private static final BigDecimal QUARTER = new BigDecimal("0.25");
    private static final Map<TemporalRegime, Integer> DEFAULT_STARTS = defaultStarts();

    private final TDGraph graph;
    private final ManifestSummary manifest;
    private final ManifestSummary.TimeWindow temporalSupport;
    private final IntervalForwardLabeling fastestProfiles;
    private final LowerBoundGraph lowerBounds;
    private final int windowMinutes;
    private final double mainSlack;
    private final double tightSlack;
    private final Map<TemporalRegime, Integer> preferredStarts;

    /** Uses the Phase 4 default window, starts, and budget slack values. */
    public QueryBudgetBuilder(GeneratedGraphDataset dataset) {
        this(dataset, DEFAULT_WINDOW_MINUTES, DEFAULT_MAIN_SLACK, DEFAULT_TIGHT_SLACK, DEFAULT_STARTS);
    }

    /** Uses the checked query-generation configuration for window and fallback values. */
    public QueryBudgetBuilder(GeneratedGraphDataset dataset, QueryGenerationConfig configuration) {
        this(
                dataset,
                requiredConfiguration(configuration).main().windowMinutes(),
                configuration.main().budgetSlack(),
                configuration.tightBudget().slack(),
                configuredStarts(configuration));
        if (!"FULL_INTERVAL_FEASIBLE".equals(configuration.main().budgetPolicy())) {
            throw new IllegalArgumentException("main query budget policy must be FULL_INTERVAL_FEASIBLE");
        }
    }

    QueryBudgetBuilder(
            GeneratedGraphDataset dataset,
            int windowMinutes,
            double mainSlack,
            double tightSlack,
            Map<TemporalRegime, Integer> preferredStarts) {
        Objects.requireNonNull(dataset, "dataset");
        graph = Objects.requireNonNull(dataset.graph(), "dataset.graph");
        manifest = Objects.requireNonNull(dataset.manifest(), "dataset manifest is required");
        temporalSupport = manifest.temporalSupport().orElseThrow(() ->
                new IllegalArgumentException("dataset manifest does not define temporal support"));
        if (windowMinutes <= 0) {
            throw new IllegalArgumentException("query window length must be positive");
        }
        requireFiniteNonnegative(mainSlack, "main budget slack");
        requireFiniteNonnegative(tightSlack, "tight budget slack");
        this.windowMinutes = windowMinutes;
        this.mainSlack = mainSlack;
        this.tightSlack = tightSlack;
        this.preferredStarts = immutableStarts(preferredStarts);
        fastestProfiles = new IntervalForwardLabeling(graph);
        lowerBounds = new LowerBoundGraph(graph);
    }

    /** Builds one temporal regime, returning empty when any exact validity condition fails. */
    public Optional<TemporalQueryBudget> build(QueryPairCandidate pair, TemporalRegime regime) {
        return buildDetailed(pair, regime).budget();
    }

    /** Builds one temporal regime with an explicit window and main-budget slack. */
    public Optional<TemporalQueryBudget> build(
            QueryPairCandidate pair,
            TemporalRegime regime,
            int requestedWindowMinutes,
            double requestedMainSlack) {
        return buildDetailed(pair, regime, requestedWindowMinutes, requestedMainSlack).budget();
    }

    /** Builds one temporal regime and preserves a machine-readable rejection reason. */
    public TemporalBuildResult buildDetailed(QueryPairCandidate pair, TemporalRegime regime) {
        return buildDetailed(pair, regime, windowMinutes, mainSlack);
    }

    /**
     * Builds one temporal regime for an arbitrary positive window and nonnegative main slack.
     * The exact profile and corridor counts are recomputed for the requested query horizon.
     */
    public TemporalBuildResult buildDetailed(
            QueryPairCandidate pair,
            TemporalRegime regime,
            int requestedWindowMinutes,
            double requestedMainSlack) {
        Objects.requireNonNull(pair, "pair");
        Objects.requireNonNull(regime, "regime");
        graph.node(pair.source());
        graph.node(pair.destination());
        return buildDetailed(
                pair,
                regime,
                new CorridorDistances(pair),
                requestedWindowMinutes,
                requestedMainSlack);
    }

    private TemporalBuildResult buildDetailed(
            QueryPairCandidate pair,
            TemporalRegime regime,
            CorridorDistances corridorDistances,
            int requestedWindowMinutes,
            double requestedMainSlack) {

        if (requestedWindowMinutes <= 0) {
            return TemporalBuildResult.failure(failure(
                    FailureReason.BUDGET_INVALID,
                    regime,
                    "query window length must be positive"));
        }
        if (!Double.isFinite(requestedMainSlack) || requestedMainSlack < 0) {
            return TemporalBuildResult.failure(failure(
                    FailureReason.BUDGET_INVALID,
                    regime,
                    "main budget slack must be finite and nonnegative"));
        }

        final int intervalStart;
        try {
            intervalStart = resolveTemporalStart(regime);
        } catch (IllegalArgumentException failure) {
            return TemporalBuildResult.failure(failure(
                    FailureReason.TEMPORAL_REGIME_UNAVAILABLE,
                    regime,
                    failure.getMessage()));
        }
        if (intervalStart < temporalSupport.startMinute()
                || intervalStart > temporalSupport.endMinute()) {
            return TemporalBuildResult.failure(failure(
                    FailureReason.TEMPORAL_REGIME_UNAVAILABLE,
                    regime,
                    "temporal regime starts outside dataset support: " + intervalStart));
        }

        final int intervalEnd;
        try {
            intervalEnd = Math.addExact(intervalStart, requestedWindowMinutes);
        } catch (ArithmeticException overflow) {
            return TemporalBuildResult.failure(failure(
                    FailureReason.BUDGET_INVALID,
                    regime,
                    "query interval end overflows integer minutes"));
        }
        if (intervalEnd > temporalSupport.endMinute()) {
            return TemporalBuildResult.failure(failure(
                    FailureReason.FUNCTION_HORIZON_EXCEEDED,
                    regime,
                    "departure interval exceeds dataset support: ["
                            + intervalStart + "," + intervalEnd + "]"));
        }

        // Every exact fastest travel time is at least the static admissible
        // lower bound. If even that optimistic main budget crosses the
        // function horizon, no profile computation can make this query valid.
        double optimisticMainBudget = scaledBudget(
                pair.lowerBoundDistance(), requestedMainSlack);
        if (!horizonFits(intervalEnd, optimisticMainBudget)) {
            return TemporalBuildResult.failure(failure(
                    FailureReason.FUNCTION_HORIZON_EXCEEDED,
                    regime,
                    "lower-bound budget horizon exceeds dataset support: interval_end="
                            + intervalEnd
                            + ", lower_bound_distance=" + pair.lowerBoundDistance()
                            + ", optimistic_budget=" + optimisticMainBudget
                            + ", support_end=" + temporalSupport.endMinute()));
        }

        Domain departureDomain = Domain.closed(intervalStart, intervalEnd);
        final Optional<IntervalForwardLabeling.FastestTravelTimeProfile> fastest;
        try {
            fastest = fastestProfiles.fastestTravelTimeProfile(
                    pair.source(), pair.destination(), departureDomain);
        } catch (IllegalArgumentException failure) {
            return TemporalBuildResult.failure(failure(
                    FailureReason.FASTEST_PROFILE_UNAVAILABLE,
                    regime,
                    failure.getMessage()));
        }
        if (fastest.isEmpty()) {
            return TemporalBuildResult.failure(failure(
                    FailureReason.FASTEST_PROFILE_UNAVAILABLE,
                    regime,
                    "no exact fastest profile is available for the departure interval"));
        }
        if (!departureDomain.difference(fastest.get().arrivalProfile().domain()).isEmpty()) {
            return TemporalBuildResult.failure(failure(
                    FailureReason.FULL_INTERVAL_INFEASIBLE,
                    regime,
                    "exact fastest profile does not cover the full departure interval"));
        }

        final double fastestMinimum;
        final double fastestMaximum;
        final double mainBudget;
        final TightBudget tightCalculation;
        try {
            fastestMinimum = fastest.get().arrivalProfile().minimumTravelTime(departureDomain);
            fastestMaximum = fastest.get().arrivalProfile().maximumTravelTime(departureDomain);
            mainBudget = scaledBudget(fastestMaximum, requestedMainSlack);
            tightCalculation = calculateTightBudget(fastestMinimum, fastestMaximum, tightSlack);
        } catch (IllegalArgumentException | ArithmeticException failure) {
            return TemporalBuildResult.failure(failure(
                    FailureReason.BUDGET_INVALID,
                    regime,
                    failure.getMessage()));
        }
        if (mainBudget < fastestMaximum) {
            return TemporalBuildResult.failure(failure(
                    FailureReason.FULL_INTERVAL_INFEASIBLE,
                    regime,
                    "main budget is below the exact fastest-profile maximum"));
        }
        if (!horizonFits(intervalEnd, mainBudget)) {
            return TemporalBuildResult.failure(failure(
                    FailureReason.FUNCTION_HORIZON_EXCEEDED,
                    regime,
                    "main budget horizon exceeds dataset support"));
        }

        if (!horizonFits(intervalEnd, tightCalculation.budget())) {
            return TemporalBuildResult.failure(failure(
                    FailureReason.FUNCTION_HORIZON_EXCEEDED,
                    regime,
                    "tight budget horizon exceeds dataset support"));
        }

        try {
            BudgetVariant main = new BudgetVariant(
                    "FULL_INTERVAL_FEASIBLE",
                    mainBudget,
                    requestedMainSlack,
                    countCorridorAnchors(corridorDistances, intervalStart, intervalEnd, mainBudget),
                    true,
                    false);
            BudgetVariant tight = new BudgetVariant(
                    "TIGHT",
                    tightCalculation.budget(),
                    tightSlack,
                    countCorridorAnchors(corridorDistances, intervalStart, intervalEnd,
                            tightCalculation.budget()),
                    tightCalculation.expectedFullIntervalFeasible(),
                    tightCalculation.expectedMixedFeasibility());

            return TemporalBuildResult.success(new TemporalQueryBudget(
                    pair,
                    regime,
                    intervalStart,
                    intervalEnd,
                    requestedWindowMinutes,
                    fastest.get(),
                    fastestMinimum,
                    fastestMaximum,
                    main,
                    tight));
        } catch (PaceException failure) {
            FailureReason reason = failure.status() == PaceStatus.FUNCTION_HORIZON_EXCEEDED
                    ? FailureReason.FUNCTION_HORIZON_EXCEEDED
                    : FailureReason.BUDGET_INVALID;
            return TemporalBuildResult.failure(failure(reason, regime, failure.getMessage()));
        } catch (IllegalArgumentException | ArithmeticException failure) {
            return TemporalBuildResult.failure(failure(
                    FailureReason.BUDGET_INVALID,
                    regime,
                    failure.getMessage()));
        }
    }

    /** Builds all four regimes atomically; one invalid regime rejects the entire pair family. */
    public Optional<TemporalQueryFamily> buildFamily(QueryPairCandidate pair) {
        return buildFamilyDetailed(pair).family();
    }

    /** Builds an atomic four-regime family with an explicit window and main slack. */
    public Optional<TemporalQueryFamily> buildFamily(
            QueryPairCandidate pair,
            int requestedWindowMinutes,
            double requestedMainSlack) {
        return buildFamilyDetailed(pair, requestedWindowMinutes, requestedMainSlack).family();
    }

    /** Builds the configured four-regime family and preserves its first rejection reason. */
    public FamilyBuildResult buildFamilyDetailed(QueryPairCandidate pair) {
        return buildFamilyDetailed(pair, windowMinutes, mainSlack);
    }

    /** Builds an arbitrary-window four-regime family and preserves its first rejection reason. */
    public FamilyBuildResult buildFamilyDetailed(
            QueryPairCandidate pair,
            int requestedWindowMinutes,
            double requestedMainSlack) {
        Objects.requireNonNull(pair, "pair");
        graph.node(pair.source());
        graph.node(pair.destination());
        CorridorDistances corridorDistances = new CorridorDistances(pair);
        EnumMap<TemporalRegime, TemporalQueryBudget> variants = new EnumMap<>(TemporalRegime.class);
        for (TemporalRegime regime : TemporalRegime.values()) {
            TemporalBuildResult variant = buildDetailed(
                    pair,
                    regime,
                    corridorDistances,
                    requestedWindowMinutes,
                    requestedMainSlack);
            if (!variant.succeeded()) {
                return FamilyBuildResult.failure(variant.failure().orElseThrow());
            }
            variants.put(regime, variant.budget().orElseThrow());
        }
        return FamilyBuildResult.success(new TemporalQueryFamily(pair, variants));
    }

    /** Manifest rush starts override configured/default peak starts. */
    public int resolveTemporalStart(TemporalRegime regime) {
        Objects.requireNonNull(regime, "regime");
        Optional<ManifestSummary.TimeWindow> rush = switch (regime) {
            case MORNING_PEAK -> manifest.rushWindow("morning");
            case EVENING_PEAK -> manifest.rushWindow("evening");
            case DAY_OFFPEAK, LATE_OFFPEAK -> Optional.empty();
        };
        double start = rush.map(ManifestSummary.TimeWindow::startMinute)
                .orElse((double) preferredStarts.get(regime));
        if (start != Math.rint(start) || start < Integer.MIN_VALUE || start > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "temporal regime start must be a whole integer minute: " + regime + "=" + start);
        }
        return (int) start;
    }

    /** Dataset-declared temporal support used for all horizon checks. */
    public ManifestSummary.TimeWindow temporalSupport() {
        return temporalSupport;
    }

    /** Exact Phase 4 main-budget formula using the repository time-unit ceiling. */
    public static double calculateMainBudget(double fastestMaximum) {
        return scaledBudget(fastestMaximum, DEFAULT_MAIN_SLACK);
    }

    /** Exact main-budget formula for an explicit nonnegative slack. */
    public static double calculateMainBudget(double fastestMaximum, double slack) {
        return scaledBudget(fastestMaximum, slack);
    }

    /** Exact Phase 4 tight-budget formula using the default five-percent slack. */
    public static TightBudget calculateTightBudget(double fastestMinimum, double fastestMaximum) {
        return calculateTightBudget(fastestMinimum, fastestMaximum, DEFAULT_TIGHT_SLACK);
    }

    /** Rounds upward to one repository time unit ({@code 10^-9} minute). */
    public static double ceilToRepositoryTimeUnit(double value) {
        requireFiniteNonnegative(value, "time value");
        return BigDecimal.valueOf(value)
                .setScale(Domain.REPOSITORY_TIME_UNIT_SCALE, RoundingMode.CEILING)
                .doubleValue();
    }

    private boolean horizonFits(int intervalEnd, double budget) {
        double rawHorizonEnd = intervalEnd + budget;
        return Double.isFinite(rawHorizonEnd)
                && Domain.canonicalTime(rawHorizonEnd) <= temporalSupport.endMinute();
    }

    private int countCorridorAnchors(
            CorridorDistances distances, int intervalStart, int intervalEnd, double budget) {
        Domain queryHorizon = Domain.closed(
                intervalStart,
                Domain.canonicalTime(intervalEnd + budget));
        AnchorIndex anchors = AnchorIndex.create(graph, queryHorizon);
        int count = 0;
        for (Anchor anchor : anchors.anchors()) {
            double prefix = distances.fromSource().distance(anchor.source());
            double suffix = distances.toDestination().distance(anchor.target());
            if (!Double.isFinite(prefix) || !Double.isFinite(suffix)) {
                continue;
            }
            double routeLowerBound = Domain.canonicalTime(
                    prefix + anchor.lowerTravelTime() + suffix);
            if (routeLowerBound <= Domain.canonicalTime(budget)) {
                count++;
            }
        }
        return count;
    }

    private static TightBudget calculateTightBudget(
            double fastestMinimum, double fastestMaximum, double tightSlack) {
        requireFastestBounds(fastestMinimum, fastestMaximum);
        requireFiniteNonnegative(tightSlack, "tight budget slack");
        double candidate = scaledBudget(fastestMinimum, tightSlack);
        double budget;
        if (fastestMinimum < candidate && candidate < fastestMaximum) {
            budget = candidate;
        } else if (fastestMinimum < fastestMaximum) {
            BigDecimal minimum = BigDecimal.valueOf(fastestMinimum);
            budget = minimum.add(
                            BigDecimal.valueOf(fastestMaximum)
                                    .subtract(minimum)
                                    .multiply(QUARTER))
                    .setScale(Domain.REPOSITORY_TIME_UNIT_SCALE, RoundingMode.CEILING)
                    .doubleValue();
        } else {
            budget = candidate;
        }
        budget = Domain.canonicalTime(budget);
        boolean full = budget >= fastestMaximum;
        boolean mixed = fastestMinimum < fastestMaximum
                && fastestMinimum <= budget
                && budget < fastestMaximum;
        return new TightBudget(budget, full, mixed);
    }

    private static double scaledBudget(double fastestTravelTime, double slack) {
        requireFiniteNonnegative(fastestTravelTime, "fastest travel time");
        requireFiniteNonnegative(slack, "budget slack");
        return BigDecimal.valueOf(fastestTravelTime)
                .multiply(BigDecimal.ONE.add(BigDecimal.valueOf(slack)))
                .setScale(Domain.REPOSITORY_TIME_UNIT_SCALE, RoundingMode.CEILING)
                .doubleValue();
    }

    private static void requireFastestBounds(double minimum, double maximum) {
        requireFiniteNonnegative(minimum, "fastest travel-time minimum");
        requireFiniteNonnegative(maximum, "fastest travel-time maximum");
        if (minimum > maximum) {
            throw new IllegalArgumentException("fastest travel-time bounds are reversed");
        }
    }

    private static void requireFiniteNonnegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and nonnegative");
        }
    }

    private static QueryGenerationConfig requiredConfiguration(QueryGenerationConfig configuration) {
        Objects.requireNonNull(configuration, "configuration").validate();
        return configuration;
    }

    private static Map<TemporalRegime, Integer> configuredStarts(QueryGenerationConfig configuration) {
        EnumMap<TemporalRegime, Integer> result = new EnumMap<>(TemporalRegime.class);
        configuration.temporalRegimes().forEach(
                (regime, settings) -> result.put(regime, settings.preferredStart()));
        return result;
    }

    private static Map<TemporalRegime, Integer> immutableStarts(Map<TemporalRegime, Integer> starts) {
        Objects.requireNonNull(starts, "preferredStarts");
        EnumMap<TemporalRegime, Integer> result = new EnumMap<>(TemporalRegime.class);
        result.putAll(starts);
        if (!result.keySet().equals(EnumSet.allOf(TemporalRegime.class))
                || result.values().stream().anyMatch(value -> value == null || value < 0)) {
            throw new IllegalArgumentException("preferred starts must define every temporal regime");
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<TemporalRegime, Integer> defaultStarts() {
        EnumMap<TemporalRegime, Integer> starts = new EnumMap<>(TemporalRegime.class);
        starts.put(TemporalRegime.MORNING_PEAK, 420);
        starts.put(TemporalRegime.DAY_OFFPEAK, 720);
        starts.put(TemporalRegime.EVENING_PEAK, 1020);
        starts.put(TemporalRegime.LATE_OFFPEAK, 60);
        return Collections.unmodifiableMap(starts);
    }

    private static BuildFailure failure(
            FailureReason reason,
            TemporalRegime temporalRegime,
            String detail) {
        return new BuildFailure(reason, temporalRegime, detail);
    }

    private final class CorridorDistances {
        private final int source;
        private final int destination;
        private LowerBoundGraph.Distances fromSource;
        private LowerBoundGraph.Distances toDestination;

        private CorridorDistances(QueryPairCandidate pair) {
            source = pair.source();
            destination = pair.destination();
        }

        private LowerBoundGraph.Distances fromSource() {
            if (fromSource == null) {
                fromSource = lowerBounds.distancesFromSource(source);
            }
            return fromSource;
        }

        private LowerBoundGraph.Distances toDestination() {
            if (toDestination == null) {
                toDestination = lowerBounds.distancesToTarget(destination);
            }
            return toDestination;
        }
    }

    /** Machine-readable Phase 5 rejection reasons emitted by exact budget construction. */
    public enum FailureReason {
        FASTEST_PROFILE_UNAVAILABLE("fastest_profile_unavailable"),
        FULL_INTERVAL_INFEASIBLE("full_interval_infeasible"),
        FUNCTION_HORIZON_EXCEEDED("function_horizon_exceeded"),
        TEMPORAL_REGIME_UNAVAILABLE("temporal_regime_unavailable"),
        BUDGET_INVALID("budget_invalid");

        private final String id;

        FailureReason(String id) {
            this.id = id;
        }

        /** Stable counter/report key. */
        public String id() {
            return id;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /** Diagnostic attached to a rejected temporal query or four-regime family. */
    public record BuildFailure(
            FailureReason reason,
            TemporalRegime temporalRegime,
            String detail) {
        public BuildFailure {
            Objects.requireNonNull(reason, "reason");
            if (detail == null || detail.isBlank()) {
                detail = reason.id();
            }
        }

        /** Stable lowercase counter/report key. */
        public String reasonId() {
            return reason.id();
        }
    }

    /** Success-or-failure result for one exact temporal regime build. */
    public record TemporalBuildResult(
            Optional<TemporalQueryBudget> budget,
            Optional<BuildFailure> failure) {
        public TemporalBuildResult {
            budget = budget == null ? Optional.empty() : budget;
            failure = failure == null ? Optional.empty() : failure;
            if (budget.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException(
                        "temporal build result must contain exactly one of budget or failure");
            }
        }

        /** Successful exact temporal build. */
        public static TemporalBuildResult success(TemporalQueryBudget budget) {
            return new TemporalBuildResult(Optional.of(Objects.requireNonNull(budget, "budget")), Optional.empty());
        }

        /** Rejected exact temporal build. */
        public static TemporalBuildResult failure(BuildFailure failure) {
            return new TemporalBuildResult(Optional.empty(), Optional.of(Objects.requireNonNull(failure, "failure")));
        }

        public boolean succeeded() {
            return budget.isPresent();
        }
    }

    /** Success-or-failure result for an atomic four-regime build. */
    public record FamilyBuildResult(
            Optional<TemporalQueryFamily> family,
            Optional<BuildFailure> failure) {
        public FamilyBuildResult {
            family = family == null ? Optional.empty() : family;
            failure = failure == null ? Optional.empty() : failure;
            if (family.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException(
                        "family build result must contain exactly one of family or failure");
            }
        }

        /** Successful atomic family build. */
        public static FamilyBuildResult success(TemporalQueryFamily family) {
            return new FamilyBuildResult(Optional.of(Objects.requireNonNull(family, "family")), Optional.empty());
        }

        /** Rejected atomic family build. */
        public static FamilyBuildResult failure(BuildFailure failure) {
            return new FamilyBuildResult(Optional.empty(), Optional.of(Objects.requireNonNull(failure, "failure")));
        }

        public boolean succeeded() {
            return family.isPresent();
        }
    }

    /** One budget policy derived from the exact fastest profile. */
    public record BudgetVariant(
            String budgetPolicy,
            double budget,
            double budgetSlack,
            int corridorAnchorCount,
            boolean expectedFullIntervalFeasible,
            boolean expectedMixedFeasibility) {
        public BudgetVariant {
            if (budgetPolicy == null || budgetPolicy.isBlank()) {
                throw new IllegalArgumentException("budget policy is required");
            }
            requireFiniteNonnegative(budget, "budget");
            requireFiniteNonnegative(budgetSlack, "budget slack");
            if (corridorAnchorCount < 0) {
                throw new IllegalArgumentException("corridor anchor count cannot be negative");
            }
        }
    }

    /** Result of the tight-budget decision tree. */
    public record TightBudget(
            double budget,
            boolean expectedFullIntervalFeasible,
            boolean expectedMixedFeasibility) {
        public TightBudget {
            requireFiniteNonnegative(budget, "tight budget");
        }
    }

    /** One pair/regime profile with main and tight variants. */
    public record TemporalQueryBudget(
            QueryPairCandidate pair,
            TemporalRegime temporalRegime,
            int intervalStart,
            int intervalEnd,
            int windowLength,
            IntervalForwardLabeling.FastestTravelTimeProfile fastestProfile,
            double fastestTravelTimeMin,
            double fastestTravelTimeMax,
            BudgetVariant main,
            BudgetVariant tight) {
        public TemporalQueryBudget {
            Objects.requireNonNull(pair, "pair");
            Objects.requireNonNull(temporalRegime, "temporalRegime");
            Objects.requireNonNull(fastestProfile, "fastestProfile");
            Objects.requireNonNull(main, "main");
            Objects.requireNonNull(tight, "tight");
            if (windowLength <= 0 || intervalEnd - intervalStart != windowLength) {
                throw new IllegalArgumentException("invalid temporal query interval");
            }
            requireFastestBounds(fastestTravelTimeMin, fastestTravelTimeMax);
        }
    }

    /** Atomic four-regime family accepted only when every required variant is valid. */
    public record TemporalQueryFamily(
            QueryPairCandidate pair,
            Map<TemporalRegime, TemporalQueryBudget> variants) {
        public TemporalQueryFamily {
            Objects.requireNonNull(pair, "pair");
            Objects.requireNonNull(variants, "variants");
            EnumMap<TemporalRegime, TemporalQueryBudget> copy = new EnumMap<>(TemporalRegime.class);
            copy.putAll(variants);
            if (!copy.keySet().equals(EnumSet.allOf(TemporalRegime.class))) {
                throw new IllegalArgumentException("temporal query family must contain all four regimes");
            }
            copy.forEach((regime, variant) -> {
                if (variant == null || variant.temporalRegime() != regime || !variant.pair().equals(pair)) {
                    throw new IllegalArgumentException("temporal query family contains an inconsistent variant");
                }
            });
            variants = Collections.unmodifiableMap(copy);
        }

        /** Variant for one required temporal regime. */
        public TemporalQueryBudget variant(TemporalRegime regime) {
            return variants.get(Objects.requireNonNull(regime, "regime"));
        }
    }
}
