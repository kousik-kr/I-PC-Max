package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.profile.TimeProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact discrete pivot profile generator.
 */
public final class ExactPivotProfileGenerator {
    /**
     * Enumerates every integer offset between inclusive lower and upper bounds.
     */
    public List<PivotProfile> generate(Domain domain, int lowerOffset, int upperOffset) {
        List<PivotProfile> pivots = new ArrayList<>();
        for (int offset = lowerOffset; offset <= upperOffset; offset++) {
            int pivotOffset = offset;
            pivots.add(new PivotProfile(
                    pivotOffset,
                    domain,
                    new TimeProfile(domain, t -> t + pivotOffset, "pivot:" + pivotOffset + ":" + domain.intervals())));
        }
        return List.copyOf(pivots);
    }
}
