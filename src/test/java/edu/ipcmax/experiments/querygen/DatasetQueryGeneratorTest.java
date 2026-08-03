package edu.ipcmax.experiments.querygen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.core.loader.GeneratedGraphDataset;
import edu.ipcmax.core.loader.ManifestSummary;
import edu.ipcmax.experiments.framework.QueryManifestEntry;

class DatasetQueryGeneratorTest {
    private static final String GRAPH_CHECKSUM = "0123456789abcdef".repeat(4);
    private static final String CONFIG_HASH = "fedcba9876543210".repeat(4);
    private static final int DESTINATION = 10_000;
    private static final int CONFIGURED_SOURCES = 256;

    private static QueryGenerationConfig configuration;
    private static DatasetQueryGenerator.DatasetQuerySets calSets;

    @BeforeAll
    static void generateCanonicalFixture() throws Exception {
        configuration = QueryGenerationConfig.load(
                Path.of("experiments/configs/query_generation.yaml"));
        Fixture fixture = fixture("CAL", 9, Set.of());
        calSets = generate(fixture, "CAL");
    }

    @Test
    void generatesEveryConfiguredSetAtItsExactBalancedCount() {
        assertEquals(96, calSets.main().size());
        assertEquals(32, calSets.pilot().size());
        assertEquals(48, calSets.sensitivity().size());
        assertEquals(16, calSets.appendix().size());
        assertEquals(24, calSets.parallelism().size());
        assertEquals(96, calSets.tightBudget().size());
        assertEquals(240, calSets.windowSensitivity().size());
        assertEquals(192, calSets.budgetSensitivity().size());
        assertEquals(744, calSets.membershipCount());
    }

    @Test
    void keepsPilotPairsDisjointAndDerivedSetsNested() {
        Set<String> mainPairs = pairFamilyIds(calSets.main());
        Set<String> pilotPairs = pairFamilyIds(calSets.pilot());
        Set<String> sensitivityQueries = queryIds(calSets.sensitivity());
        Set<String> appendixQueries = queryIds(calSets.appendix());

        assertEquals(24, mainPairs.size());
        assertEquals(8, pilotPairs.size());
        assertTrue(java.util.Collections.disjoint(mainPairs, pilotPairs));
        assertTrue(queryIds(calSets.main()).containsAll(sensitivityQueries));
        assertTrue(sensitivityQueries.containsAll(appendixQueries));
        assertEquals(12, pairFamilyIds(calSets.sensitivity()).size());
        assertEquals(4, pairFamilyIds(calSets.appendix()).size());
    }

    @Test
    void parallelismIsExactlyTheDeclaredFourCellsAndUsesMainQueries() {
        Map<String, Long> cells = new TreeMap<>();
        for (QueryManifestEntry query : calSets.parallelism()) {
            assertTrue(query.distanceBinValue() == DistanceBin.Q3
                    || query.distanceBinValue() == DistanceBin.Q4);
            assertTrue(query.temporalRegime() == TemporalRegime.MORNING_PEAK
                    || query.temporalRegime() == TemporalRegime.EVENING_PEAK);
            String cell = query.distanceBinValue() + ":" + query.temporalRegime();
            cells.merge(cell, 1L, Long::sum);
        }

        assertEquals(Map.of(
                "Q3:EVENING_PEAK", 6L,
                "Q3:MORNING_PEAK", 6L,
                "Q4:EVENING_PEAK", 6L,
                "Q4:MORNING_PEAK", 6L), cells);
        assertTrue(queryIds(calSets.main()).containsAll(queryIds(calSets.parallelism())));
    }

    @Test
    void formatsStablePairTemporalMainAndTightIds() {
        String familyId = "CAL-Q3-P004-EVENING_PEAK";
        String mainId = familyId + "-W120-RHO025";
        String tightId = familyId + "-W120-TIGHT";

        QueryManifestEntry main = byId(calSets.main(), mainId);
        QueryManifestEntry tight = byId(calSets.tightBudget(), tightId);
        assertEquals("CAL-Q3-P004", main.pairFamilyId());
        assertEquals(familyId, main.queryFamilyId());
        assertEquals(main.pairFamilyId(), tight.pairFamilyId());
        assertEquals(main.queryFamilyId(), tight.queryFamilyId());

        Fixture repeatedFixture = fixture("CAL", 9, Set.of());
        DatasetQueryGenerator.DatasetQuerySets repeated = generate(repeatedFixture, "CAL");
        assertEquals(allQueryIds(calSets), allQueryIds(repeated));
    }

    @Test
    void replacesARejectedFamilyFromTheSameDistanceBin() {
        int rejectedSource = sourceId(DistanceBin.Q1, 1, 9);
        Fixture fixture = fixture("CAL", 9, Set.of(rejectedSource));

        DatasetQueryGenerator.DatasetQuerySets result = generate(fixture, "CAL");

        assertEquals(96, result.main().size());
        assertEquals(32, result.pilot().size());
        assertFalse(Stream.concat(result.main().stream(), result.pilot().stream())
                .anyMatch(query -> query.source() == rejectedSource));
        assertEquals(1L, result.rejectionCounts().get("replacement_selected"));
    }

    @Test
    void failsWithABalanceReportWhenASetCannotBeFilled() {
        Fixture fixture = fixture("CAL", 7, Set.of());

        DatasetQueryGenerator.BalanceException failure = assertThrows(
                DatasetQueryGenerator.BalanceException.class,
                () -> generate(fixture, "CAL"));

        assertNotNull(failure.report());
        assertTrue(failure.getMessage().contains("CAL"));
        assertTrue(failure.report().toString().contains("Q1"));
    }

    @Test
    void handlesOlWithTheSameStructureAsEveryOtherDatasetId() {
        Fixture olFixture = fixture("OL", 9, Set.of());
        DatasetQueryGenerator.DatasetQuerySets ol = generate(olFixture, "OL");

        assertEquals(calSets.membershipCount(), ol.membershipCount());
        assertEquals(structuralSignatures(calSets, "CAL"), structuralSignatures(ol, "OL"));
        assertTrue(allQueryIds(ol).stream().allMatch(id -> id.startsWith("OL-")));
        assertTrue(allEntries(ol).allMatch(entry -> "OL".equals(entry.datasetId())
                && entry.datasetPath().replace('\\', '/').endsWith("data/input/OL")));
    }

    private static DatasetQueryGenerator.DatasetQuerySets generate(Fixture fixture, String datasetId) {
        return new DatasetQueryGenerator().generate(
                fixture.dataset(),
                datasetId,
                GRAPH_CHECKSUM,
                CONFIG_HASH,
                configuration,
                configuration.seed(),
                fixture.sampling());
    }

    private static Fixture fixture(String datasetId, int candidatesPerBin, Set<Integer> longTravelSources) {
        TinyGraphBuilder graphBuilder = new TinyGraphBuilder();
        for (int node = 1; node <= CONFIGURED_SOURCES; node++) {
            graphBuilder.node(node);
        }
        graphBuilder.node(DESTINATION);

        PiecewiseConstFn positiveScore = PiecewiseConstFn.constant(Domain.closed(0, 1440), 1);
        List<QueryPairCandidate> candidates = new ArrayList<>();
        EnumMap<DistanceBin, List<QueryPairCandidate>> bins = new EnumMap<>(DistanceBin.class);
        for (DistanceBin bin : DistanceBin.values()) {
            List<QueryPairCandidate> binCandidates = new ArrayList<>();
            for (int index = 1; index <= candidatesPerBin; index++) {
                int source = sourceId(bin, index, candidatesPerBin);
                double travelTime = longTravelSources.contains(source) ? 300 : 1;
                graphBuilder.edge(source, DESTINATION, travelTime, positiveScore);
                QueryPairCandidate candidate = new QueryPairCandidate(
                        datasetId,
                        source,
                        DESTINATION,
                        travelTime,
                        5,
                        1,
                        source - 1);
                binCandidates.add(candidate);
                candidates.add(candidate);
            }
            bins.put(bin, List.copyOf(binCandidates));
        }

        TDGraph graph = graphBuilder.build();
        ManifestSummary manifest = new ManifestSummary(
                graph.nodeCount(),
                graph.edgeCount(),
                42,
                graph.edgeCount(),
                true,
                java.util.Optional.of(new ManifestSummary.TimeWindow(0, 1440)),
                Map.of(
                        "morning", new ManifestSummary.TimeWindow(420, 600),
                        "evening", new ManifestSummary.TimeWindow(1020, 1200)));
        GeneratedGraphDataset dataset = new GeneratedGraphDataset(
                graph, manifest, Path.of("data/input", datasetId));
        List<Integer> sampledSources = java.util.stream.IntStream
                .rangeClosed(1, CONFIGURED_SOURCES)
                .boxed()
                .toList();
        QueryCandidateSampler.SamplingResult sampling = new QueryCandidateSampler.SamplingResult(
                datasetId,
                GRAPH_CHECKSUM,
                ManifestChecksum.deriveDatasetSeed(configuration.seed(), datasetId, GRAPH_CHECKSUM),
                CONFIGURED_SOURCES,
                sampledSources,
                CONFIGURED_SOURCES,
                candidates.size(),
                candidates,
                bins,
                quartiles(candidatesPerBin));
        return new Fixture(dataset, sampling);
    }

    private static int sourceId(DistanceBin bin, int oneBasedIndex, int candidatesPerBin) {
        return bin.ordinal() * candidatesPerBin + oneBasedIndex;
    }

    private static QueryCandidateSampler.DistanceQuartiles quartiles(int candidatesPerBin) {
        return new QueryCandidateSampler.DistanceQuartiles(
                100d + candidatesPerBin,
                200d + candidatesPerBin,
                300d + candidatesPerBin);
    }

    private static QueryManifestEntry byId(List<QueryManifestEntry> entries, String id) {
        return entries.stream()
                .filter(entry -> entry.queryId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing query id: " + id));
    }

    private static Set<String> pairFamilyIds(List<QueryManifestEntry> entries) {
        return entries.stream()
                .map(QueryManifestEntry::pairFamilyId)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Set<String> queryIds(List<QueryManifestEntry> entries) {
        return entries.stream()
                .map(QueryManifestEntry::queryId)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static List<String> allQueryIds(DatasetQueryGenerator.DatasetQuerySets sets) {
        return allEntries(sets).map(QueryManifestEntry::queryId).toList();
    }

    private static List<String> structuralSignatures(
            DatasetQueryGenerator.DatasetQuerySets sets, String datasetId) {
        return allEntries(sets)
                .map(entry -> entry.queryId().replaceFirst("^" + datasetId + "-", "DATASET-")
                        + "|" + entry.source()
                        + "|" + entry.destination()
                        + "|" + entry.distanceBinValue()
                        + "|" + entry.temporalRegime()
                        + "|" + entry.intervalStart()
                        + "|" + entry.intervalEnd()
                        + "|" + entry.budget()
                        + "|" + entry.budgetPolicy())
                .toList();
    }

    private static Stream<QueryManifestEntry> allEntries(
            DatasetQueryGenerator.DatasetQuerySets sets) {
        return Stream.of(
                        sets.main(),
                        sets.pilot(),
                        sets.sensitivity(),
                        sets.appendix(),
                        sets.parallelism(),
                        sets.tightBudget(),
                        sets.windowSensitivity(),
                        sets.budgetSensitivity())
                .flatMap(List::stream);
    }

    private record Fixture(
            GeneratedGraphDataset dataset,
            QueryCandidateSampler.SamplingResult sampling) {
    }
}
