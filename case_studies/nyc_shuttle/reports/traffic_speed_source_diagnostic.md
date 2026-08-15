# DOT Traffic Speeds source diagnostic

- Raw rows read: 29,088
- Deduplicated `(link_id, data_as_of)` rows: 29,088
- Unique `link_id`: 101
- Minimum `data_as_of`: 2026-05-14T00:03:02.000
- Maximum `data_as_of`: 2026-05-14T23:59:07.000
- Distinct `data_as_of` timestamps: 2,100
- Source timestamp contract: Socrata Floating Timestamp localized to `America/New_York` before UTC normalization
- Missing/non-numeric speed rate: 0.00%
- Zero/negative speed rate: 23.28%
- Snapshot classification: MULTI-TIMESTAMP OBSERVATIONS

## Borough counts

- Bronx: 6,336
- Brooklyn: 2,304
- Manhattan: 6,048
- Queens: 7,200
- Staten Island: 7,200

## Input artifacts

- `case_studies/nyc_shuttle/raw/dot_traffic_speeds/20260815T074644Z_i4gi-tjb9/page-000000-offset-000000000000.json` — SHA-256 `0bbf69cdad468f6e8964f153989e657f7ce92ba22bab6489ac3efe3d00192596`
