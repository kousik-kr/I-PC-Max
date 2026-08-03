# Paper query-set generation

Run the server query pipeline with:

```sh
python3 experiments/scripts/generate_query_sets.py \
  --config experiments/configs/paper_q1_server_24c_250g.yaml \
  --overwrite
```

Validation deterministically regenerates the queries through the Java graph APIs and compares the
bytes with the saved combined manifests:

```sh
python3 experiments/scripts/generate_query_sets.py \
  --config experiments/configs/paper_q1_server_24c_250g.yaml \
  --validate-only
```

Resume and read-only planning are also explicit:

```sh
make paper-resume-queries
make paper-plan-queries
```

## Required graph assets

Each base dataset belongs at `data/input/<dataset>/`, where `<dataset>` is `NY`, `FLA`, `CAL`,
`OL`, or `NY-Exact`. Every directory must contain:

```text
edges_static.csv.gz
nodes.csv.gz
manifest.json
score_functions.jsonl.gz
travel_time_functions.jsonl.gz
```

`nodes.csv.gz` is the generated coordinate file (`node_id,x,y`). `edges_static.csv.gz` contains
the static arc endpoints, distance, and base travel time. The two JSONL gzip files contain the
temporal travel-time and score functions. The generator uses these generated files through
`GeneratedGraphLoader`; it does not depend on the original DIMACS source paths recorded in
`manifest.json`.

The NY sensitivity assets use the same five-file contract at:

```text
data/input/NY/variants/score-density-005/
data/input/NY/variants/score-density-010/
data/input/NY/variants/score-density-020/
data/input/NY/variants/score-density-040/
data/input/NY/variants/seed-43/
data/input/NY/variants/seed-44/
```

Graph seed 42 is the base `data/input/NY/` directory. Every base and variant manifest must declare
a machine-readable temporal support end of at least minute `10080`. Travel-time values must use
the documented minute conversion and must leave enough horizon for
`interval_end + budget`; the loader and exact budget builder do not rescale, wrap, clamp, or
extrapolate functions.

## Outputs

For each dataset, generation writes:

```text
experiments/manifests/queries/<dataset>/paper_q1.jsonl
experiments/manifests/queries/<dataset>/paper_q1.manifest.json
experiments/manifests/queries/<dataset>/pilot.jsonl
experiments/manifests/queries/<dataset>/pilot.manifest.json
experiments/manifests/queries/<dataset>/warmup.jsonl
experiments/manifests/queries/<dataset>/warmup.manifest.json
experiments/manifests/queries/<dataset>/evaluation.jsonl
experiments/manifests/queries/<dataset>/evaluation.manifest.json
```

The combined `paper_q1.jsonl` file is consumed by T01-T05. Sidecars record all
seeds, generation parameters, conversion contract, structural and temporal
input checksums, output/manifest checksum, independent counts, and the
generating command. They intentionally contain no wall-clock timestamp, so
repetition is byte-identical.

## Pair and derived-instance contract

The Java generator calls `GeneratedGraphLoader`, `QueryCandidateSampler`, the
`LowerBoundOracle` interface, and `PointForwardLabeling`. Python derives the
matrix and validates records; it has no graph representation or DIMACS parser.
Base pairs are selected once in five deterministic lower-bound-distance bands,
then reused for all window, budget, depth, density, ablation, and parallel
studies. Pilot, warm-up, and evaluation endpoint pairs are disjoint.

The base-pair counts are 20 NY pilot pairs, 10 warm-up pairs per dataset, and
100 evaluation pairs per dataset. With two time centers this yields 40 pilot,
20 warm-up, and 200 default evaluation instances. The full checked matrix adds
the declared window and budget cells and, for NY, the graph variants; it does
not resample endpoints.

For every interval the generator evaluates exact fixed-departure fastest
travel time at Delta = 1 minute. It records the grid minimum and defines
`T_hat_min,Delta` as the maximum of those pointwise fastest travel times,
which is the full-interval-feasible requirement. It then computes:

```text
B = canonical_time((1 + rho) * T_hat_min,Delta)
```

Generation rejects a row unless `interval_end + B <= function_support_end`.
Temporal functions are never wrapped, clamped, or extrapolated. Overlapping
study windows share exact source/departure labeling work, but deterministic
reduction and serialized rows are independent of traversal completion order.

## Query-side indexes

`QueryPreparationIndexes` builds, from the canonical `TDGraph`, the
`EdgeTemporalSummaryStore`, stable `GraphPartitionMetadata`, and
`ScoreSupportIndex`. The production query-preparation call graph builds these
indexes for each base/variant dataset and checks density nesting. It does not
copy graph topology or parse files a second time.

`LowerBoundOracle` currently has one admissible implementation,
`ExactDijkstraLowerBoundOracle`, which reuses `LowerBoundGraph`. This is the
retained exact fixture/fallback path. The repository still has no scalable
continental preprocessed lower-bound routing index; that is an explicit
external implementation gap, not a claim that repeated Dijkstra is such an
index.
