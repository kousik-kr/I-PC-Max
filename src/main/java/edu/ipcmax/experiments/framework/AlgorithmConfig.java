package edu.ipcmax.experiments.framework;

import edu.ipcmax.core.pcmax.PaceExecutionPolicy;
import edu.ipcmax.core.pcmax.PaceFeatures;
import edu.ipcmax.core.pcmax.PaceOptions;

/** Normalized effective configuration supplied to every algorithm. */
public record AlgorithmConfig(
        String algorithm,
        Ablation ablation,
        int theta,
        int anchorLimit,
        int k,
        int threads,
        int rpqStepMinutes,
        int baselineK,
        long maxEnumeratedPaths,
        long maxLabels,
        long maxExpansions,
        int maxFrontierFragments,
        boolean deterministic,
        long seed) {

    /** Builds the one PACE option object used by all PACE-B ablations. */
    public PaceOptions paceOptions() {
        boolean bounded = algorithm.equals("pace-b");
        if (!bounded) {
            return new PaceOptions(PaceExecutionPolicy.PACE_X, theta, PaceOptions.UNBOUNDED,
                    PaceOptions.UNBOUNDED, 1, true, PaceFeatures.defaults(), maxFrontierFragments);
        }
        int effectiveTheta = ablation == Ablation.NO_ANCHOR ? 0 : theta;
        int effectiveAnchorLimit = ablation == Ablation.ALL_ANCHORS
                ? PaceOptions.UNBOUNDED : anchorLimit;
        int effectiveThreads = ablation == Ablation.SERIAL ? 1 : threads;
        boolean memo = ablation != Ablation.NO_MEMO;
        boolean safe = ablation != Ablation.NO_SAFE_DOM && ablation != Ablation.NO_COMPRESSION;
        boolean perCell = ablation != Ablation.GLOBAL_K;
        boolean representatives = ablation != Ablation.RANK_ONLY;
        boolean anchorLb = ablation != Ablation.NO_ANCHOR_LB;
        boolean compression = ablation != Ablation.NO_COMPRESSION;
        boolean merge = ablation != Ablation.NO_MERGE && ablation != Ablation.NO_COMPRESSION;
        PaceFeatures features = new PaceFeatures(
                safe, perCell, representatives, anchorLb, compression, merge);
        return new PaceOptions(PaceExecutionPolicy.PACE_B, effectiveTheta, effectiveAnchorLimit,
                k, effectiveThreads, memo, features, maxFrontierFragments);
    }
}
