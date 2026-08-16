package edu.ipcmax.casestudy.nyc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NycCaseStudyRunnerTest {
    @Test
    void obsoleteConnectorOptionsAreRejectedExplicitly() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> NycShuttleCaseStudyBench.main(
                        new String[] {"--connector-limit-kc", "1"}));
        assertTrue(failure.getMessage().contains("obsolete and forbidden"));
    }

    @Test
    void unknownOptionsAreNotSilentlyAccepted() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> NycShuttleCaseStudyBench.main(new String[] {"--mystery", "1"}));
        assertTrue(failure.getMessage().contains("unsupported"));
    }

    @Test
    void boundedProtocolAllowsOnlyPredeclaredCapsBeforeGraphLoading() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> NycShuttleCaseStudyBench.main(new String[] {
                        "--dataset", "missing", "--query-file", "missing",
                        "--output", "missing", "--timeout-seconds", "4"}));
        assertTrue(failure.getMessage().contains("5, 10, or 30"));
    }

    @Test
    void visualizationOnlyProtocolRejectsOfficialCapsBeforeGraphLoading() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> NycShuttleCaseStudyBench.main(new String[] {
                        "--dataset", "missing", "--query-file", "missing",
                        "--output", "missing", "--query-id", "NYC-Q024-EVENING-R50",
                        "--timeout-seconds", "30", "--visualization-only"}));
        assertTrue(failure.getMessage().contains("60 or 120"));
    }

    @Test
    void visualizationOnlyProtocolRequiresOneQueryBeforeGraphLoading() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> NycShuttleCaseStudyBench.main(new String[] {
                        "--dataset", "missing", "--query-file", "missing",
                        "--output", "missing", "--timeout-seconds", "60",
                        "--visualization-only"}));
        assertTrue(failure.getMessage().contains("requires exactly one --query-id"));
    }

    @Test
    void terminalCsvParserHandlesQuotedCommasAndEscapedQuotes() {
        assertEquals(
                java.util.List.of("A", "terminal, east", "say \"yes\""),
                NycQueryManifestBuilder.parseCsv("A,\"terminal, east\",\"say \"\"yes\"\"\""));
    }
}
