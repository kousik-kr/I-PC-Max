package edu.ipcmax.baselines.exact;

import edu.ipcmax.core.function.Domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Objects;

/** Immutable arbitrary precision rational time or value. */
public record ExactTime(BigInteger numerator, BigInteger denominator)
        implements Comparable<ExactTime> {
    public static final ExactTime ZERO = of(0);
    public static final ExactTime ONE = of(1);

    public ExactTime {
        Objects.requireNonNull(numerator, "numerator");
        Objects.requireNonNull(denominator, "denominator");
        if (denominator.signum() == 0) {
            throw new ArithmeticException("zero denominator");
        }
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger gcd = numerator.gcd(denominator);
        numerator = numerator.divide(gcd);
        denominator = denominator.divide(gcd);
    }

    public static ExactTime of(long value) {
        return new ExactTime(BigInteger.valueOf(value), BigInteger.ONE);
    }

    public static ExactTime of(BigInteger numerator, BigInteger denominator) {
        return new ExactTime(numerator, denominator);
    }

    /** Parses an exact decimal or integer spelling. */
    public static ExactTime parseDecimal(String text) {
        BigDecimal value = new BigDecimal(Objects.requireNonNull(text, "text"));
        BigInteger n = value.unscaledValue();
        int scale = value.scale();
        return scale >= 0
                ? new ExactTime(n, BigInteger.TEN.pow(scale))
                : new ExactTime(n.multiply(BigInteger.TEN.pow(-scale)), BigInteger.ONE);
    }

    /**
     * Converts a repository-canonicalized double through its shortest decimal
     * spelling. This is exact for accepted decimal inputs after their existing
     * 12-place canonicalization. It does not recover the original decimal
     * spelling when a caller supplied an arbitrary binary double.
     */
    public static ExactTime fromCanonicalDouble(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("exact decimal source must be finite");
        }
        return parseDecimal(BigDecimal.valueOf(value).toPlainString());
    }

    public ExactTime add(ExactTime other) {
        return new ExactTime(numerator.multiply(other.denominator)
                .add(other.numerator.multiply(denominator)), denominator.multiply(other.denominator));
    }

    public ExactTime subtract(ExactTime other) {
        return new ExactTime(numerator.multiply(other.denominator)
                .subtract(other.numerator.multiply(denominator)), denominator.multiply(other.denominator));
    }

    public ExactTime multiply(ExactTime other) {
        return new ExactTime(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
    }

    public ExactTime divide(ExactTime other) {
        if (other.numerator.signum() == 0) {
            throw new ArithmeticException("division by zero");
        }
        return new ExactTime(numerator.multiply(other.denominator), denominator.multiply(other.numerator));
    }

    public ExactTime negate() {
        return new ExactTime(numerator.negate(), denominator);
    }

    public int signum() {
        return numerator.signum();
    }

    public BigDecimal roundedDecimal(int scale) {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), scale, RoundingMode.HALF_EVEN);
    }

    /** Final public display boundary: HALF_EVEN to the repository's 12 places. */
    public double toDisplayDouble() {
        return roundedDecimal(Domain.TIME_SCALE).doubleValue();
    }

    public double toDouble() {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), MathContextHolder.CONTEXT)
                .doubleValue();
    }

    @Override
    public int compareTo(ExactTime other) {
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }

    @Override
    public String toString() {
        return denominator.equals(BigInteger.ONE) ? numerator.toString() : numerator + "/" + denominator;
    }

    private static final class MathContextHolder {
        private static final java.math.MathContext CONTEXT = new java.math.MathContext(50, RoundingMode.HALF_EVEN);
    }
}
