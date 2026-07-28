package edu.ipcmax.core.labeling;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.core.index.ExactDijkstraLowerBoundOracle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointForwardLabelingTest {
    @Test
    void staticGraphMatchesDijkstraExpectedPath() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .edge(1, 2, 10)
                .edge(2, 3, 10)
                .edge(1, 3, 50)
                .build();

        PointForwardLabeling.Result result = new PointForwardLabeling(graph).run(1, 100, 100);

        assertTrue(result.reached(3));
        assertEquals(120.0, result.arrivalAt(3));
        assertEquals(List.of(0, 1), result.pathTo(3).arcIds());
    }

    @Test
    void reverseLowerBoundPotentialPreservesExactResult() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .node(4)
                .edge(1, 2, 10)
                .edge(2, 4, 10)
                .edge(1, 3, 5)
                .edge(3, 4, 40)
                .build();
        PointForwardLabeling labeling = new PointForwardLabeling(graph);
        PointForwardLabeling.Result expected =
                labeling.runToTarget(1, 4, 100, 100);
        PointForwardLabeling.Result actual = labeling.runToTarget(
                1,
                4,
                100,
                100,
                new ExactDijkstraLowerBoundOracle(graph).distancesTo(4));

        assertEquals(expected.arrivalAt(4), actual.arrivalAt(4));
        assertEquals(
                expected.pathTo(4).arcIds(),
                actual.pathTo(4).arcIds());
    }
}
