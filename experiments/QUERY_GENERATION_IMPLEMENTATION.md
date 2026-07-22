# Deterministic Query-Set Generation: Phase 1 Implementation Note

## Scope and baseline

This phase is discovery only. It does not implement query generation or add another graph loader.
The baseline command `mvn test` completed successfully on 2026-07-15: 78 tests ran with
zero failures, zero errors, and zero skipped tests.

The generated road datasets use the same directory contract:

```text
manifest.json
nodes.csv.gz
edges_static.csv.gz
travel_time_functions.jsonl.gz
score_functions.jsonl.gz
README_generated.md
```

The inspected manifests report:

| Dataset | Nodes | Directed arcs | Selected score arcs | Seed |
| --- | ---: | ---: | ---: | ---: |
| NY | 264,346 | 733,846 | 146,769 | 42 |
| FLA | 1,070,376 | 2,712,798 | 542,559 | 42 |
| CAL | 1,890,815 | 4,657,742 | 931,548 | 42 |

All three declare travel-time breakpoints from minute 0 through minute 1440, morning rush
`[420,600]`, evening rush `[1020,1200]`, three-decimal non-integer travel times, score values
from 1 through 15 on a 20% selected-edge fraction, and zero score on unlisted edges. USA is
assumed to have this file structure as directed; its counts and other values were not inferred.

## 1. Graph-loading API

Use the existing API:

```java
GeneratedGraphDataset dataset = new GeneratedGraphLoader().load(datasetDirectory);
TDGraph graph = dataset.graph();
ManifestSummary manifest = dataset.manifest();
```

`GeneratedGraphLoader.load(Path)` reads the five data files above, accepts gzip based on the
file suffix, materializes zero score functions for unlisted arcs when authorized by the
manifest, constructs `TDGraph`, runs `GraphValidator.validate(graph, false)`, and checks graph
counts and selected-score counts against the manifest. `GeneratedGraphDataset` returns the
graph, the lightweight manifest summary, and the source directory.

`TDGraph` provides `nodeCount()`, `edgeCount()`, `node(int)`, `edges()`,
`outgoingEdges(int)`, and `incomingEdges(int)`. Edges and adjacency lists are immutable and
sorted by stable consecutive `arcId`. There is no public node collection or node-ID iterator;
`PaceBench` currently reconstructs incident node IDs from the edge list.

Selection for later phases: load every NY/FLA/CAL/USA dataset through one
`GeneratedGraphLoader` instance per generation run and use the returned `TDGraph`. Do not
parse road files in Python and do not create another loader. Dataset IDs currently come from
the directory name in `PaceBench`, not from `manifest.json`.

## 2. Lower-bound shortest-path API

There are two related APIs:

* `LowerBoundGraph(TDGraph)` assigns every arc `edge.travelTimeFunction().minTravelTime()`
  over the arc's entire function domain. `distancesFromSource(int)` and
  `distancesToTarget(int)` run forward/reverse Dijkstra and return `Distances`, which exposes
  `distance(int)` and `reached(int)`. This is the API used by the current private
  `PaceBench.generateQueries` method for `lower_bound_distance` and distance-bin ranking.
* `QueryLowerBounds(TDGraph, Domain)` assigns each arc its minimum travel time over a supplied
  query horizon. It exposes `edgeWeight(int)`, `distance(source,destination)`, cached
  `distancesFrom(int)` and `distancesTo(int)`, plus reverse distances excluding a supplied arc
  set. It is the lower-bound implementation used by PACE with the anchor index.

Selection for later phases: use `LowerBoundGraph` for dataset-wide candidate stratification
when retaining the existing whole-day meaning of `lower_bound_distance`. Use
`QueryLowerBounds` for query-horizon-aware feasibility and anchor relevance. The generator
must state which meaning it writes; the two distances can differ during congestion windows.

## 3. Predecessors and edge counts

Neither `LowerBoundGraph.Distances` nor `QueryLowerBounds.Distances` retains predecessor arcs,
so neither can currently return a lower-bound shortest path or its edge count. Recovering a
path afterward only from floating-point distance equalities would duplicate traversal logic
and leave shortest-path tie behavior implicit; it should not be the implementation approach.

The fixed-departure facility already retains witnesses. `PointForwardLabeling.Result.pathTo`
reconstructs an ordered `Path` from its private predecessor-arc map. The exact fastest-path
edge count is therefore `result.pathTo(destination).arcIds().size()`. `PathPointer.edgeCount()`
provides the same operation for an existing candidate profile.

Required lower-bound compatibility change for a later phase: extend the existing
lower-bound result, preferably `LowerBoundGraph.Distances`, to retain stable predecessor arcs
and expose a path/edge-count witness. The tie policy must be explicit and tested (for example,
minimum distance, then minimum hop count, then stable arc/path order). Forward and reverse
results need direction-correct reconstruction. This is an extension to the current shortest
path implementation, not a new loader or a second road-graph representation.

## 4. Exact and profile-based fastest-path facilities

Available facilities are:

* `PointForwardLabeling.run(source, departureTime, maxTravelTime)` is FIFO
  time-dependent earliest-arrival labeling for one integer departure minute. Its result has
  `reached`, `arrivalAt`, and `pathTo`. Equal arrival relaxations prefer the lower predecessor
  arc ID. This is the selected exact fastest-path API for whole-minute query generation.
* `PointBackwardLabeling.run(destination, arrivalDeadline)` computes latest departures and
  reconstructs suffix paths with `pathFrom`.
* `IntervalForwardLabeling.fastestCandidates(...)` repeats point labeling for every integer
  minute in a `Domain`, exactly validates each path, and emits singleton candidate profiles.
* `RepeatedFastestPathBaseline.solve(QuerySpec)` performs the same point runs on the query
  grid and exact-validates witnesses. `FastestPathAlgorithmRunner` exposes it to the legacy
  experiment adapter as `td-fastest`.
* `ExactPathProfileBuilder.replay(...)` constructs an exact continuous arrival/score profile
  for a known ordered arc path. It does not find a fastest path.
* `ProfileLabelingAlgorithm` and the PACE/IPC-Max profile machinery produce preference
  candidate/envelope profiles. They are not a dedicated continuous fastest-travel-time
  profile algorithm and are inappropriate merely to obtain fastest-time minima/maxima on
  these large datasets.

There is no public continuous fastest-path profile API. For the current manifest contract,
whose `QuerySpec` uses whole-minute interval endpoints and granularity 1, later generation can
run `PointForwardLabeling` at each accepted departure minute and compute exact fastest travel
time as `arrivalAt(destination) - departure`. If the intended workload is continuous rather
than the integer grid, a new routing facility or a clarified approximation rule is required.

## 5. Time-unit representation

Temporal values are minutes throughout the routing code and dataset metadata:

* Query departure bounds and granularity are integer minutes.
* Travel times, arrival times, budgets, and lower bounds are `double` minutes.
* Dataset travel-time breakpoints are `[minute, travel_time]` pairs from 0 to 1440 and are
  stored to three decimal places in the inspected datasets.
* `Domain.canonicalTime` uses a fixed internal decimal scale of 12 with `HALF_EVEN` so composed
  affine roots retain guard precision. Query budgets are a separate public repository unit and
  are rounded upward to scale 9 (`10^-9` minute). Later code must not use the internal scale as
  the query-budget serialization unit.

The inspected generated payloads do not record a conversion from the DIMACS transit-weight unit
to minutes. The official DIMACS source describes that unit as arbitrary. A production dry run on
NY therefore rejects the candidate pool at the lower-bound horizon preflight. The data generator
must make the unit conversion explicit and regenerate the payload; the Java loader must not guess.

`distance` in `Edge`/`edges_static.csv.gz` is a separate `long` road-distance attribute. It is
not the travel-time lower bound and should not be used for temporal binning unless a later
workload specification explicitly asks for physical distance.

## 6. Temporal-support representation

`Domain` is the canonical temporal support: an immutable normalized set of real-valued
intervals with explicit start/end inclusion. `Domain.closed(start,end)` is used for query
departure domains. `TimeRange` is the older half-open `[start,end)` range helper.

`PiecewiseLinearFn` stores travel time, derives a closed domain from its first and last
breakpoints, and computes arrival as `t + travelTime(t)`. The generated datasets declare the
closed support 0 through 1440. `PiecewiseConstFn` stores right-continuous score pieces,
normally `[start,end)`, with the last piece owning the endpoint of a connected component;
gaps are outside the function domain. Generated selected-score functions include zero pieces
outside rush intervals, while loader-synthesized unlisted scores are constant zero over the
corresponding travel-time domain.

PACE defines the required query horizon as the closed interval
`[intervalStart, intervalEnd + budget]`. Both travel and score functions must cover that full
horizon. Query generation must reject a candidate whose budget would take this horizon past
the common function support; for the inspected datasets the upper bound is minute 1440.

## 7. Anchor-index API

Construct a query-specific index with:

```java
Domain queryHorizon = Domain.closed(intervalStart,
        Domain.canonicalTime(intervalEnd + budget));
AnchorIndex anchors = AnchorIndex.create(graph, queryHorizon);
```

`create` validates travel/score coverage for every edge and retains all single-edge anchors
whose positive-score entry domain intersects their valid entry domain. `anchors()` is sorted
by stable arc ID. `queryHorizon()`, `isAnchorArc(int)`, `anchorArcIds()`, and `version()` expose
the immutable index. Each `Anchor` exposes the edge, source/target, stable arc ID, valid entry
domain, positive entry domain, and lower travel time.

`relevantAnchors(source,destination,subproblemDomain,budget,lowerBounds,options)` applies the
PACE lower-bound relevance filter and deterministic policy ranking, returning
`RelevantAnchor` values with score potential, positive coverage, slack, and detour. For query
generation, use `AnchorIndex.create` when an anchor-presence/count eligibility rule is needed;
use `relevantAnchors` only if the workload explicitly requires PACE-policy relevance rather
than graph-level positive-score anchors.

## 8. Current `QueryManifestEntry` fields

Schema version 1 contains, in record order:

1. `int schemaVersion`
2. `String queryId`
3. `String datasetId`
4. `int source`
5. `int destination`
6. `int intervalStart`
7. `int intervalEnd`
8. `int windowLength`
9. `double budget`
10. `Double budgetSlack`
11. `String budgetPolicy`
12. `Integer distanceBin`
13. `Double lowerBoundDistance`
14. `long querySeed`
15. `Map<String,Object> metadata`

The compact constructor makes metadata non-null and immutable. `validate()` accepts only
schema 1, requires nonblank query/dataset IDs, checks
`windowLength == intervalEnd - intervalStart`, and constructs a granularity-1 `QuerySpec`.
`toQuerySpec()` also fixes granularity at 1. It does not itself validate the budget policy,
distance bin, lower-bound value, budget slack, or metadata contents. The JSON schema restricts
`budget_policy` to `tight` or `full-interval-feasible` and disallows unknown top-level fields.

Fastest-time min/max are not record components. `PaceBench.queryRecord` currently reads
`fastest_travel_time_min` and `fastest_travel_time_max` from `metadata`.

## 9. Current `QueryManifestIO` format

The format is UTF-8 JSON Lines: one snake_case JSON object per nonblank line. The shared
Jackson mapper applies `PropertyNamingStrategies.SNAKE_CASE`. `read(Path)` deserializes and
validates every row, rejects duplicate `query_id` values, rejects an empty manifest, and
returns an immutable list. The accompanying JSON Schema is
`experiments/schemas/query_manifest.schema.json` and requires all 15 top-level properties.

`QueryManifestIO` has no write API. `PaceBench.generateQueries` currently serializes entries
through `QueryManifestIO.mapper()` and calls `Files.write` itself. This does not centralize
validation, canonical metadata key ordering, newline choice, overwrite behavior, or atomic
replacement. Also, the schema permits `query_seed` to be an integer or string, while the Java
record is a signed `long`; the unsigned CLI seed representation is therefore not fully
specified for values above `Long.MAX_VALUE`.

## 10. Best Java entry-point mechanism

The implemented entry point is
`edu.ipcmax.experiments.querygen.QuerySetGenerator`. It provides generation-only CLI options and
a non-exiting `execute(String...)` method. `PaceBench` remains the benchmark runner, and
`scripts/run_matrix.py` continues to schedule already declared manifests rather than generating
them.

No new Maven dependency or plugin is necessary. The Maven shade build already includes all
project classes and Jackson. After `mvn package`, the dedicated main can be invoked as:

```text
java -cp target/pace-bench.jar edu.ipcmax.experiments.querygen.QuerySetGenerator \
  --dataset NY --dry-run
```

A later `Makefile` target can wrap that command. The shaded JAR's existing manifest main class
should remain `PaceBench`, preserving `java -jar target/pace-bench.jar` and the existing
benchmark scripts.

## 11. Required compatibility changes

The later implementation phase should make the following focused changes:

1. Add stable node enumeration to `TDGraph` (for example `nodeIds()` sorted ascending or
   `nodes()` with a documented order). Edge-derived node enumeration silently omits isolated
   nodes and unnecessarily scans millions of arcs.
2. Extend the existing lower-bound shortest-path result with deterministic predecessor/path
   and edge-count access. Do not implement a separate Dijkstra inside the query generator.
3. Add `QueryManifestIO.write(Path,List<QueryManifestEntry>)` (and, if useful, a streaming
   writer) that validates entries and duplicate IDs, uses snake_case, sorts metadata keys,
   emits a specified LF-delimited canonical representation, and replaces output safely.
4. Add the dedicated generator entry point and tests for identical output under repeated runs,
   stable tie handling, unreachable pairs, horizon rejection, duplicate prevention, bin
   population, and read-after-write schema compatibility.
5. Preserve schema version 1 by putting audit-only generation metrics such as fastest-time
   extrema and lower-bound hop count in `metadata` unless later consumers require typed
   top-level fields. If top-level fields become required, update `QueryManifestEntry`, both
   JSON schemas/consumers, existing manifests, and the schema version together.
6. Decide whether `ManifestSummary` must expose rush windows/horizon metadata. If generation
   derives these values from `manifest.json`, extend `ManifestSummary` and
   `GeneratedGraphLoader`; do not parse the manifest independently in another loader. If the
   CLI supplies them, the current summary is sufficient.
7. Make missing-manifest handling explicit. `GeneratedGraphLoader` permits a null manifest,
   but `PaceBench.loadDataset` dereferences `loaded.manifest().seed()`. Production generation
   should require the generated-dataset manifest and produce a clear error.

The current schema can already carry additional audit data in `metadata`, so a schema change
is not intrinsically required for a first compatible generator.

## 12. Blockers and ambiguities

There is no blocker to the Phase 1 note or to loading the four datasets with the selected
Java API. These decisions must be fixed before query generation behavior can be considered
reproducible:

* The requested query count per dataset, master/per-dataset seed derivation, output paths,
  query-ID convention, and whether duplicate source/destination pairs are forbidden are not
  specified.
* The intended departure windows, window lengths, budget slack values, and mix of `tight`
  versus `full-interval-feasible` policies are not specified. The current private generator
  hard-codes a 420 start and defaults to a 10-minute window and 1.5 slack.
* Distance-bin boundaries and balancing rules are not specified. The current code uses rank
  quantiles of whole-day lower-bound distance after sampling, not fixed distance thresholds.
* If lower-bound edge counts are selection criteria, ties between equal-distance paths need a
  declared rule. Minimum-hop, first stable Dijkstra witness, and lexicographically smallest
  stable arc path are different workloads.
* It is unclear whether fastest-time coverage means all integer departure minutes (the current
  schema and `QuerySpec` behavior) or an exact continuous fastest profile. No dedicated
  continuous fastest-profile API exists.
* Anchor qualification is ambiguous: any positive-score anchor in the query horizon, an
  anchor lying on a selected path, or a PACE-policy-relevant anchor produce different sets.
* `dataset_id` must be agreed. `PaceBench` requires exact equality with the dataset directory
  name, so the new directories naturally yield `NY`, `FLA`, `CAL`, and `USA`; existing sample
  manifests use `out_ny_td` and `demo`.
* Unsigned `query_seed` serialization above signed-long range needs a canonical number/string
  rule if the full unsigned 64-bit CLI range is intended.
* USA metadata values were not supplied beyond the same-structure assumption. Any logic based
  on counts, seed, or rush-window values should read its manifest through the extended existing
  loader rather than assume the NY/FLA/CAL values.

## Files inspected

The requested files were inspected directly:

* `GeneratedGraphLoader`, `GeneratedGraphDataset`, `ManifestSummary`, `TDGraph`,
  `LowerBoundGraph`, `AnchorIndex`, `QueryLowerBounds`, `FastestPathAlgorithmRunner`,
  `QueryManifestEntry`, `QueryManifestIO`, and `PaceBench`
* `scripts/run_matrix.py`, `pom.xml`, and `Makefile`
* `data/input/NY/manifest.json`, `data/input/FLA/manifest.json`, and
  `data/input/CAL/manifest.json`

Supporting APIs, schemas, and tests were inspected where needed to verify behavior, including
`Domain`, `TimeRange`, the piecewise function classes, `QuerySpec`, point/interval labeling,
`RepeatedFastestPathBaseline`, path/profile witness classes, `Anchor`,
`ExactPathProfileBuilder`, `PaceFrontierGenerator`, `BenchOptions`, the query-manifest schema,
and the loader/lower-bound/labeling/benchmark framework tests.
