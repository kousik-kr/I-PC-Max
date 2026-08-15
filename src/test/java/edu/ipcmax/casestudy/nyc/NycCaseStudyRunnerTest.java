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
    void boundedProtocolRequiresFiveSecondsBeforeGraphLoading() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> NycShuttleCaseStudyBench.main(new String[] {
                        "--dataset", "missing", "--query-file", "missing",
                        "--output", "missing", "--timeout-seconds", "4"}));
        assertTrue(failure.getMessage().contains("exactly --timeout-seconds 5"));
    }

    @Test
    void terminalCsvParserHandlesQuotedCommasAndEscapedQuotes() {
        assertEquals(
                java.util.List.of("A", "terminal, east", "say \"yes\""),
                NycQueryManifestBuilder.parseCsv("A,\"terminal, east\",\"say \"\"yes\"\"\""));
    }
}
