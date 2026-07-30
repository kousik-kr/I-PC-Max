# PACE Large-Network Optimization and Stratified-Pilot Report

Date: 2026-07-30 UTC

Repository: `/home/koushik/Kousik/I-PC-Max`

## Decision

The optimization, instrumentation, timeout-adjudication, and regression work
in this mission is complete. The implementation is correctness-clean and the
pilot harness now emits one truthful terminal record per query. It is ready
for another bounded optimization/diagnostic cycle.

**The unchanged 60,486-job full matrix is not operationally ready and is not
authorized. It was not launched, submitted, resumed, or left running.**

The decisive pilot result, after correcting four false OOM classifications,
is:

- 20/20 terminal records;
- 3 `COMPLETED`;
- 17 `TIMEOUT`;
- 0 `OUT_OF_MEMORY`;
- 0 `NO_RECORD`;
- 0 cap activations;
- 15% completion and 85% right-censoring;
- Kaplan-Meier median not estimable;
- safe concurrency of one query process;
- a 60,486-row ledger projection floor of 37,366,469.864 seconds, or
  432.482 days at that concurrency.

This passes the correctness, deterministic-output, exact-quality, terminal
record, and no-OOM/no-NO_RECORD gates. It fails the completion and operational
projection gates. A clean full run would therefore be a cleanly recorded run
with overwhelmingly censored PACE-B results, not a completed experiment.

## 1. Build provenance

The apparently conflicting starting commits are reconciled as follows.

| Role | Commit / checksum | Evidence |
|---|---|---|
| Tested production-code commit | `f0ece60506c81b0b96126fb8bcbedcfc311d6502` | Introduced streaming graph semantic checksum |
| Starting evidence commit | `776f67ee7fe499ea1347b41d6c176a8877e312fd` | Its only change after `f0ece6` is `docs/PACE_PAPER_READINESS_FINAL_REPORT.md` |
| `src` tree at both commits | `0173e81c322ee43536a176334e99638d08615dec` | `git rev-parse <commit>:src` is identical |
| `pom.xml` content SHA-256 at both commits | `cce3292ee4bf199191abff18b3046251ea37162847b6960c3e4b83fa08a8020b` | `git show <commit>:pom.xml \| sha256sum` is identical |
| Starting build command | `mvn -q clean package` | Maven Shade produces `target/pace-bench.jar` |
| Reproduced starting JAR | `1e03d6fe2dc6bc998479994727ca38cdd88af56f64d757f5400a936d97f1d8bb` | Rebuilt independently from both identical production inputs |
| Replay/frontier optimization | `e11c4cebda5bb7bb1c8ec5240b8ca73164f59df3` | Bounded replay/prefix caches, deterministic replay batch, retained references, linear merge, counters |
| Watchdog separation | `52cc919cb06666b23d6a9cdcb281af001af4c561` | Separate preprocessing and query deadlines, ready marker, graph-free forced record |
| Endpoint-semantics correction | `d02f6e5855db3d62562325e385babc4f461b2746` | Exact score reconstruction for a maximal fragment run |
| Timeout-status correction | `9d808bfddcf2df6292570d0d63bf05782923cd95` | Preserve timeout after ignored cancellation; require concrete OOM evidence |
| Final benchmark JAR | `63aaa7a6c8808735d8536080ec953a14ce1f820a901c65c6cb7f3fcd7d0d28f3` | `sha256sum target/pace-bench.jar` |

The benchmark rows created while the worktree was being optimized naturally
record an earlier commit and `git_dirty=true`. Their governing executable is
therefore identified by the JAR hash and artifact directory, not by treating
the embedded Git field as a false clean-build assertion. The final exact
quality suite separately records the final JAR hash.

## 2. Scientific contracts were not weakened

No run changed `L`, `theta`, `K_c`, `K_f`, `M_c`, `M_b`, or `M_q` to improve
completion. The pilot used:

- `M_c = 5,000,000`;
- `M_b = 1,000,000`;
- `M_q = 250,000,000`;
- `M_q_contract = PACE-MQ-TOTAL-WORK-v2`;
- one OS worker process per query;
- at most 24 algorithm threads inside that process;
- `-Xmx250g` (manifested as 256,000 MiB);
- a 300-second query watchdog and an independent 1,800-second preprocessing
  watchdog.

The production call graph continues to enforce:

- canonical path identity and vertex simplicity in
  `CanonicalPathProfileBuilder.continueReplay`, including discontinuity and
  repeated-vertex rejection
  (`src/main/java/edu/ipcmax/core/pcmax/CanonicalPathProfileBuilder.java:223-264`);
- only selected pivot arc IDs count as explicit anchors, while non-selected
  score-bearing edges remain in exact replay
  (`CanonicalPathProfileBuilder.java:56-70,264-265`);
- the query horizon and budget are part of replay construction
  (`CanonicalPathProfileBuilder.java:78-111`);
- exact induced-time composition in `TimeProfile.compose`
  (`src/main/java/edu/ipcmax/core/profile/TimeProfile.java:241-265`);
- exact domain preimages in `TimeProfile.preimage`
  (`TimeProfile.java:195-211`);
- canonical time and deduplicated cuts in `TimeProfile.addCut`
  (`TimeProfile.java:746-757`);
- deterministic output for both PACE-X and PACE-B across 1, 2, 4, 8, 16,
  and 24 threads
  (`src/test/java/edu/ipcmax/core/pcmax/PacePublicApiOracleIntegrationTest.java:125-157`);
- actual parallel work with byte-identical serial/parallel output
  (`PacePublicApiOracleIntegrationTest.java:160-173`);
- cache-enabled/cache-disabled byte and checksum equivalence
  (`PacePublicApiOracleIntegrationTest.java:175-220`);
- the last valid frontier and typed status when `M_q` activates
  (`src/test/java/edu/ipcmax/core/pcmax/IncrementalFrontierDifferentialTest.java:304-328`).

## 3. Instrumentation and implementation changes

### 3.1 Canonical replay

`ForwardLayeredFrontierGenerator.reduceFinal` now collects replay requests
before reduction, records the number of input candidates and distinct stable
path IDs, executes the replay batch, and inserts answers in original order
(`src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:487-544`).

The query-local `ReplayStore` provides:

- a 32,768-entry LRU full-replay cache and an 8,192-entry single-flight prefix
  cache (`ForwardLayeredFrontierGenerator.java:820-868`);
- stable de-duplication before computation and deterministic result placement
  (`ForwardLayeredFrontierGenerator.java:871-959`);
- parallel computation of independent unique requests followed by
  deterministic reduction (`ForwardLayeredFrontierGenerator.java:1013-1060`);
- immutable prefix reuse through `CanonicalPathProfileBuilder.extend`
  (`ForwardLayeredFrontierGenerator.java:1082-1116` and
  `CanonicalPathProfileBuilder.java:114-220`);
- a complete replay key containing stable arc sequence, source, endpoint,
  requested domain, query horizon, canonical budget, and ordered selected
  pivots (`ForwardLayeredFrontierGenerator.java:1120-1160`);
- hard eviction and query-end clearing
  (`ForwardLayeredFrontierGenerator.java:989-1010,1163-1170`).

`SingleFlightCache.getOrCompute` permits one producer per key, accounts for
hits/misses/waits, bounds completed entries, and removes failed promises
(`src/main/java/edu/ipcmax/core/cache/SingleFlightCache.java:10-64,95-120`).

The following counters are serialized by `PaceBench`
(`src/main/java/edu/ipcmax/experiments/PaceBench.java:104-150`):

- final-reduction candidates, distinct paths, observed workers, and maximum
  concurrently active workers;
- canonical replay requests, unique requests, hits, misses, batches,
  evictions, repeated prefixes, edges, and reused prefix edges;
- temporal compose, preimage, segments, cuts attempted/created/deduplicated;
- fragment reference, restriction, materialization, identical-domain,
  cache-peak, and eviction counts;
- heap checkpoints before graph load, after graph load, after preprocessing,
  before final reduction, during replay, and after query;
- `query_worker_ignored_cancellation`.

### 3.2 Fragment restriction and merge

Incremental cells now retain immutable source-profile references with explicit
endpoint ownership instead of eagerly copying restricted profiles
(`src/main/java/edu/ipcmax/core/pcmax/FrontierCompressor.java:101-149`).

`IncrementalFrontier.rebuildRetained` materializes only when a retained
frontier is rebuilt, caches exact lineage/domain requests, charges the
existing typed work ledger, and reports retained-reference and
materialization proxies
(`src/main/java/edu/ipcmax/core/pcmax/IncrementalFrontier.java:390-485`).
The cache is bounded to at most 16,384 entries, also bounded by
`maxFrontierFragments` and the effective frontier limit
(`IncrementalFrontier.java:92-103`), and is explicitly cleared with dominance
state at query end (`IncrementalFrontier.java:488-505`).

Adjacent compatible fragments are sorted canonically and accumulated as one
maximal run rather than pairwise copying the accumulated profile
(`FrontierCompressor.java:152-230,1291-1346`). Compatibility checks preserve
path identity, anchor metadata, profile lineage, adjacency, endpoint
ownership, arrival closure, and score agreement
(`FrontierCompressor.java:1249-1288`).

The first linear score concatenation exposed a real endpoint-semantics bug on
NY band 2:

- historical profile checksum:
  `33e76057dbb923a2843a89a77b0b22323354274654d33a52737fa87fa29fde3a`;
- initial optimized checksum:
  `3533d3a438fb8f372b8aa1955f84abb7cf5caeb59a5b5751f5a675f643aa2010`;
- corrected checksum:
  `33e76057dbb923a2843a89a77b0b22323354274654d33a52737fa87fa29fde3a`.

The correction reconstructs the maximal run over its exact partition and
adds a point interval when the owned terminal value differs from the
interior value (`FrontierCompressor.java:1407-1456`). The regression compares
domain, arrival breakpoints, score intervals, and every junction/terminal
value against historical pairwise merge
(`src/test/java/edu/ipcmax/core/pcmax/FrontierCompressorTest.java:179-220`).

An attempted broader adjacent-cell coalescing was rejected after it produced
438 differential partition mismatches. It is not present in the final code.
This is important negative evidence: no performance change was retained when
its semantic equivalence failed.

### 3.3 Watchdog and terminal records

When a timeout or memory threshold is configured, `PaceBench` uses one child
process per query. The child writes a ready marker only after dataset loading
and algorithm preparation
(`src/main/java/edu/ipcmax/experiments/PaceBench.java:205-227`).
The parent separately enforces preprocessing and query deadlines
(`PaceBench.java:470-504`), forcibly terminates the worker if necessary, and
uses a graph-free fallback child to serialize exactly one terminal row
(`PaceBench.java:291-379,511-515`).

The original post-pilot summary labeled USA bands 2-5 as OOM solely because
an output was missing while a memory limit was configured. The logs actually
said that the query thread ignored cancellation; they contained neither a
JVM `OutOfMemoryError` nor an OS memory-kill code.

The fixed classification requires concrete evidence:

- ignored cancellation after readiness becomes `TIMEOUT`;
- `OutOfMemoryError` text or exit 137 under a memory limit becomes
  `OUT_OF_MEMORY`;
- any other missing result becomes `ERROR`.

The production decision is at `PaceBench.java:382-408`, with all three cases
covered at
`src/test/java/edu/ipcmax/experiments/PaceBenchFrameworkTest.java:205-259`.
After an internal timeout, an uncooperative daemon query thread no longer
causes the already determined timeout result to be discarded; the counter is
recorded and the child exits, releasing its entire address space
(`PaceBench.java:721-815`).

The pilot summary code now:

- uses completed observations only for median/IQR/p95;
- treats timeout states as right-censored;
- reports competing failures separately;
- never invents a Kaplan-Meier median;
- accounts for preprocessing on every isolated ledger job;
- labels the matrix projection as a censored lower bound, not as a successful
  completion estimate;
- rejects OOM, ERROR, HARNESS_ERROR, missing terminal rows, poor completion,
  excessive caps, or a projection above 90 days.

The implementation is at
`experiments/scripts/run_stratified_paper_pilot.py:125-147,435-620`, with
focused statistical tests at
`experiments/tests/test_stratified_pilot.py:9-26`.

## 4. Bounded diagnostic results

Phase timers can overlap and must not be summed.

| Case | Status | Preprocess (s) | Query (s) | Fragment merge (s) | Replay (s) | Peak RSS (GiB) | Profile checksum |
|---|---|---:|---:|---:|---:|---:|---|
| NY band 1, historical | COMPLETED | 15.572 | 2,091.360 | 2,043.443 | 27.873 | 16.493 | `860d764c…e0f9fd` |
| NY band 1, optimized | COMPLETED | 16.234 | 225.558 | 52.298 | 21.448 | 16.196 | `860d764c…e0f9fd` |
| NY band 2, historical | COMPLETED | 15.572 | 702.174 | 477.286 | 175.450 | 16.502 | `33e76057…fde3a` |
| NY band 2, corrected optimized | COMPLETED | 16.772 | 270.309 | 30.569 | 123.413 | 16.349 | `33e76057…fde3a` |
| FLA band 1, historical | COMPLETED | 51.973 | 108.655 | 42.979 | 46.966 | 40.917 | `2c125fd8…ce3e6` |
| FLA band 1, optimized | COMPLETED | 57.157 | 101.821 | 5.480 | 30.238 | 34.023 | `2c125fd8…ce3e6` |
| USA band 1, 600-second diagnostic | TIMEOUT | 1,110.197 | 609.257 | 33.128 | 266.792 | 165.970 | no completed profile |

Artifacts:

- `experiments/results/diagnostics/pace_large_network_optimization_20260730/ny1_linear_merge/results.jsonl`;
- `experiments/results/diagnostics/pace_large_network_optimization_20260730/ny2_endpoint_fix/results.jsonl`;
- `experiments/results/diagnostics/pace_large_network_optimization_20260730/fla1/results.jsonl`;
- `experiments/results/diagnostics/pace_large_network_optimization_20260730/usa1_fixed/results.jsonl`.

The directly comparable completed-query speedups are:

- NY band 1: 8.81x;
- NY band 2: 2.60x;
- FLA band 1: 1.10x.

The completed outputs have identical profile checksums, interval counts, path
counts, and aggregate score values to their historical rows.

The instrumentation definitions changed during the optimization, so old
null counters must not be read as zeros. For the frozen post-optimization
rows:

| Counter | NY B1 | NY B2 | FLA B1 |
|---|---:|---:|---:|
| replay requests / unique | 172 / 172 | 84 / 84 | 45 / 45 |
| prefix-cache hits / misses | 132 / 40 | 65 / 19 | 44 / 1 |
| prefix-cache hit rate | 76.7% | 77.4% | 97.8% |
| repeated prefixes | 129 | 63 | 44 |
| final-reduction input / distinct paths | 160 / 160 | 76 / 76 | 8 / 8 |
| final-reduction observed / max-active workers | 24 / 4 | 24 / 4 | 22 / 8 |
| fragment restrictions | 924,939 | 538,260 | 69,813 |
| fragment materializations | 1,849,859 | 1,071,937 | 147,024 |
| identical fragment/domain requests | 1,064 | 968 | 7,863 |
| retained reference/profile proxy peak | 7,029 / 7,029 | 8,851 / 8,851 | 4,572 / 4,572 |
| materialization-cache peak entries | 1,025 | 1,025 | 2,049 |
| temporal compose calls | 39,697 | 35,378 | 16,209 |
| temporal preimage calls | 430,512 | 415,022 | 170,856 |
| temporal segments visited | 8,161,843 | 32,543,299 | 16,153,922 |
| temporal cuts attempted / created / deduplicated | 8,453,122 / 8,453,122 / 0 | 34,878,211 / 34,878,211 / 0 | 17,498,915 / 17,498,915 / 0 |
| requested / observed algorithm workers | 24 / 4 | 24 / 4 | 24 / 8 |
| total `M_q` work | 2,959,579 | 1,729,747 | 281,844 |

All replay requests in these three rows were already unique at the full-key
level, so the full replay cache legitimately had no within-query hits. The
useful reuse was at the prefix level. USA band 1 similarly had 32 unique
full replays but 31 repeated prefixes and reached 24 simultaneous
final-reduction workers. Parallelism is real; the remaining cost is the
amount of temporal work per unique path/profile, not a parked 24-thread
executor.

## 5. USA memory and remaining bottleneck

The 600-second USA band-1 diagnostic recorded these JVM heap checkpoints:

| Checkpoint | Bytes | Approx. GiB |
|---|---:|---:|
| before graph load | 27,829,944 | 0.026 |
| after graph load | 101,017,120,384 | 94.08 |
| after preprocessing | 111,252,969,960 | 103.61 |
| before final reduction | 121,563,514,464 | 113.21 |
| during replay | 164,084,424,352 | 152.81 |
| peak used heap | 167,590,858,512 | 156.08 |
| peak process RSS | 178,089,091,072 | 165.87 |

This provides defensible category bounds:

- graph/index state is the dominant fixed base: about 94.1 GiB after load;
- preprocessing adds about 9.5 GiB;
- candidate/cell/frontier state before final reduction adds about 9.6 GiB;
- replay temporal profiles, segment arrays, and fragment materialization add
  about 39.6 GiB above the pre-final checkpoint;
- caches are query-local and bounded; the run observed only 32 full replay
  entries, one prefix entry, and at most 8,193 fragment materializations;
- instrumentation is primitive counters/timers and cannot explain the
  tens-of-GiB increase.

The main overlapping USA phases were:

| Phase | Seconds |
|---|---:|
| connector generation | 256.260 |
| canonical replay/stitching | 266.792 |
| corridor construction | 52.984 |
| bounded retention | 51.940 |
| safe dominance | 51.082 |
| fragment restriction/merge | 33.128 |
| final connector reduction | 17.050 |

At the frozen 300-second watchdog, later USA strata often timed out before
final reduction, in corridor construction or feasible-entry-band
construction. The dominant next target is therefore not another replay
cache: it is scalable lower-bound corridor/feasible-band construction and a
compact graph/index representation that lowers the approximately 94-GiB
loaded base. After those stages fit inside the watchdog, temporal
composition/segment work per unique replay is the next measured target.

## 6. Frozen 20-query pilot

The post-optimization pilot preserved the same four datasets and five
distance bands. It ran exactly one query process at a time with up to 24
threads inside that process. It did not run multiple queries concurrently.

### 6.1 Before versus after

| Outcome | Starting pilot | Final adjudicated pilot |
|---|---:|---:|
| selected strata | 20 | 20 |
| completed | 3 | 3 |
| timeout/right-censored | 12 external timeouts | 17 formal timeouts |
| OOM | 0 | 0 |
| NO_RECORD | 5 | 0 |
| cap activation | 0 | 0 |
| terminal record coverage | 3/20 | 20/20 |

The final per-stratum status is:

| Dataset | B1 | B2 | B3 | B4 | B5 |
|---|---|---|---|---|---|
| NY | COMPLETED | COMPLETED | TIMEOUT | TIMEOUT | TIMEOUT |
| FLA | COMPLETED | TIMEOUT | TIMEOUT | TIMEOUT | TIMEOUT |
| CAL | TIMEOUT | TIMEOUT | TIMEOUT | TIMEOUT | TIMEOUT |
| USA | TIMEOUT | TIMEOUT | TIMEOUT | TIMEOUT | TIMEOUT |

The original pilot directory remains immutable evidence:

`experiments/results/diagnostics/pace_large_network_optimization_20260730/stratified_pilot_endpoint_fix`

Its summary predates the final timeout-classification fix and therefore
contains four stale `OUT_OF_MEMORY` labels for USA bands 2-5. The corrected
reruns are:

`experiments/results/diagnostics/pace_large_network_optimization_20260730/usa_timeout_status_repair`

Each corrected row is `TIMEOUT`, contains
`query_worker_ignored_cancellation=1`, and has no concrete OOM evidence:

| USA band | Preprocess (s) | Recorded query+cleanup (s) | Peak RSS (GiB) | Failing phase |
|---|---:|---:|---:|---|
| 2 | 1,097.012 | 330.005 | 161.423 | corridor construction |
| 3 | 1,094.714 | 330.004 | 151.714 | feasible-entry-band computation |
| 4 | 1,105.770 | 330.004 | 157.314 | feasible-entry-band computation |
| 5 | 1,045.935 | 330.004 | 168.078 | corridor construction |

The survival censor time is 300 seconds. The extra approximately 30 seconds
is the bounded wait used to establish that the in-process query thread
ignored cancellation before the child process exits. It is operational
cleanup cost, not observed algorithm completion time.

### 6.2 Runtime statistics

Only the three completed queries are used as observed completion times.

Query-only seconds:

- values: 98.939, 237.426, 270.372;
- median: 237.426;
- inclusive IQR: 168.183 to 253.899;
- nearest-rank p95: 270.372;
- maximum: 270.372.

Process-isolated preprocessing-plus-query seconds:

- values: 170.243, 254.663, 288.237;
- median: 254.663;
- inclusive IQR: 212.453 to 271.450;
- nearest-rank p95: 288.237;
- maximum: 288.237.

There are 17 right-censored observations out of 20 (85%). With only three
events before the censor point, the Kaplan-Meier survival curve never falls
to 0.5; its median is not estimable. Timeout values are not substituted and
described as observed runtimes.

The maximum measured pilot RSS is 239,732,252,672 bytes (223.268 GiB), from
FLA band 3. With 337,737,048,064 bytes total memory and 24 threads per query,
both the memory and CPU bounds support only one query process. This agrees
with the user-specified one-query-at-a-time contract.

## 7. Correctness and data gates

| Gate / command | Result |
|---|---|
| `mvn -q test` | PASS on final commit; `comparisons=3051`, `cap_activations=4`, `mismatches=0` |
| `python3 -m unittest discover -s experiments/tests -p 'test_*.py'` | PASS; 22 tests |
| Exact small-instance suite | PASS; 12/12 completed, 12/12 reference available, 12/12 output verified |
| Exact quality | path/score agreement 1.0; breakpoint precision/recall 1.0; feasibility disagreement, score regret, score gap, and missed switches all 0 |
| Thread determinism | PASS for 1, 2, 4, 8, 16, 24 threads in PACE-X and PACE-B |
| Serial/parallel equivalence | PASS; parallel tasks observed |
| Replay/connector caches enabled versus disabled | PASS; byte-identical/checksum-identical |
| Open/closed and terminal horizon | PASS |
| Typed cap preserves last valid frontier | PASS |
| Deep dataset/query preflight at final code commit `9d808bf` | PASS; `passed=true`, `blockers=[]`, `checksums_computed=true` |

The final exact-quality artifact is:

`experiments/results/diagnostics/pace_large_network_optimization_20260730/exact_quality_final_harness/summary.json`

It records final JAR SHA-256
`63aaa7a6c8808735d8536080ec953a14ce1f820a901c65c6cb7f3fcd7d0d28f3`
and `full_matrix_launched=false`.

The final current-commit deep-preflight artifact is:

`experiments/results/diagnostics/pace_large_network_optimization_20260730/strict_preflight_final_harness.json`

It records clean commit
`9d808bfddcf2df6292570d0d63bf05782923cd95`, deep mode, all
implementation gates passed, `passed=true`, and `blockers=[]`.

The deep preflight verified all production assets:

| Dataset | Vertices | Directed arcs | FIFO functions | Positive-LB arcs | Support end |
|---|---:|---:|---:|---:|---:|
| NY | 264,346 | 733,846 | 733,846 | 733,846 | 10,080 |
| FLA | 1,070,376 | 2,712,798 | 2,712,798 | 2,712,798 | 10,080 |
| CAL | 1,890,815 | 4,657,742 | 4,657,742 | 4,657,742 | 10,080 |
| USA | 23,947,347 | 58,333,344 | 58,333,344 | 58,333,344 | 10,080 |

The conversion contract is
`declared_centisecond_normalization-v1`. Dataset structure, payload, and
temporal checksums passed; NY density variants 5%, 10%, 20%, and 40% and the
configured NY/CAL graph seeds 42, 43, and 44 passed. Query manifests passed
their checksums, horizon checks, and 20/10/100 pilot/warmup/evaluation pair
counts.

## 8. Ledger projection and operational options

The canonical job ledger is unchanged:

- path:
  `experiments/results/diagnostics/pace_paper_readiness_20260729/matrices/canonical_job_ledger.jsonl`;
- rows: 60,486;
- SHA-256:
  `484e71ab90b9a01790450b3d88d221ef5fbf79a81b94e85b9fffee1a4dcfcea3`.

Using safe concurrency one, the recorded conservative projection floor is:

- 37,366,469.864 seconds;
- 432.482 days;
- successful completion time: not estimable.

PACE-B incomplete strata contribute only censored/failure elapsed time;
non-PACE rows use the configured-timeout policy. This number is therefore a
mixed conservative floor, not an estimate that all 60,486 jobs would finish.

Speedup required merely to reduce that floor to the target wall times:

| Target | Required speedup |
|---|---:|
| 7 days | 61.783x |
| 14 days | 30.892x |
| 30 days | 14.416x |

The former projection was 116,686,803.641 seconds (1,350.5 days). The new
floor is 3.12x lower because of the NY/FLA improvements and real per-query
watchdogs, but completion remains unobserved for 85% of strata.

### Scientifically reduced proposal — not activated

First remove exact default duplicates already represented by the principal
configuration:

| Experiment | Redundant rows |
|---|---:|
| E05, window 120 | 1,200 |
| E06, `rho=0.30` | 1,200 |
| E07, default depth/theta | 1,200 |
| E08, NY density 20% | 600 |
| E09, default `L=4` plus high-budget duplicate | 240 |
| E10, full/serial/default-theta duplicates | 3,600 |
| E11, one-thread duplicate | 2,400 |
| E12, seed 42 duplicate | 1,200 |
| **Exact de-duplication total** | **11,640** |

That leaves 48,846 rows without removing a unique configuration.

A further proposal uses 30 evaluation pairs per dataset, stratified as six
per distance band, two centers, and three trials while retaining all seven
research questions, required baselines, non-default principal ablations,
disjoint query sets, and random seeds:

| Component | Proposed rows |
|---|---:|
| E01 correctness | 6 |
| E02 baseline comparison | 1,440 |
| E03 main PACE comparison | 7,440 |
| E05 window | 2,880 |
| E06 budget | 2,880 |
| E07 depth | 2,160 |
| E08 density | 540 |
| E09 interactions | 2,160 |
| E10 ablations | 2,520 |
| E11 parallel scaling | 3,600 |
| E12 robustness seeds | 2,400 |
| **Proposed total** | **28,026** |

This removes 32,460 rows (53.7%) from the canonical ledger. It is a proposal
only. It requires a documented power analysis and preregistered aggregation
plan before activation; no canonical config, manifest, or ledger was changed.

## 9. Acceptance-gate disposition

| Requirement | Disposition |
|---|---|
| Every pilot stratum emits a terminal record | PASS, after four bounded USA status repairs |
| No OOM or NO_RECORD | PASS after evidence-based reclassification |
| Correctness/differential tests | PASS |
| Exact-quality 12/12 | PASS |
| Deterministic checksums | PASS |
| Memory permits claimed concurrency | PASS only for concurrency one |
| Projection operationally acceptable | **FAIL** |
| Pilot completion operationally acceptable | **FAIL: 3/20** |
| Full matrix launched | **NO** |

All implementation and harness defects identified in this mission have been
fixed. The remaining blocker is measured algorithmic scalability, not a
preflight barrier. The next bounded work should target USA corridor and
feasible-band construction plus graph/index memory, followed by per-unique
replay temporal composition. No further full-pilot repetition is justified
until representative NY/CAL/USA diagnostics complete inside the query
watchdog without weakening the scientific parameters.

## 10. Commands actually used

Key validation/build commands:

```text
git rev-parse <commit>:src
git diff --name-only f0ece605..776f67ee
git show <commit>:pom.xml | sha256sum
mvn -q clean package
sha256sum target/pace-bench.jar
mvn -q test
python3 -m unittest discover -s experiments/tests -p 'test_*.py'
python3 experiments/scripts/preflight.py \
  --config experiments/configs/paper_q1_server_24c_250g.yaml \
  --output <diagnostic-preflight.json>
python3 experiments/scripts/run_exact_quality_suite.py <bounded-suite-options>
python3 experiments/scripts/run_stratified_paper_pilot.py <bounded-pilot-options>
```

Bounded benchmark commands used one query file, one process, 24 threads,
`--memory-limit-mb 256000`, the declared `L/theta/K_c/K_f` for the dataset,
`M_c=5000000`, `M_b=1000000`, `M_q=250000000`, deterministic mode, and
phase/memory/counter collection. The full-matrix runner was not invoked.
