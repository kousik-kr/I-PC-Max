package edu.ipcmax.core.pcmax;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;

class TemporalStitchContinuousTest {
    @Test
    void stitchKeepsContinuousDomainsAndShiftedTimes() {
        TDGraph graph = new TDGraph(
                List.of(new Node(1, 0, 0), new Node(2, 0, 0), new Node(3, 0, 0), new Node(4, 0, 0)),
                List.of(
                        new Edge(0, 1, 2, 1, 10.5,
                                new PiecewiseLinearFn(List.of(
                                        new PiecewiseLinearFn.Breakpoint(0, 10.5),
                                        new PiecewiseLinearFn.Breakpoint(10, 20.5))),
                                PiecewiseConstFn.zeroFullDay()),
                        new Edge(1, 2, 3, 1, 2.25,
                                new PiecewiseLinearFn(List.of(
                                        new PiecewiseLinearFn.Breakpoint(0, 2.25),
                                        new PiecewiseLinearFn.Breakpoint(100, 2.25))),
                                new PiecewiseConstFn(List.of(
                                        new PiecewiseConstFn.Interval(0, 10.5, 0),
                                        new PiecewiseConstFn.Interval(10.5, 20.5, 7),
                                        new PiecewiseConstFn.Interval(20.5, 100, 0)))),
                        new Edge(2, 3, 4, 1, 1.0,
                                new PiecewiseLinearFn(List.of(
                                        new PiecewiseLinearFn.Breakpoint(0, 1.0),
                                        new PiecewiseLinearFn.Breakpoint(100, 1.0))),
                                new PiecewiseConstFn(List.of(
                                        new PiecewiseConstFn.Interval(0, 12.75, 0),
                                        new PiecewiseConstFn.Interval(12.75, 22.75, 11),
                                        new PiecewiseConstFn.Interval(22.75, 100, 0))))));

        CandidateProfile left = new CandidateProfile(
                Domain.closed(0, 10),
                TimeProfile.piecewise(Domain.closed(0, 10), List.of(
                        new TimeProfile.Breakpoint(0, 10.5),
                        new TimeProfile.Breakpoint(10, 20.5)), "left-arrival"),
                ScoreProfile.constant(Domain.closed(0, 10), 0),
                PathPointer.arc(0),
                0,
                -1,
                false);

        CandidateProfile right = new CandidateProfile(
                Domain.closed(12.75, 22.75),
                TimeProfile.piecewise(Domain.closed(12.75, 22.75), List.of(
                        new TimeProfile.Breakpoint(12.75, 13.75),
                        new TimeProfile.Breakpoint(22.75, 23.75)), "right-arrival"),
                ScoreProfile.constant(Domain.closed(12.75, 22.75), 11),
                PathPointer.arc(2),
                0,
                -1,
                false);

        CandidateProfile stitched = TemporalStitch.stitch(graph, left, graph.edges().get(1), right, Domain.closed(0, 10), 20.0).orElseThrow();

        assertEquals(List.of(new Domain.Interval(0, 10)), stitched.domain().intervals());
        assertTrue(stitched.domain().contains(0.5));
        assertEquals(14.25, stitched.arrivalProfile().valueAt(0.5), 1e-9);
        assertEquals(13.75, stitched.travelTimeAt(0.5), 1e-9);
        assertEquals(18, stitched.scoreProfile().valueAt(0.5));
        assertEquals(List.of(0, 1, 2), stitched.pathPointer().arcIds());
    }
}