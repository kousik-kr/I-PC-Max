package edu.ipcmax.core.labeling;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;
import edu.ipcmax.core.validate.ExactPathValidator;
import edu.ipcmax.core.validate.Path;
import edu.ipcmax.core.validate.ValidationResult;

/**
 * Exact discrete interval forward labeling wrapper around point forward labeling.
 */
public final class IntervalForwardLabeling {
    private final TDGraph graph;

    /**
     * Creates an interval forward labeler.
     */
    public IntervalForwardLabeling(TDGraph graph) {
        this.graph = graph;
    }

    /**
     * Computes fastest candidate profiles from source to target over a domain.
     */
    public CandidateSet fastestCandidates(int source, int target, Domain domain, double budget) {
        CandidateSet set = new CandidateSet();
        PointForwardLabeling labeler = new PointForwardLabeling(graph);
        ExactPathValidator validator = new ExactPathValidator(graph);
        for (int departure : domain) {
            PointForwardLabeling.Result labels = labeler.run(source, departure, budget);
            if (!labels.reached(target)) {
                continue;
            }
            Path path = labels.pathTo(target);
            ValidationResult validation = validator.validate(source, target, departure, budget, path);
            if (!validation.valid()) {
                continue;
            }
            Domain singleton = Domain.closed(departure, departure);
            set.add(new CandidateProfile(
                    singleton,
                    TimeProfile.constant(singleton, validation.arrivalTime()),
                    ScoreProfile.constant(singleton, validation.score()),
                    () -> path.arcIds(),
                    0,
                    -1,
                    false));
        }
        return set;
    }
}
