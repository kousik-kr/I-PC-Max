package edu.ipcmax.baselines.exact.preference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.validate.Path;
import edu.ipcmax.experiments.framework.ProfileSupport;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Shared exact objective evaluation and serialization for preference algorithms. */
public final class ExactPreferenceObjective {
    public static final String OBJECTIVE = "PREFERENCE_MAX_PROFILE";
    public static final String CONTRACT = "EXACT_RATIONAL_PREFERENCE_PROFILE-v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ExactPreferenceObjective() { }

    /**
     * Replays every route emitted by a search and builds the authoritative exact envelope.
     * Route IDs are directed arc-ID sequences and are retained in the typed profile.
     */
    public static Evaluation evaluate(
            TDGraph graph,
            QuerySpec query,
            Collection<Path> emittedRoutes,
            long endToEndNanos,
            String completionStatus,
            String exactnessScope,
            String registryScope) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(emittedRoutes, "emittedRoutes");
        ExactTemporalDomain domain = ExactTemporalDomain.fromLegacy(query.departureDomain());
        ExactTime budget = ExactTime.fromCanonicalDouble(
                Domain.canonicalTime(query.maxTravelTime()));
        Map<Path, ExactPreferencePathProfile> profiles = new LinkedHashMap<>();
        emittedRoutes.stream().distinct()
                .sorted(Comparator.comparing(path -> path.arcIds().toString()))
                .forEach(path -> ExactPreferenceReplay.replayExact(
                        graph, query.source(), query.destination(), path, domain, budget)
                        .ifPresent(profile -> profiles.put(path, profile)));
        ExactPreferenceEnvelope envelope = ExactPreferenceEnvelope.overRetained(domain, profiles);
        String exactJson = ExactPreferenceProfileCodec.encode(envelope);
        Map<String, Object> result = summarize(envelope, domain, endToEndNanos,
                completionStatus, exactnessScope, registryScope, emittedRoutes, profiles, exactJson);
        return new Evaluation(envelope, Map.copyOf(profiles), exactJson, result);
    }

    public record Evaluation(
            ExactPreferenceEnvelope profile,
            Map<Path, ExactPreferencePathProfile> routeProfiles,
            String serializedProfile,
            Map<String, Object> objectiveResult) {
        public Evaluation {
            Objects.requireNonNull(profile, "profile");
            routeProfiles = Map.copyOf(routeProfiles);
            Objects.requireNonNull(serializedProfile, "serializedProfile");
            objectiveResult = Collections.unmodifiableMap(new LinkedHashMap<>(objectiveResult));
        }
    }

    private static Map<String, Object> summarize(
            ExactPreferenceEnvelope envelope,
            ExactTemporalDomain domain,
            long endToEndNanos,
            String completionStatus,
            String exactnessScope,
            String registryScope,
            Collection<Path> emittedRoutes,
            Map<Path, ExactPreferencePathProfile> profiles,
            String exactJson) {
        ExactTime totalMeasure = measure(domain);
        ExactTime feasibleMeasure = ExactTime.ZERO;
        ExactTime integratedPreference = ExactTime.ZERO;
        for (ExactPreferenceEnvelope.Cell cell : envelope.cells()) {
            if (!cell.found()) continue;
            ExactTime length = cell.interval().end().subtract(cell.interval().start());
            if (length.signum() <= 0) continue;
            feasibleMeasure = feasibleMeasure.add(length);
            integratedPreference = integratedPreference.add(
                    length.multiply(ExactTime.of(cell.score(), BigInteger.ONE)));
        }
        ExactTime coverage = totalMeasure.signum() == 0
                ? (allSingletonsSelected(domain, envelope) ? ExactTime.ONE : ExactTime.ZERO)
                : feasibleMeasure.divide(totalMeasure);
        ExactTime average = feasibleMeasure.signum() == 0
                ? ExactTime.ZERO : integratedPreference.divide(feasibleMeasure);

        long switches = 0;
        List<Integer> previous = null;
        boolean hadPrevious = false;
        for (ExactPreferenceEnvelope.Cell cell : envelope.cells()) {
            List<Integer> current = cell.found() ? cell.path().arcIds() : null;
            if (hadPrevious && !Objects.equals(previous, current)) switches++;
            previous = current;
            hadPrevious = true;
        }
        List<List<Integer>> selectedRoutes = envelope.cells().stream()
                .filter(ExactPreferenceEnvelope.Cell::found)
                .map(cell -> cell.path().arcIds()).distinct().toList();
        List<List<Integer>> registry = emittedRoutes.stream().distinct()
                .sorted(Comparator.comparing(path -> path.arcIds().toString()))
                .map(Path::arcIds).toList();
        List<List<Integer>> retainedRegistry = profiles.values().stream()
                .filter(profile -> !profile.feasibleDomain().isEmpty())
                .map(profile -> profile.path().arcIds()).distinct().toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objective", OBJECTIVE);
        result.put("numerical_contract", CONTRACT);
        result.put("completion_status", completionStatus);
        result.put("exactness_scope", exactnessScope);
        result.put("route_registry_scope", registryScope);
        result.put("exact_profile_schema", ExactPreferenceProfileCodec.SCHEMA);
        result.put("exact_profile_checksum", ProfileSupport.sha256(exactJson));
        result.put("profile", parseProfile(exactJson));
        result.put("generated_route_registry", registry);
        result.put("emitted_route_registry", registry);
        result.put("retained_route_registry", retainedRegistry);
        result.put("selected_route_registry", selectedRoutes);
        result.put("selected_route_identity", selectedRoutes.size() == 1 ? selectedRoutes.getFirst() : null);
        result.put("feasible_departure_coverage_exact", coverage.toString());
        result.put("feasible_departure_measure_exact", feasibleMeasure.toString());
        result.put("integrated_preference_exact", integratedPreference.toString());
        result.put("average_selected_preference_exact", feasibleMeasure.signum() == 0 ? null : average.toString());
        result.put("feasible_coverage_fraction", decimal(coverage));
        result.put("integrated_preference", decimal(integratedPreference));
        result.put("average_selected_preference", feasibleMeasure.signum() == 0 ? null : decimal(average));
        result.put("average_selected_score", feasibleMeasure.signum() == 0 ? null : decimal(average));
        result.put("final_profile_intervals", envelope.cells().size());
        result.put("elementary_envelope_cells", envelope.cells().size());
        result.put("distinct_selected_paths", selectedRoutes.size());
        result.put("path_changes", switches);
        result.put("path_switch_count", switches);
        result.put("profile_cell_count", envelope.cells().size());
        result.put("end_to_end_query_nanos", Math.max(0L, endToEndNanos));
        result.put("feasible", envelope.hasFeasibleCell());
        result.put("profile_checksum", ProfileSupport.sha256(exactJson));
        return result;
    }

    private static boolean allSingletonsSelected(
            ExactTemporalDomain domain, ExactPreferenceEnvelope envelope) {
        if (domain.isEmpty()) return false;
        return domain.intervals().stream().allMatch(interval ->
                interval.start().equals(interval.end())
                        && envelope.cellAt(interval.start()).map(ExactPreferenceEnvelope.Cell::found)
                                .orElse(false));
    }

    private static ExactTime measure(ExactTemporalDomain domain) {
        ExactTime result = ExactTime.ZERO;
        for (ExactTemporalDomain.Interval interval : domain.intervals()) {
            result = result.add(interval.end().subtract(interval.start()));
        }
        return result;
    }

    private static Double decimal(ExactTime value) {
        return new BigDecimal(value.numerator()).divide(
                new BigDecimal(value.denominator()), MathContext.DECIMAL128).doubleValue();
    }

    private static JsonNode parseProfile(String exactJson) {
        try {
            return JSON.readTree(exactJson.getBytes(StandardCharsets.UTF_8));
        } catch (IOException malformed) {
            throw new IllegalStateException("exact preference codec emitted invalid JSON", malformed);
        }
    }
}
