package edu.ipcmax.core.labeling;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;
import edu.ipcmax.core.validate.ExactPathValidator;
import edu.ipcmax.core.validate.GraphValidator;
import edu.ipcmax.core.validate.Path;
import edu.ipcmax.core.validate.ValidationResult;

/**
 * Exact discrete interval forward labeling wrapper around point forward labeling.
 */
public final class IntervalForwardLabeling {
    private final TDGraph graph;
    private boolean fifoValidated;

    /**
     * Creates an interval forward labeler.
     */
    public IntervalForwardLabeling(TDGraph graph) {
        this.graph = graph;
    }

    /**
     * Computes fastest candidate profiles from source to target over a domain.
     */
    public CandidateSet fastestCandidates(int source, int target, Domain domain, double budget) {
        CandidateSet set = new CandidateSet();
        PointForwardLabeling labeler = new PointForwardLabeling(graph);
        ExactPathValidator validator = new ExactPathValidator(graph);
        for (int departure : domain) {
            PointForwardLabeling.Result labels = labeler.run(source, departure, budget);
            if (!labels.reached(target)) {
                continue;
            }
            Path path = labels.pathTo(target);
            ValidationResult validation = validator.validate(source, target, departure, budget, path);
            if (!validation.valid()) {
                continue;
            }
            Domain singleton = Domain.closed(departure, departure);
            set.add(new CandidateProfile(
                    singleton,
                    TimeProfile.constant(singleton, validation.arrivalTime()),
                    ScoreProfile.constant(singleton, validation.score()),
                    () -> path.arcIds(),
                    0,
                    -1,
                    false));
        }
        return set;
    }

    /**
     * Computes the exact continuous FIFO fastest-travel-time profile over a departure domain.
     *
     * <p>Profiles are propagated by exact piecewise-linear composition and pointwise lower
     * envelopes. No fixed departure-time samples are used. The result is empty when the target
     * is unreachable or when an exact envelope would require a support discontinuity that
     * {@link TimeProfile} cannot represent without interpolation.</p>
     */
    public Optional<FastestTravelTimeProfile> fastestTravelTimeProfile(
            int source, int target, Domain departureDomain) {
        if (departureDomain == null || departureDomain.isEmpty()) {
            throw new IllegalArgumentException("fastest-profile departure domain is required");
        }
        graph.node(source);
        graph.node(target);
        requireFifo();

        Map<Integer, TimeProfile> arrivals = new HashMap<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        Set<Integer> queued = new HashSet<>();
        arrivals.put(source, TimeProfile.identity(departureDomain));
        queue.add(source);
        queued.add(source);

        while (!queue.isEmpty()) {
            int node = queue.removeFirst();
            queued.remove(node);
            TimeProfile prefix = arrivals.get(node);
            for (Edge edge : graph.outgoingEdges(node)) {
                Optional<TimeProfile> extension = compose(prefix, edge);
                if (extension.isEmpty()) {
                    continue;
                }
                TimeProfile candidate = extension.get();
                TimeProfile current = arrivals.get(edge.target());
                TimeProfile improved;
                try {
                    improved = current == null
                            ? candidate
                            : current.pointwiseMinimum(
                                    candidate,
                                    "fastest:min:node=" + edge.target());
                } catch (TimeProfile.DiscontinuousEnvelopeException unsupported) {
                    return Optional.empty();
                }
                if (current != null && current.sameValues(improved)) {
                    continue;
                }
                arrivals.put(edge.target(), improved);
                if (queued.add(edge.target())) {
                    queue.addLast(edge.target());
                }
            }
        }

        TimeProfile targetProfile = arrivals.get(target);
        if (targetProfile == null) {
            return Optional.empty();
        }
        return Optional.of(new FastestTravelTimeProfile(
                targetProfile,
                targetProfile.minimumTravelTime(targetProfile.domain()),
                targetProfile.maximumTravelTime(targetProfile.domain())));
    }

    private static Optional<TimeProfile> compose(TimeProfile prefix, Edge edge) {
        Domain edgeDomain = edge.travelTimeFunction().domain();
        if (prefix.preimage(edgeDomain, prefix.domain()).isEmpty()) {
            return Optional.empty();
        }
        PiecewiseLinearFn travel = edge.travelTimeFunction();
        List<TimeProfile.Breakpoint> points = travel.breakpoints().stream()
                .map(point -> new TimeProfile.Breakpoint(
                        point.minute(), point.minute() + point.value()))
                .toList();
        TimeProfile edgeArrival = TimeProfile.piecewise(
                edgeDomain, points, "fastest:arc=" + edge.arcId());
        return Optional.of(prefix.compose(
                edgeArrival,
                prefix.fingerprint() + "|arc=" + edge.arcId()));
    }

    private synchronized void requireFifo() {
        if (!fifoValidated) {
            GraphValidator.validate(graph, true);
            fifoValidated = true;
        }
    }

    /** Exact continuous fastest-arrival profile and its travel-time extrema. */
    public record FastestTravelTimeProfile(
            TimeProfile arrivalProfile,
            double minimumTravelTime,
            double maximumTravelTime) {
        public FastestTravelTimeProfile {
            if (arrivalProfile == null
                    || !Double.isFinite(minimumTravelTime)
                    || !Double.isFinite(maximumTravelTime)
                    || minimumTravelTime < 0
                    || maximumTravelTime < minimumTravelTime) {
                throw new IllegalArgumentException("invalid fastest-travel-time profile");
            }
        }

        /** Exact travel time at a contained continuous departure time. */
        public double travelTimeAt(double departureTime) {
            return Domain.canonicalTime(
                    arrivalProfile.valueAt(departureTime) - departureTime);
        }
    }
}
