package edu.ipcmax.core.pcmax;

import java.util.Objects;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;

/**
 * Conservative feasible entry-time band for one corridor edge.
 */
public final class FeasibleEntryBand {
    private FeasibleEntryBand() {
    }

    /**
     * Computes
     * {@code [ts+d(s,x), te+B-lb(e)-d(y,d)]} with exact endpoint ownership.
     *
     * <p>The ordinary upper endpoint is open. It is closed only when it equals
     * the terminal closed endpoint of the graph function horizon.</p>
     */
    public static Domain compute(
            double departureStart,
            double departureEnd,
            double budget,
            double sourceToEdgeSource,
            double edgeLowerTravelTime,
            double edgeTargetToDestination,
            Domain graphFunctionHorizon) {
        Objects.requireNonNull(
                graphFunctionHorizon, "graph function horizon");
        if (graphFunctionHorizon.isEmpty()
                || graphFunctionHorizon.intervals().size() != 1) {
            throw new IllegalArgumentException(
                    "graph function horizon must be one non-empty interval");
        }
        for (double value : new double[] {
                departureStart,
                departureEnd,
                budget,
                sourceToEdgeSource,
                edgeLowerTravelTime,
                edgeTargetToDestination}) {
            if (!Double.isFinite(value)) {
                return Domain.empty();
            }
        }
        if (departureEnd < departureStart
                || budget < 0
                || sourceToEdgeSource < 0
                || edgeLowerTravelTime < 0
                || edgeTargetToDestination < 0) {
            throw new IllegalArgumentException(
                    "invalid feasible-entry-band argument");
        }
        double start = Domain.canonicalTime(
                departureStart + sourceToEdgeSource);
        double end = Domain.canonicalTime(
                departureEnd + budget
                        - edgeLowerTravelTime
                        - edgeTargetToDestination);
        if (end < start) {
            return Domain.empty();
        }
        Domain.Interval horizon =
                graphFunctionHorizon.intervals().get(0);
        boolean terminalClosed = horizon.endInclusive()
                && Domain.sameTime(end, horizon.end());
        if (Domain.sameTime(start, end)) {
            if (!terminalClosed) {
                return Domain.empty();
            }
            return Domain.closed(start, end)
                    .intersection(graphFunctionHorizon);
        }
        return Domain.of(new Domain.Interval(
                        start, end, true, terminalClosed))
                .intersection(graphFunctionHorizon);
    }

    /** Computes the band for one concrete corridor edge. */
    public static Domain compute(
            QueryCorridor corridor,
            QueryLowerBounds lowerBounds,
            QueryLowerBounds.Distances fromSource,
            QueryLowerBounds.Distances toDestination,
            Edge edge,
            Domain graphFunctionHorizon) {
        Objects.requireNonNull(corridor, "corridor");
        Objects.requireNonNull(lowerBounds, "lower bounds");
        Objects.requireNonNull(fromSource, "source distances");
        Objects.requireNonNull(toDestination, "destination distances");
        Objects.requireNonNull(edge, "edge");
        Domain.Interval horizon =
                graphFunctionHorizon.intervals().get(0);
        double departureStart = horizon.start();
        double departureEnd = Domain.canonicalTime(
                horizon.end() - corridor.budget());
        return compute(
                departureStart,
                departureEnd,
                corridor.budget(),
                fromSource.distance(edge.source()),
                lowerBounds.edgeWeight(edge.arcId()),
                toDestination.distance(edge.target()),
                graphFunctionHorizon);
    }
}
