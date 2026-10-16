# R7 — THop construction fidelity and index readiness

## Source finding

The source paper is Li, Wang and Zhou, *Time-Dependent Hop Labeling on Road
Network*, ICDE 2019, DOI 10.1109/ICDE.2019.00085, local
`Baseline papar/Time-Dependent_Hop_Labeling_on_Road_Network.pdf`.
Section V-C constructs inner indexes on the augmented partition graph using
the earlier incremental labeling/search-pruning procedure. The repository's
`PartitionedTHopIndex.buildInnerLabels` instead performs an exact complete
functional search for every local source and stores all source-profile rows
before any hub label admission or pruning. It subsequently prunes admitted
labels in ascending vertex ID order using the complete-support prior-label
envelope. This difference is present in the audited source and remains.

## Consequences

| Property | What is established |
|---|---|
| Label correctness | Exact source profiles and full-support prior-label dominance preserve the verified label answers; independent tiny all-pairs rational oracle tests cover direct, unpruned, pruned, boundary and partitioned results. |
| Partition shortcut correctness | Local topology contains original directed internal arcs plus full-graph boundary shortcut profiles, so routes leaving and reentering partitions are represented. Existing counterexample/regression tests verify this. |
| Search-space reduction | Inner label admission can reduce stored labels, but it cannot reduce searches already completed to materialize every local source row. |
| Working memory | Construction retains all local source-profile rows before admission. Its peak working set can therefore exceed an incremental search/prune implementation; no paper-identical memory bound is established. |
| Construction complexity | Paper pruning benefits and any corresponding construction bound do not automatically apply to this schedule. Exact rational operator cost and coefficient bit growth also differ from floating arithmetic cost assumptions. |
| Build-time comparison | Measured build time describes this named exact search-first implementation, including its boundary/inner stages. It cannot be reported as an implementation-identical measurement of the paper's incremental procedure. |
| Query objective | Fastest travel-time profile only; no preference optimization or PACE budget constraint. |

The verified implementation is retained as an explicitly labeled variant.
The adapter emits `construction_schedule=search_first_then_prune_materialize`
and `paper_incremental_search_prune=false`. A separately tested incremental
mode would be required to claim computational fidelity to the paper's
construction, but that claim is unnecessary for a clearly labeled comparison
of the existing exact variant. No AT-Dijkstra, compression, or new search
algorithm was introduced.

## Exactness and storage checks

The five classes in `baselines.exact` carry rational segment coefficients,
domain endpoints, preimages, compositions, minima and ownership after the
existing graph input canonicalization. This is exact for those accepted
decimal inputs; it does not recover precision discarded when graph inputs
were canonicalized. The adapter reads the exact result field and serializes
rational strings plus endpoint flags. A singleton's affine representation
need not have zero slope; correctness is its value on its singleton support.

BTHP/PTHP v2 preserve exact labels and reject v1. Both core loaders check graph
identity and checksums; the partitioned pair also verifies the boundary file
digest. The adapter validates its explicit partition/domain configuration
against loaded metadata, does not overwrite existing files, hashes its exact
index pair and partition input, and reuses lazily loaded labels across queries.
An independently checksummed v1 PTHP mutation is rejected by the new adapter
test, so version rejection is distinguished from ordinary checksum rejection.

THop's semantic profile output is deterministic across repeated queries,
preparation reuse, changed ignored preference budgets, and save/reload.
Boundary-index construction already has a deterministic round-trip test.
PTHP serialization also embeds measured construction wall times and sampled
heap in construction statistics. Consequently independently rebuilt PTHP
files need not have identical bytes even when labels and answers agree.
That is an artifact-format limitation, not nondeterministic path selection;
the adapter states it explicitly. Freeze the actual produced file SHA-256
for each future campaign, rather than promising a deterministic PTHP binary
hash. Future byte-reproducible builds would require moving volatile measured
statistics outside the semantic index payload or a new canonical format.

## Evidence and limitations

The focused R4 run included all 8 partitioned, 4 boundary, 9 THop and 12 native
SICSP tests along with new adapter/CLI tests: 58 tests passed, no failures,
errors or skips. The existing `RationalTemporalReference` constructs expected
profiles independently by enumerating simple routes, using its own rational
edge interpolation, preimages, crossings and envelopes; it does not use THop
to derive expected answers. It covers every source/destination pair in the
tiny fixture, same/cross partitions, external reentry, singleton restrictions
and fractional endpoints. Exact arithmetic and all-pairs checks are retained.

Lazy byte counters measure decoded logical partition payload bytes. Cold/warm
times describe label-cache state; no physical disk-cache claim is made. The
core metadata loader reads and checks the complete serialized inner file
before lazy partition decoding, so this is lazy label materialization rather
than a promise that preparation reads only a small metadata prefix.

These tests establish tiny-instance correctness and objective-correct runner
integration. They do not establish large-network preprocessing feasibility,
the paper's incremental construction bounds, or a full-network performance
result. The global M5 readiness decision remains subject to the independently
reported numerical and provenance gates.
