# PACE Paper-Readiness Final Report

Date: 2026-07-30 UTC

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

### Reproducible checkpoint

The tested production-code checkpoint is:

- branch: `main`;
- source commit:
  `f0ece60506c81b0b96126fb8bcbedcfc311d6502`
  (`Stream graph semantic checksum`);
- parent implementation commit:
  `072ee652ef279313e2757cdd28de4e883a70a288`
  (`Complete PACE paper-readiness implementation`);
- JAR: `target/pace-bench.jar`;
- JAR SHA-256:
  `1e03d6fe2dc6bc998479994727ca38cdd88af56f64d757f5400a936d97f1d8bb`;
- paper configuration hash:
  `13d183182d5db717950b167a00e802ed69b718aac2af3a8f06a1494290018821`;
- canonical job-ledger SHA-256:
  `484e71ab90b9a01790450b3d88d221ef5fbf79a81b94e85b9fffee1a4dcfcea3`.

The four combined query-manifest SHA-256 values are:

| Dataset | Combined rows | SHA-256 |
|---|---:|---|
| NY | 2,960 | `859157add5cf6fcbcbdf2c488be6d4421cbe8644630db1d22e75e41ac1686a43` |
| FLA | 1,860 | `a3fe248f2005659a1f3465a9d4f4b6ba8488f25f64212cea0fb4ba7f3e958f6c` |
| CAL | 2,160 | `97d7481b124b36d7347c9880bb8f4fb0b20374c3cb25d83627323f661db1a045` |
| USA | 1,860 | `19a9873ad3358d572f64af728f7168151ca60a80ddc19ea10ab66527bd28dcaa` |

The canonical dataset-payload checksums are:

| Dataset | Vertices | Directed arcs | Dataset-payload SHA-256 |
|---|---:|---:|---|
| NY | 264,346 | 733,846 | `fbaaaaaa189604fffa8641d3d1810dce7fa556817bcec07ab082b669e6862c7b` |
| FLA | 1,070,376 | 2,712,798 | `22cd3c8caa79aacdfe27ecc9d3e0b9985802b8f1a9d8d4a082b26958c8dd3c4e` |
| CAL | 1,890,815 | 4,657,742 | `20d5e5371e6a56f5a2c07a45ffa4aa3feba2b1dfa2bad07163404b5e12aaad87` |
| USA | 23,947,347 | 58,333,344 | `c7a0061fdf6e484191c9d9076d2be9364158b4e98bc2551ca9988ed3b8015f91` |

All four use `declared_centisecond_normalization-v1`, score density
0.20 for the base payload, and temporal support through minute 10,080.
The strict asset gate also validates NY densities 0.05/0.10/0.20/0.40 and
the configured seed-42/43/44 variants.

The final required commands completed as follows:

| Command | Result |
|---|---|
| `mvn -q test` | PASS; incremental differential corpus: 3,051 comparisons, 4 cap activations, 0 mismatches |
| `python3 -m unittest discover -s experiments/tests -p 'test_*.py'` | PASS; 20 tests |
| `python3 experiments/scripts/generate_query_sets.py --config experiments/configs/paper_q1_server_24c_250g.yaml --validate-only` | PASS; NY, FLA, CAL, and USA independently regenerated with `deterministic_match=true` |
| `python3 experiments/scripts/preflight.py --config experiments/configs/paper_q1_server_24c_250g.yaml --output experiments/results/diagnostics/pace_final_preflight.json` | PASS; `passed=true`, `blockers=[]`; no skip flags |

The generator had already produced each final manifest twice with
byte-identical output before the validate-only run. USA has 1,695
horizon-safe derived rows; 305 otherwise-valid derived cells are explicitly
rejected rather than wrapped or extrapolated.

### Exact small-instance quality

The clean-checkpoint exact suite produced 12/12 completed PACE-B records.
Every record had `reference_available=true` and `output_verified=true`
against continuous PACE-X. Across all four `K_f` configurations:

- feasibility disagreement was 0;
- path and score agreement were 1.0;
- breakpoint precision and recall were 1.0;
- integrated score regret and relative score gap were 0;
- missed path switches were 0.

This establishes correctness evidence only for the exact manageable subset.
It does not turn the large-network rows, whose exact references are absent,
into quality evidence.

### Stratified pilot result

The bounded pilot selected all 20 requested input strata: four datasets,
five distance bands, short and long windows, rho 0.1/0.3/0.5, base
20%-score density and NY 40%-score density, and representative increasing
`theta/L/K_c/K_f` settings. It executed one query at a time, used one
`-Xmx250g` heap, allowed at most 24 internal workers, and retained `M_c`,
`M_b`, and `M_q`.

Only 3 of 20 selected queries produced completed records. The initial pilot
summary contains:

- statuses: 3 `COMPLETED`, 12 `EXTERNAL_TIMEOUT`, 5 USA `NO_RECORD`;
- completion rate: 0.15;
- cap rate: 0 among the three records;
- query-only median/p95/maximum: 300.0/702.174/2,091.360 seconds, with the
  timeout allowance included for missing records;
- process-isolated median/p95/maximum:
  2,100.0/2,100.0/2,106.932 seconds;
- recorded peak RSS: 43,934,203,904 bytes among the three formal records;
- safe process concurrency: 1.

The initial USA `NO_RECORD` condition was caused by instrumentation, not by
the PACE candidate engine: the former `PaceBench.graphSemanticChecksum`
accumulated all 58,333,344 canonical arc records in one `StringBuilder` and
failed at Java's maximum array length
(`Required array length 2147483643 + 7 is too large`). The replacement at
`PaceBench.java:1081-1101` streams the identical UTF-8 arc records through
SHA-256 one edge at a time. The regression test
`PaceBenchFrameworkTest.streamingGraphSemanticChecksumMatchesLegacyCanonicalRepresentation`
checks byte-compatible output against the former canonical representation.

The fixed JAR was then run on the same five-query USA group with the same
parameters, one process, `-Xmx250g`, 24 requested workers, and a 3,360-second
outer watchdog. It no longer encountered the array-limit failure and did
enter the first production query, but emitted no completed row before the
watchdog. TERM followed by the five-second kill grace produced exit 137.
Observed evidence from the non-terminating JVM thread dump was:

- total elapsed at the sample: 1,927.60 seconds;
- first-query worker elapsed: 650.36 seconds, hence approximately 1,277
  seconds before query execution;
- heap used: 129,154,651 KiB; observed process RSS later peaked at
  approximately 169,796,592 KiB (about 162 GiB);
- the active stack was
  `ForwardLayeredFrontierGenerator.reduceFinal` (`:463-485`) ->
  `replay` (`:565-602`) -> `replayPath` (`:605-624`) ->
  `CanonicalPathProfileBuilder.replay` (`:129`) ->
  `TimeProfile.compose/preimage/segments/addCut`;
- all 24 `pace-worker-1-*` threads were parked on the worker queue while the
  single `pace-query-worker` performed that final replay.

Thus USA's remaining failure is no longer checksum instrumentation. It is
large setup cost followed by effectively serial canonical path
replay/profile composition during final reduction, with high retained
memory. `--threads 24` creates real parallel work earlier, but it does not
parallelize this measured tail.

The three completed large-network rows show the same family of bottlenecks:

| Query | Preprocess (s) | Query (s) | Fragment restriction/merge (s) | Canonical replay/stitching (s) | Requested/observed workers |
|---|---:|---:|---:|---:|---:|
| NY band 1 | 15.572 | 2,091.360 | 2,043.443 | 27.873 | 24/4 |
| NY band 2 | 15.572 | 702.174 | 477.286 | 175.450 | 24/4 |
| FLA band 1 | 51.973 | 108.655 | 42.979 | 46.966 | 24/8 |

Phase timers may overlap, so their values are not summed. The evidence
identifies two related scaling targets: fragment restriction/merge on NY,
and canonical path replay/profile composition on FLA and especially USA.
Retention and safe-dominance time are no longer the leading measured phases
in these completed records.

The canonical-ledger pilot projection was already
116,686,803.641 seconds (about 1,350.5 days) at concurrency one and was marked
`operationally_acceptable=false`. That summary used a 2,100-second fallback
for missing USA strata. Replacing only the 1,080 USA distance-band-1 jobs
with the fixed run's greater-than-3,360-second bound increases the projection
lower bound to greater than 118,047,603.641 seconds (about 1,366.3 days).
No extrapolation from the unmeasured USA bands is needed for the decision.

### Authorization decision

**DO NOT AUTHORIZE THE FULL MATRIX.**

The budget, deterministic regeneration, checksum, differential-correctness,
clean-revision, exact-small-instance-quality, and strict preflight gates pass.
The operational gate fails:

1. only 3/20 stratified pilot queries completed;
2. NY and FLA include multi-minute to 35-minute query times and group
   timeouts;
3. CAL produced no record within its 3,360-second group bound;
4. the fixed USA band-1 query produced no record within 3,360 seconds,
   reached about 162 GiB RSS, and spent the measured tail in effectively
   serial final replay;
5. safe concurrency is one process and the projected serial wall time is at
   least 118,047,603.641 seconds.

Implementation may resume from the clean source checkpoint, but paper-matrix
execution requires optimization and a repeated stratified pilot demonstrating
acceptable completion, memory, and projection. The exact production targets
are `ForwardLayeredFrontierGenerator.reduceFinal/replayPath`,
`CanonicalPathProfileBuilder.replay`, and the repeated
`TimeProfile.compose/preimage/segments/addCut` work, plus the NY
fragment-restriction/materialization path.

The full experiment matrix was **not launched, submitted, or resumed**.
