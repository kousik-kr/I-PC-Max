# PACE Algorithm Conformance and Runtime Report

> **Historical report, superseded.** This document describes the production
> path before the 2026-07-29 correction mission. The corrected implementation,
> validation results, and current experiment-readiness decision are recorded in
> `docs/PACE_PRE_EXPERIMENT_CORRECTION_REPORT.md`. Statements below about
> missing feasible-entry bands, vertex-ID spatial diversity, full frontier
> recompression, decimal comparison, M_q accounting, and timeout-empty
> instrumentation no longer describe the current working tree.

Date: 2026-07-29 UTC

Repository: `/home/koushik/Kousik/I-PC-Max`

Revision inspected: `main` at
`c8caf96c477e951d9997fa6e45ab4c1ced3e1981`

Authoritative input for this comparison:
`/home/koushik/.codex/attachments/94224a68-9f86-412f-88a7-9e87c21bc7c9/pasted-text.txt`

## Bottom line

The production scalable candidate engine is PACE, not the unrelated
hierarchical/transitive-closure algorithm. It has no parent/child cell
hierarchy, closure propagation, parent summaries, or summary-cover selection.
Its production call path is:

1. `PaceExperimentAlgorithm.run`
   (`src/main/java/edu/ipcmax/experiments/algorithms/PaceExperimentAlgorithm.java:45`)
2. `PACE.run`
   (`src/main/java/edu/ipcmax/core/pcmax/PACE.java:116`)
3. `ForwardLayeredFrontierGenerator.generate`
   (`src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:70`)
4. `EnvelopeExtractor.extract`
   (`src/main/java/edu/ipcmax/core/pcmax/EnvelopeExtractor.java:22`)

The implementation broadly follows the 16 attached PACE steps, but it is not a
fully faithful or scalable implementation of them. The material differences
are:

1. Score relevance is tested against the same broad query horizon for every
   edge, not against an edge-specific conservative feasible-entry band.
2. Pivot “spatial” diversification uses consecutive stable vertex-ID ranges,
   not a coordinate/geometric partition.
3. The exact relationship between `L` and `theta` in the attachment is not
   implemented as a joint selection bound: the code selects up to `L` pivots
   and independently expands to depth `theta`.
4. The class named `IncrementalFrontier` performs a full frontier
   recompression and full temporal-cell reconstruction after every insertion.
5. `M_q` counts connector work reservations, not total candidate/frontier work,
   so the dominant compression work is uncapped.
6. Parallel work covers connector-to-pivot requests only. Frontier reduction,
   full path replay, direct-to-destination completion, compression, temporal
   cell construction, statistics, and envelope extraction are serial.
7. Exact per-step timings are declared in the result schema but are not
   recorded by the PACE adapter. Timeout fallback records lose all live-worker
   counters and phase state.

Therefore the correct answer is: **the implementation is the same PACE
algorithm family and matches most high-level semantics, but it differs in
important preparation, bounding, incremental-maintenance, work-cap, and
parallel-execution details.**

## What “cell” means in this repository

Two unrelated implementation concepts use the word `cell`:

- `GraphPartitionMetadata.Cell` is a flat, deterministic group of consecutive
  stable vertex IDs used by `ScoreSupportIndex` and pivot diversification
  (`src/main/java/edu/ipcmax/core/index/GraphPartitionMetadata.java:14-20`,
  `:32-76`). It has no parent or child and no transitive closure.
- `ProfileCellPartition` creates time intervals from candidate domain, arrival,
  score, and travel-time tie breakpoints
  (`src/main/java/edu/ipcmax/core/pcmax/ProfileCellPartition.java:18-37`).
  These time intervals implement the attachment's step 14 “at most `K_f`
  retained fragments per temporal cell” and step 16 envelope switching.

The timeout report's phrase “temporal cell reconstruction” refers only to the
second concept. It does not refer to hierarchical road-network cells. Calling
it out as a bottleneck does not add an algorithm step; it identifies the
current implementation cost of attached steps 14 and 16.

## Step-by-step conformance

### 1. Normalize the graph contract — MATCH, with an experiment-path caveat

- Stable directed arc IDs are read and required to be consecutive in
  `GeneratedGraphLoader.readStaticEdges`
  (`src/main/java/edu/ipcmax/core/loader/GeneratedGraphLoader.java:201-231`).
- `TDGraph` sorts by arc ID, requires IDs `0..m-1`, and builds separate incoming
  and outgoing adjacency without merging parallel arcs
  (`src/main/java/edu/ipcmax/core/graph/TDGraph.java:33-59`).
- Travel and score functions are attached by the same arc ID
  (`src/main/java/edu/ipcmax/core/loader/GeneratedGraphLoader.java:50-79`,
  `:237-267`).
- `GeneratedGraphLoader.loadVerified` checks the declared conversion contract,
  function support, FIFO arrival functions, and positive travel-time minima
  (`src/main/java/edu/ipcmax/core/loader/GeneratedGraphLoader.java:102-168`).

Caveat: the query worker calls `GeneratedGraphLoader.load`, not
`loadVerified` (`src/main/java/edu/ipcmax/experiments/PaceBench.java:835-851`).
The full launcher therefore depends on the already-completed preflight gate for
conversion-contract and full-support enforcement.

### 2. Validate the query horizon — MATCH

`ForwardLayeredFrontierGenerator.generate` constructs
`[departureStart, departureEnd+B]`
(`src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:70-75`).
`requireCorridorCoverage` checks both functions on every usable corridor arc
and throws `FUNCTION_HORIZON_EXCEEDED`
(`src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:624-638`).
Profile replay also restricts every induced edge entry to the horizon
(`src/main/java/edu/ipcmax/core/pcmax/CanonicalPathProfileBuilder.java:109-129`,
`:143-160`).

### 3. Construct the safe budget corridor — MATCH

`QueryCorridor.build` computes truncated lower-bound distances from the source
and to the destination, then retains an arc exactly when
`d(s,x)+lowerBound(e)+d(y,d) <= B`
(`src/main/java/edu/ipcmax/core/pcmax/QueryCorridor.java:60-115`).
`QueryLowerBounds` uses deterministic lower-bound Dijkstra
(`src/main/java/edu/ipcmax/core/pcmax/QueryLowerBounds.java:98-129`,
`:140-183`).

### 4. Identify score-relevant corridor edges — DIFFERENT

The attachment requires an edge-specific conservative feasible-entry band.
The production caller instead passes the same complete query horizon to pivot
selection for every edge
(`src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:98-106`).
`PivotSelector.select` asks `ScoreSupportIndex.topK` for positive-score overlap
with that common domain and only then filters by corridor membership
(`src/main/java/edu/ipcmax/core/pcmax/PivotSelector.java:77-94`).

This broad range is conservative, so it can retain false positives, but it does
not implement the stated per-edge feasible-entry calculation. It increases the
score-relevant set and pivot-ranking work.

### 5. Select bounded pivot anchors — PARTIAL MATCH

- Up to `L` pivots are selected
  (`src/main/java/edu/ipcmax/core/pcmax/PivotSelector.java:161-174`).
- The stable within-cell order is maximum score, coverage, detour, and arc ID
  (`src/main/java/edu/ipcmax/core/pcmax/PivotSelector.java:96-126`).
- Cells are interleaved round-robin for diversification
  (`src/main/java/edu/ipcmax/core/pcmax/PivotSelector.java:128-159`).

Differences:

- “Budget slack” is represented only indirectly by smaller lower-bound detour.
- The diversification cells are consecutive vertex-ID blocks, not proven
  spatial regions
  (`src/main/java/edu/ipcmax/core/index/GraphPartitionMetadata.java:14-19`,
  `:52-76`).
- `L` bounds selected pivots, while `theta` independently bounds forward depth
  (`src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:152-154`,
  `:202-203`). If “at most `L` or `theta`” means `min(L,theta)` selected pivots,
  the current implementation differs.

### 6. Construct the pivot-free connector graph — MATCH

Connector expansion skips only selected pivot arc IDs and repeated vertices
(`src/main/java/edu/ipcmax/core/pcmax/BoundedConnectorGenerator.java:331-335`).
The canonical replay explicitly states that non-selected score-bearing arcs
remain ordinary scored arcs
(`src/main/java/edu/ipcmax/core/pcmax/CanonicalPathProfileBuilder.java:55-63`).

### 7. Initialize the root candidate — MATCH

The root state is `(source, depth=0, empty used-pivot set)`, and its profile is
the identity arrival with zero score over the complete departure domain
(`src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:134-147`).
Endpoint and used pivots are stored in `StateKey`; path/arrival/score/domain are
stored in `CandidateProfile`; visited vertices are reconstructed from the
stable path before expansion
(`src/main/java/edu/ipcmax/core/profile/CandidateProfile.java:15-22`,
`src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:585-590`).

### 8. Generate connectors on demand — MATCH

`BoundedConnectorGenerator.connect` receives source, target, entry domain,
visited set, residual budget, `K_c`, and `M_c`
(`src/main/java/edu/ipcmax/core/pcmax/BoundedConnectorGenerator.java:73-115`).
The bounded target-directed search uses reverse lower-bound distances, produces
at most `K_c` valid profiles, and stops at `M_c`
(`src/main/java/edu/ipcmax/core/pcmax/BoundedConnectorGenerator.java:252-305`,
`:466-503`). It is invoked only for required endpoints; there is no all-pairs
connector materialization.

### 9. Complete each partial candidate directly to the destination — MATCH

Every retained partial first computes its residual feasible domain and requests
a direct connector to the destination
(`src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:161-198`).

### 10. Extend through unused pivots — MATCH

`pivotExpansions` skips used or vertex-inconsistent pivots, applies connector,
pivot, and suffix lower bounds, derives a residual domain and connector budget,
and reserves the work item
(`src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:350-411`).
`reducePivot` concatenates the connector and pivot arc, replays the profile,
marks the pivot, and inserts the next-layer state
(`src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:450-506`).

### 11. Perform exact temporal stitching — SEMANTIC MATCH, different mechanism

The scalable engine does not call the legacy `TemporalStitch.stitch` method.
It canonicalizes every expanded stable path by replaying it edge by edge.
For each edge it:

- pulls the edge score back through the actual induced entry arrival;
- composes the current arrival with the edge arrival;
- adds the induced score.

Evidence:
`src/main/java/edu/ipcmax/core/pcmax/CanonicalPathProfileBuilder.java:85-135`.
The resulting semantics match the attachment's composition equations, but
replaying the entire path on every extension is more expensive than a cached
incremental composition.

### 12. Restrict the valid stitched domain — MATCH

Replay takes the preimage of each next-edge domain, restricts the final arrival
to the query horizon, and retains only departures whose travel time is within
budget
(`src/main/java/edu/ipcmax/core/pcmax/CanonicalPathProfileBuilder.java:109-129`,
`:143-160`). Residual lower-bound domains are also applied before connector
requests
(`src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:552-564`).

### 13. Enforce looplessness — MATCH

Connector states carry a visited bit set and reject a visited target
(`src/main/java/edu/ipcmax/core/pcmax/BoundedConnectorGenerator.java:276-281`,
`:331-354`). Final concatenations are independently checked vertex by vertex
(`src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:566-582`).
Canonical replay rejects any repeated vertex
(`src/main/java/edu/ipcmax/core/pcmax/CanonicalPathProfileBuilder.java:85-104`).

### 14. Prune and maintain bounded temporal frontiers — SEMANTIC MATCH,
non-scalable maintenance

- Safe score upper bound:
  `src/main/java/edu/ipcmax/core/pcmax/SafeScoreUpperBound.java:13-18`,
  `:73-144`.
- Exact duplicate normalization:
  `src/main/java/edu/ipcmax/core/pcmax/FrontierCompressor.java:70-78`.
- Cell-local extension-safe dominance:
  `src/main/java/edu/ipcmax/core/pcmax/FrontierCompressor.java:306-338`.
- `K_f` bounded retention and champion/earliest/coverage/least-restrictive/fill
  representatives:
  `src/main/java/edu/ipcmax/core/pcmax/FrontierCompressor.java:340-390`.
- Exact temporal cells:
  `src/main/java/edu/ipcmax/core/pcmax/ProfileCellPartition.java:18-91`.

The scalability failure is in maintenance. `IncrementalFrontier.insert` copies
the complete retained set and calls `FrontierCompressor.compress` on all of it
after every insertion
(`src/main/java/edu/ipcmax/core/pcmax/IncrementalFrontier.java:46-79`).
`ProfileCellPartition.cells` then rebuilds all breakpoints and evaluates every
candidate pair for travel-equality roots
(`src/main/java/edu/ipcmax/core/pcmax/ProfileCellPartition.java:22-35`).
Thus the result implements the stated frontier rule, but not a genuinely
incremental scalable frontier.

### 15. Memoize repeated connector requests — MATCH

The connector key contains endpoints, canonical domain, residual budget,
visited set, `K_c`, and `M_c`
(`src/main/java/edu/ipcmax/core/pcmax/BoundedConnectorGenerator.java:88-115`,
`:599-606`). Connector and profile caches use a concurrent single-flight
implementation
(`src/main/java/edu/ipcmax/core/cache/SingleFlightCache.java:18-41`).

### 16. Extract the final profile envelope — MATCH

The final comparator is score descending, travel time ascending, edge count
ascending, then stable path ID
(`src/main/java/edu/ipcmax/core/pcmax/EnvelopeExtractor.java:39-59`).
The envelope is assigned over exact temporal cells and adjacent equal path
assignments are merged
(`src/main/java/edu/ipcmax/core/pcmax/EnvelopeExtractor.java:22-36`,
`:94-170`).

## Runtime evidence

No experiment process is currently running. A host process check on 2026-07-29
found no `java` or `python3` process.

The stopped run is:
`experiments/results/pace_q1_full_24c_250g_100g_nousa_clean_20260729T022350Z`.
It contains 6 successful fixture records and 7 serialized timeouts. The normal
NY evaluation and pilot queries exceeded the 1,800-second query limit.

### Exact wall times that are available

| Execution | Preprocessing | Query | Outcome |
|---|---:|---:|---|
| Normal `NY-EVAL-P001-C510-W120-RHO030` | unavailable for killed worker | `> 1,800 s` | timeout |
| Normal `NY-PILOT-P001-C1110-W120-RHO030` | unavailable for killed worker | `> 1,800 s` | timeout |
| Same evaluation, `no-merge` diagnostic | included in 360 s JFR window | `> 360 s` | diagnostic stop |
| Same evaluation, `no-compression` diagnostic | `24.646489349 s` | `204.126899262 s` | completed |
| Same evaluation, 24-thread normal diagnostic | included in 150 s JFR window | `> 150 s` | diagnostic stop |

The completed no-compression query had 532 corridor nodes, 1,178 corridor
edges, 228 score-relevant edges, 4 selected pivots, 80 connector requests,
20,423 connector expansions, 349 valid connectors, 316 generated candidates,
and no cap hit. Its record is:
`experiments/results/diagnostics/pace_timeout_20260729/eval_c510_no_compression.jsonl`.

### Step attribution available from JFR sampling

The saved recordings do not contain explicit phase timers. The following are
inclusive stack classifications of `jdk.ExecutionSample` events on the serial
`pace-query-worker`; they are statistical CPU attribution, not exact wall
times:

| Recording | Query-worker samples | Frontier insert/compression | Replay | Pivot+corridor+upper bound | Other |
|---|---:|---:|---:|---:|---:|
| Evaluation, normal, 1 thread | 28,695 | 27,980 (97.51%) | 66 (0.23%) | 20 (0.07%) | 629 (2.19%) |
| Pilot, normal, 1 thread | 17,803 | 16,547 (92.94%) | 482 (2.71%) | 70 (0.39%) | 704 (3.95%) |
| Evaluation, no merge | 28,891 | 27,930 (96.67%) | 79 (0.27%) | 26 (0.09%) | 856 (2.96%) |
| Evaluation, normal, 24 threads | 10,636 | 9,659 (90.81%) | 58 (0.55%) | 28 (0.26%) | 891 (8.38%) |

The 24-thread recording contained only 28 sampled worker events: 24 in profile
replay and 4 in connector generation. The serial query/reducer thread remained
in frontier compression for 90.81% of its sampled on-CPU stacks.

The completed no-compression recording shows that disabling the full frontier
logic changes where the remaining cost appears: 4,951 query-worker samples
were in final envelope extraction, while 10,845 were still under
`IncrementalFrontier`/duplicate-only normalization. This is why removing only
adjacent fragment merging did not solve the timeout.

### Why an exact 16-step runtime table cannot be recovered

`PaceBench` declares timing fields for horizon validation, anchor retrieval and
ranking, connector generation, temporal stitching, breakpoint construction,
dominance, retention, merging, and envelope extraction
(`src/main/java/edu/ipcmax/experiments/PaceBench.java:56-62`).
`PaceExperimentAlgorithm.run`, however, records counters only after
`pace.run(query)` returns and sets none of those phase timings
(`src/main/java/edu/ipcmax/experiments/algorithms/PaceExperimentAlgorithm.java:45-115`).

For an isolated timeout, the parent forcibly kills the live worker and starts a
new fallback JVM to serialize the timeout
(`src/main/java/edu/ipcmax/experiments/PaceBench.java:227-253`).
The killed worker's partial timings and counters are therefore unavailable.
Only `query_total` and process CPU total are measured around the whole call
(`src/main/java/edu/ipcmax/experiments/PaceBench.java:451-530`).

Any exact duration assigned now to individual PACE steps would be fabricated.
Before another full experiment, instrumentation must persist live,
timeout-safe phase and subphase snapshots.

## Confirmed bottleneck and why

The primary bottleneck is the implementation of step 14, not an unrelated
algorithm:

1. Every insertion copies and recompresses the full retained frontier
   (`IncrementalFrontier.java:65-77`).
2. Every compression reconstructs all temporal cuts and performs pairwise
   candidate travel-equality analysis
   (`ProfileCellPartition.java:22-35`).
3. `ScoreProfile.breakpoints` performs a linear duplicate scan for each
   interval endpoint
   (`src/main/java/edu/ipcmax/core/profile/ScoreProfile.java:133-140`,
   `:382-394`).
4. Each time comparison calls `Domain.canonicalTime`, which allocates through
   `BigDecimal.valueOf(...).setScale(12, HALF_EVEN)`
   (`src/main/java/edu/ipcmax/core/function/Domain.java:517-529`).
5. The same partition is reconstructed again for statistics and envelope
   extraction
   (`src/main/java/edu/ipcmax/core/pcmax/IncrementalFrontier.java:99-104`,
   `src/main/java/edu/ipcmax/core/pcmax/EnvelopeExtractor.java:31-36`).

Normal evaluation allocated 473.8 GB transiently during the 359-second JFR
window but spent only 2.05 seconds in GC pauses. Live heap after collection was
about 1.8 GB. This is CPU and allocation churn, not a 250 GB heap shortage.

`M_q` does not stop it. The ledger increments once per connector work
reservation
(`src/main/java/edu/ipcmax/core/pcmax/PaceWorkLedger.java:23-35`), not for
candidate-pair checks, breakpoint comparisons, temporal cells, dominance,
restrictions, or merges.

## Information needed before a faithful correction

A whole-project rewrite is neither necessary nor advisable. The existing graph
model, loaders, temporal functions, lower-bound Dijkstra, corridor, connector
search, candidate profile, exact path replay, memoization, cap/status types,
and envelope machinery are reusable.

The pasted high-level list is sufficient to identify the present deviations,
but it is not sufficient to make every bounded choice reproduce the
authoritative algorithm. The following exact information is still required
from the paper/deck or from the algorithm author:

1. The precise formula and endpoint conventions for each edge's conservative
   feasible-entry band.
2. The exact pivot ranking formula: normalization, weights or lexicographic
   order, definition of budget slack, definition of spatial diversity, and all
   tie breakers.
3. Whether the selected pivot count is `L`, `theta`, `min(L,theta)`, or whether
   `theta` only limits how many selected pivots a path may use.
4. The required definition of spatial cells/regions. Stable vertex-ID blocks
   are not inherently spatial.
5. The required `K_c` connector portfolio semantics: which connector paths must
   be retained and the deterministic order among fast, high-score, and diverse
   alternatives.
6. The formal safe score upper bound and the assumptions under which an edge's
   score can be converted to a score-per-travel-time bound.
7. The formal extension-safe dominance relation, especially visited-set and
   used-pivot compatibility.
8. The exact `K_f` representative policy and tie ordering for champion,
   earliest, least restrictive, coverage, and ranked fill.
9. What operations `M_q` must count and the required behavior when `M_c`,
   `M_b`, or `M_q` is reached in PACE-X versus PACE-B.
10. Required numerical exactness: whether the present 12-decimal half-even
    representation is authoritative or may be replaced by fixed integer ticks.
11. Required deterministic parallel granularity and the canonical reduction
    order.
12. Required timeout/progress output schema so an interrupted query preserves
    per-step elapsed time and counters.

With those definitions, the correction is localized mainly to
`PivotSelector`, incremental frontier maintenance/profile breakpoint
representation, the work ledger, instrumentation, and parallel scheduling. It
does not require replacing the whole repository.

## Required next engineering gate

Before restarting the experiment matrix:

1. add timeout-safe timers and counters for all 16 high-level steps and the
   step-14 subphases;
2. implement the per-edge feasible-entry band and confirmed pivot semantics;
3. replace full recompression with affected-cell incremental maintenance;
4. cache canonical breakpoints and remove `BigDecimal` work from inner loops
   without changing numerical semantics;
5. make `M_q` cover the actual candidate/frontier work definition;
6. verify normal PACE-B on the two saved timeout queries;
7. only then resume the full experiment matrix.

No production code was changed while producing this report, and no experiment
was restarted.
