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
     * Legacy root-time positive-score domain helper.
     *
     * <p>Exact PACE anchor handling must evaluate score domains at actual edge-entry time; see
     * {@link PaceFrontierGenerator} and {@link TemporalStitch}.</p>
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
