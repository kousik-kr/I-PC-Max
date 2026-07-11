package edu.ipcmax.experiments;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.PACE;
import edu.ipcmax.core.pcmax.PaceExecutionPolicy;
import edu.ipcmax.core.pcmax.PaceFeatures;
import edu.ipcmax.core.pcmax.PaceOptions;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.experiments.framework.ProfileSupport;

class ExperimentDeterminismTest {
    private static final QuerySpec QUERY = new QuerySpec(1, 4, 420, 430, 60, 1);

    @Test
    void repeatedRunsAndThreadCountsHaveIdenticalChecksums() {
        TDGraph graph = ExperimentDatasets.demo();
        String expected = checksum(graph, 1);
        assertEquals(expected, checksum(graph, 1));
        assertEquals(expected, checksum(graph, 2));
        assertEquals(expected, checksum(graph, 4));
    }

    @Test
    void collectionInsertionOrderDoesNotAffectTheProfile() {
        TDGraph original = ExperimentDatasets.demo();
        var reversedEdges = new ArrayList<>(original.edges());
        Collections.reverse(reversedEdges);
        List<Node> reversedNodes = new ArrayList<>(List.of(
                original.node(1), original.node(2), original.node(3), original.node(4)));
        Collections.reverse(reversedNodes);
        TDGraph reordered = new TDGraph(reversedNodes, reversedEdges);
        assertEquals(checksum(original, 1), checksum(reordered, 1));
    }

    private static String checksum(TDGraph graph, int threads) {
        PaceOptions options = new PaceOptions(PaceExecutionPolicy.PACE_B, 2, 8, 8,
                threads, true, PaceFeatures.defaults(), 100_000);
        return ProfileSupport.checksum(new PACE(graph, options).run(QUERY));
    }
}
