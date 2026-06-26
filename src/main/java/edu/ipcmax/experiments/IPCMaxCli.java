package edu.ipcmax.experiments;

import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.core.loader.GeneratedGraphLoader;
import edu.ipcmax.core.pcmax.IPCMaxResult;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.pcmax.RepeatedFastestPathBaseline;
import edu.ipcmax.core.pcmax.TinyGraphBruteForceOracle;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal command-line runner for current baselines and smoke checks.
 */
public final class IPCMaxCli {
    private IPCMaxCli() {
    }

    /**
     * Runs a query with the current exact tiny oracle or fastest-path baseline.
     */
    public static void main(String[] args) throws Exception {
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

        IPCMaxResult result;
        if ("oracle".equals(cli.algorithm)) {
            result = new TinyGraphBruteForceOracle(graph, cli.maxPathLength).solve(query);
        } else if ("fastest".equals(cli.algorithm)) {
            result = new RepeatedFastestPathBaseline(graph).solve(query);
        } else {
            throw new IllegalArgumentException("unknown --algorithm: " + cli.algorithm);
        }

        printResult(cli.algorithm, result);
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

    private static void printResult(String algorithm, IPCMaxResult result) {
        System.out.println("algorithm=" + algorithm);
        System.out.println("found=" + result.found());
        if (!result.found()) {
            System.out.println("reason=" + result.reason());
            return;
        }
        System.out.println("departure_time=" + result.departureTime());
        System.out.println("arrival_time=" + result.arrivalTime());
        System.out.println("travel_time=" + result.travelTime());
        System.out.println("score=" + result.score());
        System.out.println("path_arc_ids=" + result.path().arcIds());
    }

    private static final class CliArgs {
        private boolean demo;
        private Path graphDirectory;
        private String algorithm = "fastest";
        private int source = 1;
        private int destination = 4;
        private int departureStart = 420;
        private int departureEnd = 420;
        private double maxTravelTime = 60;
        private int granularity = 1;
        private int maxPathLength = 8;

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
