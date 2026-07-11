package edu.ipcmax.experiments.framework;

import java.util.Map;

import edu.ipcmax.core.pcmax.EnvelopeProfile;

/** Common result returned by every experiment-facing algorithm. */
public record AlgorithmResult(
        ExperimentStatus status,
        EnvelopeProfile profile,
        boolean completeProfile,
        Map<String, Object> scalars,
        String errorType,
        String errorMessage) {
    public AlgorithmResult {
        scalars = scalars == null ? Map.of() : Map.copyOf(scalars);
    }

    public static AlgorithmResult profile(EnvelopeProfile profile, boolean exactClaim) {
        boolean feasible = profile.segments().stream().anyMatch(segment -> segment.found());
        return new AlgorithmResult(
                feasible ? ExperimentStatus.COMPLETED : ExperimentStatus.NO_FEASIBLE_PATH,
                profile, exactClaim, Map.of(), null, null);
    }
}
