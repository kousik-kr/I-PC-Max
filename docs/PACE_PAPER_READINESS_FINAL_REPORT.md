# PACE Paper-Readiness Final Report

Date: 2026-07-29 UTC

Repository: `/home/koushik/Kousik/I-PC-Max`

This report supersedes the launch decisions and unresolved-budget statements
in the earlier timeout and pre-experiment diagnostic reports. Those reports
remain historical evidence.

## Scope and launch state

This mission performs source correction, query/index preparation, tests,
preflight, exact small-instance validation, and a bounded stratified pilot.
It does **not** submit, launch, or resume the 60,486-job paper matrix.

Final checkpoint identifiers and the final authorization decision are recorded
in the concluding sections after every required gate has completed.

## 1. Paper-wide budget contract

The only active paper budget definition is:

`GRID_LOWER_BOUND_WITNESS_PATH_TRAVEL_TIME`

This is deliberately not described as an exact fastest-path budget.
`PaperQuerySetGenerator.GridWitnessBudgetStore` selects the deterministic
lower-bound-routing witness already produced by `QueryCandidateSampler`,
replays its actual time-dependent travel time on the one-minute departure
grid, sets `T_hat_min,Delta` to the maximum replayed witness travel time, and
computes:

`B = canonical_time((1 + rho) * T_hat_min,Delta)`.

The v3 row schema requires the lower-bound routing contract, path checksum,
grid interval, travel-time evidence checksum, final budget, dataset/temporal
checksums, and the derivation rule. Python validation rejects any row whose
contract or evidence is absent, whose sidecar checksum differs, or for which
`interval_end + B > function_support_end`.

USA exposed a genuine pre-selection defect during this mission: a
long-distance witness produced `B=10810.896878077687` for a query ending at
minute 570. The generator correctly failed instead of wrapping or
extrapolating. The corrected production path applies a conservative
witness-path temporal upper-bound filter before deterministic distance
stratification. The bound sums each witness edge's maximum value from the
existing canonical temporal function and applies the maximum configured rho;
therefore it cannot admit a witness that is unsafe for any configured cell.
The disk-fixture test
`PaperQuerySetGeneratorTest.filtersHorizonUnsafeWitnessesBeforeDistanceStratification`
injects unsafe FIFO witnesses, verifies their rejection, and checks every
emitted row.

Continental preparation also exposed an independent memory issue: running 16
dense Dijkstra priority queues simultaneously approached the 250-GiB heap.
`QueryCandidateSampler.lowerBoundWorkerCount` now caps USA preparation at two
simultaneous source searches and million-node state graphs at four, with
submission-order reduction. This changes resource use only; sampled sources,
witnesses, pair IDs, budgets, and manifest bytes remain deterministic. It does
not reduce the PACE query engine's 24-thread maximum.

## 2. Reproducibility evidence reconciliation

### Checksum scopes

The two historical NY values were both valid but measured different
representations:

- `runtime_graph_semantic_checksum =
  7ca31c5af262748f119e5e030f95cdc7b8def244826d374bde9e11693db0e129`
  hashes the canonical in-memory directed arcs and decoded temporal functions.
- `dataset_payload_checksum =
  fbaaaaaa189604fffa8641d3d1810dce7fa556817bcec07ab082b669e6862c7b`
  is `PACE-GRAPH-CHECKSUM-v1` over the five canonical on-disk dataset files.

The result schema and `PaceBench.datasetRecord` now name those fields
separately and also report `dataset_structure_checksum`,
`temporal_attribute_checksum`, and
`checksum_scope_version=pace-explicit-dataset-checksum-scopes-v1`.
`preflight.py` recursively translates the legacy asset-validator key
`graph_checksum` into the explicit payload scope, and generated tables use the
new name.

### Configuration-hash scopes

`run.config_hash` covers the complete effective execution configuration,
including operational values and paths. `run.scientific_config_hash` covers
algorithm and determinism parameters. Thus two diagnostics may have different
complete hashes while sharing the same scientific hash; that is expected and
is now explicit through `config_hash_scope` and
`scientific_config_hash_scope`.

### Matrix totals

The canonical current total is **60,486** jobs:

| Study | Jobs |
|---|---:|
| E01 | 6 |
| E02 | 1,440 |
| E03 | 7,440 |
| E05 | 6,000 |
| E06 | 6,000 |
| E07 | 4,800 |
| E08 | 2,400 |
| E09 | 2,400 |
| E10 | 12,000 |
| E11 | 14,400 |
| E12 | 3,600 |

E00, E04, and E13 are non-execution stages. The historical totals reconcile
as follows:

- 53,286: three-dataset plan, before USA; E03=5,640 and E11=9,000.
- 55,486: older study axes, notably E02=640, E03=8,400, E09=2,040,
  E10=7,200, and E11=14,400.
- 58,086: the current axes with E11's 24-thread point accidentally omitted;
  five thread values produced 12,000 E11 jobs.
- 60,486: corrected E11 list `[1,2,4,8,16,24]`, producing 14,400 jobs.

`build_matrices.py` writes every canonical semantic job key, not only counts,
to `canonical_job_ledger.jsonl`, sorts it by `job_key`, rejects duplicate job
IDs, and independently validates expected cells.

CAL's 2,160 combined rows are 1,800 base evaluation rows, 300 seed-variant
rows, 40 pilot rows, and 20 warm-up rows. The historical 1,860 count omitted
the 300 seed-variant rows.

Required matrix query IDs and total manifest rows differ intentionally:

| Dataset | Matrix-required IDs | Combined rows | Reason for additional rows |
|---|---:|---:|---|
| NY | 2,940 | 2,960 | warm-up rows are not matrix jobs |
| FLA | 200 | 1,860 | study matrix uses a subset; manifest preserves all derived cells |
| CAL | 2,100 | 2,160 | warm-up and additional prepared variant rows |
| USA | 200 | 1,860 | study matrix uses a subset; manifest preserves all derived cells |

## 3. Incremental-frontier correctness and M_q

`IncrementalFrontierDifferentialTest.deterministicRandomizedCorpusMatchesAfterEveryInsertion`
uses 32 deterministic seeds, `K_f` values 1/2/3/4, three candidate-order
permutations, and insertion-prefix comparison. It covers overlapping and
disjoint domains, coincident breakpoints, equality roots, terminal ownership,
parallel arcs, cap activation, cell splitting/merging, and
dominance/non-dominance. After every insertion it compares canonical cells,
retained IDs per cell, normalized envelope, completion label, and semantic
checksum.

The current evidence artifact is
`target/pace-incremental-differential-corpus.json`; the strict preflight
requires at least 2,000 comparisons, zero mismatches, and the corresponding
Surefire method result.

`PACE-MQ-TOTAL-WORK-v2` versions the migrated total-work contract.
`PaceWorkKind` includes connector requests, candidate offers, affected-cell
evaluations, retention evaluations, fragment restrictions, fragment
materializations, dominance checks, and equality-root checks. The result
schema records the contract and typed counters.

Temporal retention now:

- buckets dominance candidates by structural/cardinality/arrival signatures;
- stores `RetainedCellReference` objects rather than eagerly restricted
  fragments;
- caches lineage/domain restrictions and materializes only when rebuilding or
  serializing;
- accepts deterministic same-layer cohorts through `insertLayer`;
- evaluates provably independent cells in parallel only when the remaining
  M_q budget can cover the conservative batch bound, then reduces in cell
  order;
- reuses unchanged cell states and representative keys.

These changes preserve temporal exactness, looplessness, deterministic
selection, `K_f`, and cap/status semantics.

## 4. Quality evidence

Large-network diagnostic rows with
`reference_available=false` or `output_verified=false` are scalability
evidence only and are not used to claim quality.

`experiments/scripts/run_exact_quality_suite.py` runs four bounded PACE-B
configurations (`L/K_c/K_f` of 2/2/1, 4/4/2, 8/8/4, and 16/16/8) against
continuous PACE-X on the three-query tiny manifest. It requires every result
to complete, have an exact reference, and pass output verification. The
summary reports feasibility/path/score agreement, breakpoint
precision/recall, integrated score regret, relative score gap, and missed
path switches.

Final exact-suite measurements are recorded below after the clean checkpoint
run.

## 5. Stratified bounded pilot

`experiments/scripts/run_stratified_paper_pilot.py` selects one evaluation
query from every dataset/distance-band stratum: 20 queries total. Collectively
the rows cover 120-, 300-, and 360-minute windows; rho 0.1, 0.3, and 0.5; all
four datasets; NY base and 40%-density payloads; and increasing representative
`theta/L/K_c/K_f` parameter sets.

Only one query executes at a time. Queries sharing the same dataset payload
run serially inside one JVM to avoid measuring repeated graph loading as a
short query phase. The result's measured preprocessing time is added back to
every stratum when projecting the repository's process-isolated job policy.
Every process has one total `-Xmx250g` heap and every query may use at most 24
internal threads. Safe process concurrency is the minimum of the explicit
one-process policy, physical-core capacity, and 85%-memory capacity derived
from measured peak RSS.

The pilot reports median, p95, and maximum process-isolated runtime; query-only
runtime; peak RSS; cap/completion rates; status counts; exact-reference
availability; and a dataset/distance-band ledger projection. Non-PACE-B jobs
use the configured timeout as a conservative upper bound instead of borrowing
PACE-B timing.

Final pilot measurements and the operational decision are recorded below.

## 6. Final evidence and decision

This section is finalized only after:

1. all four manifests have been regenerated twice byte-for-byte;
2. full Java and Python test suites pass;
3. exact quality and stratified pilot suites complete;
4. strict four-dataset query validation and preflight pass without skip flags;
5. a clean Git checkpoint and rebuilt JAR are recorded.

The full experiment matrix has not been launched.
