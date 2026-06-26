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
}
