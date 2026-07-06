package edu.ipcmax.core.pcmax;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;

class ImageDomainContinuousTest {
    @Test
    void imageDomainUsesExactContinuousArrivalBounds() {
        CandidateProfile left = new CandidateProfile(
                Domain.closed(0, 10),
                TimeProfile.piecewise(Domain.closed(0, 10), List.of(
                        new TimeProfile.Breakpoint(0, 10.5),
                        new TimeProfile.Breakpoint(10, 20.5)), "left-arrival"),
                ScoreProfile.constant(Domain.closed(0, 10), 0),
                PathPointer.arc(0),
                0,
                -1,
                false);

        Edge anchor = new Edge(
                0,
                1,
                2,
                1,
                2.25,
                new PiecewiseLinearFn(List.of(
                        new PiecewiseLinearFn.Breakpoint(0, 2.25),
                        new PiecewiseLinearFn.Breakpoint(100, 2.25))),
                PiecewiseConstFn.zeroFullDay());

        Domain image = PaceFrontierGenerator.imageDomainAfterAnchor(left, anchor, Domain.closed(0, 10));

        assertEquals(List.of(new Domain.Interval(12.75, 22.75)), image.intervals());
    }
}