package edu.ipcmax.experiments.querygen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/** Strict, immutable representation of {@code query_generation.yaml}. */
public record QueryGenerationConfig(
        int schemaVersion,
        long seed,
        CandidatePool candidatePool,
        Main main,
        Pilot pilot,
        Selection sensitivity,
        Selection appendix,
        Parallelism parallelism,
        TightBudget tightBudget,
        WindowSensitivity windowSensitivity,
        BudgetSensitivity budgetSensitivity,
        Map<TemporalRegime, TemporalRegimeSettings> temporalRegimes) {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public QueryGenerationConfig {
        temporalRegimes = immutableRegimes(temporalRegimes);
    }

    /** Loads and validates a query-generation YAML file. */
    public static QueryGenerationConfig load(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("query-generation configuration path is required");
        }
        JsonNode root = YAML.readTree(path.toFile());
        if (root == null) {
            throw new IOException("query-generation configuration is empty: " + path);
        }
        try {
            validateShape(root);
            QueryGenerationConfig config = YAML.treeToValue(root, QueryGenerationConfig.class);
            config.validate();
            return config;
        } catch (IllegalArgumentException failure) {
            throw new IOException("invalid query-generation configuration " + path + ": "
                    + failure.getMessage(), failure);
        }
    }

    /** Validates all required sections and cross-section invariants. */
    public void validate() {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("unsupported query-generation schema_version: " + schemaVersion);
        }
        require(candidatePool, "candidate_pool").validate();
        require(main, "main").validate();
        require(pilot, "pilot").validate();
        require(sensitivity, "sensitivity").validate("sensitivity");
        require(appendix, "appendix").validate("appendix");
        require(parallelism, "parallelism").validate();
        require(tightBudget, "tight_budget").validate();
        require(windowSensitivity, "window_sensitivity").validate();
        require(budgetSensitivity, "budget_sensitivity").validate();
        if (!temporalRegimes.keySet().equals(EnumSet.allOf(TemporalRegime.class))) {
            throw new IllegalArgumentException("temporal_regimes must define every TemporalRegime exactly once");
        }
        temporalRegimes.forEach((regime, settings) ->
                require(settings, "temporal_regimes." + regime.id()).validate(regime));
    }

    /** Candidate-pool limits and eligibility requirements. */
    public record CandidatePool(
            int sampledSources,
            int maximumPairs,
            int minimumLowerBoundEdges,
            double minimumDistance,
            boolean requireReachable,
            boolean requireAnchorCorridor) {
        void validate() {
            positive(sampledSources, "candidate_pool.sampled_sources");
            positive(maximumPairs, "candidate_pool.maximum_pairs");
            nonnegative(minimumLowerBoundEdges, "candidate_pool.minimum_lower_bound_edges");
            finiteNonnegative(minimumDistance, "candidate_pool.minimum_distance");
        }
    }

    /** Main-query selection and budget settings. */
    public record Main(
            int pairsPerDistanceBin,
            int windowMinutes,
            double budgetSlack,
            String budgetPolicy) {
        void validate() {
            positive(pairsPerDistanceBin, "main.pairs_per_distance_bin");
            positive(windowMinutes, "main.window_minutes");
            finiteNonnegative(budgetSlack, "main.budget_slack");
            if (!"FULL_INTERVAL_FEASIBLE".equals(budgetPolicy) && !"TIGHT".equals(budgetPolicy)) {
                throw new IllegalArgumentException("main.budget_policy must be FULL_INTERVAL_FEASIBLE or TIGHT");
            }
        }
    }

    /** Pilot-query selection settings. */
    public record Pilot(int pairsPerDistanceBin, boolean disjointFromMain) {
        void validate() {
            positive(pairsPerDistanceBin, "pilot.pairs_per_distance_bin");
        }
    }

    /** Main-derived family selection settings. */
    public record Selection(int pairsPerDistanceBin, boolean selectFromMain) {
        void validate(String section) {
            positive(pairsPerDistanceBin, section + ".pairs_per_distance_bin");
        }
    }

    /** Parallelism workload cells. */
    public record Parallelism(
            List<DistanceBin> distanceBins,
            List<TemporalRegime> temporalRegimes,
            int pairsPerCell) {
        public Parallelism {
            distanceBins = distanceBins == null ? List.of() : List.copyOf(distanceBins);
            temporalRegimes = temporalRegimes == null ? List.of() : List.copyOf(temporalRegimes);
        }

        void validate() {
            nonemptyDistinct(distanceBins, "parallelism.distance_bins");
            nonemptyDistinct(temporalRegimes, "parallelism.temporal_regimes");
            positive(pairsPerCell, "parallelism.pairs_per_cell");
        }
    }

    /** Tight-budget derived-family settings. */
    public record TightBudget(double slack, boolean deriveFromMain) {
        void validate() {
            finiteNonnegative(slack, "tight_budget.slack");
        }
    }

    /** Window-length sensitivity settings. */
    public record WindowSensitivity(List<Integer> valuesMinutes, boolean deriveFromSensitivity) {
        public WindowSensitivity {
            valuesMinutes = valuesMinutes == null ? List.of() : List.copyOf(valuesMinutes);
        }

        void validate() {
            nonemptyDistinct(valuesMinutes, "window_sensitivity.values_minutes");
            valuesMinutes.forEach(value -> positive(value, "window_sensitivity.values_minutes"));
        }
    }

    /** Budget-slack sensitivity settings. */
    public record BudgetSensitivity(List<Double> slackValues, boolean deriveFromSensitivity) {
        public BudgetSensitivity {
            slackValues = slackValues == null ? List.of() : List.copyOf(slackValues);
        }

        void validate() {
            nonemptyDistinct(slackValues, "budget_sensitivity.slack_values");
            slackValues.forEach(value -> finiteNonnegative(value, "budget_sensitivity.slack_values"));
        }
    }

    /** Preferred start minute for one named temporal regime. */
    public record TemporalRegimeSettings(int preferredStart) {
        void validate(TemporalRegime regime) {
            if (preferredStart < 0 || preferredStart > 1440) {
                throw new IllegalArgumentException(
                        "temporal_regimes." + regime.id() + ".preferred_start must be in [0,1440]");
            }
        }
    }

    private static Map<TemporalRegime, TemporalRegimeSettings> immutableRegimes(
            Map<TemporalRegime, TemporalRegimeSettings> values) {
        if (values == null) {
            return Map.of();
        }
        EnumMap<TemporalRegime, TemporalRegimeSettings> result = new EnumMap<>(TemporalRegime.class);
        result.putAll(values);
        return Collections.unmodifiableMap(result);
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static void positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void nonnegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
    }

    private static void finiteNonnegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and nonnegative");
        }
    }

    private static <T> void nonemptyDistinct(List<T> values, String name) {
        if (values.isEmpty() || values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(name + " must contain non-null values");
        }
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(name + " cannot contain duplicates");
        }
    }

    private static void validateShape(JsonNode root) {
        requireFields(root, "root",
                "schema_version", "seed", "candidate_pool", "main", "pilot", "sensitivity",
                "appendix", "parallelism", "tight_budget", "window_sensitivity",
                "budget_sensitivity", "temporal_regimes");
        requireFields(root.get("candidate_pool"), "candidate_pool",
                "sampled_sources", "maximum_pairs", "minimum_lower_bound_edges", "minimum_distance",
                "require_reachable", "require_anchor_corridor");
        requireFields(root.get("main"), "main",
                "pairs_per_distance_bin", "window_minutes", "budget_slack", "budget_policy");
        requireFields(root.get("pilot"), "pilot", "pairs_per_distance_bin", "disjoint_from_main");
        requireFields(root.get("sensitivity"), "sensitivity",
                "pairs_per_distance_bin", "select_from_main");
        requireFields(root.get("appendix"), "appendix",
                "pairs_per_distance_bin", "select_from_main");
        requireFields(root.get("parallelism"), "parallelism",
                "distance_bins", "temporal_regimes", "pairs_per_cell");
        requireFields(root.get("tight_budget"), "tight_budget", "slack", "derive_from_main");
        requireFields(root.get("window_sensitivity"), "window_sensitivity",
                "values_minutes", "derive_from_sensitivity");
        requireFields(root.get("budget_sensitivity"), "budget_sensitivity",
                "slack_values", "derive_from_sensitivity");
        JsonNode regimes = root.get("temporal_regimes");
        String[] regimeNames = EnumSet.allOf(TemporalRegime.class).stream()
                .map(TemporalRegime::id)
                .toArray(String[]::new);
        requireFields(regimes, "temporal_regimes", regimeNames);
        for (String regime : regimeNames) {
            requireFields(regimes.get(regime), "temporal_regimes." + regime, "preferred_start");
        }
    }

    private static void requireFields(JsonNode node, String section, String... names) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(section + " must be an object");
        }
        Set<String> expected = Set.of(names);
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(section + " is missing fields: " + missing);
        }
        Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(expected);
        if (!unexpected.isEmpty()) {
            throw new IllegalArgumentException(section + " has unexpected fields: " + unexpected);
        }
        for (String name : expected) {
            if (node.get(name).isNull()) {
                throw new IllegalArgumentException(section + "." + name + " cannot be null");
            }
        }
    }
}
