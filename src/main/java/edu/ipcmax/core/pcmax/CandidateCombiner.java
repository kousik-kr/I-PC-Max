package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;

/**
 * Combines left candidate, pivot edge, and right candidate without waiting.
 */
public final class CandidateCombiner {
    private CandidateCombiner() {
    }

    /**
     * Combines candidates over their common valid domain.
     */
    public static CandidateProfile combine(CandidateProfile left, Edge edge, CandidateProfile right) {
        Domain domain = left.domain().intersection(right.domain());
        if (domain.isEmpty()) {
            throw new IllegalArgumentException("candidate combination domain is empty");
        }
        TimeProfile arrival = new TimeProfile(
                domain,
                t -> right.arrivalProfile().valueAt((int) Math.round(edge.travelTimeFunction().arrivalTimeAt(left.arrivalProfile().valueAt((int) t)))),
                "combined-arrival:" + left.pathPointer().arcIds() + ":" + edge.arcId() + ":" + right.pathPointer().arcIds());
        ScoreProfile score = new ScoreProfile(
                domain,
                t -> left.scoreProfile().valueAt(t)
                        + edge.scoreFunction().valueAt(left.arrivalProfile().valueAt(t))
                        + right.scoreProfile().valueAt(t),
                "combined-score:" + left.pathPointer().arcIds() + ":" + edge.arcId() + ":" + right.pathPointer().arcIds());
        return new CandidateProfile(
                domain,
                arrival,
                score,
                PathPointer.concat(left.pathPointer(), PathPointer.arc(edge.arcId()), right.pathPointer()),
                Math.max(left.recursionDepth(), right.recursionDepth()) + 1,
                edge.arcId(),
                false);
    }
}
