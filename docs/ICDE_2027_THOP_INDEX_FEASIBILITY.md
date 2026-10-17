# ICDE 2027 THop v2 Index Feasibility

## Decision

Do not report THop full-network query results. No NY, FLA, or CAL BTHP/PTHP v2 index completed, so there are no frozen index bytes, load times, steady-state query times, or full-graph fastest profiles. Keep THop as a separately scoped exact fastest-travel-time reference; it is not a preference-score or budget-aware baseline.

The feasibility run used deterministic spatial recursive median bisection as a controlled partitioning proxy. This is **not** the Natural Cut partitioner and no Natural Cut fidelity is claimed. The partitioning source is committed in `experiments/tools/thop_feasibility/ThopFeasibility.java`; it chooses alternating x/y medians, with deterministic y/x/node-ID tie breaks and no random seed. It generates balanced partitions, not road-network cuts optimized for separator quality.

## Full graph and environment evidence

| Dataset | Nodes | Directed arcs | Temporal support | FIFO arcs | Boundary vertices (64 balanced spatial shards) | Cross-shard directed arcs | Assignment SHA-256 |
|---|---:|---:|---|---:|---:|---:|---|
| NY | 264,346 | 733,846 | `[0, 10080]` | 733,846 | 9,993 | 12,134 | `24cd6f0a1861e465a84197dd7bc9111b0c9a3f82578224f3d7040fb0563588c2` |
| FLA | 1,070,376 | 2,712,798 | `[0, 10080]` | 2,712,798 | 16,181 | 17,872 | `f7a838f2aa7f31f560e790fab7771fbae0b9f8fc91548c5467e49345a50d6895` |
| CAL | 1,890,815 | 4,657,742 | `[0, 10080]` | 4,657,742 | 19,171 | 21,600 | `d7336f68b1502f3aea9e29b1346f5097020195515c4341db93493a5fa8bd393a` |

Graph and temporal checksums are recorded in [main run readiness](ICDE_2027_MAIN_RUN_READINESS.md). The host offered 313 GiB available RAM, 218 GB free disk, and 32 logical/physical CPUs at preflight. Available memory does not establish an index build-time or memory guarantee.

At minimum, BTHP phase 1 needs one full-network source-profile search for every boundary vertex: 9,993 NY, 16,181 FLA, and 19,171 CAL sources, before inner PTHP construction. The counts above are from the deterministic spatial proxy; they must not be presented as Natural Cut boundary counts or shortcut counts.

**Boundary shortcut counts and full-index inner-index measurements are unavailable.** No full boundary-source pass completed. The observed full-network cross-shard arc counts are not shortcut counts.

## Controlled induced-graph construction

For each dataset, controlled samples were induced from the full graph by undirected BFS from node ID 1. Incoming and outgoing arcs were visited in ascending `arcId`; the induced graph retained only arcs whose endpoints were selected. Each induced graph was partitioned into four equal balanced spatial-median shards with the same deterministic tie rules.

| Induced vertices | Dataset | Induced directed arcs | Boundary vertices | Cross-shard arcs | Assignment SHA-256 |
|---:|---|---:|---:|---:|---|
| 128 | NY | 302 | 39 | 46 | `d17340eed4c622f554288f847f5a39c677358996548d90a9d0e7a3275745ac31` |
| 128 | FLA | 280 | 24 | 30 | `9fd5e8708cef607987b4e31aaeb1f80c1b86fcbe96a204501c7d122f75fb3fbc` |
| 128 | CAL | 294 | 23 | 26 | `863faaa35c7229b53755a7bbeda4311e9fd1c7982da22c461b011b988e2feae1` |
| 256 | NY | 616 | 40 | 48 | `00f64a7f189543aaf7597a820f1eaa535045b1d0e6d7917b17692a1bd96f9d54` |
| 256 | FLA | 562 | 38 | 46 | `3f4232adfed6d61676c17d3f04cd2f9be12d74e258e1ef8a4e90e0810fe3dadb` |
| 256 | CAL | 608 | 50 | 56 | `2e71651e8ee9bdb74e53b6b541d9c5c85de6d7446bd1cfca8ed187226cb1b1b1` |
| 512 | NY | 1,252 | 61 | 72 | `c756e5e333c9c97f6fb24214e49cc82e3a38371f1eae47f7dc44eb89a06e8ac6` |
| 512 | FLA | 1,138 | 50 | 66 | `fa43a36b321e7a023f11fd7fbf2d0bcabedb94f05d06665aa92c9283d9649d97` |
| 512 | CAL | 1,294 | 61 | 68 | `e5b1e016bae8637244496b7d34a31e49fc15deaaf92c90664836e8204eb92af8` |

The table is structural metadata, not index performance. Only one actual index construction was attempted, on the 128-vertex NY induced graph, using four spatial shards, phase-1 parallelism 1, and domain `[0, 10080]`.

## Partial NY index attempt

The 128-vertex NY attempt reached 5 of 39 boundary source shards and was stopped without a completed BTHP or PTHP file. The observed partial source-shard measurements were:

| Source vertex | Profiles | Pieces | Max rational component bits |
|---:|---:|---:|---:|
| 1 | 39 | 3,316 | 483 |
| 1,174 | 39 | 3,318 | 519 |
| 1,177 | 39 | 3,045 | 481 |
| 1,181 | 39 | 2,282 | 441 |
| 1,182 | 39 | 2,527 | 441 |

Across these five sources: 195 profiles, 14,488 pieces, maximum 519 bits in a rational component, maximum 297 pieces in one profile, and 2,289,856 serialized profile bytes. Peak observed process RSS was about 6.3 GB; Java CPU time was about 3:14. Boundary and inner construction times were not separately completed. There is no measured disk index size or functional-composition total.

This partial induced-graph result does not bound full-graph construction time. It also does not estimate the remaining 34 sources as a guarantee. No sampling, profile approximation, or omitted boundary shortcut was used to produce a completed index.

## Exactness and aggregation contract

The independent rational tiny-graph oracle, exact profile composition, and v2 save/reload tests are part of the Java test suite. The THop collector validates exact rational cells, endpoint ownership, requested-domain coverage, travel-time coefficients, checksum, and the `feasible` flag. The THop query adapter serializes `ExactArrivalProfile.asTravelTime()`, so the resulting affine coefficients are already travel-time coefficients; the aggregator integrates them directly and does not subtract departure time.

The primary full-network run continued independently of THop. No THop query was dispatched because no real-network v2 index was validated and frozen.

## Reproduction

The deterministic partition metadata and partial shard parser are available in:

- `experiments/tools/thop_feasibility/ThopFeasibility.java`
- `experiments/tools/thop_feasibility/ShardMetrics.java`

The metadata-only modes are `meta:128`, `meta:256`, and `meta:512`; passing a size without the `meta:` prefix attempts index construction. Full-network partition metadata uses 64 shards. A complete THop release would still require full BTHP/PTHP construction, exact save/reload validation, frozen index fingerprints and byte counts, and the same 50 query identities with two trials per dataset.
