# Transit-corridor affinity score

The primary score is a shuttle corridor preference proxy, not a ground-truth MTA preference.

For each directed DIMACS arc `e` and 15-minute bin `t`, `N_e(t)` is the number of distinct 
scheduled active MTA `route_id` values whose mapped route shape uses `e`. The score is 
`sigma_e(t) = min(15, N_e(t))`; all other arcs have score zero.

Route IDs are counted instead of shape IDs so bundle/direction shape duplication cannot inflate the proxy.

- schedule_rows: 400206
- reconstructed_trips: 85076
- invalid_schedule_times: 0
- active_route_shape_bins: 93322
- active_route_ids: 367
- mapped_active_route_ids: 325
- active_shape_ids: 4253
- unmatched_active_shapes: 3368
- score_bearing_edges: 21422
- score_bearing_edge_percent: 2.9191410731951937
- score_breakpoints: 95142
- score_value_distribution_edge_bins: {0: 68930609, 1: 1008512, 2: 289279, 3: 94709, 4: 44462, 5: 28071, 6: 19003, 7: 12402, 8: 6969, 9: 4261, 10: 1959, 11: 1441, 12: 791, 13: 530, 14: 621, 15: 5597}
- score_functions_sha256: 949f6a49f200d03f469ed1e71d8ebfdf42221c41a19e1ba5b77d151c8a01c0d7
