# PACE launch-readiness report

Date: 2026-07-31  
Repository: `/home/koushik/Kousik/I-PC-Max`  
Decision: **NOT_READY**

The bounded engine is materially more deterministic, observable, and robust,
but it is not operationally or algorithmically ready for the frozen 60,486-job
matrix. The final frozen pilot completed its evidence collection, not its
queries: 3 of 20 queries completed and 17 timed out.

The 60,486-job full matrix was not launched.

## 1. Provenance

The validated production source is commit
`acfee12ade8cc4fa42a036042d803596ae871108` on `main`. Every pilot raw record
identifies that commit and records `git_dirty=false`. The earlier transition
from `9d808bf` to `9fe644a` contains only
`docs/PACE_LARGE_NETWORK_OPTIMIZATION_AND_PILOT_REPORT.md`; no production file
changed in that transition.

Two consecutive `mvn -q clean package` runs from `acfee12` produced the same
benchmark JAR:

`b6aec119e61ec2820fad8ff4a240d0c81a765aaca5a1253bba178dca793fedbf`

The report/evidence commit is the commit containing this file. It is
intentionally separate from `acfee12`; retrieve it with:

```bash
git log -1 --format=%H -- docs/PACE_LAUNCH_READINESS_REPORT.md
```

### Frozen inputs

| Input | SHA-256 |
|---|---|
| Configuration file | `9157e6290bd50f43d3109279af8d7118288f58b4a8340862210548d96e6a4fb6` |
| Logical configuration | `a0cd96633c90615fcdd6c7217470ab509cdfd10640b12becae3c222e31f2690e` |
| NY query manifest | `859157add5cf6fcbcbdf2c488be6d4421cbe8644630db1d22e75e41ac1686a43` |
| FLA query manifest | `a3fe248f2005659a1f3465a9d4f4b6ba8488f25f64212cea0fb4ba7f3e958f6c` |
| CAL query manifest | `97d7481b124b36d7347c9880bb8f4fb0b20374c3cb25d83627323f661db1a045` |
| USA query manifest | `19a9873ad3358d572f64af728f7168151ca60a80ddc19ea10ab66527bd28dcaa` |
| Canonical 60,486-row ledger | `c9df350798d3cba309905308cc5c536e337b7485818200e9736f58bae8223aba` |
| Final pilot summary | `af97f875f9fbc168d9783404ff532fe4f9c9304d813059e907fb1858c4661398` |
| Exact-quality summary | `7cfa1bbf70a8a336185c5e6610836ac557ad8a58cda4b36cd0c47e43fa74eea9` |
| Strict preflight report | `fddffaacdd55f3b6be8c7eded467bbaeee0aa4005509dfe3383a6c9c88ce3690` |

The ledger hash changed from the older handoff because the configuration and
result schema now explicitly name `PACE-MQ-TOTAL-WORK-v3`. A row-by-row
reconciliation after removing `input_hash` and derived `job_id` found identical
scientific rows; its semantic hash is
`0b2aa014b6f0bd97e14180f7efbb243402072e8e3fa5e376c9427f93538db307`.
No dataset, query, cap value, or study cardinality was silently changed.

The strict preflight computed checksums and passed with no blockers
(`experiments/results/diagnostics/pace_launch_preflight.json:2-4`,
`:591`). Its embedded Git environment predates the production commit, so the
clean JAR source is established independently by the pilot raw records and
reproducible build above.

## 2. Production call graph and implemented contracts

The current production entry is `PACE.generate`, which dispatches to
`ForwardLayeredFrontierGenerator.generate` for the scalable engine
(`src/main/java/edu/ipcmax/core/pcmax/PACE.java:100-105`).

### Verified implementation

- `ForwardLayeredFrontierGenerator.generateMeasured` builds lower-bound
  source/destination labels, the safe corridor, the exact Top-L set, the online
  connector generator, bounded executor, replay store, and incremental
  frontier in that order
  (`src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:96-201`).
- The corridor and Top-L share the same lower-bound arrays; there is no second
  set of source/destination Dijkstra runs
  (`ForwardLayeredFrontierGenerator.java:103-157`).
- `PivotSelector` implements the required global
  `(-Psi,-Gamma,Delta,cellId,arcId)` ordering and documents that the historical
  diversification flag cannot alter it
  (`src/main/java/edu/ipcmax/core/pcmax/PivotSelector.java:23-32`,
  `:64-89`). Exact feasible domains are constructed in
  `QueryFeasibleEntryDomain.compute`
  (`PivotSelector.java:154-171`).
- Forward layered expansion is bounded by `theta`, uses canonical state maps,
  residual lower-bound rejection, and deterministic ledger reservations
  (`ForwardLayeredFrontierGenerator.java:203-230`, `:458-527`).
- Prefix state carries exact visited vertices and arcs. Physical pivot
  coverage is derived from the stable directed-arc path, so a connector that
  contains another selected pivot marks it covered without consuming another
  logical depth
  (`src/main/java/edu/ipcmax/core/pcmax/PivotCoverage.java:6-25`;
  `src/main/java/edu/ipcmax/core/pcmax/PartialCandidate.java:217-233`).
- Final candidate assembly verifies continuity/looplessness before admitting
  replay, deduplicates by stable path identity, and reduces results in
  canonical order
  (`ForwardLayeredFrontierGenerator.java:530-613`).
- The executor is fixed-size with a bounded queue, caller-runs saturation,
  submission-order reduction, sibling cancellation, and bounded shutdown
  (`src/main/java/edu/ipcmax/core/pcmax/IPCMaxParallelExecutor.java:15-21`,
  `:30-50`, `:58-104`).
- The versioned M_q ledger makes typed, synchronized all-or-nothing
  reservations and preserves separate M_c and M_b status
  (`src/main/java/edu/ipcmax/core/pcmax/PaceWorkLedger.java:9-29`,
  `:49-107`). Connector expansions remain separately bounded and reported by
  M_c (`PaceWorkLedger.java:89-95`, `:146-151`).
- Incremental frontier insertion now times the complete normalization,
  dominance, retention, and merge operation as `profile_merge`
  (`src/main/java/edu/ipcmax/core/pcmax/IncrementalFrontier.java:110-157`).
- Resource/cap completion is explicit: PACE-B reports
  `RESOURCE_TRUNCATED`; PACE-X aborts when an exact execution hits a cap
  (`ForwardLayeredFrontierGenerator.java:833-855`).

### Algorithmic blocker: required temporal F/B alternatives are absent

`QueryScopedConnectorLabelStore` does **not** build or retain the required
multi-alternative temporal forward and backward label sets after constructing
the corridor. It stores only one static lower-bound distance value per vertex
and delegates every prefix/suffix request to the online connector generator
(`src/main/java/edu/ipcmax/core/pcmax/QueryScopedConnectorLabelStore.java:7-15`,
`:17-41`, `:52-87`).

The online generator does return bounded alternative connector profiles and
correctly evaluates suffixes in the original temporal direction. That is
useful reuse, but it does not satisfy the agreed Phase 3 contract requiring
reusable labels containing exact departure domain, arrival/travel/score
profiles, immutable path handles, budget metadata, and exact membership.
This is an algorithm-conformance blocker, not a test-inference issue.

### Scientific-matrix blocker: obsolete diversification ablation

The exact Top-L rule deliberately ignores the diversification flag
(`PivotSelector.java:64-89`), while E10 still contains
`no-pivot-diversification`
(`experiments/configs/studies/e10_ablation.yaml:4-14`). Those jobs are now a
duplicate negative control, not a valid ablation. The matrix must not be
silently changed. User authorization is required either to remove/replace that
axis and regenerate the frozen ledger, or to retain and explicitly relabel it
as a negative control.

## 3. Verification results

| Gate | Result |
|---|---|
| Java suite | **PASS** — 205 tests, 0 failures, 0 errors |
| Incremental differential corpus | **PASS** — 3,051 comparisons, 4 cap activations, 0 mismatches |
| Seeded exact corpus | **PASS** — 1,000 seeded cases in `PaceExactOracleDifferentialTest` |
| Python suite | **PASS** — 23 tests |
| Exact PACE-X quality suite | **PASS** — 12/12 completed, reference available, and output verified |
| Reproducible JAR | **PASS** — identical SHA-256 across two clean builds |
| Thread determinism | **PASS** — byte/checksum equality at 1, 2, 4, 8, 16, and 24 threads |
| Actual overlap | **PASS** — tests require at least two workers; completed pilot queries observed 4, 4, and 8 workers |
| Worker cleanup | **PASS** — leak test passes and no Java pilot process remains |
| Query validation | **PASS** — all four manifests validated without skipped checksums |
| Strict preflight | **PASS** — all datasets and variants, horizon, FIFO, positive lower bounds, counts, and checksums |
| Exact-quality artifact | **PASS** — `experiments/results/diagnostics/pace_launch_readiness_20260731/exact_quality_final_v2/summary.json:2-3`, `:179-205` |

`PacePublicApiOracleIntegrationTest` contains the cross-thread equality,
concurrent-overlap, worker-leak, cap/status equality, and cache-equivalence
checks (`src/test/java/edu/ipcmax/core/pcmax/PacePublicApiOracleIntegrationTest.java:130-180`,
`:183-263`).

The post-resume evidence correction makes per-query
`process_end_to_end_seconds` use recorded preprocessing plus query time rather
than the near-zero duration of a Java `--resume` skip
(`experiments/scripts/run_stratified_paper_pilot.py:108-123`,
`:137-199`). It changes no raw result or algorithm output and has a regression
test in `experiments/tests/test_stratified_pilot.py`.

## 4. Frozen 20-query pilot

Artifact:
`experiments/results/diagnostics/pace_launch_readiness_20260731/stratified_pilot_final_v2/summary.json`

The summary confirms all terminal records, the 15% completion rate, actual
parallel overlap, failed operational gate, unchanged non-launch state, and
non-overlapping timing categories (`summary.json:2-4`, `:9964-9969`,
`:11091-11097`).

| Dataset | Completed | Timeout | Completed query seconds | Preprocessing range (s) | Peak RSS (GiB) |
|---|---:|---:|---|---:|---:|
| NY | 2 | 3 | 106.240, 241.076 | 15.326–19.406 | 21.44 |
| FLA | 1 | 4 | 244.921 | 60.524–70.641 | 253.24 |
| CAL | 0 | 5 | — | 97.608–116.727 | 94.42 |
| USA | 0 | 5 | — | 1,110.403–1,180.787 | 168.30 |
| **Total** | **3** | **17** | — | — | **253.24** |

There were no OOM, `NO_RECORD`, harness-error, or cap/resource-truncation
outcomes. Timeout values are right-censored and are not treated as completed
runtimes.

For completed queries:

- query-only median: 241.076 s;
- query-only IQR: 173.658–242.998 s;
- query-only p95: 244.921 s;
- isolated preprocessing-plus-query median: 256.403 s;
- completion rate: 15%;
- timeout rate: 85%;
- cap rate: 0%;
- Kaplan–Meier median: not estimable because the survival curve never falls
  below 0.5 with only three observed completions.

Peak measured RSS was 271,915,536,384 bytes (253.24 GiB). With 314.56 GiB
physical RAM, 32 physical cores, and 24 requested threads per query, safe
concurrency is **one query process** (`summary.json:11082-11089`).

### Before/after

The original handoff pilot had 3 completed, 12 external timeouts, and 5 missing
records. The intermediate endpoint-fix pilot had 3 completed, 13 timeouts, and
4 OOM outcomes. The final run has 3 completed, 17 explicit timeouts, 0 OOM, and
0 missing records. Cancellation/recovery reliability is fixed, but query
completion did not improve.

Against the endpoint-fix pilot:

- completed-query median changed from 237.426 s to 241.076 s (+1.54%);
- completed-query p95 changed from 270.372 s to 244.921 s (-9.41%);
- measured maximum RSS changed from 223.27 GiB to 253.24 GiB (+13.43%);
- completion stayed 3/20.

## 5. Measured bottlenecks

Timeout failing phases:

| Phase | Timeouts |
|---|---:|
| Connector generation | 8 |
| Feasible-entry-band computation | 2 |
| Bounded retention | 2 |
| Profile merge | 1 |
| Safe dominance | 1 |
| Canonical path replay/stitching | 1 |
| Corridor construction | 1 |
| Horizon validation | 1 |

The three completed queries spent most query time in profile merge:

| Query | Profile merge | Connector generation | Canonical replay |
|---|---:|---:|---:|
| NY band 1 | 83.237 s (78.3%) | 11.689 s | 6.958 s |
| NY band 3 | 143.566 s (59.6%) | 68.974 s | 20.061 s |
| FLA band 1 | 157.746 s (64.4%) | 63.544 s | 13.050 s |

USA exposes an additional front-end scaling defect. `PivotSelector` computes
exact feasible-entry domains for every corridor arc before retrieving the
score-bearing subset (`PivotSelector.java:154-185`). USA records reached
millions of feasible bands and timed out in feasible-band computation,
corridor construction, or even horizon validation. The latter currently scans
every corridor arc (`ForwardLayeredFrontierGenerator.java:815-829`).

The next correctness-preserving optimization should:

1. retrieve and intersect score-bearing arc IDs before exact feasible-domain
   construction;
2. build the required reusable multi-alternative temporal F/B labels and serve
   connector portfolios from them;
3. replace per-query all-corridor horizon scans with a validated immutable
   dataset/index invariant;
4. reduce profile-merge materialization, dominance, and retention work without
   changing endpoint ownership or the deterministic comparator.

## 6. Twenty launch-readiness gates

| # | Gate | Result |
|---:|---|---|
| 1 | Reproducible clean source | PASS |
| 2 | Separate source and evidence commits | PASS after the local evidence commit containing this report |
| 3 | Rebuilt JAR and recorded hash | PASS |
| 4 | Java tests | PASS |
| 5 | Python tests | PASS |
| 6 | Differential corpus | PASS |
| 7 | Exact quality suite | PASS |
| 8 | Cross-thread deterministic checksums | PASS |
| 9 | Actual query-internal concurrency | PASS |
| 10 | No worker leaks | PASS |
| 11 | Twenty terminal pilot records | PASS |
| 12 | No OOM or `NO_RECORD` | PASS |
| 13 | Predeclared operational pilot gate | **FAIL — 15% completion, required at least 90%** |
| 14 | USA fits measured safe concurrency | PASS only at concurrency one |
| 15 | Operationally acceptable projection | **FAIL** |
| 16 | Non-overlapping timing categories | PASS |
| 17 | Corridor and F/B time included in query time | PASS |
| 18 | Startup separate and included in isolated end-to-end time | PASS after evidence correction |
| 19 | Ledger and checksum reconciliation | PASS with declared M_q-v3 hash migration |
| 20 | No silent scientific-axis change | **FAIL pending authorization for the no-op diversification ablation** |

The right-censored projection floor is 34,741,452.423 seconds, or **402.1
days**, at safe concurrency one (`summary.json:11044`). Successful completion
time is not estimable because 85% of pilot queries are censored. Even this
floor requires a 13.40× speedup to fit 30 days and 28.72× to fit 14 days.

## 7. Exact blockers to authorization

1. Implement reusable exact temporal multi-alternative forward/backward labels;
   the present lower-bound facade plus online connector search does not satisfy
   the agreed algorithm.
2. Optimize exact feasible-entry-band construction, connector generation, and
   incremental profile merge enough to pass at least 18/20 frozen pilot
   queries under the declared limit.
3. Re-run the unchanged pilot and obtain an operationally acceptable,
   uncensored projection.
4. Obtain user authorization to remove/replace or explicitly relabel the
   obsolete `no-pivot-diversification` E10 axis, then regenerate and reconcile
   the ledger if the frozen matrix changes.

Until those blockers are resolved, the correct status is:

**NOT_READY**

