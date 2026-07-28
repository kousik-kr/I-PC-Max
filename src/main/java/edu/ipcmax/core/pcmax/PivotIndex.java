package edu.ipcmax.core.pcmax;

import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/** Query-wide, prefix-stable selected pivot set. */
public final class PivotIndex {
    private final List<Pivot> selected;
    private final List<Integer> scoreRelevantArcIds;
    private final BitSet selectedMembership;
    private final String version;

    PivotIndex(
            List<Pivot> selected,
            List<Integer> scoreRelevantArcIds,
            String version) {
        this.selected = List.copyOf(selected);
        this.scoreRelevantArcIds = List.copyOf(scoreRelevantArcIds);
        this.version = Objects.requireNonNull(version, "version");
        selectedMembership = new BitSet();
        for (Pivot pivot : selected) {
            selectedMembership.set(pivot.arcId());
        }
    }

    public List<Pivot> selected() {
        return selected;
    }

    public List<Integer> selectedArcIds() {
        return selected.stream().map(Pivot::arcId).toList();
    }

    public List<Integer> scoreRelevantArcIds() {
        return scoreRelevantArcIds;
    }

    public boolean isSelectedPivot(int arcId) {
        return arcId >= 0 && selectedMembership.get(arcId);
    }

    public String version() {
        return version;
    }

    /** Stable pivot ranking features retained for provenance. */
    public record Pivot(
            int arcId,
            int source,
            int target,
            int maximumScore,
            double temporalCoverage,
            double lowerBoundDetour,
            String cellId,
            int canonicalRank) {
        public Pivot {
            if (arcId < 0 || source <= 0 || target <= 0
                    || maximumScore <= 0
                    || !Double.isFinite(temporalCoverage)
                    || temporalCoverage < 0
                    || !Double.isFinite(lowerBoundDetour)
                    || lowerBoundDetour < 0
                    || cellId == null
                    || cellId.isBlank()
                    || canonicalRank < 0) {
                throw new IllegalArgumentException("invalid pivot metadata");
            }
        }
    }
}
