package edu.ipcmax.experiments.framework;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/** Strict JSONL manifest reader and validator. */
public final class QueryManifestIO {
    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private QueryManifestIO() {
    }

    public static List<QueryManifestEntry> read(Path path) throws IOException {
        List<QueryManifestEntry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                QueryManifestEntry entry;
                try {
                    entry = JSON.readValue(line, QueryManifestEntry.class);
                    entry.validate();
                } catch (RuntimeException ex) {
                    throw new IOException(path + ":" + lineNumber + ": " + ex.getMessage(), ex);
                }
                if (!ids.add(entry.queryId())) {
                    throw new IOException(path + ":" + lineNumber + ": duplicate query_id " + entry.queryId());
                }
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) {
            throw new IOException("query manifest is empty: " + path);
        }
        return List.copyOf(entries);
    }

    public static ObjectMapper mapper() {
        return JSON;
    }
}
