package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.labeling.PointForwardLabeling;
import edu.ipcmax.core.validate.ExactPathValidator;
import edu.ipcmax.core.validate.Path;
import edu.ipcmax.core.validate.ValidationResult;

/**
 * Exact repeated fastest-path interval baseline.
 *
 * <p>This is not I-PC-Max. It is a deterministic baseline and smoke runner that validates
 * every reported path exactly.</p>
 */
public final class RepeatedFastestPathBaseline {
    private final TDGraph graph;

    /**
     * Creates the baseline runner.
     */
    public RepeatedFastestPathBaseline(TDGraph graph) {
        this.graph = graph;
    }

    /**
     * Runs point FIFO earliest-arrival labeling at every configured departure time.
     */
    public IPCMaxResult solve(QuerySpec query) {
        PointForwardLabeling labeler = new PointForwardLabeling(graph);
        ExactPathValidator validator = new ExactPathValidator(graph);
        IPCMaxResult best = IPCMaxResult.notFound("destination unreachable");

        for (int departure : query.departureDomain()) {
            if (!query.isOnGrid(departure)) {
                continue;
            }
            PointForwardLabeling.Result labels = labeler.run(query.source(), departure, query.maxTravelTime());
            if (!labels.reached(query.destination())) {
                continue;
            }
            Path path = labels.pathTo(query.destination());
            ValidationResult validation = validator.validate(
                    query.source(), query.destination(), departure, query.maxTravelTime(), path);
            if (!validation.valid()) {
                continue;
            }
            IPCMaxResult candidate = new IPCMaxResult(
                    true,
                    departure,
                    validation.arrivalTime(),
                    validation.travelTime(),
                    validation.score(),
                    path,
                    "");
            if (ResultComparator.INSTANCE.compare(candidate, best) < 0) {
                best = candidate;
            }
        }

        return best;
    }
}
