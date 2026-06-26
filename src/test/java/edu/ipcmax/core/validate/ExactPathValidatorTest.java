package edu.ipcmax.core.validate;

import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactPathValidatorTest {
    @Test
    void validatesNoWaitingPathAndScore() {
        PiecewiseConstFn score = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 420, 0),
                new PiecewiseConstFn.Interval(420, 600, 9),
                new PiecewiseConstFn.Interval(600, 1440, 0)));
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .edge(1, 2, 10, score)
                .edge(2, 3, 20)
                .build();

        ValidationResult result = new ExactPathValidator(graph)
                .validate(1, 3, 420, 40, new Path(List.of(0, 1)));

        assertTrue(result.valid());
        assertEquals(450.0, result.arrivalTime());
        assertEquals(30.0, result.travelTime());
        assertEquals(9, result.score());
    }

    @Test
    void rejectsBudgetViolation() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .edge(1, 2, 10)
                .build();

        ValidationResult result = new ExactPathValidator(graph)
                .validate(1, 2, 420, 5, new Path(List.of(0)));

        assertFalse(result.valid());
    }
}
