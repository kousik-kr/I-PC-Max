# R4 — Objective-specific experiment adapters

## Scope and saved failure

The M5 registry lacked `thop-partitioned` and `sicsp-native`. Before dispatch
was added, `ObjectiveAdapterRegistrationTest` saved both missing lookups as
deterministic tests. The original run in `/tmp/m5r-r1-r4-red.log` reports two
`unknown --algorithm` errors. Both tests now pass. Existing method IDs remain
registered.

Neither new method produces a PACE preference envelope. Their authoritative
scientific output is `output.objective_result`, also exposed through
objective-specific output fields. Preference scores and score-gap fields are
null. The common `EnvelopeProfile` is absent. Canonical sorted JSON over only
the scientific payload supplies the output checksum and optional serialized
profile; query timing and preparation metadata cannot change this checksum.
The CLI captures partition/native-weight file SHA-256 values in normalized
and scientific configuration identity. Adapters parse one immutable byte
snapshot and reject input content changed since CLI parsing. Same-path input
mutation therefore cannot silently preserve the recorded configuration hash.
Bundled fixtures carry an explicit semantic graph SHA-256 and scope; physical
dataset-file checksum fields remain null because those files do not exist.

## THop-Partitioned

`ThopPartitionedExperimentAlgorithm` calls the existing
`PartitionedTHopIndex.query` and uses only `exactTravelTimeProfile`, preserving
rational affine coefficients, rational boundaries, singleton cells, and both
endpoint ownership flags. It never computes the result from a rounded legacy
projection. The index currently constructs a rounded compatibility view while
forming its result object; the adapter does not read that view.

The objective is the fastest travel-time profile for the supplied directed
graph and departure domain. Query preference scores and PACE's travel-time
budget are ignored explicitly. A completed nonempty answer is globally
certified **for the fastest-profile objective**. An empty supported answer is
`NO_FEASIBLE_PATH`; an unsupported temporal domain fails explicitly rather
than being reported as unreachable.

Required CLI inputs are:

| Flag | Meaning |
|---|---|
| `--thop-index-dir` | Directory containing `boundary.bthp` and `inner.pthp` |
| `--thop-partitions` | Version-1 JSON with a complete `assignments` object mapping vertex IDs to nonnegative partition IDs |
| `--thop-domain-start`, `--thop-domain-end` | Explicit index departure support |
| `--thop-lifecycle` | `load` (default), `build`, or `load-or-build` |

`prepare` builds or loads once outside measured queries. `build` refuses to
overwrite either existing index; `load-or-build` builds only when neither
file exists. Partial, corrupt, obsolete, mismatched, or incompatible pairs
are rejected. Existing v2 loaders verify checksums, graph fingerprints,
partition metadata and the inner file's boundary digest. The adapter also
requires the requested partition assignment and index domain to match the
loaded index. It records SHA-256 identities of both index files and the
partition input.

After construction the adapter deliberately reloads the persisted pair,
leaving inner partition labels lazy. Repeated `prepare` with the same graph
object/options is idempotent; repeated queries reuse the loaded index.
Warm-up uses the same prepared state. `close` releases the in-memory
references without removing index files. The runner closes algorithms even
on an early return. Resource-limited THop execution requires the explicit
`--shared-preprocessing` protocol so prepared state persists across queries.

Boundary preprocessing, inner preprocessing, load time, index bytes,
partition/boundary counts and profile pieces are preparation metadata.
Query timing separately records exact profile-query wall time and lazy-load
time; counters include compositions, envelopes, boundary combinations, result
pieces and lazy partition payload bytes read. Cold/warm labels refer to
whether the query decoded uncached partition labels, not operating-system
page-cache state. The disk-byte counter measures logical lazy payload bytes;
it is not a physical-device I/O measurement. `preprocessing_total` includes
all preparation, identity hashing and graph loading outside the query timer.

The construction schedule is explicitly
`search_first_then_prune_materialize`, with
`paper_incremental_search_prune=false`. See
`THOP_FIDELITY_AND_INDEX_READINESS.md`; paper-identical preprocessing work or
memory bounds are not claimed.

Example tiny workflow:

```sh
java -jar target/pace-bench.jar --algorithm thop-partitioned \
  --dataset demo --query-file experiments/manifests/tiny.jsonl \
  --thop-index-dir /tmp/icde27-thop-demo \
  --thop-partitions experiments/fixtures/thop_demo/partitions.json \
  --thop-domain-start 0 --thop-domain-end 1440 --thop-lifecycle build \
  --shared-preprocessing --warmup-runs 1 --repetitions 2 \
  --output-jsonl /tmp/icde27-thop-demo.jsonl --serialize-profile \
  --profile-output-dir /tmp/icde27-thop-profiles
```

Use `--thop-lifecycle load` for later executions over this persisted pair.
Existing files are never silently rebuilt after an identity/checksum failure.

## Native SICSP

`SicspNativeExperimentAlgorithm` invokes the existing native engine, including
unchanged LST/EAT searches, original travel events, native weight events,
O/W dominance and native selection. Its objective remains minimum arrival
over the departure interval subject to accumulated native edge weight
`<= Delta`. It does not optimize PACE scores, apply the PACE travel-time
budget, or substitute scores for weights.

`--sicsp-weights` and `--sicsp-weight-bound` are mandatory. The version-1 input
must declare `semantics: native_sicsp_edge_weight` and define every arc using
explicit nonnegative constant pieces with start/end ownership. The native
graph rejects missing arcs, unknown arcs and weight support that does not
cover travel support. The existing engine validates its FIFO temporal model.
The bound comes from the independent CLI option, never `QuerySpec`'s budget.

The adapter reports the selected departure, candidate arrival, native
accumulated weight, route IDs, weight feasibility, O/W counters, LST/EAT
search calls, phases in nanoseconds, explicit weight input hash and profile
discrepancy diagnostics. The original heuristic can miss globally feasible
routes, so its scope is always `NOT_CERTIFIED`; an empty candidate selection
is `UNRESOLVED_NO_GENERATED_CANDIDATE`, not certified graph infeasibility.
Native legacy arithmetic and its documented fractional-root discrepancy
remain visible; this adapter does not claim to repair native profile values.

The checked-in Figure 1 workflow is:

```sh
java -jar target/pace-bench.jar --algorithm sicsp-native \
  --dataset sicsp-figure1 \
  --query-file experiments/fixtures/sicsp_native_figure1/queries.jsonl \
  --sicsp-weights experiments/fixtures/sicsp_native_figure1/weights.json \
  --sicsp-weight-bound 80 --threads 1 \
  --output-jsonl /tmp/icde27-sicsp-figure1.jsonl --serialize-profile \
  --profile-output-dir /tmp/icde27-sicsp-native-output
```

The fixture's query-budget field is explicitly a schema placeholder; its
value is 1, yet native selection remains departure 0, arrival 50, accumulated
weight 25, route `[0,1]`, matching the independent paper-example expectation.
This demonstrates that Delta is not taken from that budget.

## Integration evidence

`ObjectiveAdaptersTest` covers registry IDs, build/load/reuse, warm lazy-label
state, exact result checksums independent of preference budgets, unsupported
support, unreachable queries, partition/graph mismatch, refusal to overwrite,
v1 rejection with a valid checksum, explicit native weights, Figure 1,
unreachable native selection, missing input and empty weight registry.

`ObjectiveAdapterCliTest` parses files through the CLI, runs warm-up plus
repetitions, checks objective-specific scopes, nanosecond timing/counters,
serialized output, repeated checksums, numerical metadata and separation
from preference quality. Two additional regressions change weight/partition
contents at the same path and verify both a changed configuration hash and
rejection of stale preparation. The result schema accepts the two new policies and
the existing SICSP-Pref/unresolved statuses. Python matrix forwarding has
two focused objective-option tests.

Observed focused validation:

| Command | Result |
|---|---|
| `mvn -B -ntp -Dtest=ObjectiveAdapterRegistrationTest,ObjectiveAdaptersTest test` | 8 passed, 0 failures/errors/skips; 2026-10-16T01:55:03Z |
| `mvn -B -ntp -Dtest=ObjectiveAdapterRegistrationTest,ObjectiveAdaptersTest,ObjectiveAdapterCliTest,ExperimentAlgorithmsTest,ExperimentDeterminismTest,BenchOptionsTest,PartitionedTHopIndexTest,BoundaryTHopIndexTest,THopIndexTest,SicspNativeEngineTest test` | 58 passed, 0 failures/errors/skips; 2026-10-16T01:56:23Z |
| Final hardened adapters: `mvn -B -ntp -Dtest=ObjectiveAdapterRegistrationTest,ObjectiveAdaptersTest,ObjectiveAdapterCliTest test` | 13 passed, 0 failures/errors/skips; 2026-10-16T02:04:01Z; includes content-identity regressions |
| `python3 -m pytest -q experiments/tests/test_objective_adapters.py` | 2 passed |

The final full milestone run supersedes these focused runs after later
metadata and schema additions. No full-network benchmark was executed.
