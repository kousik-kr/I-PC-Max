package edu.ipcmax.core.index;

import edu.ipcmax.core.validate.Path;

/**
 * Admissible directed lower-bound routing interface used by query preparation.
 *
 * <p>The repository currently provides an exact Dijkstra implementation.
 * Large production datasets still need a separately verified scalable routing
 * index; callers must not relabel the fallback as such.</p>
 */
public interface LowerBoundOracle {
    /** Admissible, strictly positive lower-bound weight for a directed arc. */
    double edgeWeight(int arcId);

    /** Exact/admissible labels from a source over outgoing directed arcs. */
    Labels distancesFrom(int source);

    /** Exact/admissible labels to a target over incoming directed arcs. */
    Labels distancesTo(int target);

    /** Immutable distance labels and deterministic path witnesses. */
    interface Labels {
        double distance(int node);

        boolean reached(int node);

        int edgeCount(int node);

        /**
         * Directed source-to-node path for forward labels, or node-to-target
         * path for reverse labels.
         */
        Path witnessPath(int node);
    }
}
