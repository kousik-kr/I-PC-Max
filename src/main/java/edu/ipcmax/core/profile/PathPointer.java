package edu.ipcmax.core.profile;

import edu.ipcmax.core.validate.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Lazy path pointer used by candidate profiles.
 */
public interface PathPointer {
    /**
     * Numeric lexicographic ordering used for stable path identifiers.
     */
    Comparator<List<Integer>> STABLE_PATH_ORDER = (left, right) -> {
        int common = Math.min(left.size(), right.size());
        for (int i = 0; i < common; i++) {
            int comparison = Integer.compare(left.get(i), right.get(i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    };

    /**
     * Materializes ordered arc ids.
     */
    List<Integer> arcIds();

    /**
     * Stable path identifier. Parallel arcs remain distinct because the identifier is the
     * complete ordered sequence of stable arc ids.
     */
    default List<Integer> stablePathId() {
        return List.copyOf(arcIds());
    }

    /**
     * Number of arcs in this path.
     */
    default int edgeCount() {
        return arcIds().size();
    }

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
     * Pointer backed by an immutable arc-id sequence.
     */
    static PathPointer of(List<Integer> arcIds) {
        List<Integer> copy = List.copyOf(arcIds);
        return () -> copy;
    }

    /**
     * Concatenates path pointers without exposing mutable state.
     */
    static PathPointer concat(PathPointer... pointers) {
        List<Integer> arcs = new ArrayList<>();
        for (PathPointer pointer : pointers) {
            arcs.addAll(Objects.requireNonNull(pointer, "path pointer").arcIds());
        }
        return of(arcs);
    }
}
