package edu.ipcmax.experiments.framework;

/** Stable per-query experiment statuses. */
public enum ExperimentStatus {
    COMPLETED,
    NO_FEASIBLE_PATH,
    TIMEOUT,
    OUT_OF_MEMORY,
    LIMIT_EXCEEDED,
    FUNCTION_HORIZON_EXCEEDED,
    INVALID_QUERY,
    INVALID_CONFIGURATION,
    ERROR
}
