package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;

/**
 * Incremental normalization, deduplication, dominance, bounded retention, and
 * adjacent-fragment merging for one endpoint/layer state.
 */
public final class IncrementalFrontier {
    private final TDGraph graph;
    private final Domain domain;
    private final double budget;
    private final int source;
    private final int destination;
    private final PaceOptions options;
    private final PaceWorkLedger ledger;
    private CandidateSet retained = new CandidateSet();
    private long insertions;
    private long peakSize;

    public IncrementalFrontier(
            TDGraph graph,
            Domain domain,
            double budget,
            int source,
            int destination,
            PaceOptions options,
            PaceWorkLedger ledger) {
        this.graph = graph;
        this.domain = domain;
        this.budget = budget;
        this.source = source;
        this.destination = destination;
        this.options = options;
        this.ledger = ledger;
    }

    /**
     * Inserts one exact candidate and immediately reduces the affected
     * frontier. Returns true when at least one fragment of the stable path is
     * retained.
     */
    public boolean insert(
            CandidateProfile candidate,
            String workItem) {
        Domain accepted = candidate.domain().intersection(domain);
        if (accepted.isEmpty()) {
            return false;
        }
        CandidateProfile normalized = candidate.domain().equals(accepted)
                ? candidate : candidate.restrict(accepted);
        int breakpointCount =
                normalized.arrivalProfile().breakpoints().size()
                + normalized.scoreProfile().breakpoints().size();
        if (!ledger.acceptsBreakpoints(breakpointCount, workItem)) {
            return false;
        }
        if (retained.size() >= options.maxFrontierFragments()) {
            ledger.emergencyFrontierGuard(workItem);
            return false;
        }
        CandidateSet next = new CandidateSet();
        next.addAll(retained);
        next.add(normalized);
        retained = FrontierCompressor.compress(
                graph,
                next,
                domain,
                budget,
                options.effectiveFrontierLimit(),
                options.policy(),
                source,
                destination,
                options.features());
        insertions++;
        peakSize = Math.max(peakSize, retained.size());
        return retained.candidates().stream().anyMatch(
                value -> value.stablePathId().equals(
                        normalized.stablePathId()));
    }

    public CandidateSet candidates() {
        CandidateSet copy = new CandidateSet();
        copy.addAll(retained);
        return copy;
    }

    public long insertions() {
        return insertions;
    }

    public long peakSize() {
        return peakSize;
    }

    public int cellCount() {
        if (retained.isEmpty()) {
            return 0;
        }
        return ProfileCellPartition.cells(
                domain, retained.candidates(), true).size();
    }
}
