package edu.ipcmax.experiments;

/**
 * Backward-compatible command-line entry point.
 *
 * @deprecated use {@link PaceCli}
 */
@Deprecated
public final class IPCMaxCli {
    private IPCMaxCli() {
    }

    /**
     * Delegates to {@link PaceCli}.
     */
    public static void main(String[] args) throws Exception {
        PaceCli.main(args);
    }
}
