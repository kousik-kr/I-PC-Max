package edu.ipcmax.experiments.framework;

import edu.ipcmax.core.pcmax.PaceEngineMode;
import edu.ipcmax.core.pcmax.PaceExecutionPolicy;
import edu.ipcmax.core.pcmax.PaceFeatures;
import edu.ipcmax.core.pcmax.PaceOptions;

/** Normalized effective configuration supplied to every algorithm. */
public record AlgorithmConfig(
        String algorithm,
        Ablation ablation,
        PaceEngineMode paceEngineMode,
        int theta,
        int pivotLimitL,
        int connectorLimitKc,
        int frontierLimitKf,
        long connectorExpansionCapMc,
        int breakpointCapMb,
        long queryWorkCapMq,
        int threads,
        int rpqStepMinutes,
        int baselineK,
        long maxEnumeratedPaths,
        long maxLabels,
        long maxExpansions,
        int maxFrontierFragments,
        boolean deterministic,
        long seed) {

    /** Compatibility constructor for the previous shared L/K CLI shape. */
    public AlgorithmConfig(
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
        this(
                algorithm,
                ablation,
                PaceEngineMode.SCALABLE,
                theta,
                anchorLimit,
                k,
                k,
                maxExpansions,
                maxFrontierFragments,
                maxExpansions,
                threads,
                rpqStepMinutes,
                baselineK,
                maxEnumeratedPaths,
                maxLabels,
                maxExpansions,
                maxFrontierFragments,
                deterministic,
                seed);
    }

    /** Builds the feature-flagged PACE option object. */
    public PaceOptions paceOptions() {
        boolean bounded = algorithm.equals("pace-b");
        if (!bounded) {
            return new PaceOptions(
                    PaceExecutionPolicy.PACE_X,
                    paceEngineMode,
                    theta,
                    PaceOptions.UNBOUNDED,
                    PaceOptions.UNBOUNDED,
                    PaceOptions.UNBOUNDED,
                    PaceOptions.UNBOUNDED_WORK,
                    PaceOptions.UNBOUNDED,
                    PaceOptions.UNBOUNDED_WORK,
                    1,
                    true,
                    PaceFeatures.defaults(),
                    maxFrontierFragments);
        }
        int effectiveTheta =
                ablation == Ablation.NO_ANCHOR ? 0 : theta;
        int effectivePivotLimit =
                ablation == Ablation.ALL_ANCHORS
                        ? PaceOptions.UNBOUNDED
                        : pivotLimitL;
        int effectiveThreads =
                ablation == Ablation.SERIAL ? 1 : threads;
        boolean memo = ablation != Ablation.NO_MEMO;
        boolean safe = ablation != Ablation.NO_SAFE_DOM
                && ablation != Ablation.NO_COMPRESSION;
        boolean perCell = ablation != Ablation.GLOBAL_K;
        boolean representatives = ablation != Ablation.RANK_ONLY;
        boolean anchorLb = ablation != Ablation.NO_ANCHOR_LB;
        boolean compression = ablation != Ablation.NO_COMPRESSION;
        boolean merge = ablation != Ablation.NO_MERGE
                && ablation != Ablation.NO_COMPRESSION;
        boolean safeCorridor =
                ablation != Ablation.NO_SAFE_CORRIDOR;
        boolean pivotDiversification =
                ablation != Ablation.NO_PIVOT_DIVERSIFICATION;
        boolean connectorPortfolio =
                ablation != Ablation.FAST_ONLY_CONNECTOR;
        boolean connectorCache =
                memo && ablation != Ablation.NO_CONNECTOR_CACHE;
        boolean profileCache = memo;
        boolean scoreUpperBound =
                ablation != Ablation.NO_SCORE_UPPER_BOUND;
        PaceFeatures features = new PaceFeatures(
                safe,
                perCell,
                representatives,
                anchorLb,
                compression,
                merge,
                safeCorridor,
                pivotDiversification,
                connectorPortfolio,
                connectorCache,
                profileCache,
                scoreUpperBound);
        return new PaceOptions(
                PaceExecutionPolicy.PACE_B,
                paceEngineMode,
                effectiveTheta,
                effectivePivotLimit,
                connectorLimitKc,
                frontierLimitKf,
                connectorExpansionCapMc,
                breakpointCapMb,
                queryWorkCapMq,
                effectiveThreads,
                memo,
                features,
                maxFrontierFragments);
    }

    /** Historical alias for L. */
    public int anchorLimit() {
        return pivotLimitL;
    }

    /** Historical shared-K alias, now the frontier K_f. */
    public int k() {
        return frontierLimitKf;
    }
}
