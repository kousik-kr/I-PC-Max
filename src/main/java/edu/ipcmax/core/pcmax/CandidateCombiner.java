package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateProfile;

/**
 * Compatibility facade for PACE temporal stitching.
 */
public final class CandidateCombiner {
    private CandidateCombiner() {
    }

    /**
     * Combines candidates with PACE TemporalStitch semantics.
     */
    public static CandidateProfile combine(
            TDGraph graph,
            CandidateProfile left,
            Edge edge,
            CandidateProfile right,
            Domain rootDomain,
            double budget) {
        return TemporalStitch.stitch(graph, left, edge, right, rootDomain, budget)
                .orElseThrow(() -> new IllegalArgumentException("stitched candidate domain is empty or path-inconsistent"));
    }
}
