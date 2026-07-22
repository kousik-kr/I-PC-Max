package edu.ipcmax.experiments.querygen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;

import org.junit.jupiter.api.Test;

class QueryGenerationEnumsTest {
    @Test
    void parsesRegimesAndDistanceBinsFromStableAndFriendlyNames() {
        assertEquals(TemporalRegime.MORNING_PEAK, TemporalRegime.parse("MORNING_PEAK"));
        assertEquals(TemporalRegime.DAY_OFFPEAK, TemporalRegime.parse("day-offpeak"));
        assertEquals(TemporalRegime.LATE_OFFPEAK, TemporalRegime.parse("late offpeak"));
        assertEquals(DistanceBin.Q1, DistanceBin.parse("q1"));
        assertEquals(DistanceBin.Q4, DistanceBin.parse("4"));

        assertThrows(IllegalArgumentException.class, () -> TemporalRegime.parse(null));
        assertThrows(IllegalArgumentException.class, () -> TemporalRegime.parse("weekend"));
        assertThrows(IllegalArgumentException.class, () -> DistanceBin.parse(null));
        assertThrows(IllegalArgumentException.class, () -> DistanceBin.parse("Q5"));
    }

    @Test
    void formatsDeterministicQueryIdsIndependentlyOfDefaultLocale() {
        QueryPairCandidate pair = new QueryPairCandidate("ny", 17, 91, 12.3456789014, 8, 3);
        QueryFamily family = new QueryFamily(
                "Main Workload", pair, DistanceBin.Q2, TemporalRegime.EVENING_PEAK);
        String expectedFamily = "main-workload-q2-evening-peak-ny-s17-d91";
        String expectedQuery = expectedFamily + "-v007";

        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("ny-s17-d91", pair.pairFamilyId());
            assertEquals(expectedFamily, family.queryFamilyId());
            assertEquals(expectedQuery, family.queryId(7));
            assertEquals(expectedQuery, family.queryId(7));
        } finally {
            Locale.setDefault(original);
        }
        assertThrows(IllegalArgumentException.class, () -> family.queryId(-1));
    }
}
