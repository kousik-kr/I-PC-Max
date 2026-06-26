package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;

/**
 * Computes domains where an edge has positive score.
 */
public final class ScorePositiveDomain {
    private ScorePositiveDomain() {
    }

    /**
     * Exact discrete positive-score domain using root time as edge departure time.
     */
    public static Domain forEdge(Edge edge, Domain domain) {
        Domain result = Domain.empty();
        Integer start = null;
        Integer previous = null;
        for (int t : domain) {
            boolean positive = edge.scoreFunction().valueAt(t) > 0;
            if (positive && start == null) {
                start = t;
            }
            if (!positive && start != null) {
                result = result.union(Domain.closed(start, previous));
                start = null;
            }
            previous = t;
        }
        if (start != null) {
            result = result.union(Domain.closed(start, previous));
        }
        return result;
    }
}
