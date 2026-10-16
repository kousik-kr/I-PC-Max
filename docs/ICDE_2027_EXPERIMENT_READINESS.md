# ICDE 2027 Experiment Readiness

Audit revision: 508a1d8cc422fd4595ad36857e51990fd86c562e
Branch: feature/icde27-baseline-implementation
Classification: **NOT READY FOR FULL-NETWORK EXPERIMENTS**

## Decision

Do not start M6 or M7 yet. The Java and Python test suites pass, and a later local T03 snapshot exists beyond the July 30 pilot. The result snapshot records dirty historical source trees, two dataset completion markers are missing, and current PACE production source differs from the recorded run commits. In addition, PACE-X has an incomplete-frontier status exception and SICSP-Pref has a demonstrated one-tick profile/replay mismatch.

## Registration and configuration audit

| Required method | Registry ID | Adapter / current configuration | Readiness |
|---|---|---|---|
| PACE-X | pace-x | PaceExperimentAlgorithm; T01 exactness config | Registered; conditional certificate only; P1-A unresolved. |
| PACE-B | pace-b | PaceExperimentAlgorithm; E03 and T03 configs | Registered; bounded candidate policy, never globally exact by default. |
| iSCOPE | iscope | IScopeAlgorithm; t03_scalability_main_5s.yaml | Registered and tested. E03 main and legacy T03 omit it; full original paper is not locally available. |
| SICSP-Pref | sicsp-pref | SicspPrefExperimentAlgorithm | Registered, but absent from the final T03 matrix; retained candidate envelope only; P1-B unresolved. |
| Native SICSP | none | Native engine and direct JUnit tests only | Required separate validation/reproduction runner is missing. |
| THop-Partitioned | none | Index implementation and direct tests only | Experiment adapter missing. Must be measured as fastest-profile computation. |

AlgorithmRegistry also registers exh-profile, pl-exact, rpq, ksp-profile, interval-best and allfp. Their presence does not satisfy the required iSCOPE/SICSP-Pref/THop comparison.

Configuration findings:

- experiments/configs/studies/e03_main.yaml compares PACE-B, RPQ and interval-best; it uses PACE-X/PL-exact only on NY and omits iSCOPE from the main preference-aware comparison.
- t01_exactness_oracle.yaml correctly compares PACE-X and PACE-B on NY-EXACT only.
- t03_scalability_main.yaml is a legacy configuration without iSCOPE.
- t03_scalability_main_5s.yaml includes PACE-B, iSCOPE and allFP, with iSCOPE set to a five-second cap. It does not include SICSP-Pref or THop.
- The current T03 paper comparison is therefore not the complete M7 comparison requested in the milestone.

Before freezing matrices, add native SICSP and THop reproduction configurations, SICSP-Pref, and iSCOPE to every relevant preference-aware comparison. Give THop fastest-profile metrics and keep them separate from preference score/quality.

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

Treat the existing means as provisional evidence about those recorded run trees. Do not attribute them to 508a1d8. Corrective work is to freeze a clean source/JAR/config identity, reconcile the complete raw-row manifests and checksums, and reproduce the final-table aggregate from that frozen identity. Keep timeouts separately reported and exclude them from completed-only timing/quality.

## Measurement and reproducibility

- Java: OpenJDK 21.0.7; Maven 3.6.3; Maven compiler release 21.
- Current command mvn -B -ntp clean test: 309 tests, 0 failures, 0 errors, 0 skipped.
- Python experiment framework suite: python3 -m pytest -q experiments/tests; 39 passed.
- iSCOPE has a five-second query cap and returns TIME_CAPPED_NOT_CERTIFIED when incomplete. Do not treat capped anytime profiles as complete results.
- The T03 plan distinguishes query runtime and caps in its runner/provenance; actual experiment aggregation still needs revalidation against the exact source revision and completion manifests.
- Dataset inputs occupy 4.1 GB locally and are ignored. Their existing preflight/provenance checksums must be reused; no large data were copied, removed or rewritten during this audit.
- The two source papers in Baseline papar are local and preserved. The iSCOPE full text is missing.
- No NY/FLA/CAL campaign was run during M5.

## Acceptance matrix

| Gate | Result | Evidence |
|---|---|---|
| G1 PACE-X oracle | FAIL | Tiny oracle corpus passes, but incomplete empty frontier throws; P1-A. |
| G2 PACE-B oracle | PARTIAL | Bounded validity and retained-scope tests exist; current paper-run source lineage is unresolved. |
| G3 iSCOPE oracle | PARTIAL | Independent tiny query test added; original full specification absent and probe-based comparison does not establish complete profile equality. |
| G4 Native SICSP oracle | PARTIAL | Independent tiny oracle and missed-path evidence; heuristic is intentionally incomplete and no runner exists. |
| G5 SICSP-Pref oracle | FAIL | One-tick profile/replay difference at a fractional root; P1-B. |
| G6 THop oracle | PASS WITH DOCUMENTED DEVIATION | Exact rational all-pairs tiny oracle and v2 serialization; inner construction schedule differs from paper. |
| G7 Shared temporal model | FAIL | Legacy behavior is declared; concrete SICSP-Pref mismatch remains. |
| G8 Registration/accounting | FAIL | Missing native SICSP/THop adapters, iSCOPE absent from E03, dirty historical result trees. |
| G9 Reproducible build | PASS | Current Java and Python suites pass. Large raw data/results remain local-only. |
| G10 No unresolved P0/P1 | FAIL | P1-A, P1-B and P1-C remain. |

## Corrective sequence before M6

1. Fix the PACE-X completion invariant and add a feasible-chain regression for insufficient pivot depth.
2. Make SICSP-Pref route replay and composed profiles agree at fractional roots; test exact budget and score-step boundaries.
3. Freeze a clean source commit/JAR and recover or regenerate the final NY/FLA/CAL aggregates from exact input/config/raw-row manifests. Write complete marker/provenance records for every dataset.
4. Add experiment adapters and configs for native SICSP and THop; add SICSP-Pref and iSCOPE to the full preference-aware comparison. Record THop only as a fastest-arrival profile method.
5. Obtain and review the complete iSCOPE paper; state the adaptation accurately. Expand independent profile checks to compare scores and interval ownership throughout each oracle cell.
6. Rerun Maven, Python and tiny runner smoke validation, then recompute G1–G10. Start M6 only when G10 passes and no required gate remains partial or failed.

M6 is not approved by this audit.
