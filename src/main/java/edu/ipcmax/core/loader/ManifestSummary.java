package edu.ipcmax.core.loader;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import edu.ipcmax.core.function.Domain;

/**
 * Generated-dataset manifest fields used by loading and query construction.
 */
public record ManifestSummary(
        int numNodes,
        int numArcs,
        long seed,
        int selectedScoreEdgeCount,
        boolean unlistedEdgesHaveScoreZero,
        Optional<TimeWindow> temporalSupport,
        Map<String, TimeWindow> rushWindows) {
    public ManifestSummary {
        temporalSupport = temporalSupport == null ? Optional.empty() : temporalSupport;
        TreeMap<String, TimeWindow> normalizedRushWindows = new TreeMap<>();
        if (rushWindows != null) {
            rushWindows.forEach((name, window) -> {
                if (name == null || name.isBlank() || window == null) {
                    throw new IllegalArgumentException("rush-window names and values are required");
                }
                String normalized = name.trim().toLowerCase(Locale.ROOT);
                if (normalizedRushWindows.put(normalized, window) != null) {
                    throw new IllegalArgumentException("duplicate rush-window name: " + normalized);
                }
            });
        }
        rushWindows = Collections.unmodifiableMap(normalizedRushWindows);
    }

    /** Compatibility constructor for manifests without temporal metadata. */
    public ManifestSummary(
            int numNodes,
            int numArcs,
            long seed,
            int selectedScoreEdgeCount,
            boolean unlistedEdgesHaveScoreZero) {
        this(numNodes, numArcs, seed, selectedScoreEdgeCount,
                unlistedEdgesHaveScoreZero, Optional.empty(), Map.of());
    }

    /** Named rush period, matched case-insensitively. */
    public Optional<TimeWindow> rushWindow(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(rushWindows.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    /** Closed temporal interval in dataset minutes. */
    public record TimeWindow(double startMinute, double endMinute) {
        public TimeWindow {
            if (!Double.isFinite(startMinute) || !Double.isFinite(endMinute)) {
                throw new IllegalArgumentException("manifest time-window endpoints must be finite");
            }
            startMinute = Domain.canonicalTime(startMinute);
            endMinute = Domain.canonicalTime(endMinute);
            if (endMinute < startMinute) {
                throw new IllegalArgumentException("manifest time-window endpoints are reversed");
            }
        }
    }
}
