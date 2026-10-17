# ICDE 2027 Compact Run Execution

## Frozen protocol

The main design is `experiments/configs/icde2027_compact_v1/primary_preference.yaml`, config hash `3c5a1cdf4aca61df911851847521ef84c9cd700c1c18d6fec3772fb1607d42b9`. Its canonical plan contains 600 cells: 50 original queries × 2 measured trials × 3 datasets × 2 methods. The pilot design is `primary_pilot.yaml`, with five fixed queries and one measured trial per dataset/method, for 30 planned cells.

The main plan and pilot subsets use the unchanged compact manifests and hashes in [main run readiness](ICDE_2027_MAIN_RUN_READINESS.md). Pilot measurements are isolated under `P01-fixed-pilot`; none is reused in the main campaign. Warm-up is one untimed pass per shared-preprocessing batch and is not counted as a measured job.

The final main checkout is commit `4caa38320e4687800de633a31bfa422b85bbcad0`, tree `6c8f8f3051bca8e29ea6b0a6f2d63494071506bb`. The clean-checkout JAR SHA-256 is `5497731758246d9ce78709a4b638dfa9c2f038e75f69bbb73a0f06e4e4a1e7f8`. The approved main freeze is `experiments/results/icde2027_compact_v1/freezes/primary_preference.json`. The pilot has its own freeze and source revision.

## Fixed pilot

| Dataset | Planned PACE-B | PACE-B outcome | Planned optimized iSCOPE | iSCOPE outcome |
|---|---:|---|---:|---|
| NY | 5 | Preprocessing OOM/SIGKILL; no query rows | 5 | 5 capped, checksums valid |
| FLA | 5 | Not dispatched after NY resource failure | 5 | 5 capped, checksums valid |
| CAL | 5 | Not dispatched after NY resource failure | 5 | 5 capped, checksums valid |

The five PACE-B NY cells are recorded as unresolved resource failures in `P01-fixed-pilot/provenance/PACE_B_NY_PREPROCESSING_FAILURE.json`. The run emitted no raw terminal row for them. The other ten PACE-B pilot cells were not started.

The 15 iSCOPE pilot raw rows match their fixed job IDs and exact profile checksums. All have `TIME_CAPPED_NOT_CERTIFIED`, `NOT_CERTIFIED` exactness, zero selected feasible coverage, zero integrated preference, and three exact profile cells (all `NO_PATH`). Their average selected preference is missing because no interval has a selected path; it is not replaced with zero. The median measured query time by dataset is recorded in `summaries/aggregate_records.csv`.

The pilot whole-plan validator correctly fails on the 15 missing PACE-B terminal rows and reports no duplicate cells or profile/checksum errors. The capped rows are retained as valid partial outputs, never as exhaustive completions.

## Main execution

The main canonical 600-cell plan passed independent formula and query-manifest validation. The PACE-B block of 300 cells was not dispatched after the smallest dataset’s PACE-B preprocessing process was killed by the OOM killer. This is recorded in `M01-primary-main/provenance/PACE_B_MAIN_NOT_DISPATCHED.json`; the cells remain planned and are not synthesized as terminal records.

The optimized iSCOPE block contains 300 cells and finished sequentially from the frozen clean checkout. Each query used one worker, a five-second cooperative search cap, exact retained-route final replay, and separately timed shared preprocessing. The execution matrix is stored outside `plan/matrices` at `M01-primary-main/provenance/iscope_main_execution_matrix.jsonl`, so the canonical 600-cell plan remains unchanged. Raw JSONL rows were streamed into `M01-primary-main/raw/M01` as each query terminated. All 300 iSCOPE jobs have terminal raw records; every one is capped and uncertified.

The collector found 300 new records and reused zero historical records. The plan validator reports only the 300 planned PACE-B terminal rows missing; those cells were not dispatched, not recorded as failures, and not synthesized. Per-dataset counts and capped-profile metrics are recorded in [main result validation](ICDE_2027_MAIN_RESULT_VALIDATION.md). No ordinary query timeout is applied to PACE-B or THop. THop has no run because no full-network v2 index was validated. PACE-X was not run.

| Dataset | iSCOPE terminal / planned | Completed | Capped | Failed | Median capped profile wall time | Shared preprocessing | Max recorded RSS / JVM heap |
|---|---:|---:|---:|---:|---:|---:|---:|
| NY | 100 / 100 | 0 | 100 | 0 | 4.5029 s | 16.21 s | 11.72 / 9.12 GiB |
| FLA | 100 / 100 | 0 | 100 | 0 | 4.5025 s | 61.33 s | 55.50 / 39.96 GiB |
| CAL | 100 / 100 | 0 | 100 | 0 | 4.5026 s | 91.63 s | 98.51 / 75.42 GiB |

For these capped records, all three serialized profile cells are `NO_PATH`; represented feasible coverage and integrated preference are exactly zero, with zero path switches. Average selected preference is undefined because the partial profiles select no route. These values describe the capped partial output and do not certify that the full query has no feasible route. The freeze and collected result checksums are recorded in the run provenance.

## Reproduction

Build the executable from a clean committed checkout:

```bash
mvn -B -ntp -DskipTests package
```

Plan the full main workload without starting algorithms:

```bash
python3 experiments/scripts/run_all.py \
  --config experiments/configs/icde2027_compact_v1/primary_preference.yaml \
  --run-id M01-primary-main --plan-only \
  --reuse-preflight /tmp/icde2027-primary-main-preflight.json
```

Run the dispatched iSCOPE main block with one worker:

```bash
python3 experiments/scripts/execute_matrix.py \
  --config experiments/configs/icde2027_compact_v1/primary_preference.yaml \
  --run-id M01-primary-main \
  --matrix experiments/results/icde2027_compact_v1/primary_preference/M01-primary-main/provenance/iscope_main_execution_matrix.jsonl \
  --max-concurrent 1
```

Collect, validate, and summarize records with:

```bash
python3 experiments/scripts/collect_results.py \
  --config experiments/configs/icde2027_compact_v1/primary_preference.yaml \
  --run-id M01-primary-main
python3 experiments/scripts/validate_results.py \
  --config experiments/configs/icde2027_compact_v1/primary_preference.yaml \
  --run-id M01-primary-main
python3 experiments/scripts/summarize_results.py \
  --config experiments/configs/icde2027_compact_v1/primary_preference.yaml \
  --run-id M01-primary-main
```
