package edu.ipcmax.core.function;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class PiecewiseConstExactTest {
    @Test
    void restrictionPreservesRightContinuousValueAtFinalEndpoint() {
        PiecewiseConstFn function = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 5, 1),
                new PiecewiseConstFn.Interval(5, 10, 2)));

        PiecewiseConstFn restricted = function.restrict(Domain.closed(0, 5));

        assertEquals(1, restricted.valueAt(4.5));
        assertEquals(2, restricted.valueAt(5));
        assertEquals(List.of(
                new PiecewiseConstFn.Interval(0, 5, 1),
                new PiecewiseConstFn.Interval(5, 5, 2)), restricted.intervals());
    }

    @Test
    void additionAndAffinePullbackUseContinuousBreakpoints() {
        PiecewiseConstFn left = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 3, 1),
                new PiecewiseConstFn.Interval(3, 10, 4)));
        PiecewiseConstFn right = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 7, 10),
                new PiecewiseConstFn.Interval(7, 10, 20)));

        PiecewiseConstFn sum = left.add(right, Domain.closed(0, 10));
        PiecewiseConstFn pulledBack = right.compose(time -> 2 * time, Domain.closed(0, 5));

        assertEquals(11, sum.valueAt(2.9));
        assertEquals(14, sum.valueAt(3));
        assertEquals(24, sum.valueAt(7));
        assertEquals(10, pulledBack.valueAt(3.49));
        assertEquals(20, pulledBack.valueAt(3.5));
        assertEquals(20, pulledBack.valueAt(5));
    }
}
