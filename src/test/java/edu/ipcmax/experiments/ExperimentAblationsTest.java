package edu.ipcmax.experiments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.pcmax.AnchorIndex;
import edu.ipcmax.core.pcmax.PACE;
import edu.ipcmax.core.pcmax.PaceOptions;
import edu.ipcmax.core.pcmax.QueryLowerBounds;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.experiments.framework.Ablation;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.ProfileSupport;

class ExperimentAblationsTest {
    @Test
    void everyAblationMapsToOneSharedPaceConfiguration() {
        assertEquals(0, options(Ablation.NO_ANCHOR).theta());
        assertFalse(options(Ablation.NO_SAFE_DOM).features().safeDominanceEnabled());
        assertFalse(options(Ablation.NO_MEMO).memoizationEnabled());
        assertFalse(options(Ablation.GLOBAL_K).features().perCellRetentionEnabled());
        assertFalse(options(Ablation.RANK_ONLY).features().representativeRetentionEnabled());
        assertEquals(1, options(Ablation.SERIAL).threadCount());
        assertEquals(PaceOptions.UNBOUNDED, options(Ablation.ALL_ANCHORS).anchorLimit());
        assertFalse(options(Ablation.NO_ANCHOR_LB).features().anchorLowerBoundFilterEnabled());
        assertFalse(options(Ablation.NO_COMPRESSION).features().compressionEnabled());
        assertFalse(options(Ablation.NO_COMPRESSION).features().safeDominanceEnabled());
        assertFalse(options(Ablation.NO_COMPRESSION).features().adjacentMergeEnabled());
        assertFalse(options(Ablation.NO_MERGE).features().adjacentMergeEnabled());
        assertTrue(options(Ablation.NO_MERGE).features().compressionEnabled());
    }

    @Test
    void serialAndThreadedPaceBHaveIdenticalSelectedProfile() {
        var graph = ExperimentDatasets.demo();
        QuerySpec query = new QuerySpec(1, 4, 420, 430, 60, 1);
        String serial = ProfileSupport.checksum(new PACE(graph, options(Ablation.SERIAL)).run(query));
        String threaded = ProfileSupport.checksum(new PACE(graph, options(Ablation.NONE)).run(query));
        assertEquals(serial, threaded);
    }

    @Test
    void noAnchorLowerBoundKeepsBudgetRejectedAnchorsEligibleForRanking() {
        var graph = ExperimentDatasets.demo();
        Domain horizon = Domain.closed(420, 440);
        AnchorIndex index = AnchorIndex.create(graph, horizon);
        QueryLowerBounds lower = new QueryLowerBounds(graph, horizon);
        assertTrue(index.relevantAnchors(1, 4, Domain.closed(420, 430), 10,
                lower, options(Ablation.NONE)).isEmpty());
        assertEquals(2, index.relevantAnchors(1, 4, Domain.closed(420, 430), 10,
                lower, options(Ablation.NO_ANCHOR_LB)).size());
    }

    private static PaceOptions options(Ablation ablation) {
        return new AlgorithmConfig("pace-b", ablation, 2, 2, 2, 4,
                0, 0, 100, 100, 100, 100_000, true, 42).paceOptions();
    }
}
