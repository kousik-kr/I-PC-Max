package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;

/**
 * Query configuration for a source-destination interval run.
 */
public record QuerySpec(
        int source,
        int destination,
        int departureStart,
        int departureEnd,
        double maxTravelTime,
        int granularityMinutes) {
    /**
     * Creates a validated query.
     */
    public QuerySpec {
        if (source <= 0 || destination <= 0) {
            throw new IllegalArgumentException("source and destination must be positive node ids");
        }
        if (departureEnd < departureStart) {
            throw new IllegalArgumentException("departure interval must satisfy start <= end");
        }
        if (maxTravelTime < 0) {
            throw new IllegalArgumentException("max travel time cannot be negative");
        }
        if (granularityMinutes <= 0) {
            throw new IllegalArgumentException("granularity must be positive");
        }
    }

    /**
     * Discrete departure domain for this query.
     */
    public Domain departureDomain() {
        return Domain.closed(departureStart, departureEnd);
    }

    /**
     * True when the minute belongs to the configured departure grid.
     */
    public boolean isOnGrid(int minute) {
        return minute >= departureStart
                && minute <= departureEnd
                && (minute - departureStart) % granularityMinutes == 0;
    }
}
