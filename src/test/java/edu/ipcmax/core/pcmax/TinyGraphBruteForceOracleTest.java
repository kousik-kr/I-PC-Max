package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TinyGraphBruteForceOracleTest {
    @Test
    void oracleMaximizesScoreThenTravelTime() {
        PiecewiseConstFn highScore = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 420, 0),
                new PiecewiseConstFn.Interval(420, 600, 10),
                new PiecewiseConstFn.Interval(600, 1440, 0)));
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .node(4)
                .edge(1, 2, 10, highScore)
                .edge(2, 4, 10)
                .edge(1, 3, 5)
                .edge(3, 4, 5)
                .build();

        IPCMaxResult result = new TinyGraphBruteForceOracle(graph, 4)
                .solve(new QuerySpec(1, 4, 420, 420, 40, 1));

        assertTrue(result.found());
        assertEquals(10, result.score());
        assertEquals(List.of(0, 1), result.path().arcIds());
    }
}
