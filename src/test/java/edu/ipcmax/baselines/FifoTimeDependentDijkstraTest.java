package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.validate.Path;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FifoTimeDependentDijkstraTest {
    @Test
    void staticShortestPathMatchesExhaustiveSimplePathEnumeration() {
        TDGraph graph = graph(
                List.of(1, 2, 3, 4, 5),
                constantEdge(0, 1, 2, 3, 0, 100),
                constantEdge(1, 2, 4, 4, 0, 100),
                constantEdge(2, 1, 3, 2, 0, 100),
                constantEdge(3, 3, 4, 9, 0, 100),
                constantEdge(4, 1, 4, 20, 0, 100));

        FifoTimeDependentDijkstra.ForwardResult result =
                new FifoTimeDependentDijkstra(graph).earliestArrival(1, 10);
        ExpectedForward expected = exhaustiveForward(graph, 1, 4, 10);

        assertEquals(expected.arrival(), result.arrivalAt(4));
        assertEquals(expected.path(), result.pathTo(4));
        assertEquals(17.0, result.arrivalAt(4));
        assertEquals(List.of(0, 1), result.pathTo(4).arcIds());
        assertFalse(result.reached(5));
        assertEquals(Double.POSITIVE_INFINITY, result.arrivalAt(5));
        assertThrows(IllegalArgumentException.class, () -> result.pathTo(5));
    }

    @Test
    void timeDependentCompetingRoutesMatchExhaustiveEnumerationAtBreakpointsAndInteriors() {
        PiecewiseLinearFn changing = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 10),
                new PiecewiseLinearFn.Breakpoint(10, 0),
                new PiecewiseLinearFn.Breakpoint(20, 0)));
        TDGraph graph = graph(
                List.of(1, 2, 3, 4),
                edge(0, 1, 2, changing),
                constantEdge(1, 2, 4, 1, 0, 30),
                constantEdge(2, 1, 3, 2, 0, 30),
                constantEdge(3, 3, 4, 5, 0, 30));
        FifoTimeDependentDijkstra search = new FifoTimeDependentDijkstra(graph);

        for (double departure : List.of(0.0, 5.0, 10.0, 15.0, 20.0)) {
            FifoTimeDependentDijkstra.ForwardResult result = search.earliestArrival(1, departure);
            ExpectedForward expected = exhaustiveForward(graph, 1, 4, departure);
            assertEquals(expected.arrival(), result.arrivalAt(4), "departure=" + departure);
            assertEquals(expected.path(), result.pathTo(4), "departure=" + departure);
        }
        assertEquals(11.0, search.earliestArrival(1, 10).arrivalAt(4));
        assertEquals(List.of(0, 1), search.earliestArrival(1, 10).pathTo(4).arcIds());
    }

    @Test
    void fifoPlateauAndClosedBoundariesInvertExactly() {
        PiecewiseLinearFn plateau = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 10),
                new PiecewiseLinearFn.Breakpoint(10, 0),
                new PiecewiseLinearFn.Breakpoint(20, 0),
                new PiecewiseLinearFn.Breakpoint(30, 5)));
        TDGraph graph = graph(List.of(1, 2), edge(0, 1, 2, plateau));
        FifoTimeDependentDijkstra search = new FifoTimeDependentDijkstra(graph);

        assertEquals(10.0, search.earliestArrival(1, 0).arrivalAt(2));
        assertEquals(10.0, search.earliestArrival(1, 10).arrivalAt(2));
        FifoTimeDependentDijkstra.BackwardResult atPlateau = search.latestDeparture(2, 10);
        assertEquals(10.0, atPlateau.latestDepartureAt(1));
        assertEquals(List.of(0), atPlateau.pathFrom(1).arcIds());
        assertEquals(10.0, TemporalPathReplay.arrivalTime(graph, 1, 2,
                atPlateau.latestDepartureAt(1), atPlateau.pathFrom(1)));
        assertEquals(Double.NEGATIVE_INFINITY, search.latestDeparture(2, 9).latestDepartureAt(1));

        FifoTimeDependentDijkstra.BackwardResult atDomainEnd = search.latestDeparture(2, 35);
        assertEquals(30.0, atDomainEnd.latestDepartureAt(1));
        assertEquals(35.0, TemporalPathReplay.arrivalTime(graph, 1, 2, 30, atDomainEnd.pathFrom(1)));
    }

    @Test
    void equalArrivalTiesChooseFewestEdgesThenLexicographicCompleteArcSequence() {
        TDGraph graph = graph(
                List.of(1, 2, 3, 4),
                constantEdge(0, 1, 2, 0, 0, 50),
                constantEdge(1, 2, 4, 0, 0, 50),
                constantEdge(2, 1, 3, 0, 0, 50),
                constantEdge(3, 3, 4, 0, 0, 50),
                constantEdge(4, 1, 4, 0, 0, 50));
        FifoTimeDependentDijkstra search = new FifoTimeDependentDijkstra(graph);

        FifoTimeDependentDijkstra.ForwardResult forward = search.earliestArrival(1, 7);
        FifoTimeDependentDijkstra.BackwardResult backward = search.latestDeparture(4, 7);

        assertEquals(7.0, forward.arrivalAt(4));
        assertEquals(List.of(4), forward.pathTo(4).arcIds());
        assertEquals(7.0, backward.latestDepartureAt(1));
        assertEquals(List.of(4), backward.pathFrom(1).arcIds());
    }

    @Test
    void undefinedEntryTimesAreUnavailableAndReplayRejectsThem() {
        PiecewiseLinearFn supported = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(5, 1),
                new PiecewiseLinearFn.Breakpoint(10, 1)));
        TDGraph graph = graph(List.of(1, 2), edge(0, 1, 2, supported));
        FifoTimeDependentDijkstra search = new FifoTimeDependentDijkstra(graph);

        assertFalse(search.earliestArrival(1, 4).reached(2));
        assertEquals(6.0, search.earliestArrival(1, 5).arrivalAt(2));
        assertThrows(IllegalArgumentException.class,
                () -> TemporalPathReplay.arrivalTime(graph, 1, 2, 4, new Path(List.of(0))));
        assertThrows(IllegalArgumentException.class,
                () -> TemporalPathReplay.arrivalTime(graph, 1, 2, 5, new Path(List.of(0, 0))));
    }

    @Test
    void forwardAndReverseSearchesMatchExhaustivePathEnumeration() {
        PiecewiseLinearFn plateau = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 4),
                new PiecewiseLinearFn.Breakpoint(10, 0),
                new PiecewiseLinearFn.Breakpoint(20, 0),
                new PiecewiseLinearFn.Breakpoint(40, 2)));
        TDGraph graph = graph(
                List.of(1, 2, 3, 4, 5),
                edge(0, 1, 2, plateau),
                constantEdge(1, 2, 4, 3, 0, 50),
                constantEdge(2, 1, 3, 6, 0, 50),
                constantEdge(3, 3, 4, 2, 0, 50),
                constantEdge(4, 2, 3, 1, 0, 50),
                constantEdge(5, 4, 5, 0, 0, 50));
        FifoTimeDependentDijkstra search = new FifoTimeDependentDijkstra(graph);

        for (double departure : List.of(0.0, 5.0, 10.0, 20.0, 40.0)) {
            var result = search.earliestArrival(1, departure);
            ExpectedForward expected = exhaustiveForward(graph, 1, 5, departure);
            assertEquals(expected.arrival(), result.arrivalAt(5), "departure=" + departure);
            assertEquals(expected.path(), result.pathTo(5), "departure=" + departure);
        }
        for (double deadline : List.of(0.0, 10.0, 20.0, 30.0, 45.0, 50.0)) {
            var result = search.latestDeparture(5, deadline);
            ExpectedBackward expected = exhaustiveBackward(graph, 1, 5, deadline);
            assertEquals(expected.departure(), result.latestDepartureAt(1), "deadline=" + deadline);
            if (expected.path() != null) {
                assertEquals(expected.path(), result.pathFrom(1), "deadline=" + deadline);
                double arrival = TemporalPathReplay.arrivalTime(
                        graph, 1, 5, result.latestDepartureAt(1), result.pathFrom(1));
                assertTrue(arrival <= deadline, "deadline=" + deadline);
            } else {
                assertFalse(result.reached(1), "deadline=" + deadline);
            }
        }
    }

    @Test
    void reverseSearchRejectsOpenDomainsInsteadOfApproximatingTheirSupremum() {
        PiecewiseLinearFn open = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 1),
                new PiecewiseLinearFn.Breakpoint(10, 1)))
                .restrict(Domain.halfOpen(0, 10));
        TDGraph graph = graph(List.of(1, 2), edge(0, 1, 2, open));

        assertThrows(IllegalArgumentException.class,
                () -> new FifoTimeDependentDijkstra(graph).latestDeparture(2, 11));
    }

    @Test
    void fifoValidationChecksArrivalOrderAcrossDisconnectedDomainComponents() {
        PiecewiseLinearFn base = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 10),
                new PiecewiseLinearFn.Breakpoint(5, 10),
                new PiecewiseLinearFn.Breakpoint(10, 0)));
        PiecewiseLinearFn gapped = base.restrict(Domain.of(
                new Domain.Interval(0, 5),
                new Domain.Interval(10, 10)));
        TDGraph graph = graph(List.of(1, 2), edge(0, 1, 2, gapped));

        assertTrue(gapped.isFifo(), "each connected domain component is locally FIFO");
        assertThrows(IllegalArgumentException.class,
                () -> new FifoTimeDependentDijkstra(graph));
    }

    private static ExpectedForward exhaustiveForward(
            TDGraph graph,
            int source,
            int destination,
            double departure) {
        ExpectedForward best = null;
        for (Path path : enumerateSimplePaths(graph, source, destination)) {
            double arrival;
            try {
                arrival = TemporalPathReplay.arrivalTime(graph, source, destination, departure, path);
            } catch (IllegalArgumentException undefinedOrInvalidPath) {
                continue;
            }
            ExpectedForward candidate = new ExpectedForward(arrival, path);
            if (best == null || compareForward(candidate, best) < 0) {
                best = candidate;
            }
        }
        return best == null
                ? new ExpectedForward(Double.POSITIVE_INFINITY, null)
                : best;
    }

    private static ExpectedBackward exhaustiveBackward(
            TDGraph graph,
            int source,
            int destination,
            double deadline) {
        ExpectedBackward best = null;
        for (Path path : enumerateSimplePaths(graph, source, destination)) {
            double departure = deadline;
            boolean feasible = true;
            for (int i = path.arcIds().size() - 1; i >= 0; i--) {
                Edge edge = graph.edges().get(path.arcIds().get(i));
                departure = edge.travelTimeFunction().latestDepartureForArrival(departure);
                if (departure == Double.NEGATIVE_INFINITY
                        || !edge.travelTimeFunction().domain().contains(departure)) {
                    feasible = false;
                    break;
                }
            }
            if (!feasible) {
                continue;
            }
            double arrival = TemporalPathReplay.arrivalTime(
                    graph, source, destination, departure, path);
            if (arrival > deadline) {
                continue;
            }
            ExpectedBackward candidate = new ExpectedBackward(departure, path);
            if (best == null || compareBackward(candidate, best) < 0) {
                best = candidate;
            }
        }
        return best == null
                ? new ExpectedBackward(Double.NEGATIVE_INFINITY, null)
                : best;
    }

    private static int compareForward(ExpectedForward left, ExpectedForward right) {
        int time = Double.compare(left.arrival(), right.arrival());
        return time != 0 ? time : comparePaths(left.path(), right.path());
    }

    private static int compareBackward(ExpectedBackward left, ExpectedBackward right) {
        int time = Double.compare(right.departure(), left.departure());
        return time != 0 ? time : comparePaths(left.path(), right.path());
    }

    private static int comparePaths(Path left, Path right) {
        int hops = Integer.compare(left.arcIds().size(), right.arcIds().size());
        if (hops != 0) {
            return hops;
        }
        for (int i = 0; i < left.arcIds().size(); i++) {
            int arc = Integer.compare(left.arcIds().get(i), right.arcIds().get(i));
            if (arc != 0) {
                return arc;
            }
        }
        return 0;
    }

    private static List<Path> enumerateSimplePaths(TDGraph graph, int source, int destination) {
        graph.node(source);
        graph.node(destination);
        List<Path> paths = new ArrayList<>();
        if (source == destination) {
            paths.add(Path.empty());
            return paths;
        }
        enumerate(graph, source, destination, new ArrayList<>(), new ArrayList<>(), new HashSet<>(), paths);
        return paths;
    }

    private static void enumerate(
            TDGraph graph,
            int current,
            int destination,
            List<Integer> arcs,
            List<Integer> nodes,
            Set<Integer> visited,
            List<Path> paths) {
        if (current == destination) {
            paths.add(new Path(arcs));
            return;
        }
        if (!visited.add(current)) {
            return;
        }
        nodes.add(current);
        for (Edge edge : graph.outgoingEdges(current)) {
            if (visited.contains(edge.target())) {
                continue;
            }
            arcs.add(edge.arcId());
            enumerate(graph, edge.target(), destination, arcs, nodes, visited, paths);
            arcs.remove(arcs.size() - 1);
        }
        nodes.remove(nodes.size() - 1);
        visited.remove(current);
    }

    private static TDGraph graph(List<Integer> nodeIds, Edge... edges) {
        List<Node> nodes = nodeIds.stream().map(id -> new Node(id, id, id)).toList();
        return new TDGraph(nodes, List.of(edges));
    }

    private static Edge constantEdge(
            int id, int source, int target, double travelTime, double start, double end) {
        return edge(id, source, target, new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(start, travelTime),
                new PiecewiseLinearFn.Breakpoint(end, travelTime))));
    }

    private static Edge edge(int id, int source, int target, PiecewiseLinearFn travel) {
        return new Edge(
                id,
                source,
                target,
                1,
                travel.minTravelTime(),
                travel,
                PiecewiseConstFn.constant(travel.domain(), 0));
    }

    private record ExpectedForward(double arrival, Path path) {
    }

    private record ExpectedBackward(double departure, Path path) {
    }
}
