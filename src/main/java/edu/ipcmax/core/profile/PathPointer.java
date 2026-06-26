package edu.ipcmax.core.profile;

import edu.ipcmax.core.validate.Path;

import java.util.ArrayList;
import java.util.List;

/**
 * Lazy path pointer used by candidate profiles.
 */
public interface PathPointer {
    /**
     * Materializes ordered arc ids.
     */
    List<Integer> arcIds();

    /**
     * Materializes a {@link Path}.
     */
    default Path toPath() {
        return new Path(arcIds());
    }

    /**
     * Empty path pointer.
     */
    static PathPointer empty() {
        return List::of;
    }

    /**
     * Single arc pointer.
     */
    static PathPointer arc(int arcId) {
        return () -> List.of(arcId);
    }

    /**
     * Concatenates path pointers without exposing mutable state.
     */
    static PathPointer concat(PathPointer... pointers) {
        return () -> {
            List<Integer> arcs = new ArrayList<>();
            for (PathPointer pointer : pointers) {
                arcs.addAll(pointer.arcIds());
            }
            return List.copyOf(arcs);
        };
    }
}
