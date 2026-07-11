package edu.ipcmax.core.pcmax;

import java.util.Objects;

/**
 * Configuration that affects PACE candidate generation.
 *
 * @param policy execution policy
 * @param theta maximum number of explicitly introduced anchor edges
 * @param anchorLimit maximum relevant anchors retained by a bounded subproblem
 * @param frontierLimit connector/frontier limit {@code K} in bounded mode
 * @param threadCount requested worker count
 * @param memoizationEnabled whether recursive frontiers are memoized
 */
public record PaceOptions(
        PaceExecutionPolicy policy,
        int theta,
        int anchorLimit,
        int frontierLimit,
        int threadCount,
        boolean memoizationEnabled,
        PaceFeatures features,
        int maxFrontierFragments) {
    /** Sentinel used in canonical keys for limits disabled by PACE-X. */
    public static final int UNBOUNDED = Integer.MAX_VALUE;

    /** Creates validated PACE options. */
    public PaceOptions {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(features, "features");
        if (theta < 0) {
            throw new IllegalArgumentException("theta cannot be negative");
        }
        if (threadCount < 1) {
            throw new IllegalArgumentException("thread count must be at least 1");
        }
        if (policy == PaceExecutionPolicy.PACE_B) {
            if (anchorLimit < 1) {
                throw new IllegalArgumentException("PACE-B anchor limit L must be at least 1");
            }
            if (frontierLimit < 1) {
                throw new IllegalArgumentException("PACE-B connector/frontier limit K must be at least 1");
            }
        } else {
            anchorLimit = UNBOUNDED;
            frontierLimit = UNBOUNDED;
            features = PaceFeatures.defaults();
        }
        if (maxFrontierFragments < 1) {
            throw new IllegalArgumentException("maximum frontier fragments must be at least 1");
        }
    }

    /** Source-compatible constructor for the finalized, non-ablated implementation. */
    public PaceOptions(
            PaceExecutionPolicy policy,
            int theta,
            int anchorLimit,
            int frontierLimit,
            int threadCount,
            boolean memoizationEnabled) {
        this(policy, theta, anchorLimit, frontierLimit, threadCount, memoizationEnabled,
                PaceFeatures.defaults(), Integer.MAX_VALUE);
    }

    /** Exhaustive validation configuration. */
    public static PaceOptions exhaustive(int theta) {
        return new PaceOptions(PaceExecutionPolicy.PACE_X, theta, UNBOUNDED, UNBOUNDED, 1, true,
                PaceFeatures.defaults(), Integer.MAX_VALUE);
    }

    /** Deterministically bounded configuration. */
    public static PaceOptions bounded(int theta, int anchorLimit, int frontierLimit) {
        return new PaceOptions(PaceExecutionPolicy.PACE_B, theta, anchorLimit, frontierLimit, 1, true,
                PaceFeatures.defaults(), Integer.MAX_VALUE);
    }

    /** Effective anchor limit included in memoization keys. */
    public int effectiveAnchorLimit() {
        return policy == PaceExecutionPolicy.PACE_X ? UNBOUNDED : anchorLimit;
    }

    /** Effective connector/frontier limit included in memoization keys. */
    public int effectiveFrontierLimit() {
        return policy == PaceExecutionPolicy.PACE_X ? UNBOUNDED : frontierLimit;
    }
}
