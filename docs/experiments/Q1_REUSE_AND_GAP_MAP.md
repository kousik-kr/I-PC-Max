# PACE Q1 Reuse and Gap Map

## Scope

The executable publication scope is a two-track design: NY-EXACT plus low-budget NY/FLA/CAL
PACE-X probes for exactness, and NY/FLA/CAL PACE-B for scalability. OL replaces USA in the
preparation dataset set and lives under `data/input/OL`; USA is not in the executable design.

## Reused Java Core

| Capability | Reused implementation | Experiment role |
|---|---|---|
| Graph loading | `GeneratedGraphLoader` and `GeneratedGraphDataset` | The Python layer never parses road graphs. |
| Query generation | `QuerySetGenerator`, `DatasetQueryGenerator`, `QueryCandidateSampler`, `QueryBudgetBuilder` | Existing deterministic Phase-5 workload; paper manifests have a separate validation contract. |
| Algorithms | `AlgorithmRegistry` and `ExperimentAlgorithm` adapters | Stable IDs for PACE-X, PACE-B, exhaustive, profile labeling, RPQ, KSP, and interval-best. |
| Execution | `PaceBench` | Isolated query processes, resource limits, structured records, metrics, and checksums. |
| Query records | `QueryManifestEntry` and `QueryManifestIO` | Schema-v1 compatibility plus deterministic schema-v2 rows. |
| Result records | `result_record.schema.json` | Java worker record nested unchanged in the orchestration terminal record. |

## Added Control Layer

`experiments/scripts/run_all.py` owns the stage DAG. It freezes an immutable identity, performs
preflight, builds deterministic T01--T06 matrices, executes one process per query/trial, validates
complete coverage, aggregates trials per query, creates report artifacts, and packages a release
only after hard gates pass.

The local backend limits concurrent processes. The Slurm backend produces a deterministic array script using the same matrix manifest. `clean_run.py` deletes only one explicitly named directory below `experiments/results`.

## Confirmed Full-Run Blockers

1. Current manifests describe travel-time support through minute 1440. The frozen Q1 design requires explicit support through at least minute 10080 so long evening queries satisfy `interval_end + budget <= support_end` without wrapping or extrapolation.
2. The existing Phase-5 query generator produces a different four-regime/quartile workload. Paper manifests must contain disjoint 20-pilot, 10-warmup, and 100-evaluation pairs at centers 510 and 1110 and pass `generate_queries.py --action validate`.
3. `PaceBench` does not yet emit `relative_score_gap_percent`, so the exact E02 pilot-selection rule refuses to freeze L/K.
4. PACE's requested thread count is not yet proven to perform concurrent algorithm work. E11 must remain blocked until the non-timing concurrency gate passes.
5. E01 currently has the deterministic fixtures but not the required 1,000-graph seeded corpus or an uncompressed PACE-X execution variant.
6. The target server's physical cores, RAM, per-query memory cap, maximum concurrent processes, and scheduler details are not frozen.

These blockers stop execution; they do not prevent deterministic plan generation or the tiny smoke workflow.
