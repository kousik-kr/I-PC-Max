package edu.ipcmax.core.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.Domain;

class TimeProfileExactTest {
    @Test
    void compositionPullsOuterBreakpointsBackToExactRootTimes() {
        TimeProfile inner = TimeProfile.piecewise(
                Domain.closed(0, 5),
                List.of(
                        new TimeProfile.Breakpoint(0, 0),
                        new TimeProfile.Breakpoint(5, 10)),
                "inner");
        TimeProfile outer = TimeProfile.piecewise(
                Domain.closed(0, 10),
                List.of(
                        new TimeProfile.Breakpoint(0, 0),
                        new TimeProfile.Breakpoint(5, 10),
                        new TimeProfile.Breakpoint(10, 10)),
                "outer");

        TimeProfile composed = inner.compose(outer, "composed");

        assertEquals(List.of(
                new TimeProfile.Breakpoint(0, 0),
                new TimeProfile.Breakpoint(2.5, 10),
                new TimeProfile.Breakpoint(5, 10)), composed.breakpoints());
        for (double time = 0; time <= 5; time += 0.125) {
            assertEquals(outer.valueAt(inner.valueAt(time)), composed.valueAt(time), 1e-9);
        }
    }

    @Test
    void everyCompositionMergesOldAndNewCutSetsWithoutAccumulation() {
        Domain domain = Domain.closed(0, 10);
        TimeProfile profile = TimeProfile.piecewise(
                domain,
                List.of(
                        new TimeProfile.Breakpoint(0, 0),
                        new TimeProfile.Breakpoint(2, 2),
                        new TimeProfile.Breakpoint(4, 4),
                        new TimeProfile.Breakpoint(6, 6),
                        new TimeProfile.Breakpoint(8, 8),
                        new TimeProfile.Breakpoint(10, 10)),
                "initial");
        TimeProfile relaxation = TimeProfile.piecewise(
                domain,
                List.of(
                        new TimeProfile.Breakpoint(0, 0),
                        new TimeProfile.Breakpoint(2.5, 2.5),
                        new TimeProfile.Breakpoint(5, 5),
                        new TimeProfile.Breakpoint(7.5, 7.5),
                        new TimeProfile.Breakpoint(10, 10)),
                "relaxation");

        for (int step = 0; step < 100; step++) {
            profile = profile.compose(
                    relaxation,
                    "relaxation-" + step);
        }

        assertEquals(9, profile.breakpoints().size());
        assertEquals(0, profile.valueAt(0), 1e-9);
        assertEquals(10, profile.valueAt(10), 1e-9);
    }

    @Test
    void compactedPiecewiseProfilePreservesSlopeChangesOnly() {
        TimeProfile compacted = TimeProfile.piecewiseCompacted(
                Domain.closed(0, 10),
                List.of(
                        new TimeProfile.Breakpoint(0, 1),
                        new TimeProfile.Breakpoint(2, 3),
                        new TimeProfile.Breakpoint(4, 5),
                        new TimeProfile.Breakpoint(6, 9),
                        new TimeProfile.Breakpoint(8, 13),
                        new TimeProfile.Breakpoint(10, 17)),
                "compacted");

        assertEquals(List.of(
                new TimeProfile.Breakpoint(0, 1),
                new TimeProfile.Breakpoint(4, 5),
                new TimeProfile.Breakpoint(10, 17)),
                compacted.breakpoints());
        assertEquals(11, compacted.valueAt(7), 1e-9);
    }

    @Test
    void cancellableCompositionAvoidsAnEmptyUnsupportedProfile() {
        TimeProfile inner = new TimeProfile(
                Domain.closed(0, 5), time -> time + 10, "inner");
        TimeProfile unsupportedOuter = new TimeProfile(
                Domain.closed(0, 5), time -> time + 1, "outer");

        assertNull(inner.composeOrNull(
                unsupportedOuter, "empty", () -> false));
        assertThrows(CancellationException.class,
                () -> inner.composeOrNull(
                        unsupportedOuter, "cancelled", () -> true));
    }

    @Test
    void noLaterThanIsExactOnTheRequestedSubdomain() {
        TimeProfile early = TimeProfile.piecewise(
                Domain.closed(0, 10),
                List.of(
                        new TimeProfile.Breakpoint(0, 1),
                        new TimeProfile.Breakpoint(5, 6),
                        new TimeProfile.Breakpoint(10, 11)),
                "early");
        TimeProfile late = TimeProfile.piecewise(
                Domain.closed(0, 10),
                List.of(
                        new TimeProfile.Breakpoint(0, 2),
                        new TimeProfile.Breakpoint(5, 7),
                        new TimeProfile.Breakpoint(10, 12)),
                "late");

        assertTrue(early.noLaterThan(late, Domain.closed(2, 8)));
        assertFalse(late.noLaterThan(early, Domain.closed(2, 8)));
        assertFalse(early.noLaterThan(
                late, Domain.closed(2, 12)));
    }

    @Test
    void flatSegmentHasIntervalPreimageAndHalfOpenTargetIsPreserved() {
        TimeProfile profile = TimeProfile.piecewise(
                Domain.closed(0, 10),
                List.of(
                        new TimeProfile.Breakpoint(0, 0),
                        new TimeProfile.Breakpoint(2, 5),
                        new TimeProfile.Breakpoint(6, 5),
                        new TimeProfile.Breakpoint(10, 10)),
                "flat");

        assertEquals(
                Domain.closed(2, 6),
                profile.preimage(Domain.closed(5, 5), profile.domain()));

        Domain halfOpenPreimage = profile.preimage(Domain.halfOpen(5, 8), profile.domain());
        assertEquals(List.of(new Domain.Interval(2, 8.4, true, false)), halfOpenPreimage.intervals());
        assertTrue(halfOpenPreimage.contains(2));
        assertFalse(halfOpenPreimage.contains(8.4));
    }

    @Test
    void imageAndClosureEvaluationRetainExcludedEndpoint() {
        Domain root = Domain.halfOpen(0, 5);
        TimeProfile affine = new TimeProfile(root, time -> 2 * time + 1, "affine");

        Domain image = affine.imageDomain(root);

        assertEquals(List.of(new Domain.Interval(1, 11, true, false)), image.intervals());
        assertFalse(affine.domain().contains(5));
        assertEquals(11, affine.valueAtClosure(5), 1e-9);
    }

    @Test
    void pointwiseMinimumRejectsAnUnrepresentableSupportBoundaryJump() {
        TimeProfile shortFast = new TimeProfile(
                Domain.closed(0, 5), time -> time + 1, "short-fast");
        TimeProfile fullSlow = new TimeProfile(
                Domain.closed(0, 10), time -> time + 2, "full-slow");

        assertThrows(IllegalArgumentException.class,
                () -> shortFast.pointwiseMinimum(fullSlow, "minimum"));
    }

    @Test
    void pointwiseMinimumMergesCompatibleSupportBoundariesExactly() {
        TimeProfile prefix = new TimeProfile(
                Domain.closed(0, 5), time -> time + 2, "prefix");
        TimeProfile full = new TimeProfile(
                Domain.closed(0, 10), time -> time + 2, "full");

        TimeProfile minimum = prefix.pointwiseMinimum(full, "minimum");

        assertEquals(Domain.closed(0, 10), minimum.domain());
        assertEquals(2.0, minimum.minimumTravelTime(minimum.domain()), 1e-9);
        assertEquals(2.0, minimum.maximumTravelTime(minimum.domain()), 1e-9);
    }

    @Test
    void pointwiseMinimumRejectsAnIsolatedSingletonImprovement() {
        TimeProfile full = new TimeProfile(
                Domain.closed(0, 10), time -> time + 2, "full");
        TimeProfile singleton = TimeProfile.constant(Domain.closed(5, 5), 6);

        assertThrows(TimeProfile.DiscontinuousEnvelopeException.class,
                () -> full.pointwiseMinimum(singleton, "minimum"));
    }
}
