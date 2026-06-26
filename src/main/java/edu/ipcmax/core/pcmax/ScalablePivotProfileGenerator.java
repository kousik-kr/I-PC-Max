package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic sampled pivot generator for scalable experiments.
 */
public final class ScalablePivotProfileGenerator {
    /**
     * Samples pivots at the configured step while preserving boundary pivots.
     */
    public List<PivotProfile> generate(Domain domain, int lowerOffset, int upperOffset, int step) {
        if (step < 1) {
            throw new IllegalArgumentException("pivot step must be positive");
        }
        List<PivotProfile> exact = new ExactPivotProfileGenerator().generate(domain, lowerOffset, upperOffset);
        List<PivotProfile> sampled = new ArrayList<>();
        for (PivotProfile pivot : exact) {
            if (pivot.offset() == lowerOffset || pivot.offset() == upperOffset || (pivot.offset() - lowerOffset) % step == 0) {
                sampled.add(pivot);
            }
        }
        return List.copyOf(sampled);
    }
}
