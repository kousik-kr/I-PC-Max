package edu.ipcmax.core.profile;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.pcmax.IPCMaxResult;
import edu.ipcmax.core.pcmax.ResultComparator;
import edu.ipcmax.core.validate.ExactPathValidator;
import edu.ipcmax.core.validate.ValidationResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic container for candidate profiles.
 */
public final class CandidateSet {
    private final List<CandidateProfile> candidates = new ArrayList<>();

    /**
     * Adds a non-empty candidate.
     */
    public void add(CandidateProfile candidate) {
        candidates.add(candidate);
        candidates.sort(Comparator
                .comparing((CandidateProfile c) -> c.domain().intervals().get(0).start())
                .thenComparing(c -> c.pathPointer().arcIds().toString()));
    }

    /**
     * Adds all candidates.
     */
    public void addAll(CandidateSet other) {
        for (CandidateProfile candidate : other.candidates()) {
            add(candidate);
        }
    }

    /**
     * Immutable candidate list.
     */
    public List<CandidateProfile> candidates() {
        return List.copyOf(candidates);
    }

    /**
     * Candidate count.
     */
    public int size() {
        return candidates.size();
    }

    /**
     * Selects the best exactly validated result by scanning all discrete times in candidate domains.
     */
    public IPCMaxResult selectBest(ExactPathValidator validator, int source, int destination, double budget) {
        IPCMaxResult best = IPCMaxResult.notFound("no valid candidate");
        for (CandidateProfile candidate : candidates) {
            Domain domain = candidate.domain();
            for (int departure : domain) {
                ValidationResult validation = validator.validate(
                        source, destination, departure, budget, candidate.pathPointer().toPath());
                if (!validation.valid()) {
                    continue;
                }
                IPCMaxResult result = new IPCMaxResult(
                        true,
                        departure,
                        validation.arrivalTime(),
                        validation.travelTime(),
                        validation.score(),
                        candidate.pathPointer().toPath(),
                        "");
                if (ResultComparator.INSTANCE.compare(result, best) < 0) {
                    best = result;
                }
            }
        }
        return best;
    }
}
