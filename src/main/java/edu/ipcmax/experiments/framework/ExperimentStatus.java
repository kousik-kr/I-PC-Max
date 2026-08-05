package edu.ipcmax.experiments.framework;

/** Stable per-query experiment statuses. */
public enum ExperimentStatus {
    COMPLETED,
    CERTIFIED_COMPLETE,
    NO_FEASIBLE_PATH,
    TIME_CAPPED_NOT_CERTIFIED,
    PATH_CAPPED_NOT_CERTIFIED,
    TIMEOUT,
    OUT_OF_MEMORY,
    LIMIT_EXCEEDED,
    FUNCTION_HORIZON_EXCEEDED,
    INVALID_QUERY,
    INVALID_CONFIGURATION,
    ERROR
}
