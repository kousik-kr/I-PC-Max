# THop M2C-B Partitioned implementation report

## Status

The M2C-B implementation is present, but this milestone is **not complete**.
The full-pairs differential test finds a one-tick temporal-profile mismatch on
the fractional-breakpoint fixture. The same mismatch is also present in the
existing full-graph unpruned THop result. A previous support-boundary case also
showed that the current `TimeProfile` composition can evaluate one tick beyond
an outer profile's closure. Fixes attempted in the shared profile arithmetic
caused regressions in PACE and SICSP, so those shared changes were reverted.
The existing algorithm behavior is unchanged.

## Paper-to-code audit

The original ICDE 2019 PDF was read from `Baseline papar/`, focusing on
Sections V-C through V-F. The implemented construction follows the V-C
boundary-shortcut approach:

1. For partition `Gi`, retain its original directed internal arcs.
2. For every ordered pair of local boundary vertices `(b,c)`, retrieve the
   full-graph Boundary-THop travel-time profile and add each supported profile
   cell as a directed temporal shortcut arc.
3. Run the existing functional FIFO profile search from each local source on
   this augmented local graph. No point-departure searches or time sampling are
   used. These profiles account for routes that leave and re-enter the
   partition because each shortcut is a full-graph boundary profile.
4. Process local hubs in ascending vertex ID order. Admit directed in/out
   labels incrementally and prune only when the envelope from earlier hubs
   dominates the candidate on its complete support.

Reverse labels are built from directed searches on the original augmented
graph. No time-dependent edge is reversed with its travel-time function
unchanged. The search-first, prune-second implementation materializes all
local-source profiles before label admission; it does not build all-pairs
profiles on the complete graph and filter them.

Queries in one partition use its augmented inner THop index. Cross-partition
queries exhaustively compose source-inner, boundary, and destination-inner
arrival profiles for each boundary pair and take the pointwise minimum. The
query derives a boundary hub cache from the stored in/out hub maps and builds a
reading list of pairs with at least one common boundary hub; pairs with no
common hub are safely skipped. The optional safe-pruning query also skips a
combination only when the accumulated result dominates the candidate over its
full support. The paper's minimum-value ordering and partial-domain pruning
details are not sufficiently specified for a validated exact optimization, so
the exhaustive combination query remains primary after reading-list filtering.

## Storage and metrics

The `PTHP` format is versioned and checksummed. It stores graph identity,
partition metadata, the Boundary-THop file digest/reference, temporal support,
construction identifiers, per-partition directed labels and profile cells,
and construction statistics. Partition label blocks are loaded lazily and
validated independently. Boundary and inner index byte counts are reported
separately. Missing, corrupt, or mismatched indexes fail explicitly.

Construction statistics include partition sizes, local/crossing arc counts,
boundary shortcuts, functional-search calls, generated/retained/pruned labels,
profile pieces and operators, per-partition wall time, sampled heap, and
boundary/inner disk bytes. Query statistics include partition IDs, query
type, inner and boundary label work, boundary combinations examined/skipped,
profile compositions/envelopes, support-boundary rejections, lazy bytes and
load time, total query time, and output profile pieces. These counters are
instrumented, but no performance claim is made from the tiny fixtures.

One instrumentation run on the 10-vertex, 15-arc, 5-partition correctness
fixture (9 boundary vertices, 6 crossing arcs) reported 54 local boundary
shortcut arcs. Boundary preprocessing used 9 source searches and generated 45
boundary profiles; 32 profiles and 119 profile pieces were retained, while 40
profiles were pruned. The boundary index occupied 6,720 bytes, with 5,558
temporary phase-1 bytes written. Its measured construction wall time was 212
ms; phase-1 wall time was 106 ms, pruning took 48 ms, and sampled peak used
heap was 57.5 MB. These are one-run measurements.

Inner preprocessing took 64 ms and occupied 7,641 bytes. It made 10 local
functional searches, generated 22 profiles, retained 37 directed labels,
pruned 7 labels, and retained 119 profile pieces. It recorded 87 temporal
compositions, 56 envelope operations, and 74.2 MB sampled peak used heap.
Loading the index took 6.7 ms. A cold cross-partition query `(1,6,[0,20])`
examined 4 boundary combinations, made 19 profile compositions and 6 envelope
operations, read 4,228 lazy-index bytes, and took 15.2 ms. The warm query read
zero bytes and took 10.3 ms. These measurements describe this tiny fixture
only.

## Correctness coverage and known limitation

`PartitionedTHopIndexTest` compares complete departure profiles against the
independent simple-path oracle, unpruned THop, and pruned THop. It also covers
same-partition queries, one partition without boundaries, isolated vertices,
save/reload, lazy loading, safe query pruning, and an external route that
leaves and re-enters a partition. The counterexample compares the induced
partition route of cost 6 with the full-graph route of cost 3 at departure 20.

The complete-pairs test currently rejects `4 -> 1`: one boundary combination
hits a profile-support endpoint at `21.000000000001`, outside the stored
closure. The query throws `UnsupportedTemporalCompositionException` and
reports the rejected-combination count rather than returning an incomplete
profile. While investigating this support failure, allowing the combination to
be skipped produced a one-tick difference at departure `20.666666666667`:
the oracle gave `9.333333333334`, while the partitioned result gave
`9.333333333333`. The unpruned full-graph THop result also differs from the
oracle at that departure. Fractional profile intersections are canonicalized
to a fixed 12-decimal grid, so the shared profile arithmetic cannot currently
represent every exact composition. Changes to that shared arithmetic caused
existing PACE and SICSP tests to fail and were reverted. Exact M2C-B
correctness remains blocked on profile arithmetic that can preserve these
supports and crossings without changing existing algorithms.

## Validation record

| Command | Result |
|---|---|
| `mvn -q -Dtest=THopIndexTest,BoundaryTHopIndexTest,SicspNativeEngineTest,SicspPreferenceEngineTest,PaceExactOracleDifferentialTest test` | Passed. |
| `mvn -q -Dtest=PartitionedTHopIndexTest test` | 6 tests passed; the full-pairs differential received an explicit unsupported-composition error. |
| `mvn -q test` | 299 tests: 298 passed, 0 assertion failures, 1 explicit unsupported-composition error, 0 skipped. |
| `git diff --check` | Passed. |

No NY, FLA, CAL, or other large-network campaign was run. No PACE, SICSP,
SICSP-Pref, or existing THop algorithm behavior was changed. The M2C-B
correctness blocker must be resolved before this branch is treated as a
completed milestone or pushed as a finished implementation.
