package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;

/**
 * Interval feasible-edge metadata.
 */
public record FeasibleEdgeProfile(
        Edge edge,
        Domain feasibleDomain,
        Domain scorePositiveDomain,
        double lowerArrivalBound,
        double upperDepartureBound) {
    /**
     * Creates a validated profile.
     */
    public FeasibleEdgeProfile {
        if (edge == null || feasibleDomain == null || feasibleDomain.isEmpty()) {
            throw new IllegalArgumentException("edge and non-empty feasible domain are required");
        }
        if (scorePositiveDomain == null) {
            throw new IllegalArgumentException("score positive domain is required");
        }
    }
}
