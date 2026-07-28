# PACE Final Algorithm Implementation Plan

This plan implements the target supplied with the repository audit. It is a
change plan only; no production change was made during the audit.

## Outcome and constraints

The target engine will:

1. build a safe lower-bound corridor for one query;
2. select one deterministic query-wide top-`L` pivot set;
3. leave non-selected positive-score arcs inside connectors;
4. construct exact temporal connector/candidate profiles under the
   repository's canonical numerical model;
5. expand candidates forward in layers;
6. prune with residual travel budget and a safe score upper bound;
7. maintain incremental frontiers;
8. separate `K_c`, `K_f`, `M_c`, `M_b`, and `M_q`;
9. return explicit completion/cap status;
10. perform deterministic parallel reduction.

The implementation must preserve stable directed arc IDs, path simplicity,
actual-entry-time score evaluation, endpoint-aware domains, and the distinction
between execution policy and exactness scope.

The missing manuscript and runbook prevent a paper-conformance claim. Before
freezing public semantics, the authors must supply them or decide the open
items in Phase 0.

## Proposed production call graph

```text
PACE.run
  -> QueryLowerBounds
  -> QueryCorridor.build
  -> PivotSelector.selectTopL
  -> ConnectorProfiles (corridor, selectedPivotIds, K_c, M_c)
  -> ForwardLayeredFrontierGenerator
       -> residual-budget restriction
       -> safe score-upper-bound pruning
       -> canonical connector/pivot extension
       -> IncrementalFrontier.insert (K_f, M_b)
       -> CandidateCache single-flight
       -> deterministic parallel batch/reduction
       -> PaceWorkLedger (M_q)
  -> EnvelopeExtractor
  -> PaceGenerationResult(profile, completion, cap/status, counters)
```

`EnvelopeExtractor` remains exact only over the retained root frontier
(`src/main/java/edu/ipcmax/core/pcmax/EnvelopeExtractor.java:18-58`). Global
exactness must never be inferred from the policy name or a successful
extraction.

## Phase 0: freeze externally owned semantics

Status: `EXTERNAL_BLOCKER` for final public/paper contract.

Required author decisions:

1. Supply the current manuscript and
   `PACE_Q1_Experiment_Design_and_Codex_Runbook.md`.
2. Freeze the top-`L` pivot score, tie keys, and whether selection is once per
   query (the target strongly implies once per query).
3. Define the scope and unit of each cap:
   - `M_c`: queue/DFS states expanded per connector request;
   - `M_b`: breakpoints per profile, per frontier, or per query;
   - `M_q`: attempted candidate extensions, completed candidates, or all
     internal work.
4. Decide cap exhaustion semantics. The safe default is:
   - abort generation;
   - serialize `LIMIT_EXCEEDED` plus a typed `cap_triggered`;
   - return no certified profile;
   - use `NOT_CERTIFIED`.
5. Confirm whether PACE-X uses `L=unbounded` and all caps unbounded for tiny
   validation, or whether it also uses a finite selected pivot set.
6. Confirm whether the paper requires arbitrary-rational temporal arithmetic.
   Production currently uses canonical 12-decimal doubles
   (`src/main/java/edu/ipcmax/core/function/Domain.java:20-29`, `:517-529`).

Implementation can begin with fail-closed cap behavior and the current stable
anchor comparator as an explicitly provisional ranking
(`src/main/java/edu/ipcmax/core/pcmax/AnchorIndex.java:136-163`). Publication
claims cannot.

## Phase 1: repair data and query provenance gates

This phase prevents algorithm results from being produced on silently
mis-normalized or mis-described inputs.

### 1.1 Make dataset generation idempotent

Decision: `EXTEND`.

Change `experiments/scripts/generate_dataset_assets.py`:

- Preserve `iter_dimacs_arcs`, `dimacs_to_minutes`, and
  `write_edges_from_raw` (`:81-86`, `:115-192`).
- Stop using the materialized
  `travel_time_functions.jsonl.gz` as the source of a new normalization.
  `generate_base_dataset` currently calls `transform_travel_file` with the same
  path as source and destination (`:629-641`).
- Regenerate first-day functions from a raw/unscaled generator artifact, or
  make the transform read a separately named immutable source file.
- Before atomic replacement, verify for every arc:
  `edge.base_travel_time == function.base_travel_time`, endpoints/ID agree,
  FIFO holds, support is `[0,10080]`, and the post-1440 value is the normalized
  base time.
- Extend `validate_assets` beyond manifest fields
  (`:794-825`) to stream-check the payloads.
- Add a temporary-fixture test that runs overwrite twice and obtains identical
  bytes/checksums.

### 1.2 Enforce the normalization contract in Java

Decision: `EXTEND`.

Change `GeneratedGraphLoader.readManifest/load`
(`src/main/java/edu/ipcmax/core/loader/GeneratedGraphLoader.java:43-94`,
`:202-218`) and `ManifestSummary`:

- retain contract ID/formula/numerator/denominator and generator config hash;
- optionally require a caller-supplied contract;
- reject contract or support mismatch before constructing `TDGraph`;
- cross-check the edge/function base time while streaming;
- retain the current directed ID/endpoint checks (`:127-198`).

### 1.3 Use one real paper query generator

Decision: `REPLACE`.

Change `experiments/scripts/generate_query_sets.py`:

- `_run_java_generator` must execute
  `design["query_generation"]["paper_java_main_class"]` instead of calling
  `_generate_graph_backed_queries_python`
  (`experiments/scripts/generate_query_sets.py:321-378`);
- remove/retire `_StaticGraph` and Python path/budget construction (`:381-407`,
  `:624-718`);
- rename the output summary key from `"java"` only after it genuinely
  represents Java output (`:1037-1063`);
- make validate-only compare bytes from that same Java path.

Change `PaperQuerySetGenerator` or replace it with a new paper-specific
composition of:

- `GeneratedGraphLoader`;
- `QueryCandidateSampler` lower-bound sampling
  (`src/main/java/edu/ipcmax/experiments/querygen/QueryCandidateSampler.java:83-177`);
- the required budget builder.

Do not retain the current random-walk witness/grid-max implementation as a
paper-fastest-path claim
(`PaperQuerySetGenerator.java:319-419`, `:674-767`). If the configured
`GRID_LOWER_BOUND_WITNESS_PATH_TRAVEL_TIME` is intentional, rename all fields
and documentation to say exactly that and keep it separate from
`QueryBudgetBuilder`'s continuous fastest profile
(`QueryBudgetBuilder.java:194-243`).

## Phase 2: introduce the target option and result contracts

### 2.1 Replace the shared `K`

Decision: `CREATE`.

Change `PaceOptions`
(`src/main/java/edu/ipcmax/core/pcmax/PaceOptions.java:15-50`) to contain:

```text
policy
theta
pivotLimitL
connectorLimitKc
frontierLimitKf
connectorExpansionCapMc
breakpointCapMb
queryWorkCapMq
threadCount
memoizationEnabled
features
emergencyFrontierGuard
```

Use a long-valued unlimited sentinel for work/expansion caps. Keep a deprecated
compatibility factory `bounded(theta,L,K)` only if necessary; it must map to
`K_c=K_f=K` and make the absence of `M_*` explicit. New experiment configs must
never use the compatibility factory.

Update:

- `BenchOptions` parse/normalize
  (`src/main/java/edu/ipcmax/experiments/BenchOptions.java:65-125`,
  `:187-231`);
- `AlgorithmConfig` and `paceOptions`
  (`src/main/java/edu/ipcmax/experiments/framework/AlgorithmConfig.java:8-45`);
- `experiments/configs/studies/*.yaml`;
- paper/smoke configs and config schema;
- CLI help and README examples.

### 2.2 Add completion-bearing generation results

Decision: `CREATE`.

Add:

- `PaceGenerationResult`:
  `CandidateSet frontier`, `PaceCompletion completion`,
  `PaceCapStatus capStatus`, `PaceGenerationStats stats`;
- `PaceCompletion`: `COMPLETE`, `NO_FEASIBLE_PATH`, `ABORTED`;
- `PaceCapKind`: `NONE`, `M_C`, `M_B`, `M_Q`,
  `EMERGENCY_FRONTIER_GUARD`;
- requested/observed cap values and the first canonical work item that could
  not be admitted.

`PACE.run` currently receives only a `CandidateSet` and always extracts an
envelope (`src/main/java/edu/ipcmax/core/pcmax/PACE.java:37-40`). Change it to
fail closed on an aborted generation and expose a status-bearing public method.
Retain a compatibility `run` method only if it throws a typed `PaceException`
on non-complete generation.

Extend `PaceGenerationStats`, which currently has eight counters
(`src/main/java/edu/ipcmax/core/pcmax/PaceGenerationStats.java:4-16`), with:

- corridor vertices/arcs rejected/retained;
- positive score arcs discovered, pivots selected/rejected;
- connector requests/states expanded/completed/rejected;
- layer states and expansion descriptors;
- residual-budget and score-upper-bound rejections;
- stitch attempts/failures/successes;
- candidates created/inserted/deduplicated/dominated/`K_f`-removed;
- breakpoints created/retained;
- memo entries/hits/misses/waits;
- tasks scheduled/started and maximum observed concurrent workers;
- `M_c`, `M_b`, and `M_q` requested/consumed;
- triggered cap and completion.

## Phase 3: build the safe query corridor

Decision: `CREATE`, using `QueryLowerBounds` as `EXTEND`.

Add `src/main/java/edu/ipcmax/core/pcmax/QueryCorridor.java`.

`QueryCorridor.build(graph, lowerBounds, source, destination, budget)`:

1. obtain `d_s` from `QueryLowerBounds.distancesFrom(source)`;
2. obtain `d_t` from `QueryLowerBounds.distancesTo(destination)`;
3. retain directed arc `e=(u,v)` iff all values are finite and
   `d_s(u) + lowerBounds.edgeWeight(e) + d_t(v) <= budget`, using
   `Domain.canonicalTime` consistently;
4. retain source, destination, and incident vertices;
5. expose outgoing/incoming arc-ID lists in stable order;
6. compute a stable version/hash from query horizon, budget, source/destination,
   and retained arc IDs.

This test is safe because every budget-feasible path has a prefix, the arc, and
a suffix whose lower-bound sum is no greater than its actual travel time.
Do not use the query-generation corridor count as a graph view:
`QueryBudgetBuilder.countCorridorAnchors` only counts anchors
(`src/main/java/edu/ipcmax/experiments/querygen/QueryBudgetBuilder.java:388-407`).

Extend `QueryLowerBounds.Distances` with deterministic iteration or membership
access, while retaining its query-horizon weights and cached Dijkstra
(`src/main/java/edu/ipcmax/core/pcmax/QueryLowerBounds.java:22-90`).

Tests:

- compare corridor membership to brute-force budget-feasible simple paths on
  tiny graphs;
- cover directed/parallel arcs, zero weights, exact budget equality,
  unreachable endpoints, and disconnected temporal domains;
- assert no brute-force feasible path uses an excluded arc;
- assert deterministic arc ordering/checksum.

## Phase 4: replace all-positive anchors with selected pivots

Decision: `REPLACE`.

Refactor `AnchorIndex` into two responsibilities:

1. `PositiveScoreArcIndex.discover(corridor, queryHorizon)`:
   validate temporal coverage and record positive domains/potentials only for
   corridor arcs.
2. `PivotSelector.selectTopL(...)`:
   rank once for the root query, apply stable tie keys, and return immutable
   `PivotIndex` with selected arc IDs and version.

The current `AnchorIndex.create` scans every graph edge and makes every
positive-score edge an anchor
(`src/main/java/edu/ipcmax/core/pcmax/AnchorIndex.java:37-58`). The current
`relevantAnchors` may be reused as a source for lower-bound/window/ranking
features (`:89-165`), but it must not reselect a different top-`L` set in each
subproblem.

Rules:

- selection is restricted to the safe query corridor;
- selected IDs are stable and query-wide;
- non-selected positive arcs remain ordinary connector arcs;
- a layer may filter selected pivots by reachability, residual budget, used
  vertices, and temporal domain, but never add a non-selected pivot;
- the selected-set version enters every memo/cache key.

Tests:

- more than `L` positive arcs with stable score/tie ordering;
- selected and non-selected positive parallel arcs;
- a winning path whose score comes only from a non-selected connector arc;
- the same selected set across subproblems and thread counts;
- `L=0`, `L>=positive-count`, and exact ties.

## Phase 5: rebuild connector generation

Decision: `REPLACE` enumeration, `VERIFIED_REUSE` exact profile replay.

Change `ConnectorProfiles` constructor to receive:

```text
TDGraph
QueryCorridor
PivotIndex
QueryLowerBounds
connectorLimitKc
connectorExpansionCapMc
PaceWorkLedger
```

Enumeration rules:

1. traverse only corridor arcs;
2. exclude only selected pivot arc IDs;
3. keep non-selected positive-score arcs;
4. preserve vertex simplicity and stable arc ordering;
5. prune a path state with lower-bound completion and the caller's residual
   budget;
6. count every popped/expanded state against `M_c`;
7. return `ConnectorResult(candidates, completion, expandedStates)`;
8. in PACE-B retain at most `K_c` completed nonempty-domain profiles using the
   frozen deterministic order;
9. in PACE-X enumerate exhaustively unless a fail-closed emergency cap is
   explicitly configured.

Retain `CanonicalPathProfileBuilder.replay` for completed connectors. It
already propagates actual entry, score, horizon, budget, continuity, and
simplicity (`src/main/java/edu/ipcmax/core/pcmax/CanonicalPathProfileBuilder.java:29-123`).
Remove the assertion that every positive score arc is forbidden; current
`ConnectorProfiles.buildCandidate` makes that assertion at
`src/main/java/edu/ipcmax/core/pcmax/ConnectorProfiles.java:213-236`.

Tests:

- connector with several non-selected positive-score arcs and exact accumulated
  score profile;
- selected pivot arc never appears in a connector;
- `K_c` does not affect frontier `K_f`;
- `M_c-1`, exactly `M_c`, and `M_c+1` expansion cases;
- cap status is explicit and no partial result is cached as complete;
- exhaustive connector IDs/domains match a brute-force enumerator.

## Phase 6: replace recursive splitting with forward layers

Decision: `REPLACE`.

Add `ForwardLayeredFrontierGenerator` and make
`PaceFrontierGenerator` either a compatibility facade or remove it after API
migration. The old `generate/computeFrontier/stitchForAnchor` implementation
at `src/main/java/edu/ipcmax/core/pcmax/PaceFrontierGenerator.java:137-297`
must not remain on the production PACE path.

### 6.1 State model

Add:

```text
LayerKey(layer, endpoint, usedPivotSignature)
LayerCandidate(profile, endpoint, usedPivotSignature, layer)
```

`CandidateProfile` keeps exact domain/arrival/score/path data, but gains clear
forward-layer metadata. Preserve `vertexSequence`, `internalVertices`, and
`isVertexSimple` (`src/main/java/edu/ipcmax/core/profile/CandidateProfile.java:86-139`).

### 6.2 Expansion

Start with the source identity candidate in layer 0. For every canonical layer
state:

1. request final connectors from its endpoint to the destination and insert
   complete path candidates into the root incremental frontier;
2. for every unused selected pivot in stable order:
   - request connectors from the state endpoint to the pivot source;
   - reject vertex-inconsistent concatenations;
   - append connector and pivot at actual entry time;
   - restrict by horizon and residual budget;
   - insert the new prefix into layer `layer+1`;
3. stop creating pivot prefixes at `theta`;
4. canonical-replay every new stable arc sequence before publication.

Use `TemporalStitch` composition and path checks where its current
left-anchor-right shape applies
(`src/main/java/edu/ipcmax/core/pcmax/TemporalStitch.java:54-201`). Add a
forward-extension helper rather than faking recursive right subproblems.
`CanonicalPathProfileBuilder.replay` is the final decomposition-independent
authority.

### 6.3 Residual-budget pruning

Decision: `CREATE`.

For a prefix ending at `v`, compute on root departure `t`:

```text
spent(t) = prefix.arrival(t) - t
remaining(t) = B - spent(t)
```

Use `d_t(v)` from the root query lower bounds. Restrict the prefix domain to:

```text
spent(t) + d_t(v) <= B
```

Use `TimeProfile.domainWhereTravelTimeAtMost` and exact preimages rather than
sampling (`src/main/java/edu/ipcmax/core/profile/TimeProfile.java:195-237`).
Pass a safe scalar/domain bound to connector search; never pass the original
root budget as if no prefix cost had been spent. The current recursive calls do
that at `PaceFrontierGenerator.java:260-282`.

### 6.4 Safe score upper bound

Decision: `CREATE`.

Add `SafeScoreUpperBound`:

- include every selected, unused, lower-bound-reachable pivot's maximum score;
- include every non-selected positive-score corridor arc that can still lie on
  a lower-bound-feasible completion;
- never subtract an arc merely because ordering is uncertain;
- use overflow-safe `long` accumulation;
- optionally refine by temporal cell, but only from exact domains;
- prune only if the upper bound is strictly below the incumbent score
  everywhere in the complete cell; apply the full score/travel/edge/path tie
  order when equality is involved.

The bound may initially be loose (even the sum of all remaining corridor arc
maxima) but must be provably optimistic. Current `scorePotential` is only a
ranking feature and is not safe pruning evidence
(`src/main/java/edu/ipcmax/core/pcmax/AnchorIndex.java:136-157`).

### 6.5 Incremental frontiers

Decision: `CREATE`, reusing compressor primitives as `EXTEND`.

Add `IncrementalFrontier`:

- normalize/restrict a candidate as it arrives;
- remove exact duplicates;
- build/refine affected cells;
- apply `SafeProfileDominance`;
- apply `K_f` per cell only in PACE-B;
- merge adjacent compatible fragments;
- charge every new cut to `M_b`;
- preserve deterministic `CandidateSet.STABLE_ORDER`.

Extract helpers from `FrontierCompressor`, whose existing full-set algorithms
are at `src/main/java/edu/ipcmax/core/pcmax/FrontierCompressor.java:32-123`,
`:253-381`, and `:560-754`. Do not weaken the dominance conditions in
`SafeProfileDominance.java:36-121`.

Tests:

- incremental insertion in every permutation yields the same canonical
  frontier;
- incremental result equals current batch compression when caps are disabled;
- boundary singleton, disconnected-domain, travel-tie-root, and score-boundary
  cases;
- `K_f` independent of `K_c`;
- `M_b` exact boundary behavior and typed cap result;
- no extension-safe winner is removed in exhaustive tiny suffix tests.

## Phase 7: deterministic work budgets, memoization, and parallelism

### 7.1 Query work ledger

Decision: `CREATE`.

Add `PaceWorkLedger` with immutable limits and monotonic counters for `M_c`,
`M_b`, and `M_q`. It returns typed reservation results; no component directly
throws a generic limit exception without recording the triggering component
and observed count.

The exact `M_q` unit is a Phase-0 decision. Once frozen, assign every unit a
canonical ordinal derived from:

```text
layer
state stable key
pivot stable arc ID or FINAL
connector stable path/state order
candidate stable path ID
```

### 7.2 Extend memoization

Decision: `EXTEND`.

Retain `CandidateCache.getOrCompute` single-flight behavior
(`src/main/java/edu/ipcmax/core/cache/CandidateCache.java:38-55`), but:

- cache `FrontierResult`/`ConnectorResult` with completion status;
- never reuse an aborted partial value as complete;
- include corridor version, selected pivot-set version, layer state,
  `K_c,K_f,M_c,M_b,M_q`, feature version, numerical contract, budget, and
  domains in the key;
- count cache waits separately from hits;
- scope or version the cache so a graph with equal node/edge counts cannot
  collide. The current graph version is only counts
  (`PaceFrontierGenerator.java:115-123`).

Replace the recursive shape in `MemoKey`
(`src/main/java/edu/ipcmax/core/cache/MemoKey.java:13-60`) with a forward-layer
key; retain canonical domain serialization (`:188-215`).

### 7.3 Deterministic parallel reduction

Decision: `EXTEND`.

Retain `IPCMaxParallelExecutor`'s bounded `ForkJoinPool` and input-order future
reads (`src/main/java/edu/ipcmax/core/pcmax/IPCMaxParallelExecutor.java:18-38`).

Implement:

1. construct expansion descriptors in canonical order;
2. reserve deterministic work ranges before task submission;
3. run pure connector/extension tasks without mutating shared frontiers;
4. return indexed immutable results;
5. reduce by descriptor index, not completion time;
6. update incremental frontiers and work status only in the reducer;
7. schedule bounded batches so a cap cannot cause uncontrolled speculative
   work;
8. cancel later tasks after a fail-closed cap is committed;
9. record maximum simultaneous workers using production instrumentation.

The current production implementation performs real work only for the root
anchor fan-out (`PaceFrontierGenerator.java:187-208`). Remove that special case
when the layered scheduler is active.

Acceptance:

- threads 1, 2, 4, and host maximum produce identical profile checksum,
  completion, cap trigger, observed deterministic work counts, and selected
  pivot/connector IDs;
- a test observes more than one active worker for a sufficiently wide layer;
- repeated runs are byte-identical except explicitly volatile provenance
  fields;
- single-flight prevents duplicate computation under concurrent requests.

## Phase 8: policy semantics and exactness

Decision: `EXTEND`.

PACE-X:

- tiny-instance validation policy;
- exhaustive corridor connectors;
- no lossy `K_c`/`K_f`;
- no `M_c`/`M_b`/`M_q` unless an explicit fail-closed emergency guard is
  configured;
- exact only over the represented numerical model and only if all completeness
  conditions, including sufficient pivots/theta, are certified.

PACE-B:

- finite `L,K_c,K_f,M_c,M_b,M_q`;
- deterministic bounded retained frontier;
- exactness scope `RETAINED_FRONTIER` only when generation completes under the
  policy;
- `NOT_CERTIFIED` on fail-closed cap abort;
- no global-optimality claim.

Retain the current separation among `status_code`, `execution_policy`, and
`exactness_scope` in `PaceBench.record`
(`src/main/java/edu/ipcmax/experiments/PaceBench.java:545-553`). Remove
configuration labels that claim "exhaustive connectors/frontier" solely from
algorithm ID (`:586-636`); populate them from the completed generation
certificate.

## Phase 9: instrumentation and publication artifacts

Decision: `EXTEND`.

### 9.1 Java records

Change `PaceExperimentAlgorithm.run`
(`src/main/java/edu/ipcmax/experiments/algorithms/PaceExperimentAlgorithm.java:29-46`)
to map every new generation counter and cap status.

Change `PaceBench.record`:

- copy `Execution.startMemory/endMemory` and sampled peak into
  `memory_bytes`; current code measures them at
  `PaceBench.java:424-481` but writes null at `:562-569`;
- populate memo/frontier peak estimates;
- time corridor, pivot discovery/ranking, connectors, layered expansion,
  bounds, incremental compression, breakpoints, memo, reduction, validation,
  and envelope extraction;
- populate `failing_phase`;
- distinguish requested from observed thread count/concurrency;
- serialize the generation completion/cap certificate.

Use heap terminology if measuring JVM heap; do not label it RSS. For true RSS,
measure the isolated worker process from the parent and document sampling
frequency.

### 9.2 Schema v3

Extend `experiments/schemas/result_record.schema.json` with:

```text
configuration.pivot_limit_l
configuration.connector_limit_kc
configuration.frontier_limit_kf
configuration.connector_expansion_cap_mc
configuration.breakpoint_cap_mb
configuration.query_work_cap_mq
status.generation_completion
status.cap_triggered
status.partial_output_policy
status.certificate_conditions
counters.corridor_*
counters.pivots_*
counters.connector_states_expanded
counters.layer_*
counters.residual_budget_rejections
counters.score_upper_bound_rejections
counters.candidate_work_*
counters.breakpoints_*
counters.parallel_workers_observed
```

Keep schema-v1/v2 readers for old results. Require numeric successful-run
memory and target counters where collection is enabled; current schema permits
the null records seen in production
(`experiments/schemas/result_record.schema.json:77-98`).

### 9.3 Collection, validation, summaries, tables, plots

Change:

- `experiments/scripts/collect_results.py:29-75` to flatten cap and target
  counters;
- `experiments/scripts/validate_results.py:33-129` to check non-null enabled
  metrics, cap consistency, exactness/certificate consistency, and
  cross-thread equality of profile plus deterministic counters;
- `experiments/scripts/resolve_pace_b.py:25-77` to select over
  `L,K_c,K_f,M_c,M_b,M_q` and reject incomplete/capped candidates;
- `experiments/scripts/summarize_results.py:78-182` to aggregate completion,
  caps, candidate work, corridor size, pivot/connector counts, frontier sizes,
  breakpoints, real memory, and relative score gap;
- `experiments/tables/make_all_tables.py:74-110` so T8 actually contains
  internal counters and all tables expose completion/cap rates;
- `experiments/plots/make_all_plots.py:17-40` and the specialized plot modules
  so quality/cost, cap incidence, compactness, memory, and parallel speedup use
  their intended axes instead of generic one-metric bars.

`resolve_pace_b` currently cannot accept any row because it requires numeric
`peak_rss` (`resolve_pace_b.py:40-51`) while `PaceBench` writes null. Fix and
schema-test that path before another full run.

## Test and verification matrix

All new tests must inspect the public production path (`PACE.run` or the
status-bearing successor), not call only helpers.

| Gate | Required verification |
|---|---|
| Existing regression | `mvn clean test`: retain all current 155 passing tests. |
| Python regression | `python3 -m pytest experiments/tests`: retain all current 14 passing tests. |
| Corridor safety | Exhaustive tiny-graph differential: every feasible simple path is contained. |
| Pivot semantics | Query-wide stable top-`L`; non-selected score arcs remain in connectors. |
| Exact profiles | Connector/prefix/final replay matches the independent rational oracle within the repository contract, including endpoints. |
| Layered equivalence | With unlimited controls, layered PACE-X matches exhaustive simple-path envelope on the seeded tiny corpus. |
| Residual pruning | Disabling/enabling the safe prune yields the same exhaustive envelope and fewer/equal expansions. |
| Score bound | Bound never underestimates brute-force remaining score; pruning preserves exhaustive envelope. |
| `K_c`/`K_f` independence | Vary each separately and verify only its intended stage changes. |
| `M_c`/`M_b`/`M_q` | Exact off-by-one counters, typed cap, fail-closed behavior, no false completion/certificate. |
| Incremental frontier | Permutation-independent result; equivalence to batch compressor with limits disabled. |
| Memo/single-flight | One computation per key; failed/aborted values are not published as complete. |
| Parallel determinism | Threads 1/N: same selected pivots, candidates, counters, cap, checksum; observed concurrency >1. |
| Dataset generation | Two overwrites are byte-identical; mismatched edge/function normalization is rejected. |
| Query generation | The configured Java main class is executed; Python does not parse graph payloads. |
| Instrumentation | Successful enabled records contain non-null memory/timings/counters; schema and resolver accept them. |
| Release pipeline | Preflight, smoke, E01, E02 resolver, validation, summaries, T1-T8, F1-F8, and package gates pass. |

## Recommended implementation order and stop gates

1. Obtain/freeze Phase-0 decisions.
2. Repair dataset idempotence and query-generator truth; stop if regenerated
   hashes are not stable.
3. Add option/result/schema types without changing algorithm output.
4. Add corridor and prove its safety on exhaustive tiny graphs.
5. Add query-wide pivots and connector semantics; prove non-selected score
   retention.
6. Implement forward layers with all caps disabled; require exhaustive PACE-X
   equivalence before continuing.
7. Add residual pruning and safe score upper bound one at a time; require
   equivalence after each.
8. Add incremental frontiers; require batch equivalence with bounds disabled.
9. Add `K_c,K_f,M_c,M_b,M_q` and explicit fail-closed status.
10. Extend memoization and then parallelism; require deterministic output,
    counters, and cap behavior.
11. Complete benchmark/schema/summary/table/plot instrumentation.
12. Run the full Java/Python suites, preflight, smoke, E01, and E02. Do not
    start E03-E13 until the pilot resolver produces a frozen, non-null,
    completion-qualified parameter record.

## Definition of done

Implementation is complete only when:

- no production path calls the old left/right recursive generator;
- the corridor and selected pivot set are serialized and reproducible;
- non-selected positive-score arcs demonstrably contribute inside connectors;
- `K_c`, `K_f`, `M_c`, `M_b`, and `M_q` are independent and reported;
- cap exhaustion is explicit and cannot be mistaken for completion;
- the unlimited layered policy matches exhaustive envelopes on the independent
  corpus;
- parallel and serial executions have identical semantic output and
  deterministic work/cap records;
- paper query generation genuinely uses the declared Java production path;
- successful benchmark records contain the metrics consumed by the resolver,
  summaries, tables, and plots;
- all existing and new tests pass;
- the supplied manuscript/runbook has been traced to the implementation with
  file/class/method/line evidence.

