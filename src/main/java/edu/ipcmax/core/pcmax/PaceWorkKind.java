package edu.ipcmax.core.pcmax;

/**
 * Canonical kinds of production PACE candidate/frontier work charged to
 * {@code M_q}. Each reservation is made immediately before the named unit of
 * work, in deterministic reducer order.
 */
public enum PaceWorkKind {
    CONNECTOR_REQUEST,
    CANDIDATE_OFFER,
    AFFECTED_CELL_EVALUATION,
    RETENTION_EVALUATION,
    FRAGMENT_RESTRICTION,
    FRAGMENT_MATERIALIZATION,
    DOMINANCE_CHECK,
    EQUALITY_ROOT_CHECK
}
