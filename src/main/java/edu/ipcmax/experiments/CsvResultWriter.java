package edu.ipcmax.experiments;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes experiment metrics CSV files.
 */
public final class CsvResultWriter {
    /**
     * Required metrics header.
     */
    public static final String HEADER = "query_id,dataset,algorithm,mode,theta,K,pivot_step,interval_width,overhead,"
            + "score,travel_time,arrival_time,departure_time,runtime_ms,peak_memory_mb,feasible_edge_profiles,"
            + "pivot_profiles,candidate_profiles,labeling_calls_f,labeling_calls_b,cache_hits,cache_misses,"
            + "breakpoints_total,compression_delta_max,validated,valid";

    /**
     * Writes rows to a CSV file.
     */
    public void write(Path path, List<MetricsRow> rows) throws IOException {
        StringBuilder out = new StringBuilder(HEADER).append(System.lineSeparator());
        for (MetricsRow row : rows) {
            out.append(row.queryId()).append(',')
                    .append(row.dataset()).append(',')
                    .append(row.algorithm()).append(',')
                    .append(row.mode()).append(',')
                    .append(row.theta()).append(',')
                    .append(row.k()).append(',')
                    .append(row.pivotStep()).append(',')
                    .append(row.intervalWidth()).append(',')
                    .append(row.overhead()).append(',')
                    .append(row.score()).append(',')
                    .append(row.travelTime()).append(',')
                    .append(row.arrivalTime()).append(',')
                    .append(row.departureTime()).append(',')
                    .append(row.runtimeMs()).append(',')
                    .append(row.peakMemoryMb()).append(',')
                    .append(row.feasibleEdgeProfiles()).append(',')
                    .append(row.pivotProfiles()).append(',')
                    .append(row.candidateProfiles()).append(',')
                    .append(row.labelingCallsF()).append(',')
                    .append(row.labelingCallsB()).append(',')
                    .append(row.cacheHits()).append(',')
                    .append(row.cacheMisses()).append(',')
                    .append(row.breakpointsTotal()).append(',')
                    .append(row.compressionDeltaMax()).append(',')
                    .append(row.validated()).append(',')
                    .append(row.valid())
                    .append(System.lineSeparator());
        }
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
    }
}
