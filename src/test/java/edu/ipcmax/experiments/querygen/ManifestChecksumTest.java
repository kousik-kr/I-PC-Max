package edu.ipcmax.experiments.querygen;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestChecksumTest {
    @TempDir
    Path temporary;

    @Test
    void checksumIsStableAcrossDirectoryAndCreationOrder() throws Exception {
        Map<String, String> contents = fixtureContents();
        Path first = temporary.resolve("first");
        Path second = temporary.resolve("second");
        writeFixture(first, ManifestChecksum.REQUIRED_GRAPH_FILES, contents);
        List<String> reverseOrder = new ArrayList<>(ManifestChecksum.REQUIRED_GRAPH_FILES);
        Collections.reverse(reverseOrder);
        writeFixture(second, reverseOrder, contents);
        Files.writeString(second.resolve("ignored.txt"), "not part of the graph contract");

        String firstChecksum = ManifestChecksum.graphChecksum(first);
        assertEquals(firstChecksum, ManifestChecksum.compute(first));
        assertEquals(firstChecksum, ManifestChecksum.graphChecksum(second));
        assertTrue(firstChecksum.matches("[0-9a-f]{64}"));
    }

    @Test
    void checksumChangesWhenARequiredFileChanges() throws Exception {
        Path dataset = temporary.resolve("dataset");
        writeFixture(dataset, ManifestChecksum.REQUIRED_GRAPH_FILES, fixtureContents());
        String before = ManifestChecksum.graphChecksum(dataset);

        Files.writeString(dataset.resolve("nodes.csv.gz"), "nodes changed\n");

        assertNotEquals(before, ManifestChecksum.graphChecksum(dataset));
    }

    @Test
    void checksumRejectsAMissingRequiredFile() throws Exception {
        Path dataset = temporary.resolve("incomplete");
        Map<String, String> contents = fixtureContents();
        writeFixture(dataset,
                ManifestChecksum.REQUIRED_GRAPH_FILES.subList(0, ManifestChecksum.REQUIRED_GRAPH_FILES.size() - 1),
                contents);

        IOException failure = assertThrows(IOException.class,
                () -> ManifestChecksum.graphChecksum(dataset));
        assertTrue(failure.getMessage().contains("travel_time_functions.jsonl.gz"));
    }

    @Test
    void datasetSeedIsStableAndDomainSeparated() throws Exception {
        Path dataset = temporary.resolve("seed-dataset");
        writeFixture(dataset, ManifestChecksum.REQUIRED_GRAPH_FILES, fixtureContents());
        String checksum = ManifestChecksum.graphChecksum(dataset);

        long nySeed = ManifestChecksum.deriveDatasetSeed(20260711L, "NY", checksum);
        assertEquals(nySeed, ManifestChecksum.deriveDatasetSeed(20260711L, "ny", checksum.toUpperCase()));
        assertNotEquals(nySeed, ManifestChecksum.deriveDatasetSeed(20260711L, "FLA", checksum));
        assertNotEquals(nySeed, ManifestChecksum.deriveDatasetSeed(20260712L, "NY", checksum));
        assertThrows(IllegalArgumentException.class,
                () -> ManifestChecksum.deriveDatasetSeed(1L, "NY", "not-a-checksum"));
    }

    @Test
    void checksumAndSeedAlgorithmHaveAGoldenVector() throws Exception {
        Path dataset = temporary.resolve("golden-vector");
        writeFixture(dataset, ManifestChecksum.REQUIRED_GRAPH_FILES, fixtureContents());
        String checksum = ManifestChecksum.graphChecksum(dataset);

        assertAll(
                () -> assertEquals("7eebf783577671d58933ba8dea12031a0962a976d5ccabbb0e3d674312e17dea",
                        checksum),
                () -> assertEquals(1276032319591432707L,
                        ManifestChecksum.deriveDatasetSeed(20260711L, "NY", checksum)));
    }

    private static Map<String, String> fixtureContents() {
        Map<String, String> contents = new LinkedHashMap<>();
        contents.put("edges_static.csv.gz", "edges\n");
        contents.put("nodes.csv.gz", "nodes\n");
        contents.put("manifest.json", "{\"seed\":42}\n");
        contents.put("score_functions.jsonl.gz", "scores\n");
        contents.put("travel_time_functions.jsonl.gz", "travel\n");
        return contents;
    }

    private static void writeFixture(Path directory, List<String> order, Map<String, String> contents)
            throws IOException {
        Files.createDirectories(directory);
        for (String filename : order) {
            Files.writeString(directory.resolve(filename), contents.get(filename));
        }
    }
}
