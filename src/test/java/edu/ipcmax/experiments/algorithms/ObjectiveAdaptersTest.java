package edu.ipcmax.experiments.algorithms;

import edu.ipcmax.core.pcmax.PaceEngineMode;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.experiments.AlgorithmRegistry;
import edu.ipcmax.experiments.ExperimentDatasets;
import edu.ipcmax.experiments.framework.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ObjectiveAdaptersTest {
    @TempDir Path temporary;

    @Test void thopPreparesOnceLazyReloadsAndIgnoresPreferenceBudget() throws Exception {
        var graph = ExperimentDatasets.demo();
        var config = thopConfig("build");
        var adapter = new ThopPartitionedExperimentAlgorithm();
        adapter.prepare(graph, config);
        var firstInstrumentation = new ExperimentInstrumentation();
        var first = adapter.run(graph, new QuerySpec(1, 4, 420, 430, 1, 1), config, firstInstrumentation);
        assertNull(first.profile());
        assertEquals(ExactnessScope.GLOBAL_CERTIFIED, first.exactnessScope());
        assertEquals(ExperimentStatus.COMPLETED, first.status());
        assertFalse((Boolean) first.scalars().get("pace_budget_applied"));
        assertEquals("search_first_then_prune_materialize", first.scalars().get("construction_schedule"));
        assertTrue(firstInstrumentation.counters().get("disk_bytes_read") > 0);
        var output = ProfileSupport.output(first);
        var cells = (List<?>) output.get("fastest_travel_time_profile");
        assertFalse(cells.isEmpty());
        // Singleton cells may use a different affine representative; evaluate the function.
        var firstCell = (Map<?, ?>) cells.get(0);
        var slope = rational((String) firstCell.get("slope"));
        var intercept = rational((String) firstCell.get("intercept"));
        assertEquals(edu.ipcmax.baselines.exact.ExactTime.of(20),
                slope.multiply(edu.ipcmax.baselines.exact.ExactTime.of(420)).add(intercept));
        assertNull(output.get("average_selected_score"));
        String checksum = ProfileSupport.resultChecksum(first);
        assertNotNull(checksum);

        // prepare is idempotent and must not rebuild existing files even in build mode.
        adapter.prepare(graph, config);
        var warmInstrumentation = new ExperimentInstrumentation();
        var warm = adapter.run(graph, new QuerySpec(1, 4, 420, 430, 999, 1), config, warmInstrumentation);
        assertEquals(0L, warmInstrumentation.counters().get("disk_bytes_read"));
        assertEquals(checksum, ProfileSupport.resultChecksum(warm));
        assertEquals(2L, warm.scalars().get("query_ordinal"));
        adapter.close();
        var load = thopConfig("load");
        adapter.prepare(graph, load);
        var loaded = adapter.run(graph, new QuerySpec(1, 4, 420, 430, 1, 1), load,
                new ExperimentInstrumentation());
        assertEquals(checksum, ProfileSupport.resultChecksum(loaded));
        assertEquals(0L, loaded.scalars().get("boundary_preprocessing_nanos"));
        assertEquals(0L, loaded.scalars().get("inner_preprocessing_nanos"));
        assertTrue(Files.exists(temporary.resolve("index/inner.pthp")));
    }

    @Test void thopUnreachableAndUnsupportedDomainsAreDistinct() {
        var graph = ExperimentDatasets.demo();
        var config = thopConfig("build");
        var adapter = new ThopPartitionedExperimentAlgorithm();
        adapter.prepare(graph, config);
        var unreachable = adapter.run(graph, new QuerySpec(4, 1, 420, 430, 1, 1), config,
                new ExperimentInstrumentation());
        assertEquals(ExperimentStatus.NO_FEASIBLE_PATH, unreachable.status());
        assertEquals(ExactnessScope.GLOBAL_CERTIFIED, unreachable.exactnessScope());
        assertThrows(IllegalArgumentException.class, () -> adapter.run(graph,
                new QuerySpec(1, 4, 1440, 1441, 1, 1), config, new ExperimentInstrumentation()));
    }

    @Test void thopRejectsGraphPartitionMismatchObsoleteVersionAndOverwrite() throws Exception {
        var graph = ExperimentDatasets.demo();
        var build = thopConfig("build");
        new ThopPartitionedExperimentAlgorithm().prepare(graph, build);
        assertThrows(IllegalArgumentException.class,
                () -> new ThopPartitionedExperimentAlgorithm().prepare(graph, build));
        var wrongGraph = ExperimentDatasets.sicspFigureOne();
        assertThrows(IllegalArgumentException.class,
                () -> new ThopPartitionedExperimentAlgorithm().prepare(wrongGraph, thopConfig("load")));
        Path changed = temporary.resolve("changed-partitions.json");
        Files.writeString(changed, "{\"schema_version\":1,\"assignments\":{\"1\":0,\"2\":0,\"3\":0,\"4\":0}}");
        var mismatch = new LinkedHashMap<>(thopConfig("load").objectiveOptions());
        mismatch.put("thop_partitions", changed.toString());
        assertThrows(IllegalArgumentException.class,
                () -> new ThopPartitionedExperimentAlgorithm().prepare(graph, config("thop-partitioned", mismatch)));
        Path inner = temporary.resolve("index/inner.pthp");
        byte[] bytes = Files.readAllBytes(inner);
        bytes[4] = 0; bytes[5] = 0; bytes[6] = 0; bytes[7] = 1;
        byte[] payload = Arrays.copyOf(bytes, bytes.length - 32);
        System.arraycopy(MessageDigest.getInstance("SHA-256").digest(payload), 0, bytes, payload.length, 32);
        Files.write(inner, bytes);
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new ThopPartitionedExperimentAlgorithm().prepare(graph, thopConfig("load")));
        assertTrue(failure.getMessage().contains("unsupported Partitioned-THop index format"));
    }

    @Test void nativeFigureOneUsesExplicitWeightsAndOriginalDelta() {
        var graph = ExperimentDatasets.sicspFigureOne();
        var config = config("sicsp-native", Map.of(
                "sicsp_weights", "experiments/fixtures/sicsp_native_figure1/weights.json",
                "sicsp_weight_bound", "80"));
        var adapter = new SicspNativeExperimentAlgorithm();
        adapter.prepare(graph, config);
        var instrumentation = new ExperimentInstrumentation();
        var result = adapter.run(graph, new QuerySpec(1, 3, 0, 30, 1, 1), config, instrumentation);
        var output = ProfileSupport.output(result);
        assertEquals(ExperimentStatus.COMPLETED, result.status());
        assertEquals(ExactnessScope.NOT_CERTIFIED, result.exactnessScope());
        assertNull(result.profile());
        assertEquals(50.0, output.get("earliest_candidate_arrival"));
        assertEquals(0.0, output.get("selected_departure"));
        assertEquals(25.0, output.get("accumulated_native_weight"));
        assertEquals(List.of(0, 1), output.get("selected_route_arc_ids"));
        assertTrue((Boolean) output.get("weight_bound_feasible"));
        assertFalse((Boolean) result.scalars().get("pace_scores_used_as_weights"));
        assertTrue(instrumentation.counters().get("reverse_search_calls") > 0);
        assertTrue(instrumentation.timings().get("native_query_execution") > 0);
        String checksum = ProfileSupport.resultChecksum(result);
        assertEquals(checksum, ProfileSupport.resultChecksum(adapter.run(graph,
                new QuerySpec(1, 3, 0, 30, 999, 1), config, new ExperimentInstrumentation())));
        var unreachable = adapter.run(graph, new QuerySpec(3, 1, 0, 30, 1, 1), config,
                new ExperimentInstrumentation());
        assertEquals(ExperimentStatus.UNRESOLVED_NO_GENERATED_CANDIDATE, unreachable.status());
        assertEquals(ExactnessScope.NOT_CERTIFIED, unreachable.exactnessScope());
    }

    @Test void nativeRejectsMissingWeightInputAndPartialWeightSupport() throws Exception {
        var graph = ExperimentDatasets.sicspFigureOne();
        assertThrows(IllegalArgumentException.class, () -> new SicspNativeExperimentAlgorithm()
                .prepare(graph, config("sicsp-native", Map.of("sicsp_weight_bound", "80"))));
        Path weights = temporary.resolve("partial.json");
        Files.writeString(weights, "{\"schema_version\":1,\"semantics\":\"native_sicsp_edge_weight\",\"arcs\":{}}");
        assertThrows(IllegalArgumentException.class, () -> new SicspNativeExperimentAlgorithm()
                .prepare(graph, config("sicsp-native", Map.of("sicsp_weights", weights.toString(),
                        "sicsp_weight_bound", "80"))));
    }

    @Test void allRequiredRegistryMethodsKeepDifferentObjectivesAndScopes() {
        for (String id : List.of("pace-x", "pace-b", "iscope", "sicsp-pref", "thop-partitioned", "sicsp-native")) {
            assertEquals(id, AlgorithmRegistry.create(id).id());
        }
    }

    private AlgorithmConfig thopConfig(String lifecycle) {
        return config("thop-partitioned", Map.of("thop_index_dir", temporary.resolve("index").toString(),
                "thop_partitions", "experiments/fixtures/thop_demo/partitions.json",
                "thop_domain_start", "0", "thop_domain_end", "1440", "thop_lifecycle", lifecycle));
    }

    private static AlgorithmConfig config(String algorithm, Map<String, String> options) {
        return new AlgorithmConfig(algorithm, Ablation.NONE, PaceEngineMode.SCALABLE,
                4, 32, 16, 16, 100_000, 100_000, 1_000_000,
                1, 1, 10, 100_000, 100_000, 100_000, 100_000, true, 42L, 0L, options);
    }

    private static edu.ipcmax.baselines.exact.ExactTime rational(String text) {
        String[] parts = text.split("/");
        return parts.length == 1 ? edu.ipcmax.baselines.exact.ExactTime.parseDecimal(text)
                : edu.ipcmax.baselines.exact.ExactTime.of(new java.math.BigInteger(parts[0]),
                        new java.math.BigInteger(parts[1]));
    }
}
