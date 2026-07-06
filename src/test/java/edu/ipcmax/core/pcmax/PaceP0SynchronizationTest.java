package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaceP0SynchronizationTest {
    @Test
    void temporalStitchEvaluatesAnchorAndRightCandidateAtShiftedTimes() {
        TDGraph graph = shiftedScoreGraph();
        CandidateProfile left = new CandidateProfile(
                Domain.closed(420, 420),
                TimeProfile.constant(Domain.closed(420, 420), 430),
                ScoreProfile.constant(Domain.closed(420, 420), 0),
                PathPointer.arc(0),
                0,
                -1,
                false);
        CandidateProfile right = new CandidateProfile(
                Domain.closed(435, 435),
                new TimeProfile(Domain.closed(435, 435), t -> graph.edges().get(2).travelTimeFunction().arrivalTimeAt(t), "right-arrival"),
                new ScoreProfile(Domain.closed(435, 435), t -> graph.edges().get(2).scoreFunction().valueAt(t), "right-score"),
                PathPointer.arc(2),
                0,
                -1,
                false);

        CandidateProfile stitched = TemporalStitch.stitch(
                graph, left, graph.edges().get(1), right, Domain.closed(420, 420), 30).orElseThrow();

        assertEquals(List.of(new Domain.Interval(420, 420)), stitched.domain().intervals());
        assertEquals(436.0, stitched.arrivalProfile().valueAt(420), 1e-9);
        assertEquals(16.0, stitched.travelTimeAt(420), 1e-9);
        assertEquals(18, stitched.scoreProfile().valueAt(420));
        assertEquals(List.of(0, 1, 2), stitched.pathPointer().arcIds());
    }

    @Test
    void rightDomainImageStartsAfterAnchorTraversal() {
        TDGraph graph = shiftedScoreGraph();
        CandidateProfile left = new CandidateProfile(
                Domain.closed(420, 420),
                TimeProfile.constant(Domain.closed(420, 420), 430),
                ScoreProfile.constant(Domain.closed(420, 420), 0),
                PathPointer.arc(0),
                0,
                -1,
                false);

        Domain image = PaceFrontierGenerator.imageDomainAfterAnchor(left, graph.edges().get(1), Domain.closed(420, 420));

        assertEquals(List.of(new Domain.Interval(435, 435)), image.intervals());
    }

    @Test
    void envelopeExtractionReturnsDepartureProfileSegments() {
        CandidateSet frontier = new CandidateSet();
        frontier.add(new CandidateProfile(
                Domain.closed(1, 3),
                TimeProfile.piecewise(Domain.closed(1, 3), List.of(
                        new TimeProfile.Breakpoint(1, 11),
                        new TimeProfile.Breakpoint(3, 13)), "a-arrival"),
                ScoreProfile.piecewise(Domain.closed(1, 3), List.of(
                        new ScoreProfile.Interval(1, 2, 8),
                        new ScoreProfile.Interval(2, 3, 3)), "a-score"),
                PathPointer.arc(0),
                0,
                -1,
                false));
        frontier.add(new CandidateProfile(
                Domain.closed(2, 3),
                TimeProfile.piecewise(Domain.closed(2, 3), List.of(
                        new TimeProfile.Breakpoint(2, 7),
                        new TimeProfile.Breakpoint(3, 8)), "b-arrival"),
                ScoreProfile.constant(Domain.closed(2, 3), 7),
                PathPointer.concat(PathPointer.arc(1), PathPointer.arc(2)),
                0,
                -1,
                false));

        EnvelopeProfile profile = EnvelopeExtractor.extract(frontier, Domain.closed(1, 3));

        assertEquals(2, profile.segments().size());
        assertEquals(new Domain.Interval(1, 2), profile.segments().get(0).interval());
        assertEquals(List.of(0), profile.segments().get(0).path().arcIds());
        assertEquals(new Domain.Interval(2, 3), profile.segments().get(1).interval());
        assertEquals(List.of(1, 2), profile.segments().get(1).path().arcIds());
    }

    @Test
    void exactIpcMaxDoesNotUseConfiguredPathLengthCap() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .node(4)
                .edge(1, 2, 10)
                .edge(2, 3, 10)
                .edge(3, 4, 10)
                .build();
        IPCMaxOptions options = new IPCMaxOptions(1, 0, 1, true, false, 1, false, 2, 1, 42);

        IPCMaxResult result = new IPCMax(graph, options)
                .run(new QuerySpec(1, 4, 100, 100, 40, 1));

        assertTrue(result.found());
        assertEquals(List.of(0, 1, 2), result.path().arcIds());
    }

    private static TDGraph shiftedScoreGraph() {
        PiecewiseConstFn anchorScore = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 430, 0),
                new PiecewiseConstFn.Interval(430, 431, 7),
                new PiecewiseConstFn.Interval(431, 1440, 0)));
        PiecewiseConstFn rightScore = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 435, 0),
                new PiecewiseConstFn.Interval(435, 436, 11),
                new PiecewiseConstFn.Interval(436, 1440, 0)));
        return new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .node(4)
                .edge(1, 2, 10)
                .edge(2, 3, 5, anchorScore)
                .edge(3, 4, 1, rightScore)
                .build();
    }
}
