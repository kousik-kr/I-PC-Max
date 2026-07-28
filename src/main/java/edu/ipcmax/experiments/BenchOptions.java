package edu.ipcmax.experiments;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

import edu.ipcmax.experiments.framework.Ablation;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.core.pcmax.PaceEngineMode;

/** Parsed and validated command-line options for {@link PaceBench}. */
final class BenchOptions {
    String algorithm;
    Ablation ablation = Ablation.NONE;
    String dataset;
    Path queryFile;
    Path outputJsonl;
    Path outputCsv;
    String experimentName = "pace-experiment";
    int repetitions = 1;
    int warmupRuns;
    int threads = 1;
    int timeoutSeconds;
    int memoryLimitMb;
    long seed = 42;
    boolean deterministic;
    boolean verifyOutput;
    boolean failFast;
    boolean resume;
    int theta = 2;
    int anchorLimit = 32;
    int k = 16;
    int connectorLimitKc = 16;
    int frontierLimitKf = 16;
    long connectorExpansionCapMc = 5_000_000;
    int breakpointCapMb = 1_000_000;
    long queryWorkCapMq = 5_000_000;
    PaceEngineMode paceEngineMode = PaceEngineMode.SCALABLE;
    int rpqStepMinutes;
    int baselineK;
    long maxEnumeratedPaths = 100_000;
    long maxLabels = 1_000_000;
    long maxExpansions = 5_000_000;
    int maxFrontierFragments = 1_000_000;
    Path referenceJsonl;
    String referenceAlgorithm;
    boolean collectPhaseTimings;
    boolean collectMemory;
    boolean collectInternalCounters;
    boolean serializeProfile;
    Path profileOutputDir;
    int queryCount;
    long querySeed = 42;
    int distanceBins = 1;
    int windowMinutes = 10;
    double budgetSlack = 1.5;
    String budgetPolicy = "tight";
    Path queryManifestOutput;
    String[] commandLine;
    boolean internalWorker;
    int internalRepetition;
    boolean internalWarmup;
    Path internalConfigQueryFile;
    boolean internalGeneratedQueries;
    String internalForcedStatus;

    static BenchOptions parse(String[] args) {
        BenchOptions options = new BenchOptions();
        options.commandLine = Arrays.copyOf(args, args.length);
        List<String> tokens = new ArrayList<>(List.of(args));
        for (int index = 0; index < tokens.size(); index++) {
            String option = tokens.get(index);
            switch (option) {
                case "--algorithm" -> options.algorithm = value(tokens, ++index, option);
                case "--ablation" -> options.ablation = Ablation.parse(value(tokens, ++index, option));
                case "--dataset" -> options.dataset = value(tokens, ++index, option);
                case "--query-file" -> options.queryFile = Path.of(value(tokens, ++index, option));
                case "--output-jsonl" -> options.outputJsonl = Path.of(value(tokens, ++index, option));
                case "--output-csv" -> options.outputCsv = Path.of(value(tokens, ++index, option));
                case "--experiment-name" -> options.experimentName = value(tokens, ++index, option);
                case "--repetitions" -> options.repetitions = integer(tokens, ++index, option);
                case "--warmup-runs" -> options.warmupRuns = integer(tokens, ++index, option);
                case "--threads" -> options.threads = integer(tokens, ++index, option);
                case "--timeout-seconds" -> options.timeoutSeconds = integer(tokens, ++index, option);
                case "--memory-limit-mb" -> options.memoryLimitMb = integer(tokens, ++index, option);
                case "--seed" -> options.seed = unsignedLong(tokens, ++index, option);
                case "--deterministic" -> options.deterministic = true;
                case "--verify-output" -> options.verifyOutput = true;
                case "--fail-fast" -> options.failFast = true;
                case "--resume" -> options.resume = true;
                case "--theta" -> options.theta = integer(tokens, ++index, option);
                case "--anchor-limit", "--pivot-limit-l" ->
                        options.anchorLimit = integer(tokens, ++index, option);
                case "--k" -> {
                    options.k = integer(tokens, ++index, option);
                    options.connectorLimitKc = options.k;
                    options.frontierLimitKf = options.k;
                }
                case "--connector-limit-kc" ->
                        options.connectorLimitKc = integer(tokens, ++index, option);
                case "--frontier-limit-kf" -> {
                    options.frontierLimitKf = integer(tokens, ++index, option);
                    options.k = options.frontierLimitKf;
                }
                case "--connector-expansion-cap-mc" ->
                        options.connectorExpansionCapMc =
                                positiveLong(tokens, ++index, option);
                case "--breakpoint-cap-mb" ->
                        options.breakpointCapMb =
                                integer(tokens, ++index, option);
                case "--query-work-cap-mq" ->
                        options.queryWorkCapMq =
                                positiveLong(tokens, ++index, option);
                case "--pace-engine" ->
                        options.paceEngineMode = PaceEngineMode.valueOf(
                                value(tokens, ++index, option)
                                        .trim().toUpperCase(
                                                java.util.Locale.ROOT));
                case "--rpq-step-minutes" -> options.rpqStepMinutes = wholeMinutes(tokens, ++index, option);
                case "--baseline-k" -> options.baselineK = integer(tokens, ++index, option);
                case "--max-enumerated-paths" -> options.maxEnumeratedPaths = positiveLong(tokens, ++index, option);
                case "--max-labels" -> options.maxLabels = positiveLong(tokens, ++index, option);
                case "--max-expansions" -> options.maxExpansions = positiveLong(tokens, ++index, option);
                case "--max-frontier-fragments" -> options.maxFrontierFragments = integer(tokens, ++index, option);
                case "--reference-jsonl" -> options.referenceJsonl = Path.of(value(tokens, ++index, option));
                case "--reference-algorithm" -> options.referenceAlgorithm = value(tokens, ++index, option);
                case "--collect-phase-timings" -> options.collectPhaseTimings = true;
                case "--collect-memory" -> options.collectMemory = true;
                case "--collect-internal-counters" -> options.collectInternalCounters = true;
                case "--serialize-profile" -> options.serializeProfile = true;
                case "--profile-output-dir" -> options.profileOutputDir = Path.of(value(tokens, ++index, option));
                case "--query-count" -> options.queryCount = integer(tokens, ++index, option);
                case "--query-seed" -> options.querySeed = unsignedLong(tokens, ++index, option);
                case "--distance-bins" -> options.distanceBins = integer(tokens, ++index, option);
                case "--window-minutes" -> options.windowMinutes = wholeMinutes(tokens, ++index, option);
                case "--budget-slack" -> options.budgetSlack = decimal(tokens, ++index, option);
                case "--budget-policy" -> options.budgetPolicy = value(tokens, ++index, option);
                case "--query-manifest-output" -> options.queryManifestOutput = Path.of(value(tokens, ++index, option));
                case "--internal-worker" -> options.internalWorker = true;
                case "--internal-repetition" -> options.internalRepetition = integer(tokens, ++index, option);
                case "--internal-warmup" -> options.internalWarmup = true;
                case "--internal-config-query-file" -> options.internalConfigQueryFile =
                        Path.of(value(tokens, ++index, option));
                case "--internal-generated-queries" -> options.internalGeneratedQueries = true;
                case "--internal-forced-status" -> options.internalForcedStatus = value(tokens, ++index, option);
                case "--internal-original-command-line" -> options.commandLine = new String(
                        Base64.getDecoder().decode(value(tokens, ++index, option)), StandardCharsets.UTF_8)
                        .split("\\u0000", -1);
                default -> throw new IllegalArgumentException("unknown argument: " + option);
            }
        }
        options.validate();
        return options;
    }

    private void validate() {
        if (algorithm == null) {
            throw new IllegalArgumentException("--algorithm is required");
        }
        AlgorithmRegistry.create(algorithm);
        if (referenceAlgorithm != null) {
            AlgorithmRegistry.create(referenceAlgorithm);
        }
        if (dataset == null) {
            throw new IllegalArgumentException("--dataset is required");
        }
        if (queryFile == null && queryCount < 1) {
            throw new IllegalArgumentException("--query-file or --query-count is required");
        }
        if (outputJsonl == null) {
            throw new IllegalArgumentException("--output-jsonl is required");
        }
        if (repetitions < 1 || warmupRuns < 0 || threads < 1 || theta < 0) {
            throw new IllegalArgumentException("repetitions/threads must be positive and theta/warmups nonnegative");
        }
        if (timeoutSeconds < 0 || memoryLimitMb < 0 || maxFrontierFragments < 1) {
            throw new IllegalArgumentException("resource limits cannot be negative and frontier guard must be positive");
        }
        if (distanceBins < 1 || windowMinutes < 1) {
            throw new IllegalArgumentException("distance bins and query window must be positive");
        }
        if (!Double.isFinite(budgetSlack) || budgetSlack <= 0) {
            throw new IllegalArgumentException("--budget-slack must be finite and positive");
        }
        if (!algorithm.equals("pace-b") && ablation != Ablation.NONE) {
            throw new IllegalArgumentException("ablations require --algorithm pace-b");
        }
        if (algorithm.equals("pace-x") && ablation != Ablation.NONE) {
            throw new IllegalArgumentException("PACE-X cannot use heuristic ablations");
        }
        if (algorithm.equals("pace-b")
                && (anchorLimit < 0
                    || connectorLimitKc < 1
                    || frontierLimitKf < 1
                    || connectorExpansionCapMc < 1
                    || breakpointCapMb < 1
                    || queryWorkCapMq < 1)) {
            throw new IllegalArgumentException(
                    "PACE-B requires L >= 0 and positive K_c, K_f, M_c, M_b, M_q");
        }
        if (algorithm.equals("rpq") && rpqStepMinutes < 1) {
            throw new IllegalArgumentException("RPQ requires a positive whole-minute --rpq-step-minutes");
        }
        if (algorithm.equals("ksp-profile") && baselineK < 1) {
            throw new IllegalArgumentException("KSP requires --baseline-k >= 1");
        }
        if (!budgetPolicy.equals("tight") && !budgetPolicy.equals("full-interval-feasible")) {
            throw new IllegalArgumentException("unknown --budget-policy: " + budgetPolicy);
        }
        if (serializeProfile && profileOutputDir == null) {
            throw new IllegalArgumentException("--serialize-profile requires --profile-output-dir");
        }
        if (ablation == Ablation.NO_ANCHOR) {
            theta = 0;
        }
        if (ablation == Ablation.SERIAL) {
            threads = 1;
        }
    }

    AlgorithmConfig algorithmConfig() {
        return new AlgorithmConfig(
                algorithm,
                ablation,
                paceEngineMode,
                theta,
                anchorLimit,
                connectorLimitKc,
                frontierLimitKf,
                connectorExpansionCapMc,
                breakpointCapMb,
                queryWorkCapMq,
                threads,
                rpqStepMinutes,
                baselineK,
                maxEnumeratedPaths,
                maxLabels,
                maxExpansions,
                maxFrontierFragments,
                deterministic,
                seed);
    }

    Map<String, Object> normalized() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("algorithm", algorithm);
        result.put("ablation", ablation.id());
        result.put("dataset", dataset);
        Path normalizedQueryFile = internalConfigQueryFile == null ? queryFile : internalConfigQueryFile;
        result.put("query_file", internalGeneratedQueries || normalizedQueryFile == null
                ? null : normalizedQueryFile.toAbsolutePath().normalize().toString());
        result.put("theta", theta);
        result.put("anchor_limit", anchorLimit);
        result.put("k", k);
        result.put("pivot_limit_l", anchorLimit);
        result.put("connector_limit_kc", connectorLimitKc);
        result.put("frontier_limit_kf", frontierLimitKf);
        result.put("connector_expansion_cap_mc", connectorExpansionCapMc);
        result.put("breakpoint_cap_mb", breakpointCapMb);
        result.put("query_work_cap_mq", queryWorkCapMq);
        result.put("pace_engine", paceEngineMode.name());
        result.put("threads", threads);
        result.put("rpq_step_minutes", rpqStepMinutes == 0 ? null : rpqStepMinutes);
        result.put("baseline_k", baselineK == 0 ? null : baselineK);
        result.put("timeout_seconds", timeoutSeconds);
        result.put("memory_limit_mb", memoryLimitMb);
        result.put("max_enumerated_paths", maxEnumeratedPaths);
        result.put("max_labels", maxLabels);
        result.put("max_expansions", maxExpansions);
        result.put("max_frontier_fragments", maxFrontierFragments);
        result.put("deterministic", deterministic);
        result.put("seed", Long.toUnsignedString(seed));
        result.put("verify_output", verifyOutput);
        result.put("reference_jsonl", referenceJsonl == null
                ? null : referenceJsonl.toAbsolutePath().normalize().toString());
        result.put("reference_algorithm", referenceAlgorithm);
        result.put("collect_phase_timings", collectPhaseTimings);
        result.put("collect_memory", collectMemory);
        result.put("collect_internal_counters", collectInternalCounters);
        result.put("serialize_profile", serializeProfile);
        if (queryFile == null || internalGeneratedQueries) {
            result.put("query_count", queryCount);
            result.put("query_seed", Long.toUnsignedString(querySeed));
            result.put("distance_bins", distanceBins);
            result.put("window_minutes", windowMinutes);
            result.put("budget_slack", budgetSlack);
            result.put("budget_policy", budgetPolicy);
        }
        return result;
    }

    private static String value(List<String> tokens, int index, String option) {
        if (index >= tokens.size()) {
            throw new IllegalArgumentException("missing value for " + option);
        }
        return tokens.get(index);
    }

    private static int integer(List<String> tokens, int index, String option) {
        return Integer.parseInt(value(tokens, index, option));
    }

    private static long positiveLong(List<String> tokens, int index, String option) {
        long parsed = Long.parseLong(value(tokens, index, option));
        if (parsed < 1) {
            throw new IllegalArgumentException(option + " must be positive");
        }
        return parsed;
    }

    private static long unsignedLong(List<String> tokens, int index, String option) {
        return Long.parseUnsignedLong(value(tokens, index, option));
    }

    private static double decimal(List<String> tokens, int index, String option) {
        return Double.parseDouble(value(tokens, index, option));
    }

    private static int wholeMinutes(List<String> tokens, int index, String option) {
        double parsed = decimal(tokens, index, option);
        if (parsed != Math.rint(parsed) || parsed < 1 || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(option + " must be a positive whole-minute value");
        }
        return (int) parsed;
    }
}
