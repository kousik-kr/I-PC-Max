# THop M2B implementation report

## Scope and audit

Implemented the ICDE 2019 THop Section IV-A incremental pruned construction in
the existing PACE repository. Work stayed on
`feature/icde27-baseline-implementation`, starting at commit `2461464`.
The pre-existing untracked `Baseline papar/` directory and its PDFs were
preserved. No PACE-X, PACE-B, iSCOPE, partitioning, approximation, compression,
or SICSP code was changed.

M2A passed `mvn -q -Dtest=THopIndexTest test` before this milestone. Its
unpruned builder remains available as `THopIndex.build(graph, domain)` and is
used as a differential reference.

The requested implementation-plan reconciliation required no file recovery or
history rewrite. `docs/PACE_FINAL_ALGORITHM_IMPLEMENTATION_PLAN.md` exists in
the worktree and its Git blob is identical to GitHub `main`'s blob
`840cbc699b3a391f6129e7b25fee5fb6dd3de72f`.

## Construction and exactness

`THopIndex.buildPruned` processes a stored, deterministic hub permutation. By
default, nodes are sorted by Euclidean distance to the arithmetic mean of
their coordinates, then by node ID. The graph model has no missing-coordinate
marker; if all coordinates coincide, ascending node ID is used as the
deterministic fallback. A public overload accepts an explicit permutation for
reproducible order comparisons.

For every hub, the builder runs a forward search over outgoing arcs and a
reverse-backward search over original incoming arcs. For a directed edge
`u->v` with arrival map `A_uv` and known suffix profile `F_vh`, reverse
relaxation computes `F_vh(A_uv(t))`. It does not reverse the edge and reuse its
travel-time function unchanged. The backward root identity covers the minimum
to maximum arrival image of all single edges, so a last edge may arrive beyond
the departure horizon. Earlier reverse relaxations still require each actual
edge departure to lie in its support.

At each settled vertex, the builder queries only labels belonging to completed
hubs. A previous envelope prunes the candidate only when it is defined across
the candidate's complete support and is no greater at every departure.
`ExactTemporalProfile.dominates` checks every owned singleton and the two
limits of each open affine slab split by either profile's breakpoints. This
covers crossings, equality, open/closed endpoint differences, and support
gaps without sampling. A pruned vertex is not expanded. A partially dominated
profile is retained and expanded.

`THopIndex.query` continues to intersect source out-label and destination
in-label hub IDs, compose their absolute-arrival profiles, and return the exact
minimum travel-time envelope on the query domain. Preference score and PACE
budget are not query inputs.

## Metrics

`PreprocessingStatistics` reports wall time, hub and forward/backward call
counts, constructed profiles, retained and pruned labels, skipped expansions,
forward/backward expansions, temporal pieces and operators, estimated index
bytes, and sampled peak heap. `QueryStatistics` separately reports common
hubs, compositions, envelope operations, query time, result pieces, and
sampled peak heap. Estimated index bytes are a payload estimate, not JVM
object-layout measurements. Peak heap is sampled used heap, not an allocation
profile.

A small synthetic comparison was run on a 5-node, 5-edge static graph with an
equal-cost route pair and one disconnected node. The table reports the median
of 11 alternating construction runs after five warm-up pairs, and the median
of 101 query runs after ten warm-ups. Query was `(1,4,[0,20])`. These values
describe this tiny fixture only; they are not large-network performance
claims.

| Metric | Unpruned reference | Pruned THop |
|---|---:|---:|
| Construction p50 | 1.94 ms | 3.74 ms |
| Directed labels retained | 22 | 16 |
| Labels pruned | 0 | 5 |
| Search expansions skipped | 0 | 5 |
| Profiles constructed | 13 | 23 |
| Temporal pieces | 38 | 20 |
| Estimated index bytes | 3,296 | 2,000 |
| Sampled peak used heap | 48,747,920 B | 50,761,240 B |
| Query p50 | 0.56 ms | 0.16 ms |
| Common hubs for query | 4 | 1 |
| Query compositions / envelopes | 4 / 3 | 1 / 0 |
| Result pieces | 3 | 1 |

The pruned construction does more profile work on this tiny graph because it
executes both directions; the retained label set and query envelope are
smaller. The sampled heap figures include JVM noise and should not be read as a
memory comparison for larger graphs.

## Validation

The new differential tests compare complete profiles, including open slabs and
owned singleton boundaries, across the pruned index, unpruned index, and
independent simple-path oracle. Covered cases include static and directed
asymmetric networks, equal-cost alternatives, changing fastest paths, FIFO
plateaus, full and partial dominance, endpoint ownership, support gaps,
disconnected vertices, multiple hubs, and different explicit hub orders.
Score changes and PACE travel-time budgets are also checked to leave THop
output unchanged.

Commands and results during this milestone:

| Command | Result |
|---|---|
| `mvn -q -Dtest=THopIndexTest test` (M2A audit, before edits) | Passed. |
| `mvn -q -DskipTests compile` (first implementation compile) | Failed on an incorrect local type in the new arrival-domain loop; fixed by iterating the returned `TimeProfile` cells directly. |
| `mvn -q -DskipTests compile` (after fix) | Passed. |
| `mvn -q -Dtest=THopIndexTest test` (intermediate test revision) | One assertion failed because the fixture had one common query hub and correctly performed zero envelope operations; the per-query assertion was removed. |
| `mvn -q -Dtest=THopIndexTest test` (after correction) | Passed, 9 THop tests. |
| `mvn test -q` | Passed, 263 tests; 0 failures, 0 errors, 0 skipped. |
| `git diff --check` | Passed. |
| `java -version` | OpenJDK 21.0.7; `pom.xml` sets `maven.compiler.release` to 21. |
| `javac -cp target/classes -d /tmp /tmp/THopMetrics.java && java -cp target/classes:/tmp edu.ipcmax.baselines.THopMetrics` | Completed the small comparison reported above; the runner remained in `/tmp`. |

## Limits and unresolved paper details

The primary THop index retains M2A's common edge-support-domain restriction.
The paper does not define the correctness of one-profile-per-hub labels when
edges have heterogeneous availability windows. Direct functional search can
handle heterogeneous support, but this THop builder does not claim that case.

The paper does not specify a coordinate distance metric, tie rule, or
endpoint-ownership convention for profile dominance. This implementation uses
Euclidean coordinate distance, node-ID ties, and weak pointwise dominance on
the entire candidate support as documented above. It also interprets all
coincident coordinates as unavailable or degenerate metadata because the
repository's node type has no explicit availability flag.

No partitioning or SICSP work was started. No large-network experiment was
run.
