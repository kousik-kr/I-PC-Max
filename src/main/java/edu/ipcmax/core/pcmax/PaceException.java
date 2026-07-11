package edu.ipcmax.core.pcmax;

import java.util.Objects;

/**
 * PACE execution failure carrying an explicit machine-readable status.
 */
public final class PaceException extends RuntimeException {
    private final PaceStatus status;

    /** Creates an execution exception. */
    public PaceException(PaceStatus status, String message) {
        super(message);
        this.status = Objects.requireNonNull(status, "status");
    }

    /** Failure status. */
    public PaceStatus status() {
        return status;
    }
}
