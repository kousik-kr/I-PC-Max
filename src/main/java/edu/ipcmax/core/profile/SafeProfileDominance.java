package edu.ipcmax.core.profile;

import edu.ipcmax.core.function.Domain;

/**
 * Conservative dominance checks for exact discrete domains.
 */
public final class SafeProfileDominance {
    private static final double EPSILON = 1e-9;

    private SafeProfileDominance() {
    }

    /**
     * Returns true only when candidate A has the same arrival profile and no lower score.
     *
     * <p>PACE extension-safe dominance also requires the path-consistency signature condition. This codebase
     * does not yet model Omega signatures, so this helper intentionally avoids earlier-arrival pruning.</p>
     */
    public static boolean dominates(CandidateProfile a, CandidateProfile b, Domain domain) {
        Domain common = a.domain().intersection(b.domain()).intersection(domain);
        if (common.isEmpty()) {
            return false;
        }
        for (int t : common) {
            if (Math.abs(a.arrivalProfile().valueAt(t) - b.arrivalProfile().valueAt(t)) > EPSILON) {
                return false;
            }
            if (a.scoreProfile().valueAt(t) < b.scoreProfile().valueAt(t)) {
                return false;
            }
        }
        return true;
    }
}
