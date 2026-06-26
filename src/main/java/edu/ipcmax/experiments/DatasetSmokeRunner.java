package edu.ipcmax.experiments;

import edu.ipcmax.core.function.TimeRange;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.loader.GeneratedGraphDataset;
import edu.ipcmax.core.loader.GeneratedGraphLoader;
import edu.ipcmax.core.validate.GraphValidator;

import java.nio.file.Path;

/**
 * CLI smoke runner that loads a generated graph directory and prints structural counts.
 */
public final class DatasetSmokeRunner {
    private DatasetSmokeRunner() {
    }

    /**
     * Loads and validates a generated graph directory.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: DatasetSmokeRunner [--strict-fifo] <generated-graph-directory>");
            System.exit(2);
        }
        boolean strictFifo = args.length == 2 && "--strict-fifo".equals(args[0]);
        if (args.length == 2 && !strictFifo) {
            throw new IllegalArgumentException("unknown option: " + args[0]);
        }
        Path directory = Path.of(args[args.length - 1]);

        GeneratedGraphDataset dataset = new GeneratedGraphLoader().load(directory);
        TDGraph graph = dataset.graph();
        int positiveRushEdges = graph.edgesWithPositiveScore(new TimeRange(420, 600)).size();
        long nonFifoEdges = GraphValidator.countNonFifoEdges(graph);
        if (strictFifo && nonFifoEdges > 0) {
            throw new IllegalStateException("strict FIFO check failed: " + nonFifoEdges + " non-FIFO edges");
        }

        System.out.println("directory=" + dataset.directory());
        System.out.println("nodes=" + graph.nodeCount());
        System.out.println("edges=" + graph.edgeCount());
        System.out.println("non_fifo_edges=" + nonFifoEdges);
        System.out.println("positive_score_edges_morning=" + positiveRushEdges);
        if (!graph.edges().isEmpty()) {
            Edge first = graph.edges().get(0);
            System.out.println("first_arc=" + first.arcId() + "," + first.source() + "->" + first.target()
                    + ",base_time=" + first.baseTravelTime()
                    + ",score_max=" + first.scoreFunction().maxValue());
        }
    }
}
