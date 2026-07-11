package edu.ipcmax.experiments.framework;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mutable per-query counters and phase timings owned by one worker. */
public final class ExperimentInstrumentation {
    private final Map<String, Long> counters = new LinkedHashMap<>();
    private final Map<String, Long> timings = new LinkedHashMap<>();

    public void addCounter(String name, long amount) {
        counters.merge(name, amount, Long::sum);
    }

    public void increment(String name) {
        addCounter(name, 1);
    }

    public void setTiming(String name, long nanos) {
        timings.put(name, nanos);
    }

    public void addTiming(String name, long nanos) {
        timings.merge(name, nanos, Long::sum);
    }

    public Map<String, Long> counters() {
        return Map.copyOf(counters);
    }

    public Map<String, Long> timings() {
        return Map.copyOf(timings);
    }
}
