package edu.ipcmax.core.pcmax;

import java.util.Objects;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.validate.ExactPathValidator;
import edu.ipcmax.core.validate.GraphValidator;

/**
 * Public Profile-Aware Candidate Envelope algorithm.
 *
 * <p>{@link PaceExecutionPolicy#PACE_X PACE-X} exhaustively validates tiny graphs.
 * {@link PaceExecutionPolicy#PACE_B PACE-B} applies the configured deterministic
 * anchor, connector, and frontier limits and is exact over its retained root
 * frontier.</p>
 */
public final class PACE {
    private final TDGraph graph;
    private final PaceOptions options;
    private final PaceFrontierGenerator generator;

    /** Creates a PACE runner with an explicit execution policy. */
    public PACE(TDGraph graph, PaceOptions options) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.options = Objects.requireNonNull(options, "options");
        GraphValidator.validate(graph, true);
        this.generator = new PaceFrontierGenerator(graph, options);
    }

    /**
     * Executes the query and returns its maximal departure-time-to-path profile.
     *
     * @throws PaceException with status {@link PaceStatus#FUNCTION_HORIZON_EXCEEDED}
     *         when the graph functions do not cover {@code [t_s,t_e+B]}
     */
    public synchronized EnvelopeProfile run(QuerySpec query) {
        Objects.requireNonNull(query, "query");
        CandidateSet rootFrontier = generator.generateFrontier(query);
        return EnvelopeExtractor.extract(rootFrontier, query.departureDomain());
    }

    /** Explicitly named alias for callers migrating from {@link IPCMax}. */
    public EnvelopeProfile runProfile(QuerySpec query) {
        return run(query);
    }

    /**
     * Selects one best discrete point from the generated profile for legacy callers.
     * The primary PACE result remains the full profile returned by {@link #run(QuerySpec)}.
     */
    public IPCMaxResult bestPointResult(QuerySpec query) {
        EnvelopeProfile profile = run(query);
        return profile.bestResult(
                new ExactPathValidator(graph),
                query.source(),
                query.destination(),
                query.maxTravelTime(),
                query::isOnGrid);
    }

    /** Effective execution configuration. */
    public PaceOptions options() {
        return options;
    }

    /** Statistics from the latest completed frontier generation. */
    public PaceGenerationStats stats() {
        return generator.stats();
    }
}
