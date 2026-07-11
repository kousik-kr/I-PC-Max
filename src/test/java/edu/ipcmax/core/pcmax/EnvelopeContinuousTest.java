package edu.ipcmax.core.pcmax;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;

class EnvelopeContinuousTest {
    @Test
    void envelopeBoundaryCanOccurAtNonIntegerBreakpoint() {
        Domain root = Domain.closed(0, 10);

        CandidateProfile left = new CandidateProfile(
                root,
                TimeProfile.piecewise(root, List.of(
                        new TimeProfile.Breakpoint(0, 10),
                        new TimeProfile.Breakpoint(10, 20)), "left-arrival"),
                ScoreProfile.piecewise(root, List.of(
                        new ScoreProfile.Interval(0, 3.75, 8),
                        new ScoreProfile.Interval(3.75, 10, 3)), "left-score"),
                PathPointer.arc(0),
                0,
                -1,
                false);

        CandidateProfile right = new CandidateProfile(
                root,
                TimeProfile.piecewise(root, List.of(
                        new TimeProfile.Breakpoint(0, 9.5),
                        new TimeProfile.Breakpoint(10, 19.5)), "right-arrival"),
                ScoreProfile.constant(root, 7),
                PathPointer.arc(1),
                0,
                -1,
                false);

        CandidateSet frontier = new CandidateSet();
        frontier.add(left);
        frontier.add(right);

        EnvelopeProfile profile = EnvelopeExtractor.extract(frontier, root);

        assertEquals(2, profile.segments().size());
        assertEquals(new Domain.Interval(0, 3.75, true, false), profile.segments().get(0).interval());
        assertEquals(List.of(0), profile.segments().get(0).path().arcIds());
        assertEquals(new Domain.Interval(3.75, 10), profile.segments().get(1).interval());
        assertEquals(List.of(1), profile.segments().get(1).path().arcIds());
    }

    @Test
    void equalScoreCandidatesSwitchAtNonIntegerTravelTie() {
        Domain root = Domain.closed(0, 10);

        CandidateProfile left = new CandidateProfile(
                root,
                TimeProfile.piecewise(root, List.of(
                        new TimeProfile.Breakpoint(0, 5),
                        new TimeProfile.Breakpoint(10, 15)), "left-arrival"),
                ScoreProfile.constant(root, 10),
                PathPointer.arc(0),
                0,
                -1,
                false);

        CandidateProfile right = new CandidateProfile(
                root,
                TimeProfile.piecewise(root, List.of(
                        new TimeProfile.Breakpoint(0, 10.5),
                        new TimeProfile.Breakpoint(10, 10.5)), "right-arrival"),
                ScoreProfile.constant(root, 10),
                PathPointer.arc(1),
                0,
                -1,
                false);

        CandidateSet frontier = new CandidateSet();
        frontier.add(left);
        frontier.add(right);

        EnvelopeProfile profile = EnvelopeExtractor.extract(frontier, root);

        assertEquals(2, profile.segments().size());
        assertEquals(new Domain.Interval(0, 5.5, true, true), profile.segments().get(0).interval());
        assertEquals(List.of(0), profile.segments().get(0).path().arcIds());
        assertEquals(new Domain.Interval(5.5, 10, false, true), profile.segments().get(1).interval());
        assertEquals(List.of(1), profile.segments().get(1).path().arcIds());
    }
}
