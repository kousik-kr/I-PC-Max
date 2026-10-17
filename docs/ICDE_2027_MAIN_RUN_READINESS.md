# ICDE 2027 Main Run Readiness

## Decision record

The committed primary design passed configuration and matrix validation for 600 cells: 300 PACE-B and 300 optimized iSCOPE. The fixed pilot exposed a PACE-B resource failure on the smallest graph, NY. Its preprocessing process was killed with `SIGKILL` before producing any query row; the session cgroup recorded one OOM kill. The 300 PACE-B main cells remain planned and were not dispatched. No synthetic terminal rows were created.

The optimized iSCOPE main block has finished from the final clean release checkout: 300/300 planned iSCOPE jobs emitted terminal raw records, 100 per dataset. All 300 are `TIME_CAPPED_NOT_CERTIFIED`; there are no certified completions. The separate 300-cell PACE-B block was not dispatched after the pilot OOM. The full-plan validator therefore reports exactly those 300 missing terminal records, with no duplicates, formula mismatches, or profile checksum errors.

THop remains a separately scoped fastest-travel-time reference. The full-network v2 index was not feasible to validate within the available construction evidence; see [THop index feasibility](ICDE_2027_THOP_INDEX_FEASIBILITY.md). PACE-X was not run.

## Verification gates

| Gate | Result |
|---|---|
| M5-R4 Java suite | `mvn -B -ntp clean test`: 373 tests, no failures, on unchanged Java production sources |
| Python experiment suite | `python3 -m pytest -q experiments/tests`: 74 passed in 34.26 s on the current working tree |
| Working-tree whitespace | `git diff --check`: passed |
| Deep NY/FLA/CAL asset preflight | Passed; graph counts, temporal payload checksums, FIFO counts, and horizon `[0, 10080]` match the manifests |
| Original compact query identities | 50 rows per dataset; hashes preserved (below) |
| Fixed pilot plan | 30 cells, no duplicate or missing planned cells |
| Main plan | 600 cells, no duplicate or missing planned cells |
| Java CLI timeout preview | PACE-B `unlimited`; optimized iSCOPE `5` seconds; preprocessing timeout `1800` seconds |
| Worker and memory policy | One worker; `256000` MB configured memory ceiling |
| Clean-source execution freeze | Approved main freeze from commit `4caa383`; copied into the ignored result archive and embedded in the run provenance |

The raw DIMACS conversion replay could not be repeated for FLA and CAL because those original source files are absent. The generated graph and temporal payloads were deeply checked against their release manifests and checksums.

## Focused M5-R4 correctness gate

The review kept the existing algorithm variants and searched for protocol-invalidating behavior. PACE-B's final preference profile is evaluated over its retained candidate registry with directed vertex-simple routes and the travel-time budget enforced; its candidate/work bounds are distinct from the unlimited query wall-time policy. The independent rational checks and known scope limits are in [PACE pruning rational correctness](repairs/PACE_PRUNING_RATIONAL_CORRECTNESS.md).

For iSCOPE, the historical basic alias remains intact. Opt1's exact temporal pruning is checked against the independent whole-profile rational oracle; optimized Opt1+Opt2 output remains `NOT_CERTIFIED`, with exact replay for retained routes and a cooperative five-second search cap. The implementation, pruning proof, and approximation contract are in [M5-R4 implementation](repairs/ISCOPE_OPTIMIZED_IMPLEMENTATION.md), [Opt1 proof and tests](repairs/ISCOPE_OPT1_PRUNING_PROOF_AND_TESTS.md), and [Opt2 contract](repairs/ISCOPE_OPT2_APPROXIMATION_CONTRACT.md).

The THop adapter is a fastest-travel-time reference, uses exact rational profiles and the version-2 partitioned index format, and does not interpret preference scores or budgets. Its tiny-graph oracle, save/reload checks, and no-full-network-index finding are in [THop fidelity and index readiness](repairs/THOP_FIDELITY_AND_INDEX_READINESS.md) and [THop feasibility](ICDE_2027_THOP_INDEX_FEASIBILITY.md). Python aggregation reads `objective_result.profile`, validates the exact profile checksum, separates capped profile metrics from certified completion statistics, and keeps THop fastest-profile aggregation separate. The full experiment gate is described in the [objective adapter report](repairs/EXPERIMENT_OBJECTIVE_ADAPTERS.md).

The earlier M5-R4 readiness note recorded a prospective, unapproved design before this run. This M5-R5 report and the [result validation](ICDE_2027_MAIN_RESULT_VALIDATION.md) record the later approved freeze and actual execution; the older note remains historical.

## Frozen identities

The final main source commit is `4caa38320e4687800de633a31bfa422b85bbcad0`, tree `6c8f8f3051bca8e29ea6b0a6f2d63494071506bb`. It was cloned into a clean checkout and packaged there. The JAR SHA-256 is `5497731758246d9ce78709a4b638dfa9c2f038e75f69bbb73a0f06e4e4a1e7f8`.

| Identity | Pilot | Main |
|---|---|---|
| Config | `primary_pilot.yaml` | `primary_preference.yaml` |
| Canonical config hash | `077a86cb332b103225cf58c61d252ea174873b24742e627c5737ac4db10b4f17` | `3c5a1cdf4aca61df911851847521ef84c9cd700c1c18d6fec3772fb1607d42b9` |
| Run series freeze | `primary_pilot.json` | `primary_preference.json` |
| Freeze source commit | `7790a6470116d9e7e141c8f68effc0470454bdf1` | `4caa38320e4687800de633a31bfa422b85bbcad0` |
| JAR SHA-256 | `5497731758246d9ce78709a4b638dfa9c2f038e75f69bbb73a0f06e4e4a1e7f8` | `5497731758246d9ce78709a4b638dfa9c2f038e75f69bbb73a0f06e4e4a1e7f8` |

The pilot and main have distinct approved freezes. The pilot used the same Java JAR bytes; later source commits fixed only plan formatting, exact-profile JSON key-order validation, and capped-profile aggregation. The main freeze includes the final aggregation code. The working tree now also has unstaged edits to `experiments/scripts/common/exact_preference.py` and `experiments/tests/test_exact_preference_profile.py`; the 74-test result includes them, but they are not part of commit `4caa383`, the approved freeze, or the executed main artifact.

Runtime versions recorded by the freeze are Java 21.0.7, Maven 3.6.3, and Python 3.10.12. The host has 32 logical/physical CPUs. The preflight observed 313 GiB available RAM and 218 GB free disk before algorithm execution.

## Dataset and query identities

| Dataset | Nodes | Directed arcs | Graph SHA-256 | Temporal SHA-256 | Original 50-query SHA-256 |
|---|---:|---:|---|---|---|
| NY | 264,346 | 733,846 | `97999bd124ed9639234bc1a9334b0426911a234b1ed80b5093ec1b30a8ade675` | `d13b5d1080e9520a82df780831265e63bbfe6a518db73d793b3f5ae4d8d548f3` | `0094e026e67dd6f8e6cc9bb16d6302588fa02b6491431713113c256cfe2e0609` |
| FLA | 1,070,376 | 2,712,798 | `19329caf5c0f725589bd30667f513ea4b6b6bbd24854cebd0ad36aa8b3e7b34d` | `d07f1ca2fa37efccfb8f7e43584241aabd5976033aa8aee74483b00403a235b9` | `e417c799d094c4e8dfeff1a421621f317a6a2d4a938ec974fba5360573e6c09e` |
| CAL | 1,890,815 | 4,657,742 | `13e4c7b0c625fedb110c1af4c392122cda95aac29ec67f6f61fb6b24ed5fc52b` | `a6321260b73f67ba1cc6b5ed0b5d4733d2dded95266857cd2b22eb10e9533db9` | `46c50a51c0b453bba0ac776bacef226fcc18eb17d51b606a99c9496773e9dab4` |

The five-query pilot manifests are fixed subsets selected from the original compact rows before observing performance. Their SHA-256 values are NY `6b377353d7972c115e73b8490fd0312322134cdb6d4fa4d7566402e786b9576c`, FLA `1c94c73ba6e262965fcd737971aae3904068ae731ab43816405ee9fa8741ff92`, and CAL `30078877208e2bd3bc380112da4fec048a2ab0197375d3ed4146bc8479d84f40`.

## Execution policy and remaining blockers

PACE-B has no ordinary query wall-time deadline. Its intrinsic 4/4/2 bounds and declared work caps remain in force. Optimized iSCOPE uses the five-second search cap and the exact retained-route replay. THop has no query deadline if an index is later validated. Preprocessing is reported separately and has a distinct 1,800-second limit. The batch runner persists a raw row as each query terminates.

The NY pilot PACE-B process reached at least 169,620,624 KiB RSS at the last observation and was then killed before a result row appeared. The launch command used `-Xmx250g`, `256000` MB configured memory, and no PACE-B query deadline. This is an OOM resource failure, not a query timeout or completed response. Since NY is the smallest checked graph, FLA/CAL PACE-B feasibility is not established under this host policy. The PACE-B main cells are recorded as not dispatched; the iSCOPE run proceeded independently and finished with capped partial profiles. THop still has no full-network index or query results, as detailed in the feasibility report.

The unstaged edit to `docs/repairs/EXACT_PREFERENCE_TEMPORAL_API.md` was preserved byte-for-byte (SHA-256 `fd0c7b226086fde867cc90a4450a270b661940ee7d82674065564654572a4c74`) and is excluded from the source commits and executable freezes.

## Reproduction commands

From the clean release checkout, after linking the ignored local `data` directory and building the JAR:

```bash
python3 experiments/scripts/run_all.py \
  --config experiments/configs/icde2027_compact_v1/primary_preference.yaml \
  --run-id M01-primary-main --plan-only \
  --reuse-preflight /tmp/icde2027-primary-main-preflight.json

python3 experiments/scripts/execute_matrix.py \
  --config experiments/configs/icde2027_compact_v1/primary_preference.yaml \
  --run-id M01-primary-main \
  --matrix experiments/results/icde2027_compact_v1/primary_preference/M01-primary-main/provenance/iscope_main_execution_matrix.jsonl \
  --max-concurrent 1
```

The execution matrix intentionally contains only the iSCOPE rows after the PACE-B NY preprocessing OOM. The canonical 600-cell plan remains unchanged and the missing PACE-B cells remain visible to validation.
