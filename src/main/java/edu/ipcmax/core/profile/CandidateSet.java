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
    /**
     * Canonical ordering independent of insertion and hash iteration order.
     */
    public static final Comparator<CandidateProfile> STABLE_ORDER = (left, right) -> {
        int comparison = Double.compare(
                left.domain().intervals().get(0).start(),
                right.domain().intervals().get(0).start());
        if (comparison != 0) {
            return comparison;
        }
        comparison = PathPointer.STABLE_PATH_ORDER.compare(left.stablePathId(), right.stablePathId());
        if (comparison != 0) {
            return comparison;
        }
        comparison = left.domain().toString().compareTo(right.domain().toString());
        if (comparison != 0) {
            return comparison;
        }
        comparison = left.arrivalProfile().fingerprint().compareTo(right.arrivalProfile().fingerprint());
        if (comparison != 0) {
            return comparison;
        }
        comparison = left.scoreProfile().fingerprint().compareTo(right.scoreProfile().fingerprint());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.explicitAnchorCount(), right.explicitAnchorCount());
        if (comparison != 0) {
            return comparison;
        }
        return Integer.compare(left.pivotId(), right.pivotId());
    };

    private final List<CandidateProfile> candidates = new ArrayList<>();

    /**
     * Adds a non-empty candidate.
     */
    public void add(CandidateProfile candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate is required");
        }
        candidates.add(candidate);
        candidates.sort(STABLE_ORDER);
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
     * True when no candidates are retained.
     */
    public boolean isEmpty() {
        return candidates.isEmpty();
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
