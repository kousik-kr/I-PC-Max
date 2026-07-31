package edu.ipcmax.core.pcmax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.core.index.EdgeTemporalSummaryStore;
import edu.ipcmax.core.index.GraphPartitionMetadata;
import edu.ipcmax.core.index.ScoreSupportIndex;
import edu.ipcmax.core.profile.CandidateProfile;

class ScalablePaceCandidateEngineTest {
    @Test
    void nonSelectedScoreEdgeRemainsInsideAnExactConnector() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1).node(2).node(3)
                .edge(1, 2, 1, score(7))
                .edge(2, 3, 1)
                .build();
        PACE pace = new PACE(
                graph,
                PaceOptions.bounded(0, 0, 4, 100, 4, 100, 100, 1));

        PaceGenerationResult result =
                pace.generate(new QuerySpec(1, 3, 0, 5, 3, 1));

        assertEquals(List.of(), result.selectedPivotArcIds());
        var scored = result.frontier().candidates().stream()
                .filter(candidate ->
                        candidate.stablePathId().equals(List.of(0, 1)))
                .findFirst()
                .orElseThrow();
        assertEquals(7, scored.scoreProfile().valueAt(0));
        assertEquals(PaceExactnessScope.RETAINED_FRONTIER,
                result.exactnessScope());
    }

    @Test
    void pivotLimitAndTraversalDepthRemainIndependent() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1).node(2).node(3).node(4)
                .edge(1, 2, 1, score(9))
                .edge(2, 3, 1, score(8))
                .edge(3, 4, 1)
                .edge(1, 4, 3)
                .build();
        PACE pace = new PACE(
                graph,
                PaceOptions.bounded(
                        1, 2, 8, 1_000,
                        8, 1_000, 10_000, 1));

        PaceGenerationResult result = pace.generate(
                new QuerySpec(1, 4, 0, 5, 5, 1));

        assertEquals(2, result.selectedPivotArcIds().size(),
                "L selects pivots independently of theta");
        assertTrue(result.frontier().candidates().stream()
                .allMatch(candidate ->
                        candidate.explicitAnchorCount() <= 1),
                "theta limits only per-candidate pivot traversal");
    }

    @Test
    void connectorAndQueryWorkCapsAreHardUpperBoundsWithTypedStatus() {
        TDGraph connectorGraph = branchingGraph(false);
        PACE connectorLimited = new PACE(
                connectorGraph,
                PaceOptions.bounded(0, 0, 8, 2, 8, 100, 100, 1));
        PaceGenerationResult connectorResult = connectorLimited.generate(
                new QuerySpec(1, 5, 0, 5, 20, 1));
        assertTrue(connectorResult.stats().connectorExpansions() <= 2);
        assertTrue(connectorResult.capStatus().reached(
                PaceCapKind.CONNECTOR_M_C));
        assertEquals(PaceCompletion.RESOURCE_TRUNCATED,
                connectorResult.completion());

        TDGraph pivotGraph = branchingGraph(true);
        PACE queryLimited = new PACE(
                pivotGraph,
                PaceOptions.bounded(2, 8, 8, 100, 8, 100, 1, 1));
        PaceGenerationResult queryResult = queryLimited.generate(
                new QuerySpec(1, 5, 0, 5, 20, 1));
        assertTrue(queryResult.stats().totalWork() <= 1);
        assertTrue(queryResult.capStatus().reached(
                PaceCapKind.QUERY_WORK_M_Q));
        assertEquals(PaceCompletion.RESOURCE_TRUNCATED,
                queryResult.completion());
        assertEquals(PaceExactnessScope.RETAINED_FRONTIER,
                queryResult.exactnessScope());

        PACE breakpointLimited = new PACE(
                connectorGraph,
                PaceOptions.bounded(0, 0, 8, 100, 8, 1, 100, 1));
        PaceGenerationResult breakpointResult =
                breakpointLimited.generate(
                        new QuerySpec(1, 5, 0, 5, 20, 1));
        assertTrue(breakpointResult.capStatus().reached(
                PaceCapKind.BREAKPOINT_M_B));
        assertTrue(breakpointResult.stats().breakpointCapHits() > 0);
    }

    @Test
    void resourceTruncatedPaceXFailsItsGlobalCertificate() {
        TDGraph graph = branchingGraph(false);
        PaceOptions guarded = new PaceOptions(
                PaceExecutionPolicy.PACE_X,
                PaceEngineMode.SCALABLE,
                0,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                true,
                PaceFeatures.defaults(),
                1);
        PaceGenerationResult result = new PACE(
                graph, guarded).generate(
                        new QuerySpec(1, 5, 0, 5, 20, 1));

        assertEquals(PaceCompletion.ABORTED, result.completion());
        assertEquals(PaceExactnessScope.NOT_CERTIFIED,
                result.exactnessScope());
        assertTrue(result.capStatus().reached(
                PaceCapKind.EMERGENCY_FRONTIER_GUARD));
    }

    @Test
    void connectorStreamIsPrefixStableAndAlwaysLoopless() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1).node(2).node(3).node(4)
                .edge(1, 2, 1)
                .edge(1, 2, 1.5)
                .edge(2, 2, 0.25)
                .edge(2, 3, 1)
                .edge(3, 4, 1)
                .edge(2, 4, 3)
                .edge(1, 3, 2.5)
                .build();

        List<List<Integer>> one = connectors(graph, 1);
        List<List<Integer>> four = connectors(graph, 4);

        assertFalse(one.isEmpty());
        assertIterableEquals(one, four.subList(0, one.size()));
        assertTrue(four.stream().noneMatch(path -> path.contains(2)),
                "self-loop must never appear in a connector");
        for (List<Integer> path : four) {
            assertTrue(CanonicalPathProfileBuilder.replay(
                    graph,
                    Domain.closed(0, 20),
                    Set.of(),
                    path,
                    1,
                    4,
                    Domain.closed(0, 5),
                    20,
                    -1,
                    false).orElseThrow().isVertexSimple(graph, 1, 4));
        }
    }

    @Test
    void repeatedConnectorRequestUsesTheCachedExactProfiles() {
        TDGraph graph = branchingGraph(false);
        Domain horizon = Domain.closed(0, 20);
        EdgeTemporalSummaryStore summaries =
                EdgeTemporalSummaryStore.build(graph);
        GraphPartitionMetadata partition =
                GraphPartitionMetadata.partition(graph);
        QueryLowerBounds lowerBounds =
                new QueryLowerBounds(graph, summaries);
        QueryCorridor corridor = QueryCorridor.build(
                graph, lowerBounds, partition, 1, 5, 20);
        PivotIndex pivots =
                new PivotIndex(List.of(), List.of(), "empty");
        PaceOptions options =
                PaceOptions.bounded(0, 0, 8, 1_000, 16, 1_000, 1_000, 1);
        PaceWorkLedger ledger = new PaceWorkLedger(options);
        BoundedConnectorGenerator generator =
                new BoundedConnectorGenerator(
                        graph, corridor, pivots, lowerBounds, summaries,
                        horizon, options, ledger);
        BitSet visited = new BitSet();
        visited.set(1);

        ConnectorResult first = generator.connect(
                1, 5, Domain.closed(0, 5), visited, 20, "first");
        long computedExpansions = ledger.connectorExpansions();
        ConnectorResult second = generator.connect(
                1, 5, Domain.closed(0, 5), visited, 20, "second");

        assertEquals(
                first.connectors().stream()
                        .map(candidate -> candidate.stablePathId()).toList(),
                second.connectors().stream()
                        .map(candidate -> candidate.stablePathId()).toList());
        assertEquals(computedExpansions, ledger.connectorExpansions());
        assertTrue(generator.cacheHits() >= 1);
        assertTrue(generator.cacheMisses() >= 1);
    }

    @Test
    void queryScopedSuffixLabelsUseForwardTemporalSemantics() {
        Domain horizon = Domain.closed(0, 20);
        PiecewiseConstFn zero =
                PiecewiseConstFn.constant(horizon, 0);
        TDGraph graph = new TDGraph(
                List.of(
                        new Node(1, 0, 0),
                        new Node(2, 1, 1),
                        new Node(3, 2, 2)),
                List.of(
                        new Edge(
                                0, 1, 2, 1, 1,
                                new PiecewiseLinearFn(List.of(
                                        new PiecewiseLinearFn.Breakpoint(
                                                0, 1),
                                        new PiecewiseLinearFn.Breakpoint(
                                                10, 5),
                                        new PiecewiseLinearFn.Breakpoint(
                                                20, 5))),
                                zero),
                        new Edge(
                                1, 2, 3, 1, 1,
                                new PiecewiseLinearFn(List.of(
                                        new PiecewiseLinearFn.Breakpoint(
                                                0, 1),
                                        new PiecewiseLinearFn.Breakpoint(
                                                20, 1))),
                                zero)));
        EdgeTemporalSummaryStore summaries =
                EdgeTemporalSummaryStore.build(graph);
        GraphPartitionMetadata partition =
                GraphPartitionMetadata.partition(graph);
        QueryLowerBounds lowerBounds =
                new QueryLowerBounds(graph, summaries);
        QueryCorridor corridor = QueryCorridor.build(
                graph, lowerBounds, partition, 1, 3, 20);
        PivotIndex pivots = new PivotIndex(
                List.of(), List.of(), "empty");
        PaceOptions options = PaceOptions.bounded(
                0, 0, 4, 1_000,
                4, 1_000, 10_000, 1);
        PaceWorkLedger ledger = new PaceWorkLedger(options);
        BoundedConnectorGenerator generator =
                new BoundedConnectorGenerator(
                        graph,
                        corridor,
                        pivots,
                        lowerBounds,
                        summaries,
                        horizon,
                        options,
                        ledger);
        QueryScopedConnectorLabelStore labels =
                new QueryScopedConnectorLabelStore(
                        generator,
                        lowerBounds.truncatedDistancesFrom(
                                1, 20),
                        lowerBounds.truncatedDistancesTo(
                                3, 20),
                        PaceExecutionMetrics.none());
        BitSet vertices = new BitSet();
        vertices.set(1);

        ConnectorResult firstResult = labels.suffixLabels(
                1,
                3,
                Domain.closed(0, 5),
                vertices,
                new BitSet(),
                20,
                "suffix");
        CandidateProfile actual = firstResult.connectors().get(0);
        ConnectorResult reusedResult = labels.suffixLabels(
                1,
                3,
                Domain.closed(0, 5),
                vertices,
                new BitSet(),
                20,
                "suffix-reused");
        assertEquals(
                firstResult.connectors().stream()
                        .map(CandidateProfile::stablePathId)
                        .toList(),
                reusedResult.connectors().stream()
                        .map(CandidateProfile::stablePathId)
                        .toList());
        CandidateProfile expected =
                CanonicalPathProfileBuilder.replay(
                        graph,
                        horizon,
                        Set.of(),
                        List.of(0, 1),
                        1,
                        3,
                        Domain.closed(0, 5),
                        20,
                        -1,
                        false).orElseThrow();

        assertEquals(expected.stablePathId(), actual.stablePathId());
        assertEquals(
                expected.arrivalProfile().valueAt(5),
                actual.arrivalProfile().valueAt(5));
        assertEquals(9, actual.arrivalProfile().valueAt(5));
    }

    @Test
    void selectedPivotsAreAPrefixForLargerL() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1).node(2).node(3).node(4).node(5)
                .edge(1, 2, 1, score(9))
                .edge(2, 5, 1)
                .edge(1, 3, 1, score(8))
                .edge(3, 5, 1)
                .edge(1, 4, 1, score(7))
                .edge(4, 5, 1)
                .build();
        Domain horizon = Domain.closed(0, 10);
        EdgeTemporalSummaryStore summaries =
                EdgeTemporalSummaryStore.build(graph);
        GraphPartitionMetadata partition =
                GraphPartitionMetadata.partition(graph, 2);
        ScoreSupportIndex scores =
                ScoreSupportIndex.build(summaries, partition);
        QueryLowerBounds lowerBounds =
                new QueryLowerBounds(graph, horizon);
        QueryCorridor corridor = QueryCorridor.build(
                graph, lowerBounds, partition, 1, 5, 3);

        PivotIndex one = PivotSelector.select(
                graph, corridor, lowerBounds, partition,
                summaries, scores, horizon, 1);
        PivotIndex three = PivotSelector.select(
                graph, corridor, lowerBounds, partition,
                summaries, scores, horizon, 3);

        assertIterableEquals(
                one.selectedArcIds(),
                three.selectedArcIds().subList(
                        0, one.selectedArcIds().size()));
        assertEquals(3, three.scoreRelevantArcIds().size());
    }

    @Test
    void connectorCoveredPivotIsNotDuplicatedAndItsScoreIsCountedOnce() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1).node(2).node(3).node(4)
                .edge(1, 2, 1, score(5))
                .edge(2, 3, 1, score(7))
                .edge(3, 4, 1, score(3))
                .build();
        PivotIndex pivots = new PivotIndex(
                List.of(
                        new PivotIndex.Pivot(
                                0, 1, 2, 5, 10, 0,
                                "CELL-00000000", 0),
                        new PivotIndex.Pivot(
                                1, 2, 3, 7, 10, 0,
                                "CELL-00000000", 1),
                        new PivotIndex.Pivot(
                                2, 3, 4, 3, 10, 0,
                                "CELL-00000000", 2)),
                List.of(0, 1, 2),
                "fixture");
        var candidate = CanonicalPathProfileBuilder.replay(
                graph,
                Domain.closed(0, 20),
                Set.of(0, 1, 2),
                List.of(0, 1),
                1,
                3,
                Domain.closed(0, 5),
                20,
                -1,
                false).orElseThrow();

        BitSet covered = PivotCoverage.extend(
                new BitSet(), pivots, candidate.stablePathId());
        BitSet explicitlyUsed = new BitSet();
        explicitlyUsed.set(0);
        PartialCandidate partial = new PartialCandidate(
                3,
                candidate,
                candidate.vertexMembership(graph, 1, 3),
                candidate.edgeMembership(),
                explicitlyUsed,
                covered,
                List.of(0),
                1,
                Set.of());

        assertTrue(partial.coveredPivot(0));
        assertTrue(partial.coveredPivot(1));
        assertFalse(partial.usedPivot(1),
                "physically covered pivot B is not a second "
                        + "explicit branch choice");
        assertEquals(List.of(0), partial.pivotSequence());
        assertEquals(1, partial.pivotDepth(),
                "covered B must not consume a depth level");
        assertFalse(partial.coveredPivot(2),
                "the next distinct pivot C remains eligible");
        assertEquals(12, candidate.scoreProfile().valueAt(0),
                "the covered pivot score is replayed exactly once");
        assertEquals(2, candidate.edgeMembership().cardinality());
    }

    @Test
    void explicitDirectedArcAndVertexRepetitionAreRejected() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1).node(2).node(3)
                .edge(1, 2, 1)
                .edge(2, 1, 1)
                .edge(1, 3, 1)
                .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalPathProfileBuilder.replay(
                        graph,
                        Domain.closed(0, 20),
                        Set.of(),
                        List.of(0, 1, 2),
                        1,
                        3,
                        Domain.closed(0, 5),
                        20,
                        -1,
                        false));
        var repeatedArcHandle = new edu.ipcmax.core.profile.CandidateProfile(
                Domain.closed(0, 1),
                edu.ipcmax.core.profile.TimeProfile.identity(
                        Domain.closed(0, 1)),
                edu.ipcmax.core.profile.ScoreProfile.constant(
                        Domain.closed(0, 1), 0),
                edu.ipcmax.core.profile.PathPointer.of(
                        List.of(0, 0)),
                0,
                -1,
                false);
        assertThrows(
                IllegalArgumentException.class,
                repeatedArcHandle::edgeMembership);
    }

    @Test
    void relaxedScoreRateBoundIsHandComputableAndAdmissible() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1).node(2).node(3)
                .edge(1, 2, 2, score(4))
                .edge(2, 3, 1, score(1))
                .build();
        Domain horizon = Domain.closed(0, 20);
        EdgeTemporalSummaryStore summaries =
                EdgeTemporalSummaryStore.build(graph);
        GraphPartitionMetadata partition =
                GraphPartitionMetadata.partition(graph);
        QueryLowerBounds lowerBounds =
                new QueryLowerBounds(graph, horizon);
        QueryCorridor corridor = QueryCorridor.build(
                graph, lowerBounds, partition, 1, 3, 10);
        SafeScoreUpperBound bound =
                new SafeScoreUpperBound(corridor, summaries);

        assertEquals(2, bound.corridorMaximumScoreRate());
        assertEquals(15, bound.upperBound(3, 4, 10));
        assertTrue(5 <= bound.upperBound(0, 0, 3),
                "the exact two-edge path score must not exceed the relaxation");
    }

    @Test
    void scalableAndLegacyEnginesAgreeOnDiagnosticFixture() {
        TDGraph graph = branchingGraph(true);
        QuerySpec query = new QuerySpec(1, 5, 0, 5, 20, 1);
        PaceOptions base =
                PaceOptions.bounded(2, 8, 16, 10_000, 16, 10_000, 10_000, 1);

        EnvelopeProfile scalable = new PACE(graph, base).run(query);
        EnvelopeProfile legacy = new PACE(
                graph,
                base.withEngineMode(PaceEngineMode.LEGACY)).run(query);

        assertEquals(
                scalable.segments().stream()
                        .map(segment -> segment.path().arcIds()).toList(),
                legacy.segments().stream()
                        .map(segment -> segment.path().arcIds()).toList());
    }

    private static List<List<Integer>> connectors(
            TDGraph graph,
            int connectorLimit) {
        Domain horizon = Domain.closed(0, 20);
        EdgeTemporalSummaryStore summaries =
                EdgeTemporalSummaryStore.build(graph);
        GraphPartitionMetadata partition =
                GraphPartitionMetadata.partition(graph);
        QueryLowerBounds lowerBounds =
                new QueryLowerBounds(graph, horizon);
        QueryCorridor corridor = QueryCorridor.build(
                graph, lowerBounds, partition, 1, 4, 20);
        PivotIndex pivots =
                new PivotIndex(List.of(), List.of(), "empty");
        PaceOptions options = PaceOptions.bounded(
                0, 0, connectorLimit, 1_000,
                16, 1_000, 1_000, 1);
        BoundedConnectorGenerator generator =
                new BoundedConnectorGenerator(
                        graph,
                        corridor,
                        pivots,
                        lowerBounds,
                        summaries,
                        horizon,
                        options,
                        new PaceWorkLedger(options));
        BitSet visited = new BitSet();
        visited.set(1);
        return generator.connect(
                1,
                4,
                Domain.closed(0, 5),
                visited,
                20,
                "test").connectors().stream()
                .map(candidate -> candidate.stablePathId())
                .toList();
    }

    private static TDGraph branchingGraph(boolean scored) {
        PiecewiseConstFn value =
                scored ? score(5) : PiecewiseConstFn.zeroFullDay();
        return new TinyGraphBuilder()
                .node(1).node(2).node(3).node(4).node(5)
                .edge(1, 2, 1, value)
                .edge(2, 5, 1)
                .edge(1, 3, 1)
                .edge(3, 5, 2)
                .edge(1, 4, 2)
                .edge(4, 5, 1)
                .edge(2, 3, 0.5)
                .edge(3, 4, 0.5)
                .build();
    }

    private static PiecewiseConstFn score(int value) {
        return PiecewiseConstFn.constant(
                Domain.closed(0, 1440), value);
    }
}
