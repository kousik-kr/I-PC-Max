package edu.ipcmax.experiments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BenchOptionsTest {
    @Test
    void rejectsInvalidCombinationsBeforeLoadingData() {
        assertThrows(IllegalArgumentException.class, () -> parse("--algorithm", "rpq"));
        assertThrows(IllegalArgumentException.class, () -> parse("--algorithm", "ksp-profile"));
        assertThrows(IllegalArgumentException.class, () -> parse(
                "--algorithm", "exh-profile", "--ablation", "no-memo"));
        assertThrows(IllegalArgumentException.class, () -> parse(
                "--algorithm", "pace-b", "--anchor-limit", "-1"));
    }

    @Test
    void appliesSemanticAblationOverrides() {
        assertEquals(0, parse("--algorithm", "pace-b", "--ablation", "no-anchor").theta);
        assertEquals(1, parse("--algorithm", "pace-b", "--ablation", "serial", "--threads", "8").threads);
        assertEquals(
                1800,
                parse("--algorithm", "pace-b",
                        "--preprocessing-timeout-seconds", "1800")
                        .preprocessingTimeoutSeconds);
        assertTrue(parse(
                "--algorithm", "pace-b",
                "--shared-preprocessing").sharedPreprocessing);
    }

    @Test
    void fiveSecondAlgorithmsAndSparseResumeTrialsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> parse(
                "--algorithm", "iscope", "--timeout-seconds", "4"));
        BenchOptions options = parse(
                "--algorithm", "allfp",
                "--timeout-seconds", "10",
                "--repetitions", "3",
                "--repetition-indices", "0,2");
        assertEquals(java.util.List.of(0, 2), options.repetitionIndices);
        assertEquals(
                java.util.concurrent.TimeUnit.SECONDS.toNanos(10),
                options.algorithmConfig().queryTimeLimitNanos());
        assertThrows(IllegalArgumentException.class, () -> parse(
                "--algorithm", "iscope", "--timeout-seconds", "5",
                "--repetitions", "3", "--repetition-indices", "2,2"));
        assertEquals("iscope", AlgorithmRegistry.create("iscope").id());
        assertEquals("allfp", AlgorithmRegistry.create("allfp").id());
        assertEquals("interval-best",
                AlgorithmRegistry.create("interval-best").id());
        assertEquals("rpq", AlgorithmRegistry.create("rpq").id());
    }

    private static BenchOptions parse(String... prefix) {
        String[] suffix = {"--dataset", "demo", "--query-file", "experiments/manifests/tiny.jsonl",
                "--output-jsonl", "target/test.jsonl"};
        String[] arguments = new String[prefix.length + suffix.length];
        System.arraycopy(prefix, 0, arguments, 0, prefix.length);
        System.arraycopy(suffix, 0, arguments, prefix.length, suffix.length);
        return BenchOptions.parse(arguments);
    }
}
