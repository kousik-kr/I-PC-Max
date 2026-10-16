# ICDE 2027 Full Repository Audit

Date: 2026-10-16 UTC
Audited source revision: 508a1d8cc422fd4595ad36857e51990fd86c562e
Branch: feature/icde27-baseline-implementation
Required THop repair ancestor: c1d92d3105bc536cea97722007cd1f6f24ffa1c6
Final classification: **NOT READY**

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

The working tree has 4.1 GB under data/, which is ignored by Git. Experiments/results/ is also ignored. The repository tracks only seven files under results and experiments/results, mostly placeholders and visualization examples; the local large-run ledgers and raw results are not versioned. Two local PDFs are present in Baseline papar/: Yuan et al. on SICSP and Li, Wang and Zhou on THop. The original iSCOPE paper PDF was not present. Existing SICSP and THop audits and paper-to-code notes were read alongside the source.

## Findings

### P1-A — PACE-X incomplete empty frontier throws instead of returning an uncertified result

**Component:** PACE-X status and completion reporting.
**Evidence:** ForwardLayeredFrontierGenerator.completion() assigns NO_FEASIBLE_PATH to every empty PACE-X frontier (lines 889–904). Its exactness() can correctly return RETAINED_FRONTIER when pivot/depth conditions are incomplete (lines 906–925). PaceGenerationResult rejects NO_FEASIBLE_PATH unless exactness is GLOBAL_CERTIFIED (lines 34–38). The two paths conflict.

**Reproduction:** On a three-node chain 1→2→3 with constant travel time 1 and score 7 on both arcs, query source 1, destination 3, departure [0,5], budget 5, and PACE-X theta=0, the feasible route exists but the generator throws:
IllegalArgumentException: NO_FEASIBLE_PATH requires independent global certification.

The run does not return a false NO_PATH record; it fails before returning a query result. The contract requires an unresolved or retained-frontier result whenever generation is incomplete. Reserve NO_FEASIBLE_PATH for a complete, uncapped global certificate. Add a regression that asserts status/scope and compare the returned coverage to the independent oracle.

### P1-B — SICSP-Pref composed arrival differs from scalar replay by one temporal tick

**Component:** SICSP-Pref exact path profile construction over the legacy temporal model.
**Evidence:** SicspPreferenceEngineTest.rootRoundingRemainsAOneTickDocumentedBoundaryCase() (lines 167–187) uses the fractional root 6.666666666667. Scalar replay returns prefix arrival 20.000000000001 and final arrival 60.000000000001, while the composed profile reports 60.0 at that same departure. The profile score there is 10.

This establishes a profile/replay disagreement of 10^-12 minute. The checked fixture does not establish a changed winning route or budget decision, so the demonstrated impact is a wrong profile value at a boundary; a changed feasibility or winner is a risk to test, not a proven outcome. A boundary error is P1 under the M5 rubric. Fixing shared arithmetic globally is out of scope for this audit. Before M6, reconcile profile composition and replay, then test exact budget equality, score-step ownership, route selection and profile support on the fractional-root fixture.

### P1-C — Final large-network results are not tied to the audited source tree

The local experiments/results/Final-result snapshot and manuscript artifacts postdate the July 30 readiness reports and support the general NY/FLA/CAL comparison claims; the July report's 3/20 pilot must not be presented as the latest result. The snapshot index reports 15,000 PACE-B terminal rows for NY, 12,743 completed plus 2,257 timeouts for FLA, and 15,000 completed for CAL; iSCOPE rows are reported as time-capped. The manuscript values are also present in the local manuscript artifacts.

However, the dataset provenance records dirty working trees and different commits: NY ed0eef27, FLA eebb1f79, CAL d4f740a8. The run tree hashes are recorded, but the corresponding patches/source trees are not archived in Git. PACE production source differs between d4f740a8 and audited HEAD in nine files, including BoundedConnectorGenerator, ForwardLayeredFrontierGenerator, PACE, PaceGenerationResult and PaceOptions. The NY and FLA copies in Final-result lack markers/main.complete.json, while CAL has one. Thus the manuscript results may describe real completed subsets, but the audit cannot reproduce or attribute them to the source under review. This blocks claims about the current implementation and is an experiment-integrity P1.

Corrective action: freeze a clean source commit, rebuild the benchmark JAR from it, rerun a bounded identity/checksum validation on the existing raw records, and either reproduce the manuscript aggregate on that commit or regenerate the comparison. Preserve timeout rows and keep them out of completed-only runtime and score summaries. Add a final marker and archived manifest/source identity for each dataset. Do not run the full campaign as part of M5.

### P2-A — Mandatory adapters/configuration are missing from relevant experiment paths

AlgorithmRegistry registers pace-x, pace-b, iscope, sicsp-pref, exh-profile, pl-exact, rpq, ksp-profile, interval-best and allfp. It does not register thop-partitioned or sicsp-native. Native SICSP has direct engine tests, but no separate runner/CLI reproduction entry point. THop has a full index implementation and tests but no experiment adapter.

The E03 main preference-aware matrix omits iSCOPE. The newer t03_scalability_main_5s.yaml includes iSCOPE, but the legacy t03_scalability_main.yaml omits it. SICSP-Pref and THop are absent from the T03 comparison configuration required for the next full comparison. This fails the experiment-registration acceptance gate and makes baseline inclusion dependent on choosing the special configuration. Add explicit configurations and adapters with objective-specific metrics before a paper matrix is frozen.

### P2-B — iSCOPE source-paper fidelity cannot be fully verified

The registered iSCOPE implementation is a five-second anytime profile search. It computes lower bounds, streams vertex-simple source/destination paths, builds exact profiles per completed route, commits a score envelope incrementally and reports capped results as NOT_CERTIFIED. The new independent test checks route/no-path agreement with the tiny continuous oracle at segment endpoints and midpoints.

The original WISE 2025 chapter is identifiable, but its full text was not locally available; the publisher page exposes only bibliographic details and an abstract. The profile-oriented five-second path-enumeration adaptation therefore cannot be checked against the complete original algorithm specification. Document the adaptation and obtain full-text review before making a paper-faithfulness claim. The added test compares feasibility and route IDs at probes; it does not prove whole-interval score-profile equality by itself.

### P2-C — Large experiment artifacts are outside Git and partially inconsistent

The 4.1 GB input data and experiments/results tree are ignored. The final snapshot index, raw rows, manuscript aggregation scripts and environment records are local-only. NY and FLA lack their main-complete markers in the snapshot inspected, although their progress files contain terminal rows. CAL has the marker. This is insufficiently reproducible without the source/config/raw-row identity bundle. Preserve the existing files and add a compact tracked release manifest with hashes, code revision and the exact aggregation command once provenance is repaired.

## Algorithm audit summary

| Component | What source and independent evidence establish | Limitation/finding |
|---|---|---|
| PACE-X | The public path profile is replayed using edge functions; score is accumulated at edge-entry time; the route checker enforces vertex simplicity; budget support is represented continuously in the legacy model. Certification requires no caps, every score-relevant pivot selected, and theta at least the selected pivot count. TinyContinuousEnvelopeOracle independently enumerates simple routes and uses rational temporal replay; the PACE-X differential suite includes 1,000 fixed-seed tiny FIFO DAGs with parallel arcs and handcrafted boundary/switch/tie cases. | P1-A status exception. “PACE-X” is only globally exact when the explicit certificate conditions hold. Oracle coverage does not prove arbitrary graph correctness or the 12-decimal model equals real arithmetic. |
| PACE-B | Uses bounded pivot selection, connector limits, bounded frontier/work/breakpoint controls and deterministic connector scheduling. Selected pivots are excluded from connector paths; unselected score-bearing edges remain eligible. Paths are canonically replayed and validated before envelope insertion. Cap/completion scope is exposed as bounded/retained or not certified. Tiny public API tests and the existing exact-small quality harness provide useful checks. | No global optimality claim. Quality depends on selected pivots, connector schedule, frontier retention and limits. Existing paper data is not version-pinned to audited source (P1-C). |
| iSCOPE | Registered as iscope; five-second default and config validation exist. Completed simple paths are profiled and inserted incrementally. Time/path caps produce NOT_CERTIFIED. A new tiny oracle comparison is included. | Full source paper not available locally; one new graph is only endpoint/midpoint route testing; experiment runs were made from dirty older trees. |
| Native SICSP | Source follows Yuan et al.'s O-point event structure: travel breakpoints, reverse LST, forward EAT, event path reconstruction and native accumulated-weight feasibility. Tests compare tiny cases to an independent vertex-simple route oracle and include the paper Figure 1, ties, plateaus, repeated-vertex witness and a globally feasible route missed by the heuristic. | The published method is a heuristic and the oracle confirms it can miss feasible/simple paths; it must not be called globally complete. No experiment adapter. Wgh details are prose-specified, not pseudocode; inference ledger is in the traceability report. |
| SICSP-Pref | Reuses native event generation; substitutes preference score breakpoints for weight events; does not add preference-guided LST/EAT searches. It replays score at actual edge-entry times, applies vertex-simple filtering after generation, and reports raw, simple, retained and feasible candidate sets separately. Its envelope is exact only over retained generated candidates. | P1-B one-tick profile/replay disagreement. Heuristic candidates can miss the global constrained-preference optimum. Exactness scope is retained candidates, not all graph paths. |
| THop | Direct functional search, unpruned and pruned indexes, boundary index and partitioned queries use exact rational profiles internally after graph input canonicalization. Tiny all-pairs tests compare to a separate rational simple-route oracle; tests cover routes leaving/re-entering partitions and exact v2 save/reload. BTHP/PTHP reject unsupported versions. | Exactness applies to decimal-canonical graph inputs. Partition inner construction is search-first/prune-second, not the paper's incremental search-prune schedule; its memory/time behavior and construction bound are not proven paper-identical. THop solves fastest-arrival profiles, not preference maximization or PACE budget feasibility. No experiment adapter. |

## Shared temporal contract

Domain canonicalizes temporal values to a 12-decimal tick grid using decimal HALF_EVEN conversion. PiecewiseLinearFn canonicalizes input time, travel-time output and arrival sum. This is a finite-precision, per-operation model for PACE and SICSP. It is not arbitrary real arithmetic. Equality, endpoint ownership, budget equality and score-step transitions must be interpreted under that model.

THop's ExactTime and ExactArrivalProfile use rational arithmetic for composition, restriction, preimages, crossings, dominance and stored labels after graph input values have been canonicalized. Rounded compatibility accessors exist; exact accessors are authoritative for the index. The THop source does not make PACE or SICSP exact by association.

SICSP-Pref's one-tick fixture shows a concrete profile value disagreement. The PACE-X status failure is independent of numeric rounding. No global change to shared numerical behavior was made.

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

## Acceptance matrix

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

## Corrective-action plan and release gate

1. Repair PACE-X completion/status mapping. Add regression for the feasible two-edge chain with insufficient theta. Assert unresolved/retained scope, and reserve NO_FEASIBLE_PATH for independently certified exhaustive empty results.
2. Reconcile SICSP-Pref profile composition with scalar replay at 6.666666666667. Extend the fixture across score steps, exact budget equality and route-envelope selection; establish equality under the declared temporal contract.
3. Freeze a clean source revision and JAR. Reconcile raw records with dataset/config checksums, create complete NY/FLA/CAL markers, archive the aggregation script and rerun only the bounded final-table extraction. If outputs differ, regenerate the affected study at the frozen revision.
4. Register a THop adapter that exposes fastest-profile objective and separates preprocessing/index-load/query timing. Add a native SICSP reproduction entry point with native weight semantics. Add iSCOPE to every relevant preference-aware matrix, including E03, and construct a unified PACE-B/iSCOPE/SICSP-Pref comparison configuration.
5. Record the iSCOPE adaptation against the full source paper. Expand the oracle comparison to check score and full profile intervals, not only route IDs at a few probes.
6. Rerun the full Java suite, Python suite, and small runner smoke checks. Re-evaluate G1–G10 before M6.

No full-network campaign, merge or algorithm rewrite was started during M5.
