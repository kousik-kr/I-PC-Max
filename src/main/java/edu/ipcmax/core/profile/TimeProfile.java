package edu.ipcmax.core.profile;

import edu.ipcmax.core.function.Domain;

import java.util.Objects;
import java.util.function.DoubleUnaryOperator;

/**
 * Time-valued profile over a discrete root departure domain.
 */
public final class TimeProfile {
    private final Domain domain;
    private final DoubleUnaryOperator evaluator;
    private final String fingerprint;

    /**
     * Creates a profile with a stable fingerprint.
     */
    public TimeProfile(Domain domain, DoubleUnaryOperator evaluator, String fingerprint) {
        if (domain == null || domain.isEmpty()) {
            throw new IllegalArgumentException("time profile domain cannot be null or empty");
        }
        this.domain = domain;
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
    }

    /**
     * Identity departure profile {@code psi(t)=t}.
     */
    public static TimeProfile identity(Domain domain) {
        return new TimeProfile(domain, t -> t, "identity:" + domain.intervals());
    }

    /**
     * Constant time profile.
     */
    public static TimeProfile constant(Domain domain, double value) {
        return new TimeProfile(domain, ignored -> value, "constant:" + value + ":" + domain.intervals());
    }

    /**
     * Evaluates the profile at a root departure time.
     */
    public double valueAt(int rootDepartureTime) {
        if (!domain.contains(rootDepartureTime)) {
            throw new IllegalArgumentException("time is outside profile domain: " + rootDepartureTime);
        }
        return evaluator.applyAsDouble(rootDepartureTime);
    }

    /**
     * Domain where this profile is valid.
     */
    public Domain domain() {
        return domain;
    }

    /**
     * Stable fingerprint for memoization.
     */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * Restricts this profile to a subdomain.
     */
    public TimeProfile restrict(Domain subdomain) {
        Domain restricted = domain.intersection(subdomain);
        if (restricted.isEmpty()) {
            throw new IllegalArgumentException("restricted time profile domain is empty");
        }
        return new TimeProfile(restricted, evaluator, fingerprint + "|restrict:" + restricted.intervals());
    }
}
