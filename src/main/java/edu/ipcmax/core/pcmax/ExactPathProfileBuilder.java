package edu.ipcmax.core.pcmax;

import java.util.List;
import java.util.Optional;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateProfile;

/** Shared exact temporal replay for independently enumerated paths. */
public final class ExactPathProfileBuilder {
    private ExactPathProfileBuilder() {
    }

    /**
     * Composes edge functions at their actual entry times and restricts the path to
     * the exact function-horizon and budget-feasible departure domain.
     */
    public static Optional<CandidateProfile> replay(
            TDGraph graph,
            List<Integer> arcIds,
            int source,
            int destination,
            Domain departureDomain,
            double budget) {
        if (departureDomain == null || departureDomain.isEmpty()) {
            return Optional.empty();
        }
        return context(graph, departureDomain, budget).replay(arcIds, source, destination);
    }

    /** Prepares immutable per-query replay state once for algorithms evaluating many paths. */
    public static ReplayContext context(TDGraph graph, Domain departureDomain, double budget) {
        if (graph == null || departureDomain == null || departureDomain.isEmpty()) {
            throw new IllegalArgumentException("graph and a nonempty departure domain are required");
        }
        double start = departureDomain.intervals().get(0).start();
        double end = departureDomain.intervals().get(departureDomain.intervals().size() - 1).end();
        Domain horizon = Domain.closed(start, Domain.canonicalTime(end + budget));
        return new ReplayContext(graph, AnchorIndex.create(graph, horizon), departureDomain, budget);
    }

    /** Shared exact replay context; it does not enumerate or retain candidate paths. */
    public record ReplayContext(
            TDGraph graph, AnchorIndex anchors, Domain departureDomain, double budget) {
        public Optional<CandidateProfile> replay(
                List<Integer> arcIds, int source, int destination) {
            return CanonicalPathProfileBuilder.replay(
                    graph, anchors, arcIds, source, destination, departureDomain, budget, -1, false);
        }
    }
}
