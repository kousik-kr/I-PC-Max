package edu.ipcmax.core.profile;

import edu.ipcmax.core.function.Domain;

/**
 * Candidate path profile over a root departure domain.
 */
public record CandidateProfile(
        Domain domain,
        TimeProfile arrivalProfile,
        ScoreProfile scoreProfile,
        PathPointer pathPointer,
        int recursionDepth,
        int pivotId,
        boolean compressed) {
    /**
     * Creates a validated candidate profile.
     */
    public CandidateProfile {
        if (domain == null || domain.isEmpty()) {
            throw new IllegalArgumentException("candidate domain cannot be null or empty");
        }
        if (arrivalProfile == null || scoreProfile == null || pathPointer == null) {
            throw new IllegalArgumentException("candidate profiles and path pointer are required");
        }
    }

    /**
     * Restricts this candidate to a subdomain.
     */
    public CandidateProfile restrict(Domain subdomain) {
        Domain restricted = domain.intersection(subdomain);
        if (restricted.isEmpty()) {
            throw new IllegalArgumentException("restricted candidate domain is empty");
        }
        return new CandidateProfile(
                restricted,
                arrivalProfile.restrict(restricted),
                scoreProfile.restrict(restricted),
                pathPointer,
                recursionDepth,
                pivotId,
                compressed);
    }
}
