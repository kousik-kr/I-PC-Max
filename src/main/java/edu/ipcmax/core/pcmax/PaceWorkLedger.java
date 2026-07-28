package edu.ipcmax.core.pcmax;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic query-level work and typed cap ledger. */
public final class PaceWorkLedger {
    private final PaceOptions options;
    private final AtomicLong queryWork = new AtomicLong();
    private final AtomicLong connectorExpansions = new AtomicLong();
    private final AtomicLong connectorCapHits = new AtomicLong();
    private final AtomicLong breakpointCapHits = new AtomicLong();
    private final AtomicLong queryWorkCapHits = new AtomicLong();
    private final EnumSet<PaceCapKind> triggered =
            EnumSet.noneOf(PaceCapKind.class);
    private String firstCanonicalWorkItem = "";

    public PaceWorkLedger(PaceOptions options) {
        this.options = options;
    }

    /**
     * Reserves one canonical partial-candidate expansion before task
     * submission. Calls must occur in reducer order.
     */
    public synchronized boolean reserveQueryWork(String canonicalWorkItem) {
        if (queryWork.get() >= options.queryWorkCapMq()) {
            queryWorkCapHits.incrementAndGet();
            trigger(PaceCapKind.QUERY_WORK_M_Q, canonicalWorkItem);
            return false;
        }
        queryWork.incrementAndGet();
        return true;
    }

    public void addConnectorExpansions(long count) {
        connectorExpansions.addAndGet(count);
    }

    public synchronized void connectorCapReached(String workItem) {
        connectorCapHits.incrementAndGet();
        trigger(PaceCapKind.CONNECTOR_M_C, workItem);
    }

    public synchronized boolean acceptsBreakpoints(
            int exactBreakpointCount,
            String workItem) {
        if (exactBreakpointCount <= options.breakpointCapMb()) {
            return true;
        }
        breakpointCapHits.incrementAndGet();
        trigger(PaceCapKind.BREAKPOINT_M_B, workItem);
        return false;
    }

    public synchronized void emergencyFrontierGuard(String workItem) {
        trigger(PaceCapKind.EMERGENCY_FRONTIER_GUARD, workItem);
    }

    public long queryWork() {
        return queryWork.get();
    }

    public long connectorExpansions() {
        return connectorExpansions.get();
    }

    public long connectorCapHits() {
        return connectorCapHits.get();
    }

    public long breakpointCapHits() {
        return breakpointCapHits.get();
    }

    public long queryWorkCapHits() {
        return queryWorkCapHits.get();
    }

    public synchronized PaceCapStatus capStatus() {
        return new PaceCapStatus(
                Set.copyOf(triggered), firstCanonicalWorkItem);
    }

    private void trigger(PaceCapKind kind, String workItem) {
        triggered.add(kind);
        String canonical = workItem == null ? "" : workItem;
        if (firstCanonicalWorkItem.isEmpty()
                || (!canonical.isEmpty()
                    && canonical.compareTo(firstCanonicalWorkItem) < 0)) {
            firstCanonicalWorkItem = canonical;
        }
    }
}
