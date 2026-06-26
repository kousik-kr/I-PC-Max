package edu.ipcmax.experiments;

import edu.ipcmax.core.pcmax.IPCMaxResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes simple debug JSON files for manual inspection.
 */
public final class DebugExporter {
    /**
     * Exports a final result as JSON.
     */
    public void exportResult(Path path, IPCMaxResult result) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        String json = "{\n"
                + "  \"found\": " + result.found() + ",\n"
                + "  \"departure_time\": " + result.departureTime() + ",\n"
                + "  \"arrival_time\": " + result.arrivalTime() + ",\n"
                + "  \"travel_time\": " + result.travelTime() + ",\n"
                + "  \"score\": " + result.score() + ",\n"
                + "  \"path_arc_ids\": \"" + result.path().arcIds() + "\"\n"
                + "}\n";
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }
}
