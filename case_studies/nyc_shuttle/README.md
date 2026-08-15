# NYC real-world shuttle case study

This directory is an isolated, reproducible adapter around the production PACE-B implementation. It keeps the existing DIMACS NY road graph and asks, for every departure minute in a fixed interval, for the highest transit-corridor-affinity road path whose exact time-dependent travel time is within a single fixed budget.

The operational interpretation is a customized inter-terminal shuttle road leg. It is not passenger assignment, bus scheduling, stop sequencing, fleet assignment, or a vehicle-routing solver.

## Prepared case

- Road graph: the repository's `data/input/NY` graph, with 264,346 vertices and 733,846 stable directed arc IDs.
- Coordinate contract: signed microdegrees converted to EPSG:4326; spatial matching is performed in EPSG:32618.
- Traffic horizon: 2026-05-14 00:00 through 24:00 America/New_York (2026-05-14 04:00 UTC through 2026-05-15 04:00 UTC), exactly 96 observed 15-minute bins, with no wrapping or extrapolation. The official `DATA_AS_OF` field is a Socrata Floating Timestamp, so the localization convention is explicit rather than silently treating it as UTC.
- Query periods: 07:00–09:00 and 17:00–19:00 America/New_York, represented as minute offsets 420–540 and 1020–1140 in that local-day horizon.
- Budget: `B = (1 + rho) * max_I(T_fast(t))`, with `rho` in `{0.20, 0.50}`.
- Public bounded PACE-B parameters: `(L, theta, Kf, Mb) = (32, 2, 16, 1,000,000)` and an exact five-second query limit. Obsolete `Kc`, `Mc`, `Mq`, connector-portfolio, and connector-cache flags are rejected by the runner.
- Score: `sigma_e(t) = min(15, count(distinct active route_id using e in bin t))`. This is an active transit-corridor affinity proxy, not a ground-truth MTA preference.

The repository audit and coordinate verification are in [`docs/nyc_case_study_audit.md`](../../docs/nyc_case_study_audit.md).

## Official primary sources

- NYC Street Centerline catalog view `3mf9-qshr`. That catalog entry currently points to official underlying Socrata resource `inkn-q76z`, which is recorded in every download manifest.
- NYC DOT Traffic Speeds `i4gi-tjb9`.
- MTA Current Bus Routes `h2wf-afav`.
- MTA Bus Schedules: 2026 `4fnn-qsea`.

The selected DOT table slice contains multiple historical timestamps, so the primary build does not require a live collector. The separate collector remains available for a future observation period and is never triggered by an ordinary build.

Raw page bytes and schema metadata are write-once. Every download manifest records source URLs, view IDs, retrieval time, sizes, row counts, schemas, and SHA-256 checksums. Derived large files are ignored by Git and can be rebuilt from those raw artifacts.

## Reproduce

Run these from the repository root in order:

```bash
make nyc-setup
make nyc-download
make nyc-audit
make nyc-map
make nyc-build-profiles
make nyc-build-scores
make nyc-queries
make nyc-run
make nyc-analyze
make nyc-figures
make nyc-report
make nyc-finalize
```

`nyc-run` is resumable and refuses non-five-second protocols. It writes one JSONL row per manifest query, including exact-replay feasibility, profile structure, score gain, travel-time premium, timing, memory samples, candidate statistics, and checksums. `nyc-analyze` rejects partial/mismatched batches and any exact-replay budget violation.

If a future DOT inspection shows only a latest snapshot, collect observations explicitly:

```bash
make nyc-collect-traffic NYC_TRAFFIC_DURATION_HOURS=24
```

This target polls every five minutes by default, saves raw responses and checksums, resumes safely, and deduplicates on `(link_id, data_as_of)`. Set `NYC_SOCRATA_APP_TOKEN` in the environment if available; never place its value in `.env.example` or source control.

## Outputs

- `intermediate/`: DIMACS geometry and official-source-to-arc mappings.
- `processed/NYC-REAL/`: production-loader graph, FIFO travel functions, provenance, scores, and graph manifest.
- `manifests/`: deterministic terminal pairs, queries, and exclusions.
- `results/`: exact batch JSONL and aggregate summaries.
- `reports/`: source diagnostics, mapping quality, score definition, data quality, findings, and LaTeX tables.
- `figures/`: only an automatically qualifying, actual result figure; figure generation fails explicitly if the predeclared rule has no qualifying query.
- `experiments/results/Final-result/NYC-real-shuttle/`: compact final bundle created by `make nyc-finalize`. Existing USA and other final results are not overwritten.

Primary source downloads are intentionally not included in the compact final bundle; their exact provenance remains under `raw/`, and the data-quality report records how to reproduce them.
