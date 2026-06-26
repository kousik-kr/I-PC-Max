package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact discrete feasible-edge profile generator using positive score filtering.
 */
public final class FeasibleEdgeProfileGenerator {
    private final TDGraph graph;

    /**
     * Creates the generator.
     */
    public FeasibleEdgeProfileGenerator(TDGraph graph) {
        this.graph = graph;
    }

    /**
     * Computes positive-score feasible edge profiles over a root domain.
     */
    public List<FeasibleEdgeProfile> compute(Domain rootDomain) {
        List<FeasibleEdgeProfile> profiles = new ArrayList<>();
        for (Edge edge : graph.edges()) {
            Domain positive = ScorePositiveDomain.forEdge(edge, rootDomain);
            if (!positive.isEmpty()) {
                profiles.add(new FeasibleEdgeProfile(edge, positive, positive, 0, 1440));
            }
        }
        return List.copyOf(profiles);
    }
}
