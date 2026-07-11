package edu.ipcmax.core.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.pcmax.PaceExecutionPolicy;
import edu.ipcmax.core.pcmax.PaceOptions;

class PaceMemoKeyContractTest {
    @Test
    void normalizedEquivalentDomainsShareAKeyButConfigurationsDoNotCollide() {
        Domain splitRepresentation = Domain.of(
                new Domain.Interval(0, 5, true, false),
                new Domain.Interval(5, 10, true, true));
        Domain canonicalRepresentation = Domain.closed(0, 10);
        MemoKey first = key(
                splitRepresentation,
                PaceExecutionPolicy.PACE_X,
                PaceOptions.UNBOUNDED,
                PaceOptions.UNBOUNDED,
                5);
        MemoKey equivalent = key(
                canonicalRepresentation,
                PaceExecutionPolicy.PACE_X,
                PaceOptions.UNBOUNDED,
                PaceOptions.UNBOUNDED,
                5);

        assertEquals(first, equivalent);
        assertNotEquals(first, key(canonicalRepresentation, PaceExecutionPolicy.PACE_B, 2, 3, 5));
        assertNotEquals(first, key(
                canonicalRepresentation,
                PaceExecutionPolicy.PACE_X,
                PaceOptions.UNBOUNDED,
                PaceOptions.UNBOUNDED,
                6));
        assertNotEquals(
                key(canonicalRepresentation, PaceExecutionPolicy.PACE_B, 2, 3, 5),
                key(canonicalRepresentation, PaceExecutionPolicy.PACE_B, 3, 3, 5));
        assertNotEquals(
                key(canonicalRepresentation, PaceExecutionPolicy.PACE_B, 2, 3, 5),
                key(canonicalRepresentation, PaceExecutionPolicy.PACE_B, 2, 4, 5));
    }

    @Test
    void endpointOwnershipIsPartOfCanonicalDomainIdentity() {
        MemoKey closed = key(Domain.closed(0, 10), PaceExecutionPolicy.PACE_X,
                PaceOptions.UNBOUNDED, PaceOptions.UNBOUNDED, 5);
        MemoKey halfOpen = key(Domain.halfOpen(0, 10), PaceExecutionPolicy.PACE_X,
                PaceOptions.UNBOUNDED, PaceOptions.UNBOUNDED, 5);

        assertNotEquals(closed, halfOpen);
    }

    private static MemoKey key(
            Domain domain,
            PaceExecutionPolicy policy,
            int anchorLimit,
            int candidateLimit,
            double budget) {
        return new MemoKey(
                1,
                2,
                domain,
                2,
                policy,
                anchorLimit,
                candidateLimit,
                budget,
                Domain.closed(0, 20),
                "graph-v1",
                "anchors-v1");
    }
}
