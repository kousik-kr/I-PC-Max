# ICDE 2027 Full Repository Audit

Date: 2026-10-16 UTC
Audited source revision: 508a1d8cc422fd4595ad36857e51990fd86c562e
Branch: feature/icde27-baseline-implementation
Required THop repair ancestor: c1d92d3105bc536cea97722007cd1f6f24ffa1c6
Final classification: **NOT READY**

## M5-R superseding status (2026-10-16)

This addendum supersedes the audit-time gate statuses below; the original findings remain as the baseline record. R1 now fixes the PACE-X empty/incomplete-frontier status invariant. R3 has read the full local WISE 2025 paper and documented the implemented iSCOPE variant as a material deviation. R4 registers and tests the partitioned THop and native SICSP experiment adapters. R5 adds the versioned prospective matrices and corrects capped-run completion accounting. R6 reaggregates the six displayed scalability values and validates historical row integrity, but the dirty historical source/JAR lineage remains unrecovered. SICSP-Pref has a wrong winner and false-feasibility counterexample under mixed legacy arithmetic; the adapter now returns `NOT_CERTIFIED`, but P1-B remains unresolved. THop remains an exact-rational fastest-profile implementation with a disclosed search-first/prune-later schedule deviation.

Current release decision: **NOT READY; M6 PILOT BLOCKED**. G1 is partial because the status invariant is fixed but shared temporal arithmetic remains a limit; G2 is partial; G3 is partial; G4 is partial; G5 and G7 fail; G6 passes with a documented construction-schedule deviation; G8 and G9 pass within the scopes recorded in the M5-R report; G10 fails because P1-B remains. The historical manuscript table stays quarantined and must be replaced. Full gate evidence and freeze state are in [ICDE_2027_M5_REPAIR_REPORT.md](ICDE_2027_M5_REPAIR_REPORT.md) and [ICDE_2027_BENCHMARK_FREEZE_MANIFEST.md](ICDE_2027_BENCHMARK_FREEZE_MANIFEST.md).

## Executive result

The audited code builds on Java 21 and the complete current Maven suite passes. Independent tiny-graph evidence is substantial for PACE-X, THop and native SICSP, with an iSCOPE oracle test added during this audit. Those results do not clear the benchmark gate.

Two concrete temporal/status defects remain: PACE-X can throw instead of returning an incomplete result when its frontier is empty without a global certificate, and SICSP-Pref has a one-tick difference between its scalar route replay and composed arrival profile. The local final NY/FLA/CAL experiment artifacts also identify dirty source trees from older revisions; PACE source changed between those revisions and this audit. Full-network results therefore are not traceable to the audited implementation. The preference-aware experiment matrix also omits iSCOPE from E03, and the experiment registry has no THop or native-SICSP adapter.

Do not begin M6 or M7 until the P1 findings are corrected or bounded and the affected acceptance gates are rerun.

## Repository baseline and inventory

The worktree was clean at the start of M5. The audit added one independent tiny-query test in IScopeAlgorithmTest; no production algorithm code or existing data/result files were edited. At audit start, HEAD was 508a1d8 on feature/icde27-baseline-implementation, with origin configured as https://github.com/kousik-kr/I-PC-Max.git and no upstream branch configured. The THop repair commit c1d92d3 is an ancestor of HEAD.

The Java 21 source inventory contains 213 production files and 72 Java test files:

| Area | Files | Responsibility |
|---|---:|---|
| core/pcmax | 72 | PACE-X/PACE-B candidate generation, temporal replay, envelopes, caps and work accounting |
| baselines/sicsp | 26 | Native SICSP event search, O/W candidates, preference adaptation and outputs |
| baselines/exact | 5 | Rational temporal domain, affine segments, profiles and envelopes for THop |
| baselines (other) | 10 | THop, partition metadata, functional search and temporal profile adapters |
| core/profile, labeling, index, graph, function, loader, validate, cache | 45 | Shared graph, temporal, path, query and cache infrastructure |
| experiments, algorithms, framework, querygen | 51 | CLI/registry, experiment adapters, measurement and query generation |
| casestudy/nyc | 4 | NYC case-study path |

There are 51 experiment configuration files, 29 pre-existing tracked documentation files, and 94 Python/shell tooling files present under experiments/scripts and scripts (51 are tracked). The inventory includes the Maven build, CLI runners, dataset loaders, manifests/configuration schemas, plotting/packaging code and artifact-handling scripts. Maven compiled all 213 production and all 72 test source files.

At the initial audit, the worktree had 4.1 GB under ignored `data/`, and `experiments/results/` was ignored. The large-run ledgers and raw results were local-only. The initial inventory did not find the iSCOPE PDF; M5-R later found and read `Baseline papar/WISE 2025.pdf`, SHA-256 `50016f19605f7e32dc6de9c3a5e2e684e5131937497ca7d5f451057fecdceffc`. Existing SICSP and THop audits and paper-to-code notes were read alongside the source.

## Findings

### P1-A — PACE-X incomplete empty frontier threw (fixed in M5-R R1)

**Component:** PACE-X status and completion reporting.
**Evidence:** ForwardLayeredFrontierGenerator.completion() assigns NO_FEASIBLE_PATH to every empty PACE-X frontier (lines 889–904). Its exactness() can correctly return RETAINED_FRONTIER when pivot/depth conditions are incomplete (lines 906–925). PaceGenerationResult rejects NO_FEASIBLE_PATH unless exactness is GLOBAL_CERTIFIED (lines 34–38). The two paths conflict.

**Reproduction:** On a three-node chain 1→2→3 with constant travel time 1 and score 7 on both arcs, query source 1, destination 3, departure [0,5], budget 5, and PACE-X theta=0, the feasible route exists but the generator throws:
IllegalArgumentException: NO_FEASIBLE_PATH requires independent global certification.

The baseline run did not return a false NO_PATH record; it failed before returning a query result. M5-R adds `PaceGenerationOutcome.resolve` and `PaceCompletionInvariantTest`: incomplete empty PACE-X output now returns `UNRESOLVED_NO_GENERATED_CANDIDATE / NOT_CERTIFIED`; `NO_FEASIBLE_PATH` requires a complete, uncapped global certificate. The red and green commands and state table are in `repairs/PACE_X_COMPLETION_INVARIANT.md`.

### P1-B — SICSP-Pref composed arrival differs from scalar replay by one temporal tick

**Component:** SICSP-Pref exact path profile construction over the legacy temporal model.
**Evidence:** SicspPreferenceEngineTest.rootRoundingRemainsAOneTickDocumentedBoundaryCase() (lines 167–187) uses the fractional root 6.666666666667. Scalar replay returns prefix arrival 20.000000000001 and final arrival 60.000000000001, while the composed profile reports 60.0 at that same departure. The profile score there is 10.

The initial fixture established a profile/replay disagreement of 10^-12 minute. M5-R's independent tests expanded the impact: a tiny graph produces a wrong winning route/score, and another admits a departure whose independently replayed travel time exceeds the budget. The SICSP-Pref adapter now marks output `NOT_CERTIFIED`; the numerical repair remains blocked pending a complete common API or separately reviewed exact mode. No tolerance was added. See `repairs/SICSP_PREF_TEMPORAL_CORRECTNESS.md`.

### P1-C — Final large-network results are not tied to the audited source tree

The local experiments/results/Final-result snapshot and manuscript artifacts postdate the July 30 readiness reports and support the general NY/FLA/CAL comparison claims; the July report's 3/20 pilot must not be presented as the latest result. The snapshot index reports 15,000 PACE-B terminal rows for NY, 12,743 completed plus 2,257 timeouts for FLA, and 15,000 completed for CAL; iSCOPE rows are reported as time-capped. The manuscript values are also present in the local manuscript artifacts.

However, the dataset provenance records dirty working trees and different commits: NY ed0eef27, FLA eebb1f79, CAL d4f740a8. The run tree hashes are recorded, but the corresponding patches/source trees are not archived in Git. PACE production source differs between d4f740a8 and audited HEAD in nine files, including BoundedConnectorGenerator, ForwardLayeredFrontierGenerator, PACE, PaceGenerationResult and PaceOptions. The NY and FLA copies in Final-result lack markers/main.complete.json, while CAL has one. Thus the manuscript results may describe real completed subsets, but the audit cannot reproduce or attribute them to the source under review. This blocks claims about the current implementation and is an experiment-integrity P1.

M5-R revalidated the historical raw-tree identities and manuscript aggregation without writing to the old result directories. The dirty source/JAR lineage remains unrecovered; no historical marker was created or replaced. Separate unapproved source/JAR/config/data/query freezes now define prospective runs, and the manuscript table/figure remain replacement obligations. Do not attribute historic rows to that new source identity.

### P2-A — Mandatory adapters/configuration were missing at initial audit (implemented in M5-R)

At the initial audit, AlgorithmRegistry lacked `thop-partitioned` and `sicsp-native` adapters. M5-R registers both, adds objective-separated experiment outputs and native Figure 1 weights/Delta inputs, and verifies both with tiny CLI tests.

The initial E03 matrix omitted iSCOPE and the old T03 comparison omitted SICSP-Pref/THop. The versioned `icde2027_v1` family now supplies preference, fastest-profile, tractable reference, E03/T03 sensitivity, and native Figure 1 specifications. THop remains in a separate fastest-profile track. These configurations are identity-frozen but unapproved for execution; real-network THop partition/index artifacts are still absent.

### P2-B — iSCOPE source-paper fidelity is reviewed and differs materially

The registered iSCOPE implementation is a five-second anytime profile search. It computes lower bounds, streams vertex-simple source/destination paths, builds exact profiles per completed route, commits a score envelope incrementally and reports capped results as NOT_CERTIFIED. The new independent test checks route/no-path agreement with the tiny continuous oracle at segment endpoints and midpoints.

M5-R read the full 12-page WISE 2025 paper and added a complete-cell independent oracle. The current one-thread continuous-profile DFS remains a material deviation from the paper's optimized interval-best parallel implementation. The deviation and its cap/thread semantics are documented in `repairs/ISCOPE_FIDELITY_AND_ORACLE.md`; no paper-faithfulness claim is made. The shared fractional-root numerical issue remains.

### P2-C — Large experiment artifacts are outside Git and partially inconsistent

The 4.1 GB input data and experiments/results tree are ignored. The final snapshot index, raw rows, manuscript aggregation scripts and environment records are local-only. NY and FLA lack their main-complete markers in the snapshot inspected, although their progress files contain terminal rows. CAL has the marker. This is insufficiently reproducible without the source/config/raw-row identity bundle. Preserve the existing files and add a compact tracked release manifest with hashes, code revision and the exact aggregation command once provenance is repaired.

## Algorithm audit summary

| Component | What source and independent evidence establish | Limitation/finding |
|---|---|---|
| PACE-X | The public path profile is replayed using edge functions; score is accumulated at edge-entry time; the route checker enforces vertex simplicity; budget support is represented continuously in the legacy model. Certification requires no caps, every score-relevant pivot selected, and theta at least the selected pivot count. TinyContinuousEnvelopeOracle independently enumerates simple routes and uses rational temporal replay; the PACE-X differential suite includes 1,000 fixed-seed tiny FIFO DAGs with parallel arcs and handcrafted boundary/switch/tie cases. | R1 fixes P1-A status exception. “PACE-X” remains globally exact only under explicit certificate conditions. Oracle coverage does not prove arbitrary graph correctness or that the 12-decimal model equals real arithmetic. |
| PACE-B | Uses bounded pivot selection, connector limits, bounded frontier/work/breakpoint controls and deterministic connector scheduling. Selected pivots are excluded from connector paths; unselected score-bearing edges remain eligible. Paths are canonically replayed and validated before envelope insertion. Cap/completion scope is exposed as bounded/retained or not certified. Tiny public API tests and the existing exact-small quality harness provide useful checks. | No global optimality claim. Quality depends on selected pivots, connector schedule, frontier retention and limits. Existing paper data is not version-pinned to audited source (P1-C). |
| iSCOPE | Registered as iscope; five-second default and config validation exist. Completed simple paths are profiled and inserted incrementally. Whole-cell oracle checks compare path, score, arrival function, and endpoint ownership. | Full paper is now read. The current implementation materially deviates from the paper's optimized parallel algorithm; it inherits the shared temporal arithmetic limit and historical runs remain source-unbound. |
| Native SICSP | Source follows Yuan et al.'s O-point event structure: travel breakpoints, reverse LST, forward EAT, event path reconstruction and native accumulated-weight feasibility. Tests compare tiny cases to an independent vertex-simple route oracle and include the paper Figure 1, ties, plateaus, repeated-vertex witness and a globally feasible route missed by the heuristic. A separate native weight/Delta CLI adapter is registered. | The published method is a heuristic and the oracle confirms it can miss feasible/simple paths; it must not be called globally complete. Wgh details are prose-specified, not pseudocode; inference ledger is in the traceability report. |
| SICSP-Pref | Reuses native event generation; substitutes preference score breakpoints for weight events; does not add preference-guided LST/EAT searches. It replays score at actual edge-entry times, applies vertex-simple filtering after generation, and reports raw, simple, retained and feasible candidate sets separately. | P1-B is open: wrong winner/score and false budget feasibility now have independent fixtures. The adapter marks output NOT_CERTIFIED; no retained-frontier correctness claim is made. Heuristic candidates can also miss the global optimum. |
| THop | Direct functional search, unpruned and pruned indexes, boundary index and partitioned queries use exact rational profiles internally after graph input canonicalization. Tiny all-pairs tests compare to a separate rational simple-route oracle; tests cover routes leaving/re-entering partitions and exact v2 save/reload. BTHP/PTHP reject unsupported versions. A fastest-profile experiment adapter is registered. | Exactness applies to decimal-canonical graph inputs. Partition inner construction is search-first/prune-second, not the paper's incremental search-prune schedule; its memory/time behavior and construction bound are not proven paper-identical. No real-network partition/index files are materialized. THop solves fastest-arrival profiles, not preference maximization or PACE budget feasibility. |

## Shared temporal contract

Domain canonicalizes temporal values to a 12-decimal tick grid using decimal HALF_EVEN conversion. PiecewiseLinearFn canonicalizes input time, travel-time output and arrival sum. This is a finite-precision, per-operation model for PACE and SICSP. It is not arbitrary real arithmetic. Equality, endpoint ownership, budget equality and score-step transitions must be interpreted under that model.

THop's ExactTime and ExactArrivalProfile use rational arithmetic for composition, restriction, preimages, crossings, dominance and stored labels after graph input values have been canonicalized. Rounded compatibility accessors exist; exact accessors are authoritative for the index. The THop source does not make PACE or SICSP exact by association.

SICSP-Pref's fractional-root fixtures show a concrete profile value disagreement, wrong winner, and false feasibility. The initial PACE-X status failure was independent of numeric rounding and is now fixed by R1. No global change to shared numerical behavior was made.

TDGraph stores directed incoming/outgoing adjacency and stable arc ordering. GraphValidator checks consecutive arc IDs, endpoint membership and FIFO by default; non-FIFO counting is available separately. Domain rejects times outside its signed tick range. Path IDs use stable arc sequences, envelope tie ordering is deterministic, and cache/index identities include temporal or graph fingerprints. These structural checks and focused tests passed; they do not resolve the two algorithm findings above.

## Validation performed

Environment: OpenJDK 21.0.7, Maven 3.6.3, compiler release 21.

| Command/evidence | Result |
|---|---|
| Clean-checkout Maven run at audited HEAD before adding the oracle test | 308 tests, 0 failures/errors/skips |
| Current full clean Maven build after adding the oracle test: mvn -B -ntp clean test | BUILD SUCCESS; 309 tests, 0 failures/errors/skips; 21.801 s; 2026-10-16T01:31:48Z |
| Targeted iSCOPE test run | 9 tests, 0 failures/errors/skips |
| Python experiment test suite: python3 -m pytest -q experiments/tests | 39 passed in 35.05 s |
| Independent PACE-X differential evidence | 1,000 fixed-seed tiny FIFO DAG cases plus hand-authored cases, no reported mismatches |
| THop independent evidence | Full source-destination pairs on tiny graphs for direct search, unpruned/pruned THop and partitioned THop; every boundary pair for Boundary-THop |
| SICSP native evidence | Tiny independent exhaustive simple-route oracle, including a documented global heuristic miss |
| Large NY/FLA/CAL campaigns | Not run during M5 |

The prior M2 THop development run with an explicitly unsupported composition error predates c1d92d3. The current full suite is after the exact rational repair and passes.

Complete Maven terminal summary:

    [INFO] Results:
    [INFO] Tests run: 309, Failures: 0, Errors: 0, Skipped: 0
    [INFO] BUILD SUCCESS
    [INFO] Total time: 21.801 s
    [INFO] Finished at: 2026-10-16T01:31:48Z

## Initial M5 acceptance matrix (superseded by the M5-R status above)

| Gate | Status | Reason |
|---|---|---|
| G1 PACE-X independently verified | **FAIL** | Tiny oracle evidence passes, but incomplete empty frontier throws instead of returning a non-certified result. |
| G2 PACE-B independently verified | **PARTIAL** | Returned paths and bounded scope are tested; no global guarantee; current paper run lineage is not tied to audited code. |
| G3 iSCOPE independently verified | **PARTIAL** | New tiny oracle test and cap semantics pass; full source-paper fidelity unavailable. |
| G4 Native SICSP independently verified | **PARTIAL** | Tiny oracle and missed-path fixture exist; method is heuristic and no reproduction adapter exists. |
| G5 SICSP-Pref independently verified | **FAIL** | One-tick arrival profile discrepancy; generated-candidate envelope scope is explicit but global path completeness is absent. |
| G6 THop independently verified | **PASS WITH DEVIATION** | Exact rational tiny all-pairs oracle and v2 storage pass; construction schedule deviation is documented. |
| G7 Shared temporal semantics audited | **FAIL** | Legacy model is explicit, but SICSP-Pref has a demonstrated profile/replay boundary mismatch. |
| G8 Experiment registration/accounting | **FAIL** | Missing THop/native SICSP adapters; E03 omission of iSCOPE; final results use dirty older source revisions. |
| G9 Reproducible build and tests | **PASS** | Java 21/Maven build and current Java/Python suites pass. Data and result artifacts remain local-only. |
| G10 No unresolved P0/P1 | **FAIL** | P1-A, P1-B and P1-C remain. |

No P0 finding was established by the inspected code and artifacts. No separate P3 item was raised; the open items affect status correctness, temporal profile values, source fidelity or experiment reproducibility.

## Initial M5 corrective plan (superseded by the M5-R report)

1. Repair PACE-X completion/status mapping. Add regression for the feasible two-edge chain with insufficient theta. Assert unresolved/retained scope, and reserve NO_FEASIBLE_PATH for independently certified exhaustive empty results.
2. Reconcile SICSP-Pref profile composition with scalar replay at 6.666666666667. Extend the fixture across score steps, exact budget equality and route-envelope selection; establish equality under the declared temporal contract.
3. Freeze a clean source revision and JAR. Reconcile raw records with dataset/config checksums, create complete NY/FLA/CAL markers, archive the aggregation script and rerun only the bounded final-table extraction. If outputs differ, regenerate the affected study at the frozen revision.
4. Register a THop adapter that exposes fastest-profile objective and separates preprocessing/index-load/query timing. Add a native SICSP reproduction entry point with native weight semantics. Add iSCOPE to every relevant preference-aware matrix, including E03, and construct a unified PACE-B/iSCOPE/SICSP-Pref comparison configuration.
5. Record the iSCOPE adaptation against the full source paper. Expand the oracle comparison to check score and full profile intervals, not only route IDs at a few probes.
6. Rerun the full Java suite, Python suite, and small runner smoke checks. Re-evaluate G1–G10 before M6.

No full-network campaign, merge or algorithm rewrite was started during M5.
