# NYC case-study validation record

Validation date: 2026-08-15

- Python case-study contract tests: **14 passed**, 0 failed.
- Full Java/Maven regression suite: **240 passed**, 0 failed, 0 errors, 0 skipped.
- Corrected query manifest: 100 schema-valid unique IDs, 0 exclusions.
- Corrected result batch: 100 schema-valid unique IDs matching the query manifest.
- Strict production graph loading: passed for 264,346 nodes and 733,846 arcs; no non-FIFO edge was accepted.
- Exact replay budget violations across completed PACE-B profile cells: **0**.
- Deterministic fresh-JVM replay query: `NYC-Q002-MORNING-R20`.
- Final checksum in full batch, replay A, and replay B: `e8d0e1f88ebda76cd3fddb03b08ec1bb14bd799efa59f72be3fe498a308e4e21`.
- Materialized PACE profile checksum in all three runs: `fd9c1267ed64641da5478b12d5d4018db1f1ec2e5241445f78724c0463832c88`.
- Materialized fastest-profile checksum in all three runs: `ef0be488b06442e66f6405671f6f280d86cfaa74329d9d1b1e13ff9468a1fecf`.

The replay comparison intentionally excludes runtime and sampled heap fields. The archived pre-timezone-correction batch is diagnostic only and is not included in these counts.
