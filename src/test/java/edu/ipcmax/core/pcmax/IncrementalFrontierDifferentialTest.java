package edu.ipcmax.core.pcmax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;

class IncrementalFrontierDifferentialTest {
    private static final Domain ROOT = Domain.closed(0, 10);

    @Test
    void incrementalMatchesBatchOracleAfterEveryInsertionAndPermutation() {
        TDGraph graph = graph();
        List<CandidateProfile> candidates = candidates();
        List<List<Integer>> permutations = List.of(
                List.of(0, 1, 2, 3),
                List.of(3, 2, 1, 0),
                List.of(1, 3, 0, 2),
                List.of(2, 0, 3, 1),
                List.of(0, 2, 1, 3),
                List.of(3, 1, 2, 0));
        String expectedFinal = null;
        int comparisons = 0;

        for (List<Integer> order : permutations) {
            PaceOptions options = PaceOptions.bounded(
                    4, 4, 8, 1_000_000,
                    3, 1_000_000, 10_000_000, 1);
            PaceWorkLedger ledger =
                    new PaceWorkLedger(options);
            IncrementalFrontier incremental =
                    new IncrementalFrontier(
                            graph, ROOT, 20, 1, 4,
                            options, ledger);
            CandidateSet batch = new CandidateSet();
            for (int position = 0;
                    position < order.size();
                    position++) {
                CandidateProfile offered =
                        candidates.get(order.get(position));
                incremental.insert(
                        offered,
                        "permutation=" + order
                                + ":position=" + position);

                CandidateSet batchInput =
                        new CandidateSet();
                batchInput.addAll(batch);
                batchInput.add(offered);
                batch = FrontierCompressor.compress(
                        graph,
                        batchInput,
                        ROOT,
                        20,
                        options.effectiveFrontierLimit(),
                        options.policy(),
                        1,
                        4,
                        options.features());

                CandidateSet actual =
                        incremental.candidates();
                assertEquals(
                        batch.temporalCells(),
                        actual.temporalCells(),
                        "canonical cells at " + order
                                + " position " + position);
                assertEquals(
                        retainedIdsByCell(batch),
                        retainedIdsByCell(actual),
                        "retained IDs at " + order
                                + " position " + position);
                assertEquals(
                        semanticEnvelope(batch),
                        semanticEnvelope(actual),
                        "envelope at " + order
                                + " position " + position);
                assertEquals(
                        semanticChecksum(batch),
                        semanticChecksum(actual),
                        "semantic checksum at " + order
                                + " position " + position);
                assertFalse(
                        ledger.capStatus().any(),
                        "incremental oracle comparison was not COMPLETE at "
                                + order + " position " + position);
                comparisons++;
            }
            String finalSemantic =
                    semanticEnvelope(incremental.candidates());
            if (expectedFinal == null) {
                expectedFinal = finalSemantic;
            } else {
                assertEquals(
                        expectedFinal,
                        finalSemantic,
                        "candidate order changed semantic output");
            }
        }
        assertEquals(24, comparisons);
        assertFalse(expectedFinal.isBlank());
    }

    @Test
    void deterministicRandomizedCorpusMatchesAfterEveryInsertion()
            throws Exception {
        TDGraph graph = graph();
        long comparisons = 0;
        long mismatches = 0;
        long capActivations = 0;
        List<String> mismatchDetails = new ArrayList<>();

        try (IPCMaxParallelExecutor executor =
                     new IPCMaxParallelExecutor(4)) {
            for (int seed = 0; seed < 32; seed++) {
                List<CandidateProfile> corpus =
                        randomizedCandidates(seed);
                for (int kf : List.of(1, 2, 3, 4)) {
                    for (int permutation = 0;
                            permutation < 3;
                            permutation++) {
                        List<Integer> order =
                                shuffledOrder(
                                        corpus.size(),
                                        seed,
                                        permutation);
                        boolean cappedScenario =
                                seed % 8 == 0
                                && permutation == 2
                                && kf == 2;
                        PaceOptions options =
                                PaceOptions.bounded(
                                        4,
                                        4,
                                        8,
                                        1_000_000,
                                        kf,
                                        1_000_000,
                                        cappedScenario
                                                ? 35
                                                : 10_000_000,
                                        4);
                        PaceWorkLedger ledger =
                                new PaceWorkLedger(options);
                        IncrementalFrontier incremental =
                                new IncrementalFrontier(
                                        graph,
                                        ROOT,
                                        30,
                                        1,
                                        4,
                                        options,
                                        ledger,
                                        PaceExecutionMetrics.none(),
                                        executor);
                        CandidateSet batch =
                                new CandidateSet();
                        for (int position = 0;
                                position < order.size();
                                position++) {
                            CandidateProfile offered =
                                    corpus.get(
                                            order.get(position));
                            incremental.insert(
                                    offered,
                                    "seed=" + seed
                                            + ":kf=" + kf
                                            + ":permutation="
                                            + permutation
                                            + ":position="
                                            + position);
                            boolean capped =
                                    ledger.capStatus().reached(
                                            PaceCapKind
                                                    .QUERY_WORK_M_Q);
                            if (!capped) {
                                CandidateSet batchInput =
                                        new CandidateSet();
                                batchInput.addAll(batch);
                                batchInput.add(offered);
                                batch = FrontierCompressor
                                        .compress(
                                                graph,
                                                batchInput,
                                                ROOT,
                                                30,
                                                kf,
                                                options.policy(),
                                                1,
                                                4,
                                                options.features());
                            } else {
                                capActivations++;
                            }
                            CandidateSet actual =
                                    incremental.candidates();
                            List<String> differences =
                                    differences(
                                            batch,
                                            actual,
                                            capped
                                                    ? "CAP_REACHED"
                                                    : "COMPLETE",
                                            capped
                                                    ? "CAP_REACHED"
                                                    : "COMPLETE");
                            comparisons++;
                            if (!differences.isEmpty()) {
                                mismatches++;
                                String detail =
                                        "seed=" + seed
                                                + ",kf=" + kf
                                                + ",permutation="
                                                + permutation
                                                + ",position="
                                                + position
                                                + ":"
                                                + differences;
                                if (mismatchDetails.isEmpty()) {
                                    detail += "\nexpected_cells="
                                            + batch.temporalCells()
                                            + "\nactual_cells="
                                            + actual.temporalCells()
                                            + "\nexpected_ids="
                                            + retainedIdsByCell(batch)
                                            + "\nactual_ids="
                                            + retainedIdsByCell(actual)
                                            + "\nexpected_envelope="
                                            + semanticEnvelope(batch)
                                            + "\nactual_envelope="
                                            + semanticEnvelope(actual);
                                }
                                mismatchDetails.add(detail);
                            }
                            if (capped) {
                                break;
                            }
                        }
                    }
                }
            }
        }

        Path report = Path.of(
                "target",
                "pace-incremental-differential-corpus.json");
        Files.createDirectories(report.getParent());
        Files.writeString(
                report,
                "{\n"
                        + "  \"schema_version\": 1,\n"
                        + "  \"seed_count\": 32,\n"
                        + "  \"kf_values\": [1,2,3,4],\n"
                        + "  \"permutations_per_seed\": 3,\n"
                        + "  \"comparisons\": "
                        + comparisons + ",\n"
                        + "  \"cap_activations\": "
                        + capActivations + ",\n"
                        + "  \"mismatches\": "
                        + mismatches + "\n"
                        + "}\n",
                StandardCharsets.UTF_8);
        System.out.println(
                "PACE_INCREMENTAL_DIFFERENTIAL comparisons="
                        + comparisons
                        + " cap_activations="
                        + capActivations
                        + " mismatches=" + mismatches);
        assertTrue(
                comparisons >= 2_000,
                "randomized corpus was unexpectedly small");
        assertTrue(
                capActivations > 0,
                "randomized corpus did not activate M_q");
        assertEquals(
                0,
                mismatches,
                String.join("\n", mismatchDetails));
    }

    @Test
    void typedMqCapLeavesLastValidFrontierAndExplicitStatus() {
        TDGraph graph = graph();
        PaceOptions options = PaceOptions.bounded(
                1, 1, 1, 10,
                1, 100, 1, 1);
        PaceWorkLedger ledger =
                new PaceWorkLedger(options);
        IncrementalFrontier frontier =
                new IncrementalFrontier(
                        graph, ROOT, 20, 1, 4,
                        options, ledger);

        assertFalse(frontier.insert(
                candidates().get(0), "capped"));
        assertTrue(frontier.candidates().isEmpty());
        assertEquals(
                1,
                ledger.typedWork(
                        PaceWorkKind.CANDIDATE_OFFER));
        assertEquals(
                0,
                ledger.typedWork(
                        PaceWorkKind.AFFECTED_CELL_EVALUATION));
        assertTrue(ledger.capStatus().reached(
                PaceCapKind.QUERY_WORK_M_Q));
    }

    @Test
    void dominanceRequiresUsedPivotSubset() {
        TDGraph graph = graph();
        CandidateProfile fewer = candidate(
                ROOT,
                List.of(0),
                linear(ROOT, 5, 15, "fewer"),
                ScoreProfile.constant(ROOT, 9),
                Set.of(0));
        CandidateProfile more = candidate(
                ROOT,
                List.of(5),
                linear(ROOT, 5, 15, "more"),
                ScoreProfile.constant(ROOT, 8),
                Set.of(5));
        Domain.Interval cell =
                ROOT.intervals().get(0);

        assertFalse(edu.ipcmax.core.profile.SafeProfileDominance
                .dominates(
                        graph, fewer, more, cell, 1, 4));
    }

    @Test
    void adjacentTickOpenCellUsesExactProfileClosures() {
        long startTick = 475_163_463_525_451L;
        double start = Domain.timeFromTick(startTick);
        double end = Domain.timeFromTick(startTick + 1);
        Domain open = Domain.open(start, end);
        CandidateProfile left = candidate(
                open,
                List.of(0),
                linear(open, 480, 480.000000000001,
                        "adjacent-left"),
                ScoreProfile.constant(open, 7),
                Set.of());
        CandidateProfile right = candidate(
                open,
                List.of(0),
                linear(open, 480, 480.000000000001,
                        "adjacent-right"),
                ScoreProfile.constant(open, 7),
                Set.of());

        assertEquals(
                start,
                ProfileCellPartition.midpoint(
                        open.intervals().get(0)));
        assertFalse(open.contains(start));
        assertEquals(7,
                left.scoreProfile().valueAtClosure(start));
        assertEquals(
                7,
                left.scoreProfile()
                        .restrict(open)
                        .valueAtClosure(start));
        assertEquals(
                List.of(start, end),
                ProfileCellPartition
                        .travelEqualityBreakpoints(
                                left, right, open));
    }

    private static TDGraph graph() {
        return new TinyGraphBuilder()
                .node(1).node(2).node(3).node(4)
                .edge(1, 4, 1)
                .edge(1, 2, 1)
                .edge(2, 4, 1)
                .edge(1, 3, 1)
                .edge(3, 4, 1)
                .edge(1, 4, 1)
                .build();
    }

    private static List<CandidateProfile> candidates() {
        CandidateProfile direct = candidate(
                ROOT,
                List.of(0),
                linear(ROOT, 5, 15, "direct"),
                score(10, 4, "direct-score"),
                Set.of());
        CandidateProfile viaTwo = candidate(
                ROOT,
                List.of(1, 2),
                linear(ROOT, 5, 15, "via-two"),
                score(8, 8, "via-two-score"),
                Set.of());
        CandidateProfile viaThree = candidate(
                ROOT,
                List.of(3, 4),
                linear(ROOT, 4, 16, "via-three"),
                score(8, 8, "via-three-score"),
                Set.of());
        Domain partial = Domain.closed(2, 8);
        CandidateProfile parallel = candidate(
                partial,
                List.of(5),
                linear(partial, 8, 14, "parallel"),
                ScoreProfile.constant(partial, 12),
                Set.of());
        return List.of(
                direct, viaTwo, viaThree, parallel);
    }

    private static List<CandidateProfile> randomizedCandidates(
            int seed) {
        Random random = new Random(
                0x50414345L + seed);
        List<Domain> domains = List.of(
                Domain.closed(0, 10),
                Domain.closed(0, 5),
                Domain.closed(5, 10),
                Domain.closed(2, 8),
                Domain.closed(0, 10),
                Domain.closed(5, 10),
                Domain.closed(0, 5),
                Domain.closed(2, 8));
        List<Integer> kinds =
                List.of(0, 1, 2, 3, 0, 2, 1, 3);
        List<CandidateProfile> baseProfiles =
                new ArrayList<>();
        List<List<Integer>> uniquePaths =
                List.of(
                        List.of(0),
                        List.of(5),
                        List.of(1, 2),
                        List.of(3, 4));
        for (int kind = 0;
                kind < uniquePaths.size();
                kind++) {
            double startTravel =
                    3 + ((seed + kind) % 5);
            double endTravel =
                    kind % 2 == 0
                            ? 8 - ((seed + kind) % 4)
                            : 3 + ((seed + kind) % 4);
            endTravel = Math.max(
                    endTravel, startTravel - 9.5);
            double jitter =
                    random.nextInt(3) * 0.25;
            TimeProfile arrival = TimeProfile.piecewise(
                    ROOT,
                    List.of(
                            new TimeProfile.Breakpoint(
                                    0,
                                    startTravel + jitter),
                            new TimeProfile.Breakpoint(
                                    10,
                                    10 + endTravel + jitter)),
                    "random-arrival-" + seed
                            + "-path-" + kind);
            int score = 4
                    + Math.floorMod(
                            seed * 3 + kind * 5, 11);
            Set<Integer> pivots =
                    kind == 0
                            ? Set.of()
                            : Set.of(
                                    uniquePaths.get(kind)
                                            .get(0));
            baseProfiles.add(candidate(
                    ROOT,
                    uniquePaths.get(kind),
                    arrival,
                    ScoreProfile.constant(
                            ROOT, score),
                    pivots));
        }
        List<CandidateProfile> result =
                new ArrayList<>();
        for (int index = 0;
                index < domains.size();
                index++) {
            Domain candidateDomain =
                    domains.get(index);
            CandidateProfile base =
                    baseProfiles.get(kinds.get(index));
            result.add(
                    candidateDomain.equals(ROOT)
                            ? base
                            : base.restrict(
                                    candidateDomain));
        }
        return List.copyOf(result);
    }

    private static List<Integer> shuffledOrder(
            int size,
            int seed,
            int permutation) {
        List<Integer> order =
                new ArrayList<>();
        for (int index = 0;
                index < size;
                index++) {
            order.add(index);
        }
        Collections.shuffle(
                order,
                new Random(
                        0x514c4544L
                                + seed * 31L
                                + permutation));
        return List.copyOf(order);
    }

    private static List<String> differences(
            CandidateSet expected,
            CandidateSet actual,
            String expectedCompletion,
            String actualCompletion) {
        List<String> differences =
                new ArrayList<>();
        if (!expected.temporalCells().equals(
                actual.temporalCells())) {
            differences.add("canonical_cells");
        }
        if (!retainedIdsByCell(expected).equals(
                retainedIdsByCell(actual))) {
            differences.add("retained_ids");
        }
        if (!semanticEnvelope(expected).equals(
                semanticEnvelope(actual))) {
            differences.add("normalized_envelope");
        }
        if (!expectedCompletion.equals(
                actualCompletion)) {
            differences.add("completion_status");
        }
        if (!semanticChecksum(expected).equals(
                semanticChecksum(actual))) {
            differences.add("semantic_checksum");
        }
        return differences;
    }

    private static CandidateProfile candidate(
            Domain domain,
            List<Integer> path,
            TimeProfile arrival,
            ScoreProfile score,
            Set<Integer> usedPivots) {
        return new CandidateProfile(
                domain,
                arrival,
                score,
                PathPointer.of(path),
                usedPivots.size(),
                usedPivots.isEmpty()
                        ? -1 : usedPivots.iterator().next(),
                false,
                usedPivots);
    }

    private static String semanticChecksum(CandidateSet candidates) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    semanticEnvelope(candidates).getBytes(
                            StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static TimeProfile linear(
            Domain domain,
            double startArrival,
            double endArrival,
            String fingerprint) {
        Domain.Interval interval =
                domain.intervals().get(0);
        return TimeProfile.piecewise(
                domain,
                List.of(
                        new TimeProfile.Breakpoint(
                                interval.start(),
                                startArrival),
                        new TimeProfile.Breakpoint(
                                interval.end(),
                                endArrival)),
                fingerprint);
    }

    private static ScoreProfile score(
            int beforeFive,
            int afterFive,
            String fingerprint) {
        return ScoreProfile.piecewise(
                ROOT,
                List.of(
                        new ScoreProfile.Interval(
                                0, 5, beforeFive),
                        new ScoreProfile.Interval(
                                5, 10, afterFive)),
                fingerprint);
    }

    private static List<String> retainedIdsByCell(
            CandidateSet frontier) {
        List<String> result = new ArrayList<>();
        for (Domain.Interval cell :
                frontier.temporalCells()) {
            double sample = cell.start() == cell.end()
                    ? cell.start()
                    : ProfileCellPartition.midpoint(cell);
            List<List<Integer>> ids =
                    frontier.candidates().stream()
                            .filter(candidate ->
                                    candidate.domain()
                                            .contains(sample))
                            .map(CandidateProfile::stablePathId)
                            .distinct()
                            .sorted(PathPointer.STABLE_PATH_ORDER)
                            .toList();
            result.add(cell + "=" + ids);
        }
        return result;
    }

    private static String semanticEnvelope(
            CandidateSet frontier) {
        EnvelopeProfile envelope =
                EnvelopeExtractor.extract(frontier, ROOT);
        StringBuilder result = new StringBuilder();
        for (EnvelopeSegment segment :
                envelope.segments()) {
            result.append(segment.interval())
                    .append('=')
                    .append(segment.found()
                            ? segment.candidate()
                                    .stablePathId()
                            : "NO_PATH")
                    .append(';');
        }
        return result.toString();
    }
}
