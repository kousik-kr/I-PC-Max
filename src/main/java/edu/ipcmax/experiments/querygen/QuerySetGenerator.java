package edu.ipcmax.experiments.querygen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import edu.ipcmax.experiments.framework.QueryManifestEntry;
import edu.ipcmax.experiments.framework.QueryManifestIO;

/**
 * Java entry point for deterministic query-set generation across NY, FLA, CAL, and USA.
 */
public final class QuerySetGenerator {
    private final DatasetQueryGenerator datasetGenerator;

    /** Uses the production dataset generator. */
    public QuerySetGenerator() {
        this(new DatasetQueryGenerator());
    }

    QuerySetGenerator(DatasetQueryGenerator datasetGenerator) {
        this.datasetGenerator = Objects.requireNonNull(datasetGenerator, "datasetGenerator");
    }

    /** Command-line entry point. */
    public static void main(String[] arguments) {
        int status = new QuerySetGenerator().execute(arguments);
        if (status != 0) {
            System.exit(status);
        }
    }

    /**
     * Non-exiting CLI adapter for tests and embedding. Returns zero on success and two on a
     * configuration, loading, balancing, or writing failure.
     */
    public int execute(String... arguments) {
        try {
            GenerationRun run = generate(QueryGenerationOptions.parse(arguments));
            System.out.println("generated_query_memberships=" + run.summary().queriesGenerated());
            return 0;
        } catch (Exception failure) {
            System.err.println("query generation failed: " + failure.getMessage());
            return 2;
        }
    }

    /** Generates all selected datasets and applies the requested output policy. */
    public GenerationRun generate(QueryGenerationOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        QueryGenerationConfig configuration = QueryGenerationConfig.load(options.configurationPath());
        String configHash = sha256(options.configurationPath());
        TreeMap<String, DatasetQueryGenerator.DatasetQuerySets> datasets = new TreeMap<>();

        // Dataset graphs are deliberately processed one at a time: concurrent USA graph loads
        // multiply peak memory without changing deterministic output ordering.
        for (String datasetId : options.selectedDatasets()) {
            DatasetQueryGenerator.DatasetQuerySets generated = datasetGenerator.generate(
                    options.dataRoot(), datasetId, configuration, options.seed(), configHash);
            datasets.put(datasetId, generated);
        }

        GenerationRun run = new GenerationRun(
                configuration,
                configHash,
                datasets,
                summarize(options.seed(), datasets),
                outputPaths(options.outputRoot(), datasets));
        applyOutputPolicy(options, run);
        return run;
    }

    private static void applyOutputPolicy(QueryGenerationOptions options, GenerationRun run)
            throws IOException {
        if (options.validateOnly()) {
            for (Map.Entry<String, DatasetQueryGenerator.DatasetQuerySets> dataset : run.datasets().entrySet()) {
                for (DatasetQueryGenerator.QuerySet set : DatasetQueryGenerator.QuerySet.values()) {
                    Path path = run.outputPaths().get(dataset.getKey()).get(set);
                    if (!Files.isRegularFile(path)) {
                        throw new IOException("missing manifest for validation: " + path);
                    }
                    List<QueryManifestEntry> existing = QueryManifestIO.read(path);
                    if (!existing.equals(dataset.getValue().queries(set))) {
                        throw new IOException("existing manifest differs from deterministic output: " + path);
                    }
                }
            }
            return;
        }
        if (options.dryRun()) {
            return;
        }

        for (Map.Entry<String, DatasetQueryGenerator.DatasetQuerySets> dataset : run.datasets().entrySet()) {
            for (DatasetQueryGenerator.QuerySet set : DatasetQueryGenerator.QuerySet.values()) {
                Path path = run.outputPaths().get(dataset.getKey()).get(set);
                if (Files.exists(path) && !options.overwrite() && !options.resume()) {
                    throw new IOException("manifest already exists (use --overwrite or --resume): " + path);
                }
                if (options.resume() && Files.isRegularFile(path)) {
                    List<QueryManifestEntry> existing = QueryManifestIO.read(path);
                    if (!existing.equals(dataset.getValue().queries(set))) {
                        throw new IOException("resume manifest differs from deterministic output: " + path);
                    }
                    continue;
                }
                QueryManifestIO.write(path, dataset.getValue().queries(set));
            }
        }
    }

    private static QueryGenerationSummary summarize(
            long seed,
            Map<String, DatasetQueryGenerator.DatasetQuerySets> datasets) {
        long pairsExamined = 0;
        long candidatesAccepted = 0;
        long generated = 0;
        TreeMap<String, Long> byDataset = new TreeMap<>();
        TreeMap<String, Long> byFamily = new TreeMap<>();
        for (Map.Entry<String, DatasetQueryGenerator.DatasetQuerySets> item : datasets.entrySet()) {
            DatasetQueryGenerator.DatasetQuerySets sets = item.getValue();
            pairsExamined += sets.pairsExamined();
            candidatesAccepted += sets.candidatePoolSize();
            generated += sets.membershipCount();
            byDataset.put(item.getKey(), (long) sets.membershipCount());
            for (DatasetQueryGenerator.QuerySet set : DatasetQueryGenerator.QuerySet.values()) {
                byFamily.merge(set.id(), (long) sets.queries(set).size(), Long::sum);
            }
        }
        return new QueryGenerationSummary(
                seed, datasets.size(), pairsExamined, candidatesAccepted, generated, byDataset, byFamily);
    }

    private static Map<String, Map<DatasetQueryGenerator.QuerySet, Path>> outputPaths(
            Path outputRoot,
            Map<String, DatasetQueryGenerator.DatasetQuerySets> datasets) {
        Objects.requireNonNull(outputRoot, "outputRoot");
        TreeMap<String, Map<DatasetQueryGenerator.QuerySet, Path>> result = new TreeMap<>();
        for (String datasetId : datasets.keySet()) {
            EnumMap<DatasetQueryGenerator.QuerySet, Path> paths = new EnumMap<>(
                    DatasetQueryGenerator.QuerySet.class);
            for (DatasetQueryGenerator.QuerySet set : DatasetQueryGenerator.QuerySet.values()) {
                paths.put(set, outputRoot.resolve(datasetId).resolve(set.id() + ".jsonl"));
            }
            result.put(datasetId, Collections.unmodifiableMap(paths));
        }
        return Collections.unmodifiableMap(result);
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    /** Immutable result of a multi-dataset query-generation run. */
    public record GenerationRun(
            QueryGenerationConfig configuration,
            String generatorConfigHash,
            Map<String, DatasetQueryGenerator.DatasetQuerySets> datasets,
            QueryGenerationSummary summary,
            Map<String, Map<DatasetQueryGenerator.QuerySet, Path>> outputPaths) {
        public GenerationRun {
            Objects.requireNonNull(configuration, "configuration");
            if (generatorConfigHash == null || generatorConfigHash.isBlank()) {
                throw new IllegalArgumentException("generator configuration hash is required");
            }
            TreeMap<String, DatasetQueryGenerator.DatasetQuerySets> sortedDatasets = new TreeMap<>();
            if (datasets != null) {
                sortedDatasets.putAll(datasets);
            }
            datasets = Collections.unmodifiableMap(sortedDatasets);
            Objects.requireNonNull(summary, "summary");
            TreeMap<String, Map<DatasetQueryGenerator.QuerySet, Path>> sortedPaths = new TreeMap<>();
            if (outputPaths != null) {
                outputPaths.forEach((dataset, paths) -> {
                    EnumMap<DatasetQueryGenerator.QuerySet, Path> copy = new EnumMap<>(
                            DatasetQueryGenerator.QuerySet.class);
                    copy.putAll(paths);
                    sortedPaths.put(dataset, Collections.unmodifiableMap(copy));
                });
            }
            outputPaths = Collections.unmodifiableMap(sortedPaths);
        }
    }
}
