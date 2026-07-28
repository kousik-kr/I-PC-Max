package edu.ipcmax.core.pcmax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.core.index.GraphPartitionMetadata;

class QueryCorridorPropertyTest {
    @Test
    void everyArcOnEveryLowerBoundFeasibleSimplePathSurvives() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1).node(2).node(3).node(4).node(5)
                .edge(1, 2, 2)
                .edge(1, 2, 3)
                .edge(2, 3, 2)
                .edge(3, 4, 2)
                .edge(2, 4, 6)
                .edge(1, 3, 8)
                .edge(2, 2, 0.5)
                .edge(5, 4, 1)
                .build();
        double budget = 7;
        QueryLowerBounds lowerBounds =
                new QueryLowerBounds(graph, Domain.closed(0, 20));
        GraphPartitionMetadata partition =
                GraphPartitionMetadata.partition(graph, 2);

        QueryCorridor first = QueryCorridor.build(
                graph, lowerBounds, partition, 1, 4, budget);
        QueryCorridor repeated = QueryCorridor.build(
                graph, lowerBounds, partition, 1, 4, budget);

        List<List<Integer>> feasible = enumerateFeasible(
                graph, 1, 4, budget);
        assertEquals(List.of(List.of(0, 2, 3), List.of(1, 2, 3)),
                feasible);
        for (List<Integer> path : feasible) {
            for (int arcId : path) {
                assertTrue(first.containsArc(arcId),
                        () -> "feasible path arc missing: " + path);
            }
        }
        assertEquals(first.directedArcIds(), repeated.directedArcIds());
        assertEquals(first.checksum(), repeated.checksum());
        assertEquals(64, first.checksum().length());
    }

    private static List<List<Integer>> enumerateFeasible(
            TDGraph graph,
            int source,
            int destination,
            double budget) {
        List<List<Integer>> paths = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        visited.add(source);
        enumerate(
                graph,
                source,
                destination,
                budget,
                0,
                visited,
                new ArrayList<>(),
                paths);
        return paths;
    }

    private static void enumerate(
            TDGraph graph,
            int current,
            int destination,
            double budget,
            double weight,
            Set<Integer> visited,
            List<Integer> path,
            List<List<Integer>> output) {
        if (current == destination) {
            output.add(List.copyOf(path));
            return;
        }
        for (Edge edge : graph.outgoingEdges(current)) {
            double nextWeight = weight + edge.baseTravelTime();
            if (nextWeight > budget || visited.contains(edge.target())) {
                continue;
            }
            visited.add(edge.target());
            path.add(edge.arcId());
            enumerate(
                    graph,
                    edge.target(),
                    destination,
                    budget,
                    nextWeight,
                    visited,
                    path,
                    output);
            path.remove(path.size() - 1);
            visited.remove(edge.target());
        }
    }
}
