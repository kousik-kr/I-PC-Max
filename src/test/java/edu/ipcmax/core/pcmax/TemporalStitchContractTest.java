package edu.ipcmax.core.pcmax;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;

class TemporalStitchContractTest {
    @Test
    void exactBudgetRootIsRetainedAndIdentityConnectorsStitch() {
        PiecewiseConstFn positive = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 20, 4)));
        Edge edge = new Edge(
                0,
                1,
                2,
                1,
                1,
                new PiecewiseLinearFn(List.of(
                        new PiecewiseLinearFn.Breakpoint(0, 1),
                        new PiecewiseLinearFn.Breakpoint(10, 3))),
                positive);
        TDGraph graph = new TDGraph(
                List.of(new Node(1, 0, 0), new Node(2, 0, 0)),
                List.of(edge));
        Domain root = Domain.closed(0, 10);
        CandidateProfile leftIdentity = identity(root);
        CandidateProfile rightIdentity = identity(Domain.closed(1, 13));
        Anchor anchor = new Anchor(edge, root, root, 1);

        CandidateProfile stitched = TemporalStitch.stitch(
                graph,
                leftIdentity,
                anchor,
                rightIdentity,
                root,
                2).orElseThrow();

        assertEquals(Domain.closed(0, 5), stitched.domain());
        assertEquals(2, stitched.travelTimeAt(5), 1e-9);
        assertEquals(4, stitched.scoreProfile().valueAt(2));
        assertEquals(List.of(0), stitched.stablePathId());
    }

    @Test
    void repeatedVertexConcatenationIsRejected() {
        PiecewiseConstFn positive = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 1440, 1)));
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .node(4)
                .edge(1, 2, 1)
                .edge(2, 3, 1, positive)
                .edge(3, 1, 1)
                .edge(1, 4, 1)
                .build();
        Domain horizon = Domain.closed(0, 10);
        AnchorIndex anchors = AnchorIndex.create(graph, horizon);
        QueryLowerBounds lowerBounds = new QueryLowerBounds(graph, horizon);
        ConnectorProfiles connectors = new ConnectorProfiles(
                graph,
                anchors,
                lowerBounds,
                PaceOptions.exhaustive(0));
        CandidateProfile left = connectors.generate(1, 2, Domain.closed(0, 1), 10)
                .candidates().get(0);
        CandidateProfile right = connectors.generate(3, 4, Domain.closed(2, 3), 10)
                .candidates().get(0);

        assertTrue(TemporalStitch.stitch(
                graph,
                left,
                anchors.anchors().get(0),
                right,
                Domain.closed(0, 1),
                10).isEmpty());
    }

    private static CandidateProfile identity(Domain domain) {
        return new CandidateProfile(
                domain,
                TimeProfile.identity(domain),
                ScoreProfile.constant(domain, 0),
                PathPointer.empty(),
                0,
                -1,
                false);
    }
}
