package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.validate.Path;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Builds exact no-wait rational arrival and preference profiles for one route. */
public final class ExactPreferenceReplay {
    private ExactPreferenceReplay() { }

    /** Public decimal bounds and budget are canonicalized once by their existing input boundary. */
    public static Optional<ExactPreferencePathProfile> replay(
            TDGraph graph, int source, int destination, Path path, Domain departureDomain, double budget) {
        if (!Double.isFinite(budget) || budget < 0) throw new IllegalArgumentException("invalid budget");
        return replayExact(graph, source, destination, path,
                ExactTemporalDomain.fromLegacy(departureDomain),
                ExactTime.fromCanonicalDouble(Domain.canonicalTime(budget)));
    }

    public static Optional<ExactPreferencePathProfile> replayExact(
            TDGraph graph, int source, int destination, Path path,
            ExactTemporalDomain departureDomain, ExactTime budget) {
        graph.node(source);
        graph.node(destination);
        validatePath(graph, source, destination, path);
        ExactArrivalProfile prefix = ExactArrivalProfile.identity(departureDomain);
        ExactScoreProfile accumulated = null;
        long scoreEvents = 0;
        int current = source;
        for (int arcId : path.arcIds()) {
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != current) throw new IllegalArgumentException("disconnected path at arc " + arcId);
            ExactScoreProfile edgeScore = ExactScoreProfile.edgeScore(edge);
            ExactTemporalDomain scorePreimage = prefix.preimage(edgeScore.domain());
            ExactTemporalDomain scoreSupport = prefix.domain().intersection(scorePreimage);
            if (scoreSupport.isEmpty()) return Optional.empty();
            ExactArrivalProfile scoreEntryMap = prefix.restrict(scoreSupport);
            ExactScoreProfile edgeScoreOnDeparture = edgeScore.pullback(scoreEntryMap);
            scoreEvents += Math.max(0, edgeScoreOnDeparture.pieceCount() - 1);
            if (accumulated == null) accumulated = edgeScoreOnDeparture;
            else accumulated = accumulated.restrict(scoreSupport).plus(edgeScoreOnDeparture);

            ExactArrivalProfile edgeArrival = ExactArrivalProfile.edgeArrival(edge);
            ExactTemporalDomain travelPreimage = prefix.preimage(edgeArrival.domain());
            ExactTemporalDomain nextSupport = scoreSupport.intersection(travelPreimage);
            if (nextSupport.isEmpty()) return Optional.empty();
            accumulated = accumulated.restrict(nextSupport);
            prefix = prefix.restrict(nextSupport).composeAfter(edgeArrival);
            if (prefix.isEmpty()) return Optional.empty();
            current = edge.target();
        }
        if (current != destination) throw new IllegalArgumentException("path does not reach destination");
        if (accumulated == null) accumulated = ExactScoreProfile.constant(prefix.domain(), java.math.BigInteger.ZERO);
        accumulated = accumulated.restrict(prefix.domain());
        if (!prefix.domain().equals(accumulated.domain())) {
            throw new IllegalStateException("exact path score support differs from arrival support");
        }
        ExactTravelBudgetDomain budgetDomain = ExactTravelBudgetDomain.compute(prefix, budget);
        ExactTemporalDomain feasible = prefix.domain().intersection(budgetDomain.domain());
        return Optional.of(new ExactPreferencePathProfile(path, prefix, accumulated, feasible,
                budget, scoreEvents, budgetDomain.rootOperations()));
    }

    /** Replays one canonical decimal departure exactly, without imposing a budget. */
    public static Optional<PointReplay> replayAt(
            TDGraph graph, int source, int destination, Path path, double departure) {
        ExactTime exact = ExactTime.fromCanonicalDouble(Domain.canonicalTime(departure));
        var profile = replayExact(graph, source, destination, path,
                ExactTemporalDomain.of(ExactTemporalDomain.Interval.singleton(exact)), ExactTime.ZERO);
        if (profile.isEmpty() || !profile.orElseThrow().arrival().domain().contains(exact)) return Optional.empty();
        ExactPreferencePathProfile result = profile.orElseThrow();
        return Optional.of(new PointReplay(result.arrival().valueAtExact(exact), result.score().valueAt(exact)));
    }

    public record PointReplay(ExactTime arrival, java.math.BigInteger score) { }

    private static void validatePath(TDGraph graph, int source, int destination, Path path) {
        if (path.arcIds().isEmpty()) {
            if (source != destination) throw new IllegalArgumentException("empty route requires identical endpoints");
            return;
        }
        if (source == destination) throw new IllegalArgumentException("nonempty identity route is not loopless");
        Set<Integer> visited = new LinkedHashSet<>();
        visited.add(source);
        int current = source;
        for (int arcId : path.arcIds()) {
            if (arcId < 0 || arcId >= graph.edgeCount()) throw new IllegalArgumentException("unknown arc id " + arcId);
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != current) throw new IllegalArgumentException("disconnected path at arc " + arcId);
            if (!visited.add(edge.target())) throw new IllegalArgumentException("path is not vertex-simple");
            current = edge.target();
        }
        if (current != destination) throw new IllegalArgumentException("path does not reach destination");
    }
}
