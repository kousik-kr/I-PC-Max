package edu.ipcmax.core.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LowerBoundGraphTest {
    @Test
    void queryTimedConstructionHonorsCancellation() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .edge(1, 2, 1)
                .build();

        assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> new LowerBoundGraph(graph, () -> true));
    }

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
        assertEquals(2, toTarget.edgeCount(1));
        assertEquals(1, toTarget.edgeCount(2));
        assertEquals(java.util.List.of(0, 1), toTarget.pathFrom(1).arcIds());
    }

    @Test
    void forwardWitnessPrefersFewerEdgesThenStablePredecessorArc() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .node(4)
                .node(5)
                .node(6)
                .edge(1, 3, 1)
                .edge(3, 4, 1)
                .edge(1, 2, 1)
                .edge(2, 4, 1)
                .edge(1, 5, 0.5)
                .edge(5, 6, 0.5)
                .edge(6, 4, 1)
                .build();

        LowerBoundGraph.Distances fromSource = new LowerBoundGraph(graph).distancesFromSource(1);

        assertEquals(2.0, fromSource.distance(4), 1e-9);
        assertEquals(2, fromSource.edgeCount(4));
        assertEquals(java.util.List.of(0, 1), fromSource.pathTo(4).arcIds());
        assertEquals(0, fromSource.edgeCount(1));
        assertEquals(java.util.List.of(), fromSource.pathTo(1).arcIds());
        assertEquals(-1, fromSource.edgeCount(99));
        assertThrows(IllegalArgumentException.class, () -> fromSource.pathTo(99));
        assertThrows(IllegalStateException.class, () -> fromSource.pathFrom(4));
    }
}
