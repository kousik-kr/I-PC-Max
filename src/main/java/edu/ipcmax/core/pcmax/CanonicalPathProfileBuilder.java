package edu.ipcmax.core.pcmax;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;

/**
 * Replays one stable path edge by edge to obtain a decomposition-independent
 * temporal profile.
 *
 * <p>PACE can discover the same path through several legal anchor split trees.
 * A final path profile must therefore depend only on the ordered arc ids and
 * the current subproblem, never on the split tree that happened to discover
 * it. This builder is the single normalization boundary for that invariant.</p>
 */
final class CanonicalPathProfileBuilder {
    private CanonicalPathProfileBuilder() {
    }

    static Optional<CandidateProfile> replay(
            TDGraph graph,
            AnchorIndex anchors,
            List<Integer> arcIds,
            int source,
            int destination,
            Domain requestedDomain,
            double budget,
            int pivotId,
            boolean compressed) {
        if (anchors == null) {
            throw new IllegalArgumentException("anchor index is required");
        }
        return replay(
                graph,
                anchors.queryHorizon(),
                anchors.anchorArcIds(),
                arcIds,
                source,
                destination,
                requestedDomain,
                budget,
                pivotId,
                compressed);
    }

    /**
     * Replays a path for the layered engine using only the selected query-wide
     * pivot set. Non-selected score-bearing arcs remain ordinary scored arcs.
     */
    static Optional<CandidateProfile> replay(
            TDGraph graph,
            Domain queryHorizon,
            Set<Integer> selectedPivotArcIds,
            List<Integer> arcIds,
            int source,
            int destination,
            Domain requestedDomain,
            double budget,
            int pivotId,
            boolean compressed) {
        if (graph == null
                || queryHorizon == null
                || selectedPivotArcIds == null
                || arcIds == null
                || requestedDomain == null) {
            throw new IllegalArgumentException("graph, anchor index, path, and requested domain are required");
        }
        Domain rootDomain = requestedDomain.intersection(queryHorizon);
        if (rootDomain.isEmpty()) {
            return Optional.empty();
        }
        if (budget < 0 || !Double.isFinite(budget)) {
            throw new IllegalArgumentException("travel budget must be finite and nonnegative");
        }

        int current = source;
        int explicitAnchorCount = 0;
        Set<Integer> vertices = new HashSet<>();
        vertices.add(source);
        TimeProfile arrival = TimeProfile.identity(rootDomain);
        ScoreProfile score = ScoreProfile.constant(rootDomain, 0);

        for (int arcId : arcIds) {
            if (arcId < 0 || arcId >= graph.edgeCount()) {
                throw new IllegalArgumentException("candidate contains unknown arc id: " + arcId);
            }
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != current) {
                throw new IllegalArgumentException(
                        "candidate path is discontinuous at arc " + arcId
                                + ": expected source " + current);
            }
            if (!vertices.add(edge.target())) {
                throw new IllegalArgumentException("candidate path is not vertex-simple: " + arcIds);
            }
            if (selectedPivotArcIds.contains(arcId)) {
                explicitAnchorCount++;
            }

            Domain validEntry = PaceProfiles.validEntryDomain(
                    edge, queryHorizon);
            Domain entryDomain = arrival.preimage(validEntry, arrival.domain());
            if (entryDomain.isEmpty()) {
                return Optional.empty();
            }

            TimeProfile edgeEntry = arrival.restrict(entryDomain);
            ScoreProfile scoreBeforeEdge = score.restrict(entryDomain);
            ScoreProfile edgeScore = ScoreProfile.compose(
                    edgeEntry,
                    edge.scoreFunction(),
                    entryDomain,
                    "canonical-path-edge-score:path=" + arcIds + ":arc=" + arcId);
            TimeProfile edgeArrival = PaceProfiles.edgeArrivalProfile(
                    edge,
                    validEntry,
                    "canonical-path-edge-arrival:path=" + arcIds);
            arrival = edgeEntry.compose(
                    edgeArrival,
                    "canonical-path-arrival:path=" + arcIds + ":arc=" + arcId);
            score = scoreBeforeEdge.add(
                    edgeScore,
                    entryDomain,
                    "canonical-path-score:path=" + arcIds + ":arc=" + arcId);
            current = edge.target();
        }

        if (current != destination) {
            throw new IllegalArgumentException(
                    "candidate path ends at " + current
                            + " instead of subproblem destination " + destination);
        }

        Domain withinHorizon = arrival.preimage(
                queryHorizon, arrival.domain());
        if (withinHorizon.isEmpty()) {
            return Optional.empty();
        }
        TimeProfile horizonArrival = arrival.restrict(withinHorizon);
        Domain feasible = horizonArrival.domainWhereTravelTimeAtMost(withinHorizon, budget);
        if (feasible.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CandidateProfile(
                feasible,
                horizonArrival.restrict(feasible),
                score.restrict(feasible),
                PathPointer.of(arcIds),
                explicitAnchorCount,
                pivotId,
                compressed));
    }
}
