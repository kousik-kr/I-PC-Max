package edu.ipcmax.core.function;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Piecewise-linear travel-time function over a one-day horizon.
 *
 * <p>The stored value is travel time. The corresponding edge arrival function is
 * {@code Gamma(t) = t + travelTime(t)}.</p>
 */
public final class PiecewiseLinearFn {
    private final List<Breakpoint> breakpoints;

    /**
     * A travel-time breakpoint.
     */
    public record Breakpoint(double minute, double value) {
        /**
         * Creates a validated breakpoint.
         */
        public Breakpoint {
            if (!Double.isFinite(minute) || !Double.isFinite(value)) {
                throw new IllegalArgumentException("breakpoint values must be finite");
            }
            if (value < 0) {
                throw new IllegalArgumentException("travel time cannot be negative");
            }
        }
    }

    /**
     * Creates a piecewise-linear travel-time function from sorted breakpoints.
     */
    public PiecewiseLinearFn(List<Breakpoint> breakpoints) {
        if (breakpoints.size() < 2) {
            throw new IllegalArgumentException("piecewise-linear function requires at least two breakpoints");
        }
        List<Breakpoint> copy = new ArrayList<>(breakpoints);
        copy.sort(Comparator.comparingDouble(Breakpoint::minute));
        for (int i = 0; i < copy.size(); i++) {
            if (i > 0 && copy.get(i).minute() <= copy.get(i - 1).minute()) {
                throw new IllegalArgumentException("breakpoint times must be strictly increasing");
            }
        }
        this.breakpoints = List.copyOf(copy);
    }

    /**
     * Returns the immutable breakpoint list.
     */
    public List<Breakpoint> breakpoints() {
        return breakpoints;
    }

    /**
     * Evaluates travel time at {@code minute}.
     */
    public double travelTimeAt(double minute) {
        if (minute < firstMinute() || minute > lastMinute()) {
            throw new IllegalArgumentException("time is outside function domain: " + minute);
        }
        if (minute == lastMinute()) {
            return breakpoints.get(breakpoints.size() - 1).value();
        }

        int index = Collections.binarySearch(
                breakpoints,
                new Breakpoint(minute, 0),
                Comparator.comparingDouble(Breakpoint::minute));
        if (index >= 0) {
            return breakpoints.get(index).value();
        }

        int insertionPoint = -index - 1;
        Breakpoint left = breakpoints.get(insertionPoint - 1);
        Breakpoint right = breakpoints.get(insertionPoint);
        double alpha = (minute - left.minute()) / (right.minute() - left.minute());
        return left.value() + alpha * (right.value() - left.value());
    }

    /**
     * Evaluates arrival time {@code Gamma(t) = t + travelTime(t)}.
     */
    public double arrivalTimeAt(double minute) {
        return minute + travelTimeAt(minute);
    }

    /**
     * Returns the minimum travel time attained at any breakpoint.
     */
    public double minTravelTime() {
        double min = Double.POSITIVE_INFINITY;
        for (Breakpoint breakpoint : breakpoints) {
            min = Math.min(min, breakpoint.value());
        }
        return min;
    }

    /**
     * Latest departure in the function domain whose arrival is no later than {@code arrivalDeadline}.
     */
    public double latestDepartureForArrival(double arrivalDeadline) {
        if (!Double.isFinite(arrivalDeadline)) {
            throw new IllegalArgumentException("arrival deadline must be finite");
        }
        double latest = Double.NEGATIVE_INFINITY;
        for (int i = 1; i < breakpoints.size(); i++) {
            Breakpoint left = breakpoints.get(i - 1);
            Breakpoint right = breakpoints.get(i);
            double leftArrival = left.minute() + left.value();
            double rightArrival = right.minute() + right.value();

            if (arrivalDeadline >= rightArrival) {
                latest = Math.max(latest, right.minute());
                continue;
            }
            if (arrivalDeadline < leftArrival) {
                continue;
            }

            double duration = right.minute() - left.minute();
            double arrivalSlope = (rightArrival - leftArrival) / duration;
            if (arrivalSlope == 0) {
                latest = Math.max(latest, right.minute());
            } else {
                double offset = (arrivalDeadline - leftArrival) / arrivalSlope;
                latest = Math.max(latest, left.minute() + offset);
            }
        }
        return latest;
    }

    /**
     * Returns true when the induced arrival function is FIFO on each segment.
     */
    public boolean isFifo() {
        for (int i = 1; i < breakpoints.size(); i++) {
            Breakpoint prev = breakpoints.get(i - 1);
            Breakpoint next = breakpoints.get(i);
            if (next.minute() + next.value() < prev.minute() + prev.value()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates FIFO and throws with context on failure.
     */
    public void requireFifo(String context) {
        if (!isFifo()) {
            throw new IllegalArgumentException(context + ": non-FIFO travel-time function");
        }
    }

    /**
     * First minute in the function domain.
     */
    public double firstMinute() {
        return breakpoints.get(0).minute();
    }

    /**
     * Last minute in the function domain.
     */
    public double lastMinute() {
        return breakpoints.get(breakpoints.size() - 1).minute();
    }
}
