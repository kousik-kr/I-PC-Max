package edu.ipcmax.core.profile;

import edu.ipcmax.core.function.Domain;

/**
 * Splits a domain into feasible subdomains using arrival plus lower-bound remaining travel time.
 */
public final class SplitFunctionPruner {
    private SplitFunctionPruner() {
    }

    /**
     * Keeps times where {@code arrival(t)+lowerBoundRemaining <= deadline(t)}.
     */
    public static Domain feasibleDomain(
            Domain domain,
            TimeProfile arrival,
            DeadlineProfile deadline,
            double lowerBoundRemaining) {
        Domain result = Domain.empty();
        Integer start = null;
        Integer previous = null;
        for (int t : domain) {
            boolean feasible = arrival.valueAt(t) + lowerBoundRemaining <= deadline.valueAt(t);
            if (feasible && start == null) {
                start = t;
            }
            if (!feasible && start != null) {
                result = result.union(Domain.closed(start, previous));
                start = null;
            }
            previous = t;
        }
        if (start != null) {
            result = result.union(Domain.closed(start, previous));
        }
        return result;
    }
}
