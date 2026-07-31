package edu.ipcmax.core.pcmax;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.EdgeTemporalSummaryStore;
import edu.ipcmax.core.index.GraphPartitionMetadata;
import edu.ipcmax.core.index.ScoreSupportIndex;
import edu.ipcmax.core.pcmax.PivotIndex.Pivot;

/**
 * Deterministic exact Top-L query-pivot ranking.
 *
 * <p>The canonical order is independent of {@code L}:
 * {@code (-Psi, -Gamma, Delta, cellId, arcId)}. {@code Psi} is the exact
 * maximum score on the positive feasible entry domain, {@code Gamma} is the
 * exact measure of that domain, and {@code Delta} is the normalized
 * lower-bound detour through the directed arc. This is the only production
 * pivot order; the historical diversification switch is retained solely for
 * configuration compatibility and cannot change the scientific result.</p>
 */
public final class PivotSelector {
    private PivotSelector() {
    }

    /**
     * Retrieves score-bearing corridor arcs through the prepared score index
     * and returns the first L pivots from the exact canonical order.
     */
    public static PivotIndex select(
            TDGraph graph,
            QueryCorridor corridor,
            QueryLowerBounds lowerBounds,
            GraphPartitionMetadata partition,
            EdgeTemporalSummaryStore summaries,
            ScoreSupportIndex scoreIndex,
            Domain graphFunctionHorizon,
            int limit) {
        return select(
                graph,
                corridor,
                lowerBounds,
                partition,
                summaries,
                scoreIndex,
                graphFunctionHorizon,
                limit,
                true,
                PaceExecutionMetrics.none());
    }

    /**
     * Selects pivots with the historical diversification argument retained for
     * source compatibility. The exact Top-L contract deliberately ignores the
     * argument.
     */
    public static PivotIndex select(
            TDGraph graph,
            QueryCorridor corridor,
            QueryLowerBounds lowerBounds,
            GraphPartitionMetadata partition,
            EdgeTemporalSummaryStore summaries,
            ScoreSupportIndex scoreIndex,
            Domain graphFunctionHorizon,
            int limit,
            boolean diversificationEnabled) {
        return select(
                graph,
                corridor,
                lowerBounds,
                partition,
                summaries,
                scoreIndex,
                graphFunctionHorizon,
                limit,
                diversificationEnabled,
                PaceExecutionMetrics.none());
    }

    /** Selects pivots while recording preparation work and phase timings. */
    public static PivotIndex select(
            TDGraph graph,
            QueryCorridor corridor,
            QueryLowerBounds lowerBounds,
            GraphPartitionMetadata partition,
            EdgeTemporalSummaryStore summaries,
            ScoreSupportIndex scoreIndex,
            Domain graphFunctionHorizon,
            int limit,
            boolean diversificationEnabled,
            PaceExecutionMetrics metrics) {
        if (limit < 0) {
            throw new IllegalArgumentException(
                    "pivot limit cannot be negative");
        }
        QueryLowerBounds.Distances fromSource =
                lowerBounds.truncatedDistancesFrom(
                        corridor.source(), corridor.budget());
        QueryLowerBounds.Distances toDestination =
                lowerBounds.truncatedDistancesTo(
                        corridor.destination(), corridor.budget());
        return select(
                graph,
                corridor,
                lowerBounds,
                partition,
                summaries,
                scoreIndex,
                graphFunctionHorizon,
                limit,
                diversificationEnabled,
                metrics,
                fromSource,
                toDestination);
    }

    /**
     * Selects pivots using the forward and backward query labels already
     * constructed for corridor assembly.
     */
    static PivotIndex select(
            TDGraph graph,
            QueryCorridor corridor,
            QueryLowerBounds lowerBounds,
            GraphPartitionMetadata partition,
            EdgeTemporalSummaryStore summaries,
            ScoreSupportIndex scoreIndex,
            Domain graphFunctionHorizon,
            int limit,
            boolean diversificationEnabled,
            PaceExecutionMetrics metrics,
            QueryLowerBounds.Distances fromSource,
            QueryLowerBounds.Distances toDestination) {
        if (limit < 0) {
            throw new IllegalArgumentException(
                    "pivot limit cannot be negative");
        }
        java.util.Objects.requireNonNull(
                fromSource, "fromSource");
        java.util.Objects.requireNonNull(
                toDestination, "toDestination");
        LinkedHashSet<Integer> indexedScoreArcs =
                new LinkedHashSet<>();
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.SCORE_SUPPORT_LOOKUP)) {
            for (String indexCellId : corridor.activeCellIds()) {
                for (int arcId :
                        scoreIndex.scoreBearingArcIds(indexCellId)) {
                    if (corridor.containsArc(arcId)) {
                        indexedScoreArcs.add(arcId);
                    }
                }
            }
        }

        /*
         * E_q^+ is exactly the score-bearing subset returned by the validated
         * score-support index.  Feasible-entry bands are only meaningful for
         * that subset; computing them for every corridor arc made the USA
         * query spend most of its preparation time on arcs that could never
         * become pivots.  The indexed set is still intersected with the
         * corridor above, so this is a safe reduction rather than a ranking
         * approximation.
         */
        Map<Integer, Domain> feasibleBands = new HashMap<>();
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.FEASIBLE_ENTRY_BANDS)) {
            for (int arcId : indexedScoreArcs) {
                Edge edge = graph.edges().get(arcId);
                Domain band = QueryFeasibleEntryDomain.compute(
                        corridor,
                        lowerBounds,
                        fromSource,
                        toDestination,
                        edge,
                        graphFunctionHorizon);
                feasibleBands.put(arcId, band);
                metrics.increment("feasible_entry_bands");
                metrics.increment("feasible_entry_band_score_arcs");
                if (band.isEmpty()) {
                    metrics.increment("empty_feasible_entry_bands");
                }
            }
        }

        Map<Integer, ScoreFeatures> scoreFeatures = new HashMap<>();
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.SCORE_SUPPORT_LOOKUP)) {
            for (int arcId : indexedScoreArcs) {
                metrics.increment("score_support_edges_examined");
                Domain band = feasibleBands.getOrDefault(
                        arcId, Domain.empty());
                if (band.isEmpty()) {
                    continue;
                }
                Edge edge = graph.edges().get(arcId);
                Domain positive = edge.scoreFunction()
                        .positiveDomain().intersection(band);
                if (positive.isEmpty()) {
                    continue;
                }
                scoreFeatures.put(
                        arcId,
                        new ScoreFeatures(
                                edge.scoreFunction().maxValue(positive),
                                measure(positive),
                                positive.toString()));
            }
        }
        List<Integer> scoreRelevant =
                scoreFeatures.keySet().stream().sorted().toList();

        List<PivotFeatures> canonical = new ArrayList<>();
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.PIVOT_RANKING)) {
            double shortestLowerBound =
                    fromSource.distance(corridor.destination());
            for (int arcId : scoreRelevant) {
                Edge edge = graph.edges().get(arcId);
                ScoreFeatures score = scoreFeatures.get(arcId);
                double throughEdgeLowerBound = Domain.canonicalTime(
                        fromSource.distance(edge.source())
                                + lowerBounds.edgeWeight(arcId)
                                + toDestination.distance(edge.target()));
                double normalizedDetour = normalizedDetour(
                        throughEdgeLowerBound, shortestLowerBound);
                canonical.add(new PivotFeatures(
                        arcId,
                        edge.source(),
                        edge.target(),
                        score.maximumScore(),
                        score.coverage(),
                        normalizedDetour,
                        partition.cellForVertex(
                                edge.source()).cellId(),
                        score.bandFingerprint()));
            }
            canonical.sort(Comparator
                    .comparingInt(
                            PivotFeatures::maximumScore).reversed()
                    .thenComparing(
                            Comparator.comparingDouble(
                                    PivotFeatures::coverage).reversed())
                    .thenComparing(
                            PivotFeatures::normalizedDetour)
                    .thenComparing(PivotFeatures::cellId)
                    .thenComparingInt(PivotFeatures::arcId));
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
                    value.normalizedDetour(),
                    value.cellId(),
                    rank));
        }
        return new PivotIndex(
                selected,
                scoreRelevant,
                version(
                        corridor.checksum(),
                        canonical));
    }

    private static double normalizedDetour(
            double throughEdgeLowerBound,
            double shortestLowerBound) {
        if (!Double.isFinite(throughEdgeLowerBound)
                || !Double.isFinite(shortestLowerBound)
                || throughEdgeLowerBound < 0
                || shortestLowerBound < 0) {
            throw new IllegalArgumentException(
                    "pivot detour inputs must be finite and nonnegative");
        }
        double excess = Math.max(
                0, throughEdgeLowerBound - shortestLowerBound);
        if (Domain.sameTime(shortestLowerBound, 0)) {
            return Domain.canonicalTime(excess);
        }
        return Domain.canonicalTime(excess / shortestLowerBound);
    }

    private static double measure(Domain domain) {
        double result = 0;
        for (Domain.Interval interval : domain.intervals()) {
            result += Math.max(0, interval.end() - interval.start());
        }
        return Domain.canonicalTime(result);
    }

    private static String version(
            String corridorChecksum,
            List<PivotFeatures> canonical) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", failure);
        }
        update(digest, "PACE-EXACT-TOP-L-v1");
        update(digest, corridorChecksum);
        for (PivotFeatures pivot : canonical) {
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(pivot.arcId()).array());
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(pivot.maximumScore()).array());
            digest.update(ByteBuffer.allocate(Long.BYTES)
                    .putLong(Double.doubleToLongBits(
                            pivot.coverage())).array());
            digest.update(ByteBuffer.allocate(Long.BYTES)
                    .putLong(Double.doubleToLongBits(
                            pivot.normalizedDetour())).array());
            update(digest, pivot.cellId());
            update(digest, pivot.bandFingerprint());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(
            MessageDigest digest,
            String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes.length).array());
        digest.update(bytes);
    }

    private record ScoreFeatures(
            int maximumScore,
            double coverage,
            String bandFingerprint) {
    }

    private record PivotFeatures(
            int arcId,
            int source,
            int target,
            int maximumScore,
            double coverage,
            double normalizedDetour,
            String cellId,
            String bandFingerprint) {
    }
}
