# ICDE 2027 M5-R repair and release-gate report

Date: 2026-10-16. Branch: `feature/icde27-baseline-implementation`. Audited starting revision: `508a1d8cc422fd4595ad36857e51990fd86c562e`; implementation baseline at M5-R start: `0d0fa20c8b979b1db9916d5489649d73e8cc4993`.

## Executive result

M5-R repairs the PACE-X status invariant, adds independent whole-profile iSCOPE checks and fidelity disclosures, registers native SICSP and exact-rational partitioned THop experiment adapters, adds prospective objective-specific configurations and a fail-closed source/JAR/input freeze, corrects capped-run accounting, and reaggregates and quarantines historical results.

The SICSP-Pref temporal defect is **not fixed**. Independent fixtures now show both a wrong selected score/path and a false feasible budget boundary. The adapter reports `NOT_CERTIFIED`, and the detailed arithmetic blocker is recorded in [SICSP_PREF_TEMPORAL_CORRECTNESS.md](repairs/SICSP_PREF_TEMPORAL_CORRECTNESS.md). The historical source revisions also remain unrecovered. The repository is **NOT READY; M6 PILOT BLOCKED**.

No NY/FLA/CAL performance campaign, THop network index build, merge, or force-push was performed. The only executions beyond the test suites were tiny/synthetic CLI checks. Both benchmark freeze records default to `readiness.approved=false`.

## Baseline and final validation

The initial manifest is [`m5r_initial_manifest_20261016.json`](repairs/evidence/m5r_initial_manifest_20261016.json). At baseline `0d0fa20`, the worktree was clean; Java 21.0.7, Maven 3.6.3, and Python 3.10.12 were available; 213 production Java files and 72 Java test files were present. The local papers were preserved and hashed. The historical baseline was 309 Java tests and 39 Python tests passing.

Final verification on the repaired worktree:

| Command | Observed result |
|---|---|
| `mvn -B -ntp clean test` | 350 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS; 25.869 s; log `/tmp/m5r-final-java.log` |
| `python3 -m pytest -q experiments/tests` | 50 passed in 32.49 s; log `/tmp/m5r-final-python.log` |
| SICSP/native focused: `mvn -B -ntp -Dtest=SicspPrefTemporalContractTest,SicspPreferenceEngineTest,SicspNativeEngineTest,ExperimentAlgorithmsTest test` | 41 passed, 0 failures/errors/skips; 2.499 s; includes the unchanged native SICSP engine suite |
| R5/R6 focused: `python3 -m pytest -q experiments/tests/test_icde2027_config.py experiments/tests/test_benchmark_freeze.py experiments/tests/test_historical_provenance.py experiments/tests/test_objective_adapters.py` | 11 passed |
| `git diff --check` | Passed for the final source and documentation changes |
| Clean source JAR build: `mvn -B -ntp -DskipTests package` | BUILD SUCCESS in 8.641 s; only Maven Shade overlapping-resource warnings; JAR SHA-256 recorded below |

Tiny CLI smoke on that JAR covered all six registered methods. Each emitted the expected number of terminal rows:

| Method | Tiny CLI input | Rows and observed statuses |
|---|---|---|
| PACE-X | `demo`, `tiny.jsonl`, `theta=4` | 3; COMPLETED and NO_FEASIBLE_PATH |
| PACE-B | `demo`, `tiny.jsonl` | 3; COMPLETED and UNRESOLVED_NO_GENERATED_CANDIDATE |
| iSCOPE | `demo`, `tiny.jsonl`, 5 s | 3; CERTIFIED_COMPLETE and NO_FEASIBLE_PATH |
| SICSP-Pref | `demo`, `tiny.jsonl` | 3; COMPLETED and UNRESOLVED_NO_GENERATED_CANDIDATE; output exactness remains NOT_CERTIFIED |
| Native SICSP | separate Figure 1 fixture, native arc weights, `Delta=80` | 1; COMPLETED, NOT_CERTIFIED heuristic scope |
| THop-Partitioned | `demo`, tiny partition fixture, local temporary v2 index build | 3; COMPLETED and NO_FEASIBLE_PATH, exact rational fastest-profile output |

The PACE-X and SICSP smoke results with `NO_FEASIBLE_PATH` came from the tiny manifest's deliberately unreachable query. The native fixture preserves its original weight objective; THop ignores preference scores. No large graph query was started.

## Gate-by-gate reassessment

| Gate | Status | Defect and source reviewed | Repair or evidence | Regression command and observed output | Independent evidence and remaining limit |
|---|---|---|---|---|---|
| **G1 PACE-X** | **PARTIAL** | `ForwardLayeredFrontierGenerator`, `PaceGenerationResult`, completion/exactness mapping. An empty but incomplete frontier was mislabeled `NO_FEASIBLE_PATH`, conflicting with its uncertified scope and throwing. | Added one outcome resolver from raw generation/cap/coverage facts; empty incomplete X output is unresolved. Candidate search/arithmetic was not changed. | `mvn -B -ntp -Dtest=PaceCompletionInvariantTest,ScalablePaceCandidateEngineTest,PacePublicApiOracleIntegrationTest,PaceExactOracleDifferentialTest,ExperimentAlgorithmsTest,PaceBenchFrameworkTest test` on a clean baseline clone with only R1 files: 60 passed. The original depth-0 feasible-chain exception is saved in `/tmp/m5r-r1-r4-red.log`; green output `/tmp/m5r-r1-green.log`. | Incomplete empty frontier returns UNRESOLVED/NOT_CERTIFIED; complete `theta=2` returns the expected route and matches the independent tiny oracle. Empty global output remains `NO_FEASIBLE_PATH` only with complete coverage. Shared legacy temporal arithmetic still limits broad real-time claims. |
| **G2 PACE-B** | **PARTIAL** | `PACE`, `BoundedConnectorGenerator`, `ForwardLayeredFrontierGenerator`, `PaceBench`; retained/bounded search behavior and the 1,000-case oracle suite reviewed. | No search rewrite. R1 shares only the new completion/scope resolver; cap behavior and bounded candidate policy are preserved. | Included in the R1 clean-clone command above and final `mvn -B -ntp clean test`: 350 passed. | Existing fixed-seed rational oracle, candidate validity, work-cap and retained-frontier tests pass. PACE-B remains a bounded candidate policy without a global optimum guarantee; historical performance rows do not identify its exact source. |
| **G3 iSCOPE** | **PARTIAL** | `IScopeAlgorithm`, path search, deadline and `TinyContinuousEnvelopeOracle`; full WISE paper was read from local PDF. | Added complete temporal-cell oracle comparisons, deadline/overrun metadata, and explicit source fidelity/numerical-contract metadata. Search method is preserved and labeled a material deviation from optimized paper iSCOPE. | `mvn -B -ntp -Dtest=IScopeAlgorithmTest,IScopeWholeProfileOracleTest test`: 20 focused tests passed in the R3 run; final suite includes 11 adapter and 9 whole-profile tests, all passed. | Independent whole-cell checks cover endpoints, score, path, affine arrival, NO_PATH, and 40 fixed-seed DAGs. Full paper is locally available (SHA-256 `50016f19605f7e32dc6de9c3a5e2e684e5131937497ca7d5f451057fecdceffc`). Current DFS/profile implementation does not reproduce the paper's Opt1/Opt2, randomized interval-best/parallel work-stealing method. It inherits the fractional-root shared arithmetic limit. |
| **G4 Native SICSP** | **PARTIAL** | `SicspNativeEngine`, LST/EAT candidate generation, O/W event construction, independent route replay and weight semantics reviewed. | Added `sicsp-native` registry/CLI adapter with required per-arc weight file and required native `Delta`; added original Figure 1 fixture. No preference score is substituted for native weights. | `mvn -B -ntp -Dtest=SicspNativeEngineTest,ObjectiveAdaptersTest,ObjectiveAdapterCliTest test`; covered by final Java 350-test pass. Tiny CLI reproduced Figure 1 with `Delta=80`, one completed result and heuristic `NOT_CERTIFIED` scope. | Native tests and independent tiny route checks pass; known heuristic miss is retained. The original native LST/EAT method is intentionally heuristic, so it is not a global optimum certificate. |
| **G5 SICSP-Pref** | **FAIL** | `SicspPreferenceEngine`, scalar route replay, PWL profile composition, score/budget boundary ownership, retained envelope, exact rational API and independent oracle reviewed. | Numerical repair was not safe within this API. Adapter metadata now downgrades all output to `NOT_CERTIFIED` and records P1-B; independent red tests were preserved. No search path changed. | Before downgrade, `/tmp/m5r-r2-reporting-red.log` showed expected NOT_CERTIFIED but actual RETAINED_FRONTIER. Afterward, `mvn -B -ntp -Dtest=SicspPrefTemporalContractTest,SicspPreferenceEngineTest,SicspNativeEngineTest,ExperimentAlgorithmsTest test`: 41 passed. | At `6.666666666667`, scalar replay ends at `60.000000000001` while composed profile gives `60.0`. Separate tiny graph reports chain score 20 instead of independent scalar/oracle winner score 15; separate `B=21` graph includes an infeasible departure with actual travel `21.000000000001`. Tests prove and disclose the defect; they do not repair it. Common exact score, budget, envelope, and serialization APIs are needed. |
| **G6 THop** | **PASS WITH DOCUMENTED DEVIATION** | `PartitionedTHopIndex`, `BoundaryTHopIndex`, exact rational profile/index serialization and objective adapter reviewed against local THop paper. | Added partitioned fastest-profile experiment adapter, build/load lifecycle, graph/config identities, and exact rational objective output. Search-first then prune/materialize schedule is explicitly reported. | `mvn -B -ntp -Dtest=THopIndexTest,BoundaryTHopIndexTest,PartitionedTHopIndexTest,ObjectiveAdaptersTest,ObjectiveAdapterCliTest test`; final suite passed. Tiny CLI built and queried only the synthetic `thop_demo` partition fixture. | Exact rational all-pairs tiny oracle, partition shortcuts and v2 save/reload tests pass. The current schedule differs from the paper's incremental search/prune order; it does not earn the paper's search-space or working-set construction bounds. No NY/FLA/CAL partition/index is present, so no large-index readiness is claimed. |
| **G7 Shared temporal model** | **FAIL** | Legacy scalar replay, PWL composition, `Domain`, score steps, and THop rational model reviewed. | No global arithmetic migration. Numerical contracts are explicit per method, and SICSP-Pref is downgraded. | Fractional-root test in `SicspPrefTemporalContractTest`; nine new SICSP-Pref temporal tests pass while retaining the concrete disagreement. The full Java suite also passes because the failure is now an asserted/documented counterexample, not because arithmetic agrees. | Exact rational arrivals and legacy scalar/profile composition remain different models. The wrong winner and false budget feasibility disprove a blanket shared-model correctness claim. |
| **G8 Experiment registration/accounting** | **PASS** | `AlgorithmRegistry`, `AlgorithmConfig`, `PaceBench`, `execute_matrix.py`, config loader, runner schema, objective adapters and lifecycle reviewed. | Added native/THop adapters, typed objective outputs, objective/config hashes, freeze guard, versioned configs, and a separate `terminal` flag. Capped statuses now set `completed=false`; PACE-B/iSCOPE/SICSP-Pref anytime outputs keep status and exactness separate. | `mvn -B -ntp -Dtest=ObjectiveAdapterRegistrationTest,ObjectiveAdaptersTest,ObjectiveAdapterCliTest,ExperimentCompletionAccountingTest test` and focused Python 11 tests passed; final suites passed. The accounting regression was red before the code change: capped row had `completed=true`, and ordinary rows lacked `terminal`. | Registry confirms all six IDs. Tiny command-line output identities and checksums were exercised. Historical-row reuse is disabled in `icde2027_v1`. Execution of the new preference campaign remains blocked by G5 and unapproved freeze status. |
| **G9 Reproducible build and test** | **PASS** | Maven/Java build, Python runner/config tests, freeze inputs and all method CLI adapters reviewed. | Captured initial inventory; built JAR from clean commit; created unapproved preference and reference source/JAR/data/query freezes. | `mvn -B -ntp clean test`: 350/350; `python3 -m pytest -q experiments/tests`: 50/50; clean-checkout `mvn -B -ntp -DskipTests package`: success; six tiny CLI smoke runs: all emitted expected rows/statuses; `git diff --check`: pass. | Source/JAR/config/input hashes are reproducible on this workspace. Data/query paths in freeze JSON are local absolute paths; a different host must provide identical bytes. THop fastest-profile index artifacts are absent. Historical dirty-run source is still not reproducible. |
| **G10 No unresolved benchmark-critical P0/P1** | **FAIL** | P1-B numerical counterexamples remain; P1-C historical source lineage remains unresolved for the published large-network table. | P1-A is fixed; P1-C is quarantined and excluded from prospective results. No successful numerical-repair claim is made for P1-B. | SICSP temporal counterexamples pass as expected regressions; full Java/Python suites pass. G10 is decided by correctness evidence, not by green status alone. | SICSP-Pref's wrong winner/score and false feasibility keep a required method benchmark-critical and uncertified. The old table is not attributable to clean code and must be replaced. |

## R1 — PACE-X status invariant

The original graph `1→2→3`, with two travel-1/score-7 arcs, query `[0,5]`, budget 5, and `theta=0`, previously caused `NO_FEASIBLE_PATH` / `RETAINED_FRONTIER` inconsistency and an exception. With incomplete depth/pivot coverage, the same query now returns `UNRESOLVED_NO_GENERATED_CANDIDATE` / `NOT_CERTIFIED`; a complete-depth run returns the route and a global certificate. The state table also covers feasible and empty complete/incomplete frontiers, caps, aborts, invalid query and unsupported horizon. `PaceGenerationOutcome` owns the paired status/scope mapping; no fake route was introduced.

## R2 — SICSP-Pref numerical disposition

The exact rational event is `20/3`, while the query receives the canonical decimal `6.666666666667`. Rational replay of that decimal produces first-edge exit `20.0000000000005` and final arrival `60.0000000000005`; legacy per-operation replay produces `20.000000000001` and `60.000000000001`; whole-interval PWL composition produces `60.0`. A second graph changes the winning path and score, and a third proves a false budget-feasible point. The independent switch-point test selects the direct route immediately before `20/3` and the chain at the exact event. Option C was selected: retain search and numerical behavior, make the scope `NOT_CERTIFIED`, and document the additional exact score-step, budget-domain, tie-envelope, and serialization API required for a complete opt-in rational mode. This does not claim the API work is mathematically impossible.

Native SICSP candidate generation and its regression suite are unchanged. `SicspPreferenceEngineTest.rootRoundingRemainsAOneTickDocumentedBoundaryCase` remains in place. Full trace and 17-case coverage notes are in [SICSP_PREF_TEMPORAL_CORRECTNESS.md](repairs/SICSP_PREF_TEMPORAL_CORRECTNESS.md).

## R3 — iSCOPE paper and implementation fidelity

The complete local WISE 2025 paper is `Baseline papar/WISE 2025.pdf`, 12 pages, SHA-256 `50016f19605f7e32dc6de9c3a5e2e684e5131937497ca7d5f451057fecdceffc`. The current code retains a basic vertex-simple route DFS and continuous-profile adaptation; it does not implement the paper's optimized partial-function recursion, Opt1 temporal splitting, Opt2 breakpoint merging/random score roulette, or parallel work stealing. The current variant is single-threaded with an absolute deadline, profile output, and an explicitly reported five-second cap. The whole-profile oracle compares paths, integer scores, affine arrival values and endpoint ownership over complete cells on positive representable fixtures. The separate fractional-root test prevents that positive corpus from being used to claim general arithmetic agreement. Full mapping is in [ISCOPE_FIDELITY_AND_ORACLE.md](repairs/ISCOPE_FIDELITY_AND_ORACLE.md).

## R4/R7 — objective-specific adapters and THop

Registry IDs are `pace-x`, `pace-b`, `iscope`, `sicsp-pref`, `sicsp-native`, and `thop-partitioned`. The primary prospective preference track contains PACE-B, iSCOPE and SICSP-Pref on matched workloads. THop is a separate fastest-profile track: its preference score and preference budget are ignored. The native SICSP reproduction requires its own per-arc weight JSON and `Delta`; the Figure 1 fixture uses `Delta=80`.

THop's exact rational profiles, partition graph identity, v2 serialization, save/reload and fastest-path objective pass tiny tests. Its code first searches candidate labels, then prunes/materializes, unlike the paper's incremental search/prune construction. This preserves verified label answers but changes search-space work, peak working memory and the applicability of paper construction bounds; build times are not paper-equivalent. No real-network THop indexes were built. Detailed adapter/fidelity reports are [EXPERIMENT_OBJECTIVE_ADAPTERS.md](repairs/EXPERIMENT_OBJECTIVE_ADAPTERS.md) and [THOP_FIDELITY_AND_INDEX_READINESS.md](repairs/THOP_FIDELITY_AND_INDEX_READINESS.md).

## R5 — configuration and capped-result accounting

The `icde2027_v1` family provides preference, fastest-profile, tractable reference, E03/T03 sensitivity, and native Figure 1 configurations. Preference comparison defaults specify PACE-B 10 s, iSCOPE 5 s, SICSP-Pref 10 s, one thread per query. The separate THop track is 10 s and reports preprocessing/load/cold/warm separately. The native fixture has a native weight bound of 80. These are future protocol decisions, not reconstructed historical caps.

`ExperimentStatus.completedExecution()` now returns false for `TIME_CAPPED_NOT_CERTIFIED` and `PATH_CAPPED_NOT_CERTIFIED`; `terminal=true` records that the query emitted an outcome row. `completed=true` remains for normal completed, certified complete, unresolved-after-configured-generation, and certified no-path outcomes. Result schema v3 accepts the new optional `terminal` property without invalidating prior rows. The direct regression produced `PATH_CAPPED_NOT_CERTIFIED` and asserts terminal/incomplete accounting.

## R6 — historical results and source lineage

The full T03 matrix contains 15,000 planned trials per method and dataset. Ledger-scoped counts are: PACE-B NY 15,000 completed; FLA 12,743 completed and 2,257 timeouts (84.9533%); CAL 15,000 completed. iSCOPE has 15,000 time-capped trials and zero completed trials per dataset. The manuscript slice is a separate 200-query/600-trial workload. Final revalidation compared `/tmp/icde2027-historical-revalidation.json` with the tracked attestation and reported `identical=true`, no row identity mismatches, and `historical_source_reconstructed=false`.

All six manuscript aggregate values were reproduced from the archived selector and aggregation functions. The score column is the median over logical queries of each query's median trial `average_selected_score`; it is not a mean. The archived PACE-B caps were NY/CAL 10 s and FLA fasttrack 5 s with 24 threads; iSCOPE used 5 s and one thread. The tracked manuscript corrects the label and cap/thread description, withholds the mislabeled archived plot, and marks the table/figure for replacement. NY/FLA/CAL source commits were `ed0eef27`, `eebb1f79`, and `d4f740a8`; each run tree was dirty. The exact dirty sources/JAR binding were not recovered. No raw bytes or old completion markers were modified or fabricated. The separate `VALIDATED_ATTESTATION` is not a substitute historical marker. Counts and recovery evidence are in [HISTORICAL_RESULTS_PROVENANCE.md](repairs/HISTORICAL_RESULTS_PROVENANCE.md) and [`historical_results_attestation_20261016.json`](repairs/evidence/historical_results_attestation_20261016.json).

## Source, JAR, workload, and readiness freeze

Clean source commit `cb9d1bfe8e9734b2ce57cab786168ddb216bd18b`, tree `9376f4783606c54eec885112a7daf135b0b93fcd`; JAR SHA-256 `d947f8f9ed44a2d3dcb7772992c4dbc50c447099c5aa4c9cc58c9970386ae96d`. Preference design hash is `0e86c5544c7b0354241070690035b66be3221599a107f6a2fc0366b94f1c1edc`; NY-EXACT reference design hash is `a80c54e979cbf1f02178d02072aa3bd08f6b2d9ff5b7cde7d6cc1be931fb9ac2`. Both JSON freezes record clean source/JAR and dataset/query identities, but both set `readiness.approved=false` and disable historical row reuse. The THop fastest freeze is pending absent graph-bound partition/index inputs. See [ICDE_2027_BENCHMARK_FREEZE_MANIFEST.md](ICDE_2027_BENCHMARK_FREEZE_MANIFEST.md).

## Commit record

| Commit | Phase |
|---|---|
| `21d1873` | PACE-X completion invariant |
| `56ea101` | iSCOPE whole-profile oracle and fidelity/accounting metadata |
| `87b618f` | THop/native SICSP adapters and objective-specific serialization |
| `8629c94` | Bundled SICSP-Pref status downgrade, prospective configs/freeze guard, and historical attestation/provenance tools |
| `cb9d1bf` | Capped results terminal but incomplete accounting |

The final report/manuscript files are included in the closing documentation commit. The configured origin is the verified `kousik-kr/I-PC-Max` repository with push permission; only a standard branch push is intended. No merge or force-push is authorized.

## Final acceptance criteria

| # | Criterion | Result |
|---:|---|---|
| 1 | PACE-X no longer throws for incomplete uncertified empty frontiers | Pass |
| 2 | `NO_FEASIBLE_PATH` requires a global certificate | Pass |
| 3 | SICSP-Pref scalar replay and profiles agree under a rigorous arithmetic contract | **Fail; blocker documented** |
| 4 | Boundary score and travel-time decisions pass independent reference validation | **Fail; counterexamples remain** |
| 5 | PACE-B oracle and bounded-candidate validity tests pass | Pass within tested legacy model and bounded scope |
| 6 | iSCOPE preserved and independently checked; fidelity limits explicit | Partial; full paper read, current method is a material deviation, shared numerical issue remains |
| 7 | Native SICSP objective and reproduction adapter preserve native weight semantics | Pass within the heuristic method's scope |
| 8 | SICSP-Pref retains native SICSP candidate generation | Pass; no score-guided search was added |
| 9 | Objective-correct partitioned THop adapter exists | Pass for tiny fixtures; no real-network indexes |
| 10 | Prospective matrices separate preference comparison, THop fastest track, and PACE-X reference | Pass as configuration; not approved for execution |
| 11 | Timeout and partial-result completion accounting is correct | Pass for tested statuses; capped rows are terminal and incomplete |
| 12 | Future preference/reference result identities bind source/JAR/config/data/query | Pass for identity-only local freezes; THop inputs pending |
| 13 | Dirty historical rows are quarantined and excluded from new results | Pass for new family; historical source itself remains unreconstructed |
| 14 | Java/Python/oracle/tiny CLI checks pass | Pass: 350 Java, 50 Python, all six tiny CLI methods |
| 15 | No unresolved benchmark-critical P0/P1 remains | **Fail: P1-B is open** |

## Remaining issues and decision

- **P1:** SICSP-Pref's legacy scalar/profile mismatch changes selected score/path and budget feasibility. It is explicitly `NOT_CERTIFIED` pending a complete numerical API repair or a separately reviewed exact mode.
- **P1 historical:** old NY/FLA/CAL source/JAR lineage remains unrecovered. The table and figure are quarantined, excluded from new runs, and must be replaced. NY exactness, stage, sensitivity and parallel artifacts require their own validation or replacement.
- **P2:** iSCOPE is not a faithful reproduction of the paper's optimized parallel variant; current adaptation and one-thread allocation are disclosed.
- **P2:** THop's construction schedule differs from the paper, limiting complexity and build-time comparability; large-network partition/index artifacts are absent.
- **P2:** freeze data paths are workspace-local absolute paths. Other hosts must provide byte-identical inputs. Both current freezes are unapproved.
- **P3:** Maven Shade reports overlapping dependency metadata/resources; the package succeeds, but this packaging warning remains.
- **P3:** The manuscript's LaTeX source was updated but not compiled because a LaTeX compiler is unavailable in this environment. The archived PDF and raw figure files were preserved.

**Final classification: NOT READY. Explicit decision: M6 PILOT BLOCKED.** The blocking evidence is the reproducible P1-B wrong-winner and false-feasibility behavior. Stop here; no M6/M7 activity is authorized by this milestone.
