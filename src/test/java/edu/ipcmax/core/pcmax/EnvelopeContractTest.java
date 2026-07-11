package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvelopeContractTest {
    private static final Domain ROOT = Domain.closed(0, 10);

    @Test
    void rankingUsesScoreThenTravelThenEdgesThenNumericStablePathId() {
        assertEquals(List.of(1), bestPath(
                candidate(ROOT, List.of(0), 1, 5),
                candidate(ROOT, List.of(1), 2, 20)));
        assertEquals(List.of(1), bestPath(
                candidate(ROOT, List.of(0), 2, 6),
                candidate(ROOT, List.of(1), 2, 5)));
        assertEquals(List.of(1), bestPath(
                candidate(ROOT, List.of(7, 8), 2, 5),
                candidate(ROOT, List.of(1), 2, 5)));
        assertEquals(List.of(2), bestPath(
                candidate(ROOT, List.of(9), 2, 5),
                candidate(ROOT, List.of(2), 2, 5)));
    }

    @Test
    void uncoveredCellsAreMergedNoPathSegmentsWithExactEndpointOwnership() {
        Domain leftDomain = Domain.halfOpen(0, 2);
        Domain rightDomain = Domain.closed(4, 6);
        CandidateSet frontier = set(
                candidate(leftDomain, List.of(0), 3, 1),
                candidate(rightDomain, List.of(1), 3, 1));

        EnvelopeProfile envelope = EnvelopeExtractor.extract(frontier, Domain.closed(0, 6));

        assertEquals(3, envelope.segments().size());
        assertEquals(new Domain.Interval(0, 2, true, false), envelope.segments().get(0).interval());
        assertEquals(new Domain.Interval(2, 4, true, false), envelope.segments().get(1).interval());
        assertTrue(envelope.segments().get(1).noPath());
        assertEquals(new Domain.Interval(4, 6, true, true), envelope.segments().get(2).interval());
        assertTrue(envelope.segmentAt(2).noPath());
        assertEquals(List.of(1), envelope.segmentAt(4).path().arcIds());
    }

    @Test
    void travelTieAtCellBoundaryIsAssignedExactlyWithoutOverlappingSegments() {
        CandidateProfile lexicographic = new CandidateProfile(
                ROOT,
                TimeProfile.piecewise(ROOT, List.of(
                        new TimeProfile.Breakpoint(0, 5),
                        new TimeProfile.Breakpoint(10, 15)), "lex-arrival"),
                ScoreProfile.constant(ROOT, 10),
                PathPointer.arc(0),
                0,
                -1,
                false);
        CandidateProfile crossing = new CandidateProfile(
                ROOT,
                TimeProfile.piecewise(ROOT, List.of(
                        new TimeProfile.Breakpoint(0, 10.5),
                        new TimeProfile.Breakpoint(10, 10.5)), "cross-arrival"),
                ScoreProfile.constant(ROOT, 10),
                PathPointer.arc(1),
                0,
                -1,
                false);

        EnvelopeProfile envelope = EnvelopeExtractor.extract(set(lexicographic, crossing), ROOT);

        assertEquals(2, envelope.segments().size());
        assertEquals(new Domain.Interval(0, 5.5, true, true), envelope.segments().get(0).interval());
        assertEquals(List.of(0), envelope.segmentAt(5.5).path().arcIds());
        assertEquals(new Domain.Interval(5.5, 10, false, true), envelope.segments().get(1).interval());
        assertEquals(List.of(1), envelope.segmentAt(5.500000001).path().arcIds());
        assertFalse(envelope.segments().get(1).contains(5.5));
    }

    private static List<Integer> bestPath(CandidateProfile... candidates) {
        EnvelopeProfile envelope = EnvelopeExtractor.extract(set(candidates), ROOT);
        return envelope.segmentAt(5).path().arcIds();
    }

    private static CandidateProfile candidate(
            Domain domain,
            List<Integer> path,
            int score,
            double travelTime) {
        Domain.Interval component = domain.intervals().get(0);
        TimeProfile arrival = component.start() == component.end()
                ? TimeProfile.constant(domain, component.start() + travelTime)
                : TimeProfile.piecewise(domain, List.of(
                new TimeProfile.Breakpoint(component.start(), component.start() + travelTime),
                new TimeProfile.Breakpoint(component.end(), component.end() + travelTime)),
                "arrival:" + path + ":" + domain);
        return new CandidateProfile(
                domain,
                arrival,
                ScoreProfile.constant(domain, score),
                PathPointer.of(path),
                0,
                -1,
                false);
    }

    private static CandidateSet set(CandidateProfile... candidates) {
        CandidateSet result = new CandidateSet();
        for (CandidateProfile candidate : candidates) {
            result.add(candidate);
        }
        return result;
    }
}
