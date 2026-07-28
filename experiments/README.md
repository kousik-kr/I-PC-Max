# PACE experimental framework

## Q1 single-command workflow

The publication controller is `experiments/scripts/run_all.py`. The supported paper datasets are
NY, FLA, CAL, and USA; OL is intentionally excluded. Python orchestrates Java and validates its
records but never loads or reimplements the road graph.

```sh
make paper-preflight
make paper-generate-assets
make paper-validate-assets
make paper-generate-queries
make paper-validate-queries
make paper-preflight-server
make paper-smoke
make paper-plan RUN_ID=pace_q1_review
make paper-reproduce RUN_ID=pace_q1_final BACKEND=local MAX_CONCURRENT=1
make paper-reproduce-server
make paper-monitor-server
```

`paper-preflight` is read-only and performs stable file/checksum/count/support checks.
`paper-generate-assets` materializes the declared DIMACS-weight normalization in
`experiments/configs/dataset_generation.yaml`: `minutes = DIMACS_weight * 1 / 6000`.
Generated assets explicitly cover `[0,10080]`; travel time is flat after minute `1440`
at the converted static travel time, and score is zero after minute `1440`.
`paper-plan` writes the exact E00-E13 matrix and starts no algorithm. `paper-smoke` runs the Maven
suite plus a tiny/demo end-to-end workflow. `paper-reproduce` is the only full execution target;
it refuses the default planning run ID and is safe to resume only when the config, Git state, and
backend identity match.

Generated state lives under `experiments/results/<run-id>/` with separate plan, raw, log,
normalized, summary, table, figure, provenance, marker, and release directories. Use
`make paper-clean RUN_ID=<id>` to remove exactly one generated run. Large data and all generated
experiment state are ignored by Git.

Before a full run, fill the fields in `experiments/configs/SERVER_PROFILE_TEMPLATE.yaml` and apply
the resolved memory/concurrency values to `paper_q1.yaml`. The server profile used here is captured
in `paper_q1_server_24c_250g.yaml`. The dataset conversion contract is an author release decision,
not an official DIMACS physical-unit claim; see `experiments/DATASET_GENERATION.md`.

`paper-generate-queries` derives its split sizes, time centers, windows, budgets, and graph variants
from `paper_q1_server_24c_250g.yaml` and E00-E13. Python performs configuration and asset checks;
the production Java loader and routing code select and validate graph-backed pairs. Outputs are
written under `experiments/manifests/queries/<dataset>/`: `paper_q1.jsonl` is the combined runner
manifest, and `pilot.jsonl`, `warmup.jsonl`, and `evaluation.jsonl` are audited split exports with
checksum sidecars. Generation stops before loading a graph if a required file, temporal horizon, or
required score-density/graph-seed variant is missing. Score-density variants are required for NY;
graph seeds 42/43/44 are required for both NY and CAL.

`paper-resume-assets`, `paper-plan-assets`, `paper-resume-queries`, and
`paper-plan-queries` provide the corresponding resumable and no-write planning
modes. Query budgets use exact fixed-departure fastest paths on the declared
one-minute grid, are canonicalized after applying rho, and are rejected unless
`interval_end + budget` remains inside minute 10080. The Java preparation path
also builds edge temporal summaries, stable graph cells, and cell-local score
support indexes from the already loaded canonical graph. Exact Dijkstra remains
the fallback lower-bound oracle; no scalable continental routing index is
claimed.

This directory contains the reproducible experiment layer for PACE. All methods use the same
`ExperimentAlgorithm` interface, graph implementation, `QuerySpec`, exact path replay, envelope
extractor, manifest reader, result writer, and matrix scheduler. PACE-X and PACE-B use the
forward-layered scalable engine by default; the former recursive engine is available only through
`--pace-engine legacy` for fixture diagnostics. Ablations are feature switches on PACE-B.

## Requirements and build

Use JDK 21+, Maven 3.9+, Python 3.10+, and a POSIX shell (Git Bash or WSL on Windows). PyYAML and
`jsonschema` are optional because the checked-in configurations are JSON-compatible YAML and the
validator includes structural checks. The Windows launcher is `pace_bench.cmd`; the POSIX launcher
is `./pace_bench`.
For this pure-Java project, `scripts/build_experiments.sh --sanitizers` enables assertions,
JNI checks, and full compiler linting (the JVM analogue of the native sanitizer target).

```sh
make configure
make build
make test
make test-unit
make test-integration
make test-experiments
```

The Maven package step creates `target/pace-bench.jar`. `make benchmark-smoke` runs the practical
seven-method smoke matrix. `make validate-results RESULT_INPUT=results/raw/smoke` and
`make summarize-results RESULT_INPUT=results/raw/smoke` validate and summarize it.

## Algorithms and ablations

Stable algorithm identifiers are `pace-x`, `pace-b`, `exh-profile`, `pl-exact`, `rpq`,
`ksp-profile`, and `interval-best`. Paper labels RPQ-1/5/15 use `--rpq-step-minutes 1/5/15`;
KSP-Profile-k uses `--baseline-k k`.

The final E10 PACE-B variants are `none`, `no-safe-corridor`,
`no-pivot-diversification`, `fast-only-connector`, `no-connector-cache`,
`no-score-upper-bound`, `no-memo`, `no-compression`, theta zero, and `serial`.
Legacy diagnostic switches remain CLI-compatible but are not part of the final Q1 ablation table.
`no-memo` disables memoization and both single-flight caches; `serial` forces one thread.
PACE-B is exact over its retained frontier, not globally exact. RPQ is sampled with left-closed,
right-open cells and a separately evaluated final endpoint. IntervalBest returns one selected
departure/path and an evaluation-only profile for that path.

Completion, requested PACE policy, and exactness scope are separate fields. PACE-X is
`GLOBAL_CERTIFIED` only when exhaustive controls are independently verified, every score-relevant
corridor pivot is selected, theta covers the selected set, and no cap is reached. PACE-B always
uses `RETAINED_FRONTIER`. Resource-truncated PACE-X is `ABORTED`, fails closed, and is
`NOT_CERTIFIED`.

## Common command line

General flags: `--algorithm`, `--ablation`, `--dataset`, `--query-file`, `--output-jsonl`,
`--output-csv`, `--experiment-name`, `--repetitions`, `--warmup-runs`, `--threads`,
`--timeout-seconds`, `--memory-limit-mb`, `--seed`, `--deterministic`, `--verify-output`,
`--fail-fast`, and `--resume`.

PACE flags are `--theta`, `--pivot-limit-l` (`--anchor-limit` alias),
`--connector-limit-kc`, `--connector-expansion-cap-mc`, `--frontier-limit-kf`,
`--breakpoint-cap-mb`, `--query-work-cap-mq`, and `--pace-engine`.
The compatibility flag `--k` sets both K_c and K_f; finalized configurations
should set them independently. Baseline/guard flags are
`--rpq-step-minutes`, `--baseline-k`, `--max-enumerated-paths`, `--max-labels`,
`--max-expansions`, and `--max-frontier-fragments`. Reference flags are `--reference-jsonl` and
`--reference-algorithm`. Collection/output flags are `--collect-phase-timings`,
`--collect-memory`, `--collect-internal-counters`, `--serialize-profile`, and
`--profile-output-dir`. Deterministic query generation uses `--query-count`, `--query-seed`,
`--distance-bins`, `--window-minutes`, `--budget-slack`, `--budget-policy`, and
`--query-manifest-output` when `--query-file` is omitted. The complete normalized effective
configuration is printed at startup and hashed into every result record.

## Manifests and records

The manifest reader accepts legacy schema version 1 and generated schema version 2. New generated
manifests use version 2 and include deterministic family IDs, dataset/checksum provenance, temporal
regime, lower-bound and fastest-profile metadata, generator/config identity, and metadata. A
manifest is loaded once and never regenerated per method. Invalid rows and duplicate query IDs fail
before execution.

New raw results use schema version 3 in `schemas/result_record.schema.json`; the schema retains the
version-1 and version-2 status shapes for compatibility. Records contain one row per experiment,
algorithm, ablation, query, repetition, and warmup. The top-level sections are `run`, `system`,
`dataset`, `query`, `configuration`, `status`, `timing_ns`, `memory_bytes`, `counters`, `output`,
`quality`, and `error`. Timings are integer nanoseconds; unavailable fields are JSON null. Statuses
include completed/no-path, timeout, out-of-memory, limit, horizon, invalid query/configuration, and
unexpected error. Version 2 status also records `execution_policy` and `exactness_scope`.
`NO_FEASIBLE_PATH` is completed. Profile checksums use canonical
breakpoints, interval closure, paths, arrival profiles, and score profiles. Version 3 adds the
engine mode, independent PACE limits, generation completion, typed cap flags, partial-output
policy, certificate conditions, corridor/candidate/cache counters, process CPU time, and JVM heap
samples. The Linux harness also records start/end/peak process RSS, and outputs include exact
interval-weighted average and best selected scores.

The release pipeline creates F1-F10 in PDF, SVG, and PNG plus a JSON provenance sidecar for every
figure. It creates T1-T12 as CSV and LaTeX, then copies them with one run-bound
`manuscript_macros.tex`, `VALIDATION_REPORT`, and `CLAIM_SUPPORT_MATRIX` into the release package.
Plot and table stages are blocked until result validation passes; failed, timed-out, OOM, and
cap-truncated cells remain in the aggregates.

When a timeout or memory limit is configured, `pace_bench` automatically runs each logical query
and repetition in a dedicated JVM. The parent enforces a hard wall-clock deadline, applies `-Xmx`
for the configured memory ceiling, preserves the worker's formal record, and synthesizes a valid
TIMEOUT/OUT_OF_MEMORY/ERROR record if the worker is terminated before it can serialize one. A
single failed query therefore cannot leave computation running inside later matrix jobs.

Online query time excludes shared dataset loading/lower-bound setup and includes algorithm-specific
per-query work. Shared preprocessing is reported separately as `preprocessing_total`. Quality
comparison is interval based; a missing candidate path receives score zero under the nonnegative
score model and feasibility disagreement is reported separately.

## Configurations and output layout

`configs/` provides smoke, exactness, main comparison, L/K/theta sensitivity, scalability, main and
appendix ablations, and parallelism matrices. `run_matrix.py --dry-run` prints normalized commands;
`--resume` skips completed run IDs; `--only-failed` uses the scheduler log; every process receives a
dedicated log. Raw data goes under `results/raw`, profiles under `results/profiles`, summaries under
`results/summaries`, logs under `results/logs`, and generated manifests under `results/manifests`.
Large result directories are ignored by Git.

```sh
python scripts/run_matrix.py --config experiments/configs/smoke.yaml --jobs 1
python scripts/run_matrix.py --config experiments/configs/exactness.yaml --jobs 1
python scripts/run_matrix.py --config experiments/configs/main_comparison.yaml --jobs 1
python scripts/run_matrix.py --config experiments/configs/sensitivity_l.yaml --jobs 1
python scripts/run_matrix.py --config experiments/configs/sensitivity_k.yaml --jobs 1
python scripts/run_matrix.py --config experiments/configs/sensitivity_theta.yaml --jobs 1
python scripts/run_matrix.py --config experiments/configs/ablation_main.yaml --jobs 1
python scripts/run_matrix.py --config experiments/configs/ablation_appendix.yaml --jobs 1
python scripts/run_matrix.py --config experiments/configs/parallelism.yaml --jobs 1
```

## Direct examples

```sh
./pace_bench --algorithm pace-x --dataset demo --query-file experiments/manifests/tiny.jsonl --theta 4 --threads 1 --repetitions 1 --deterministic --output-jsonl results/raw/pace_x.jsonl
./pace_bench --algorithm pace-b --dataset data/input/out_ny_td --query-file experiments/manifests/main.jsonl --theta 2 --pivot-limit-l 32 --connector-limit-kc 16 --connector-expansion-cap-mc 5000000 --frontier-limit-kf 16 --breakpoint-cap-mb 1000000 --query-work-cap-mq 5000000 --threads 8 --repetitions 3 --deterministic --output-jsonl results/raw/pace_b.jsonl
./pace_bench --algorithm exh-profile --dataset demo --query-file experiments/manifests/tiny.jsonl --max-enumerated-paths 100000 --threads 1 --output-jsonl results/raw/exh_profile.jsonl
./pace_bench --algorithm pl-exact --dataset demo --query-file experiments/manifests/tiny.jsonl --max-labels 1000000 --max-expansions 5000000 --output-jsonl results/raw/pl_exact.jsonl
./pace_bench --algorithm rpq --rpq-step-minutes 5 --dataset demo --query-file experiments/manifests/tiny.jsonl --output-jsonl results/raw/rpq_5.jsonl
./pace_bench --algorithm ksp-profile --baseline-k 32 --dataset demo --query-file experiments/manifests/tiny.jsonl --output-jsonl results/raw/ksp_32.jsonl
./pace_bench --algorithm interval-best --dataset demo --query-file experiments/manifests/tiny.jsonl --output-jsonl results/raw/interval_best.jsonl
```

Every ablation uses the PACE-B command shape below; substitute each identifier from the list above.

```sh
./pace_bench --algorithm pace-b --ablation no-safe-dom --dataset demo --query-file experiments/manifests/tiny.jsonl --theta 2 --anchor-limit 32 --k 16 --threads 1 --output-jsonl results/raw/no_safe_dom.jsonl
```

To reproduce paper data, run the named matrix, validate its raw directory against the matching
manifest, then summarize that same directory. The produced `per_run.csv`, `per_query_method.csv`,
`per_method_summary.csv`, `exactness_summary.csv`, `quality_runtime_summary.csv`,
`ablation_summary.csv`, `parallelism_summary.csv`, and `failure_summary.csv` are the analysis inputs
for tables and figures. Full large matrices are intentionally not part of the smoke target.
