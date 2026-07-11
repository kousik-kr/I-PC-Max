package edu.ipcmax.core.pcmax;

/**
 * Machine-readable status for PACE query execution.
 */
public enum PaceStatus {
    /** Query execution completed normally. */
    SUCCESS,

    /** The requested departure-plus-budget horizon is outside graph function coverage. */
    FUNCTION_HORIZON_EXCEEDED
}
