package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tiny-graph reference built from exact decimal rationals, independent of
 * TimeProfile and all THop composition code.
 */
final class RationalTemporalReference {
    private RationalTemporalReference() {
    }

    record Q(BigInteger numerator, BigInteger denominator) implements Comparable<Q> {
        static final Q ZERO = of(0);
        static final Q ONE = of(1);

        Q {
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

        static Q of(long value) {
            return new Q(BigInteger.valueOf(value), BigInteger.ONE);
        }

        static Q decimal(String value) {
            BigDecimal decimal = new BigDecimal(value);
            BigInteger numerator = decimal.unscaledValue();
            int scale = decimal.scale();
            return scale >= 0
                    ? new Q(numerator, BigInteger.TEN.pow(scale))
                    : new Q(numerator.multiply(BigInteger.TEN.pow(-scale)), BigInteger.ONE);
        }

        static Q decimal(double value) {
            return decimal(BigDecimal.valueOf(value).toPlainString());
        }

        Q add(Q other) {
            return new Q(numerator.multiply(other.denominator)
                    .add(other.numerator.multiply(denominator)), denominator.multiply(other.denominator));
        }

        Q subtract(Q other) {
            return new Q(numerator.multiply(other.denominator)
                    .subtract(other.numerator.multiply(denominator)), denominator.multiply(other.denominator));
        }

        Q multiply(Q other) {
            return new Q(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
        }

        Q divide(Q other) {
            return new Q(numerator.multiply(other.denominator), denominator.multiply(other.numerator));
        }

        String rounded(int scale) {
            return new BigDecimal(numerator).divide(
                    new BigDecimal(denominator), scale, RoundingMode.HALF_EVEN).toPlainString();
        }

        @Override
        public int compareTo(Q other) {
            return numerator.multiply(other.denominator)
                    .compareTo(other.numerator.multiply(denominator));
        }

        @Override
        public String toString() {
            return denominator.equals(BigInteger.ONE)
                    ? numerator.toString()
                    : numerator + "/" + denominator;
        }
    }

    record Affine(Q slope, Q intercept) {
        Q at(Q x) {
            return slope.multiply(x).add(intercept);
        }

        /** Returns {@code outer(this(x))}. */
        Affine then(Affine outer) {
            return new Affine(
                    outer.slope.multiply(slope),
                    outer.slope.multiply(intercept).add(outer.intercept));
        }

        Q rootFor(Q value) {
            if (slope.compareTo(Q.ZERO) == 0) {
                throw new ArithmeticException("constant affine map has no unique root");
            }
            return value.subtract(intercept).divide(slope);
        }

        Q crossing(Affine other) {
            Q slopeDifference = slope.subtract(other.slope);
            if (slopeDifference.compareTo(Q.ZERO) == 0) {
                return null;
            }
            return other.intercept.subtract(intercept).divide(slopeDifference);
        }
    }

    record Step(
            int arcId,
            Q entryTime,
            Q travelTime,
            Q arrivalTime,
            Q segmentStart,
            Q segmentEnd,
            Affine travelFunction,
            Affine arrivalFunction) {
    }

    record Replay(int source, int destination, Q departure, Q arrival, List<Integer> arcIds, List<Step> steps) {
        Q travelTime() {
            return arrival.subtract(departure);
        }
    }

    static Replay replay(TDGraph graph, int source, int destination, Q departure, List<Integer> arcIds) {
        graph.node(source);
        graph.node(destination);
        int currentNode = source;
        Q currentTime = departure;
        List<Step> steps = new ArrayList<>();
        for (int arcId : arcIds) {
            if (arcId < 0 || arcId >= graph.edgeCount()) {
                throw new IllegalArgumentException("unknown arc ID " + arcId);
            }
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != currentNode) {
                throw new IllegalArgumentException("disconnected exact route at arc " + arcId);
            }
            requireSupported(edge.travelTimeFunction().domain(), currentTime, arcId);
            Segment segment = segmentAt(edge.travelTimeFunction(), currentTime);
            Q travel = segment.travel().at(currentTime);
            Q arrival = segment.arrival().at(currentTime);
            steps.add(new Step(arcId, currentTime, travel, arrival,
                    segment.start(), segment.end(), segment.travel(), segment.arrival()));
            currentTime = arrival;
            currentNode = edge.target();
        }
        if (currentNode != destination) {
            throw new IllegalArgumentException("exact route does not reach destination");
        }
        return new Replay(source, destination, departure, currentTime, List.copyOf(arcIds), List.copyOf(steps));
    }

    static Replay bestSimpleRoute(TDGraph graph, int source, int destination, Q departure) {
        graph.node(source);
        graph.node(destination);
        if (source == destination) {
            return replay(graph, source, destination, departure, List.of());
        }
        Best best = new Best();
        Set<Integer> visited = new HashSet<>();
        visited.add(source);
        enumerate(graph, source, destination, departure, visited, new ArrayList<>(), best);
        if (best.replay == null) {
            throw new IllegalArgumentException("no supported simple route");
        }
        return best.replay;
    }

    static Affine arrivalAffine(TDGraph graph, int source, List<Integer> arcIds, Q probeDeparture) {
        int currentNode = source;
        Affine route = new Affine(Q.ONE, Q.ZERO);
        for (int arcId : arcIds) {
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != currentNode) {
                throw new IllegalArgumentException("disconnected exact route at arc " + arcId);
            }
            Q entryTime = route.at(probeDeparture);
            requireSupported(edge.travelTimeFunction().domain(), entryTime, arcId);
            Segment segment = segmentAt(edge.travelTimeFunction(), entryTime);
            route = route.then(segment.arrival());
            currentNode = edge.target();
        }
        return route;
    }

    static boolean supports(Domain domain, Q time) {
        for (Domain.Interval interval : domain.intervals()) {
            Q start = Q.decimal(interval.start());
            Q end = Q.decimal(interval.end());
            boolean afterStart = time.compareTo(start) > 0
                    || (time.compareTo(start) == 0 && interval.startInclusive());
            boolean beforeEnd = time.compareTo(end) < 0
                    || (time.compareTo(end) == 0 && interval.endInclusive());
            if (afterStart && beforeEnd) {
                return true;
            }
        }
        return false;
    }

    private static void enumerate(
            TDGraph graph,
            int current,
            int destination,
            Q departure,
            Set<Integer> visited,
            List<Integer> path,
            Best best) {
        for (Edge edge : graph.outgoingEdges(current)) {
            if (visited.contains(edge.target())) {
                continue;
            }
            path.add(edge.arcId());
            if (edge.target() == destination) {
                try {
                    Replay candidate = replay(graph, pathSource(graph, path), destination, departure, path);
                    if (best.replay == null || candidate.arrival().compareTo(best.replay.arrival()) < 0) {
                        best.replay = candidate;
                    }
                } catch (UnsupportedExactTime unsupported) {
                    // This route is outside at least one edge's exact support.
                }
            } else {
                visited.add(edge.target());
                enumerate(graph, edge.target(), destination, departure, visited, path, best);
                visited.remove(edge.target());
            }
            path.remove(path.size() - 1);
        }
    }

    private static int pathSource(TDGraph graph, List<Integer> path) {
        return graph.edges().get(path.get(0)).source();
    }

    private static void requireSupported(Domain domain, Q time, int arcId) {
        if (supports(domain, time)) {
            return;
        }
        throw new UnsupportedExactTime("arc " + arcId + " excludes exact entry time " + time);
    }

    private static Segment segmentAt(PiecewiseLinearFn function, Q time) {
        List<PiecewiseLinearFn.Breakpoint> points = function.breakpoints();
        for (int index = 0; index + 1 < points.size(); index++) {
            PiecewiseLinearFn.Breakpoint left = points.get(index);
            PiecewiseLinearFn.Breakpoint right = points.get(index + 1);
            Q start = Q.decimal(left.minute());
            Q end = Q.decimal(right.minute());
            if (time.compareTo(start) >= 0 && time.compareTo(end) <= 0) {
                Q leftValue = Q.decimal(left.value());
                Q rightValue = Q.decimal(right.value());
                Q slope = rightValue.subtract(leftValue).divide(end.subtract(start));
                Q intercept = leftValue.subtract(slope.multiply(start));
                Affine travel = new Affine(slope, intercept);
                return new Segment(start, end, travel,
                        new Affine(Q.ONE.add(slope), intercept));
            }
        }
        throw new UnsupportedExactTime("no interpolation segment contains exact time " + time);
    }

    private record Segment(Q start, Q end, Affine travel, Affine arrival) {
    }

    private static final class Best {
        private Replay replay;
    }

    private static final class UnsupportedExactTime extends IllegalArgumentException {
        private UnsupportedExactTime(String message) {
            super(message);
        }
    }
}
