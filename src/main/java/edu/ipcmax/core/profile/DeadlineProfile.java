package edu.ipcmax.core.profile;

import edu.ipcmax.core.function.Domain;

/**
 * Absolute latest-arrival deadline profile over the root departure domain.
 */
public final class DeadlineProfile {
    private final TimeProfile profile;

    private DeadlineProfile(TimeProfile profile) {
        this.profile = profile;
    }

    /**
     * Creates {@code Lambda(t)=t+budget}.
     */
    public static DeadlineProfile constantBudget(Domain domain, double budget) {
        if (budget < 0) {
            throw new IllegalArgumentException("budget cannot be negative");
        }
        return new DeadlineProfile(new TimeProfile(domain, t -> t + budget, "deadline-budget:" + budget + ":" + domain.intervals()));
    }

    /**
     * Evaluates the absolute deadline.
     */
    public double valueAt(int rootDepartureTime) {
        return profile.valueAt(rootDepartureTime);
    }

    /**
     * Domain where the deadline is valid.
     */
    public Domain domain() {
        return profile.domain();
    }

    /**
     * Stable fingerprint for memoization.
     */
    public String fingerprint() {
        return profile.fingerprint();
    }

    /**
     * Restricts this deadline to a subdomain.
     */
    public DeadlineProfile restrict(Domain subdomain) {
        return new DeadlineProfile(profile.restrict(subdomain));
    }
}
