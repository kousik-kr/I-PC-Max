package edu.ipcmax.experiments;

import edu.ipcmax.experiments.algorithms.ExhaustiveProfileAlgorithm;
import edu.ipcmax.experiments.algorithms.IntervalBestAlgorithm;
import edu.ipcmax.experiments.algorithms.KspProfileAlgorithm;
import edu.ipcmax.experiments.algorithms.PaceExperimentAlgorithm;
import edu.ipcmax.experiments.algorithms.ProfileLabelingAlgorithm;
import edu.ipcmax.experiments.algorithms.RpqAlgorithm;
import edu.ipcmax.experiments.framework.ExperimentAlgorithm;

/** Stable candidate identifier registry. */
public final class AlgorithmRegistry {
    private AlgorithmRegistry() {
    }

    public static ExperimentAlgorithm create(String id) {
        return switch (id) {
            case "pace-x", "pace-b" -> new PaceExperimentAlgorithm(id);
            case "exh-profile" -> new ExhaustiveProfileAlgorithm();
            case "pl-exact" -> new ProfileLabelingAlgorithm();
            case "rpq" -> new RpqAlgorithm();
            case "ksp-profile" -> new KspProfileAlgorithm();
            case "interval-best" -> new IntervalBestAlgorithm();
            default -> throw new IllegalArgumentException("unknown --algorithm: " + id);
        };
    }
}
