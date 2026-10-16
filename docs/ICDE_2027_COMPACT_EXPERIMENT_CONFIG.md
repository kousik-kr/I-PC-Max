# ICDE 2027 compact experiment configuration

**Configuration:** `experiments/configs/icde2027_compact_v1/compact.yaml`
**State:** planned configuration only. No full-network job, controlled query job, freeze, or experiment dispatch was run or approved.

## Workloads

`C01` uses 50 frozen historical queries in each of NY, FLA, and CAL, for PACE-B, basic iSCOPE, and THop, with two measured trials. Matrix expansion yields 900 C01 jobs (100 per method per dataset). `C02` uses the same 20 controlled NY-EXACT queries for PACE-X, PACE-B, iSCOPE, and SICSP-Pref, with one measured trial (80 jobs). The manifest audit documents the source IDs and hashes.

The basic iSCOPE adaptation is labelled as such. THop is the preference-free fastest-profile method and ignores the preference and travel-budget fields. SICSP-Pref appears only in C02; large-network participation requires the later pilot decision and is not included here.

## Timeout and resource policy

| Method | Query timeout | Scope |
| --- | --- | --- |
| PACE-B | Explicit `unlimited` | C01 and C02; bounded intrinsic `L`, `K_c`, `K_f`, `M_c`, `M_b`, and `M_q` policies remain configured and reported. |
| THop | Explicit `unlimited` | C01 only; no query wall-clock watchdog. The configured finite memory limit remains in force. |
| iSCOPE | Explicit 5 seconds | C01 and C02; any capped result remains `NOT_CERTIFIED`. |
| SICSP-Pref | Explicit `unlimited` | C02 controlled workload only. Large-network scope remains pilot-dependent. |
| PACE-X | Explicit `unlimited` | C02 controlled workload only. Exact-reference quality requires `GLOBAL_CERTIFIED` plus independent rational validation. Current implementation does not meet that condition. |

`unlimited` is an explicit token in both the Python configuration and Java CLI. Missing family timeout, `null`, and numeric zero are rejected by Python configuration validation; Java rejects numeric `--timeout-seconds 0` and records a missing legacy setting as `UNSPECIFIED_LEGACY`. Per-method omission inherits only a validated explicit family default. The local Python runner passes `timeout=None` for these jobs and uses `Popen` without a query watchdog for shared-preprocessing batches. Slurm dispatch is rejected when any selected method is unbounded because scheduler wall time would impose a hidden deadline.

Preprocessing retains a separate 1800-second cap. The Java process retains `memory_limit_mb: 256000`, and JVM error reporting remains active. No memory cap is disabled to obtain unlimited query time. PAR2 does not invent a finite penalty for unlimited methods; without a finite cap there is no PAR2 timeout sample.

## Execution prerequisites

The THop index paths are load-only placeholders under `data/indices/icde2027_compact_v1/{dataset}`. Full-network THop index construction is deferred. The configured isolated jar path is `target/pace-bench-icde2027-compact-v1.jar`; it is not a freeze artifact. The config requires a new freeze before any execution, historical result reuse is disabled, and output paths are isolated under `experiments/results/icde2027_compact_v1`.

The synthetic runner regression verifies that an unlimited Java command carries the token `unlimited` and that Python invokes it with no subprocess deadline while retaining the memory argument. Matrix construction is tested only as a plan and validates exact per-dataset job counts. The PACE numerical pruning audit remains open for global certification; this configuration is not approved for execution or freeze.
