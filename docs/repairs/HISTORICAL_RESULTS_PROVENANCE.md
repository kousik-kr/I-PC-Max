# M5-R R6: historical result reconciliation

Date: 2026-10-16 UTC. Initial M5-R revision: `0d0fa20c8b979b1db9916d5489649d73e8cc4993`.

**Historical source is not fully reproducible.** The available raw records can be counted and the six manuscript scalability values can be reaggregated. This does not recover the missing dirty source or establish those values as results of the repaired implementation. The historical corpus is quarantined for interpretation: retain every byte, exclude every historical row from prospective results, and replace the affected manuscript results after an authorized clean campaign.

## Read-only procedure and evidence

The validator is `experiments/scripts/audit_historical_results.py`. It reads the canonical job ledgers before counting records. It never writes below its input snapshot and refuses to overwrite an audit output. Its independent accounting requires both a normal terminal status and `completed=true`; terminal, completed, valid-profile and capped-profile denominators are reported separately. Duplicate/missing jobs, unexpected jobs, input-hash mismatches and serialized dataset/method/query/trial identities are checked.

The tracked evidence is under `docs/repairs/evidence/`. Each record is explicitly `VALIDATED_ATTESTATION`, includes the validator revision and exact validator SHA-256, and sets `historical_source_reconstructed=false`. These are new validation records, not replacement historical completion markers. The original NY/FLA missing markers remain missing; CAL's original marker remains unchanged. No completion marker was created.

Reproduce into a new output path:

```bash
python3 experiments/scripts/audit_historical_results.py \
  --compare docs/repairs/evidence/historical_results_attestation_20261016.json \
  --output /tmp/icde2027-historical-revalidation.json
python3 -m pytest -q experiments/tests/test_historical_provenance.py
```

The second scan's raw-tree hashes and byte counts match the first scan. The digest is SHA-256 over sorted `(relative filename UTF-8, NUL, file SHA-256 ASCII, NUL)` records. It includes **all** files under each dataset snapshot's `raw/T03`, including rows belonging to another dataset.

| Snapshot directory | Raw files | Raw bytes | Raw-tree SHA-256 |
|---|---:|---:|---|
| NY | 33,974 | 476,006,210 | `f14fce3e9168e227f7a7b4a545f9d08334d8932f312f188c0c2ff0c6dbce32da` |
| FLA | 34,083 | 422,828,445 | `de658f9e418474536405c07158842f9d76ec2beff115a02956fc7b5c35529770` |
| CAL | 30,000 | 454,136,475 | `dae6c7afa4135ffd0a0f32d5c5bc0f1671b89d174eed4b5966c7f6ea88949444` |

The NY directory also contains 3,650 ledger-identified FLA rows and 324 NY allFP rows. The FLA directory contains 4,083 ledger-identified CAL rows. Counting filenames alone would therefore give incorrect dataset/method totals. The canonical-ledger joins find no duplicate raw job IDs, duplicate ledger job IDs, unknown raw job IDs or input/serialized identity mismatches. allFP is outside the final two-method comparison and remains incomplete/skipped; its rows are preserved.

## Full T03 matrix: independent counts

Each dataset/method has 5,000 distinct logical query IDs and 15,000 planned query/trial jobs. The final two-method scope has complete **terminal row coverage**, which is different from successful algorithm completion.

| Dataset | Method | Terminal jobs | Completed | TIMEOUT | Time-capped | Completed/terminal |
|---|---|---:|---:|---:|---:|---:|
| NY | PACE-B | 15,000 | 15,000 | 0 | 0 | 100% |
| FLA | PACE-B | 15,000 | 12,743 | 2,257 | 0 | 84.953333% |
| CAL | PACE-B | 15,000 | 15,000 | 0 | 0 | 100% |
| NY | iSCOPE | 15,000 | 0 | 0 | 15,000 | 0% |
| FLA | iSCOPE | 15,000 | 0 | 0 | 15,000 | 0% |
| CAL | iSCOPE | 15,000 | 0 | 0 | 15,000 | 0% |

No additional resource-truncated, failed/error, missing-job or duplicate-job row is present in these six scoped groups. iSCOPE's capped outputs remain partial and uncertified even when a profile is serialized. No completion percentage has been inferred from profile existence.

The following are **completed-trial-only** full-matrix statistics, recomputed directly from serialized `query_total` and `average_selected_score`. They are not the manuscript's 200-query slice or its two-level aggregation.

| Dataset | Completed trials | Runtime mean (s) | Runtime median (s) | Score mean | Score median |
|---|---:|---:|---:|---:|---:|
| NY | 15,000 | 3.3432583821 | 3.2947159135 | 327.0311658333 | 291.1833333333 |
| FLA | 12,743 | 8.6865831983 | 8.6728044850 | 211.7075080654 | 195.6600000000 |
| CAL | 15,000 | 1.2746739716 | 1.0574883100 | 270.6323072222 | 278.2977777778 |

All 2,257 FLA timeout rows are excluded from these completed-only means/medians. They are retained in the terminal denominator. iSCOPE has no completed-only mean or median because its completed set is empty. Capped-profile score and coverage distributions are separately recorded in the machine-readable attestation, with null score values excluded rather than imputed as measured scores. No uncertainty interval was invented: the archived scalability aggregation does not define one.

`FINAL_RESULT_INDEX.md` agrees with the scoped status counts. Progress records describe their larger source run roots and include other datasets and skipped allFP work; their `completed_effective` field counts terminal/skip accounting. It is not a successful-completion statistic and does not supersede these dataset-filtered raw counts.

## Dataset, query and configuration identities

The attestation preserves the environment JSON, immutable run identity, effective-config SHA-256, canonical-ledger SHA-256, preflight identity, query marker and main marker status.

| Dataset | Recorded graph checksum | Current query-manifest SHA-256 | Historical query-marker comparison |
|---|---|---|---|
| NY | `97999bd124ed9639234bc1a9334b0426911a234b1ed80b5093ec1b30a8ade675` | `d825450cbc9f7a0b244ce3e35c5c61a82813c0862eeb5b630bb6928350c8f244` | No NY query marker in this snapshot; current hash alone is not proof of original bytes. |
| FLA | `19329caf5c0f725589bd30667f513ea4b6b6bbd24854cebd0ad36aa8b3e7b34d` | `5380579aa05085892db9984f1f259f1262af7e20678a8bc3341bacf84dcbbcad` | Matches original marker. |
| CAL | `13e4c7b0c625fedb110c1af4c392122cda95aac29ec67f6f61fb6b24ed5fc52b` | `1ec64f72b59683b42c9bab1f2fc14efc8f0aa24f8d78af3fa49d19560a779663` | Matches original marker. |

These graph checksums are recorded historical preflight identities. R6 does not silently replace their historical scope with a newly calculated dataset payload definition. The FLA/CAL budgets use the explicit NY-matched workload family. Configuration identities from `IMMUTABLE.json` are NY `1ac22e4668a684e7e52a3c277f762ae3e1912e91eecc05175a2725e93917a4b3`, FLA `107f22a2da9a1bcfc8d1018238b696789cfc448257d6a15e1a96e050efc84f3c`, CAL `03a4c26e3686eaf58df636d51c93ca8a924cad2a0ce2d8a370a9a4f4c58fc92a`.

## Manuscript slice and exact reaggregation

The full matrix above must not be confused with the manuscript's W=120 minutes, rho=0.30 slice: 100 OD pairs × 2 centers = 200 logical queries, each with three measured trials. The archived extractor `manuscript_artifacts/internal_superseded_ol/scripts/00_extract_and_freeze_manuscript_data.py` has SHA-256 `12431f2b41da09e3088bea283ecee1ee4e4e536a9d1ba0b9805ac3e1b223a8e0`. Its pure record adapter, default-slice selector and trial/query aggregation functions were invoked without its output-producing main routine. Its ROOT was explicitly rebound to the snapshot because it was moved into a superseded folder.

The input bundle has 11 work JSONL files, 1,415,157,667 bytes, tree digest `d29eb3cdb77591d8416f5555ac18b5d505ff4b86a6e8bbe8ece270c454cb6ed2`. The FLA PACE-B slice explicitly selects the separate `fasttrack_scalability_repairs_reuse_final` batch with 600 completed trials; NY and CAL use their canonical T03 batches. No FLA full-matrix timeout is included in that selected fasttrack subset. No old and repaired trials were mixed in one query median.

| Dataset | Method | Logical queries / trials | Successful trials | Median query time (s) | Median query average score | Recorded cap | Threads |
|---|---|---:|---:|---:|---:|---:|---:|
| NY | PACE-B | 200 / 600 | 600 | 3.2853846475 | 376.8916666667 | 10 s | 24 |
| NY | iSCOPE | 200 / 600 | 0 | 4.5059635365 | 295.4967989083 | 5 s | 1 |
| FLA | PACE-B, fasttrack | 200 / 600 | 600 | 0.8874258385 | 222.2291666667 | 5 s | 24 |
| FLA | iSCOPE | 200 / 600 | 0 | 4.5048209920 | 173.8962996244 | 5 s | 1 |
| CAL | PACE-B | 200 / 600 | 600 | 0.9672451970 | 306.6166666667 | 10 s | 24 |
| CAL | iSCOPE | 200 / 600 | 0 | 4.5080162425 | 82.6258548025 | 5 s | 1 |

All six values reproduce the manuscript/plot constants to their displayed precision. Original differences between these values and the full-matrix `paper_existing/t03_scalability_current_protocol.csv` arise from the different workload slice, the FLA fasttrack replacement, and different aggregation definitions. They are not evidence that the raw index count is wrong.

Two actual presentation defects were found at original `PACE.tex` lines 2548–2578 and `manuscript_artifacts/scripts/02_scalability_runtime_score.py`:

1. **Mean/median mismatch.** The extractor computes the median across logical queries after taking each query's trial median. For score it aggregates the route profile's average selected score in that same way. The manuscript column and plot say “Mean returned score.” For example, NY's displayed 376.8916666667 is the median; the arithmetic mean across its 600 trial scores is 401.8185000000. The label must be corrected; no raw value needs to change.
2. **Cap/thread mismatch.** The claim of a common five-second protocol is unsupported for the NY/CAL PACE-B rows, whose serialized caps are ten seconds. PACE-B uses 24 threads and iSCOPE one. Being below five seconds at the median does not establish a five-second timeout boundary or identical resources. State the observed mixed caps/threads explicitly. The corrected prospective primary comparison uses one thread and explicit per-method caps.

The old plot/aggregation artifacts under `experiments/results` remain untouched. The tracked manuscript can correct labels and disclose quarantine, but that does not turn the dirty historical measurements into current-code evidence.

## Dirty-source recovery and semantic changes

| Dataset | Available base commit | Base Git tree | Original dirty-diff/content hash |
|---|---|---|---|
| NY | `ed0eef27ac63860d40ca8aee679be26e0cde47c5` | `0fc75bb48a3a7366cf7a5053716d21da1a702a42` | `847fcf3a29d8ce43b6886170441e2e17b2d45306581eb14bc4490ccf51c6ae28` |
| FLA | `eebb1f79600d2cda1ea8f1839d965bc76637987d` | `a203d72609253e9619f095135609f3c1e0773dc4` | `4b0b6308b39726937e016b759d3b586f04dcec58da57ba6df582ff502d4f8206` |
| CAL | `d4f740a80834395cb83feda928ec671bde6e7e95` | `3dddf21e447b4b111757db4e2af1202a88c4879d` | `b7f4b57f8a0d5b743be7de576205e611e48b8308752598e0fa514678593993ce` |

All three environment records have `dirty=true`. In `common/provenance.py`, `working_tree_hash` is SHA-256 over a binary Git diff plus untracked filenames/content. It is **not** a Git tree object ID. A base commit and that one-way digest cannot recreate missing files.

Read-only recovery inspected available Git revisions, reflogs, stash list, worktree list, local source snapshots, patch/bundle/archive names and JARs. No stash exists; only the current worktree exists. `git fsck --no-reflogs --unreachable` inventoried 341 trees and two commits, including 36 complete repository-root tree candidates. The two commits are an earlier July 31 candidate-engine change and an October 15 paper addition; neither is an archived dirty-run bundle. No complete patch plus untracked contents tied to the recorded dirty hash was recovered. We do not claim every unreachable tree was reconstructed and tested as a historical working tree.

The available archived JARs are the NY exact-parameter study JAR (SHA-256 `b3bcc53d96403b66ea6a67089f2e5ce1383ae8f4a45d60594e1420fac4aecc13`, two copies) and NYC case-study JARs (`983b99e7a795f5cf6cf027375a1a5eedcac9a9acc079330156c310ac4310771a`, `3d73adce5bea68f1248e807627a42f075d8a1a864fe3437b9031a0508e0963e1`). Their existence is recorded; none has a source/run binding establishing the NY/FLA/CAL T03 binary. The recorded live filename `target/pace-bench-5s-optimized.jar` is not an archived binary identity. Source copies from the later NYC case study and the M5 clean checkout do not recover the dirty T03 source.

Between CAL's base commit and audited `508a1d8`, nine PACE production files change (511 insertions, 179 deletions):

| File | Material source difference |
|---|---|
| BoundedConnectorGenerator | Replaces reused lower-bound witnesses with a FIFO fastest connector chosen at each request-cell midpoint; removes the old grid-replay branch. Candidate generation and work therefore differ. |
| ForwardLayeredFrontierGenerator | Adds diagnostics, changes empty-frontier status paths, removes grid-replay specialization and changes replay-work admission. |
| PACE | Moves graph validation into cached graph preparation; changes initial/legacy empty-result completion and adds diagnostics. Query/preprocessing timing changes. |
| PaceOptions | Renames and redefines the one-connector policy as exact-midpoint selection. |
| PaceGenerationResult | Adds the global certificate precondition for NO_FEASIBLE_PATH. |
| PaceCompletion | Adds UNRESOLVED_NO_GENERATED_CANDIDATE. |
| QueryScopedConnectorLabelStore | Removes attached reusable F/B witness wiring and describes the new midpoint connector structure. |
| GridPathProfileBuilder | Uses compacted piecewise construction. |
| PaceDiagnosticTrace | Adds diagnostic state and counter reporting. |

These are behavioral and accounting differences, so old timing/score data cannot be attributed to the repaired source by updating a commit label.

## Disposition and replacement obligations

Historical reproducibility: **PARTIAL / source unrecovered**. Byte integrity, scoped terminal counts, FLA timeout exclusion and the six displayed aggregate values are validated; dirty source/JAR lineage remains unresolved. No historical success gate is asserted.

Current implementation readiness is evaluated in the M5-R repair report and is independent of this historical conclusion. Future reproducibility uses the new `icde2027_v1` configuration family and enforced clean source/JAR/config/data/query freeze. Historic configurations are preserved, historic rows cannot be reused in the new family, and no campaign was launched by R5/R6.

Before publication, replace `tab:scalability_main` and `fig:scalability_main` with measurements from the clean prospective preference track including SICSP-Pref. Replace any prose using those dirty measurements as evidence for the repaired PACE revision. The NY exactness/policy, stage, sensitivity and parallel artifacts have their own provenance limitations and are not cleared by validating this scalability slice. They also require revision-specific validation or replacement; this report does not extend its attestation to them.

The tests deliberately cover timeout contamination, false completed flags, duplicate/extra/missing IDs and preservation of the original raw bytes and missing-marker state. The exact focused results and final full-suite totals are recorded in the parent M5-R report.
