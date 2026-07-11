package edu.ipcmax.core.pcmax;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;

/** End-to-end checks of the public PACE policies against an independent tiny-graph oracle. */
class PacePublicApiOracleIntegrationTest {
    private static final double EPSILON = 1e-9;

    @Test
    void paceXAndPaceBAgreeWithIndependentContinuousOracleAcrossChangeCells() {
        TDGraph graph = switchingGraph();
        QuerySpec query = new QuerySpec(1, 4, 0, 10, 10, 1);

        EnvelopeProfile exhaustive = new PACE(graph, PaceOptions.exhaustive(1)).run(query);
        EnvelopeProfile bounded = new PACE(graph, PaceOptions.bounded(1, 2, 4)).run(query);

        List<Domain.Interval> expectedCells = List.of(
                new Domain.Interval(0, 3, true, false),
                new Domain.Interval(3, 7, true, false),
                new Domain.Interval(7, 10, true, true));
        assertEquals(expectedCells, exhaustive.segments().stream()
                .map(EnvelopeSegment::interval)
                .toList());
        assertEquals(List.of(List.of(0, 1), List.of(2, 3), List.of(4)),
                exhaustive.segments().stream().map(segment -> segment.path().arcIds()).toList());

        double[] probes = {
                0,
                1.5,
                Math.nextDown(3),
                3,
                5,
                Math.nextDown(7),
                7,
                8.5,
                10
        };
        assertMatchesIndependentOracle(graph, query, exhaustive, probes);
        assertMatchesIndependentOracle(graph, query, bounded, probes);
        assertArrayEquals(serialize(exhaustive), serialize(bounded));
    }

    @SuppressWarnings("deprecation")
    @Test
    void ipcMaxIsOnlyACompatibilityFacadeOverThePaceProfile() {
        TDGraph graph = switchingGraph();
        QuerySpec query = new QuerySpec(1, 4, 0, 10, 10, 1);
        PACE pace = new PACE(graph, PaceOptions.exhaustive(1));
        IPCMax legacy = new IPCMax(
                graph,
                new IPCMaxOptions(1, 0, 4, true, false, 1, false, 2, 1, 42));

        assertArrayEquals(serialize(pace.run(query)), serialize(legacy.runProfile(query)));
        IPCMaxResult pacePoint = pace.bestPointResult(query);
        IPCMaxResult legacyPoint = legacy.run(query);
        assertEquals(pacePoint, legacyPoint);
        assertTrue(legacyPoint.found());
        assertEquals(List.of(2, 3), legacyPoint.path().arcIds());
        assertEquals(8, legacyPoint.score());
        assertEquals(3, legacyPoint.departureTime());
    }

    @Test
    void noPathIsExplicitAcrossTheWholePublicEnvelope() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .edge(1, 2, 8)
                .build();
        QuerySpec query = new QuerySpec(1, 2, 0, 5, 5, 1);
        PACE pace = new PACE(graph, PaceOptions.exhaustive(0));

        EnvelopeProfile profile = pace.run(query);

        assertEquals(1, profile.segments().size());
        assertEquals(new Domain.Interval(0, 5, true, true), profile.segments().get(0).interval());
        assertTrue(profile.segments().get(0).noPath());
        assertTrue(profile.segmentAt(0).noPath());
        assertTrue(profile.segmentAt(2.5).noPath());
        assertTrue(profile.segmentAt(5).noPath());
        assertFalse(pace.bestPointResult(query).found());
    }

    @Test
    void publicQueryHorizonFailureHasMachineReadableStatusAndNoImplicitWrap() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .edge(1, 2, 1)
                .build();
        PACE pace = new PACE(graph, PaceOptions.exhaustive(0));

        EnvelopeProfile covered = pace.run(new QuerySpec(1, 2, 1430, 1430, 10, 1));
        assertEquals(List.of(0), covered.segmentAt(1430).path().arcIds());

        PaceException failure = assertThrows(
                PaceException.class,
                () -> pace.run(new QuerySpec(1, 2, 1431, 1431, 10, 1)));
        assertEquals(PaceStatus.FUNCTION_HORIZON_EXCEEDED, failure.status());
        assertTrue(failure.getMessage().contains("FUNCTION_HORIZON_EXCEEDED"));
    }

    @Test
    void repeatedPublicOutputIsByteStableForOneAndFourThreadsInBothPolicies() {
        TDGraph graph = switchingGraph();
        QuerySpec query = new QuerySpec(1, 4, 0, 10, 10, 1);

        for (PaceExecutionPolicy policy : PaceExecutionPolicy.values()) {
            PaceOptions oneThread = options(policy, 1);
            PaceOptions fourThreads = options(policy, 4);
            PACE repeated = new PACE(graph, oneThread);

            byte[] first = serialize(repeated.run(query));
            byte[] second = serialize(repeated.run(query));
            byte[] parallelConfiguration = serialize(new PACE(graph, fourThreads).run(query));

            assertArrayEquals(first, second, policy + " changed across repeated runs");
            assertArrayEquals(first, parallelConfiguration,
                    policy + " changed when threadCount changed from 1 to 4");
        }
    }

    @Test
    void paceBBoundsEveryObservedFrontierCellByK() {
        TDGraph graph = new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .node(4)
                .node(5)
                .edge(1, 5, 3)
                .edge(1, 2, 1)
                .edge(2, 5, 3)
                .edge(1, 3, 1)
                .edge(3, 5, 4)
                .edge(1, 4, 2)
                .edge(4, 5, 4)
                .build();
        QuerySpec query = new QuerySpec(1, 5, 0, 5, 10, 1);
        int k = 2;
        CandidateSet frontier = new PaceFrontierGenerator(
                graph,
                PaceOptions.bounded(0, 1, k)).generateFrontier(query);

        assertFalse(frontier.isEmpty());
        TreeSet<Double> boundaries = new TreeSet<>();
        boundaries.add((double) query.departureStart());
        boundaries.add((double) query.departureEnd());
        frontier.candidates().forEach(candidate -> boundaries.addAll(candidate.domain().breakpoints()));
        List<Double> ordered = List.copyOf(boundaries);
        for (int index = 1; index < ordered.size(); index++) {
            double sample = ordered.get(index - 1)
                    + (ordered.get(index) - ordered.get(index - 1)) / 2.0;
            long active = frontier.candidates().stream()
                    .filter(candidate -> candidate.domain().contains(sample))
                    .count();
            assertTrue(active <= k, "PACE-B retained " + active + " fragments at t=" + sample);
        }
    }

    private static PaceOptions options(PaceExecutionPolicy policy, int threads) {
        return policy == PaceExecutionPolicy.PACE_X
                ? new PaceOptions(policy, 1, 1, 1, threads, true)
                : new PaceOptions(policy, 1, 2, 4, threads, true);
    }

    private static TDGraph switchingGraph() {
        PiecewiseConstFn firstBranch = scoreFunction(1, 4, 6);
        PiecewiseConstFn secondBranch = scoreFunction(5, 9, 8);
        return new TinyGraphBuilder()
                .node(1)
                .node(2)
                .node(3)
                .node(4)
                .edge(1, 2, 1)
                .edge(2, 4, 3, firstBranch)
                .edge(1, 3, 2)
                .edge(3, 4, 2, secondBranch)
                .edge(1, 4, 1)
                .build();
    }

    private static PiecewiseConstFn scoreFunction(double positiveStart, double positiveEnd, int score) {
        return new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, positiveStart, 0),
                new PiecewiseConstFn.Interval(positiveStart, positiveEnd, score),
                new PiecewiseConstFn.Interval(positiveEnd, 1440, 0)));
    }

    private static void assertMatchesIndependentOracle(
            TDGraph graph,
            QuerySpec query,
            EnvelopeProfile actual,
            double[] probes) {
        List<List<Integer>> simplePaths = enumerateSimplePaths(graph, query.source(), query.destination());
        assertFalse(simplePaths.isEmpty());
        for (double departure : probes) {
            OraclePoint expected = bestAt(graph, simplePaths, departure, query.maxTravelTime());
            EnvelopeSegment segment = actual.segmentAt(departure);
            assertNotNull(segment, "missing envelope segment at t=" + departure);
            if (expected == null) {
                assertTrue(segment.noPath(), "expected NO_PATH at t=" + departure);
                continue;
            }
            assertTrue(segment.found(), "expected a feasible path at t=" + departure);
            assertEquals(expected.path(), segment.path().arcIds(), "path mismatch at t=" + departure);
            CandidateProfile candidate = segment.candidate();
            assertEquals(expected.score(), candidate.scoreProfile().valueAt(departure),
                    "score mismatch at t=" + departure);
            assertEquals(expected.arrival(), candidate.arrivalProfile().valueAt(departure), EPSILON,
                    "arrival mismatch at t=" + departure);
            assertEquals(expected.arrival() - departure, candidate.travelTimeAt(departure), EPSILON,
                    "travel-time mismatch at t=" + departure);
        }
    }

    private static List<List<Integer>> enumerateSimplePaths(TDGraph graph, int source, int destination) {
        List<List<Integer>> paths = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        visited.add(source);
        enumerate(graph, source, destination, visited, new ArrayList<>(), paths);
        return List.copyOf(paths);
    }

    private static void enumerate(
            TDGraph graph,
            int current,
            int destination,
            Set<Integer> visited,
            List<Integer> path,
            List<List<Integer>> output) {
        if (current == destination) {
            output.add(List.copyOf(path));
            return;
        }
        for (Edge edge : graph.outgoingEdges(current)) {
            if (!visited.add(edge.target())) {
                continue;
            }
            path.add(edge.arcId());
            enumerate(graph, edge.target(), destination, visited, path, output);
            path.remove(path.size() - 1);
            visited.remove(edge.target());
        }
    }

    private static OraclePoint bestAt(
            TDGraph graph,
            List<List<Integer>> paths,
            double departure,
            double budget) {
        OraclePoint best = null;
        for (List<Integer> path : paths) {
            OraclePoint candidate = replay(graph, path, departure, budget);
            if (candidate != null && (best == null || compare(candidate, best, departure) < 0)) {
                best = candidate;
            }
        }
        return best;
    }

    private static OraclePoint replay(
            TDGraph graph,
            List<Integer> path,
            double departure,
            double budget) {
        double time = departure;
        int score = 0;
        try {
            for (int arcId : path) {
                Edge edge = graph.edges().get(arcId);
                score += edge.scoreFunction().valueAt(time);
                time = edge.travelTimeFunction().arrivalTimeAt(time);
            }
        } catch (IllegalArgumentException outsideFunctionDomain) {
            return null;
        }
        return time - departure <= budget + EPSILON
                ? new OraclePoint(path, score, time)
                : null;
    }

    private static int compare(OraclePoint left, OraclePoint right, double departure) {
        int comparison = Integer.compare(right.score(), left.score());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Double.compare(left.arrival() - departure, right.arrival() - departure);
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.path().size(), right.path().size());
        if (comparison != 0) {
            return comparison;
        }
        int common = Math.min(left.path().size(), right.path().size());
        for (int index = 0; index < common; index++) {
            comparison = Integer.compare(left.path().get(index), right.path().get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.path().size(), right.path().size());
    }

    private static byte[] serialize(EnvelopeProfile profile) {
        StringBuilder serialized = new StringBuilder(profile.domain().toString());
        for (EnvelopeSegment segment : profile.segments()) {
            serialized.append('\n')
                    .append(segment.interval())
                    .append('|');
            if (segment.noPath()) {
                serialized.append("NO_PATH");
            } else {
                CandidateProfile candidate = segment.candidate();
                serialized.append(candidate.stablePathId())
                        .append('|').append(candidate.domain())
                        .append('|').append(candidate.arrivalProfile().fingerprint())
                        .append('|').append(candidate.scoreProfile().fingerprint());
            }
        }
        return serialized.toString().getBytes(StandardCharsets.UTF_8);
    }

    private record OraclePoint(List<Integer> path, int score, double arrival) {
        private OraclePoint {
            path = List.copyOf(path);
        }
    }
}
