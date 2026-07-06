package edu.ipcmax.core.pcmax;

import java.util.List;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.validate.ExactPathValidator;
import edu.ipcmax.core.validate.ValidationResult;

/**
 * Departure-time-to-path profile extracted from a PACE candidate frontier.
 */
public record EnvelopeProfile(Domain domain, List<EnvelopeSegment> segments) {
    /**
     * Creates an immutable envelope profile.
     */
    public EnvelopeProfile {
        if (domain == null) {
            throw new IllegalArgumentException("envelope domain is required");
        }
        segments = List.copyOf(segments);
    }

    /**
     * Selects the best legacy point result from this profile for compatibility callers.
     */
    public IPCMaxResult bestResult(ExactPathValidator validator, int source, int destination, double budget) {
        IPCMaxResult best = IPCMaxResult.notFound("no feasible envelope segment");
        for (EnvelopeSegment segment : segments) {
            if (!segment.found()) {
                continue;
            }
            CandidateProfile candidate = segment.candidate();
            int start = (int) Math.ceil(segment.interval().start());
            int end = (int) Math.floor(segment.interval().end());
            for (int departure = start; departure <= end; departure++) {
                ValidationResult validation = validator.validate(
                        source,
                        destination,
                        departure,
                        budget,
                        candidate.pathPointer().toPath());
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
