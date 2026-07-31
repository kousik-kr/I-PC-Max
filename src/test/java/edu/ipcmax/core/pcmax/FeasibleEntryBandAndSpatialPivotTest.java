package edu.ipcmax.core.pcmax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.core.index.EdgeTemporalSummaryStore;
import edu.ipcmax.core.index.GraphPartitionMetadata;
import edu.ipcmax.core.index.ScoreSupportIndex;

class FeasibleEntryBandAndSpatialPivotTest {
    @Test
    void feasibleEntryBandOwnsOnlyTheTerminalUpperEndpoint() {
        Domain horizon = Domain.closed(0, 20);

        assertTrue(FeasibleEntryBand.compute(
                0, 5, 2, 10, 2, 4, horizon).isEmpty());
        assertTrue(FeasibleEntryBand.compute(
                0, 5, 2, 5, 1, 1, horizon).isEmpty(),
                "ordinary endpoint-only contact is empty");
        assertEquals(
                Domain.halfOpen(2, 11),
                FeasibleEntryBand.compute(
                        0, 5, 10, 2, 1, 3, horizon));
        assertEquals(
                Domain.closed(20, 20),
                FeasibleEntryBand.compute(
                        10, 10, 10, 10, 0, 0, horizon),
                "terminal horizon contact is owned");
        assertEquals(
                Domain.closed(15, 20),
                FeasibleEntryBand.compute(
                        10, 20, 20, 5, 1, 0, horizon),
                "clipping preserves the closed terminal horizon");
    }

    @Test
    void budgetEqualityAtLatestFeasibleEntryIsOwned() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1, 0, 0)
                .node(2, 5, 5)
                .node(3, 10, 10)
                .edge(1, 2, 1, score(
                        new PiecewiseConstFn.Interval(0, 8, 0),
                        new PiecewiseConstFn.Interval(8, 20, 7)))
                .edge(2, 3, 1)
                .build();
        Prepared prepared = prepare(graph, 1, 3, 2);

        PivotIndex result = PivotSelector.select(
                graph,
                prepared.corridor(),
                prepared.lowerBounds(),
                prepared.partition(),
                prepared.summaries(),
                prepared.scores(),
                Domain.closed(0, 10),
                4);

        assertEquals(List.of(0), result.scoreRelevantArcIds());
        assertEquals(List.of(0), result.selectedArcIds());
    }

    @Test
    void parallelArcsRemainDistinctAndSerializationIsDeterministic() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1, 0, 0)
                .node(2, 10, 10)
                .edge(1, 2, 1, positiveScore(4))
                .edge(1, 2, 1, positiveScore(4))
                .build();
        Prepared prepared = prepare(graph, 1, 2, 2);

        PivotIndex first = select(
                graph, prepared, Domain.closed(0, 10), 4);
        PivotIndex second = select(
                graph, prepared, Domain.closed(0, 10), 4);

        assertEquals(List.of(0, 1), first.scoreRelevantArcIds());
        assertEquals(List.of(0, 1), first.selectedArcIds());
        assertEquals(first.selectedArcIds(), second.selectedArcIds());
        assertEquals(first.version(), second.version());
    }

    @Test
    void exactTopLPrefersGreaterFeasibleTemporalCoverageAtEqualScore() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1).node(2)
                .edge(1, 2, 1, score(
                        new PiecewiseConstFn.Interval(0, 4, 7),
                        new PiecewiseConstFn.Interval(4, 20, 0)))
                .edge(1, 2, 1, score(
                        new PiecewiseConstFn.Interval(0, 8, 7),
                        new PiecewiseConstFn.Interval(8, 20, 0)))
                .build();
        Prepared prepared = prepare(graph, 1, 2, 5);

        PivotIndex result = select(
                graph, prepared, Domain.closed(0, 10), 8);

        assertEquals(List.of(1, 0), result.selectedArcIds());
        assertTrue(result.selected().get(0).temporalCoverage()
                > result.selected().get(1).temporalCoverage());
    }

    @Test
    void exactTopLBreaksCompleteFeatureTiesByStableArcId() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1).node(2)
                .edge(1, 2, 1, positiveScore(5))
                .edge(1, 2, 1, positiveScore(5))
                .edge(1, 2, 1, positiveScore(5))
                .build();
        Prepared prepared = prepare(graph, 1, 2, 5);

        PivotIndex result = select(
                graph, prepared, Domain.closed(0, 10), 8);

        assertEquals(List.of(0, 1, 2), result.selectedArcIds());
        assertEquals(3, result.selected().size(),
                "L is a maximum and all eligible edges are retained");
    }

    @Test
    void exactTopLUsesStablePartitionCellsAndClampsMaximum() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1, 0, 0)
                .node(2, 1, 1)
                .node(3, 10, 10)
                .node(4, 10, 10)
                .edge(1, 2, 1, positiveScore(10))
                .edge(2, 4, 1)
                .edge(1, 3, 1)
                .edge(3, 4, 1, positiveScore(1))
                .build();
        Prepared prepared = prepare(graph, 1, 4, 2);

        PivotIndex result = select(
                graph, prepared, Domain.closed(0, 10), 2);

        assertEquals(List.of(0, 3), result.selectedArcIds());
        assertEquals(
                "CELL-00000001",
                result.selected().get(1).cellId());
        assertFalse(result.version().isBlank());
    }

    @Test
    void exactTopLDoesNotInterpretSpatialCoordinatesAsTemporalTicks() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1, 41_138_295L, -74_260_000L)
                .node(2, 41_138_296L, -74_259_999L)
                .node(3, 42_100_000L, -73_100_000L)
                .edge(1, 2, 1, positiveScore(2))
                .edge(2, 3, 1, positiveScore(1))
                .build();
        Prepared prepared = prepare(graph, 1, 3, 2);

        PivotIndex result = select(
                graph, prepared, Domain.closed(0, 10), 2);

        assertEquals(List.of(0, 1), result.selectedArcIds());
    }

    private static PivotIndex select(
            TDGraph graph,
            Prepared prepared,
            Domain horizon,
            int limit) {
        return PivotSelector.select(
                graph,
                prepared.corridor(),
                prepared.lowerBounds(),
                prepared.partition(),
                prepared.summaries(),
                prepared.scores(),
                horizon,
                limit);
    }

    private static Prepared prepare(
            TDGraph graph,
            int source,
            int destination,
            double budget) {
        EdgeTemporalSummaryStore summaries =
                EdgeTemporalSummaryStore.build(graph);
        GraphPartitionMetadata partition =
                GraphPartitionMetadata.partition(graph, 2);
        ScoreSupportIndex scores =
                ScoreSupportIndex.build(summaries, partition);
        QueryLowerBounds lowerBounds =
                new QueryLowerBounds(graph, summaries);
        QueryCorridor corridor = QueryCorridor.build(
                graph,
                lowerBounds,
                partition,
                source,
                destination,
                budget);
        return new Prepared(
                summaries,
                partition,
                scores,
                lowerBounds,
                corridor);
    }

    private static PiecewiseConstFn positiveScore(int value) {
        return score(new PiecewiseConstFn.Interval(
                0, 1_440, value));
    }

    private static PiecewiseConstFn score(
            PiecewiseConstFn.Interval... intervals) {
        return new PiecewiseConstFn(List.of(intervals));
    }

    private record Prepared(
            EdgeTemporalSummaryStore summaries,
            GraphPartitionMetadata partition,
            ScoreSupportIndex scores,
            QueryLowerBounds lowerBounds,
            QueryCorridor corridor) {
    }
}
