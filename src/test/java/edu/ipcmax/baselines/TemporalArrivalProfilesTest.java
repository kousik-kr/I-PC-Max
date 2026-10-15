package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.TimeProfile;
import edu.ipcmax.core.validate.Path;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporalArrivalProfilesTest {
    @Test
    void compositionPreservesPreimagesOfDownstreamBreakpointsAndBuildsExactEnvelope() {
        PiecewiseLinearFn first = constant(0, 8, 2);
        PiecewiseLinearFn second = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 1),
                new PiecewiseLinearFn.Breakpoint(5, 1),
                new PiecewiseLinearFn.Breakpoint(10, 6)));
        TDGraph graph = graph(
                List.of(1, 2, 3),
                edge(0, 1, 2, first),
                edge(1, 2, 3, second),
                edge(2, 1, 3, constant(0, 8, 5)));

        TimeProfile composed = TemporalArrivalProfiles.pathArrivalProfile(
                graph, 1, 3, Domain.closed(0, 8), new Path(List.of(0, 1))).orElseThrow();
        TimeProfile direct = TemporalArrivalProfiles.pathArrivalProfile(
                graph, 1, 3, Domain.closed(0, 8), new Path(List.of(2))).orElseThrow();
        TimeProfile envelope = composed.pointwiseMinimum(direct, "test-envelope");

        assertTrue(composed.breakpoints().stream().anyMatch(point -> point.minute() == 3.0));
        assertEquals(3.0, composed.valueAt(0));
        assertEquals(6.0, composed.valueAt(3));
        assertEquals(16.0, composed.valueAt(8));
        assertEquals(3.0, TemporalPathReplay.arrivalTime(
                graph, 1, 3, 0, new Path(List.of(0, 1))));
        assertTrue(envelope.breakpoints().stream().anyMatch(point -> point.minute() == 5.0));
        assertEquals(10.0, envelope.valueAt(5));
        assertEquals(11.0, envelope.valueAt(6));
    }

    @Test
    void pathProfileReportsEmptySupportAndRejectsMalformedPaths() {
        TDGraph graph = graph(
                List.of(1, 2, 3),
                edge(0, 1, 2, constant(0, 10, 6)),
                edge(1, 2, 3, constant(0, 5, 1)));

        assertTrue(TemporalArrivalProfiles.pathArrivalProfile(
                graph, 1, 3, Domain.closed(0, 10), new Path(List.of(0, 1))).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> TemporalArrivalProfiles.pathArrivalProfile(
                        graph, 1, 3, Domain.closed(0, 10), new Path(List.of(1))));
        assertThrows(IllegalArgumentException.class,
                () -> TemporalArrivalProfiles.pathArrivalProfile(
                        graph, 1, 3, Domain.closed(0, 10), Path.empty()));
    }

    @Test
    void closureEvaluationSupportsFifoValidationWithoutMakingExcludedTimesTraversable() {
        PiecewiseLinearFn original = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 1),
                new PiecewiseLinearFn.Breakpoint(10, 1)));
        PiecewiseLinearFn restricted = original.restrict(Domain.halfOpen(0, 10));

        assertEquals(11.0, restricted.arrivalTimeAtClosure(10));
        assertThrows(IllegalArgumentException.class, () -> restricted.arrivalTimeAt(10));
        assertFalse(restricted.domain().contains(10));
    }

    private static PiecewiseLinearFn constant(double start, double end, double time) {
        return new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(start, time),
                new PiecewiseLinearFn.Breakpoint(end, time)));
    }

    private static Edge edge(int id, int source, int target, PiecewiseLinearFn travel) {
        return new Edge(id, source, target, 1, travel.minTravelTime(), travel,
                PiecewiseConstFn.constant(travel.domain(), 0));
    }

    private static TDGraph graph(List<Integer> nodeIds, Edge... edges) {
        List<Node> nodes = nodeIds.stream().map(id -> new Node(id, id, id)).toList();
        return new TDGraph(nodes, List.of(edges));
    }
}
