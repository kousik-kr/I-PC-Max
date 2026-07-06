package edu.ipcmax.core.pcmax;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;
import edu.ipcmax.core.validate.LooplessChecker;
import edu.ipcmax.core.validate.Path;

/**
 * PACE temporal stitching for a left candidate, score anchor, and right candidate.
 */
public final class TemporalStitch {
    private static final double EPSILON = 1e-9;

    private TemporalStitch() {
    }

    /**
     * Stitches {@code C_L}, anchor {@code a=(x,y)}, and {@code C_R} over the root domain.
     */
    public static Optional<CandidateProfile> stitch(
            TDGraph graph,
            CandidateProfile left,
            Edge anchor,
            CandidateProfile right,
            Domain rootDomain,
            double budget) {
        PathPointer pathPointer = PathPointer.concat(left.pathPointer(), PathPointer.arc(anchor.arcId()), right.pathPointer());
        if (!LooplessChecker.isLoopless(graph, new Path(pathPointer.arcIds()))) {
            return Optional.empty();
        }

        Domain base = rootDomain.intersection(left.domain());
        Domain stitchedDomain = feasibleDomain(left, anchor, right, base, budget);
        if (stitchedDomain.isEmpty()) {
            return Optional.empty();
        }

        TimeProfile anchorArrival = edgeArrivalProfile(anchor);
        TimeProfile afterAnchor = left.arrivalProfile().compose(anchorArrival, "temporal-stitch-anchor-arrival:" + left.pathPointer().arcIds() + ":" + anchor.arcId());
        TimeProfile stitchedArrival = afterAnchor.compose(right.arrivalProfile(), "temporal-stitch-arrival:" + left.pathPointer().arcIds() + ":" + anchor.arcId()
            + ":" + right.pathPointer().arcIds() + ":" + stitchedDomain.intervals());

        ScoreProfile anchorScore = ScoreProfile.compose(left.arrivalProfile(), anchor.scoreFunction(), base, "temporal-stitch-anchor-score:" + left.pathPointer().arcIds() + ":" + anchor.arcId());
        ScoreProfile rightScore = right.scoreProfile().compose(afterAnchor, "temporal-stitch-right-score:" + right.pathPointer().arcIds() + ":" + anchor.arcId());
        ScoreProfile stitchedScore = left.scoreProfile()
            .add(anchorScore, base, "temporal-stitch-score-left-anchor:" + left.pathPointer().arcIds() + ":" + anchor.arcId())
            .add(rightScore, base, "temporal-stitch-score:" + left.pathPointer().arcIds() + ":" + anchor.arcId()
                + ":" + right.pathPointer().arcIds() + ":" + stitchedDomain.intervals());

        return Optional.of(new CandidateProfile(
                stitchedDomain,
            stitchedArrival.restrict(stitchedDomain),
            stitchedScore.restrict(stitchedDomain),
                pathPointer,
                Math.max(left.recursionDepth(), right.recursionDepth()) + 1,
                anchor.arcId(),
                false));
    }

    private static Domain feasibleDomain(
            CandidateProfile left,
            Edge anchor,
            CandidateProfile right,
            Domain base,
            double budget) {
        TimeProfile anchorArrival = edgeArrivalProfile(anchor);
        Domain leftAnchorDomain = left.arrivalProfile().preimage(anchor.scoreFunction().positiveDomain(), base);
        if (leftAnchorDomain.isEmpty()) {
            return Domain.empty();
        }
        TimeProfile afterAnchor = left.arrivalProfile().compose(anchorArrival, "temporal-stitch-feasible-anchor:" + left.pathPointer().arcIds() + ":" + anchor.arcId());
        Domain rightFeasibleDomain = afterAnchor.preimage(right.domain(), leftAnchorDomain);
        if (rightFeasibleDomain.isEmpty()) {
            return Domain.empty();
        }
        TimeProfile stitchedArrival = afterAnchor.compose(right.arrivalProfile(), "temporal-stitch-feasible-arrival:" + left.pathPointer().arcIds() + ":" + anchor.arcId()
                + ":" + right.pathPointer().arcIds());
        return stitchedArrival.domainWhereTravelTimeAtMost(rightFeasibleDomain, budget);
    }

    private static TimeProfile edgeArrivalProfile(Edge edge) {
        List<TimeProfile.Breakpoint> breakpoints = new ArrayList<>();
        for (edu.ipcmax.core.function.PiecewiseLinearFn.Breakpoint breakpoint : edge.travelTimeFunction().breakpoints()) {
            breakpoints.add(new TimeProfile.Breakpoint(breakpoint.minute(), edge.travelTimeFunction().arrivalTimeAt(breakpoint.minute())));
        }
        return TimeProfile.piecewise(Domain.closed(edge.travelTimeFunction().firstMinute(), edge.travelTimeFunction().lastMinute()), breakpoints,
                "temporal-stitch-edge-arrival:" + edge.arcId());
    }
}
