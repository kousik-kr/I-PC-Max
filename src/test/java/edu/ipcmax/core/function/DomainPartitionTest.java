package edu.ipcmax.core.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class DomainPartitionTest {
    @Test
    void splitAtKeepsOrderedHalfOpenCellsWithoutChangingTheSet() {
        Domain original = Domain.closed(0, 10);

        Domain partition = original.splitAt(List.of(7.0, 3.0, 7.0));

        assertEquals(List.of(
                new Domain.Interval(0, 3, true, false),
                new Domain.Interval(3, 7, true, false),
                new Domain.Interval(7, 10, true, true)), partition.intervals());
        assertEquals(original, partition);
        assertEquals(List.of(0.0, 3.0, 7.0, 10.0), partition.breakpoints());
        assertTrue(partition.contains(3));
        assertTrue(partition.contains(7));

        List<Integer> integerPoints = new ArrayList<>();
        partition.forEach(integerPoints::add);
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10), integerPoints);
    }

    @Test
    void endpointOwnershipSurvivesSetOperations() {
        Domain halfOpen = Domain.halfOpen(3, 7);

        assertTrue(halfOpen.contains(3));
        assertFalse(halfOpen.contains(7));
        assertTrue(halfOpen.intersection(Domain.closed(7, 9)).isEmpty());
        assertEquals(
                Domain.of(new Domain.Interval(0, 3, true, false)),
                Domain.closed(0, 7).difference(Domain.closed(3, 7)));
    }
}
