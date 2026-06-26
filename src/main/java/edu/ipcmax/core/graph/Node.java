package edu.ipcmax.core.graph;

/**
 * Road-network node with optional coordinate metadata.
 */
public record Node(int id, long x, long y) {
    /**
     * Creates a validated node.
     */
    public Node {
        if (id <= 0) {
            throw new IllegalArgumentException("node id must be positive");
        }
    }
}
