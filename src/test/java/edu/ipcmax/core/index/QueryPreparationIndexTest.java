package edu.ipcmax.core.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class QueryPreparationIndexTest {
    @Test
    void buildsStableTemporalSummariesPartitionsAndTimeRangeRankings() {
        TDGraph graph = graph(List.of(
                score(0, 100, 4),
                score(0, 50, 9),
                score(200, 300, 15),
                zero()));

        EdgeTemporalSummaryStore summaries =
                EdgeTemporalSummaryStore.build(graph);
        GraphPartitionMetadata partition =
                GraphPartitionMetadata.partition(graph, 2);
        ScoreSupportIndex index =
                ScoreSupportIndex.build(summaries, partition);

        assertEquals(4, summaries.size());
        assertEquals(2.0, summaries.summary(0).lowerBoundTravelTime());
        assertEquals(9, summaries.summary(1).maximumScore());
        assertEquals(List.of("CELL-00000000", "CELL-00000001"),
                partition.cells().stream()
                        .map(GraphPartitionMetadata.Cell::cellId)
                        .toList());
        assertEquals(List.of(1, 2),
                partition.cells().get(0).vertexIds());
        assertEquals(List.of(1, 2),
                partition.cells().get(0).boundaryVertexIds());
        assertEquals(List.of(3, 4),
                partition.cells().get(1).boundaryVertexIds());
        assertEquals(List.of(0, 1),
                index.scoreBearingArcIds("CELL-00000000"));
        assertEquals(List.of(
                        new ScoreSupportIndex.RankedScoreEdge(
                                1,
                                9,
                                List.of(new EdgeTemporalSummaryStore
                                        .PositiveScoreInterval(0, 50, 9))),
                        new ScoreSupportIndex.RankedScoreEdge(
                                0,
                                4,
                                List.of(new EdgeTemporalSummaryStore
                                        .PositiveScoreInterval(0, 100, 4)))),
                index.topK("CELL-00000000", Domain.closed(25, 40), 10));
        assertTrue(index.topK(
                "CELL-00000000", Domain.closed(250, 260), 10).isEmpty());
    }

    @Test
    void verifiesNestedDensityVariantsAndRejectsARegression() {
        GraphPartitionMetadata partition =
                GraphPartitionMetadata.partition(graph(List.of(
                        zero(), zero(), zero(), zero())), 2);
        ScoreSupportIndex five = index(
                partition, List.of(score(0, 100, 1), zero(), zero(), zero()));
        ScoreSupportIndex ten = index(
                partition, List.of(score(0, 100, 1),
                        score(0, 100, 2), zero(), zero()));
        ScoreSupportIndex twenty = index(
                partition, List.of(score(0, 100, 1),
                        score(0, 100, 2), score(0, 100, 3), zero()));
        ScoreSupportIndex nonNested = index(
                partition, List.of(zero(),
                        score(0, 100, 2), score(0, 100, 3),
                        score(0, 100, 4)));

        ScoreSupportIndex.requireNested(List.of(five, ten, twenty));
        assertThrows(IllegalArgumentException.class,
                () -> ScoreSupportIndex.requireNested(
                        List.of(five, nonNested)));
    }

    @Test
    void exactLowerBoundOracleRetainsDijkstraFixtureFallback() {
        TDGraph graph = graph(List.of(zero(), zero(), zero(), zero()));
        LowerBoundOracle oracle =
                new ExactDijkstraLowerBoundOracle(graph);

        LowerBoundOracle.Labels labels = oracle.distancesFrom(1);

        assertTrue(labels.reached(4));
        assertEquals(6.0, labels.distance(4));
        assertEquals(List.of(0, 1, 2),
                labels.witnessPath(4).arcIds());
        assertEquals(2.0, oracle.edgeWeight(0));
    }

    @Test
    void rejectsNonPositiveLowerBoundEdges() {
        PiecewiseLinearFn travel = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 0),
                new PiecewiseLinearFn.Breakpoint(10080, 0)));
        TDGraph graph = new TDGraph(
                List.of(new Node(1, 0, 0), new Node(2, 1, 1)),
                List.of(new Edge(
                        0, 1, 2, 1, 0, travel,
                        PiecewiseConstFn.constant(
                                Domain.closed(0, 10080), 0))));

        assertThrows(IllegalArgumentException.class,
                () -> EdgeTemporalSummaryStore.build(graph));
        assertThrows(IllegalArgumentException.class,
                () -> new ExactDijkstraLowerBoundOracle(graph));
    }

    private static ScoreSupportIndex index(
            GraphPartitionMetadata partition,
            List<PiecewiseConstFn> scores) {
        return ScoreSupportIndex.build(
                EdgeTemporalSummaryStore.build(graph(scores)),
                partition);
    }

    private static TDGraph graph(List<PiecewiseConstFn> scores) {
        if (scores.size() != 4) {
            throw new IllegalArgumentException("four scores are required");
        }
        PiecewiseLinearFn travel = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 2),
                new PiecewiseLinearFn.Breakpoint(10080, 2)));
        List<Node> nodes = List.of(
                new Node(4, 4, 4),
                new Node(2, 2, 2),
                new Node(1, 1, 1),
                new Node(3, 3, 3));
        List<Edge> edges = List.of(
                new Edge(0, 1, 2, 1, 2, travel, scores.get(0)),
                new Edge(1, 2, 3, 1, 2, travel, scores.get(1)),
                new Edge(2, 3, 4, 1, 2, travel, scores.get(2)),
                new Edge(3, 4, 1, 1, 2, travel, scores.get(3)));
        return new TDGraph(nodes, edges);
    }

    private static PiecewiseConstFn score(int start, int end, int value) {
        java.util.ArrayList<PiecewiseConstFn.Interval> intervals =
                new java.util.ArrayList<>();
        if (start > 0) {
            intervals.add(new PiecewiseConstFn.Interval(0, start, 0));
        }
        intervals.add(new PiecewiseConstFn.Interval(start, end, value));
        if (end < 10080) {
            intervals.add(new PiecewiseConstFn.Interval(end, 10080, 0));
        }
        return new PiecewiseConstFn(intervals);
    }

    private static PiecewiseConstFn zero() {
        return PiecewiseConstFn.constant(Domain.closed(0, 10080), 0);
    }
}
