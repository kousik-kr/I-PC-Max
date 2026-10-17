# ICDE 2027 Main Result Validation

## Run outcome

The primary preference plan contains 600 cells: 100 each for PACE-B and optimized iSCOPE on NY, FLA, and CAL. The optimized iSCOPE block emitted 300 terminal raw rows, 100 per dataset. All 300 are `TIME_CAPPED_NOT_CERTIFIED`; none is an exhaustive completion. The PACE-B block has zero dispatched jobs and 300 planned cells without terminal records. The missing rows were not fabricated.

| Dataset | PACE-B planned / dispatched / terminal | iSCOPE planned / terminal | iSCOPE completed | iSCOPE capped | iSCOPE failed |
|---|---:|---:|---:|---:|---:|
| NY | 100 / 0 / 0 | 100 / 100 | 0 | 100 | 0 |
| FLA | 100 / 0 / 0 | 100 / 100 | 0 | 100 | 0 |
| CAL | 100 / 0 / 0 | 100 / 100 | 0 | 100 | 0 |
| **Total** | **300 / 0 / 0** | **300 / 300** | **0** | **300** | **0** |

PACE-B was not dispatched after the fixed NY pilot preprocessing batch was killed by `SIGKILL` with one cgroup OOM event and no Java query output. The failure and the main-run non-dispatch decision are recorded in `primary_pilot/P01-fixed-pilot/provenance/PACE_B_NY_PREPROCESSING_FAILURE.json` and `primary_preference/M01-primary-main/provenance/PACE_B_MAIN_NOT_DISPATCHED.json`. The pilot failure is a resource failure; the 300 main PACE-B cells are unrun, not terminal failures.

The result validator reports `passed=false` with the single error `missing 300 planned terminal records`. It confirms 600 expected formula cells, zero formula mismatches, zero duplicate planned cells, and zero warnings. Independent job-ledger comparison confirms that the 300 missing IDs are exactly the PACE-B cells; all 300 raw IDs map to the plan with matching input hashes, study IDs, and trial IDs. There are zero duplicate or unknown raw job IDs. The profile decoder reported no checksum/schema errors.

## Per-dataset iSCOPE results

| Dataset | Rows / query IDs / trials | Status | Median capped profile wall time | Shared preprocessing | Max recorded process RSS | Max recorded JVM heap |
|---|---:|---|---:|---:|---:|---:|
| NY | 100 / 50 / 2 each | 100 capped | 4.5029 s | 16.21 s | 11.72 GiB | 9.12 GiB |
| FLA | 100 / 50 / 2 each | 100 capped | 4.5025 s | 61.33 s | 55.50 GiB | 39.96 GiB |
| CAL | 100 / 50 / 2 each | 100 capped | 4.5026 s | 91.63 s | 98.51 GiB | 75.42 GiB |

The query-time values are medians of the summary field `median_capped_profile_wall_time_ns`; they are not completed-search runtimes. Preprocessing was shared within each dataset batch and excluded from query time. Memory values are maxima across that dataset's 100 raw records (`peak_rss` and `peak_heap`); they are not a matched-resource comparison against PACE-B, which did not run.

Every capped record uses numerical contract `EXACT_RATIONAL_PREFERENCE_PROFILE-v1`, output schema `exact-preference-envelope-v1`, and exactness scope `NOT_CERTIFIED`. All 300 records have an exact profile checksum, and their retained-route profiles pass decoder validation. The partial profiles each contain three `NO_PATH` cells, zero represented feasible coverage, zero integrated preference, zero selected paths, and zero path switches. Average selected preference is null because no route is selected. These partial outputs do not certify infeasibility for the full search.

| Dataset | Planned iSCOPE | Capped query units | Median partial profile cells | Median represented feasible coverage (exact) | Median integrated preference (exact) | Median path switches | Average selected preference |
|---|---:|---:|---:|---:|---:|---:|---|
| NY | 100 | 50 | 3 | 0 | 0 | 0 | Undefined; no selected route |
| FLA | 100 | 50 | 3 | 0 | 0 | 0 | Undefined; no selected route |
| CAL | 100 | 50 | 3 | 0 | 0 | 0 | Undefined; no selected route |

Here, “capped query units” counts the 50 logical query identities after pairing their two measured trials. `valid_profiles=0` and completion rate zero in the aggregate are intentional: capped partial profiles are checksum-valid outputs but are not certified complete profiles. No zero was substituted for the missing average selected preference.

## Frozen identities and provenance

| Identity | Value |
|---|---|
| Config | `experiments/configs/icde2027_compact_v1/primary_preference.yaml` |
| Canonical config SHA-256 | `3c5a1cdf4aca61df911851847521ef84c9cd700c1c18d6fec3772fb1607d42b9` |
| Source commit / tree | `4caa38320e4687800de633a31bfa422b85bbcad0` / `6c8f8f3051bca8e29ea6b0a6f2d63494071506bb` |
| Clean-release JAR SHA-256 | `5497731758246d9ce78709a4b638dfa9c2f038e75f69bbb73a0f06e4e4a1e7f8` |
| Main freeze SHA-256 | `4c3c22a05f513711e28da7a4362ac386ac08f08cb457969f0a81daba0d68ec73` |
| Post-run result receipt SHA-256 | `a36c86566ea618c1da421002237e9af432cddb4a5908ec56391be8a04477a821` |
| Runner SHA-256 | `9f08d7d4018f5641f438a19968452f70d64dc4998f4edb60564ee20aea026e12` |
| Aggregator SHA-256 | `7b7d3947c897b363d2905151b74ffe70168372164ecb9f9304c177a85d808d00` |
| Collected raw checksum | `159fd307fe2c555363bc59dbf66d928e3293c1a6d3ed36615ae36120b682a7a5` |
| Java / Maven / Python | Java 21.0.7 / Maven 3.6.3 / Python 3.10.12 |

The main and pilot approved freezes are stored at `experiments/results/icde2027_compact_v1/freezes/primary_preference.json` and `primary_pilot.json`. The prospective freeze embedded in `M01-primary-main/provenance/benchmark_freeze.json` is byte-identical to the copied main freeze. It remains unchanged after execution. The post-run receipt at `M01-primary-main/provenance/result_manifest.json` ties that approved freeze to collection, validation, and summary checksums.

The original 50-query manifest SHA-256 values are NY `0094e026e67dd6f8e6cc9bb16d6302588fa02b6491431713113c256cfe2e0609`, FLA `e417c799d094c4e8dfeff1a421621f317a6a2d4a938ec974fba5360573e6c09e`, and CAL `46c50a51c0b453bba0ac776bacef226fcc18eb17d51b606a99c9496773e9dab4`. The full graph and temporal checksums are listed in [main run readiness](ICDE_2027_MAIN_RUN_READINESS.md).

The approved policy used one worker, a five-second iSCOPE search cap, 500 ms finalization reserve, separate preprocessing accounting with a 1,800-second preprocessing limit, and a configured 256,000 MB memory ceiling. PACE-B had no query deadline but was not launched for the main block. THop has no full-network index and no query rows. PACE-X was not run.

## Validation artifacts

The ignored result archive is under `experiments/results/icde2027_compact_v1/primary_preference/M01-primary-main/`:

- `plan/matrices/canonical_job_ledger.jsonl`: unchanged 600-cell ledger.
- `provenance/iscope_main_execution_matrix.jsonl`: dispatched iSCOPE cells.
- `provenance/PACE_B_MAIN_NOT_DISPATCHED.json`: explicit PACE-B non-dispatch decision.
- `raw/M01/`: 300 new terminal iSCOPE records.
- `normalized/run_records.jsonl` and `normalized/run_records.csv`: collected rows; no historical reuse.
- `summaries/aggregate_records.csv`: per-dataset capped-profile summary.
- `release/validation_report.json` and `release/VALIDATION_REPORT.md`: full-plan validation output.
- `provenance/benchmark_freeze.json`: approved prospective freeze embedded for this run.
- `provenance/result_manifest.json`: post-run receipt linking the freeze to collected results.

The collector reported 300 new raw records and zero reused historical records. It wrote no Parquet file because optional `pyarrow` is not installed; canonical JSONL and CSV outputs are present.

## Remaining experiment work

- PACE-B: diagnose and resolve the NY preprocessing memory failure before authorizing another full-network attempt; current FLA/CAL feasibility is unknown.
- THop: finish and validate full BTHP/PTHP v2 indexes, then run the same 50 query identities with two trials. No full-network fastest-profile metrics exist yet.
- iSCOPE: this campaign provides capped partial profiles only; a five-second cap produced no exhaustive completion on any dataset.
- PACE-X was not run. SICSP-Pref remains optional and was not allowed to delay the primary workload.

The fixed pilot, THop feasibility findings, and reproduction commands are documented in [compact run execution](ICDE_2027_COMPACT_RUN_EXECUTION.md) and [THop index feasibility](ICDE_2027_THOP_INDEX_FEASIBILITY.md). The M5-R4 Java suite previously passed 373 tests; the current workspace Python suite passed 74 tests. The latter includes unstaged decoder hardening in the workspace that is outside the approved main source freeze.
