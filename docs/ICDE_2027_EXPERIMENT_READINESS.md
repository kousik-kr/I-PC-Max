# ICDE 2027 Experiment Readiness

Audit revision: 508a1d8cc422fd4595ad36857e51990fd86c562e
Branch: feature/icde27-baseline-implementation
Classification: **NOT READY FOR FULL-NETWORK EXPERIMENTS**

## M5-R2 numerical repair update (2026-10-16)

The SICSP-Pref final evaluator now uses exact rational edge-entry replay, score-event preimages, budget feasibility and complete envelope selection over its retained route registry. The repaired exactness scope is `RETAINED_FRONTIER`; the search remains heuristic and the adapter does not claim global certification. Native SICSP generation is unchanged. Independent whole-cell rational oracle checks validate exact output for raw-simple, retained-feasible and global simple-path registries, and the known heuristic-miss route remains absent from the generated set. Details and counterexamples are in [SICSP_PREF_EXACT_NUMERICAL_REPAIR.md](repairs/SICSP_PREF_EXACT_NUMERICAL_REPAIR.md).

This closes P1-B for SICSP-Pref's final retained-registry evaluation, but it does not establish a common objective evaluator across PACE-X, PACE-B and iSCOPE. Those methods still use legacy temporal profile or pruning arithmetic, which has a demonstrated fractional-root counterexample. Historical P1-C source/JAR lineage also remains unresolved, and the THop network index is absent. Keep both preference/reference freezes at `readiness.approved=false`; the overall classification remains **NOT READY** and M6/M7 are not started. See the [numerical migration matrix](repairs/PREFERENCE_ALGORITHM_NUMERICAL_MIGRATION_PLAN.md).

## M5-R superseding status (2026-10-16)

This is a later readiness assessment than the original M5 audit below. The versioned `icde2027_v1` family now includes preference matrices with PACE-B, iSCOPE, and SICSP-Pref; a separate fastest-profile THop matrix; a tractable PACE-X reference matrix; and a native SICSP Figure 1 reproduction fixture. The registry has THop/native SICSP adapters. Capped anytime results are now terminal but `completed=false`. No NY/FLA/CAL benchmark was run. The preference and tractable-reference input identities can be frozen from the present local datasets and query manifests with `readiness.approved=false`; a THop fastest-profile freeze is pending its real partition/index files. SICSP-Pref remains `NOT_CERTIFIED`, so the primary comparison is still not cleared for execution.

The historical six-value scalability table was reaggregated from archived rows but its dirty source/JAR lineage remains unresolved. The rows are quarantined from new runs, and the manuscript table/figure must be replaced after a separately authorized clean comparison. A new source/JAR/data/query identity does not repair that historical gap. Current gate results and exact commands are in [ICDE_2027_M5_REPAIR_REPORT.md](ICDE_2027_M5_REPAIR_REPORT.md); current prospective freeze state is in [ICDE_2027_BENCHMARK_FREEZE_MANIFEST.md](ICDE_2027_BENCHMARK_FREEZE_MANIFEST.md).

## Decision

Do not start M6 or M7 yet. Earlier audit text below records the state before M5-R2. The SICSP-Pref mismatch has been repaired for exact final evaluation over its retained candidate registry. PACE-X, PACE-B and iSCOPE still use a different legacy temporal model, and historical source/JAR lineage is unrecovered; these blockers keep the pilot unapproved.

## Registration and configuration audit

| Required method | Registry ID | Adapter / current configuration | Readiness |
|---|---|---|---|
| PACE-X | pace-x | `PaceExperimentAlgorithm`; T01 and `icde2027_v1/reference.yaml` | Registered; R1 status invariant fixed; global certificate remains conditional on explicit completeness. |
| PACE-B | pace-b | `PaceExperimentAlgorithm`; E03/T03 and `icde2027_v1` preference/reference configs | Registered; bounded candidate policy, never globally exact by default. |
| iSCOPE | iscope | `IScopeAlgorithm`; `icde2027_v1` preference/reference configs | Registered; whole-profile oracle checks pass. Full paper read; current implementation is a material deviation and inherits the shared arithmetic limit. |
| SICSP-Pref | sicsp-pref | `SicspPrefExperimentAlgorithm`; `icde2027_v1` preference/reference configs | Exact final evaluation over the generated retained registry; `RETAINED_FRONTIER` only when feasible. Heuristic candidate generation remains incomplete and uses native legacy numerics. |
| Native SICSP | sicsp-native | Native SICSP CLI adapter; Figure 1 weights fixture and `Delta=80` | Registered and smoke-tested with original native weight semantics; heuristic scope remains. |
| THop-Partitioned | thop-partitioned | Exact-rational fastest-profile adapter; separate `icde2027_v1/fastest.yaml` | Registered and smoke-tested on tiny partitions. Real-network partition/index inputs are absent, so no full-network freeze exists. |

AlgorithmRegistry also registers exh-profile, pl-exact, rpq, ksp-profile, interval-best and allfp. Their presence does not satisfy the required iSCOPE/SICSP-Pref/THop comparison.

Initial M5 configuration findings:

- experiments/configs/studies/e03_main.yaml compares PACE-B, RPQ and interval-best; it uses PACE-X/PL-exact only on NY and omits iSCOPE from the main preference-aware comparison.
- t01_exactness_oracle.yaml correctly compares PACE-X and PACE-B on NY-EXACT only.
- t03_scalability_main.yaml is a legacy configuration without iSCOPE.
- t03_scalability_main_5s.yaml includes PACE-B, iSCOPE and allFP, with iSCOPE set to a five-second cap. It does not include SICSP-Pref or THop.
- The legacy T03 comparison remains incomplete. The new versioned family provides the required objective-specific methods, but it is unapproved and SICSP-Pref's numerical blocker prevents execution.

M5-R adds the versioned prospective `icde2027_v1` family. Its preference matrices include PACE-B, iSCOPE and SICSP-Pref; its fastest track keeps THop separate; its tractable reference includes PACE-X; and native SICSP has a separate Figure 1 fixture. These configurations are frozen with `readiness.approved=false`. THop's real graph-bound indices remain missing and SICSP-Pref's numerical issue blocks preference-track execution.

## Result artifacts and provenance

The tracked manuscript PACE.tex contains the large-network table at lines 2545–2578 and reports the 200-query NY/FLA/CAL comparison. The local ignored snapshot experiments/results/Final-result/FINAL_RESULT_INDEX.md is newer than the July 30 readiness report and identifies:

| Dataset | Snapshot's declared PACE-B rows | iSCOPE |
|---|---|---|
| NY | 15,000 terminal rows | 15,000 time-capped |
| FLA | 12,743 completed; 2,257 timeouts excluded from completed-only summaries | 15,000 time-capped |
| CAL | 15,000 completed | 15,000 time-capped |

The July report's 3/20 pilot is an earlier diagnostic, not the final comparison. The final manuscript artifacts contain the table values and aggregation materials. M5 therefore does not classify the paper's large-workload table as disproven solely by the older pilot.

The local snapshot also carries limitations that prevent source-level reproduction:

- NY provenance records commit ed0eef27 with dirty=true; FLA records eebb1f79 with dirty=true; CAL records d4f740a8 with dirty=true. Their tree hashes are in the local environment records, but the corresponding dirty patches are not archived as Git commits.
- Nine PACE production files differ between d4f740a8 and audited HEAD 508a1d8, including candidate generation, PACE status and options.
- In the snapshot inspected, NY and FLA lack markers/main.complete.json; CAL has one. Their progress records contain rows, but the final snapshot is not uniformly sealed.
- experiments/results/ is Git-ignored. The local raw runs, query ledgers and summary scripts are not part of the audited Git revision.

The six manuscript table aggregates were reproduced from the archived selector and two-level aggregation; the score column is a median of per-query trial medians, not a mean. This confirms the displayed aggregate values but does not recover dirty source/JAR lineage. Do not attribute those rows to the new clean code. The manuscript table and figure are quarantined and must be replaced from an authorized clean comparison. Keep timeouts separately reported and exclude them from completed-only timing/quality.

## Measurement and reproducibility

- Java: OpenJDK 21.0.7; Maven 3.6.3; Maven compiler release 21.
- Initial audit commands passed 309 Java tests and 39 Python tests. M5-R final commands passed 350 Java tests and 50 Python tests.
- Capped iSCOPE outputs return `TIME_CAPPED_NOT_CERTIFIED`, with `completed=false` and `terminal=true`. Do not treat capped anytime profiles as completed searches.
- The T03 plan distinguishes query runtime and caps in its runner/provenance; actual experiment aggregation still needs revalidation against the exact source revision and completion manifests.
- Dataset inputs occupy 4.1 GB locally and are ignored. Their existing preflight/provenance checksums must be reused; no large data were copied, removed or rewritten during this audit.
- The PACE, native SICSP, THop, and full iSCOPE papers are locally preserved; the WISE 2025 paper has SHA-256 `50016f19605f7e32dc6de9c3a5e2e684e5131937497ca7d5f451057fecdceffc`.
- No NY/FLA/CAL campaign was run during M5.

## Acceptance matrix

| Gate | Result | Evidence |
|---|---|---|
| G1 PACE-X oracle | PARTIAL | R1 fixes the incomplete empty-frontier exception; global certification is conditional and shared temporal limits remain. |
| G2 PACE-B oracle | PARTIAL | Bounded validity/oracle tests pass; no global guarantee, historical result source remains unrecovered. |
| G3 iSCOPE oracle | PARTIAL | Full paper read and complete-cell tests added; current method materially deviates and inherits shared arithmetic issue. |
| G4 Native SICSP oracle | PARTIAL | Independent tiny oracle and native Figure 1 CLI adapter pass; heuristic miss remains by method design. |
| G5 SICSP-Pref oracle | PASS, scoped | Exact replay/envelope agrees with independent rational whole-cell oracles over the retained registry. Global heuristic miss remains; `GLOBAL_CERTIFIED` is not claimed. |
| G6 THop oracle | PASS WITH DOCUMENTED DEVIATION | Exact rational tiny oracle and v2 serialization pass; schedule deviation and absent large indices are disclosed. |
| G7 Shared temporal model | FAIL | SICSP-Pref final retained-registry output is exact. PACE-X, PACE-B and iSCOPE still use legacy temporal/pruning operations; no common exact comparison model exists. |
| G8 Registration/accounting | PASS | Six method IDs/adapters, prospective config family, freeze guard, and capped completion semantics pass tests. |
| G9 Reproducible build | PASS | 350 Java tests, 50 Python tests, clean-source JAR and preference/reference identity freezes; THop index is pending. |
| G10 No unresolved P0/P1 | FAIL | Historical P1-C remains unresolved and quarantined; cross-algorithm numerical contracts remain incompatible. |

## Corrective sequence before M6

1. Migrate PACE-X, PACE-B and iSCOPE search-time pruning and final profiles to the same explicitly chosen exact numerical contract, or qualify any comparison under their current incompatible contracts.
2. Keep the historical table/figure quarantined and replace them after a separately authorized clean campaign. Validate or replace the NY exactness, stage, sensitivity, and parallel artifacts separately.
3. Materialize and identity-freeze real THop partition/index inputs before preparing its fastest-profile campaign.
4. Re-run the full correctness, paper-fidelity, and source/JAR/input freeze checks before any later authorization. The current freeze is not execution approval.

M6 is not approved by this audit.
