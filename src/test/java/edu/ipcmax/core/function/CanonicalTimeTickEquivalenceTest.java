package edu.ipcmax.core.function;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Regression proof for the signed 12-decimal tick representation. */
class CanonicalTimeTickEquivalenceTest {
    private static final int RANDOM_CASES = 100_000;

    @Test
    void tickConversionIsBitEquivalentToLegacyBigDecimalCanonicalTime() {
        List<Double> values = new ArrayList<>(List.of(
                0.0,
                -0.0,
                10_080.0,
                1.0,
                -1.0,
                1.234567890123,
                -1.234567890123,
                1.2345678901235,
                1.2345678901245,
                -1.2345678901235,
                -1.2345678901245,
                0.00000000000049,
                0.00000000000050,
                0.00000000000051,
                Math.nextDown(3.0),
                Math.nextUp(3.0),
                Math.nextDown(10_080.0),
                Math.nextUp(0.0)));
        Random random = new Random(0x504143455449434bL);
        for (int index = 0; index < RANDOM_CASES; index++) {
            double integral = random.nextInt(20_161) - 10_080;
            double fraction = random.nextDouble();
            values.add(integral + (random.nextBoolean()
                    ? fraction : -fraction));
        }

        for (double value : values) {
            double expected = legacyCanonicalTime(value);
            double actual = Domain.canonicalTime(value);
            assertEquals(
                    Double.doubleToRawLongBits(expected),
                    Double.doubleToRawLongBits(actual),
                    () -> "bit mismatch for " + value
                            + ": expected=" + expected
                            + ", actual=" + actual);
            assertEquals(
                    Domain.canonicalTick(value),
                    Domain.canonicalTick(actual),
                    () -> "tick is not idempotent for " + value);
        }
        assertEquals(RANDOM_CASES + 18, values.size());
    }

    private static double legacyCanonicalTime(double value) {
        return BigDecimal.valueOf(value)
                .setScale(
                        Domain.TIME_SCALE,
                        RoundingMode.HALF_EVEN)
                .doubleValue();
    }
}
