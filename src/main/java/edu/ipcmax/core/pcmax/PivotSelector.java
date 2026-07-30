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
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.EdgeTemporalSummaryStore;
import edu.ipcmax.core.index.GraphPartitionMetadata;
import edu.ipcmax.core.index.ScoreSupportIndex;
import edu.ipcmax.core.pcmax.PivotIndex.Pivot;

/** Deterministic query-wide score-aware and coordinate-diverse pivot ranking. */
public final class PivotSelector {
    private PivotSelector() {
    }

    /**
     * Retrieves score-bearing corridor arcs through the prepared score index and
     * returns the first L pivots from one canonical coordinate-grid order.
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

    /** Selects pivots with an explicit diversification ablation switch. */
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

        Map<Integer, Domain> feasibleBands = new HashMap<>();
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.FEASIBLE_ENTRY_BANDS)) {
            for (int arcId : corridor.directedArcIds()) {
                Edge edge = graph.edges().get(arcId);
                Domain band = FeasibleEntryBand.compute(
                        corridor,
                        lowerBounds,
                        fromSource,
                        toDestination,
                        edge,
                        graphFunctionHorizon);
                feasibleBands.put(arcId, band);
                metrics.increment("feasible_entry_bands");
                if (band.isEmpty()) {
                    metrics.increment("empty_feasible_entry_bands");
                }
            }
        }

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
                                edge.scoreFunction().maxValue(band),
                                measure(positive),
                                band.toString()));
            }
        }
        List<Integer> scoreRelevant =
                scoreFeatures.keySet().stream().sorted().toList();

        List<PivotFeatures> canonical;
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.PIVOT_RANKING)) {
            Grid grid = Grid.forCorridor(
                    graph, corridor, Math.max(1, limit));
            TreeMap<GridCell, List<PivotFeatures>> byCell =
                    new TreeMap<>();
            for (int arcId : scoreRelevant) {
                Edge edge = graph.edges().get(arcId);
                ScoreFeatures score = scoreFeatures.get(arcId);
                double corridorBound = Domain.canonicalTime(
                        fromSource.distance(edge.source())
                                + lowerBounds.edgeWeight(arcId)
                                + toDestination.distance(edge.target()));
                double budgetSlack = Domain.canonicalTime(Math.max(
                        0, corridor.budget() - corridorBound));
                GridCell cell = grid.cellForEdge(graph, edge);
                byCell.computeIfAbsent(
                                cell, ignoredCell -> new ArrayList<>())
                        .add(new PivotFeatures(
                                arcId,
                                edge.source(),
                                edge.target(),
                                score.maximumScore(),
                                score.coverage(),
                                budgetSlack,
                                cell.stableId(),
                                score.bandFingerprint()));
            }
            Comparator<PivotFeatures> withinCell = Comparator
                    .comparingInt(
                            PivotFeatures::maximumScore).reversed()
                    .thenComparing(
                            Comparator.comparingDouble(
                                    PivotFeatures::coverage).reversed())
                    .thenComparing(
                            Comparator.comparingDouble(
                                    PivotFeatures::budgetSlack).reversed())
                    .thenComparingInt(PivotFeatures::arcId);
            byCell.values().forEach(
                    values -> values.sort(withinCell));

            canonical = new ArrayList<>();
            if (diversificationEnabled) {
                for (int round = 0;
                        canonical.size() < scoreRelevant.size();
                        round++) {
                    boolean added = false;
                    for (List<PivotFeatures> values :
                            byCell.values()) {
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
                    value.budgetSlack(),
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

    private static double measure(Domain domain) {
        double result = 0;
        for (Domain.Interval interval : domain.intervals()) {
            result += Math.max(0, interval.end() - interval.start());
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
            throw new IllegalStateException(
                    "SHA-256 is unavailable", failure);
        }
        update(digest, "PACE-PIVOT-ORDER-v2");
        update(digest, corridorChecksum);
        update(
                digest,
                diversificationEnabled
                        ? "COORDINATE_GRID"
                        : "GLOBAL_RANK");
        for (PivotFeatures pivot : canonical) {
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(pivot.arcId()).array());
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
            double budgetSlack,
            String cellId,
            String bandFingerprint) {
    }

    private record Grid(
            int side,
            double minimumX,
            double minimumY,
            double maximumX,
            double maximumY) {
        static Grid forCorridor(
                TDGraph graph,
                QueryCorridor corridor,
                int limit) {
            int side = Math.max(
                    1, (int) Math.ceil(Math.sqrt(limit)));
            double minimumX = Double.POSITIVE_INFINITY;
            double minimumY = Double.POSITIVE_INFINITY;
            double maximumX = Double.NEGATIVE_INFINITY;
            double maximumY = Double.NEGATIVE_INFINITY;
            for (int vertex : corridor.vertexIds()) {
                Node node = graph.node(vertex);
                minimumX = Math.min(minimumX, node.x());
                minimumY = Math.min(minimumY, node.y());
                maximumX = Math.max(maximumX, node.x());
                maximumY = Math.max(maximumY, node.y());
            }
            if (!Double.isFinite(minimumX)) {
                minimumX = minimumY = maximumX = maximumY = 0;
            }
            return new Grid(
                    side,
                    minimumX,
                    minimumY,
                    maximumX,
                    maximumY);
        }

        GridCell cellForEdge(TDGraph graph, Edge edge) {
            Node source = graph.node(edge.source());
            Node target = graph.node(edge.target());
            double midpointX = source.x() / 2.0 + target.x() / 2.0;
            double midpointY = source.y() / 2.0 + target.y() / 2.0;
            return new GridCell(
                    coordinate(
                            midpointY, minimumY, maximumY, side),
                    coordinate(
                            midpointX, minimumX, maximumX, side));
        }

        private static int coordinate(
                double value,
                double minimum,
                double maximum,
                int side) {
            /*
             * Coordinates are DIMACS integer geometry, not temporal values.
             * Do not pass them through the signed 10^-12-minute tick contract:
             * real road coordinates can be much larger than its temporal
             * range. The extrema originate from the same long-valued node
             * coordinates, so direct equality is exact here.
             */
            if (side == 1 || minimum == maximum) {
                return 0;
            }
            if (value >= maximum) {
                return side - 1;
            }
            double normalized =
                    (value - minimum) / (maximum - minimum);
            int result = (int) Math.floor(normalized * side);
            return Math.max(0, Math.min(side - 1, result));
        }
    }

    private record GridCell(int row, int column)
            implements Comparable<GridCell> {
        String stableId() {
            return String.format(
                    java.util.Locale.ROOT,
                    "GRID-R%05d-C%05d",
                    row,
                    column);
        }

        @Override
        public int compareTo(GridCell other) {
            int byRow = Integer.compare(row, other.row);
            return byRow != 0
                    ? byRow
                    : Integer.compare(column, other.column);
        }
    }
}
