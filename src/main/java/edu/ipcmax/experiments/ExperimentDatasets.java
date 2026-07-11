package edu.ipcmax.experiments;

import java.util.List;

import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;

/** Small deterministic datasets bundled for infrastructure smoke tests. */
public final class ExperimentDatasets {
    private ExperimentDatasets() {
    }

    /** Four-node temporal graph with competing score/travel alternatives. */
    public static TDGraph demo() {
        PiecewiseConstFn scoreA = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 420, 0),
                new PiecewiseConstFn.Interval(420, 600, 8),
                new PiecewiseConstFn.Interval(600, 1440, 0)));
        PiecewiseConstFn scoreB = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 420, 0),
                new PiecewiseConstFn.Interval(420, 600, 5),
                new PiecewiseConstFn.Interval(600, 1440, 0)));
        return new TinyGraphBuilder()
                .node(1).node(2).node(3).node(4)
                .edge(1, 2, 10, scoreA)
                .edge(2, 4, 10, PiecewiseConstFn.zeroFullDay())
                .edge(1, 3, 5, scoreB)
                .edge(3, 4, 40, PiecewiseConstFn.zeroFullDay())
                .edge(1, 4, 25, PiecewiseConstFn.zeroFullDay())
                .build();
    }

    /** Dense acyclic graph used only to verify external timeout handling in infrastructure tests. */
    static TDGraph timeoutStress() {
        TinyGraphBuilder builder = new TinyGraphBuilder().node(1).node(4);
        for (int node = 5; node <= 23; node++) {
            builder.node(node);
        }
        for (int target = 5; target <= 23; target++) {
            builder.edge(1, target, 1);
        }
        for (int source = 5; source <= 23; source++) {
            for (int target = source + 1; target <= 23; target++) {
                builder.edge(source, target, 1);
            }
            builder.edge(source, 4, 1);
        }
        return builder.build();
    }
}
