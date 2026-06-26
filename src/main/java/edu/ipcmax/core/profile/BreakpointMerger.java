package edu.ipcmax.core.profile;

import edu.ipcmax.core.function.PiecewiseLinearFn;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic merge-breakpoint compression with error accounting.
 */
public final class BreakpointMerger {
    /**
     * Compression result.
     */
    public record Result(PiecewiseLinearFn function, double maxDelta) {
    }

    private BreakpointMerger() {
    }

    /**
     * Deterministically keeps endpoints and every nth internal breakpoint.
     */
    public static Result mergeEvery(PiecewiseLinearFn function, int step) {
        if (step <= 1 || function.breakpoints().size() <= 2) {
            return new Result(function, 0);
        }
        List<PiecewiseLinearFn.Breakpoint> kept = new ArrayList<>();
        List<PiecewiseLinearFn.Breakpoint> original = function.breakpoints();
        kept.add(original.get(0));
        for (int i = 1; i < original.size() - 1; i++) {
            if (i % step == 0) {
                kept.add(original.get(i));
            }
        }
        kept.add(original.get(original.size() - 1));
        PiecewiseLinearFn merged = new PiecewiseLinearFn(kept);
        double maxDelta = 0;
        for (PiecewiseLinearFn.Breakpoint point : original) {
            maxDelta = Math.max(maxDelta, Math.abs(function.travelTimeAt(point.minute()) - merged.travelTimeAt(point.minute())));
        }
        return new Result(merged, maxDelta);
    }
}
