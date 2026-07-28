package edu.ipcmax.core.pcmax;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.EdgeTemporalSummaryStore;
import edu.ipcmax.core.index.EdgeTemporalSummaryStore.PositiveScoreInterval;
import edu.ipcmax.core.index.GraphPartitionMetadata;
import edu.ipcmax.core.index.ScoreSupportIndex;
import edu.ipcmax.core.index.ScoreSupportIndex.RankedScoreEdge;
import edu.ipcmax.core.pcmax.PivotIndex.Pivot;

/** Deterministic query-wide score-aware and spatially diverse pivot ranking. */
public final class PivotSelector {
    private PivotSelector() {
    }

    /**
     * Retrieves score-bearing corridor arcs through the cell index and returns
     * the first L pivots from one canonical diversity order.
     */
    public static PivotIndex select(
            TDGraph graph,
            QueryCorridor corridor,
            QueryLowerBounds lowerBounds,
            GraphPartitionMetadata partition,
            EdgeTemporalSummaryStore summaries,
            ScoreSupportIndex scoreIndex,
            Domain conservativeEntryTimes,
            int limit) {
        return select(
                graph,
                corridor,
                lowerBounds,
                partition,
                summaries,
                scoreIndex,
                conservativeEntryTimes,
                limit,
                true);
    }

    /** Selects pivots with an explicit diversification ablation switch. */
    public static PivotIndex select(
            TDGraph graph,
            QueryCorridor corridor,
            QueryLowerBounds lowerBounds,
            GraphPartitionMetadata partition,
            EdgeTemporalSummaryStore summaries,
            ScoreSupportIndex scoreIndex,
            Domain conservativeEntryTimes,
            int limit,
            boolean diversificationEnabled) {
        if (limit < 0) {
            throw new IllegalArgumentException("pivot limit cannot be negative");
        }
        QueryLowerBounds.Distances fromSource =
                lowerBounds.truncatedDistancesFrom(
                        corridor.source(), corridor.budget());
        QueryLowerBounds.Distances toDestination =
                lowerBounds.truncatedDistancesTo(
                        corridor.destination(), corridor.budget());
        double shortest = fromSource.distance(corridor.destination());
        LinkedHashSet<Integer> relevantIds = new LinkedHashSet<>();
        Map<Integer, Integer> rangeMaximum = new HashMap<>();
        for (String cellId : corridor.activeCellIds()) {
            int requested = scoreIndex.scoreBearingArcIds(cellId).size();
            if (requested == 0) {
                continue;
            }
            for (RankedScoreEdge ranked :
                    scoreIndex.topK(cellId, conservativeEntryTimes, requested)) {
                if (corridor.containsArc(ranked.arcId())) {
                    relevantIds.add(ranked.arcId());
                    rangeMaximum.put(
                            ranked.arcId(), ranked.maximumScore());
                }
            }
        }
        List<Integer> scoreRelevant =
                relevantIds.stream().sorted().toList();
        TreeMap<String, List<PivotFeatures>> byCell = new TreeMap<>();
        for (int arcId : scoreRelevant) {
            Edge edge = graph.edges().get(arcId);
            String cellId =
                    partition.cellForVertex(edge.source()).cellId();
            double coverage = coverage(
                    summaries.summary(arcId).positiveScoreIntervals(),
                    conservativeEntryTimes);
            double detour = Domain.canonicalTime(Math.max(
                    0,
                    fromSource.distance(edge.source())
                            + lowerBounds.edgeWeight(arcId)
                            + toDestination.distance(edge.target())
                            - shortest));
            byCell.computeIfAbsent(cellId, ignored -> new ArrayList<>())
                    .add(new PivotFeatures(
                            arcId,
                            edge.source(),
                            edge.target(),
                            rangeMaximum.get(arcId),
                            coverage,
                            detour,
                            cellId));
        }
        Comparator<PivotFeatures> withinCell = Comparator
                .comparingInt(PivotFeatures::maximumScore).reversed()
                .thenComparing(
                        Comparator.comparingDouble(
                                PivotFeatures::coverage).reversed())
                .thenComparingDouble(PivotFeatures::detour)
                .thenComparingInt(PivotFeatures::arcId);
        byCell.values().forEach(values -> values.sort(withinCell));

        List<String> cellOrder = byCell.entrySet().stream()
                .sorted((left, right) -> {
                    int comparison = withinCell.compare(
                            left.getValue().get(0),
                            right.getValue().get(0));
                    return comparison != 0
                            ? comparison
                            : left.getKey().compareTo(right.getKey());
                })
                .map(Map.Entry::getKey)
                .toList();
        List<PivotFeatures> canonical = new ArrayList<>();
        if (diversificationEnabled) {
            for (int round = 0;
                    canonical.size() < scoreRelevant.size();
                    round++) {
                boolean added = false;
                for (String cell : cellOrder) {
                    List<PivotFeatures> values = byCell.get(cell);
                    if (round < values.size()) {
                        canonical.add(values.get(round));
                        added = true;
                    }
                }
                if (!added) {
                    break;
                }
            }
        } else {
            byCell.values().forEach(canonical::addAll);
            canonical.sort(withinCell.thenComparing(
                    PivotFeatures::cellId));
        }
        int retained = Math.min(limit, canonical.size());
        List<Pivot> selected = new ArrayList<>(retained);
        for (int rank = 0; rank < retained; rank++) {
            PivotFeatures value = canonical.get(rank);
            selected.add(new Pivot(
                    value.arcId(),
                    value.source(),
                    value.target(),
                    value.maximumScore(),
                    value.coverage(),
                    value.detour(),
                    value.cellId(),
                    rank));
        }
        return new PivotIndex(
                selected,
                scoreRelevant,
                version(
                        corridor.checksum(),
                        canonical,
                        diversificationEnabled));
    }

    private static double coverage(
            List<PositiveScoreInterval> intervals,
            Domain requested) {
        double result = 0;
        for (PositiveScoreInterval interval : intervals) {
            Domain support = interval.startMinute() == interval.endMinute()
                    ? Domain.closed(
                            interval.startMinute(), interval.endMinute())
                    : Domain.halfOpen(
                            interval.startMinute(), interval.endMinute());
            Domain overlap = support.intersection(requested);
            for (Domain.Interval component : overlap.intervals()) {
                result += Math.max(
                        0, component.end() - component.start());
            }
        }
        return Domain.canonicalTime(result);
    }

    private static String version(
            String corridorChecksum,
            List<PivotFeatures> canonical,
            boolean diversificationEnabled) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
        update(digest, "PACE-PIVOT-ORDER-v1");
        update(digest, corridorChecksum);
        update(
                digest,
                diversificationEnabled
                        ? "DIVERSIFIED"
                        : "GLOBAL_RANK");
        for (PivotFeatures pivot : canonical) {
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(pivot.arcId()).array());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes.length).array());
        digest.update(bytes);
    }

    private record PivotFeatures(
            int arcId,
            int source,
            int target,
            int maximumScore,
            double coverage,
            double detour,
            String cellId) {
    }
}
