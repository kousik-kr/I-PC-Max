package edu.ipcmax.core.labeling;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.validate.Path;

import java.util.HashMap;
import java.util.Map;

/**
 * Exact discrete interval backward labeling wrapper around point backward labeling.
 */
public final class IntervalBackwardLabeling {
    private final TDGraph graph;

    /**
     * Creates an interval backward labeler.
     */
    public IntervalBackwardLabeling(TDGraph graph) {
        this.graph = graph;
    }

    /**
     * Computes suffix witnesses for each root departure time.
     */
    public Map<Integer, Path> suffixes(int node, int destination, Domain rootDomain, double budget) {
        Map<Integer, Path> suffixByDeparture = new HashMap<>();
        PointBackwardLabeling labeler = new PointBackwardLabeling(graph);
        for (int departure : rootDomain) {
            PointBackwardLabeling.Result result = labeler.run(destination, departure + budget);
            if (result.reached(node)) {
                suffixByDeparture.put(departure, result.pathFrom(node));
            }
        }
        return Map.copyOf(suffixByDeparture);
    }
}
