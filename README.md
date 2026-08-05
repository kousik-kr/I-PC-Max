# PACE

This repository implements **PACE: Profile-Aware Candidate Envelope** for constrained
preference path-profile queries on FIFO time-dependent directed graphs.

PACE exposes two execution policies:

- `PACE_X` exhaustively enumerates safely relevant anchors and all lower-bound-feasible
  simple anchor-free connectors within the configured `theta`. It is intended for tiny
  validation graphs and is not globally certified merely by selecting this policy.
- `PACE_B` deterministically limits each recursive subproblem to `L` anchors, `K`
  connector profiles, and `K` candidate fragments per temporal cell. Its envelope is
  exact over the retained root frontier; it does not claim unconditional global optimality.

`IPCMax` remains as a deprecated compatibility facade and delegates to the same PACE
implementation.

The experiment CLI also exposes two five-second T03 methods:

- `iscope` streams loopless complete paths into exact full-window profiling and
  maintains an anytime score-maximizing feasible envelope. It is distinct from the
  legacy single-result `interval-best` baseline.
- `allfp` implements continuous functional Time-Interval All Fastest Paths. It
  reuses one measured search across rho-only budget variants; stable
  outgoing-edge composition can use the configured per-query thread limit. Its
  search and envelope ignore preference score and the PC-Max budget; score is
  attached only for post-hoc analysis.

The old `rpq` and `interval-best` IDs remain readable for historical manifests.

## Model

Edge arrival functions are continuous, nondecreasing, and piecewise linear. Edge scores
are nonnegative and piecewise constant. Profile domains preserve explicit open/closed endpoint
ownership, and every downstream function is evaluated at its actual edge-entry time. Temporal
endpoints currently use a canonical decimal `double` model; the paper's exact rational-arithmetic
condition is not yet certified.

For query `(G,s,d,I,B,theta)`, `theta` is the maximum number of explicitly introduced
anchor-edge occurrences in a candidate - not recursion-tree depth. PACE refuses a query with
`FUNCTION_HORIZON_EXCEEDED` when `[t_s,t_e+B]` is outside graph-function coverage; it never
wraps, extrapolates, clamps, or repeats daily functions.

## Java API

```java
PACE exhaustive = new PACE(graph, PaceOptions.exhaustive(theta));
EnvelopeProfile exhaustiveProfile = exhaustive.run(query);

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

The unified paper experiment runner, manifests, schemas, matrix configurations, and reproduction
commands are documented in [experiments/README.md](experiments/README.md).

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

## Deterministic query sets

Build the shaded JAR, then run the dedicated generator in dry-run mode before writing manifests:

```bash
mvn package
java -cp target/pace-bench.jar edu.ipcmax.experiments.querygen.QuerySetGenerator \
  --dataset NY --dry-run --verbose
```

The current large dataset manifests do not record a conversion from the DIMACS arbitrary transit-weight
unit to minutes. The generator therefore rejects horizon-incompatible candidate pools with a detailed
`function_horizon_exceeded` balance report. Regenerate the payloads with an explicit, documented unit
conversion before producing experiment manifests; the loader deliberately does not guess or rescale them.

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
