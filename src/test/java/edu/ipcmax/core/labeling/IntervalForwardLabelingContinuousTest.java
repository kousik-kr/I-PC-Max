package edu.ipcmax.core.labeling;

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
import edu.ipcmax.core.graph.TinyGraphBuilder;

class IntervalForwardLabelingContinuousTest {
    @Test
    void computesExactContinuousLowerEnvelopeAtANonIntegerCrossing() {
        TDGraph graph = new TDGraph(
                List.of(new Node(1, 0, 0), new Node(2, 1, 1)),
                List.of(
                        edge(0, 1, 2, points(0, 10, 10, 20)),
                        edge(1, 1, 2, points(0, 15, 10, 5))));

        IntervalForwardLabeling.FastestTravelTimeProfile profile =
                new IntervalForwardLabeling(graph)
                        .fastestTravelTimeProfile(1, 2, Domain.closed(0, 10))
                        .orElseThrow();

        assertEquals(5.0, profile.minimumTravelTime(), 1e-9);
        assertEquals(12.5, profile.maximumTravelTime(), 1e-9);
        assertEquals(10.0, profile.travelTimeAt(0), 1e-9);
        assertEquals(12.5, profile.travelTimeAt(2.5), 1e-9);
        assertEquals(5.0, profile.travelTimeAt(10), 1e-9);
        assertTrue(profile.arrivalProfile().breakpoints().stream()
                .anyMatch(point -> Domain.sameTime(point.minute(), 2.5)));
    }

    @Test
    void propagatesLaterProfileImprovementsToDownstreamNodes() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .node(4)
                .edge(1, 2, 10)
                .edge(1, 3, 1)
                .edge(3, 2, 1)
                .edge(2, 4, 1)
                .build();

        var profile = new IntervalForwardLabeling(graph)
                .fastestTravelTimeProfile(1, 4, Domain.closed(0, 10))
                .orElseThrow();

        assertEquals(3.0, profile.minimumTravelTime(), 1e-9);
        assertEquals(3.0, profile.maximumTravelTime(), 1e-9);
    }

    @Test
    void rejectsNonFifoTravelFunctions() {
        TDGraph graph = new TDGraph(
                List.of(new Node(1, 0, 0), new Node(2, 1, 1)),
                List.of(edge(0, 1, 2, points(0, 20, 10, 0))));

        assertThrows(IllegalArgumentException.class, () -> new IntervalForwardLabeling(graph)
                .fastestTravelTimeProfile(1, 2, Domain.closed(0, 10)));
    }

    @Test
    void rejectsAnUnrepresentableSingletonPathWithoutInterpolatingIt() {
        TDGraph graph = new TDGraph(
                List.of(new Node(1, 0, 0), new Node(2, 1, 1), new Node(3, 2, 2)),
                List.of(
                        edge(0, 1, 2, points(0, 2, 10, 2)),
                        edge(1, 1, 3, List.of(new PiecewiseLinearFn.Breakpoint(5, 0))),
                        edge(2, 3, 2, List.of(new PiecewiseLinearFn.Breakpoint(5, 1)))));

        assertTrue(new IntervalForwardLabeling(graph)
                .fastestTravelTimeProfile(1, 2, Domain.closed(0, 10))
                .isEmpty());
    }

    private static Edge edge(
            int arcId, int source, int destination, List<PiecewiseLinearFn.Breakpoint> points) {
        PiecewiseLinearFn travel = new PiecewiseLinearFn(points);
        return new Edge(
                arcId,
                source,
                destination,
                Math.round(travel.minTravelTime()),
                points.get(0).value(),
                travel,
                PiecewiseConstFn.constant(travel.domain(), 0));
    }

    private static List<PiecewiseLinearFn.Breakpoint> points(
            double firstTime, double firstValue, double secondTime, double secondValue) {
        return List.of(
                new PiecewiseLinearFn.Breakpoint(firstTime, firstValue),
                new PiecewiseLinearFn.Breakpoint(secondTime, secondValue));
    }
}
