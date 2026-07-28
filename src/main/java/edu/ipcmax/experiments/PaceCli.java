package edu.ipcmax.experiments;

import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.core.loader.GeneratedGraphLoader;
import edu.ipcmax.core.pcmax.EnvelopeProfile;
import edu.ipcmax.core.pcmax.EnvelopeSegment;
import edu.ipcmax.core.pcmax.IPCMaxResult;
import edu.ipcmax.core.pcmax.PACE;
import edu.ipcmax.core.pcmax.PaceException;
import edu.ipcmax.core.pcmax.PaceExecutionPolicy;
import edu.ipcmax.core.pcmax.PaceOptions;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.pcmax.RepeatedFastestPathBaseline;
import edu.ipcmax.core.pcmax.TinyGraphBruteForceOracle;
import edu.ipcmax.experiments.framework.ExactnessScope;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line runner for PACE and the retained validation baselines.
 */
public final class PaceCli {
    private PaceCli() {
    }

    /**
     * Runs a PACE profile query or a legacy point-result baseline.
     */
    public static void main(String[] args) throws Exception {
        int exitCode = execute(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * Executes the CLI and returns a process-style status code.
     */
    static int execute(String[] args, PrintStream out, PrintStream err) throws Exception {
        CliArgs cli = CliArgs.parse(args);
        TDGraph graph;
        if (cli.demo) {
            graph = demoGraph();
        } else {
            if (cli.graphDirectory == null) {
                throw new IllegalArgumentException("missing --graph <generated-graph-directory> or --demo");
            }
            graph = new GeneratedGraphLoader().load(cli.graphDirectory).graph();
        }

        QuerySpec query = new QuerySpec(
                cli.source,
                cli.destination,
                cli.departureStart,
                cli.departureEnd,
                cli.maxTravelTime,
                cli.granularity);

        if ("pace-x".equals(cli.algorithm) || "pace-b".equals(cli.algorithm)) {
            PaceExecutionPolicy policy = "pace-x".equals(cli.algorithm)
                    ? PaceExecutionPolicy.PACE_X
                    : PaceExecutionPolicy.PACE_B;
            PaceOptions options = new PaceOptions(
                    policy,
                    cli.theta,
                    cli.anchorLimit,
                    cli.candidateLimit,
                    cli.threadCount,
                    true);
            try {
                PACE pace = new PACE(graph, options);
                EnvelopeProfile profile = pace.run(query);
                ExactnessScope scope = switch (
                        pace.lastGenerationResult().exactnessScope()) {
                    case GLOBAL_CERTIFIED -> ExactnessScope.GLOBAL_CERTIFIED;
                    case RETAINED_FRONTIER -> ExactnessScope.RETAINED_FRONTIER;
                    case NOT_CERTIFIED -> ExactnessScope.NOT_CERTIFIED;
                };
                printProfile(out, cli.algorithm, policy, scope, profile);
            } catch (PaceException failure) {
                err.println("algorithm=" + cli.algorithm);
                err.println("status=" + failure.status());
                err.println("execution_policy=" + policy);
                err.println("exactness_scope=" + ExactnessScope.NOT_CERTIFIED);
                err.println("reason=" + failure.getMessage());
                return 2;
            }
            return 0;
        }

        IPCMaxResult result;
        String resultAlgorithm = cli.algorithm;
        if ("oracle".equals(cli.algorithm)) {
            TinyGraphBruteForceOracle oracle = cli.maxPathLength > 0
                    ? new TinyGraphBruteForceOracle(graph, cli.maxPathLength)
                    : new TinyGraphBruteForceOracle(graph);
            resultAlgorithm = cli.maxPathLength > 0 ? "oracle-bounded" : "oracle-exhaustive";
            result = oracle.solve(query);
        } else if ("fastest".equals(cli.algorithm)) {
            result = new RepeatedFastestPathBaseline(graph).solve(query);
        } else {
            throw new IllegalArgumentException("unknown --algorithm: " + cli.algorithm);
        }

        printResult(out, resultAlgorithm, result);
        return 0;
    }

    private static TDGraph demoGraph() {
        PiecewiseConstFn scoreA = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 420, 0),
                new PiecewiseConstFn.Interval(420, 600, 8),
                new PiecewiseConstFn.Interval(600, 1440, 0)));
        PiecewiseConstFn scoreB = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 420, 0),
                new PiecewiseConstFn.Interval(420, 600, 5),
                new PiecewiseConstFn.Interval(600, 1440, 0)));
        return new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .node(4)
                .edge(1, 2, 10, scoreA)
                .edge(2, 4, 10, PiecewiseConstFn.zeroFullDay())
                .edge(1, 3, 5, scoreB)
                .edge(3, 4, 40, PiecewiseConstFn.zeroFullDay())
                .edge(1, 4, 25, PiecewiseConstFn.zeroFullDay())
                .build();
    }

    private static void printResult(PrintStream out, String algorithm, IPCMaxResult result) {
        out.println("algorithm=" + algorithm);
        out.println("found=" + result.found());
        if (!result.found()) {
            out.println("reason=" + result.reason());
            return;
        }
        out.println("departure_time=" + result.departureTime());
        out.println("arrival_time=" + result.arrivalTime());
        out.println("travel_time=" + result.travelTime());
        out.println("score=" + result.score());
        out.println("path_arc_ids=" + result.path().arcIds());
    }

    private static void printProfile(
            PrintStream out,
            String algorithm,
            PaceExecutionPolicy policy,
            ExactnessScope exactnessScope,
            EnvelopeProfile profile) {
        out.println("algorithm=" + algorithm);
        out.println("status=SUCCESS");
        out.println("execution_policy=" + policy);
        out.println("exactness_scope=" + exactnessScope);
        out.println("segments=" + profile.segments().size());
        for (int index = 0; index < profile.segments().size(); index++) {
            EnvelopeSegment segment = profile.segments().get(index);
            String left = segment.interval().startInclusive() ? "[" : "(";
            String right = segment.interval().endInclusive() ? "]" : ")";
            String path = segment.noPath() ? "NO_PATH" : segment.path().arcIds().toString();
            out.println("segment_" + index + "="
                    + left + segment.interval().start() + ',' + segment.interval().end() + right
                    + " -> " + path);
        }
    }

    private static final class CliArgs {
        private boolean demo;
        private Path graphDirectory;
        private String algorithm = "pace-b";
        private int source = 1;
        private int destination = 4;
        private int departureStart = 420;
        private int departureEnd = 420;
        private double maxTravelTime = 60;
        private int granularity = 1;
        private int maxPathLength = 0;
        private int theta = 2;
        private int anchorLimit = 32;
        private int candidateLimit = 32;
        private int threadCount = 1;

        private static CliArgs parse(String[] args) {
            CliArgs cli = new CliArgs();
            List<String> tokens = new ArrayList<>(List.of(args));
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                switch (token) {
                    case "--demo" -> cli.demo = true;
                    case "--graph" -> cli.graphDirectory = Path.of(requireValue(tokens, ++i, token));
                    case "--algorithm" -> cli.algorithm = requireValue(tokens, ++i, token);
                    case "--source" -> cli.source = Integer.parseInt(requireValue(tokens, ++i, token));
                    case "--destination" -> cli.destination = Integer.parseInt(requireValue(tokens, ++i, token));
                    case "--departure-start" -> cli.departureStart = Integer.parseInt(requireValue(tokens, ++i, token));
                    case "--departure-end" -> cli.departureEnd = Integer.parseInt(requireValue(tokens, ++i, token));
                    case "--budget" -> cli.maxTravelTime = Double.parseDouble(requireValue(tokens, ++i, token));
                    case "--granularity" -> cli.granularity = Integer.parseInt(requireValue(tokens, ++i, token));
                    case "--max-path-length" -> cli.maxPathLength = Integer.parseInt(requireValue(tokens, ++i, token));
                    case "--theta" -> cli.theta = Integer.parseInt(requireValue(tokens, ++i, token));
                    case "--anchor-limit" -> cli.anchorLimit = Integer.parseInt(requireValue(tokens, ++i, token));
                    case "--candidate-limit" -> cli.candidateLimit = Integer.parseInt(requireValue(tokens, ++i, token));
                    case "--threads" -> cli.threadCount = Integer.parseInt(requireValue(tokens, ++i, token));
                    default -> throw new IllegalArgumentException("unknown argument: " + token);
                }
            }
            return cli;
        }

        private static String requireValue(List<String> tokens, int index, String option) {
            if (index >= tokens.size()) {
                throw new IllegalArgumentException("missing value for " + option);
            }
            return tokens.get(index);
        }
    }
}
