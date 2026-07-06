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
     * Combines candidates over their common valid domain.
     */
    @Deprecated
    public static CandidateProfile combine(CandidateProfile left, Edge edge, CandidateProfile right) {
        throw new UnsupportedOperationException(
                "PACE temporal stitching requires graph, root domain, and budget; use combine(graph,left,edge,right,domain,budget)");
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
