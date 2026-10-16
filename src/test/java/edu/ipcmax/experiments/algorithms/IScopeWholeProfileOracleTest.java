package edu.ipcmax.experiments.algorithms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.core.pcmax.EnvelopeProfile;
import edu.ipcmax.core.pcmax.EnvelopeSegment;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.profile.TimeProfile;
import edu.ipcmax.experiments.framework.Ablation;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.AlgorithmResult;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;
import edu.ipcmax.testoracle.ExactFraction;
import edu.ipcmax.testoracle.TinyContinuousEnvelopeOracle;
import edu.ipcmax.testoracle.TinyContinuousEnvelopeOracle.TemporalCell;

/** Structural comparisons of every cell, endpoint, score, and affine coefficient. */
class IScopeWholeProfileOracleTest {
    @Test
    void scoreDiscontinuitiesAndRightContinuousEndpointOwnership() {
        verify(switchingGraph(), new QuerySpec(1, 4, 0, 10, 20, 1));
        verify(switchingGraph(), new QuerySpec(1, 4, 0, 5, 20, 1));
        verify(switchingGraph(), new QuerySpec(1, 4, 5, 5, 20, 1));
        verify(switchingGraph(), new QuerySpec(1, 4, 10, 10, 20, 1));
    }

    @Test
    void exactBudgetRootsAndFifoPlateauHaveCompleteNoPathCoverage() {
        TDGraph graph = graph(List.of(edge(0, 1, 2,
                travel(0, 1, 4, 5, 8, 1, 1440, 1), score(0, 1440, 7))));
        var result = verify(graph, new QuerySpec(1, 2, 0, 10, 3, 1));
        assertTrue(result.profile().segmentAt(2).found());
        assertTrue(result.profile().segmentAt(4).noPath());
        assertTrue(result.profile().segmentAt(6).found());
    }

    @Test
    void equalScoresTravelCrossingsAndAllDeterministicTieRules() {
        TDGraph crossing = graph(List.of(
                edge(0, 1, 2, travel(0, 4, 1440, 4), score(0, 1440, 9)),
                edge(1, 1, 2, travel(0, 2, 8, 6, 1440, 6), score(0, 1440, 9))));
        var result = verify(crossing, new QuerySpec(1, 2, 0, 8, 10, 1));
        assertEquals(List.of(0), result.profile().segmentAt(4).path().arcIds());
        TDGraph ties = new TinyGraphBuilder().node(1).node(2).node(3)
                .edge(1, 3, 4).edge(1, 3, 4)
                .edge(1, 2, 2).edge(2, 3, 2).build();
        assertEquals(List.of(0), verify(ties, new QuerySpec(1, 3, 0, 10, 4, 1))
                .profile().segmentAt(5).path().arcIds());
    }

    @Test
    void multipleCompositionsPullBackEveryEdgeEntryScoreEvent() {
        TDGraph graph = graph(List.of(
                edge(0, 1, 2, travel(0, 1, 4, 5, 1440, 5), score(0, 1440, 1)),
                edge(1, 2, 3, travel(0, 2, 1440, 2), score(0, 5, 2, 5, 9, 8, 9, 1440, 3)),
                edge(2, 3, 4, travel(0, 1, 1440, 1), score(0, 7, 0, 7, 11, 6, 11, 1440, 4)),
                edge(3, 1, 4, travel(0, 8, 1440, 8), score(0, 1440, 7))));
        verify(graph, new QuerySpec(1, 4, 0, 10, 10, 1));
    }

    @Test
    void directedAsymmetryDisconnectedAndBudgetInfeasibleQueries() {
        TDGraph graph = new TinyGraphBuilder().node(1).node(2).node(3)
                .edge(1, 2, 3).build();
        verify(graph, new QuerySpec(1, 2, 0, 10, 3, 1));
        assertEquals(ExperimentStatus.NO_FEASIBLE_PATH,
                verify(graph, new QuerySpec(2, 1, 0, 10, 3, 1)).status());
        assertEquals(ExperimentStatus.NO_FEASIBLE_PATH,
                verify(graph, new QuerySpec(1, 3, 0, 10, 3, 1)).status());
        assertEquals(ExperimentStatus.NO_FEASIBLE_PATH,
                verify(graph, new QuerySpec(1, 2, 0, 10, 2, 1)).status());
    }

    @Test
    void fixedSeedCompetingPathCorpusMatchesWholeProfileAlgebra() {
        Random random = new Random(0x4953434f5045L);
        for (int fixture = 0; fixture < 40; fixture++) {
            TinyGraphBuilder builder = new TinyGraphBuilder();
            for (int node = 1; node <= 5; node++) {
                builder.node(node);
            }
            for (int source = 1; source < 5; source++) {
                for (int target = source + 1; target <= 5; target++) {
                    if (target == source + 1 || random.nextBoolean()) {
                        builder.edge(source, target, 1 + random.nextInt(4),
                                score(0, 4, random.nextInt(8), 4, 9, random.nextInt(8),
                                        9, 1440, random.nextInt(8)));
                    }
                }
            }
            verify(builder.build(), new QuerySpec(1, 5, 0, 10, 5 + random.nextInt(10), 1));
        }
    }

    @Test
    void cappedExecutionCommitsOnlyWholeIndependentlyValidProfiles() {
        TDGraph graph = switchingGraph();
        QuerySpec query = new QuerySpec(1, 4, 0, 10, 20, 1);
        var pathCapped = new IScopeAlgorithm().run(graph, query,
                config(1, 10_000_000_000L), new ExperimentInstrumentation());
        assertEquals(ExperimentStatus.PATH_CAPPED_NOT_CERTIFIED, pathCapped.status());
        validateCapped(graph, query, pathCapped);
        boolean sawPartial = false;
        for (long limit = 30; limit < 400; limit += 5) {
            AtomicLong clock = new AtomicLong();
            var result = new IScopeAlgorithm(clock::getAndIncrement).run(graph, query,
                    config(10_000, limit), new ExperimentInstrumentation());
            if (result.status() == ExperimentStatus.TIME_CAPPED_NOT_CERTIFIED
                    && (Long) result.scalars().get("accepted_profiles") > 0) {
                validateCapped(graph, query, result);
                sawPartial = true;
                break;
            }
        }
        assertTrue(sawPartial, "must exercise a committed partial anytime profile");
    }

    @Test
    void preparedWeightsAreSeparateAndRepeatedQueriesPreserveTheWholeProfile() {
        TDGraph graph = switchingGraph();
        QuerySpec query = new QuerySpec(1, 4, 0, 10, 20, 1);
        IScopeAlgorithm algorithm = new IScopeAlgorithm();
        AlgorithmConfig config = config(10_000, 10_000_000_000L);
        var unprepared = algorithm.run(graph, query, config, new ExperimentInstrumentation());
        assertFalse((Boolean) unprepared.scalars().get("query_independent_lower_bound_weights_prepared"));
        algorithm.prepare(graph, config);
        algorithm.prepare(graph, config);
        for (int repeat = 0; repeat < 2; repeat++) {
            var instrumentation = new ExperimentInstrumentation();
            var prepared = algorithm.run(graph, query, config, instrumentation);
            assertTrue((Boolean) prepared.scalars().get("query_independent_lower_bound_weights_prepared"));
            assertTrue(instrumentation.timings().containsKey("lower_bound_preprocessing"));
            assertWholeProfile(graph, query, prepared.profile(), TinyContinuousEnvelopeOracle.solveCells(graph, query));
        }
    }

    @Test
    void fractionalInverseRootDocumentsTheSharedLegacyArithmeticLimit() {
        TDGraph graph = graph(List.of(
                edge(0, 1, 2, travel(0, 10, 40, 30, 100, 40), score(0, 100, 1)),
                edge(1, 2, 3, travel(0, 40, 50, 40, 80, 10, 100, 10),
                        score(0, 20, 1, 20, 60, 9, 60, 100, 2))));
        QuerySpec query = new QuerySpec(1, 3, 0, 20, 80, 1);
        var result = new IScopeAlgorithm().run(graph, query,
                config(10_000, 10_000_000_000L), new ExperimentInstrumentation());
        double departure = 6.666666666667;
        double entry = graph.edges().get(0).travelTimeFunction().arrivalTimeAt(departure);
        double replay = graph.edges().get(1).travelTimeFunction().arrivalTimeAt(entry);
        double profile = result.profile().segmentAt(departure).candidate()
                .arrivalProfile().valueAt(departure);
        assertEquals(20.000000000001, entry);
        assertEquals(60.000000000001, replay);
        assertEquals(60.0, profile);
        assertNotEquals(replay, profile);
        ExactFraction trueRoot = ExactFraction.of(20).divide(ExactFraction.of(3));
        assertTrue(TinyContinuousEnvelopeOracle.solveCells(graph, query).stream()
                .anyMatch(cell -> cell.start().equals(trueRoot)));
        assertNotEquals(trueRoot, fraction(departure));
        assertEquals("LEGACY_CANONICAL_PROFILE_12DP-v1", result.scalars().get("numerical_contract"));
        // This is an unresolved shared-model limitation, not a successful
        // numerical repair or evidence of exactness over arbitrary real times.
    }

    private static void validateCapped(TDGraph graph, QuerySpec query, AlgorithmResult result) {
        assertEquals(ExactnessScope.NOT_CERTIFIED, result.exactnessScope());
        assertTrue((Long) result.scalars().get("accepted_profiles")
                <= (Long) result.scalars().get("fully_profiled_paths"));
        assertFalse(result.scalars().containsKey("completion_percentage"));
        List<List<Integer>> selected = result.profile().segments().stream().filter(EnvelopeSegment::found)
                .map(segment -> segment.path().arcIds()).distinct().toList();
        assertWholeProfile(graph, query, result.profile(),
                TinyContinuousEnvelopeOracle.solveCellsForPaths(graph, query, selected));
    }

    private static AlgorithmResult verify(TDGraph graph, QuerySpec query) {
        var result = new IScopeAlgorithm().run(graph, query,
                config(10_000, 10_000_000_000L), new ExperimentInstrumentation());
        assertEquals(ExactnessScope.GLOBAL_CERTIFIED, result.exactnessScope());
        assertTrue(result.status() == ExperimentStatus.CERTIFIED_COMPLETE
                || result.status() == ExperimentStatus.NO_FEASIBLE_PATH);
        assertWholeProfile(graph, query, result.profile(), TinyContinuousEnvelopeOracle.solveCells(graph, query));
        return result;
    }

    /**
     * Common-refine every oracle and output cut, then compare exact coefficients
     * on open cells and values at singleton endpoints. Midpoints select already
     * proven affine/constant pieces; expected values are not sampled from output.
     */
    private static void assertWholeProfile(TDGraph graph, QuerySpec query,
            EnvelopeProfile actual, List<TemporalCell> expected) {
        assertEquals(query.departureDomain(), actual.domain());
        TreeSet<ExactFraction> cuts = new TreeSet<>();
        for (TemporalCell cell : expected) {
            cuts.add(cell.start());
            cuts.add(cell.end());
        }
        for (EnvelopeSegment segment : actual.segments()) {
            cuts.add(fraction(segment.interval().start()));
            cuts.add(fraction(segment.interval().end()));
            if (segment.found()) {
                segment.candidate().arrivalProfile().breakpoints().forEach(point -> cuts.add(fraction(point.minute())));
                segment.candidate().scoreProfile().breakpoints().forEach(point -> cuts.add(fraction(point)));
                segment.candidate().domain().intervals().forEach(interval -> {
                    cuts.add(fraction(interval.start()));
                    cuts.add(fraction(interval.end()));
                });
            }
        }
        ExactFraction queryStart = fraction(query.departureStart());
        ExactFraction queryEnd = fraction(query.departureEnd());
        for (EnvelopeSegment segment : actual.segments()) {
            assertTrue(fraction(segment.interval().start()).compareTo(queryStart) >= 0);
            assertTrue(fraction(segment.interval().end()).compareTo(queryEnd) <= 0);
            if (segment.found()) {
                assertEquals(segment.candidate().domain(), segment.candidate().arrivalProfile().domain());
                assertEquals(segment.candidate().domain(), segment.candidate().scoreProfile().domain());
            }
        }
        List<ExactFraction> ordered = cuts.stream().filter(value -> value.compareTo(queryStart) >= 0
                && value.compareTo(queryEnd) <= 0).toList();
        for (int index = 0; index < ordered.size(); index++) {
            compareCell(actual, expected, ordered.get(index), ordered.get(index));
            if (index + 1 < ordered.size()) {
                compareCell(actual, expected, ordered.get(index), ordered.get(index + 1));
            }
        }
    }

    private static void compareCell(EnvelopeProfile actual, List<TemporalCell> expected,
            ExactFraction left, ExactFraction right) {
        boolean point = left.equals(right);
        ExactFraction inside = point ? left : left.add(right).divide(ExactFraction.of(2));
        List<TemporalCell> oracleCells = expected.stream().filter(cell -> contains(cell.start(), cell.end(),
                cell.startInclusive(), cell.endInclusive(), inside)).toList();
        assertEquals(1, oracleCells.size(), "oracle coverage at " + inside);
        List<EnvelopeSegment> actualCells = actual.segments().stream().filter(segment -> contains(
                fraction(segment.interval().start()), fraction(segment.interval().end()),
                segment.interval().startInclusive(), segment.interval().endInclusive(), inside)).toList();
        assertEquals(1, actualCells.size(), "unique output endpoint/cell ownership at " + inside);
        TemporalCell oracle = oracleCells.getFirst();
        EnvelopeSegment segment = actualCells.getFirst();
        assertEquals(oracle.path().isPresent(), segment.found(), "feasibility on " + left + ".." + right);
        if (segment.noPath()) {
            return;
        }
        assertEquals(oracle.path().orElseThrow(), segment.path().arcIds(), "route/tie on " + left + ".." + right);
        var candidate = segment.candidate();
        assertTrue(candidate.domain().intervals().stream().anyMatch(interval -> contains(fraction(interval.start()),
                fraction(interval.end()), interval.startInclusive(), interval.endInclusive(), inside)));
        var expectedValue = oracle.value().orElseThrow();
        List<Integer> scores = candidate.scoreProfile().intervals().stream()
                .filter(interval -> scoreContains(candidate.scoreProfile().intervals(), interval, inside))
                .map(edu.ipcmax.core.profile.ScoreProfile.Interval::value).toList();
        assertEquals(List.of(expectedValue.score()), scores, "whole-cell score at " + inside);
        ExactFraction[] coefficients = affineAt(candidate.arrivalProfile(), inside);
        if (point) {
            assertEquals(expectedValue.arrivalSlope().multiply(inside).add(expectedValue.arrivalIntercept()),
                    coefficients[0].multiply(inside).add(coefficients[1]), "endpoint arrival at " + inside);
        } else {
            assertEquals(expectedValue.arrivalSlope(), coefficients[0], "whole-cell arrival slope " + left + ".." + right);
            assertEquals(expectedValue.arrivalIntercept(), coefficients[1], "whole-cell arrival intercept " + left + ".." + right);
            // Equality of arrival affine functions also establishes travel(t)=arrival(t)-t.
        }
    }

    private static boolean scoreContains(List<edu.ipcmax.core.profile.ScoreProfile.Interval> all,
            edu.ipcmax.core.profile.ScoreProfile.Interval interval, ExactFraction point) {
        ExactFraction start = fraction(interval.startMinute());
        ExactFraction end = fraction(interval.endMinute());
        boolean nextOwnsEnd = all.stream().anyMatch(other -> other != interval
                && fraction(other.startMinute()).equals(end));
        return contains(start, end, true, !nextOwnsEnd, point);
    }

    private static ExactFraction[] affineAt(TimeProfile profile, ExactFraction inside) {
        var points = profile.breakpoints();
        if (points.size() == 1) {
            return new ExactFraction[] { ExactFraction.ZERO, fraction(points.getFirst().value()) };
        }
        for (int index = 1; index < points.size(); index++) {
            var left = points.get(index - 1);
            var right = points.get(index);
            ExactFraction x1 = fraction(left.minute());
            ExactFraction x2 = fraction(right.minute());
            if (inside.compareTo(x1) >= 0 && inside.compareTo(x2) <= 0) {
                ExactFraction slope = fraction(right.value()).subtract(fraction(left.value())).divide(x2.subtract(x1));
                return new ExactFraction[] { slope, fraction(left.value()).subtract(slope.multiply(x1)) };
            }
        }
        throw new AssertionError("arrival breakpoints omit " + inside);
    }

    private static boolean contains(ExactFraction start, ExactFraction end,
            boolean startInclusive, boolean endInclusive, ExactFraction point) {
        return (point.compareTo(start) > 0 || startInclusive && point.equals(start))
                && (point.compareTo(end) < 0 || endInclusive && point.equals(end));
    }

    private static ExactFraction fraction(double value) {
        return ExactFraction.fromDouble(value);
    }

    private static TDGraph switchingGraph() {
        return new TinyGraphBuilder().node(1).node(2).node(3).node(4)
                .edge(1, 2, 2, score(0, 5, 10, 5, 1440, 0)).edge(2, 4, 2)
                .edge(1, 3, 2, score(0, 5, 0, 5, 1440, 10)).edge(3, 4, 2).build();
    }

    private static TDGraph graph(List<Edge> edges) {
        TreeSet<Integer> ids = new TreeSet<>();
        edges.forEach(edge -> { ids.add(edge.source()); ids.add(edge.target()); });
        return new TDGraph(ids.stream().map(id -> new Node(id, id, 0)).toList(), edges);
    }

    private static Edge edge(int arcId, int source, int target, PiecewiseLinearFn travel, PiecewiseConstFn score) {
        return new Edge(arcId, source, target, 1, travel.minTravelTime(), travel, score);
    }

    private static PiecewiseLinearFn travel(double... values) {
        List<PiecewiseLinearFn.Breakpoint> points = new ArrayList<>();
        for (int index = 0; index < values.length; index += 2) {
            points.add(new PiecewiseLinearFn.Breakpoint(values[index], values[index + 1]));
        }
        return new PiecewiseLinearFn(points);
    }

    private static PiecewiseConstFn score(int... values) {
        List<PiecewiseConstFn.Interval> intervals = new ArrayList<>();
        for (int index = 0; index < values.length; index += 3) {
            intervals.add(new PiecewiseConstFn.Interval(values[index], values[index + 1], values[index + 2]));
        }
        return new PiecewiseConstFn(intervals);
    }

    private static AlgorithmConfig config(long maxPaths, long deadlineNanos) {
        return new AlgorithmConfig("iscope", Ablation.NONE,
                edu.ipcmax.core.pcmax.PaceEngineMode.SCALABLE,
                0, 1, 1, 1, 1, 1, 1, 1, 0, 0, maxPaths,
                100_000, 100_000, 100_000, true, 42, deadlineNanos);
    }
}
