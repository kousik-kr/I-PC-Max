package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.validate.Path;

/**
 * One elementary interval in the extracted departure-time-to-path envelope.
 */
public record EnvelopeSegment(Domain.Interval interval, CandidateProfile candidate) {
    /**
     * Creates a segment. A null candidate represents bottom.
     */
    public EnvelopeSegment {
        if (interval == null) {
            throw new IllegalArgumentException("envelope interval is required");
        }
    }

    /**
     * True when this segment has a feasible path.
     */
    public boolean found() {
        return candidate != null;
    }

    /**
     * Materialized path, or the empty path for bottom.
     */
    public Path path() {
        return found() ? candidate.pathPointer().toPath() : Path.empty();
    }
}
