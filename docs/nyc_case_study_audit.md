# NYC shuttle case-study repository audit

Audit date: 2026-08-15  
Audited revision: `035966b188c7e889cd206ccf87620cec36f1b4cb`

Temporal-source note: NYC Open Data exposes DOT `DATA_AS_OF` as a Socrata
Floating Timestamp (no embedded offset). The case-study contract explicitly
localizes it to `America/New_York` before UTC normalization, so DOT bins and
local MTA schedule activity share the same civil-time basis.

## Existing NY graph and coordinates

The materialized DIMACS-derived NY graph is `data/input/NY/`. It contains
`nodes.csv.gz`, `edges_static.csv.gz`, `travel_time_functions.jsonl.gz`,
`score_functions.jsonl.gz`, and `manifest.json`. The manifest points back to
the source files `USA-road-d.NY.co`, `USA-road-d.NY.gr`, and
`USA-road-t.NY.gr` in the sibling `Synthetic-data-generator` repository. Those
source paths are not required for this case study because the converted node
and arc tables are present, but their exact SHA-256 values remain recorded in
the graph manifest.

`nodes.csv.gz` contains 264,346 nodes in the exact schema `node_id,x,y`. The
coordinates are signed integer microdegrees, following the DIMACS `.co`
convention used by the conversion script:

```
longitude = x / 1,000,000
latitude  = y / 1,000,000
```

The measured raw-coordinate bounding box is:

```
x: -74,499,998 .. -73,500,016
y:  40,300,009 ..  41,299,997
```

After conversion, the geographic bounding box is:

```
longitude: -74.499998 .. -73.500016
latitude:   40.300009 ..  41.299997
```

This is a plausible regional New York graph and contains all of New York City.
It is intentionally larger than the five boroughs. The conversion is therefore
verified and geographic integration may proceed. Spatial matching must still
filter to the NYC data extent. Case-study metric calculations use EPSG:32618
(WGS 84 / UTM zone 18N), so meter thresholds are never applied directly to
longitude/latitude.

## Loader and function contracts

The production loader is
`edu.ipcmax.core.loader.GeneratedGraphLoader`. It requires:

- `nodes.csv.gz`: `node_id,x,y`;
- `edges_static.csv.gz`:
  `arc_id,u,v,distance,base_travel_time`;
- `travel_time_functions.jsonl.gz`: one record per arc with `arc_id`, `u`,
  `v`, `base_travel_time`, and `travel_time_breakpoints`, where each
  breakpoint is `[minute, travel_time_minutes]`;
- `score_functions.jsonl.gz`: records with `arc_id`, `u`, `v`, and
  `score_intervals`, where each interval is
  `[start_minute,end_minute,nonnegative_integer_score]`.

Score records may omit zero-only arcs only when the manifest declares
`unlisted_edges_have_score_zero: true`. The NYC graph builder materializes all
score records to make provenance explicit.

The graph conversion contract is
`declared_centisecond_normalization-v1`: converted minutes are the DIMACS
weight divided by 6000. This is a declared experimental normalization, not a
claim about the DIMACS source unit. The NYC workflow preserves this static
base travel time and calibrates temporal multipliers from observed DOT data.

`PiecewiseLinearFn` stores travel time and evaluates arrival as
`A_e(t)=t+tau_e(t)`. It validates nonnegative travel time and provides FIFO
validation through `isFifo()`/`requireFifo()`. `GraphValidator` can enforce
FIFO across all arcs. The asset generator creates FIFO synthetic peaks and
validates them, but the repository has no reusable repair routine for arbitrary
observations. The NYC workflow therefore uses one documented repair: project
sampled arrival values onto a nondecreasing sequence with a deterministic
cumulative maximum, then linearly interpolate arrival between knots. The
generated Java-format functions are subsequently checked by both the Python
validator and `GeneratedGraphLoader`/`GraphValidator`.

## Arc identity and external attributes

Arc IDs are row-stable and assigned consecutively from zero by the DIMACS
asset generator. `TDGraph` and `GeneratedGraphLoader` both reject any gap or
reordering. The case study copies the static edge file byte-for-byte and joins
all external attributes by this stable `arc_id`; it never renumbers the graph.

There is no production external geographic-attribute importer. Existing
preparation scripts build temporal assets but do not consume Centerline, DOT,
MTA, GeoParquet, or general edge-attribute tables. A separate Python mapping
layer is therefore required; no core graph model change is required.

## PACE and baselines

The production PACE entry point is `edu.ipcmax.core.pcmax.PACE`. The benchmark
adapter is `edu.ipcmax.experiments.algorithms.PaceExperimentAlgorithm`, driven
by `edu.ipcmax.experiments.PaceBench`. Exact path replay uses
`ExactPathValidator`.

FIFO time-dependent fastest paths are implemented by `PointForwardLabeling`.
`RepeatedFastestPathBaseline` samples it on the query departure grid. The NYC
runner uses the same labeler but retains the complete minute-by-minute fastest
path profile and its exact accumulated score, rather than reducing it to one
legacy point result.

An adapted five-second anytime iSCOPE implementation exists as
`edu.ipcmax.experiments.algorithms.IScopeAlgorithm` and is registered as
`iscope`. It can consume the same `TDGraph` and `QuerySpec`; it remains optional
for this case study because PACE-B versus time-dependent fastest routing is the
primary semantic comparison.

## Configuration compatibility finding

The current general benchmark interface still carries legacy bounded controls
`K_c`, `M_c`, `M_q`, connector-portfolio, and connector-cache switches in
`PaceOptions`, `AlgorithmConfig`, `BenchOptions`, and the main result schema.
Exposing or silently inheriting those controls would violate the requested NYC
protocol, whose bounded parameters are only `(L, theta, K_f, M_b)`.

The isolated NYC runner therefore provides a narrow compatibility adapter:

- its public CLI and result configuration contain only `L`, `theta`, `K_f`,
  and `M_b`;
- legacy connector-portfolio and connector-cache features are explicitly
  disabled;
- the legacy connector output slot is fixed internally to one deterministic
  lower-bound witness and is not a case-study parameter;
- legacy connector and aggregate-query work caps are set to their unbounded
  sentinels and are not used as stopping criteria;
- the 5-second limit is enforced as an external experimental execution limit,
  not introduced as an approximation parameter.

This adapter reuses production PACE and does not change its mathematical
objective. A repository-wide removal of legacy fields would affect the
existing paper suite and is deliberately outside this isolated case-study
change.

## Query, result, and analysis formats

The main benchmark supports strict query-manifest schemas v1-v3 through
`QueryManifestIO`. Version 3 is specialized to the synthetic paper datasets
and its dataset enumeration and metadata contract do not describe NYC
observation provenance. The case study therefore uses a separate strict JSONL
manifest (`nyc-query-v1`) whose rows still map directly to `QuerySpec` and
include `pair_id`, source/destination, interval, fixed budget, rho, fastest
profile evidence, temporal-support checksum, and source manifest checksum.
The main schemas are not weakened or extended.

The main benchmark writes a large versioned JSONL result record. The NYC runner
writes an isolated `nyc-case-result-v1` record containing the requested
case-study metrics, profile cells and arc IDs, exact replay validation counts,
status, timing, memory, and checksums. Analysis scripts consume only this case
schema.

Existing Python analysis is under `experiments/scripts/`, plotting under
`experiments/plots/`, and LaTeX table generation under `experiments/tables/`.
The case-study equivalents live under `case_studies/nyc_shuttle/scripts/` so
their real-data assumptions cannot affect the synthetic benchmark path.

## Minimum isolated changes

The minimum integration consists of:

1. an immutable, checksummed official-data downloader/collector;
2. GeoParquet conversion and direction-aware Centerline/MTA/DOT mappings;
3. observed-horizon travel-function and MTA-derived score builders that emit
   the existing generated-graph format;
4. deterministic endpoint and query manifests;
5. the narrow Java runner described above;
6. isolated analysis, figures, tables, reports, tests, and Make targets.

No PACE objective, graph class, loader format, synthetic dataset, or existing
result is replaced.
