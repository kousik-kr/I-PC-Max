package edu.ipcmax.core.profile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.Domain;

class BudgetRestrictionContinuousTest {
    @Test
    void budgetCutsAtNonIntegerCrossing() {
        TimeProfile arrival = TimeProfile.piecewise(
                Domain.closed(0, 10),
                List.of(
                        new TimeProfile.Breakpoint(0, 10.25),
                        new TimeProfile.Breakpoint(10, 22.25)),
                "budget-arrival");

        Domain feasible = arrival.domainWhereTravelTimeAtMost(Domain.closed(0, 10), 11.0);

        assertEquals(List.of(new Domain.Interval(0, 3.75)), feasible.intervals());
    }
}