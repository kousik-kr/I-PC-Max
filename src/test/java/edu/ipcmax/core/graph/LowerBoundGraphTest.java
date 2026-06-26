package edu.ipcmax.core.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LowerBoundGraphTest {
    @Test
    void distancesToTargetUseMinimumEdgeWeights() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .edge(1, 2, 10)
                .edge(2, 3, 20)
                .edge(1, 3, 50)
                .build();

        LowerBoundGraph lowerBound = new LowerBoundGraph(graph);
        LowerBoundGraph.Distances toTarget = lowerBound.distancesToTarget(3);

        assertTrue(toTarget.reached(1));
        assertEquals(30.0, toTarget.distance(1), 1e-9);
        assertEquals(20.0, toTarget.distance(2), 1e-9);
        assertEquals(0.0, toTarget.distance(3), 1e-9);
    }
}
