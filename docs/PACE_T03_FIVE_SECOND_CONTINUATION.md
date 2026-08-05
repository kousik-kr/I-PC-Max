# PACE T03 five-second continuation

## Protocol boundary

This continuation changes only the large-network scalability study. The unchanged
PACE-X exactness/certification configuration remains
`experiments/configs/paper_q1_server_24c_250g.yaml`; PACE-X is not present in the
five-second T03 configuration and is not retroactively capped.

The T03 source of truth is
`experiments/configs/paper_q1_server_24c_250g_5s.yaml` plus
`experiments/configs/studies/t03_scalability_main_5s.yaml`:

| Dataset | PACE-B | iSCOPE | allFP | Logical total |
|---|---:|---:|---:|---:|
| NY | 15,000 | 15,000 | 15,000 | 45,000 |
| FLA | 15,000 | 15,000 | 15,000 | 45,000 |
| CAL | 15,000 | 15,000 | 15,000 | 45,000 |
| Total | 45,000 | 45,000 | 45,000 | 135,000 |

`rpq` and `interval-best` remain registered and historical rows remain immutable,
but neither appears in the new T03 manifest. iSCOPE is not an alias for
`interval-best`.

T03-A compares the preference-aware PACE-B and iSCOPE methods. T03-B treats allFP
as a preference-free continuous fastest-profile reference; the summary pipeline
reports score gain, travel-time detour, and runtime overhead without claiming an
objective-equivalent win over allFP.

## Five-second timing and result semantics

PACE-B, iSCOPE, and allFP use one logical query at a time. PACE-B and allFP use at
most 24 internal workers; iSCOPE remains serial. The heap ceiling is one JVM-wide
`-Xmx250g`, not 250 GiB per worker. iSCOPE and allFP start a monotonic internal
five-second query deadline and use a seven-second external watchdog only for emergency
cleanup. The minimum-edge weight array (and allFP's common support end) is immutable,
query-independent state built once in `prepare` and charged to separately reported
shared preprocessing. Destination-specific reverse Dijkstra remains inside each
measured allFP/iSCOPE search; allFP's four rho-only projection rows reuse the
corresponding measured profile because budget cannot affect allFP search.
Functional/profile construction, validation, and incremental envelope construction
remain inside every five-second query deadline.
iSCOPE's exact replay uses the canonical anchor-free budget context: it preserves
the same arrival, score, horizon, and feasibility functions but does not construct
an `AnchorIndex`, because iSCOPE enumerates paths and never selects pivots.

Shared-preprocessing batches do not use fail-fast termination: a terminal query
timeout or cap is appended immediately, and the already loaded dataset is retained
for the next query. Fatal worker/process failures still stop with their existing
nonzero exit handling and the batch remains resumable.

Capped iSCOPE/allFP rows are valid anytime rows with
`TIME_CAPPED_NOT_CERTIFIED` or `PATH_CAPPED_NOT_CERTIFIED`; they are never exact.
Only uncapped exhaustive/proof completion can emit `CERTIFIED_COMPLETE`.
If a destination-specific reverse Dijkstra consumes iSCOPE's query budget before an
incumbent is found, the valid result is an empty, zero-coverage
`TIME_CAPPED_NOT_CERTIFIED` row. It is retained as cap evidence, not interpreted as
an infeasibility proof or a quality observation.

allFP is budget-independent. The matrix deliberately retains 45,000 logical rows
for matched tables, while the shared JVM performs 9,000 measured continuous
fastest-profile searches and produces 36,000 post-hoc rho projections. Every
projected row carries the source query ID/budget, the source measured timing and
memory record, `allfp_search_executed=0`, and
`allfp_budget_variant_reuse_hit=1`. Search rows carry the complementary markers.
The reconciliation audit rejects a T03 shape whose budget-variant groups are not
exactly five.

## Historical v6 reconciliation result

Prepared run ID: `pace_q1_t03_5s_iscope_allfp_20260804_v6`.
The run-specific executable is `target/pace-bench-5s-v6.jar`, SHA-256
`69007f2e49d758f5d8893462fda42fd89aa362665e3db3253f6043565e973c30`.
These v6 pilot records are immutable historical evidence. The optimized code in
this continuation builds `target/pace-bench-5s-optimized.jar` and must use a new
run ID; it never appends optimized records to the v6 raw ledger.

The ten-second source run and JAR are read only. The source JAR checksum is
`1a93867f63413165340d960ddf40c664ffd84ce137d91c1f553bff323ebf680c`.
The deterministic audit currently finds:

- reusable PACE-B: 0;
- remaining PACE-B: 45,000;
- planned iSCOPE: 45,000;
- planned allFP: 45,000;
- execution manifest: 135,000.

All 15,000 historical PACE-B rows lack the new explicit exact-profile,
feasible, and loopless validation evidence, so fail-closed reconciliation schedules
them instead of relabelling them. Additionally, 327 recorded runtimes exceed five
seconds and 4,323 rows declare a different source commit (these reasons overlap the
missing-evidence population). The original raw ledgers are unchanged.

Artifacts are under:

`experiments/results/pace_q1_t03_5s_iscope_allfp_20260804_v6/plan/`.

The plan reuses the last passed deep NY/FLA/CAL checksum preflight as immutable
data/query evidence. Reuse is accepted only because every required payload predates
that report, current query-manifest checksums still match, implementation gates are
unchanged, and all resource fields other than the declared watchdog durations match.
The resulting provenance retains the evidence source and reapplies the current
five-second resources; it is not a plan-only substitute for deep validation.

## Optimized v7 commands

Rebuild/audit the plan without starting algorithms:

```bash
python3 experiments/scripts/run_all.py \
  --config experiments/configs/paper_q1_server_24c_250g_5s.yaml \
  --run-id pace_q1_t03_5s_optimized_20260805_v8 \
  --backend local --study T03 --max-concurrent 1 --plan-only --resume \
  --reuse-preflight experiments/results/pace_q1_scalability_shared_aggressive_10s_20260802d/provenance/preflight.json

python3 experiments/scripts/reconcile_pace_b_5s.py \
  --config experiments/configs/paper_q1_server_24c_250g_5s.yaml \
  --matrix experiments/results/pace_q1_t03_5s_optimized_20260805_v8/plan/matrices/t03.jsonl \
  --source-run experiments/results/pace_q1_scalability_fb_witness_grid_10s_20260803c \
  --output experiments/results/pace_q1_t03_5s_optimized_20260805_v8/plan/reconciliation \
  --validate-only
```

The deterministic stratified pilot contains trial 0 for an easy (`W=120`,
`rho=0.10`) and hard (`W=360`, `rho=0.50`) query in every dataset/distance-band
stratum: 30 matched queries and 90 jobs. Generate/audit it with:

```bash
python3 experiments/scripts/prepare_t03_5s_pilot.py \
  --execution-manifest experiments/results/pace_q1_t03_5s_optimized_20260805_v8/plan/reconciliation/execution_manifest.jsonl \
  --output experiments/results/pace_q1_t03_5s_optimized_20260805_v8/plan/pilot

python3 experiments/scripts/execute_matrix.py \
  --config experiments/configs/paper_q1_server_24c_250g_5s.yaml \
  --run-id pace_q1_t03_5s_optimized_20260805_v8 \
  --matrix experiments/results/pace_q1_t03_5s_optimized_20260805_v8/plan/pilot/pilot_manifest.jsonl \
  --backend local --max-concurrent 1 --dry-run
```

Pilot execution command (one new query process at a time, in the isolated run):

```bash
python3 experiments/scripts/execute_matrix.py \
  --config experiments/configs/paper_q1_server_24c_250g_5s.yaml \
  --run-id pace_q1_t03_5s_optimized_20260805_v8 \
  --matrix experiments/results/pace_q1_t03_5s_optimized_20260805_v8/plan/pilot/pilot_manifest.jsonl \
  --backend local --max-concurrent 1 --resume
```

After the 90 jobs finish, audit terminal status, five-second timing, exact replay,
looplessness, matched identities, and allFP objective independence with:

```bash
python3 experiments/scripts/audit_t03_5s_pilot.py \
  --pilot-manifest experiments/results/pace_q1_t03_5s_optimized_20260805_v8/plan/pilot/pilot_manifest.jsonl \
  --raw-root experiments/results/pace_q1_t03_5s_optimized_20260805_v8/raw/T03 \
  --output experiments/results/pace_q1_t03_5s_optimized_20260805_v8/plan/pilot/pilot_audit.json
```

The historical v6 pilot passed this audit with 90/90 terminal records and 30
records per algorithm.  Its status totals are 9 `SUCCESS`, 24 PACE-B
`TIMEOUT`, and 57 internally returned `TIME_CAPPED_NOT_CERTIFIED` records.
Neither iSCOPE nor allFP required an external watchdog timeout.  Re-running the
pilot command with `--resume` left all 90 raw files unchanged: the deterministic
aggregate raw-record SHA-256 was
`95bde31a07d8062070d755d94378b264e994da2e5fbc7f68af2b0d1956d2f1d6`
both before and after the resume check.

Independent duplicate-aware progress is available at any point:

```bash
python3 experiments/scripts/t03_5s_progress.py \
  --run-root experiments/results/pace_q1_t03_5s_optimized_20260805_v8 \
  --output experiments/results/pace_q1_t03_5s_optimized_20260805_v8/provenance/progress.json
```

Full resume command, supplied for handoff but not launched:

```bash
python3 experiments/scripts/run_all.py \
  --config experiments/configs/paper_q1_server_24c_250g_5s.yaml \
  --run-id pace_q1_t03_5s_optimized_20260805_v8 \
  --backend local --stages main --study T03 --max-concurrent 1 --resume \
  --reuse-preflight experiments/results/pace_q1_scalability_shared_aggressive_10s_20260802d/provenance/preflight.json
```

The full command consumes the reconciliation execution manifest and skips any pilot
rows already materialized with matching job identity.
