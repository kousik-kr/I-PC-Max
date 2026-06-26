package edu.ipcmax.experiments;

import edu.ipcmax.core.pcmax.IPCMaxResult;
import edu.ipcmax.core.pcmax.QuerySpec;

/**
 * Common experiment runner interface.
 */
public interface AlgorithmRunner {
    /**
     * Algorithm label.
     */
    String label();

    /**
     * Runs one query.
     */
    IPCMaxResult run(QuerySpec query);
}
