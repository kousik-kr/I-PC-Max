package edu.ipcmax.core.function;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainTest {
    @Test
    void unionIntersectionDifferenceAndIterationAreExact() {
        Domain left = Domain.of(new Domain.Interval(1, 4), new Domain.Interval(8, 10));
        Domain right = Domain.closed(3, 8);

        assertEquals(List.of(new Domain.Interval(1, 10)), left.union(right).intervals());
        assertEquals(List.of(new Domain.Interval(3, 4), new Domain.Interval(8, 8)), left.intersection(right).intervals());
        assertEquals(List.of(new Domain.Interval(1, 2), new Domain.Interval(9, 10)), left.difference(right).intervals());

        List<Integer> values = new ArrayList<>();
        for (int value : Domain.of(new Domain.Interval(1, 2), new Domain.Interval(4, 4))) {
            values.add(value);
        }
        assertEquals(List.of(1, 2, 4), values);
    }

    @Test
    void emptyDomainHasNoIntervals() {
        assertTrue(Domain.empty().isEmpty());
    }
}
