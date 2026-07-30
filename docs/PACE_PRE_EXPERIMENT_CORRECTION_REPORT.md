# PACE Pre-Experiment Correction Report

> **Historical diagnostic, superseded.** This report records the state before
> the witness-budget harmonization, total-work M_q v2 migration, expanded
> incremental differential corpus, and four-dataset stratified readiness
> mission. In particular, its USA budget blocker, 58,086-job total, 5,000,000
> M_q value, and launch decision are not statements about the current
> repository. See `docs/PACE_PAPER_READINESS_FINAL_REPORT.md`.

Date: 2026-07-29 UTC

Repository: `/home/koushik/Kousik/I-PC-Max`

Revision: `main` at
`c8caf96c477e951d9997fa6e45ab4c1ced3e1981` with an intentionally dirty
working tree containing the correction mission and pre-existing runner edits.

Corrected benchmark JAR:
`target/pace-bench.jar`,
SHA-256
`dd7bb0f06a89c1bd868a881060045b75c8f4228d8f9b27a6da93cfd0cf5b26c9`.

## Readiness decision

The two required normal PACE-B diagnostics now complete under the unchanged
1,800-second timeout:

| Query | Status | Query runtime | Caps | Output checksum |
|---|---|---:|---|---|
| `NY-EVAL-P001-C510-W120-RHO030` | `COMPLETED` | 267.903902928 s | none | `ea565d3976894d35a286ae956869ec3d09a9b1ae003f3d05e3c11939280075ff` |
| `NY-PILOT-P001-C1110-W120-RHO030` | `COMPLETED` | 1326.938274916 s | none | `d3d86f7b673258814c8ee46d825bbafbffc5aa285fecdaf74d0704bdb8273042` |

These are compression-enabled `PACE_B_BOUNDED` results with `L=4`, `theta=2`,
`K_c=8`, `K_f=8`, `M_c=5,000,000`, `M_b=1,000,000`, `M_q=5,000,000`,
24 requested threads, deterministic reduction, `-Xmx250g`, and no warm-up.
They were not replaced by the no-compression ablation. The previous normal
production evidence exceeded 1,800 seconds, while the old no-compression
evaluation took 204.126899262 seconds.

The corrected candidate engine is ready for further bounded validation, but
the complete paper matrix is **not authorized yet**. Its saved query inputs do
not share the mission's frozen budget contract:

- the authoritative mission requires
  `GRID_LOWER_BOUND_WITNESS_PATH_TRAVEL_TIME`;
- `experiments/configs/paper_q1_server_24c_250g.yaml:47` and the current NY,
  FLA, and CAL manifests declare
  `GRID_FIXED_DEPARTURE_FASTEST_TRAVEL_TIME`;
- the current USA manifest declares the witness-path definition, and therefore
  conflicts with the current server configuration.

Changing query preparation is outside this algorithm-only mission. The
researcher must select one paper-wide definition and regenerate or reconcile
the conflicting manifests before a full run can claim conformance.

## 1. Files changed

Production PACE and instrumentation:

- `src/main/java/edu/ipcmax/core/function/Domain.java`
- `src/main/java/edu/ipcmax/core/function/PiecewiseConstFn.java`
- `src/main/java/edu/ipcmax/core/graph/TinyGraphBuilder.java`
- `src/main/java/edu/ipcmax/core/pcmax/BoundedConnectorGenerator.java`
- `src/main/java/edu/ipcmax/core/pcmax/CanonicalPathProfileBuilder.java`
- `src/main/java/edu/ipcmax/core/pcmax/EnvelopeExtractor.java`
- `src/main/java/edu/ipcmax/core/pcmax/FeasibleEntryBand.java` (new)
- `src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java`
- `src/main/java/edu/ipcmax/core/pcmax/FrontierCompressor.java`
- `src/main/java/edu/ipcmax/core/pcmax/IncrementalFrontier.java`
- `src/main/java/edu/ipcmax/core/pcmax/PACE.java`
- `src/main/java/edu/ipcmax/core/pcmax/PaceExecutionMetrics.java` (new)
- `src/main/java/edu/ipcmax/core/pcmax/PaceOptions.java`
- `src/main/java/edu/ipcmax/core/pcmax/PaceWorkKind.java` (new)
- `src/main/java/edu/ipcmax/core/pcmax/PaceWorkLedger.java`
- `src/main/java/edu/ipcmax/core/pcmax/PaceWorkLimitReachedException.java`
  (new)
- `src/main/java/edu/ipcmax/core/pcmax/PivotIndex.java`
- `src/main/java/edu/ipcmax/core/pcmax/PivotSelector.java`
- `src/main/java/edu/ipcmax/core/pcmax/ProfileCellPartition.java`
- `src/main/java/edu/ipcmax/core/pcmax/TemporalStitch.java`
- `src/main/java/edu/ipcmax/core/profile/CandidateProfile.java`
- `src/main/java/edu/ipcmax/core/profile/CandidateSet.java`
- `src/main/java/edu/ipcmax/core/profile/SafeProfileDominance.java`
- `src/main/java/edu/ipcmax/core/profile/ScoreProfile.java`
- `src/main/java/edu/ipcmax/experiments/BenchOptions.java`
- `src/main/java/edu/ipcmax/experiments/PaceBench.java`
- `src/main/java/edu/ipcmax/experiments/algorithms/PaceExperimentAlgorithm.java`
- `src/main/java/edu/ipcmax/experiments/framework/ExperimentInstrumentation.java`
- `experiments/schemas/result_record.schema.json`

Tests:

- `src/test/java/edu/ipcmax/core/function/CanonicalTimeTickEquivalenceTest.java`
  (new)
- `src/test/java/edu/ipcmax/core/pcmax/FeasibleEntryBandAndSpatialPivotTest.java`
  (new)
- `src/test/java/edu/ipcmax/core/pcmax/IncrementalFrontierDifferentialTest.java`
  (new)
- `src/test/java/edu/ipcmax/core/pcmax/PaceExecutionMetricsTest.java`
  (new)
- `src/test/java/edu/ipcmax/core/pcmax/PacePublicApiOracleIntegrationTest.java`
- `src/test/java/edu/ipcmax/core/pcmax/ScalablePaceCandidateEngineTest.java`
- `src/test/java/edu/ipcmax/experiments/PaceBenchFrameworkTest.java`
- `src/test/java/edu/ipcmax/experiments/framework/ExperimentInstrumentationProgressTest.java`
  (new)

Documentation:

- this report;
- `docs/PACE_ALGORITHM_CONFORMANCE_AND_RUNTIME_REPORT.md`, marked historical
  because its pre-correction findings are now stale.

`experiments/scripts/background_run.py`,
`experiments/scripts/run_all.py`, and
`docs/PACE_TIMEOUT_BOTTLENECK_ANALYSIS.md` were already dirty before this
correction mission and were not rewritten as part of the algorithm fix.

## 2. Frozen algorithm contracts

The production call graph remains:

`PaceExperimentAlgorithm.run`
(`src/main/java/edu/ipcmax/experiments/algorithms/PaceExperimentAlgorithm.java`)
to `PACE.run` (`src/main/java/edu/ipcmax/core/pcmax/PACE.java`) to
`ForwardLayeredFrontierGenerator.generate`
(`src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:70`)
to `EnvelopeExtractor.extract`
(`src/main/java/edu/ipcmax/core/pcmax/EnvelopeExtractor.java:22`).

No hierarchy, parent/child closure, transitive closure, or summary-cover
selection was introduced. Exact induced-time composition, looplessness, stable
parallel-arc IDs, deterministic ordering, final lexicographic selection,
separate PACE-X/PACE-B policies, `K_f` frontiers, and explicit caps/statuses are
preserved.

`L` and `theta` remain independent. `PivotSelector` first keeps up to `L`
(`PivotSelector.java:230`); expansion depth is then
`min(theta, selected pivots)` (`ForwardLayeredFrontierGenerator.java:173-174`).
It does not compute selected pivots as `min(L, theta)`.

When a cap is reached, `ForwardLayeredFrontierGenerator.completion` returns
`RESOURCE_TRUNCATED` for PACE-B and `EXACTNESS_NOT_CERTIFIED` for PACE-X
(`ForwardLayeredFrontierGenerator.java:709-715`). The last valid retained
frontier is returned; completion is not silently claimed.

The one nonconforming frozen contract is the prepared-query budget definition
described in the readiness decision. The algorithm implementation does not
redefine a supplied query budget, so this cannot be corrected inside the
candidate engine.

## 3. Timeout-safe instrumentation

`PaceExecutionMetrics` defines all 14 requested phases at
`PaceExecutionMetrics.java:25-38`. It uses thread-safe phase clocks and counters,
publishes every phase transition immediately, and runs a one-second heartbeat
(`:67-79`, `:97-113`, `:187-205`). Concurrent entries are recorded as
wall-clock union, not a sum inflated by 24 workers (`:97-102`).

`ExperimentInstrumentation.accept` writes cumulative snapshots
(`ExperimentInstrumentation.java:60-69`) through temporary-file plus atomic
replace (`:104-137`). If the parent terminates a worker,
`PaceBench.forcedExecution` recovers that snapshot and uses its elapsed time,
phase, timings, and counters (`PaceBench.java:446-464`).

The result schema and `PaceBench` expose candidate offers, connector requests
and expansions, breakpoints, equality roots, affected-cell work, dominance,
retention, fragments, typed M_q work, cache behavior, and observed parallelism.
Successful records now serialize `error.failing_phase=null`; failed/terminated
records retain the last recovered live phase.
For a zero-occurrence counter the JSON serializer retains the repository's
existing nullable-counter convention; for example, zero equality roots or zero
new/merged temporal cells serialize as `null`.

## 4. Feasible-entry-band tests

`FeasibleEntryBand.compute` implements

`[departureStart + d(s,x), departureEnd + B - lb(e) - d(y,t)]`

intersected with the graph horizon
(`FeasibleEntryBand.java:22-77`). Ordinary bands are left-closed/right-open;
only the terminal horizon endpoint can be right-closed
(`FeasibleEntryBand.java:67-77`).

`FeasibleEntryBandAndSpatialPivotTest` covers:

- empty band;
- ordinary endpoint-only contact;
- positive-width interior band;
- equality at the terminal horizon boundary;
- horizon clipping;
- positive support touching only an open band end;
- parallel arcs retaining distinct IDs;
- repeat-selection serialization stability.

All cases passed.

## 5. Coordinate spatial-diversity tests

`PivotSelector.Grid.forCorridor` uses graph coordinates and
`g=max(1,ceil(sqrt(L)))` (`PivotSelector.java:325`), clamps boundary coordinates
to `g-1` (`:381`), and assigns deterministic row-major IDs. Ranking is score,
positive temporal coverage, slack, then arc ID; selection is deterministic
round-robin across nonempty cells (`:167-226`).

Tests verify selection from separated coordinate cells, maximum-boundary
clamping to `GRID-R00001-C00001`, and NY-scale integer coordinates that are far
outside the temporal tick range. Parallel arcs and repeated selection are also
checked. All passed.

## 6. Incremental-versus-batch comparisons

`IncrementalFrontier.insert` computes cuts only against overlapping retained
candidates (`IncrementalFrontier.java:173-226`), reevaluates only affected
cells (`:367-396`), leaves nonoverlapping `CellState` objects untouched
(`:190-198`), and rebuilds the global retained-domain index from already
decided cells without pairwise full-frontier recompression (`:247-310`).
`FrontierCompressor.compress` remains the batch oracle and the non-production
ablation path (`:140-170`).

The differential test performs 6 candidate-order permutations with 4
post-insertion comparisons each: **24 comparisons, 0 mismatches**. Every
comparison checks canonical temporal cells, retained path IDs by cell,
normalized envelope, SHA-256 semantic checksum, and uncapped/complete status.
The same test separately verifies explicit typed-M_q truncation and preservation
of the last valid frontier.

## 7. Tick-versus-BigDecimal comparisons

`Domain.canonicalTick` represents time as signed 10^-12-minute ticks with
12-decimal `HALF_EVEN` rounding (`Domain.java:517-544`). Raw-double-to-tick and
tick-to-double caches ensure the decimal conversion cost is paid only for a
new value (`:522-561`). Domains, constant functions, score profiles, and cell
partitions deduplicate with sorted `TreeSet<Long>` collections rather than
linear scans.

The equivalence test compares the new path against the legacy
`BigDecimal.valueOf(...).setScale(12, HALF_EVEN)` result for 18 directed edge
cases plus 100,000 fixed-seed randomized inputs: **100,018 comparisons, 0
bit-level mismatches and 0 tick-idempotence mismatches**. Cases include exact
decimals, half-way rounding, signed zero, near-equal values, and 0/10080
endpoints.

## 8. PACE-X comparisons

The exact continuous oracle suite completed:

- 9 deterministic full-envelope fixtures;
- 1,000 fixed-seed FIFO DAGs with parallel arcs against the independent
  continuous oracle;
- 64 compressed-versus-uncompressed PACE-X corpus cases.

Total: **1,073 PACE-X envelope comparisons, 0 mismatches**. The public API test
also checks both PACE-X and PACE-B at nine boundary/interior probes and requires
byte-identical envelopes between the two policies on the switching fixture.

## 9. Deterministic checksums by thread count

`PacePublicApiOracleIntegrationTest.repeatedPublicOutputIsByteStableForAllDeclaredThreadCountsInBothPolicies`
ran both PACE-X and PACE-B at each requested thread count. Results:

| Threads | PACE-X | PACE-B |
|---:|---|---|
| 1 | identical | identical |
| 2 | identical | identical |
| 4 | identical | identical |
| 8 | identical | identical |
| 24 | identical | identical |

There were **10 cross-thread semantic-checksum comparisons and 0
mismatches**, plus repeated one-thread byte-identity checks for both policies.
The separate parallel test requires `parallelTasksStarted > 0` and
byte-identical serial/parallel output
(`PacePublicApiOracleIntegrationTest.java:161-173`).

The two large diagnostics requested 24 workers and observed 4 because only four
pivots were selected. They started 14 and 17 deterministic parallel task
batches respectively; this is real query-internal parallelism, not concurrent
queries.

## 10. Diagnostic-query runtime and output

| Metric | Evaluation C510 | Pilot C1110 |
|---|---:|---:|
| preprocessing (excluded) | 16.370676632 s | 16.871557702 s |
| query wall time | 267.903902928 s | 1326.938274916 s |
| process CPU | 301.240 s | 1501.390 s |
| generation completion | `COMPLETE` | `COMPLETE` |
| cap status | none | none |
| candidates generated | 257 | 367 |
| retained candidates | 165 | 238 |
| final profile intervals | 17 | 27 |
| distinct selected paths | 5 | 10 |
| path switches | 16 | 26 |
| profile checksum | `d917a6356d7e9ff7e23d2954d57931adbe17c4402178b2b16350655bdfbfb0ea` | `635e41a0fb1906f4b5b98b519ea9958b385e8afe2002b23f776100e96e3905ed` |

Evidence:

- `experiments/results/diagnostics/pace_incremental_20260729/eval_c510_normal_final5.jsonl`
- `experiments/results/diagnostics/pace_incremental_20260729/pilot_c1110_normal_final3.jsonl`

## 11. Per-phase timings and work counters

Phase timings are cumulative wall-clock union within each named phase. Some
phases are nested (for example dominance inside retention), so phase rows must
not be summed to reconstruct query wall time.

| Phase | Evaluation C510 (s) | Pilot C1110 (s) |
|---|---:|---:|
| horizon validation | 0.006260 | 0.034453 |
| corridor construction | 0.083048 | 0.421628 |
| feasible-entry bands | 0.010192 | 0.078800 |
| score-support lookup | 0.020601 | 0.063005 |
| pivot ranking/diversification | 0.012544 | 0.037186 |
| connector generation | 1.412009 | 20.298592 |
| canonical replay/stitching | 4.469426 | 61.313575 |
| breakpoint processing | 0.050355 | 0.112789 |
| equality-root computation | 0.252500 | 0.998369 |
| safe dominance | 55.131632 | 207.067361 |
| `K_f` bounded retention | 83.991785 | 498.169609 |
| fragment restriction/merge | 3.585390 | 30.932328 |
| statistics | 0.000414 | 0.000591 |
| envelope extraction | 0.047671 | 0.212758 |

The main remaining measured cost is `K_f` retention, with dominance nested
inside it. On the pilot these account for 498.169609 s and 207.067361 s
respectively. Connector generation is no longer the bottleneck.

The reason is temporal fragmentation, not path search. The pilot corridor has
9,311 edges and 1,850 score-relevant edges. Only 367 completed candidates are
generated, but their exact temporal profiles cause 553,783 processed
breakpoints, 800,149 affected-cell evaluations, 1,600,562 retention
evaluations, and 12,768,034 retained-fragment operations. The structural
dominance prefilter rejects 113,915,557 impossible pairs before exact temporal
comparison; 719,131 exact comparisons remain. All 506 memo lookups are unique
(506 misses, zero hits), so this query offers no repeated identical
candidate/cell key for the memo to amortize. This explains both the phase
timings and why raising connector parallelism would not remove the remaining
bottleneck.

| Counter | Evaluation C510 | Pilot C1110 |
|---|---:|---:|
| corridor nodes / edges / cells | 532 / 1,178 / 4 | 3,867 / 9,311 / 9 |
| score-relevant edges / selected pivots | 228 / 4 | 1,850 / 4 |
| feasible-entry bands | 1,178 | 9,311 |
| connector requests / expansions | 51 / 28,868 | 88 / 64,642 |
| valid / invalid connectors | 285 / 30 | 418 / 51 |
| candidate offers | 258 | 368 |
| breakpoints processed | 153,540 | 553,783 |
| candidate-pair root checks / roots created | 3,690 / 0 | 7,887 / 1 |
| affected-cell evaluations | 217,460 | 800,149 |
| frontier-retention evaluations | 435,156 | 1,600,562 |
| temporal cells split | 3,408 | 10,283 |
| current frontier cells / peak frontier | 2,639 / 35 | 8,270 / 44 |
| dominance comparisons | 787,233 | 719,131 |
| structural dominance rejections | 30,313,499 | 113,915,557 |
| retained / dropped fragments | 3,467,082 / 430,222 | 12,768,034 / 1,587,084 |
| fragments coalesced by retained-domain rebuild | 2,260,766 | 12,825,268 |
| cache lookups / hits / misses / waits | 338 / 0 / 338 / 0 | 506 / 0 / 506 / 0 |
| requested / observed workers | 24 / 4 | 24 / 4 |
| deterministic parallel tasks started | 14 | 17 |
| typed M_q total | 1,008,692 | 1,527,623 |
| M_q connector requests | 51 | 88 |
| M_q candidate offers | 258 | 368 |
| M_q affected-cell evaluations | 217,460 | 800,149 |
| M_q dominance checks | 787,233 | 719,131 |
| M_q equality-root checks | 3,690 | 7,887 |

No newly uncovered-cell creation, adjacent cell-state merge, or evaluation
equality-root event was serialized; these zero-occurrence counters use the
nullable-zero representation. `fragments_merged` is the measured
profile-fragment coalescing count.

## 12. Peak memory and allocation evidence

| Metric | Evaluation C510 | Pilot C1110 |
|---|---:|---:|
| peak RSS | 10.227 GiB | 21.675 GiB |
| peak Java heap | 8.445 GiB | 19.128 GiB |
| configured total heap limit | 250 GiB | 250 GiB |

Neither query approached the heap limit. No JFR/allocation recorder was enabled
for the final corrected JAR, so a corrected-build allocation rate is
**unavailable** and is not inferred from the historical pre-correction JFRs.

## 13. Pilot runtime projection

The diagnostic pilot query is also the requested small representative NY pilot.
Using the arithmetic mean of the two corrected normal PACE-B samples gives
797.421088922 seconds per job. The latest four-dataset, 24-core plan contains
58,086 jobs, of which 51,240 are PACE-B:

- all jobs at that sample mean, one query at a time: about 536.1 days
  (1.47 years);
- PACE-B jobs alone at that mean: about 472.9 days (1.30 years);
- a simple two-sample range for all jobs is 180.1 to 892.1 days.

This is a warning-scale projection, not a statistically defensible runtime
model: it uses only two NY, distance-band-1, normal PACE-B queries, while the
matrix includes other datasets, policies, ablations, depths, densities, and
thread counts. It is sufficient to conclude that a serial full launch should
not be authorized from this pilot alone. More stratified bounded pilots or a
reduced study matrix are needed.

## 14. Maven and Python validation

- `mvn -q test`: **PASS**, 189 tests, 0 failures/errors/skips.
- `python3 -m unittest discover -s experiments/tests -p 'test_*.py'`:
  **PASS**, 20 tests in 16.896 s.

The Maven suite includes the 1,000-case exactness corpus, 100,018 tick
comparisons, 24 incremental/batch insertion comparisons, spatial/endpoint
fixtures, cap/status tests, timeout-snapshot recovery, and cross-thread
determinism.

## 15. Strict preflight

Command:

`python3 experiments/scripts/preflight.py --config experiments/configs/paper_q1_server_24c_250g.yaml --output experiments/results/diagnostics/pace_incremental_20260729/strict_preflight.json`

No `--skip-checksums` or `--allow-unresolved-resources` flag was used.

Result: **FAIL, one blocker**. Checksums were computed. Deep dataset integrity
passed for NY, FLA, CAL, and USA: all directed arcs have FIFO arrival
functions, positive lower-bound travel times, support through 10080 minutes,
and the declared conversion contract. All implementation gates passed.

The sole blocker is:

`USA queries: 1860 queries have the wrong budget definition`

NY, FLA, and CAL query validation passed with exact pilot/warm-up/evaluation
pair counts. USA also has the correct 20/10/100 pair counts, but all 1,860 rows
declare `GRID_LOWER_BOUND_WITNESS_PATH_TRAVEL_TIME` while the current server
configuration expects `GRID_FIXED_DEPARTURE_FASTEST_TRAVEL_TIME`.

Evidence:
`experiments/results/diagnostics/pace_incremental_20260729/strict_preflight.json`.

## 16. Remaining blockers

1. **Paper budget contract conflict.** The mission freezes witness-path-grid
   budgets, while current server configuration and NY/FLA/CAL query manifests
   use fixed-departure fastest-path-grid budgets; USA uses witness-path
   budgets. A single paper-wide definition and matching regenerated manifests
   are required.
2. **Full-matrix runtime.** The two-query serial projection is measured in
   hundreds of days. A representative stratified pilot and/or matrix reduction
   is required before authorizing the complete experiment.
3. **Remaining performance concentration.** Bounded retention and dominance
   remain the dominant phases on the pilot. They no longer time out on the two
   mandated queries, but should be the target of any next optimization.
4. **Allocation rate.** Not a launch blocker, but unavailable for the corrected
   build because the mandated final diagnostics did not enable JFR.
5. **Strict-preflight result.** Dataset integrity and implementation gates pass,
   but the one USA budget-definition blocker above stops the full four-dataset
   run.

The production algorithm now matches the attached high-level PACE candidate
engine and its algorithmic contracts. The prepared experimental input does
**not** yet match the frozen budget-definition contract, so the repository as a
whole cannot be called fully paper-conformant.

## 17. Full-experiment launch confirmation

The complete matrix was **not launched or resumed during this correction
mission**. Historical partial-run artifacts remain in `experiments/results`,
but no matrix worker was started here. Only the two named NY normal PACE-B
diagnostics, the Java/Python validation suites, and strict preflight were run.
