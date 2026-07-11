package edu.ipcmax.experiments;

import edu.ipcmax.core.pcmax.EnvelopeProfile;
import edu.ipcmax.core.pcmax.QuerySpec;

/** Common experiment contract for algorithms that return a full departure profile. */
public interface ProfileAlgorithmRunner {
    /** Stable experiment label. */
    String label();

    /** Executes one path-profile query. */
    EnvelopeProfile run(QuerySpec query);
}
