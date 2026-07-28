package edu.ipcmax.experiments;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaceCliSmallQueryTest {
    @Test
    void demoSmallQueryDefaultsToPaceBAndPrintsStableEnvelope() throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exitCode = PaceCli.execute(new String[]{
                "--demo",
                "--source", "1",
                "--destination", "4",
                "--departure-start", "420",
                "--departure-end", "430",
                "--budget", "60",
                "--theta", "2",
                "--anchor-limit", "8",
                "--candidate-limit", "8"
        }, printStream(stdout), printStream(stderr));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
        assertTrue(output.contains("algorithm=pace-b"));
        assertTrue(output.contains("status=SUCCESS"));
        assertTrue(output.contains("execution_policy=PACE_B"));
        assertTrue(output.contains("exactness_scope=RETAINED_FRONTIER"));
        assertTrue(output.contains("segments=1"));
        assertTrue(output.contains("segment_0=[420.0,430.0] -> [0, 1]"));
    }

    @Test
    void horizonExceededReturnsNonZeroStatus() throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exitCode = PaceCli.execute(new String[]{
                "--demo",
                "--departure-start", "1400",
                "--departure-end", "1420",
                "--budget", "60",
                "--theta", "2",
                "--anchor-limit", "8",
                "--candidate-limit", "8"
        }, printStream(stdout), printStream(stderr));

        String error = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(2, exitCode);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertTrue(error.contains("algorithm=pace-b"));
        assertTrue(error.contains("status=FUNCTION_HORIZON_EXCEEDED"));
        assertTrue(error.contains("execution_policy=PACE_B"));
        assertTrue(error.contains("exactness_scope=NOT_CERTIFIED"));
    }

    @Test
    void paceXReportsGlobalExactnessAfterExhaustiveConditionsAreVerified() throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exitCode = PaceCli.execute(new String[]{
                "--demo", "--algorithm", "pace-x", "--theta", "2"
        }, printStream(stdout), printStream(stderr));

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
        assertTrue(output.contains("execution_policy=PACE_X"));
        assertTrue(output.contains("exactness_scope=GLOBAL_CERTIFIED"));
    }

    private static PrintStream printStream(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }
}
