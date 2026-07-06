package edu.ipcmax.core.function;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainContinuousTest {
    @Test
    void intersectionUnionNormalizationAndEqualityHandleReals() {
        Domain left = Domain.of(new Domain.Interval(0.5, 2.0), new Domain.Interval(2.0, 4.0));
        Domain right = Domain.of(new Domain.Interval(1.25, 3.5), new Domain.Interval(3.5, 5.0));

        assertEquals(List.of(new Domain.Interval(0.5, 4.0)), left.intervals());
        assertEquals(List.of(new Domain.Interval(1.25, 5.0)), right.intervals());
        assertEquals(List.of(new Domain.Interval(1.25, 4.0)), left.intersection(right).intervals());
        assertEquals(List.of(new Domain.Interval(0.5, 5.0)), left.union(right).intervals());

        assertTrue(left.contains(1.75));
        assertFalse(left.contains(4.5));

        Domain normalized = Domain.closed(0.5, 4.0);
        assertEquals(normalized, left);
        assertEquals(normalized.hashCode(), left.hashCode());
    }
}