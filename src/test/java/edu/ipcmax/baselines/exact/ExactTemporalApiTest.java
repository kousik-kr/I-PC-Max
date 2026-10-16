package edu.ipcmax.baselines.exact;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactTemporalApiTest {
    @Test
    void decimalQueryIsNotReplacedByItsNearbyRationalEventRoot() {
        ExactTime root = ExactTime.of(java.math.BigInteger.valueOf(62), java.math.BigInteger.valueOf(3));
        ExactTime decimal = ExactTime.parseDecimal("20.666666666667");
        assertEquals("62/3", root.toString());
        assertEquals("20.666666666667", root.roundedDecimal(12).toPlainString());
        assertEquals("1/3000000000000", decimal.subtract(root).toString());

        ExactArrivalProfile prefix = profile(ExactTime.of(3).divide(ExactTime.of(2)),
                ExactTime.of(-10), closed("20", "30"));
        ExactTemporalDomain singletonAtTwentyOne = ExactTemporalDomain.of(
                ExactTemporalDomain.Interval.singleton(ExactTime.of(21)));
        assertEquals(List.of(ExactTemporalDomain.Interval.singleton(root)), prefix.preimage(singletonAtTwentyOne).intervals());
        assertEquals(ExactTime.of(21), prefix.valueAtExact(root));
        ExactTime leftDecimal = ExactTime.parseDecimal("20.666666666666");
        assertEquals(ExactTime.parseDecimal("21.0000000000005"), prefix.valueAtExact(decimal));
        assertEquals(ExactTime.parseDecimal("20.999999999999"), prefix.valueAtExact(leftDecimal));
        assertFalse(prefix.preimage(singletonAtTwentyOne).contains(decimal));
        assertFalse(prefix.preimage(singletonAtTwentyOne).contains(leftDecimal));
        ExactTemporalDomain rightOpen = ExactTemporalDomain.of(
                new ExactTemporalDomain.Interval(ExactTime.of(21), ExactTime.of(25), false, false));
        assertTrue(prefix.preimage(rightOpen).contains(decimal));
        assertFalse(prefix.preimage(rightOpen).contains(leftDecimal));
    }

    @Test
    void exactCompositionPreservesOpenSupportRootsAndConstantPreimages() {
        ExactTime root = ExactTime.of(java.math.BigInteger.valueOf(62), java.math.BigInteger.valueOf(3));
        ExactArrivalProfile prefix = profile(ExactTime.of(3).divide(ExactTime.of(2)),
                ExactTime.of(-10), closed("20", "30"));
        ExactTemporalDomain outerOpen = ExactTemporalDomain.of(new ExactTemporalDomain.Interval(
                ExactTime.of(21), ExactTime.of(25), false, false));
        ExactArrivalProfile outer = profile(ExactTime.ONE, ExactTime.of(9), outerOpen.intervals().getFirst());
        ExactArrivalProfile composed = prefix.composeAfter(outer);
        assertEquals(ExactTemporalDomain.of(new ExactTemporalDomain.Interval(
                root, ExactTime.of(java.math.BigInteger.valueOf(70), java.math.BigInteger.valueOf(3)), false, false)),
                composed.domain());
        assertTrue(composed.domain().contains(ExactTime.parseDecimal("20.666666666667")));

        ExactArrivalProfile plateau = profile(ExactTime.ZERO, ExactTime.of(21), closed("0", "5"));
        ExactArrivalProfile singletonOuter = profile(ExactTime.ZERO, ExactTime.of(44),
                ExactTemporalDomain.Interval.singleton(ExactTime.of(21)));
        assertTrue(plateau.composeAfter(singletonOuter).sameFunction(
                profile(ExactTime.ZERO, ExactTime.of(44), closed("0", "5"))));
        assertTrue(plateau.composeAfter(outer).isEmpty());
    }

    @Test
    void exactEnvelopeFindsRationalCrossingsAndCompositionIsAssociative() {
        ExactTemporalDomain.Interval horizon = closed("0", "5");
        ExactArrivalProfile rising = profile(ExactTime.of(2), ExactTime.ZERO, horizon);
        ExactArrivalProfile falling = profile(ExactTime.of(-1), ExactTime.of(10), horizon);
        ExactTime crossing = ExactTime.of(10).divide(ExactTime.of(3));
        assertEquals(List.of(crossing), ExactProfileEnvelope.breakpointCrossings(rising, falling));
        ExactArrivalProfile envelope = ExactProfileEnvelope.minimum(rising, falling);
        assertEquals(ExactTime.of(20).divide(ExactTime.of(3)), envelope.valueAtExact(crossing));
        assertEquals(ExactTime.of(4), envelope.valueAtExact(ExactTime.of(2)));
        assertEquals(ExactTime.of(0), envelope.valueAtExact(ExactTime.ZERO));

        ExactArrivalProfile f = profile(ExactTime.of(2), ExactTime.ONE, horizon);
        ExactArrivalProfile g = profile(ExactTime.of(3), ExactTime.of(-1), closed("1", "11"));
        ExactArrivalProfile h = profile(ExactTime.of(2), ExactTime.of(4), closed("2", "32"));
        assertTrue(f.composeAfter(g).composeAfter(h).sameFunction(
                f.composeAfter(g.composeAfter(h))));

        ExactArrivalProfile singleton = f.restrict(ExactTemporalDomain.of(
                ExactTemporalDomain.Interval.singleton(ExactTime.of(1))));
        assertTrue(singleton.sameFunction(f.restrict(ExactTemporalDomain.of(
                ExactTemporalDomain.Interval.singleton(ExactTime.of(1))))));
    }

    @Test
    void exactProfileSerializationRetainsRationalRootsAndOwnership() throws Exception {
        ExactTime root = ExactTime.of(java.math.BigInteger.valueOf(62), java.math.BigInteger.valueOf(3));
        ExactArrivalProfile source = ExactArrivalProfile.ofSegments(List.of(
                new ExactAffineSegment(ExactTime.of(2), ExactTime.of(4),
                        new ExactTemporalDomain.Interval(root, ExactTime.of(30), false, true)),
                new ExactAffineSegment(ExactTime.ZERO, ExactTime.of(44),
                        ExactTemporalDomain.Interval.singleton(root))));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            source.writeTo(output);
        }
        ExactArrivalProfile restored;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = ExactArrivalProfile.readFrom(input);
            assertEquals(0, input.available());
        }
        assertTrue(source.sameFunction(restored));
        assertEquals(root, restored.segments().get(1).interval().start());
        assertFalse(restored.segments().get(1).interval().startInclusive());
        assertEquals(root, restored.segments().getFirst().interval().start());
    }

    @Test
    void domainRetainsDisconnectedOpenAndSingletonCellsExactly() {
        ExactTemporalDomain domain = ExactTemporalDomain.of(
                new ExactTemporalDomain.Interval(ExactTime.ZERO, ExactTime.ONE, false, false),
                ExactTemporalDomain.Interval.singleton(ExactTime.of(2)),
                new ExactTemporalDomain.Interval(ExactTime.of(3), ExactTime.of(4), true, false));
        assertEquals(3, domain.intervals().size());
        assertFalse(domain.contains(ExactTime.ZERO));
        assertTrue(domain.contains(ExactTime.of(java.math.BigInteger.ONE, java.math.BigInteger.TWO)));
        assertTrue(domain.contains(ExactTime.of(2)));
        assertFalse(domain.contains(ExactTime.of(4)));
        assertTrue(domain.intersection(ExactTemporalDomain.of(
                ExactTemporalDomain.Interval.singleton(ExactTime.of(2)))).contains(ExactTime.of(2)));
        assertTrue(domain.difference(domain).isEmpty());
        assertTrue(ExactTemporalDomain.empty().isEmpty());
    }

    private static ExactArrivalProfile profile(
            ExactTime slope, ExactTime intercept, ExactTemporalDomain.Interval interval) {
        return ExactArrivalProfile.ofSegments(List.of(new ExactAffineSegment(slope, intercept, interval)));
    }

    private static ExactTemporalDomain.Interval closed(String start, String end) {
        return ExactTemporalDomain.Interval.closed(
                ExactTime.parseDecimal(start), ExactTime.parseDecimal(end));
    }
}
