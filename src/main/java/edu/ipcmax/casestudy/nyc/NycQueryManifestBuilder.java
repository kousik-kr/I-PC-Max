package edu.ipcmax.casestudy.nyc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.ExactDijkstraLowerBoundOracle;
import edu.ipcmax.core.index.LowerBoundOracle;
import edu.ipcmax.core.labeling.PointForwardLabeling;
import edu.ipcmax.core.loader.GeneratedGraphDataset;
import edu.ipcmax.core.loader.GeneratedGraphLoader;

/** Builds fixed-budget NYC query rows from exact minute-grid fastest paths. */
public final class NycQueryManifestBuilder {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private NycQueryManifestBuilder() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        JsonNode config = JSON.readTree(options.config().toFile());
        GeneratedGraphDataset dataset = new GeneratedGraphLoader().load(options.dataset());
        TDGraph graph = dataset.graph();
        JsonNode graphManifest = JSON.readTree(options.dataset().resolve("manifest.json").toFile());
        double supportStart = graphManifest.path("temporal_support").path("start").asDouble(Double.NaN);
        double supportEnd = graphManifest.path("temporal_support").path("end").asDouble(Double.NaN);
        if (!Double.isFinite(supportStart) || !Double.isFinite(supportEnd)) {
            throw new IllegalArgumentException("processed graph manifest has no finite temporal support");
        }
        List<TerminalPair> pairs = readPairs(options.terminalPairs());
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException("terminal-pair CSV contains no selected pairs");
        }
        ExactDijkstraLowerBoundOracle oracle = new ExactDijkstraLowerBoundOracle(graph);
        String graphManifestChecksum = sha256(options.dataset().resolve("manifest.json"));
        String pairChecksum = sha256(options.terminalPairs());
        ForkJoinPool pool = new ForkJoinPool(options.threads());
        List<PairBuild> built;
        try {
            built = pool.submit(() -> IntStream.range(0, pairs.size()).parallel()
                    .mapToObj(index -> buildPair(
                            graph, oracle, pairs.get(index), index, config,
                            supportStart, supportEnd, graphManifestChecksum, pairChecksum))
                    .toList()).get();
        } finally {
            pool.shutdownNow();
        }
        List<Map<String, Object>> queries = built.stream()
                .flatMap(item -> item.queries().stream())
                .sorted(Comparator.comparing(item -> item.get("query_id").toString()))
                .toList();
        List<Map<String, Object>> exclusions = built.stream()
                .flatMap(item -> item.exclusions().stream())
                .sorted(Comparator.comparing(item -> item.get("query_id").toString()))
                .toList();
        if (queries.isEmpty()) {
            throw new IllegalStateException("all candidate NYC queries were excluded; see horizon/budget diagnostics");
        }
        writeJsonl(options.output(), queries);
        writeJsonl(options.exclusions(), exclusions);
        System.out.printf(
                "status=COMPLETE pairs=%d queries=%d exclusions=%d output=%s%n",
                pairs.size(), queries.size(), exclusions.size(), options.output());
    }

    private static PairBuild buildPair(
            TDGraph graph,
            ExactDijkstraLowerBoundOracle oracle,
            TerminalPair pair,
            int pairIndex,
            JsonNode config,
            double supportStart,
            double supportEnd,
            String graphManifestChecksum,
            String pairChecksum) {
        try {
            PointForwardLabeling labeler = new PointForwardLabeling(graph);
            LowerBoundOracle.Labels reverse = oracle.distancesTo(pair.destination());
            List<Map<String, Object>> queries = new ArrayList<>();
            List<Map<String, Object>> exclusions = new ArrayList<>();
            for (JsonNode period : config.path("periods")) {
                int intervalStart = period.path("interval_start").asInt();
                int intervalEnd = period.path("interval_end").asInt();
                FastestEvidence evidence = fastestEvidence(
                        graph, labeler, reverse, pair, intervalStart, intervalEnd);
                for (JsonNode rhoNode : config.path("rho")) {
                    double rho = rhoNode.asDouble();
                    double budget = Domain.canonicalTime((1.0 + rho) * evidence.maximum());
                    String exclusion = null;
                    if (!evidence.reachedAllDepartures()) {
                        exclusion = "FASTEST_PATH_NOT_AVAILABLE_FOR_FULL_INTERVAL";
                    } else if (intervalStart < supportStart || intervalEnd > supportEnd) {
                        exclusion = "QUERY_INTERVAL_OUTSIDE_TEMPORAL_SUPPORT";
                    } else if (intervalEnd + budget > supportEnd) {
                        exclusion = "BUDGET_COULD_ENTER_BEYOND_TEMPORAL_SUPPORT";
                    }
                    String queryId = String.format(
                            "NYC-Q%03d-%s-R%02d", pairIndex + 1,
                            period.path("id").asText(), Math.round(rho * 100));
                    if (exclusion != null) {
                        exclusions.add(Map.of(
                                "query_id", queryId,
                                "pair_id", pair.pairId(),
                                "period_id", period.path("id").asText(),
                                "rho", rho,
                                "reason", exclusion));
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("schema_version", "nyc-query-v1");
                    row.put("query_id", queryId);
                    row.put("pair_id", pair.pairId());
                    row.put("dataset_id", config.path("case_id").asText());
                    row.put("source", pair.source());
                    row.put("destination", pair.destination());
                    row.put("period_id", period.path("id").asText());
                    row.put("local_clock", period.path("local_clock").asText());
                    row.put("interval_start", intervalStart);
                    row.put("interval_end", intervalEnd);
                    row.put("window_length", intervalEnd - intervalStart);
                    row.put("budget", budget);
                    row.put("rho", rho);
                    row.put("fastest_travel_time_min", evidence.minimum());
                    row.put("fastest_travel_time_max", evidence.maximum());
                    row.put("fastest_profile_checksum", evidence.checksum());
                    row.put("fastest_departure_count", intervalEnd - intervalStart + 1);
                    row.put("budget_definition", "B=(1+rho)*max_I(T_fast(t))");
                    row.put("temporal_support_start", supportStart);
                    row.put("temporal_support_end", supportEnd);
                    row.put("graph_manifest_sha256", graphManifestChecksum);
                    row.put("terminal_pair_manifest_sha256", pairChecksum);
                    row.put("query_seed", config.path("seed").asLong());
                    queries.add(row);
                }
            }
            System.out.printf("prepared_pair=%s queries=%d exclusions=%d%n",
                    pair.pairId(), queries.size(), exclusions.size());
            return new PairBuild(List.copyOf(queries), List.copyOf(exclusions));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static FastestEvidence fastestEvidence(
            TDGraph graph,
            PointForwardLabeling labeler,
            LowerBoundOracle.Labels reverse,
            TerminalPair pair,
            int start,
            int end) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        boolean reachedAll = true;
        for (int departure = start; departure <= end; departure++) {
            PointForwardLabeling.Result result = labeler.runToTarget(
                    pair.source(), pair.destination(), departure,
                    Double.POSITIVE_INFINITY, reverse);
            if (!result.reached(pair.destination())) {
                reachedAll = false;
                continue;
            }
            double travel = Domain.canonicalTime(
                    result.arrivalAt(pair.destination()) - departure);
            minimum = Math.min(minimum, travel);
            maximum = Math.max(maximum, travel);
            edu.ipcmax.core.validate.Path path = result.pathTo(pair.destination());
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(departure).array());
            digest.update(ByteBuffer.allocate(Long.BYTES)
                    .putLong(Double.doubleToLongBits(travel)).array());
            for (int arcId : path.arcIds()) {
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(arcId).array());
            }
        }
        if (!Double.isFinite(minimum)) {
            minimum = Double.NaN;
            maximum = Double.NaN;
        }
        return new FastestEvidence(
                minimum, maximum, reachedAll,
                HexFormat.of().formatHex(digest.digest()));
    }

    private static List<TerminalPair> readPairs(Path path) throws IOException {
        List<TerminalPair> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<String> header = parseCsv(reader.readLine());
            Map<String, Integer> positions = new LinkedHashMap<>();
            for (int index = 0; index < header.size(); index++) {
                positions.put(header.get(index), index);
            }
            for (String field : List.of(
                    "pair_id", "selected", "source_vertex", "destination_vertex")) {
                if (!positions.containsKey(field)) {
                    throw new IOException(path + ": missing CSV field " + field);
                }
            }
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> row = parseCsv(line);
                if (!Boolean.parseBoolean(row.get(positions.get("selected")))) {
                    continue;
                }
                result.add(new TerminalPair(
                        row.get(positions.get("pair_id")),
                        Integer.parseInt(row.get(positions.get("source_vertex"))),
                        Integer.parseInt(row.get(positions.get("destination_vertex")))));
            }
        }
        return List.copyOf(result);
    }

    static List<String> parseCsv(String line) {
        if (line == null) {
            return List.of();
        }
        List<String> fields = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                fields.add(value.toString());
                value.setLength(0);
            } else {
                value.append(character);
            }
        }
        fields.add(value.toString());
        return fields;
    }

    private static void writeJsonl(Path output, List<Map<String, Object>> rows) throws IOException {
        Path absolute = output.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), ".nyc-query-", ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            for (Map<String, Object> row : rows) {
                writer.write(JSON.writeValueAsString(row));
                writer.newLine();
            }
        }
        Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
    }

    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record TerminalPair(String pairId, int source, int destination) {
    }

    private record FastestEvidence(
            double minimum,
            double maximum,
            boolean reachedAllDepartures,
            String checksum) {
    }

    private record PairBuild(
            List<Map<String, Object>> queries,
            List<Map<String, Object>> exclusions) {
    }

    private record Options(
            Path dataset,
            Path terminalPairs,
            Path config,
            Path output,
            Path exclusions,
            int threads) {
        static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index += 2) {
                if (index + 1 >= args.length || !args[index].startsWith("--")) {
                    throw usage();
                }
                values.put(args[index], args[index + 1]);
            }
            for (String required : List.of(
                    "--dataset", "--terminal-pairs", "--config", "--output", "--exclusions")) {
                if (!values.containsKey(required)) {
                    throw usage();
                }
            }
            return new Options(
                    Path.of(values.get("--dataset")),
                    Path.of(values.get("--terminal-pairs")),
                    Path.of(values.get("--config")),
                    Path.of(values.get("--output")),
                    Path.of(values.get("--exclusions")),
                    Integer.parseInt(values.getOrDefault("--threads", "8")));
        }

        private static IllegalArgumentException usage() {
            return new IllegalArgumentException(
                    "Usage: NycQueryManifestBuilder --dataset DIR --terminal-pairs CSV "
                            + "--config JSON_YAML --output JSONL --exclusions JSONL [--threads N]");
        }
    }
}
