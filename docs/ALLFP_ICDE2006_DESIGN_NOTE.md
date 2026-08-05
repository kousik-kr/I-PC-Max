# allFP continuous functional-search design note

## Source and scope

The implementation follows the complete author PDF of Evangelos Kanoulas, Yang Du,
Tian Xia, and Donghui Zhang, “Finding Fastest Paths on a Road Network with Speed
Patterns,” ICDE 2006, DOI `10.1109/ICDE.2006.71` ([author PDF](https://www.khoury.northeastern.edu/home/ekanou/research/papers/mypapers/23.pdf)).
The paper's Time-Interval All Fastest Paths operation is exposed as algorithm ID
`allfp` and paper label **allFP**.

## Paper-to-repository mapping

| ICDE concept | Repository representation/operation |
|---|---|
| edge speed pattern and time-dependent traversal | FIFO `Edge.travelTimeFunction()` (`PiecewiseLinearFn`) |
| edge arrival function | `t + tau_e(t)`, materialized once per touched directed arc and query by `AllFpAlgorithm.edgeArrival` |
| path travel/arrival function | exact `TimeProfile`; extension uses cancellation-aware `prefix.composeOrNull(edgeArrival)` |
| functional search label | `AllFpAlgorithm.FunctionalLabel(node, parent, incomingArc, persistentTrace, arrival, priority, ordinal)` |
| functional A* order | minimum path travel time over the active domain plus a destination lower bound |
| lower border | pointwise minimum arrival profile of completed destination labels |
| interval answer | `FastestEnvelopeExtractor`, with exact crossings and endpoint ownership |
| path reconstruction | stable directed-arc sequence in `PathPointer`/`CandidateProfile` |

For a prefix `P` and edge `e`, the implementation computes the exact composition

`A_(P+e)(t) = A_e(A_P(t))`,

equivalently `T_(P+e)(t) = T_P(t) + tau_e(t + T_P(t))`. It first restricts the
root domain to entries supported by the next edge. No temporal function is wrapped,
clamped, extrapolated, or sampled.

## Search order, lower bound, and proof stop

The paper obtains an admissible functional estimate from maximum speeds and its
road-network representation hierarchy. This repository uses a safe graph-native
adaptation: `DenseDijkstraLowerBoundOracle` reads every canonical directed edge's
minimum travel time and runs reverse Dijkstra from the destination. It does not
construct a second graph. For a label ending at `v`,

`priority = min_t T_P(t) + d_min(v,d)`.

This is an admissible lower bound for every continuation because every realized
edge traversal is at least its edge minimum. It differs from the paper's geometric
and representation-bound construction, but preserves the proof obligation.
The minimum-edge array and graph-wide common temporal-support end are immutable
dataset state constructed by `AllFpAlgorithm.prepare` and charged to shared
preprocessing. Each measured fastest-profile search performs its own
destination-specific reverse Dijkstra inside the five-second query boundary;
only the four rho projections of that identical search reuse it. Exact Dijkstra
remains the sparse-ID fixture fallback. This preserves the common T03 timing
boundary rather than introducing a workload-specific offline destination index.

Completed destination labels update a continuous pointwise-minimum lower border
`U(t)`. Once `U` covers the full query interval, search is certified when the
smallest queued functional lower bound is at least `max_t U(t)`. Queue exhaustion
is the second exact stopping condition. A deadline, label cap, or expansion cap
never produces an exactness claim.

The search retains functional alternatives and never applies unsafe scalar
dominance. It prunes only when an existing prefix covers the candidate's domain,
is pointwise no later, and its visited-vertex set is a subset of the candidate's;
under FIFO this preserves every vertex-simple continuation available to the
candidate. Two crossing arrival functions remain incomparable. Labels use
persistent parent/path traces and are vertex-simple to match the repository's
loopless output contract.

## Objective separation and deterministic boundaries

allFP search, ordering, stopping, and envelope selection do not inspect preference
score or the PC-Max budget. Terminal envelope construction is arrival-only. After
the selected fastest paths are known, `scoreSelectedPaths` composes score only for
those distinct paths from the already retained parent-arrival profiles; it does not
replay travel functions. `FastestEnvelopeExtractor` still selects solely by
arrival time, then edge count, then numeric lexicographic directed-arc ID sequence.

For T03, five rho values attached to the same dataset/source/destination/window/
trial are five logical reporting rows but only one measured fastest-profile search.
`PaceBench` copies that measured execution and recomputes only
`posthoc_budget_feasible_coverage_fraction`. The planned 45,000 logical allFP rows
therefore execute 9,000 searches and 36,000 budget projections. A resume that
starts after the first rho block safely chooses the first unfinished rho as the new
source; no completed output is rewritten. Projection compute cost is recorded
separately as `allfp_budget_projection`; the logical query runtime is explicitly
marked as inherited from its measured source search.

With `threads > 1`, outgoing directed-arc compositions are submitted concurrently
and reduced in stable arc-ID order. `requested_workers`, `observed_workers`,
`parallel_functional_tasks`, `functional_composition`, `posthoc_scoring`, and
the allFP reuse counters make this behavior auditable.

All travel-function breakpoints, pairwise equality roots, open/closed endpoints,
and the terminal query horizon are retained. Incremental envelope commits are
cancellation-aware: an interrupted update is discarded and the previously complete
envelope is returned with `TIME_CAPPED_NOT_CERTIFIED`. `CERTIFIED_COMPLETE` requires
both a normal proof stop and complete interval coverage.

## Known adaptation limits

The implementation does not reproduce the paper's external spatial representation
hierarchy; reverse minimum-edge Dijkstra supplies the admissible bound instead. It
also uses the repository's canonical decimal-time model rather than symbolic
rational arithmetic. These are explicit representation adaptations, not a sampled
RPQ substitution.
