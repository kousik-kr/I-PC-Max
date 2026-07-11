# PACE

This repository implements **PACE: Profile-Aware Candidate Envelope** for constrained
preference path-profile queries on FIFO time-dependent directed graphs.

PACE exposes two execution policies:

- `PACE_X` exhaustively enumerates safely relevant anchors and all lower-bound-feasible
  simple anchor-free connectors. It is intended for tiny validation graphs.
- `PACE_B` deterministically limits each recursive subproblem to `L` anchors, `K`
  connector profiles, and `K` candidate fragments per temporal cell. Its envelope is
  exact over the retained root frontier; it does not claim unconditional global optimality.

`IPCMax` remains as a deprecated compatibility facade and delegates to the same PACE
implementation.

## Model

Edge arrival functions are continuous, nondecreasing, and piecewise linear. Edge scores
are nonnegative and piecewise constant. Profile domains preserve exact open/closed endpoint
ownership, and every downstream function is evaluated at its actual edge-entry time.

For query `(G,s,d,I,B,theta)`, `theta` is the maximum number of explicitly introduced
anchor-edge occurrences in a candidate - not recursion-tree depth. PACE refuses a query with
`FUNCTION_HORIZON_EXCEEDED` when `[t_s,t_e+B]` is outside graph-function coverage; it never
wraps, extrapolates, clamps, or repeats daily functions.

## Java API

```java
PACE exact = new PACE(graph, PaceOptions.exhaustive(theta));
EnvelopeProfile exactProfile = exact.run(query);

PaceOptions boundedOptions = PaceOptions.bounded(theta, anchorLimitL, candidateLimitK);
PACE bounded = new PACE(graph, boundedOptions);
EnvelopeProfile retainedFrontierProfile = bounded.run(query);
```

Each `EnvelopeSegment` has an endpoint-aware interval and either a selected path or
`NO_PATH`. `EnvelopeProfile.segmentAt(t)` performs an unambiguous lookup.

## Generated graph input

The loader accepts the synthetic time-dependent road graph files produced by the sibling
`Synthetic-data-generator` project:

- `nodes.csv.gz`
- `edges_static.csv.gz`
- `travel_time_functions.jsonl.gz`
- `score_functions.jsonl.gz`
- `manifest.json`

The copied NY synthetic input is under:

```text
data/input/out_ny_td
```

## Build and test

Java 21 is supported. Maven is the intended build tool:

```bash
mvn clean test
mvn package
```

On Windows PowerShell the same commands are:

```powershell
mvn clean test
mvn package
```

## Smoke Loader

After compiling, load and validate a generated graph directory:

```bash
java -cp target/classes edu.ipcmax.experiments.DatasetSmokeRunner data/input/out_ny_td
```

The copied synthetic NY input has been regenerated with FIFO-preserving travel-time profiles. The smoke runner
reports `non_fifo_edges` explicitly so input suitability is visible before algorithmic execution.

## Small query sanity check

The CLI has a built-in four-node graph for quick validation. This command runs a
small PACE-B profile query from node `1` to node `4` over departure interval
`[420,430]` with budget `60`:

```powershell
mvn package
java -cp target/classes edu.ipcmax.experiments.PaceCli --demo `
  --source 1 --destination 4 `
  --departure-start 420 --departure-end 430 `
  --budget 60 --theta 2 --anchor-limit 8 --candidate-limit 8
```

Expected output:

```text
algorithm=pace-b
status=SUCCESS
segments=1
segment_0=[420.0,430.0] -> [0, 1]
```

PACE-X on the same built-in tiny graph:

```bash
java -cp target/classes edu.ipcmax.experiments.PaceCli --demo --algorithm pace-x \
  --source 1 --destination 4 --departure-start 420 --departure-end 430 \
  --budget 60 --theta 2
```

PACE-B on the same graph:

```bash
java -cp target/classes edu.ipcmax.experiments.PaceCli --demo --algorithm pace-b \
  --source 1 --destination 4 --departure-start 420 --departure-end 430 \
  --budget 60 --theta 2 --anchor-limit 8 --candidate-limit 8 --threads 4
```

`--threads` is part of the deterministic execution configuration; output is independent
of the configured worker count.

Legacy validation baselines remain available:

Built-in tiny exact oracle:

```bash
java -cp target/classes edu.ipcmax.experiments.PaceCli --demo --algorithm oracle \
  --source 1 --destination 4 --departure-start 420 --departure-end 420 --budget 60
```

Repeated fastest-path baseline on the copied NY input:

```bash
java -cp target/classes edu.ipcmax.experiments.PaceCli --graph data/input/out_ny_td \
  --algorithm fastest --source 1 --destination 2 \
  --departure-start 420 --departure-end 420 --budget 10000
```

The exhaustive oracle and PACE-X should only be used on small graphs. Use PACE-B for
production-sized inputs and choose limits appropriate for available memory.
