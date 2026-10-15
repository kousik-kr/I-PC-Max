package edu.ipcmax.baselines.sicsp;

/** Preference-direction counterparts to native SICSP's arrival/weight tests. */
public final class SicspPreferenceDominance {
    private SicspPreferenceDominance() {
    }

    /** O-point rule: both better arrival and strictly larger preference are required. */
    public static boolean strictlyDominates(
            double otherArrival,
            int otherScore,
            double candidateArrival,
            int candidateScore) {
        return otherArrival < candidateArrival && otherScore > candidateScore;
    }

    /** W-point skyline: earlier arrival and larger score, with one strict improvement. */
    public static boolean skylineDominates(
            double otherArrival,
            int otherScore,
            double candidateArrival,
            int candidateScore) {
        return otherArrival <= candidateArrival
                && otherScore >= candidateScore
                && (otherArrival < candidateArrival || otherScore > candidateScore);
    }
}
