package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.validate.ExactPathValidator;

/**
 * Selects the best root result from candidate profiles using exact validation.
 */
public final class RootResultSelector {
    private final ExactPathValidator validator;

    /**
     * Creates a selector.
     */
    public RootResultSelector(ExactPathValidator validator) {
        this.validator = validator;
    }

    /**
     * Selects best validated result.
     */
    public IPCMaxResult select(CandidateSet candidates, int source, int destination, double budget) {
        return candidates.selectBest(validator, source, destination, budget);
    }
}
