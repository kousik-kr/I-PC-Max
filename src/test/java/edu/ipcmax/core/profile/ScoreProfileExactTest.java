package edu.ipcmax.core.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;

class ScoreProfileExactTest {
    @Test
    void edgeScoreCompositionChangesAtExactPulledBackBreakpoint() {
        TimeProfile arrival = TimeProfile.piecewise(
                Domain.closed(0, 5),
                List.of(
                        new TimeProfile.Breakpoint(0, 0),
                        new TimeProfile.Breakpoint(5, 10)),
                "arrival");
        PiecewiseConstFn edgeScore = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 5, 0),
                new PiecewiseConstFn.Interval(5, 10, 10)));

        ScoreProfile composed = ScoreProfile.compose(
                arrival, edgeScore, arrival.domain(), "score-compose");

        assertEquals(0, composed.valueAt(2));
        assertEquals(10, composed.valueAt(2.5));
        assertEquals(10, composed.valueAt(5));
        assertEquals(List.of(
                new ScoreProfile.Interval(0, 2.5, 0),
                new ScoreProfile.Interval(2.5, 5, 10)), composed.intervals());
    }

    @Test
    void restrictionRetainsDifferentValueAtIncludedFinalEndpoint() {
        ScoreProfile score = ScoreProfile.piecewise(
                Domain.closed(0, 10),
                List.of(
                        new ScoreProfile.Interval(0, 5, 1),
                        new ScoreProfile.Interval(5, 10, 2)),
                "score");

        ScoreProfile restricted = score.restrict(Domain.closed(0, 5));

        assertEquals(1, restricted.valueAt(4.999));
        assertEquals(2, restricted.valueAt(5));
        assertEquals(List.of(
                new ScoreProfile.Interval(0, 5, 1),
                new ScoreProfile.Interval(5, 5, 2)), restricted.intervals());
    }

    @Test
    void positiveDomainExcludesBoundaryOwnedByFollowingZeroPiece() {
        PiecewiseConstFn score = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 5, 7),
                new PiecewiseConstFn.Interval(5, 10, 0)));

        Domain positive = score.positiveDomain();

        assertEquals(List.of(new Domain.Interval(0, 5, true, false)), positive.intervals());
        assertTrue(positive.contains(0));
        assertTrue(positive.contains(4.999));
        assertFalse(positive.contains(5));
        assertEquals(0, score.valueAt(5));
    }

    @Test
    void additionUsesEveryContinuousScoreBreakpoint() {
        Domain domain = Domain.closed(0, 10);
        ScoreProfile left = ScoreProfile.piecewise(domain, List.of(
                new ScoreProfile.Interval(0, 3, 1),
                new ScoreProfile.Interval(3, 10, 4)), "left");
        ScoreProfile right = ScoreProfile.piecewise(domain, List.of(
                new ScoreProfile.Interval(0, 7, 10),
                new ScoreProfile.Interval(7, 10, 20)), "right");

        ScoreProfile sum = left.add(right, domain, "sum");

        assertEquals(11, sum.valueAt(2.5));
        assertEquals(14, sum.valueAt(3));
        assertEquals(24, sum.valueAt(7));
        assertEquals(List.of(
                new ScoreProfile.Interval(0, 3, 11),
                new ScoreProfile.Interval(3, 7, 14),
                new ScoreProfile.Interval(7, 10, 24)), sum.intervals());
    }
}
