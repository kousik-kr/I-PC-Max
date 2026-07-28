package edu.ipcmax.testoracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.EnvelopeProfile;
import edu.ipcmax.core.pcmax.EnvelopeSegment;
import edu.ipcmax.core.pcmax.PACE;
import edu.ipcmax.core.pcmax.PaceExecutionPolicy;
import edu.ipcmax.core.pcmax.PaceFeatures;
import edu.ipcmax.core.pcmax.PaceOptions;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.testoracle.TinyContinuousEnvelopeOracle.Segment;

class PaceExactOracleDifferentialTest {
    private static final int SEEDED_CORPUS_CASES = 1000;
    private static final double HALF_REPOSITORY_TIME_QUANTUM = 0.00000000051;
    private static final PaceFeatures UNCOMPRESSED_EXHAUSTIVE_FEATURES =
            new PaceFeatures(true, true, true, true, false, false);

    @Test
    void scoreBoundaryPullbackMatchesTheFullExactEnvelope() {
        TDGraph graph = graph(
                4,
                edge(0, 1, 2, travel(0, 1, 40, 21), score(0)),
                edge(1, 2, 4, travel(0, 1, 40, 1), scoreSwitch()),
                edge(2, 1, 4, travel(0, 3, 40, 3), score(5)));
        QuerySpec query = new QuerySpec(1, 4, 0, 6, 20, 1);

        List<Segment> expected = TinyContinuousEnvelopeOracle.solve(graph, query);

        assertEquals(List.of(
                segment(ExactFraction.of(0), ExactFraction.of(2), true, false, 2),
                segment(
                        ExactFraction.of(2),
                        ExactFraction.of(14).divide(ExactFraction.of(3)),
                        true,
                        false,
                        0,
                        1),
                segment(
                        ExactFraction.of(14).divide(ExactFraction.of(3)),
                        ExactFraction.of(6),
                        true,
                        true,
                        2)), expected);
        assertMatchesPaceX(graph, query, expected);
    }

    @Test
    void exactBudgetBoundaryAndNoPathMatchTheFullEnvelope() {
        TDGraph graph = graph(
                2,
                edge(0, 1, 2, travel(0, 2, 20, 22), score(10)));
        QuerySpec query = new QuerySpec(1, 2, 0, 6, 3, 1);

        List<Segment> expected = TinyContinuousEnvelopeOracle.solve(graph, query);

        assertEquals(List.of(
                segment(ExactFraction.of(0), ExactFraction.of(1), true, true, 0),
                noPath(ExactFraction.of(1), ExactFraction.of(6), false, true)), expected);
        assertMatchesPaceX(graph, query, expected);
    }

    @Test
    void fractionalTravelTieBetweenParallelArcsMatchesTheFullEnvelope() {
        TDGraph graph = graph(
                2,
                edge(0, 1, 2, travel(0, 5, 40, 5), score(10)),
                edge(1, 1, 2, travel(0, 10.5, 10, 0.5, 40, 0.5), score(10)));
        QuerySpec query = new QuerySpec(1, 2, 0, 10, 20, 1);

        List<Segment> expected = TinyContinuousEnvelopeOracle.solve(graph, query);

        assertEquals(List.of(
                segment(
                        ExactFraction.of(0),
                        ExactFraction.fromDouble(5.5),
                        true,
                        true,
                        0),
                segment(
                        ExactFraction.fromDouble(5.5),
                        ExactFraction.of(10),
                        false,
                        true,
                        1)), expected);
        assertMatchesPaceX(graph, query, expected);
    }

    @Test
    void sourceEqualToDestinationUsesTheIdentityPathAcrossTheWholeInterval() {
        TDGraph graph = graph(1);
        QuerySpec query = new QuerySpec(1, 1, 2, 7, 3, 1);

        List<Segment> expected = TinyContinuousEnvelopeOracle.solve(graph, query);

        assertEquals(List.of(segment(
                ExactFraction.of(2),
                ExactFraction.of(7),
                true,
                true)), expected);
        assertMatchesPaceX(graph, query, expected);
    }

    @Test
    void selfLoopIsExcludedEvenWhenItsScoreWouldOtherwiseDominate() {
        TDGraph graph = graph(
                2,
                edge(0, 1, 1, travel(0, 1, 20, 1), scoreTo(20, 100)),
                edge(1, 1, 2, travel(0, 2, 20, 2), scoreTo(20, 1)));
        QuerySpec query = new QuerySpec(1, 2, 0, 5, 5, 1);

        List<Segment> expected = TinyContinuousEnvelopeOracle.solve(graph, query);

        assertEquals(List.of(segment(
                ExactFraction.of(0),
                ExactFraction.of(5),
                true,
                true,
                1)), expected);
        assertMatchesPaceX(graph, query, expected);
    }

    @Test
    void anchorRemainsTraversableWhereItsScoreIsZero() {
        PiecewiseConstFn score = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 8, 0),
                new PiecewiseConstFn.Interval(8, 9, 7),
                new PiecewiseConstFn.Interval(9, 20, 0)));
        TDGraph graph = graph(
                2,
                edge(0, 1, 2, travel(0, 1, 20, 1), score));
        QuerySpec query = new QuerySpec(1, 2, 0, 5, 5, 1);

        List<Segment> expected = TinyContinuousEnvelopeOracle.solve(graph, query);

        assertEquals(List.of(segment(
                ExactFraction.of(0),
                ExactFraction.of(5),
                true,
                true,
                0)), expected);
        assertMatchesPaceX(graph, query, expected);
    }

    @Test
    void disjointFeasiblePathCellsLeaveAnExplicitNoPathGap() {
        TDGraph graph = graph(
                2,
                edge(0, 1, 2, travel(0, 2, 20, 22), scoreTo(20, 1)),
                edge(1, 1, 2, travel(0, 7, 7, 0, 20, 0), scoreTo(20, 1)));
        QuerySpec query = new QuerySpec(1, 2, 0, 6, 3, 1);

        List<Segment> expected = TinyContinuousEnvelopeOracle.solve(graph, query);

        assertEquals(List.of(
                segment(ExactFraction.of(0), ExactFraction.of(1), true, true, 0),
                noPath(ExactFraction.of(1), ExactFraction.of(4), false, false),
                segment(ExactFraction.of(4), ExactFraction.of(6), true, true, 1)), expected);
        assertMatchesPaceX(graph, query, expected);
    }

    @Test
    void coincidentScoreAndBudgetRootsCanProduceASingletonWinner() {
        PiecewiseConstFn boundaryScore = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 1, 5),
                new PiecewiseConstFn.Interval(1, 20, 10)));
        TDGraph graph = graph(
                2,
                edge(0, 1, 2, travel(0, 2, 20, 22), boundaryScore),
                edge(1, 1, 2, travel(0, 3, 20, 3), scoreTo(20, 6)));
        QuerySpec query = new QuerySpec(1, 2, 0, 6, 3, 1);

        List<Segment> expected = TinyContinuousEnvelopeOracle.solve(graph, query);

        assertEquals(List.of(
                segment(ExactFraction.of(0), ExactFraction.of(1), true, false, 1),
                segment(ExactFraction.of(1), ExactFraction.of(1), true, true, 0),
                segment(ExactFraction.of(1), ExactFraction.of(6), false, true, 1)), expected);
        assertMatchesPaceX(graph, query, expected);
    }

    @Test
    void multiEdgeBudgetRootUsesTheExactRationalTransition() {
        TDGraph graph = graph(
                3,
                edge(0, 1, 2, travel(0, 3, 8, 4.5, 40, 4.5), score(0)),
                edge(1, 2, 3, travel(0, 1.25, 8, 1.25, 16, 1, 40, 0.75), score(0)));
        QuerySpec query = new QuerySpec(1, 3, 0, 8, 5.5, 1);

        List<Segment> expected = TinyContinuousEnvelopeOracle.solve(graph, query);
        ExactFraction root = ExactFraction.of(560).divide(ExactFraction.of(77));

        assertEquals(List.of(
                segment(ExactFraction.of(0), root, true, true, 0, 1),
                noPath(root, ExactFraction.of(8), false, true)), expected);
        assertMatchesPaceX(graph, query, expected);
    }

    @Test
    void fixedSeedTinyFifoDagsWithParallelArcsMatchCompletePaceXEnvelopes() {
        Random random = new Random(0x50414345L);
        List<Executable> comparisons = new ArrayList<>();
        for (int caseIndex = 0; caseIndex < SEEDED_CORPUS_CASES; caseIndex++) {
            TDGraph graph = randomDagWithParallelArcs(random);
            QuerySpec query = new QuerySpec(1, 4, 0, 8, 5 + random.nextInt(6), 1);
            List<Segment> expected = TinyContinuousEnvelopeOracle.solve(graph, query);
            int retainedIndex = caseIndex;
            comparisons.add(() -> assertMatchesPaceX(
                    "fixed-seed case " + retainedIndex + " " + describe(graph) + " query=" + query,
                    graph,
                    query,
                    expected,
                    false));
        }
        assertAll(comparisons);
    }

    @Test
    void uncompressedPaceXMatchesCompressedPaceXOnSeededCorpus() {
        Random random = new Random(0x554e434f4d505245L);
        PaceOptions uncompressed = new PaceOptions(
                PaceExecutionPolicy.PACE_X,
                4,
                PaceOptions.UNBOUNDED,
                PaceOptions.UNBOUNDED,
                1,
                true,
                UNCOMPRESSED_EXHAUSTIVE_FEATURES,
                Integer.MAX_VALUE);
        List<Executable> comparisons = new ArrayList<>();
        for (int caseIndex = 0; caseIndex < 64; caseIndex++) {
            TDGraph graph = randomDagWithParallelArcs(random);
            QuerySpec query = new QuerySpec(1, 4, 0, 8, 5 + random.nextInt(6), 1);
            int retainedIndex = caseIndex;
            comparisons.add(() -> {
                EnvelopeProfile compressed = new PACE(graph, PaceOptions.exhaustive(4)).run(query);
                EnvelopeProfile exactDuplicatesOnly = new PACE(graph, uncompressed).run(query);
                assertEquals(normalize(compressed), normalize(exactDuplicatesOnly),
                        "uncompressed PACE-X differs on fixed-seed case " + retainedIndex);
            });
        }
        assertAll(comparisons);
    }

    private static void assertMatchesPaceX(
            TDGraph graph,
            QuerySpec query,
            List<Segment> expected) {
        assertMatchesPaceX("deterministic fixture", graph, query, expected);
    }

    private static void assertMatchesPaceX(
            String context,
            TDGraph graph,
            QuerySpec query,
            List<Segment> expected) {
        assertMatchesPaceX(context, graph, query, expected, true);
    }

    private static void assertMatchesPaceX(
            String context,
            TDGraph graph,
            QuerySpec query,
            List<Segment> expected,
            boolean assertBoundaryOwnership) {
        assertNormalizedOracleEnvelope(context, query, expected);
        EnvelopeProfile actual = new PACE(graph, uncompressedExhaustive(graph.edgeCount())).run(query);
        assertEquals(expected.size(), actual.segments().size(),
                () -> context + " expected=" + expected + " actual=" + normalize(actual));
        String details = context + " expected=" + expected + " actual=" + normalize(actual);
        for (int i = 0; i < expected.size(); i++) {
            Segment expectedSegment = expected.get(i);
            EnvelopeSegment actualSegment = actual.segments().get(i);
            assertEquals(expectedSegment.start().toDouble(), actualSegment.interval().start(),
                    HALF_REPOSITORY_TIME_QUANTUM, details + " segment " + i + " start");
            assertEquals(expectedSegment.end().toDouble(), actualSegment.interval().end(),
                    HALF_REPOSITORY_TIME_QUANTUM, details + " segment " + i + " end");
            if (assertBoundaryOwnership) {
                assertEquals(expectedSegment.startInclusive(), actualSegment.interval().startInclusive(),
                        context + " segment " + i + " start ownership");
                assertEquals(expectedSegment.endInclusive(), actualSegment.interval().endInclusive(),
                        context + " segment " + i + " end ownership");
            }
            Optional<List<Integer>> actualPath = actualSegment.noPath()
                    ? Optional.empty()
                    : Optional.of(actualSegment.path().arcIds());
            assertEquals(expectedSegment.path(), actualPath, context + " segment " + i + " assignment");
        }
    }

    private static PaceOptions uncompressedExhaustive(int theta) {
        return new PaceOptions(
                PaceExecutionPolicy.PACE_X,
                theta,
                PaceOptions.UNBOUNDED,
                PaceOptions.UNBOUNDED,
                1,
                true,
                UNCOMPRESSED_EXHAUSTIVE_FEATURES,
                Integer.MAX_VALUE);
    }

    private static void assertNormalizedOracleEnvelope(
            String context,
            QuerySpec query,
            List<Segment> segments) {
        assertEquals(ExactFraction.of(query.departureStart()), segments.get(0).start(),
                context + " oracle coverage start");
        assertEquals(true, segments.get(0).startInclusive(), context + " oracle start ownership");
        Segment last = segments.get(segments.size() - 1);
        assertEquals(ExactFraction.of(query.departureEnd()), last.end(),
                context + " oracle coverage end");
        assertEquals(true, last.endInclusive(), context + " oracle end ownership");
        for (int i = 1; i < segments.size(); i++) {
            Segment previous = segments.get(i - 1);
            Segment next = segments.get(i);
            assertEquals(previous.end(), next.start(), context + " oracle boundary " + i);
            assertEquals(true, previous.endInclusive() ^ next.startInclusive(),
                    context + " oracle boundary ownership " + i);
            assertEquals(false, previous.path().equals(next.path()),
                    context + " oracle maximality " + i);
        }
    }

    private static List<String> normalize(EnvelopeProfile profile) {
        List<String> normalized = new ArrayList<>();
        for (EnvelopeSegment segment : profile.segments()) {
            normalized.add(segment.interval() + "="
                    + (segment.noPath() ? "NO_PATH" : segment.path().arcIds()));
        }
        return normalized;
    }

    private static Segment segment(
            ExactFraction start,
            ExactFraction end,
            boolean startInclusive,
            boolean endInclusive,
            Integer... path) {
        return new Segment(
                start,
                end,
                startInclusive,
                endInclusive,
                Optional.of(List.of(path)));
    }

    private static Segment noPath(
            ExactFraction start,
            ExactFraction end,
            boolean startInclusive,
            boolean endInclusive) {
        return new Segment(start, end, startInclusive, endInclusive, Optional.empty());
    }

    private static TDGraph graph(int nodeCount, Edge... edges) {
        List<Node> nodes = new ArrayList<>();
        for (int nodeId = 1; nodeId <= nodeCount; nodeId++) {
            nodes.add(new Node(nodeId, nodeId, nodeId));
        }
        return new TDGraph(nodes, List.of(edges));
    }

    private static Edge edge(
            int arcId,
            int source,
            int target,
            PiecewiseLinearFn travel,
            PiecewiseConstFn score) {
        return new Edge(arcId, source, target, 1, travel.minTravelTime(), travel, score);
    }

    private static PiecewiseLinearFn travel(double... minuteValuePairs) {
        List<PiecewiseLinearFn.Breakpoint> points = new ArrayList<>();
        for (int i = 0; i < minuteValuePairs.length; i += 2) {
            points.add(new PiecewiseLinearFn.Breakpoint(
                    minuteValuePairs[i],
                    minuteValuePairs[i + 1]));
        }
        return new PiecewiseLinearFn(points);
    }

    private static PiecewiseConstFn score(int value) {
        return new PiecewiseConstFn(List.of(new PiecewiseConstFn.Interval(0, 40, value)));
    }

    private static PiecewiseConstFn scoreTo(double horizon, int value) {
        return new PiecewiseConstFn(List.of(new PiecewiseConstFn.Interval(0, horizon, value)));
    }

    private static PiecewiseConstFn scoreSwitch() {
        return new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 4, 0),
                new PiecewiseConstFn.Interval(4, 8, 8),
                new PiecewiseConstFn.Interval(8, 40, 0)));
    }

    private static TDGraph randomDagWithParallelArcs(Random random) {
        List<Edge> edges = new ArrayList<>();
        addRandomEdge(edges, random, 1, 2);
        addRandomEdge(edges, random, 2, 3);
        addRandomEdge(edges, random, 3, 4);
        addRandomEdge(edges, random, 1, 4);
        addRandomEdge(edges, random, 1, 4);
        if (random.nextBoolean()) {
            addRandomEdge(edges, random, 1, 3);
        }
        if (random.nextBoolean()) {
            addRandomEdge(edges, random, 2, 4);
        }
        return graph(4, edges.toArray(Edge[]::new));
    }

    private static void addRandomEdge(
            List<Edge> edges,
            Random random,
            int source,
            int target) {
        double first = 1.0 + random.nextInt(17) / 4.0;
        double second = Math.max(0.25, first + (random.nextInt(13) - 4) / 4.0);
        double third = Math.max(0.25, second + (random.nextInt(13) - 4) / 4.0);
        double fourth = Math.max(0.25, third + (random.nextInt(17) - 6) / 4.0);
        PiecewiseLinearFn travel = travel(
                0, first,
                8, second,
                16, third,
                40, fourth);
        PiecewiseConstFn score = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 4, random.nextInt(7)),
                new PiecewiseConstFn.Interval(4, 8, random.nextInt(7)),
                new PiecewiseConstFn.Interval(8, 12, random.nextInt(7)),
                new PiecewiseConstFn.Interval(12, 40, random.nextInt(7))));
        edges.add(edge(edges.size(), source, target, travel, score));
    }

    private static String describe(TDGraph graph) {
        List<String> edges = new ArrayList<>();
        for (Edge edge : graph.edges()) {
            edges.add(edge.arcId() + ":" + edge.source() + "->" + edge.target()
                    + ":travel=" + edge.travelTimeFunction().breakpoints()
                    + ":score=" + edge.scoreFunction().intervals());
        }
        return edges.toString();
    }
}
