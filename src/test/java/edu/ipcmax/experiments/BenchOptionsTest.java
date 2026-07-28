package edu.ipcmax.experiments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
