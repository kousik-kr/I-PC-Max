# I-PC-Max

This repository implements I-PC-Max, an interval version of PC-Max for time-dependent constrained path optimization.
BiSCOPE is not part of the core algorithm; it may only appear later as a separate baseline or experiment label.

The current foundation loads the generated synthetic time-dependent road graph files produced by the sibling
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

## Build And Test

Java 21 is supported. Maven is the intended build tool:

```bash
mvn test
```

If Maven is not installed, main sources can still be compiled directly:

```bash
javac -d target/classes @sources.txt
```

## Smoke Loader

After compiling, load and validate a generated graph directory:

```bash
java -cp target/classes edu.ipcmax.experiments.DatasetSmokeRunner data/input/out_ny_td
```

The copied synthetic NY input has been regenerated with FIFO-preserving travel-time profiles. The smoke runner
reports `non_fifo_edges` explicitly so input suitability is visible before algorithmic execution.

## Query Runner

Built-in tiny exact oracle:

```bash
java -cp target/classes edu.ipcmax.experiments.IPCMaxCli --demo --algorithm oracle \
  --source 1 --destination 4 --departure-start 420 --departure-end 420 --budget 60
```

Repeated fastest-path baseline on the copied NY input:

```bash
java -cp target/classes edu.ipcmax.experiments.IPCMaxCli --graph data/input/out_ny_td \
  --algorithm fastest --source 1 --destination 2 \
  --departure-start 420 --departure-end 420 --budget 10000
```

## Scope

Implemented now:

- Project scaffold and package layout.
- Core time-domain, piecewise-linear travel-time, and piecewise-constant score functions.
- Directed time-dependent graph model with incoming/outgoing adjacency.
- Loader for the generated compressed CSV and JSONL files.
- Basic graph validation and loader smoke CLI.
- Query specification, discrete `Domain`, exact loopless path replay, and final result tie-breaking.
- FIFO point forward labeling.
- Exact tiny brute-force oracle for correctness tests.
- Repeated fastest-path baseline for smoke experiments.

Next implementation layers should follow the subtask prompt pack: point backward labeling, repeated-PC-Max,
lower-bound graphs, interval labeling, feasible edge profiles, pivots, candidate sets, recursion, safe dominance,
memoization, deterministic parallelism, and paper-scale experiment runners.
