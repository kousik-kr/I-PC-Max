package edu.ipcmax.core.function;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiecewiseFunctionTest {
    @Test
    void linearTravelTimeEvaluatesInterpolatedValuesAndArrival() {
        PiecewiseLinearFn fn = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 10),
                new PiecewiseLinearFn.Breakpoint(60, 20),
                new PiecewiseLinearFn.Breakpoint(120, 10)));

        assertEquals(15.0, fn.travelTimeAt(30), 1e-9);
        assertEquals(45.0, fn.arrivalTimeAt(30), 1e-9);
        assertTrue(fn.isFifo());
        assertEquals(30.0, fn.latestDepartureForArrival(45), 1e-9);
        assertEquals(10.0, fn.minTravelTime(), 1e-9);
    }

    @Test
    void linearTravelTimeRejectsNonFifoArrival() {
        PiecewiseLinearFn fn = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 100),
                new PiecewiseLinearFn.Breakpoint(60, 0)));

        assertFalse(fn.isFifo());
        assertThrows(IllegalArgumentException.class, () -> fn.requireFifo("test"));
    }

    @Test
    void constantScoreEvaluatesBoundariesAndPositiveDomain() {
        PiecewiseConstFn fn = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 420, 0),
                new PiecewiseConstFn.Interval(420, 600, 7),
                new PiecewiseConstFn.Interval(600, 1440, 0)));

        assertEquals(0, fn.valueAt(0));
        assertEquals(7, fn.valueAt(420));
        assertEquals(0, fn.valueAt(600));
        assertTrue(fn.hasPositiveValueIn(new TimeRange(500, 520)));
        assertFalse(fn.hasPositiveValueIn(new TimeRange(700, 800)));
    }

    @Test
    void linearRestrictionRetainsBreakpointsAndExactDomainHoles() {
        PiecewiseLinearFn fn = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 0),
                new PiecewiseLinearFn.Breakpoint(5, 10),
                new PiecewiseLinearFn.Breakpoint(10, 0)));

        PiecewiseLinearFn restricted = fn.restrict(Domain.of(
                new Domain.Interval(0, 3, true, false),
                new Domain.Interval(7, 10)));

        assertEquals(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 0),
                new PiecewiseLinearFn.Breakpoint(3, 6),
                new PiecewiseLinearFn.Breakpoint(7, 6),
                new PiecewiseLinearFn.Breakpoint(10, 0)), restricted.breakpoints());
        assertEquals(2, restricted.travelTimeAt(1), 1e-9);
        assertEquals(4, restricted.travelTimeAt(8), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> restricted.travelTimeAt(3));
        assertThrows(IllegalArgumentException.class, () -> restricted.travelTimeAt(5));
    }

    @Test
    void arrivalComparisonCutsAtContinuousRoot() {
        PiecewiseLinearFn fn = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 0),
                new PiecewiseLinearFn.Breakpoint(10, 10)));

        Domain feasible = fn.domainWhereArrivalAtMost(Domain.closed(0, 10), ignored -> 7.5);

        assertEquals(Domain.closed(0, 3.75), feasible);
    }
}
