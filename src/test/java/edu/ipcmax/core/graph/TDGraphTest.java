package edu.ipcmax.core.graph;

import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.function.TimeRange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TDGraphTest {
    @Test
    void adjacencyPreservesParallelArcsByArcId() {
        PiecewiseLinearFn travel = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 10),
                new PiecewiseLinearFn.Breakpoint(1440, 10)));
        PiecewiseConstFn score = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 420, 0),
                new PiecewiseConstFn.Interval(420, 600, 4),
                new PiecewiseConstFn.Interval(600, 1440, 0)));

        TDGraph graph = new TDGraph(
                List.of(new Node(1, 0, 0), new Node(2, 1, 1), new Node(3, 2, 2)),
                List.of(
                        new Edge(0, 1, 2, 100, 10, travel, score),
                        new Edge(1, 2, 3, 200, 20, travel, PiecewiseConstFn.zeroFullDay()),
                        new Edge(2, 1, 2, 120, 12, travel, score)));

        assertEquals(List.of(0, 2), graph.outgoingEdges(1).stream().map(Edge::arcId).toList());
        assertEquals(List.of(0, 2), graph.incomingEdges(2).stream().map(Edge::arcId).toList());
        assertEquals(2, graph.edgesWithPositiveScore(new TimeRange(420, 600)).size());
    }
}
