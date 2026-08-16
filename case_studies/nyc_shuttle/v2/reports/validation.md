# NYC-real-shuttle-v2 validation

This is a diagnostic validation report and does not alter manuscript claims.

## Execution

- 5 s primary statuses: `{'TIMEOUT': 93, 'COMPLETE': 7}`
- 10 s diagnostic statuses: `{'TIMEOUT': 87, 'COMPLETE': 13}`
- 30 s diagnostic statuses: `{'TIMEOUT': 85, 'COMPLETE': 15}`
- No V2 timed result emitted `NO_FEASIBLE_PATH` or `INFEASIBLE`.

## Exact invariants

- Stored/fresh fastest valid: 12100/12100 of 12100 each.
- Independent fastest path/travel agreements: 12100/12100.
- Fastest paths inside safe query region: 12100/12100.
- PACE exact replay valid: 1815/1815; budget violations: 0.

## Profile and score diagnostics at 30 seconds

- Resolved coverage: 15.000%.
- MultiPathRate: 6.667%.
- Profile cells: `{'n': 15, 'mean': 1.7333333333333334, 'median': 1.0, 'q1': 1.0, 'q3': 1.0, 'minimum': 1.0, 'maximum': 12.0}`.
- Distinct paths: `{'n': 15, 'mean': 1.2666666666666666, 'median': 1.0, 'q1': 1.0, 'q3': 1.0, 'minimum': 1.0, 'maximum': 5.0}`.
- Route switches: `{'n': 15, 'mean': 0.4666666666666667, 'median': 0.0, 'q1': 0.0, 'q3': 0.0, 'minimum': 0.0, 'maximum': 7.0}`.
- ChangeCoverage versus fastest: 53.499%.
- Full-interval DeltaS (unresolved contributes zero): -90.964215.
- Resolved-only DeltaS: -0.343251.
- Preference improvement/equal/loss: 21.543% / 51.240% / 27.218%.
- Pearson/Spearman correlation of DeltaS with DeltaT: 0.541665 / -0.144842.
- Budget utilization: `{'n': 1815, 'mean': 0.6529602859411837, 'median': 0.6423518479454828, 'q1': 0.5659142495453895, 'q3': 0.7536447808833165, 'minimum': 0.40074378196909605, 'maximum': 0.9991693371524323}`.

## Coverage, growth, segmentation, determinism

- Interval-best registry path coverage: `{'query_count': 15, 'selected_departure_coverage': {'n': 15, 'mean': 0.9421487603305785, 'median': 1.0, 'q1': 1.0, 'q3': 1.0, 'minimum': 0.1322314049586777, 'maximum': 1.0}, 'csv_sha256': '1331e01759d692600961a895755dcf575c4efbc424e615a81e7ae23b48302371'}`.
- R50/R20 query-region growth: `{'paired_query_families': 50, 'nodes_ratio_r50_r20': {'n': 50, 'mean': 1.4683422055478546, 'median': 1.4986074357254422, 'q1': 1.348760881424758, 'q3': 1.6399772743579213, 'minimum': 1.0787765240821825, 'maximum': 1.790368484122229}, 'edges_ratio_r50_r20': {'n': 50, 'mean': 1.4354150399374848, 'median': 1.4602703660004681, 'q1': 1.3089543061046576, 'q3': 1.612924703291236, 'minimum': 1.0691495093783863, 'maximum': 1.741933291035983}, 'nodes_nondecrease_fraction': 1, 'edges_nondecrease_fraction': 1, 'csv_sha256': '467b5a2dd07330061600eeb4d6e138714a394ec735c1d36883c30e2b5c108594'}`.
- Score segmentation correlations: `{'resolved_temporal_path_samples': 1815, 'corr_score_edge_count': 0.5592274513897784, 'corr_score_geometric_length': 0.8029077996835845, 'severe_segmentation_bias_by_predeclared_rule': False, 'severity_rule': 'severe iff |corr(score,edge_count)| >= 0.70 and exceeds |corr(score,geometric_length)| by > 0.15', 'csv_sha256': 'a12a9816d6b7281dd152a74d8c56dbd7d5dcddacb0d6f1cb39897f15c23886fc'}`.
- Output checksum determinism: `{'cross_cap_repeated_completed_queries': 13, 'cross_cap_checksum_agreements': 13, 'cross_cap_all_agree': True, 'independent_trace_reruns': 15, 'independent_trace_checksum_agreements': 15, 'trace_all_agree': True}`.
