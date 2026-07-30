package edu.ipcmax.experiments.querygen;

import edu.ipcmax.core.function.Domain;

import java.util.Comparator;
import java.util.List;

/**
 * Deterministically measured source/destination candidate before query expansion.
 * {@code sampledSourceIndex} is the zero-based position in the sampler's source draw order.
 */
public record QueryPairCandidate(
        String datasetId,
        int source,
        int destination,
        double lowerBoundDistance,
        int lowerBoundEdgeCount,
        int corridorAnchorCount,
        int sampledSourceIndex,
        long temporalFunctionComplexity,
        List<Integer> lowerBoundWitnessArcIds) {
    /** Stable ordering used for candidate-pool truncation, binning, and reporting. */
    public static final Comparator<QueryPairCandidate> CANONICAL_ORDER = Comparator
            .comparingDouble(QueryPairCandidate::lowerBoundDistance)
            .thenComparingInt(QueryPairCandidate::source)
            .thenComparingInt(QueryPairCandidate::destination);

    public QueryPairCandidate {
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("candidate dataset id is required");
        }
        datasetId = datasetId.trim().toUpperCase(java.util.Locale.ROOT);
        if (source <= 0 || destination <= 0 || source == destination) {
            throw new IllegalArgumentException("candidate endpoints must be distinct positive node ids");
        }
        if (!Double.isFinite(lowerBoundDistance) || lowerBoundDistance < 0) {
            throw new IllegalArgumentException("candidate lower-bound distance must be finite and nonnegative");
        }
        lowerBoundDistance = Domain.canonicalTime(lowerBoundDistance);
        if (lowerBoundEdgeCount < 0 || corridorAnchorCount < 0
                || temporalFunctionComplexity < 0) {
            throw new IllegalArgumentException(
                    "candidate edge, anchor, and temporal-complexity counts cannot be negative");
        }
        if (sampledSourceIndex < 0) {
            throw new IllegalArgumentException("sampled-source index cannot be negative");
        }
        lowerBoundWitnessArcIds = List.copyOf(lowerBoundWitnessArcIds);
        if (!lowerBoundWitnessArcIds.isEmpty()
                && lowerBoundWitnessArcIds.size() != lowerBoundEdgeCount) {
            throw new IllegalArgumentException(
                    "lower-bound witness size must equal edge count");
        }
        if (lowerBoundWitnessArcIds.stream().anyMatch(arcId -> arcId < 0)) {
            throw new IllegalArgumentException(
                    "lower-bound witness arc IDs cannot be negative");
        }
    }

    /** Source-compatible constructor without a retained witness. */
    public QueryPairCandidate(
            String datasetId,
            int source,
            int destination,
            double lowerBoundDistance,
            int lowerBoundEdgeCount,
            int corridorAnchorCount,
            int sampledSourceIndex,
            long temporalFunctionComplexity) {
        this(datasetId, source, destination, lowerBoundDistance,
                lowerBoundEdgeCount, corridorAnchorCount,
                sampledSourceIndex, temporalFunctionComplexity, List.of());
    }

    /**
     * Source-compatible constructor for callers that do not yet track temporal-function
     * complexity.
     */
    public QueryPairCandidate(
            String datasetId,
            int source,
            int destination,
            double lowerBoundDistance,
            int lowerBoundEdgeCount,
            int corridorAnchorCount,
            int sampledSourceIndex) {
        this(datasetId, source, destination, lowerBoundDistance, lowerBoundEdgeCount,
                corridorAnchorCount, sampledSourceIndex, 0, List.of());
    }

    /** Phase-2-compatible constructor for callers that do not yet track source sampling. */
    public QueryPairCandidate(
            String datasetId,
            int source,
            int destination,
            double lowerBoundDistance,
            int lowerBoundEdgeCount,
            int corridorAnchorCount) {
        this(datasetId, source, destination, lowerBoundDistance, lowerBoundEdgeCount,
                corridorAnchorCount, 0, 0, List.of());
    }

    /** Stable ID shared by every query derived from this pair. */
    public String pairFamilyId() {
        return QueryFamily.formatPairFamilyId(datasetId, source, destination);
    }
}
