package edu.ipcmax.core.profile;

import edu.ipcmax.core.function.Domain;

/**
 * Pointwise safe dominance checks for exact discrete domains.
 */
public final class SafeProfileDominance {
    private SafeProfileDominance() {
    }

    /**
     * Returns true only if candidate A arrives no later and scores no lower for every time in the domain.
     */
    public static boolean dominates(CandidateProfile a, CandidateProfile b, Domain domain) {
        Domain common = a.domain().intersection(b.domain()).intersection(domain);
        if (common.isEmpty()) {
            return false;
        }
        for (int t : common) {
            if (a.arrivalProfile().valueAt(t) > b.arrivalProfile().valueAt(t)) {
                return false;
            }
            if (a.scoreProfile().valueAt(t) < b.scoreProfile().valueAt(t)) {
                return false;
            }
        }
        return true;
    }
}
