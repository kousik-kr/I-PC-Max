# Basic iSCOPE pruning and completion audit

**Variant:** `CONTINUOUS_PROFILE_EXHAUSTIVE_PATH_ADAPTATION-v1`. This is the existing basic adaptation, not the optimized published WISE implementation. The interface continues to use the shared exact objective evaluator.

## Search audit

The iSCOPE adapter no longer invokes `LowerBoundGraph`, reverse lower-bound Dijkstra, branch feasibility pruning, a numerical deadline estimate, or a floating residual-budget test. It calls `SimplePathSearch.exhaustiveStreamingUnpruned`, which traverses outgoing directed arcs in deterministic order and rejects a next vertex only when that vertex is already in the current path history. This is the structural looplessness invariant for directed vertex-simple paths.

Each complete path is replayed with `ExactPreferenceReplay` before acceptance. An empty exact feasible domain is safe to reject for the preference objective. A nonempty exact-feasible route enters the emitted registry even if the legacy projection cannot profile it. The exact route registry is then passed to `ExactPreferenceObjective`; the legacy envelope is kept only for compatibility.

| Decision | Invariant | Rational status |
| --- | --- | --- |
| Visited-vertex rejection | A route containing a repeated vertex is outside the declared vertex-simple route set. | Exact structural check; safe. |
| Directed adjacency traversal | Every emitted arc starts at the current node and ends at the next node. | Uses graph outgoing adjacency and stable arc IDs; safe. |
| Exact route-feasibility rejection | A path with no departure satisfying `A_P(t)-t <= B` cannot contribute to any selected cell. | Exact rational replay; safe. |
| Lower-bound feasibility | No lower-bound feasibility decision is active in the iSCOPE DFS fallback. | Disabled. Counter remains zero. |
| Budget branch pruning | No branch is removed using accumulated double travel time or remaining-budget estimates. | Disabled. |
| Route commitment | Exact-feasible paths are retained independently of whether the legacy projection can replay them. | Exact objective uses all accepted stable arc-ID sequences. |
| Deadline propagation | Search and finalization observe a monotonic cooperative deadline. | A time cap, path cap, or unexhausted DFS is `NOT_CERTIFIED`; exact output over the returned registry remains available. |

The configured algorithm cap remains five seconds, with a finalization reserve. The default path cap remains a separate candidate cap. Exceeding either cap cannot yield a global certificate, even if the returned registry has a complete exact profile. The exact objective metadata is downgraded together with the result if finalization crosses the deadline.

## Regression evidence and limits

`IScopeWholeProfileOracleTest` checks open cells, owned singleton boundaries, score discontinuities, travel-budget roots, FIFO plateaus, ties, disconnected routes, and a fixed seeded corpus. It verifies that exhaustive uncapped tiny searches match the independent whole-profile oracle and that partial/capped results remain uncertified. `PreferenceAdaptersCrossOracleTest` runs iSCOPE on the SICSP wrong-winner and false-budget graphs, compares its emitted-registry envelope against `TinyContinuousEnvelopeOracle.solveCellsForPaths`, and separately records the global oracle cells.

The algorithm still profiles completed paths through a compatibility projection for its legacy `AlgorithmResult.profile`. The exact profile is authoritative. Large-graph runtime may be exponential; no scalability or optimized-WISE claim is made.
