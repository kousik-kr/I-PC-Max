# THop M2C-A Boundary-THop implementation report

## Scope

This milestone implements graph partition metadata, the two-phase offline
Boundary-THop construction, versioned disk storage, and boundary-to-boundary
queries from the ICDE 2019 THop paper. Work stayed on
`feature/icde27-baseline-implementation`. The original PDFs in
`Baseline papar/` were preserved. No PACE-X, PACE-B, iSCOPE, SICSP-native,
SICSP-Pref, M2A, or M2B algorithm was replaced or modified. `THopIndex` only
received a package-private factory for wrapping already-built label maps.

Natural Cut is not implemented. Callers supply a deterministic partition ID
for every graph vertex.

## Paper-to-code mapping

| Li, Wang and Zhou (ICDE 2019) | Implementation |
|---|---|
| §V-A, Graph Partition (paper p. 908) | `GraphPartitionMetadata.fromAssignments` records one partition per vertex, sorted member lists, internal arc IDs, directed cross-partition arc IDs, cut endpoints, incoming/outgoing boundary arc IDs, partition adjacency, and directed partition-pair arc groups. Every original arc is assigned to exactly one of the internal or crossing sets. |
| §V-B, Boundary Hop Construction (paper pp. 908–909) | `BoundaryTHopIndex.build` first runs `FifoFastestPathProfileSearch` from every boundary source on the original `TDGraph`. It retains profiles to reachable boundary destinations and writes each source result to a temporary indexed disk shard. In phase 2 it loads profiles in the recorded hub order and checks each forward and backward label against the envelope from previously processed hubs only. |
| §V-D, Storage (paper p. 909) | The finalized index uses the `BTHP` binary format, version 1. It stores graph/time-function identity, temporal support and endpoints, the complete partition metadata, hub order and construction parameters, both boundary label maps, and deterministic build counters. A SHA-256 payload checksum is checked on load. |
| §IV-C, common-hub query semantics used for the boundary labels | `BoundaryTHopIndex.query` accepts only boundary vertices and delegates to `THopIndex.query`: intersect common hubs, compose their arrival profiles, and take the exact minimum envelope. The result is a travel-time profile. |

The source PDF was read directly from `Baseline papar/Time-Dependent_Hop_Labeling_on_Road_Network.pdf`; its Section V begins on p. 908. Section V-A defines vertex-disjoint subgraphs and boundary endpoints of crossing edges. Section V-B specifies a first all-pairs boundary phase whose search results are written to disk, followed by ordered pruning that compares against the labels built so far. Section V-D says to store the boundary index separately. The implementation follows these mechanics and records its choices where the paper leaves them open.

## Construction details and choices

* **Partition input:** `GraphPartitionMetadata` requires exactly one nonnegative partition ID for every graph vertex. It does not alter edge direction, duplicate or drop arcs, or infer a cut. Equivalent input maps produce identical metadata.
* **Boundary rule:** both endpoints of every cross-partition directed arc are boundary vertices. Incoming and outgoing cross arcs remain separately addressable by original arc ID. Partition adjacency is directed.
* **Phase 1:** one exact, unsampled all-destination functional search runs per boundary source on the complete original graph. Searches can run concurrently with a caller-selected worker count; the default is capped at four workers. Only the boundary-destination profiles are written to per-source disk shards. Shards are random-accessible by destination, so phase 2 can load a hub row or one incoming profile at a time. The transient phase-1 directory is removed after the final index is committed.
* **Phase 2:** the default order is ascending boundary vertex ID; an explicit complete boundary permutation is also accepted and serialized. Forward candidates `h→v` populate `Lin(v)`, and reverse-direction candidates `v→h` populate `Lout(v)`. A candidate is dropped only if the envelope from already processed hubs is defined and no greater over its complete support, including owned endpoints and singleton cells. Pruning does not trigger another graph search and does not replace a dropped candidate with a different path.
* **Index compatibility:** the graph fingerprint covers vertex IDs, directed arc IDs/endpoints, and exact travel-time support and breakpoints. It deliberately excludes edge scores because THop is preference-free. The index domain must cover all edge function domains, and this implementation retains M2A/M2B's common edge-support requirement.
* **Storage and failures:** serialization sorts vertices, hubs, labels, and metadata collections. Loading validates the checksum, format version, graph fingerprint, partition metadata, hub order, temporal support, and self labels. A missing, invalid, or graph-mismatched file raises an error; loading never rebuilds an index implicitly.
* **Scope:** there is no Natural Cut implementation, inner THop construction, arbitrary internal-vertex query, AT-Dijkstra, Bottom-Up Compression, or large-network experiment in this milestone.

## Independent validation

`BoundaryTHopIndexTest` validates one-, two-, and five-partition fixtures. The
five-partition graph includes directed asymmetric edges, multiple crossing
arcs, a disconnected component, equal-cost alternatives, a FIFO plateau,
changing fastest routes, temporal breakpoints and singleton cells. For every
boundary-to-boundary pair, its complete profile is compared with:

1. the Boundary-THop query;
2. the existing unpruned THop index;
3. the existing M2B pruned THop index; and
4. `SimplePathProfileOracle`, which recursively enumerates vertex-simple
   routes and builds the exact temporal envelope.

The fixture also verifies a fastest boundary route that starts in partition 1,
leaves it, crosses partitions 2 and 3, then re-enters partition 1. Its profile
matches the original complete-graph oracle. A second route on the same pair
uses a non-boundary internal vertex. Save/reload checks every boundary pair,
repeated construction with the same parameters produces byte-identical files,
and checksum corruption, missing files, and graph mismatches fail explicitly.

The index API does not expose preference scores, budgets, candidate paths, or
arbitrary internal-vertex queries.

## Metrics

`BoundaryTHopIndex.ConstructionStatistics` reports partition and boundary
counts, crossing arcs, boundary-source search calls, generated/retained/pruned
profile counts, generated/retained function pieces, forward and backward
search work, profile-search compositions and envelopes, phase-1 search time,
phase-1 wall time, pruning time, phase-1 disk read/write time, full build wall
time, load time, temporary profile bytes, actual final index bytes, sampled
peak used heap, and configured phase-1 parallelism. There is no backward graph
search in this two-phase method: incoming hub candidates are read from the
source-oriented phase-1 all-pairs results, so backward counters are zero.

Per-source search elapsed times are summed for `candidateGenerationNanos`;
`phase1WallNanos` is the wall time for phase 1. Disk-write time sums per-shard
write durations and final index output, so in a parallel phase it may overlap
search time. The temporary profile-byte count is not the final index size.
`indexBytesOnDisk` is measured with the finalized file size. Heap use is sampled
JVM used heap, not allocation-profiler output. These metrics describe tiny
correctness fixtures only and do not establish large-network scalability.

`THopIndex.QueryStatistics` reports boundary query time, common hubs, temporal
compositions/envelopes, result pieces, and sampled heap separately from
preprocessing. No large graph benchmark was run.

On the 10-vertex, 15-arc, 5-partition correctness fixture (9 boundary vertices,
6 crossing arcs), one instrumented construction reported 45 phase-1 boundary
profiles, 32 retained non-self labels, 40 pruned candidate labels, 113
generated function pieces, and 103 retained label pieces. It made 9 forward
profile-search calls (52 expanded states, 53 generated states) and no backward
search calls. The final index occupied 5,936 bytes; transient phase-1 profile
shards occupied 5,558 bytes. Sampled peak JVM heap was about 50.8 MB. Search
elapsed time summed across the three workers was 262 ms; phase-1 wall time was
100 ms, pruning took 38 ms, phase-1 reads took 33 ms, aggregate writes took
25 ms, and total construction took 209 ms. A `(3,2)` boundary query over
`[0,60]` had a 1.14 ms median over 101 queries after 10 warm-ups. These are
single-run, tiny-fixture measurements and are not scalability evidence.

## Validation record

Baseline regression tests were run before M2C-A, covering `THopIndexTest`,
`SicspNativeEngineTest`, and `SicspPreferenceEngineTest`. After M2C-A, the
focused `BoundaryTHopIndexTest` passed. The complete `mvn -q test` suite then
passed 292 tests across 64 suites, with 0 failures, 0 errors, and 0 skipped.
The final suite included 4 boundary tests, 9 THop tests, 12 SICSP-native tests,
and 11 SICSP-Pref tests. It can be rerun with:

```text
mvn -q -Dtest=BoundaryTHopIndexTest,THopIndexTest,SicspNativeEngineTest,SicspPreferenceEngineTest test
mvn -q test
```

No merge into `main` was performed.
