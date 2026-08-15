# NYC case-study data quality and reproducibility

## Official source artifacts

- NYC Street Centerline / Centerline — dataset `inkn-q76z` on `data.cityofnewyork.us`
  - official landing page: https://data.cityofnewyork.us/City-Government/Centerline/3mf9-qshr
  - retrieval: 2026-08-15T07:45:41Z
  - rows: 122,244
  - exact raw bytes (metadata + pages): 196,872,144
  - content checksum: `5853de84726d725d332f6e07ffaa050208d9c2c8c108ccc583a31b5d118ccfb2`
  - metadata URL: https://data.cityofnewyork.us/api/views/inkn-q76z
  - captured schema: `the_geom:multiline, physicalid:number, l_low_hn:text, l_high_hn:text, r_low_hn:text, r_high_hn:text, l_zip:text, r_zip:text, status:text, bike_lane:text, trafdir:text, rw_type:number, pre_type:text, post_type:text, objectid:number, fcc:text, l_blockfaceid:number, r_blockfaceid:number, avgtravtime:number, rwjurisdiction:text, nominaldir:text, accessible:text, nonped:text, boroughcode:text, borough_indicator:text, seglocstatus:text, sandist_ind:text, lsubsect:text, rsubsect:text, continuous_parity_flag:text, twisted_parity_flag:text, posted_speed:number, segmentlength:number, streetwidth:number, streetwidth_irr:text, special_disaster:text, fire_lane:text, created_date:date, modified_date:date, within_bndy_dist:text, truck_route_type:text, collectionmethod:text, from_level_code:number, to_level_code:number, b5sc:text, snow_priority:text, joinid:text, bphys_id:number, carto_display_level:text, number_travel_lanes:number, number_park_lanes:number, number_total_lanes:number, pre_modifier:text, pre_directional:text, post_directional:text, post_modifier:text, full_street_name:text, bike_trafdir:text, shape_length:number, globalid:text, segment_type:text, segment_type_value:text, street_name:text, stname_label:text`
  - page `page-000000-offset-000000000000.geojson`: 50,000 rows, 79,841,251 bytes, SHA-256 `9507ecaea94ae02b2a11441310dad28780ef77a7e762710f97872dc26e6db2c0`, URL https://data.cityofnewyork.us/resource/inkn-q76z.geojson?%24limit=50000&%24offset=0&%24order=:id
  - page `page-000001-offset-000000050000.geojson`: 50,000 rows, 79,838,312 bytes, SHA-256 `11b79729df25c4105035a4d0db7f74e091028f8ad0accd40c883780ab6a68f47`, URL https://data.cityofnewyork.us/resource/inkn-q76z.geojson?%24limit=50000&%24offset=50000&%24order=:id
  - page `page-000002-offset-000000100000.geojson`: 22,244 rows, 37,121,146 bytes, SHA-256 `1ea891f7ee1ada27574f18869884d83ec15d01a25b44fed0bf404cd51f587a37`, URL https://data.cityofnewyork.us/resource/inkn-q76z.geojson?%24limit=50000&%24offset=100000&%24order=:id
- NYC DOT Traffic Speeds — dataset `i4gi-tjb9` on `data.cityofnewyork.us`
  - official landing page: https://dev.socrata.com/foundry/data.cityofnewyork.us/i4gi-tjb9
  - retrieval: 2026-08-15T07:46:44Z
  - rows: 29,088
  - exact raw bytes (metadata + pages): 18,624,061
  - content checksum: `397a5cb9f7b27ef66ef92ca430c274bbc88d826227d54d2f42ed04939b3ba495`
  - metadata URL: https://data.cityofnewyork.us/api/views/i4gi-tjb9
  - captured schema: `id:text, speed:text, travel_time:text, status:text, data_as_of:calendar_date, link_id:text, link_points:text, encoded_poly_line:text, encoded_poly_line_lvls:text, owner:text, transcom_id:text, borough:text, link_name:text`
  - page `page-000000-offset-000000000000.json`: 29,088 rows, 18,592,554 bytes, SHA-256 `0bbf69cdad468f6e8964f153989e657f7ce92ba22bab6489ac3efe3d00192596`, URL https://data.cityofnewyork.us/resource/i4gi-tjb9.json?%24limit=50000&%24offset=0&%24order=:id&%24where=data_as_of+%3E%3D+%272026-05-14T00:00:00%27+and+data_as_of+%3C+%272026-05-15T00:00:00%27
- MTA Bus Schedules: 2026 — dataset `4fnn-qsea` on `data.ny.gov`
  - official landing page: https://data.ny.gov/Transportation/MTA-Bus-Schedules-2026/4fnn-qsea
  - retrieval: 2026-08-15T07:47:15Z
  - rows: 400,206
  - exact raw bytes (metadata + pages): 100,219,486
  - content checksum: `8939ae053ca20859bc71c6adbd2eaded29fd15e894654288cd6e25840fa2cb26`
  - metadata URL: https://data.ny.gov/api/views/4fnn-qsea
  - captured schema: `schedule_date:calendar_date, day_type:text, borough:text, operator:text, service_id:text, direction:text, shape_id:text, trip_type:text, route_id:text, stop_sequence:number, stop_id:text, stop_name:text, schedule_time:calendar_date, origin:text, destination:text, school:text, revenue_stop:text, timepoint:text, boarding:text, alighting:text, distance_from_start:number, trip_headsign:text, block_id:text, depot_code:text, bundle:text`
  - page `page-000000-offset-000000000000.csv`: 50,000 rows, 12,807,898 bytes, SHA-256 `d42ae97c5dc3ebe699202558925a0932b4976376a8ae1de668c6301179e4f86e`, URL https://data.ny.gov/resource/4fnn-qsea.csv?%24limit=50000&%24offset=0&%24order=:id&%24where=schedule_date+%3D+%272026-05-14T00:00:00.000%27
  - page `page-000001-offset-000000050000.csv`: 50,000 rows, 12,740,078 bytes, SHA-256 `667646882f01b66a920948b0fd82f34eb868a2e0a3495b7ed84be27710aff2ea`, URL https://data.ny.gov/resource/4fnn-qsea.csv?%24limit=50000&%24offset=50000&%24order=:id&%24where=schedule_date+%3D+%272026-05-14T00:00:00.000%27
  - page `page-000002-offset-000000100000.csv`: 50,000 rows, 12,560,197 bytes, SHA-256 `3cefd7d6dc34134e6e2fdb88249d3a7e0e34e70c7557afa78250599ab8bdf521`, URL https://data.ny.gov/resource/4fnn-qsea.csv?%24limit=50000&%24offset=100000&%24order=:id&%24where=schedule_date+%3D+%272026-05-14T00:00:00.000%27
  - page `page-000003-offset-000000150000.csv`: 50,000 rows, 12,388,488 bytes, SHA-256 `aa215c66aa1f2156782ccb107fdf11caae31847a6af5a6920e81d95ab9952d7f`, URL https://data.ny.gov/resource/4fnn-qsea.csv?%24limit=50000&%24offset=150000&%24order=:id&%24where=schedule_date+%3D+%272026-05-14T00:00:00.000%27
  - page `page-000004-offset-000000200000.csv`: 50,000 rows, 12,460,562 bytes, SHA-256 `22f98e3a2f5d96e332b431cb19496432c0a432bf7e93677561a74b75f3484364`, URL https://data.ny.gov/resource/4fnn-qsea.csv?%24limit=50000&%24offset=200000&%24order=:id&%24where=schedule_date+%3D+%272026-05-14T00:00:00.000%27
  - page `page-000005-offset-000000250000.csv`: 50,000 rows, 12,324,031 bytes, SHA-256 `9d1cb6efbd88dc0f4f45df1c54871e5512b0a8e74e56adee14c03e9fd9f65eb1`, URL https://data.ny.gov/resource/4fnn-qsea.csv?%24limit=50000&%24offset=250000&%24order=:id&%24where=schedule_date+%3D+%272026-05-14T00:00:00.000%27
  - page `page-000006-offset-000000300000.csv`: 50,000 rows, 12,441,216 bytes, SHA-256 `af5b642f5685d8f2a0567aae50f369aba81e98844e636ea8ae73bd3c2c2e628c`, URL https://data.ny.gov/resource/4fnn-qsea.csv?%24limit=50000&%24offset=300000&%24order=:id&%24where=schedule_date+%3D+%272026-05-14T00:00:00.000%27
  - page `page-000007-offset-000000350000.csv`: 50,000 rows, 12,405,972 bytes, SHA-256 `98d892722737d0b82ece081f48eb3be5edd5709111117569fbc4290b196ac5c9`, URL https://data.ny.gov/resource/4fnn-qsea.csv?%24limit=50000&%24offset=350000&%24order=:id&%24where=schedule_date+%3D+%272026-05-14T00:00:00.000%27
  - page `page-000008-offset-000000400000.csv`: 206 rows, 51,965 bytes, SHA-256 `ad37a1d406c8f75267f6ff82140ce8a5b64302f6759a66eff5dc69266f2954c0`, URL https://data.ny.gov/resource/4fnn-qsea.csv?%24limit=50000&%24offset=400000&%24order=:id&%24where=schedule_date+%3D+%272026-05-14T00:00:00.000%27
- MTA Current Bus Routes — dataset `h2wf-afav` on `data.ny.gov`
  - official landing page: https://data.ny.gov/Transportation/MTA-Current-Bus-Routes/h2wf-afav
  - retrieval: 2026-08-15T07:47:02Z
  - rows: 1,290
  - exact raw bytes (metadata + pages): 13,100,209
  - content checksum: `ccddfbad78f98d9f4adbeed01adcfe25b1bb3a5c416366e75adce5b50c763fff`
  - metadata URL: https://data.ny.gov/api/views/h2wf-afav
  - captured schema: `valid_from:calendar_date, valid_to:calendar_date, in_effect:text, route_id:text, route_short_name:text, route_long_name:text, route_description:text, trip_type:number, route_type:text, bundle:text, route_color:text, direction_id:text, direction:text, shape_id:text, vertices:number, shape_length:number, min_longitude:number, min_latitude:number, max_longitude:number, max_latitude:number, geometry:multiline`
  - page `page-000000-offset-000000000000.geojson`: 1,290 rows, 13,067,437 bytes, SHA-256 `d1329f1f007c8f21b5dbb6beedb81533ff9748eb52bf842d2fe7b86c0a8918b5`, URL https://data.ny.gov/resource/h2wf-afav.geojson?%24limit=50000&%24offset=0&%24order=:id

## Temporal calibration

- observed support: 2026-05-14T04:00:00Z to 2026-05-15T04:00:00Z
- bin size: 15 minutes
- DOT timestamp convention: Socrata Floating Timestamp localized to `America/New_York` and normalized to UTC
- provenance counts: `{'BOROUGH_CLASS_IMPUTED': 7567514, 'CITYWIDE_IMPUTED': 62659235, 'DIRECT': 222467}`
- provenance percentages: `{'DIRECT': 0.31578349998955274, 'BOROUGH_CLASS_IMPUTED': 10.741800164248811, 'CITYWIDE_IMPUTED': 88.94241633576164, 'STATIC_FALLBACK': 0.0}`
- FIFO repaired knots: 4
- day wrapping: none
- extrapolation: none

## Mapping and score

# Centerline mapping quality

- edge_count: 733846
- matched_edges: 81006
- matched_percent: 11.038555773282132
- ambiguous_matches: 2713
- unmatched_edges: 652840
- distance_median_m: 6.527545173908102
- distance_p95_m: 27.661537224317556
- output_sha256: 1c47088270efd16e7797e9f68ca6a0400f3c52247722c136daba1a29fa0c457e

## Matched edges by borough

- 5: 19760
- 4: 19334
- 3: 18012
- 2: 15578
- 1: 8322

## Matched edges by road class

- 1: 73656
- 2: 2429
- 10: 1377
- 6: 1146
- 9: 1125
- 3: 970
- 8: 141
- 5: 59
- 7: 47
- 4: 42
- 13: 6
- 12: 4
- 14: 4

# DOT-link mapping quality

- dot_links_with_geometry: 78
- dot_links_mapped: 76
- dot_links_unparseable: 3
- median_coverage_ratio: 1.0
- one_to_many_links: 74
- output_sha256: 71bfd49dba0230d362ded7c9aa4d276a0a337e56df5aabbdc05c29dfb3a0bbc4

## Geometry parse failures

- 4362244: truncated encoded polyline
- 4575278: decoded DOT polyline lies outside the NYC region
- 4616241: decoded DOT polyline lies outside the NYC region

# MTA route-shape mapping quality

- route_shapes_processed: 1290
- mapped_shapes: 1288
- mapped_route_direction_records: 1288
- usable_mapping_percent: 99.84496124031008
- median_mapped_length_ratio: 0.559192365108877
- ambiguous_mappings: 0
- median_distinct_routes_per_mapped_arc: 1.0
- max_distinct_routes_per_mapped_arc: 31
- output_sha256: 2fa61c1cb9093ba6dd9686c45eb61862e50d8125818751331976b6995e000284

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


## Query filtering and deterministic choices

- selected terminal pairs: 25
- emitted queries: 100
- excluded queries: 0
- exclusion reasons: `{}`
- deterministic seed: 20260815
- terminal selection target: 25

## Determinism and software

- graph-build PACE revision: `035966b188c7e889cd206ccf87620cec36f1b4cb`
- report-generation revision: `035966b188c7e889cd206ccf87620cec36f1b4cb`
- software: `{'python': '3.10.12', 'platform': 'Linux-6.8.0-136-generic-x86_64-with-glibc2.35', 'pandas': '2.3.3', 'geopandas': '1.1.4', 'shapely': '2.1.2', 'pyproj': '3.7.1', 'numpy': '2.2.6', 'pyarrow': '23.0.1'}`

## Reproduction commands

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

The long-running `make nyc-collect-traffic NYC_TRAFFIC_DURATION_HOURS=H` target is separate and was not used: the official DOT table supplied multiple historical timestamps for the selected day.
