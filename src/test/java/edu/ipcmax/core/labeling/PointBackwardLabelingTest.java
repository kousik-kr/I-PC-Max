package edu.ipcmax.core.labeling;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.core.validate.ExactPathValidator;
import edu.ipcmax.core.validate.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointBackwardLabelingTest {
    @Test
    void latestDepartureWitnessValidatesForward() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .edge(1, 2, 10)
                .edge(2, 3, 20)
                .edge(1, 3, 50)
                .build();

        PointBackwardLabeling.Result result = new PointBackwardLabeling(graph).run(3, 150);

        assertTrue(result.reached(1));
        assertEquals(120.0, result.latestDepartureAt(1), 1e-9);
        assertEquals(List.of(0, 1), result.pathFrom(1).arcIds());

        ValidationResult validation = new ExactPathValidator(graph)
                .validate(1, 3, 120, 30, result.pathFrom(1));
        assertTrue(validation.valid());
        assertEquals(150.0, validation.arrivalTime());
    }
}
