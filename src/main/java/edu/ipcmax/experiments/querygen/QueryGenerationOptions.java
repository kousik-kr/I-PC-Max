package edu.ipcmax.experiments.querygen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Validated command-line options for the deterministic query generator. */
public record QueryGenerationOptions(
        Set<String> datasets,
        boolean allDatasets,
        Path dataRoot,
        Path outputRoot,
        Path configurationPath,
        int threads,
        long seed,
        boolean overwrite,
        boolean resume,
        boolean dryRun,
        boolean validateOnly,
        boolean verbose) {
    public static final int MIN_THREADS = 1;
    public static final int MAX_THREADS = 24;
    public static final long DEFAULT_SEED = 20260711L;
    public static final Set<String> SUPPORTED_DATASETS = Set.of("NY", "FLA", "CAL", "USA");
    public static final Path DEFAULT_DATA_ROOT = Path.of("data/input");
    public static final Path DEFAULT_OUTPUT_ROOT = Path.of("results/manifests");
    public static final Path DEFAULT_CONFIGURATION = Path.of("experiments/configs/query_generation.yaml");

    public QueryGenerationOptions {
        TreeSet<String> normalized = new TreeSet<>();
        if (datasets != null) {
            datasets.forEach(dataset -> normalized.add(normalizeDataset(dataset)));
        }
        datasets = Collections.unmodifiableSet(normalized);
        if (allDatasets && !datasets.isEmpty()) {
            throw new IllegalArgumentException("--all-datasets cannot be combined with explicit datasets");
        }
        if (!allDatasets && datasets.isEmpty()) {
            throw new IllegalArgumentException("select at least one dataset or use --all-datasets");
        }
        if (dataRoot == null || outputRoot == null || configurationPath == null) {
            throw new IllegalArgumentException("data root, output root, and configuration path are required");
        }
        if (threads < MIN_THREADS || threads > MAX_THREADS) {
            throw new IllegalArgumentException("threads must be between 1 and 24");
        }
        if (overwrite && resume) {
            throw new IllegalArgumentException("--overwrite and --resume are mutually exclusive");
        }
    }

    /** Parses query-generator command-line options. */
    public static QueryGenerationOptions parse(String... arguments) {
        if (arguments == null) {
            throw new IllegalArgumentException("arguments cannot be null");
        }
        return parse(Arrays.asList(arguments));
    }

    /** Parses query-generator command-line options. */
    public static QueryGenerationOptions parse(List<String> arguments) {
        if (arguments == null) {
            throw new IllegalArgumentException("arguments cannot be null");
        }
        List<String> tokens = new ArrayList<>(arguments);
        Set<String> datasets = new LinkedHashSet<>();
        boolean allDatasets = true;
        boolean sawAllDatasets = false;
        boolean sawExplicitDataset = false;
        Path dataRoot = DEFAULT_DATA_ROOT;
        Path outputRoot = DEFAULT_OUTPUT_ROOT;
        Path configuration = DEFAULT_CONFIGURATION;
        int threads = 1;
        long seed = DEFAULT_SEED;
        boolean overwrite = false;
        boolean resume = false;
        boolean dryRun = false;
        boolean validateOnly = false;
        boolean verbose = false;

        for (int index = 0; index < tokens.size(); index++) {
            String option = tokens.get(index);
            switch (option) {
                case "--dataset", "--datasets" -> {
                    sawExplicitDataset = true;
                    allDatasets = false;
                    addDatasets(datasets, value(tokens, ++index, option));
                }
                case "--all-datasets" -> {
                    sawAllDatasets = true;
                    allDatasets = true;
                }
                case "--data-root" -> dataRoot = path(tokens, ++index, option);
                case "--output-root" -> outputRoot = path(tokens, ++index, option);
                case "--config", "--configuration" -> configuration = path(tokens, ++index, option);
                case "--threads" -> threads = integer(tokens, ++index, option);
                case "--seed" -> seed = unsignedLong(tokens, ++index, option);
                case "--overwrite" -> overwrite = true;
                case "--resume" -> resume = true;
                case "--dry-run" -> dryRun = true;
                case "--validate-only" -> validateOnly = true;
                case "--verbose" -> verbose = true;
                default -> throw new IllegalArgumentException("unknown query-generation argument: " + option);
            }
        }
        if (sawAllDatasets && sawExplicitDataset) {
            throw new IllegalArgumentException("--all-datasets cannot be combined with explicit datasets");
        }
        return new QueryGenerationOptions(
                datasets, allDatasets, dataRoot, outputRoot, configuration, threads, seed,
                overwrite, resume, dryRun, validateOnly, verbose);
    }

    /** Effective dataset IDs in stable order. */
    public Set<String> selectedDatasets() {
        return allDatasets
                ? Collections.unmodifiableSet(new TreeSet<>(SUPPORTED_DATASETS))
                : datasets;
    }

    private static void addDatasets(Set<String> target, String value) {
        for (String token : value.split(",", -1)) {
            target.add(normalizeDataset(token));
        }
    }

    private static String normalizeDataset(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("dataset id cannot be blank");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_DATASETS.contains(normalized)) {
            throw new IllegalArgumentException("unknown dataset: " + value);
        }
        return normalized;
    }

    private static String value(List<String> tokens, int index, String option) {
        if (index >= tokens.size()) {
            throw new IllegalArgumentException("missing value for " + option);
        }
        return tokens.get(index);
    }

    private static Path path(List<String> tokens, int index, String option) {
        String value = value(tokens, index, option);
        if (value.isBlank()) {
            throw new IllegalArgumentException(option + " path cannot be blank");
        }
        return Path.of(value);
    }

    private static int integer(List<String> tokens, int index, String option) {
        try {
            return Integer.parseInt(value(tokens, index, option));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(option + " requires an integer", failure);
        }
    }

    private static long unsignedLong(List<String> tokens, int index, String option) {
        try {
            return Long.parseUnsignedLong(value(tokens, index, option));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(option + " requires an unsigned 64-bit integer", failure);
        }
    }
}
