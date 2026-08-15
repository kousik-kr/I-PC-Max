package edu.ipcmax.core.pcmax;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TemporalProfileWork;
import edu.ipcmax.core.profile.TimeProfile;

/**
 * Deterministic grid replay for the explicitly aggressive bounded PACE mode.
 *
 * <p>Every declared grid departure is replayed through the canonical graph and
 * temporal functions without wrapping or extrapolation. Arrival values are
 * linearly connected between retained grid points and scores are held constant
 * on each grid cell. This builder is never used by PACE-X or the normal PACE-B
 * connector portfolio.</p>
 */
final class GridPathProfileBuilder {
    private GridPathProfileBuilder() {
    }

    static Optional<CandidateProfile> replay(
            TDGraph graph,
            Domain queryHorizon,
            Set<Integer> selectedPivotArcIds,
            List<Integer> arcIds,
            int source,
            int destination,
            Domain requestedDomain,
            double budget,
            int pivotId,
            int gridStepMinutes) {
        if (graph == null || queryHorizon == null
                || selectedPivotArcIds == null || arcIds == null
                || requestedDomain == null || gridStepMinutes < 1
                || !Double.isFinite(budget) || budget < 0) {
            throw new IllegalArgumentException(
                    "invalid deterministic grid replay request");
        }
        Domain rootDomain = requestedDomain.intersection(queryHorizon);
        if (rootDomain.isEmpty()) {
            return Optional.empty();
        }
        PathMetadata path = validatePath(
                graph,
                selectedPivotArcIds,
                arcIds,
                source,
                destination);
        List<Domain.Interval> feasibleIntervals = new ArrayList<>();
        List<TimeProfile.Breakpoint> arrivalPoints = new ArrayList<>();
        List<ScoreProfile.Interval> scoreIntervals = new ArrayList<>();
        long evaluatedPoints = 0;
        for (Domain.Interval component : rootDomain.intervals()) {
            List<Double> times = sampleTimes(component, gridStepMinutes);
            List<GridPoint> points = new ArrayList<>(times.size());
            for (double departure : times) {
                points.add(evaluate(
                        graph,
                        queryHorizon,
                        arcIds,
                        departure,
                        budget));
                evaluatedPoints++;
            }
            appendFeasibleRuns(
                    component,
                    points,
                    feasibleIntervals,
                    arrivalPoints,
                    scoreIntervals);
        }
        TemporalProfileWork.add(
                "sampled_grid_replay_points", evaluatedPoints);
        TemporalProfileWork.add(
                "sampled_grid_replay_edge_evaluations",
                Math.multiplyExact(evaluatedPoints, arcIds.size()));
        if (feasibleIntervals.isEmpty()) {
            TemporalProfileWork.add("sampled_grid_replay_empty", 1);
            return Optional.empty();
        }
        Domain feasible = Domain.of(
                feasibleIntervals.toArray(Domain.Interval[]::new));
        String fingerprint = "PACE-B-GRID-REPLAY-v1:path=" + arcIds
                + ":domain=" + feasible
                + ":step=" + gridStepMinutes;
        TimeProfile arrival = TimeProfile.piecewiseCompacted(
                feasible,
                arrivalPoints,
                fingerprint + ":arrival");
        ScoreProfile score = ScoreProfile.piecewise(
                feasible,
                scoreIntervals,
                fingerprint + ":score");
        TemporalProfileWork.add("sampled_grid_replay_profiles", 1);
        return Optional.of(new CandidateProfile(
                feasible,
                arrival,
                score,
                PathPointer.of(arcIds),
                path.explicitPivotCount(),
                pivotId,
                true,
                path.usedPivotArcIds()));
    }

    private static PathMetadata validatePath(
            TDGraph graph,
            Set<Integer> selectedPivotArcIds,
            List<Integer> arcIds,
            int source,
            int destination) {
        int current = source;
        Set<Integer> vertices = new HashSet<>();
        Set<Integer> edges = new HashSet<>();
        Set<Integer> usedPivots = new HashSet<>();
        vertices.add(source);
        for (int arcId : arcIds) {
            if (arcId < 0 || arcId >= graph.edgeCount()) {
                throw new IllegalArgumentException(
                        "candidate contains unknown arc id: " + arcId);
            }
            Edge edge = graph.edges().get(arcId);
            if (!edges.add(arcId) || edge.source() != current
                    || !vertices.add(edge.target())) {
                throw new IllegalArgumentException(
                        "candidate is not a continuous vertex-simple path: "
                                + arcIds);
            }
            if (selectedPivotArcIds.contains(arcId)) {
                usedPivots.add(arcId);
            }
            current = edge.target();
        }
        if (current != destination) {
            throw new IllegalArgumentException(
                    "candidate path ends at " + current
                            + " instead of " + destination);
        }
        return new PathMetadata(usedPivots.size(), Set.copyOf(usedPivots));
    }

    private static GridPoint evaluate(
            TDGraph graph,
            Domain queryHorizon,
            List<Integer> arcIds,
            double departure,
            double budget) {
        double arrival = departure;
        int score = 0;
        for (int arcId : arcIds) {
            Edge edge = graph.edges().get(arcId);
            if (!edge.travelTimeFunction().domain().contains(arrival)
                    || !edge.scoreFunction().domain().contains(arrival)) {
                return GridPoint.infeasible(departure);
            }
            score = Math.addExact(
                    score,
                    edge.scoreFunction().valueAt(arrival));
            arrival = edge.travelTimeFunction().arrivalTimeAt(arrival);
        }
        boolean feasible = queryHorizon.contains(arrival)
                && Domain.canonicalTime(arrival - departure)
                    <= Domain.canonicalTime(budget);
        return feasible
                ? new GridPoint(departure, arrival, score, true)
                : GridPoint.infeasible(departure);
    }

    private static List<Double> sampleTimes(
            Domain.Interval component,
            int gridStepMinutes) {
        List<Double> result = new ArrayList<>();
        result.add(component.start());
        double next = Domain.canonicalTime(
                component.start() + gridStepMinutes);
        while (next < component.end()) {
            result.add(next);
            next = Domain.canonicalTime(next + gridStepMinutes);
        }
        if (!Domain.sameTime(
                component.start(), component.end())) {
            result.add(component.end());
        }
        return List.copyOf(result);
    }

    private static void appendFeasibleRuns(
            Domain.Interval component,
            List<GridPoint> points,
            List<Domain.Interval> feasibleIntervals,
            List<TimeProfile.Breakpoint> arrivalPoints,
            List<ScoreProfile.Interval> scoreIntervals) {
        int cursor = 0;
        while (cursor < points.size()) {
            while (cursor < points.size() && !points.get(cursor).feasible()) {
                cursor++;
            }
            if (cursor >= points.size()) {
                break;
            }
            int start = cursor;
            while (cursor + 1 < points.size()
                    && points.get(cursor + 1).feasible()) {
                cursor++;
            }
            int end = cursor;
            boolean startInclusive = start == 0
                    ? component.startInclusive() : true;
            boolean endInclusive = end == points.size() - 1
                    ? component.endInclusive() : true;
            if (start == end && !(startInclusive && endInclusive)) {
                cursor++;
                continue;
            }
            feasibleIntervals.add(new Domain.Interval(
                    points.get(start).departure(),
                    points.get(end).departure(),
                    startInclusive,
                    endInclusive));
            for (int index = start; index <= end; index++) {
                GridPoint point = points.get(index);
                arrivalPoints.add(new TimeProfile.Breakpoint(
                        point.departure(), point.arrival()));
            }
            if (start == end) {
                scoreIntervals.add(new ScoreProfile.Interval(
                        points.get(start).departure(),
                        points.get(start).departure(),
                        points.get(start).score()));
            } else {
                for (int index = start; index < end; index++) {
                    scoreIntervals.add(new ScoreProfile.Interval(
                            points.get(index).departure(),
                            points.get(index + 1).departure(),
                            points.get(index).score()));
                }
                GridPoint endpoint = points.get(end);
                GridPoint before = points.get(end - 1);
                if (endInclusive && endpoint.score() != before.score()) {
                    scoreIntervals.add(new ScoreProfile.Interval(
                            endpoint.departure(),
                            endpoint.departure(),
                            endpoint.score()));
                }
            }
            cursor++;
        }
    }

    private record GridPoint(
            double departure,
            double arrival,
            int score,
            boolean feasible) {
        static GridPoint infeasible(double departure) {
            return new GridPoint(departure, departure, 0, false);
        }
    }

    private record PathMetadata(
            int explicitPivotCount,
            Set<Integer> usedPivotArcIds) {
    }
}
