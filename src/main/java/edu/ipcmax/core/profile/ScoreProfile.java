package edu.ipcmax.core.profile;

import edu.ipcmax.core.function.Domain;

import java.util.Objects;
import java.util.function.IntUnaryOperator;

/**
 * Integer score profile over root departure times.
 */
public final class ScoreProfile {
    private final Domain domain;
    private final IntUnaryOperator evaluator;
    private final String fingerprint;

    /**
     * Creates a score profile.
     */
    public ScoreProfile(Domain domain, IntUnaryOperator evaluator, String fingerprint) {
        if (domain == null || domain.isEmpty()) {
            throw new IllegalArgumentException("score profile domain cannot be null or empty");
        }
        this.domain = domain;
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
    }

    /**
     * Constant score profile.
     */
    public static ScoreProfile constant(Domain domain, int score) {
        return new ScoreProfile(domain, ignored -> score, "score-constant:" + score + ":" + domain.intervals());
    }

    /**
     * Evaluates score at a root departure time.
     */
    public int valueAt(int rootDepartureTime) {
        if (!domain.contains(rootDepartureTime)) {
            throw new IllegalArgumentException("time is outside score profile domain: " + rootDepartureTime);
        }
        return evaluator.applyAsInt(rootDepartureTime);
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
     * Restricts this score profile.
     */
    public ScoreProfile restrict(Domain subdomain) {
        Domain restricted = domain.intersection(subdomain);
        if (restricted.isEmpty()) {
            throw new IllegalArgumentException("restricted score profile domain is empty");
        }
        return new ScoreProfile(restricted, evaluator, fingerprint + "|restrict:" + restricted.intervals());
    }
}
