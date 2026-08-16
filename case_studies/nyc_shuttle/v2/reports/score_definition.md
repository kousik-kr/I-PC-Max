# Transit-corridor affinity score

The primary score is a shuttle corridor preference proxy, not a ground-truth MTA preference.

For each directed DIMACS arc `e` and 15-minute bin `t`, `N_e(t)` is the number of distinct 
scheduled active MTA `route_id` values whose exact `shape_id -> shapes.txt` mapping uses `e`. The score is 
`sigma_e(t) = min(15, N_e(t))`; all other arcs have score zero.

Route IDs are counted instead of shape IDs so bundle/direction shape duplication cannot inflate the proxy. 
No `route_id` geometry fallback is used.

- schedule_rows: 400206
- reconstructed_trips: 85076
- invalid_schedule_times: 0
- active_route_shape_bins: 93322
- active_route_ids: 367
- mapped_active_route_ids: 345
- active_shape_ids: 4253
- exact_shape_geometries_available: 1421
- exact_shape_geometries_mapped_to_dimacs: 1418
- unmatched_active_shapes: 2835
- active_route_shape_bin_exact_matches: 55017
- active_route_shape_bin_exact_match_percent: 58.953944407535204
- route_geometry_fallback_count: 0
- shape_geometry_contract: EXACT_SHAPE_ID_TO_GTFS_SHAPES_TXT_ONLY
- score_bearing_edges: 24655
- score_bearing_edge_percent: 3.359696721110424
- score_breakpoints: 107157
- score_value_distribution_edge_bins: {0: 68596770, 1: 1177578, 2: 377898, 3: 130022, 4: 56225, 5: 33190, 6: 21189, 7: 17436, 8: 9457, 9: 6671, 10: 4417, 11: 2274, 12: 2826, 13: 2459, 14: 963, 15: 9841}
- score_functions_sha256: 20fe006c1c5b75627b36dc9882c81f789f2311cdb275261c5d69f41632a24c5c
