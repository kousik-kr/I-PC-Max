package edu.ipcmax.core.labeling;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
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
}
