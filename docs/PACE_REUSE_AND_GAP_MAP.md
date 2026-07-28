# PACE Reuse and Gap Map

Audit basis: actual working tree at
`c79924409ad2264b89af5623272392c1b5244c9d` on `main`, including pre-existing
dirty and untracked implementation files. Tests are not used as substitutes
for production evidence.

## Classification legend

- `VERIFIED_REUSE`: the production call path implements the required behavior
  and can be retained without semantic change.
- `EXTEND`: the production implementation is useful but requires new state,
  validation, integration, or behavior.
- `REPLACE`: the current implementation's central semantics conflict with the
  target.
- `CREATE`: no production implementation of the target component exists.
- `EXTERNAL_BLOCKER`: required source material or an author-level semantic
  decision is outside the repository.

## Required repository components

| Required component | Decision | Reuse boundary / required change | Line-level production evidence |
|---|---|---|---|
| 1. Graph parsing and directed arc IDs | `VERIFIED_REUSE` | Retain loader, `Edge`, and `TDGraph` identity/adjacency rules. | `GeneratedGraphLoader.load/readStaticEdges/readTravelTimeFunctions/readScoreFunctions`, `src/main/java/edu/ipcmax/core/loader/GeneratedGraphLoader.java:43-94`, `:127-198`; `TDGraph.<init>`, `src/main/java/edu/ipcmax/core/graph/TDGraph.java:25-68`, `:79-121`. |
| 2. DIMACS loading and `declared_centisecond_normalization-v1` | `EXTEND` | Retain DIMACS pairing and Decimal conversion. Make generation idempotent and validate edge/function numeric agreement plus contract identity in the production loader. | `iter_dimacs_arcs`, `write_edges_from_raw`, `dimacs_to_minutes`, `experiments/scripts/generate_dataset_assets.py:81-86`, `:115-192`; unsafe source=destination transform in `generate_base_dataset`, `:597-645`; shallow `validate_assets`, `:794-825`; manifest fields ignored by `GeneratedGraphLoader.readManifest`, `GeneratedGraphLoader.java:202-218`. |
| 3. Temporal arrival and score functions | `VERIFIED_REUSE` | Retain under the repository's documented 12-decimal numerical contract. Arbitrary-rational paper claims remain outside this classification. | `PiecewiseLinearFn.arrivalTimeAt/isFifo`, `src/main/java/edu/ipcmax/core/function/PiecewiseLinearFn.java:76-145`; `PiecewiseConstFn.valueAt/positiveDomain`, `src/main/java/edu/ipcmax/core/function/PiecewiseConstFn.java:101-157`; `ScoreProfile.compose`, `src/main/java/edu/ipcmax/core/profile/ScoreProfile.java:193-232`; `Domain.canonicalTime`, `src/main/java/edu/ipcmax/core/function/Domain.java:20-29`, `:517-529`. |
| 4. Temporal support through 10080 | `EXTEND` | Retain horizon construction and per-edge coverage rejection; add loader and asset semantic enforcement. | `PaceFrontierGenerator.execute`, `src/main/java/edu/ipcmax/core/pcmax/PaceFrontierGenerator.java:103-111`; `AnchorIndex.create/requireCoverage`, `src/main/java/edu/ipcmax/core/pcmax/AnchorIndex.java:37-58`, `:167-175`; materialized declaration, `data/input/NY/manifest.json:71-85`. |
| 5. Fixed-departure fastest path | `VERIFIED_REUSE` | Retain for integer departure instants and deterministic arc-ID witnesses. Add a continuous-departure overload only if the missing manuscript requires it. | `PointForwardLabeling.run/Result.pathTo`, `src/main/java/edu/ipcmax/core/labeling/PointForwardLabeling.java:30-62`, `:107-128`. |
| 6. Lower-bound distance/index | `EXTEND` | Retain query-horizon edge minima and forward/reverse Dijkstra caches; create the target corridor on top. No routing index exists. | `QueryLowerBounds.<init>/distancesFrom/distancesTo/dijkstra`, `src/main/java/edu/ipcmax/core/pcmax/QueryLowerBounds.java:22-90`; legacy witness index, `LowerBoundGraph`, `src/main/java/edu/ipcmax/core/graph/LowerBoundGraph.java:22-87`. |
| 7. Query generation classes and current paper path | `REPLACE` | Preserve useful legacy sampling/budget routines only as helpers. Route the paper entry point through one production Java generator/loader and implement the frozen budget definition there. Remove misleading Python `"java"` wrapper. | Legacy chain: `QuerySetGenerator.generate`, `src/main/java/edu/ipcmax/experiments/querygen/QuerySetGenerator.java:56-78`; `DatasetQueryGenerator.generate`, `src/main/java/edu/ipcmax/experiments/querygen/DatasetQueryGenerator.java:58-72`, `:100-110`; `QueryCandidateSampler.sample`, `src/main/java/edu/ipcmax/experiments/querygen/QueryCandidateSampler.java:83-177`; `QueryBudgetBuilder.buildDetailed`, `src/main/java/edu/ipcmax/experiments/querygen/QueryBudgetBuilder.java:178-243`. Actual paper chain: `_run_java_generator`, `experiments/scripts/generate_query_sets.py:321-378`; `_StaticGraph.load`, `:381-407`; `_budget_for_cell`, `:658-695`; `generate`, `:1006-1063`. |
| 8. Anchor identification | `REPLACE` | Replace all-positive-edge anchor identity and recursive local top-`L` with a query-corridor-scoped, deterministic, query-wide selected pivot set. | `AnchorIndex.create`, `src/main/java/edu/ipcmax/core/pcmax/AnchorIndex.java:37-58`; `AnchorIndex.relevantAnchors`, `:89-165`; `anchorArcIds`, `:71-78`. |
| 9. Connector generation | `REPLACE` | Rebuild enumeration over the safe corridor, excluding selected pivots only. Retain exact replay for each completed connector. Add independent `K_c` and `M_c`. | `ConnectorProfiles.generate`, `src/main/java/edu/ipcmax/core/pcmax/ConnectorProfiles.java:43-77`; exhaustive exclusions, `:80-141`; bounded exclusions, `:143-210`; exact candidate replay, `:213-236`. |
| 10. Candidate profile representation | `EXTEND` | Retain domain, time/score profiles, and stable path. Add layer/pivot state, residual-feasibility metadata, work/cap provenance, and completion status; rename historic `recursionDepth`. | `CandidateProfile` record and validation, `src/main/java/edu/ipcmax/core/profile/CandidateProfile.java:15-37`; path and signature methods, `:57-139`. |
| 11. `GenerateFrontier` | `REPLACE` | Remove left/right recursive split generation. Introduce canonical forward expansion layers and incremental frontier updates. | `PaceFrontierGenerator.generate/computeFrontier`, `src/main/java/edu/ipcmax/core/pcmax/PaceFrontierGenerator.java:137-246`; `stitchForAnchor` split loop and two recursive calls, `:248-297`. |
| 12. Temporal stitching and breakpoint construction | `EXTEND` | Reuse exact staged stitching, canonical replay, and exact cell cuts. Add budget/cap-aware forward-prefix APIs and `M_b` accounting/status. | `TemporalStitch.stitch`, `src/main/java/edu/ipcmax/core/pcmax/TemporalStitch.java:54-134`; `CanonicalPathProfileBuilder.replay`, `src/main/java/edu/ipcmax/core/pcmax/CanonicalPathProfileBuilder.java:29-123`; `ProfileCellPartition.cells`, `src/main/java/edu/ipcmax/core/pcmax/ProfileCellPartition.java:18-91`. |
| 13. Path consistency | `VERIFIED_REUSE` | Retain vertex-simple concatenation and stable-arc replay checks. Invoke them on every forward expansion. | `TemporalStitch.isVertexSimpleConcatenation`, `src/main/java/edu/ipcmax/core/pcmax/TemporalStitch.java:137-201`; `CandidateProfile.vertexSequence/internalVertices/isVertexSimple`, `src/main/java/edu/ipcmax/core/profile/CandidateProfile.java:86-139`; `CanonicalPathProfileBuilder.replay`, `src/main/java/edu/ipcmax/core/pcmax/CanonicalPathProfileBuilder.java:57-72`. |
| 14. Frontier compression and dominance | `EXTEND` | Retain exact deduplication, extension-safe dominance, deterministic bounded retention, and adjacent merge. Convert use from end-of-recursion compression to incremental layer/state frontiers; account for `K_f` and `M_b`. | `FrontierCompressor.compress`, `src/main/java/edu/ipcmax/core/pcmax/FrontierCompressor.java:32-123`; dedup/dominance/bounded retention, `:253-381`; `SafeProfileDominance.dominates`, `src/main/java/edu/ipcmax/core/profile/SafeProfileDominance.java:36-55`, `:57-121`. |
| 15. Memoization and single-flight | `EXTEND` | Retain `CompletableFuture` single-flight and defensive publication. Replace recursive `MemoKey` with corridor/pivot/layer/cap-aware keys and cache completion-bearing results, never partial unpublished sets. | `CandidateCache.getOrCompute`, `src/main/java/edu/ipcmax/core/cache/CandidateCache.java:14-55`; `MemoKey`, `src/main/java/edu/ipcmax/core/cache/MemoKey.java:13-60`, `:139-215`. |
| 16. PACE-X and PACE-B policies | `EXTEND` | Retain policy identity and separate exactness scope. Redefine options: X disables lossy limits for tiny-instance validation; B uses finite `L,K_c,K_f,M_c,M_b,M_q`. Do not certify either from policy name alone. | `PaceExecutionPolicy`, `src/main/java/edu/ipcmax/core/pcmax/PaceExecutionPolicy.java:6-11`; `PaceOptions`, `src/main/java/edu/ipcmax/core/pcmax/PaceOptions.java:15-50`, `:65-84`; `AlgorithmConfig.paceOptions`, `src/main/java/edu/ipcmax/experiments/framework/AlgorithmConfig.java:24-45`; adapter scope, `src/main/java/edu/ipcmax/experiments/algorithms/PaceExperimentAlgorithm.java:29-46`. |
| 17. `threadCount` and parallel work | `EXTEND` | Retain the bounded `ForkJoinPool` and input-order future reduction. Replace root-recursion-only tasking with canonical layered batches and deterministic cap reservation/reduction. | Root fan-out, `src/main/java/edu/ipcmax/core/pcmax/PaceFrontierGenerator.java:187-208`; `IPCMaxParallelExecutor.invokeAllDeterministic`, `src/main/java/edu/ipcmax/core/pcmax/IPCMaxParallelExecutor.java:18-38`. |
| 18. `PaceBench`/result instrumentation | `EXTEND` | Retain isolation, structured records, status/exactness separation, checksums, and quality comparison. Wire actual memory/timings, target cap status, work counters, corridor/anchor/connector/layer statistics. | Isolation, `src/main/java/edu/ipcmax/experiments/PaceBench.java:156-256`; measured-but-discarded memory, `:424-481`, `:562-569`; sparse PACE mapping, `src/main/java/edu/ipcmax/experiments/algorithms/PaceExperimentAlgorithm.java:29-46`; sparse stats, `src/main/java/edu/ipcmax/core/pcmax/PaceGenerationStats.java:4-16`. |
| 19. Schemas, summaries, tables, plots | `EXTEND` | Version the record schema, propagate all new fields, reject null required metrics on successful records, aggregate cap/completion/work measures, and update T/F artifacts. | Current configuration/status/counter schema, `experiments/schemas/result_record.schema.json:49-98`; normalization, `experiments/scripts/collect_results.py:29-75`; aggregation, `experiments/scripts/summarize_results.py:78-182`; tables, `experiments/tables/make_all_tables.py:74-110`; plots, `experiments/plots/make_all_plots.py:17-40`. |

## Target PACE design decisions

| Target component | Decision | Concrete implementation decision | Existing evidence / gap |
|---|---|---|---|
| Safe lower-bound query corridor | `CREATE` | Add `QueryCorridor` built from query-horizon `QueryLowerBounds`: retain directed arc `e=(u,v)` iff `d_s(u)+w_q(e)+d_t(v) <= B`; retain incident/reachable vertices and stable arc order. All PACE enumeration must consume this view. | Full-graph distances exist in `src/main/java/edu/ipcmax/core/pcmax/QueryLowerBounds.java:22-90`; production PACE creates only the lower-bound object and full `AnchorIndex`/`ConnectorProfiles` (`src/main/java/edu/ipcmax/core/pcmax/PaceFrontierGenerator.java:103-123`). Query-generation "corridor" only counts anchors (`src/main/java/edu/ipcmax/experiments/querygen/QueryBudgetBuilder.java:388-407`). |
| Selected top-`L` pivot anchors | `REPLACE` | Split discovery from selection: `PivotSelector.select(QueryCorridor, query, L)` runs once per query, returns a stable ranked list/set and version. Subproblems may filter the selected set but may not reselect it. | Current `AnchorIndex.create` makes all positive arcs anchors, while `relevantAnchors` truncates independently per subproblem (`src/main/java/edu/ipcmax/core/pcmax/AnchorIndex.java:37-58`, `:89-165`). |
| Non-selected score edges retained inside connectors | `REPLACE` | Connector graph excludes only selected pivot arc IDs. Every other corridor arc, including positive-score arcs, remains traversable and contributes score. | Current `anchorArcIds` contains all positive-score arcs (`src/main/java/edu/ipcmax/core/pcmax/AnchorIndex.java:23-31`, `:71-78`); both connector enumerators skip them (`src/main/java/edu/ipcmax/core/pcmax/ConnectorProfiles.java:80-210`). |
| Exact connector score profiles | `VERIFIED_REUSE` | Retain `CanonicalPathProfileBuilder.replay` for every completed connector; it evaluates each edge score at the actual entry profile. Rewire it to the selected-pivot index rather than the all-positive anchor index. | `CanonicalPathProfileBuilder.replay`, `src/main/java/edu/ipcmax/core/pcmax/CanonicalPathProfileBuilder.java:29-123`; score pullback/add at `:74-97`; `ConnectorProfiles.buildCandidate`, `src/main/java/edu/ipcmax/core/pcmax/ConnectorProfiles.java:213-236`. |
| Separate `K_c` connector and `K_f` frontier limits | `CREATE` | Replace `frontierLimit` with independently validated `connectorLimitKc` and `frontierLimitKf`; include both in CLI/config/schema/memo keys. `ConnectorProfiles` consumes only `K_c`; incremental compression consumes only `K_f`. | One shared field is declared and forced unbounded together (`src/main/java/edu/ipcmax/core/pcmax/PaceOptions.java:9-23`, `:37-47`); both connector and compressor consume it (`src/main/java/edu/ipcmax/core/pcmax/ConnectorProfiles.java:68-75`; `src/main/java/edu/ipcmax/core/pcmax/PaceFrontierGenerator.java:236-245`). |
| `M_c` connector-expansion cap | `CREATE` | Add a per-connector-request expansion budget and explicit observed count/status. Count priority-queue/DFS state expansions, not just completed paths. Exhaustion returns a typed cap result, never silent truncation. | Bounded loop has no expansion counter/cap (`src/main/java/edu/ipcmax/core/pcmax/ConnectorProfiles.java:143-210`); stats count only completed connector candidates (`src/main/java/edu/ipcmax/core/pcmax/PaceGenerationStats.java:4-12`). |
| `M_b` breakpoint cap | `CREATE` | Add a query-scoped breakpoint budget consumed by profile composition, cell partition, and merging; expose requested/observed values and typed exhaustion status. Fail closed until paper semantics say otherwise. | `ProfileCellPartition.cells` adds all cuts and pairwise equality roots without a cap (`src/main/java/edu/ipcmax/core/pcmax/ProfileCellPartition.java:18-91`); schema has no field (`experiments/schemas/result_record.schema.json:49-98`). |
| `M_q` total candidate-work cap | `CREATE` | Define one deterministic query work ledger covering connector state expansion, candidate construction, stitch attempts, and frontier insertions. Reserve work in canonical task order so the same prefix is admitted at every thread count. | No total work field exists in `PaceOptions` or `PaceGenerationStats` (`src/main/java/edu/ipcmax/core/pcmax/PaceOptions.java:15-23`; `src/main/java/edu/ipcmax/core/pcmax/PaceGenerationStats.java:4-16`). |
| Forward layered candidate expansion | `REPLACE` | Replace `generate(u,v,D,B,ell)` and left/right split enumeration with layer `0..theta` forward states. Expand prefix -> connector -> selected pivot, and prefix -> final connector, in canonical pivot/path order. | Current recursive implementation and split loop are at `src/main/java/edu/ipcmax/core/pcmax/PaceFrontierGenerator.java:137-297`. |
| Residual-budget pruning | `CREATE` | For prefix profile `A_P(t)` at vertex `v`, retain only root times satisfying `(A_P(t)-t)+d_t(v) <= B`; pass the residual profile/bound to connector enumeration and pivot feasibility checks. | Current connector calls always receive the original scalar `budget` (`src/main/java/edu/ipcmax/core/pcmax/PaceFrontierGenerator.java:175`, `:260-282`); no residual travel state exists in `src/main/java/edu/ipcmax/core/profile/CandidateProfile.java:15-22`. |
| Safe score upper bound | `CREATE` | Add `SafeScoreUpperBound` that conservatively includes every still-usable selected pivot and non-selected score arc in the remaining corridor. Use it only when it proves a candidate cannot beat the incumbent on a complete temporal cell. | No upper-bound implementation occurs in `src/main/java/edu/ipcmax/core`; current anchor ranking's `scorePotential` is ranking-only (`src/main/java/edu/ipcmax/core/pcmax/AnchorIndex.java:136-157`). |
| Incremental frontiers | `CREATE` | Maintain a frontier per canonical layer/state key; replay, deduplicate, safely dominate, and apply `K_f` as candidates arrive/batches reduce. Do not accumulate an entire recursive subproblem before compression. | Current `computeFrontier` accumulates connectors and all stitched candidates, canonicalizes everything, then compresses (`src/main/java/edu/ipcmax/core/pcmax/PaceFrontierGenerator.java:168-246`). |
| Explicit cap/status fields | `CREATE` | Add a completion-bearing `PaceGenerationResult` and schema-v3 fields: overall completion, exactness scope, triggered cap, requested/observed `K_c,K_f,M_c,M_b,M_q`, partial-output policy, and per-phase counters. | Current guard throws a generic PACE `LIMIT_EXCEEDED` (`src/main/java/edu/ipcmax/core/pcmax/PaceFrontierGenerator.java:230-235`); status schema has only generic codes (`experiments/schemas/result_record.schema.json:53-75`). |
| Deterministic parallel reduction | `EXTEND` | Reuse ordered futures, but generate indexed expansion descriptors, execute pure batches, and reduce results/cap reservations in descriptor order. Add observed-worker and checksum/counter equality across thread counts. | Input-order futures are real (`src/main/java/edu/ipcmax/core/pcmax/IPCMaxParallelExecutor.java:18-38`), but production parallelism is root-only (`src/main/java/edu/ipcmax/core/pcmax/PaceFrontierGenerator.java:187-208`) and current records only count tasks (`src/main/java/edu/ipcmax/experiments/algorithms/PaceExperimentAlgorithm.java:35-42`). |

## Reusable dependency chain

The target engine should reuse this production chain unchanged unless a focused
test reveals a defect:

```text
GeneratedGraphLoader / TDGraph
  -> PiecewiseLinearFn + PiecewiseConstFn
  -> QueryLowerBounds
  -> CanonicalPathProfileBuilder
  -> TemporalStitch
  -> CandidateProfile path/signature checks
  -> SafeProfileDominance
  -> ProfileCellPartition
  -> EnvelopeExtractor
```

Evidence for the final two steps is
`ProfileCellPartition.cells`
(`src/main/java/edu/ipcmax/core/pcmax/ProfileCellPartition.java:18-91`) and
`EnvelopeExtractor.extract/compareAt`
(`src/main/java/edu/ipcmax/core/pcmax/EnvelopeExtractor.java:18-58`).
`EnvelopeExtractor` is exact over the **retained** frontier; it does not make an
incomplete frontier globally exact.

## Components that must not be reused as target architecture

The following current semantics should be removed rather than wrapped:

1. `AnchorIndex.anchorArcIds()` as "all positive-score arcs"
   (`AnchorIndex.java:23-31`, `:71-78`).
2. Recursive subproblem-local top-`L` selection
   (`AnchorIndex.java:89-165`).
3. Connectors in `G \ all-positive-score-arcs`
   (`ConnectorProfiles.java:80-210`).
4. One `frontierLimit` for connectors and frontier cells
   (`PaceOptions.java:9-23`).
5. Left/right anchor-budget splitting
   (`PaceFrontierGenerator.java:248-297`).
6. End-of-subproblem rather than incremental compression
   (`PaceFrontierGenerator.java:222-245`).
7. Policy-only "exhaustive" configuration labels in result records. The current
   adapter correctly reports `RETAINED_FRONTIER`, and that separation must be
   preserved (`PaceExperimentAlgorithm.java:43-46`;
   `PaceBench.java:545-553`).

## External blockers

| Blocker | Classification | Impact |
|---|---|---|
| Current manuscript absent | `EXTERNAL_BLOCKER` | Cannot verify literal paper definitions, ranking formula, proofs, cap semantics, or claimed complexity. |
| `PACE_Q1_Experiment_Design_and_Codex_Runbook.md` absent | `EXTERNAL_BLOCKER` | Cannot verify its operational gates or intended config/result mapping. |
| Top-`L` ranking and tie keys not specified in the supplied target list | `EXTERNAL_BLOCKER` | Current `RelevantAnchor` comparator may be a starting point, but adopting it as paper semantics requires author confirmation (`AnchorIndex.java:136-163`). |
| Cap-exhaustion/partial-output semantics not specified | `EXTERNAL_BLOCKER` | The safe interim implementation is fail-closed with `NOT_CERTIFIED`; any partial-profile claim requires an explicit paper decision. |
| Exact definition of `M_q` "candidate work" not specified | `EXTERNAL_BLOCKER` | Must be frozen before results are comparable across versions/thread counts. |

These blockers do not prevent scaffolding or testing the corridor, selected
pivot set, separate options, forward layers, safe residual pruning, or result
plumbing. They do block claiming that the eventual implementation matches the
unavailable current paper.
