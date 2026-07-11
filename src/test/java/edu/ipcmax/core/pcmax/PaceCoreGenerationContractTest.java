package edu.ipcmax.core.pcmax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;

class PaceCoreGenerationContractTest {
    @Test
    void queryHorizonIsValidatedWithoutWrappingOrExtrapolation() {
        TDGraph graph = new TDGraph(
                List.of(new Node(1, 0, 0), new Node(2, 0, 0)),
                List.of(new Edge(
                        0,
                        1,
                        2,
                        1,
                        1,
                        constantTravel(0, 10, 1),
                        new PiecewiseConstFn(List.of(new PiecewiseConstFn.Interval(0, 10, 0))))));
        PaceFrontierGenerator generator = new PaceFrontierGenerator(graph, PaceOptions.exhaustive(0));

        assertFalse(generator.generateFrontier(new QuerySpec(1, 2, 5, 8, 2, 1)).isEmpty());
        PaceException failure = assertThrows(
                PaceException.class,
                () -> generator.generateFrontier(new QuerySpec(1, 2, 5, 8, 3, 1)));
        assertEquals(PaceStatus.FUNCTION_HORIZON_EXCEEDED, failure.status());
    }

    @Test
    void anchorValidDomainDiffersFromPositiveDomainAndZeroScoreTraversalRemainsValid() {
        PiecewiseConstFn score = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 8, 0),
                new PiecewiseConstFn.Interval(8, 9, 7),
                new PiecewiseConstFn.Interval(9, 1440, 0)));
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .edge(1, 2, 1, score)
                .build();
        Domain horizon = Domain.closed(0, 10);
        AnchorIndex index = AnchorIndex.create(graph, horizon);

        assertEquals(1, index.anchors().size());
        Anchor anchor = index.anchors().get(0);
        assertTrue(anchor.validDomain().contains(2));
        assertFalse(anchor.positiveDomain().contains(2));

        CandidateSet noAnchors = new PaceFrontierGenerator(graph, PaceOptions.exhaustive(0))
                .generateFrontier(new QuerySpec(1, 2, 0, 5, 5, 1));
        assertTrue(noAnchors.isEmpty(), "the anchor edge must be absent from G_0");

        CandidateSet oneAnchor = new PaceFrontierGenerator(graph, PaceOptions.exhaustive(1))
                .generateFrontier(new QuerySpec(1, 2, 0, 5, 5, 1));
        CandidateProfile candidate = oneAnchor.candidates().stream()
                .filter(item -> item.stablePathId().equals(List.of(0)))
                .findFirst()
                .orElseThrow();
        assertTrue(candidate.domain().contains(2));
        assertEquals(0, candidate.scoreProfile().valueAt(2));
        assertEquals(1, candidate.explicitAnchorCount());
    }

    @Test
    void connectorsUseAnchorFreeGraphAndPoliciesHaveExactDeterministicLimits() {
        PiecewiseConstFn positive = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 1440, 5)));
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .node(4)
                .edge(1, 2, 2)
                .edge(1, 3, 1)
                .edge(3, 2, 1)
                .edge(1, 4, 1)
                .edge(4, 2, 2)
                .edge(3, 4, 1, positive)
                .build();
        Domain horizon = Domain.closed(0, 10);
        AnchorIndex index = AnchorIndex.create(graph, horizon);
        QueryLowerBounds lowerBounds = new QueryLowerBounds(graph, horizon);

        CandidateSet exhaustive = new ConnectorProfiles(
                graph,
                index,
                lowerBounds,
                PaceOptions.exhaustive(0))
                .generate(1, 2, Domain.closed(0, 5), 5);
        assertEquals(3, exhaustive.size());
        assertTrue(exhaustive.candidates().stream()
                .flatMap(candidate -> candidate.stablePathId().stream())
                .noneMatch(index::isAnchorArc));
        assertTrue(exhaustive.candidates().stream()
                .allMatch(candidate -> candidate.scoreProfile().valueAt(2) == 0));

        CandidateSet bounded = new ConnectorProfiles(
                graph,
                index,
                lowerBounds,
                PaceOptions.bounded(0, 1, 2))
                .generate(1, 2, Domain.closed(0, 5), 5);
        assertEquals(2, bounded.size());
        assertEquals(
                List.of(List.of(0), List.of(1, 2)),
                bounded.candidates().stream().map(CandidateProfile::stablePathId).toList());

        CandidateSet identity = new ConnectorProfiles(
                graph,
                index,
                lowerBounds,
                PaceOptions.exhaustive(0))
                .generate(1, 1, Domain.closed(0, 5), 5);
        assertEquals(1, identity.size());
        assertTrue(identity.candidates().get(0).stablePathId().isEmpty());
    }

    @Test
    void explicitAnchorBudgetUsesComplementaryLeftRightSplit() {
        PiecewiseConstFn positive = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 1440, 1)));
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .edge(1, 2, 1, positive)
                .edge(2, 3, 1, positive)
                .build();
        QuerySpec query = new QuerySpec(1, 3, 0, 5, 5, 1);

        assertTrue(new PaceFrontierGenerator(graph, PaceOptions.exhaustive(0))
                .generateFrontier(query).isEmpty());
        assertTrue(new PaceFrontierGenerator(graph, PaceOptions.exhaustive(1))
                .generateFrontier(query).isEmpty());
        CandidateSet thetaTwo = new PaceFrontierGenerator(graph, PaceOptions.exhaustive(2))
                .generateFrontier(query);

        assertFalse(thetaTwo.isEmpty());
        assertTrue(thetaTwo.candidates().stream()
                .allMatch(candidate -> candidate.explicitAnchorCount() <= 2));
        assertTrue(thetaTwo.candidates().stream()
                .anyMatch(candidate -> candidate.stablePathId().equals(List.of(0, 1))
                        && candidate.explicitAnchorCount() == 2));

        PaceFrontierGenerator memoized = new PaceFrontierGenerator(graph, PaceOptions.exhaustive(2));
        CandidateSet first = memoized.generateFrontier(query);
        CandidateSet second = memoized.generateFrontier(query);
        assertEquals(serialize(first), serialize(second));
        assertTrue(memoized.stats().cacheHits() > 0);
    }

    @Test
    void boundedAnchorRankingUsesScorePotentialBeforeStableArcId() {
        PiecewiseConstFn low = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 10, 1),
                new PiecewiseConstFn.Interval(10, 1440, 0)));
        PiecewiseConstFn high = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 10, 5),
                new PiecewiseConstFn.Interval(10, 1440, 0)));
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .node(4)
                .edge(1, 2, 1, low)
                .edge(2, 4, 1)
                .edge(1, 3, 1, high)
                .edge(3, 4, 1)
                .build();
        Domain horizon = Domain.closed(0, 10);
        AnchorIndex index = AnchorIndex.create(graph, horizon);
        List<RelevantAnchor> ranked = index.relevantAnchors(
                1,
                4,
                Domain.closed(0, 5),
                5,
                new QueryLowerBounds(graph, horizon),
                PaceOptions.bounded(1, 1, 1));

        assertEquals(1, ranked.size());
        assertEquals(2, ranked.get(0).anchor().stableArcId());
    }

    private static PiecewiseLinearFn constantTravel(double start, double end, double value) {
        return new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(start, value),
                new PiecewiseLinearFn.Breakpoint(end, value)));
    }

    private static List<String> serialize(CandidateSet candidates) {
        return candidates.candidates().stream()
                .map(candidate -> candidate.stablePathId()
                        + "|" + candidate.domain()
                        + "|" + candidate.arrivalProfile().fingerprint()
                        + "|" + candidate.scoreProfile().fingerprint())
                .toList();
    }
}
