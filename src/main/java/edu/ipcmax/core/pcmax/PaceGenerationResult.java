package edu.ipcmax.core.pcmax;

import java.util.List;
import java.util.Objects;

import edu.ipcmax.core.profile.CandidateSet;

/** Completion-bearing output of the feature-flagged candidate engine. */
public record PaceGenerationResult(
        CandidateSet frontier,
        PaceCompletion completion,
        PaceExactnessScope exactnessScope,
        PaceCapStatus capStatus,
        PaceGenerationStats stats,
        String corridorChecksum,
        List<Integer> selectedPivotArcIds,
        String outputChecksum) {
    public PaceGenerationResult {
        Objects.requireNonNull(frontier, "frontier");
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(exactnessScope, "exactnessScope");
        Objects.requireNonNull(capStatus, "capStatus");
        Objects.requireNonNull(stats, "stats");
        corridorChecksum = corridorChecksum == null ? "" : corridorChecksum;
        selectedPivotArcIds = List.copyOf(selectedPivotArcIds);
        outputChecksum = outputChecksum == null ? "" : outputChecksum;
        if (exactnessScope == PaceExactnessScope.GLOBAL_CERTIFIED
                && ((completion != PaceCompletion.COMPLETE
                     && completion != PaceCompletion.NO_FEASIBLE_PATH)
                    || capStatus.any())) {
            throw new IllegalArgumentException(
                    "global exactness requires complete uncapped generation");
        }
    }
}
