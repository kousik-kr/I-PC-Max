# ICDE 2027 baseline paper-to-code map

## Scope and repository state

This note records the paper specifications and maps them to the shared
baseline foundation, the M2A exact-profile/unpruned THop implementation, and
the M2B incremental pruned THop implementation. It does not claim that SICSP,
partitioning, or the later THop approximation stages have been implemented.

The two source PDFs in `Baseline papar/` were readable with `pdftotext`:

* Yuan et al., “Constrained Shortest Path Query in a Large Time-Dependent
  Graph,” PVLDB 12(10), 2019, 1058–1071, 13 pages.
* Li et al., “Time-Dependent Hop Labeling on Road Network,” ICDE 2019,
  903–914, 12 pages.

The citation trail for the profile search also includes Li et al.'s MORT paper
and Ding et al.'s interval-starting-time shortest-path paper. The MORT paper is
available from [PVLDB](https://www.vldb.org/pvldb/vol10/p1274-zhou.pdf), and
Ding et al. from [Microsoft Research](https://www.microsoft.com/en-us/research/wp-content/uploads/2016/02/edbt08tdsp.pdf).

`docs/ICDE_2027_BASELINE_IMPLEMENTATION_PLAN.md` is absent from the worktree
and from `HEAD`; no file with that name was found under `docs/`. Therefore,
this mapping is grounded in the original papers and in the components named in
the request. Planned items specific to the missing document cannot yet be
cross-referenced.

At inspection time the branch was `main`, two commits ahead of `origin/main`.
The only pre-existing worktree change was the untracked `Baseline papar/`
directory. It was preserved. The requested branch
`feature/icde27-baseline-implementation` was created from that `main` HEAD.
The baseline `mvn test` ran 243 tests and failed the pre-existing timing
assertion `PacePublicApiOracleIntegrationTest.parallelConfigurationStartsParallelTasksAndKeepsOutputChecksumStable`
at line 173; 242 tests passed.

After the foundation changes, `mvn test` passed all 254 tests, including the
PACE differential oracle and the independent baseline path-enumeration tests.
The baseline timing assertion passed on this full rerun.

## Reusable repository components

| Need | Existing component | Baseline use |
|---|---|---|
| Directed graph and complete arc IDs | `core.graph.TDGraph`, `Edge`, `Node` | Stable outgoing/incoming adjacency and edge-ID access. |
| Graph loading | `core.loader.GeneratedGraphLoader` | Loads validated PACE road-network datasets. The baselines consume `TDGraph` and do not depend on PACE candidate generation. |
| Travel-time functions | `core.function.PiecewiseLinearFn` | Piecewise-linear travel times, exact edge-entry evaluation, domains, FIFO checks, and edge-level latest-departure inversion. `arrivalTimeAtClosure` was added for FIFO checks across domain gaps. |
| Constrained attribute | `core.function.PiecewiseConstFn`, stored on `Edge` | Edge score/toll values, independent of the time-only THop objective. |
| Temporal domain and breakpoints | `core.function.Domain` | Endpoint-aware domains and canonical temporal arithmetic. |
| Materialized path | `core.validate.Path` | Ordered, complete arc-ID sequence; reused by new baseline results. |
| Exact path replay | `core.validate.ExactPathValidator`, `pcmax.ExactPathProfileBuilder` | Existing replay validates score, budget, and/or profile semantics. New `baselines.TemporalPathReplay` provides scalar exact replay without a PACE budget or score objective. |
| Profile composition and minimum envelope | `core.profile.TimeProfile.compose`, `pointwiseMinimum`; `core.pcmax.ExactPathProfileBuilder`; new `baselines.TemporalArrivalProfiles` | Reuse exact piecewise-linear composition and envelopes. The new adapter converts edge travel times to absolute-arrival functions, composes a path, and retains its exact departure domain. For fixed departure `t`, minimizing absolute arrival is equivalent to minimizing travel time. |
| Experiment instrumentation | `experiments.framework.ExperimentInstrumentation`, `MetricsRow`, and framework result types | Can instrument future baseline runners without changing the PACE engines. |
| Algorithm registration | `experiments.AlgorithmRegistry` and runner interfaces | Future SICSP/THop experiment adapters belong here; not changed in this foundation phase. |

The existing `PointForwardLabeling` and `PointBackwardLabeling` already
perform point searches, but their result tie handling is based on a local
predecessor arc and does not define a complete-path tie order. The backward
search also accepts open domains through an edge inverse that returns the
previous representable floating-point value for an excluded endpoint. The new
baseline search therefore supplies an explicit tie contract and requires
closed domains for latest-departure queries. It reuses the graph, function,
domain, and path primitives above.

## Yuan et al. (PVLDB 2019): SICSP

### Objectives and paper algorithm

Section 2.1 defines a continuous time-dependent graph. An edge has a
piecewise-linear travel-time function and a piecewise-constant weight function.
The arrival function is `A_e(t) = t + travelTime_e(t)`. For a path, each edge's
weight is evaluated at that edge's actual entry time, and its arrival time is
composed with the preceding edge arrivals.

Section 2.2 defines two constrained shortest-path objectives:

* **PCSP:** for fixed source, destination, departure `ts`, and weight budget
  `Δ`, return a path whose total time-dependent weight is at most `Δ` and whose
  absolute arrival time is minimum.
* **ICSP:** for fixed source, destination, departure interval `[ts1, ts2]`,
  and weight budget `Δ`, choose both a departure in that interval and a path
  with total weight at most `Δ` to minimize absolute arrival time at the
  destination. PCSP is the point-departure special case. Path retrieval also
  returns the selected source departure and edge path.

Sections 3.1.1–3.1.3 define the destination profiles `Arr_d(t)` (arrival) and
`Wgh_d(t)` (total weight). `Arr_d` is piecewise linear; `Wgh_d` is
piecewise constant. The arrival profile has **O-points**, produced when a
path's composed arrival function changes slope, and **I-points**, where two
path-arrival pieces intersect. The weight profile's **W-points** originate at
edge-weight breakpoints; its pieces are horizontal.

Section 3.1.4 gives the paper's heuristic: for each edge travel-time
breakpoint, use a reverse latest-start search to find `LST` at the edge tail
and a forward earliest-arrival search to find `EAT` from the edge head. The
paper keeps the lower-right `(LST, EAT)` candidate rather than enumerating the
whole rectangle. It similarly selects candidate W-points at score
breakpoints. This prioritizes arrival time, then weight, and the paper reports
empirical quality; it is not a proof that every constrained optimum is
retained.

Algorithm 1 in Section 3.2 builds `Arr_d` in three stages:

1. For each edge and each travel-time breakpoint, run reverse LST and forward
   EAT searches, replay the corresponding concatenated path to calculate its
   weight, and record `(LST, EAT, WGH)`.
2. Group candidate points by their associated path. For a candidate `T_i`,
   compare paths from other groups at `LST_i`; the pseudocode removes it when a
   competing path has both smaller arrival and smaller weight.
3. Sort surviving O-points by LST inside each path group, connect neighboring
   points with linear pieces, and obtain intersections through the lower
   envelope.

The paper says ICSP-Wgh follows similar steps, using edge-score breakpoints
and retaining skyline W-points at a common departure time. Algorithm 2
filters the arrival and weight profiles by the departure interval and budget,
selects the least feasible arrival, and uses the inverse arrival profile to
recover a departure. The fixed-departure PCSP query then retrieves a path.

### Paper-to-code relationships

| Paper component | Paper location | Existing or new code relationship |
|---|---|---|
| Edge travel time and arrival function | §2.1 | `Edge.travelTimeFunction`; `PiecewiseLinearFn.arrivalTimeAt`. |
| Edge score/weight at entry time | §2.1 | `Edge.scoreFunction`; `PiecewiseConstFn.valueAt`. Existing `ExactPathValidator` and profile replay evaluate scores at actual edge-entry times. |
| PCSP / ICSP and path retrieval | §2.2, Definition 3, Algorithm 2 | Future SICSP query/result types. `Path` and `TemporalPathReplay` now provide exact edge-ID witness replay. |
| LST reverse search | §3.1.4, Algorithm 1 line 5 | `FifoTimeDependentDijkstra.latestDeparture`; this is the scalar reverse primitive for fixed edge-event times. |
| EAT forward search | §3.1.4, Algorithm 1 line 6 | `FifoTimeDependentDijkstra.earliestArrival`; this is the scalar forward primitive. |
| O-point candidates and path grouping | §§3.1.2, 3.1.4, 3.2 Algorithm 1 | Not implemented in this phase. It will need candidate records that retain the complete prefix-edge-suffix path and breakpoint provenance. |
| O-point dominance validation | §3.2 Algorithm 1 lines 9–18 | Not implemented. The required comparison combines a competing path's arrival and constrained weight at the same departure. |
| O-point interpolation and I-points | §§3.1.2, 3.2 Algorithm 1 lines 19–22 | Future use of exact profile breakpoints/envelopes (`TimeProfile`); not implemented in this phase. |
| W-point candidates and `Wgh_d(t)` | §3.1.3, §3.2 ICSP-Wgh discussion | Future use of `PiecewiseConstFn` and exact path score replay; not implemented. |
| Weight-budget filtering and final retrieval | §2.2, §3.2 Algorithm 2 | Future SICSP query logic. Do not feed the budget into the LST/EAT searches. |

For SICSP, LST, EAT, arrival-profile composition, and O/I-point geometry are
time-objective operations. Edge-score replay, W-points, dominance involving
the weight coordinate, budget filtering, and returned feasibility are
constrained-attribute operations. SICSP-Pref must retain the paper's LST/EAT
searches, breakpoint loop, candidate construction, and path grouping; only
operations proved to depend on its objective may change. No preference
adaptation is implemented here.

### Ambiguities to resolve before implementing SICSP

1. The paper calls the O-point method a heuristic and retains only the
   lower-right candidate. It does not establish that this set is complete for
   the exact ICSP optimum. A native implementation must match the paper's
   heuristic and label its scope accordingly; it must not be presented as an
   exact solver without separate proof.
2. Algorithm 1 uses strict comparisons (`Arr_p(LST_i) < EAT_i` and
   `W_T < WGH_i`). The prose uses “dominated” without specifying whether
   equality in one coordinate is enough. Equal-arrival/equal-weight tie
   handling and duplicate candidates are not specified.
3. The LST/EAT Dijkstra calls return paths as well as scalar values, but the
   paper does not specify tie-breaking when several paths have the same LST
   or EAT. That choice can change the replayed weight and candidate grouping.
4. Algorithm 1's presentation of the per-group scan and `break` indentation is
   ambiguous in the extracted pseudocode. The prose says one point per other
   path group can dominate a candidate, but it does not fully state how the
   scan behaves when the first nearby point does not dominate it.
5. ICSP-Wgh is described as “similar” and its pseudocode is omitted. The
   exact treatment of score-breakpoint endpoint ownership, equal-departure
   skyline ties, and connection of W-points is not fully specified.
6. Algorithm 2 uses `Arr_d^{-1}`. If `Arr_d` has a plateau or multiple
   departures attain the same minimum arrival, it does not specify which
   departure/path to return. The paper also leaves interval-endpoint and
   budget-equality conventions implicit in this reconstruction.
7. The edge-score model is piecewise constant with half-open intervals in
   §2.1, while query and function terminal endpoints are not consistently
   spelled out. The repository's score implementation is right-continuous;
   an implementation must state this convention and test all boundaries.

## Li et al. (ICDE 2019): THop

### Objective and paper algorithm

Section III-A defines the **Fastest Path Profile (FPP)** for a pair `(u,v)`:
for every departure time `t` in the time domain, return the minimum travel
time among all paths from `u` to `v`. THop is preference-free: the profile is
the travel-time objective across the full departure domain. It has no PACE
travel-time budget and does not use PACE edge scores.

For travel-time profiles `f_ab` and `f_bc`, temporal path composition is
`(f_ab ⊕ f_bc)(t) = f_ab(t) + f_bc(t + f_ab(t))`. The pointwise `Min` operator
forms the lower envelope of all candidate profiles, preserving the winning
piece on each departure subinterval.

Section IV defines out-labels `Lout(v) = {(h, f_vh)}` and in-labels
`Lin(v) = {(h, f_hv)}`. The unpruned 2-hop construction orders vertices and
performs a forward all-destinations FPP search and a backward all-sources FPP
search from each hub, adding the resulting functions as labels. The pruned
construction compares each settled search profile against the answer already
available through earlier hubs. If the existing label query pointwise
dominates the direct profile for all departures, it omits the label and does
not expand through that vertex. The paper orders vertices heuristically by
distance from the network barycenter.

Section IV-C answers a query by intersecting source out-label and destination
in-label hub IDs, composing the two profiles for each common hub, then taking
their pointwise minimum. It describes minimum/maximum profile values as safe
pruning bounds and suggests processing promising hubs first. Section IV-D
proves cover correctness; Section IV-E gives complexity.

Section V partitions the graph into vertex-disjoint subgraphs. Endpoints of
cross-partition edges are boundary vertices. It builds a boundary THop over
boundary vertices and an inner THop per partition. A same-partition query uses
the inner labels. A cross-partition query combines source-to-local-boundary,
boundary-to-boundary, and local-boundary-to-destination profiles. Boundary
labels are generated in parallel and pruned in a fixed order; inner searches
on a partition can use the original graph and the boundary labels to represent
paths that leave and re-enter that partition.

Section VI adds approximate profile construction and compression. AT-Dijkstra
(§VI-A) assigns per-search pruning budgets so the accumulated approximation
factor along a path is bounded by the requested global factor. Bottom-Up
Compression (§VI-B, Algorithm 1) removes turning points whose replacement by
a straight segment has error below `ε`; the paper states `2ε` and `6ε` bounds
for its inner and cross-partition query constructions. These are THop profile
approximations, not changes to its objective.

### Paper-to-code relationships

| Paper component | Paper location | Existing or new code relationship |
|---|---|---|
| Single departure fastest path / FPP | §III-A, Definitions 1–2 | `FifoTimeDependentDijkstra` is the scalar primitive; `FifoFastestPathProfileSearch` computes all-destination profiles over continuous time with function-valued relaxation when edge supports match. |
| Profile composition `⊕` | §III-A-B, §IV-C | `ExactTemporalProfile.composeAfter` composes absolute-arrival mappings; converting the result to travel time subtracts the root departure, equivalent to the paper's `t + f(t)` entry-time formula. |
| Pointwise `Min` envelope | §III-B, §IV-C | `ExactTemporalProfile.pointwiseMinimum` computes exact affine crossings and endpoint ownership, including jumps at support boundaries that one `TimeProfile` cannot represent. |
| Out-label / in-label | §III-B | `THopIndex` stores `Lout(v)` by hub `h` as `F(v,h)` and `Lin(v)` by hub `h` as `F(h,v)`. |
| Unpruned 2-hop labels | §IV-A | `THopIndex.build` remains the all-pairs differential reference, running one forward all-destination profile search per source. |
| Pruned labels and hub order | §IV-A, §IV-D | `THopIndex.buildPruned` performs incremental forward and reverse-backward searches in an explicit distance-to-barycenter order. It checks exact full-domain dominance against labels of earlier hubs before expanding each settled vertex. |
| Label-intersection query | §IV-C | `THopIndex.query` intersects hub IDs, composes each source-to-hub and hub-to-destination arrival profile, restricts to the query interval, and returns the minimum travel-time envelope. |
| Partition and boundary labels | §§V-A, V-B, V-D | `baselines.GraphPartitionMetadata` now records caller-supplied vertex partitions and every directed internal/crossing arc. `baselines.BoundaryTHopIndex` performs the offline two-phase boundary build, stores exact source profiles in temporary disk shards, prunes by earlier boundary hubs, and provides versioned `write(Path)`/`load(...)` APIs for the finalized boundary index. Section V-C inner indexes and Section V-E arbitrary-vertex queries remain future work. |
| AT-Dijkstra | §VI-A | Future optional approximate profile-search mode with the paper's multiplicative error budget. It does not add scores or a PACE budget. |
| Bottom-up compression | §VI-B Algorithm 1 | Future optional turning-point simplifier with paper error thresholds. It must be evaluated separately from exact profile composition. |

### Ambiguities to resolve before implementing THop

1. The THop paper's reference [23] is Li et al.'s MORT paper. That paper's
   related-work discussion points to Ding et al. [7] for interval-starting-time
   shortest paths, but that method is described around selecting a best
   departure and does not specify THop's all-destinations, complete-profile
   API or partial edge-support semantics. M2A uses whole-function relaxation
   under a shared edge-function support domain and an exact simple-route
   fallback for direct searches with different support domains. The unpruned
   THop index requires shared edge support so a faster prefix cannot be
   unavailable to a downstream edge while a slower prefix remains feasible.
2. The paper does not spell out profile-domain ownership for “dominates for
   all `t`.” M2B uses weak pointwise `<=` over the candidate's entire support;
   the existing profile must also be defined at every such departure. Open
   slabs are compared at both affine limits and owned singleton points are
   compared separately. A profile unavailable at a candidate departure never
   dominates it.
3. The paper gives no coordinate metric or tie rule for its barycenter
   heuristic. M2B uses Euclidean distance to the arithmetic mean of node
   coordinates, then ascending node ID. The graph model has no explicit
   “coordinates missing” marker; when all coordinates coincide, ascending node
   ID is the deterministic fallback. Disconnected vertices use the same
   global barycenter.
4. Partitioning permits an existing partitioner and the reported experiment
   uses Natural Cut. Reproducing reported construction performance requires
   the same partition inputs and configuration; correctness does not depend
   on one unique partitioner.
5. The boundary-THop and inner-THop descriptions rely on label caches and
   reading lists without giving a complete serialized schema. The cited
   pseudocode/prose leaves storage details and some duplicate boundary-label
   handling to an implementation.
6. AT-Dijkstra describes a multiplicative error budget over path vertices,
   while the input/output and use of sampled searches are partly heuristic.
   The exact budget allocation, rounding, and finite-domain endpoint policy
   need to be pinned down for a reproducible variant.
7. Bottom-up compression defines error over indexed turning points and states
   aggregate query bounds, but the paper does not fully state how continuous
   sup-norm error, ties between equal-error removals, and half-open domains are
   handled. The tie order and error norm must be made explicit.

## Shared foundation delivered in M1

`edu.ipcmax.baselines.FifoTimeDependentDijkstra` performs FIFO earliest-arrival
and reverse latest-departure searches over `TDGraph`. It evaluates each edge
at its actual entry time, validates FIFO across each function's defined
domain, and uses stable ties: best time, fewer edges, lexicographically
smaller complete arc-ID sequence, then node ID. Undefined edge-entry times
are unavailable transitions. Reverse search uses the existing exact
piecewise-linear inverse and requires closed edge-function domains, avoiding
an approximate predecessor time for open endpoints.

`edu.ipcmax.baselines.TemporalPathReplay` replays ordered arc IDs without
waiting, budgets, or scores. It rejects malformed paths and edge entries
outside function domains. The existing `PiecewiseLinearFn` and
`TimeProfile` remain the function/envelope implementations.
`edu.ipcmax.baselines.TemporalArrivalProfiles` adapts edge travel-time
functions to arrival-time profiles and exactly composes complete paths; an
empty profile domain is reported explicitly.

## M2A implementation delivered

`FifoFastestPathProfileSearch` computes full functions over a continuous root
departure domain. For the common road-network case where every edge function
uses the same temporal support domain, it keeps one profile label per node,
composes that label with each outgoing edge arrival mapping, and enqueues a
node whenever the exact lower envelope changes. In a graph with differing
edge support domains, it uses finite vertex-simple route labels so that a
fastest prefix cannot hide a slower, supported prefix. Both branches operate
on whole piecewise-linear functions. No departure sampling, arbitrary
breakpoint limit, score, or PACE budget is used. The differing-support branch
has an exponential worst case; it is intended for exact small-graph use.

`ExactTemporalProfile` stores the underlying piecewise-linear mapping as
disjoint open-interval cells and singleton endpoint cells. This preserves
exact endpoint ownership and allows a route envelope to jump at a support
boundary. Within a cell it delegates affine composition and breakpoint
handling to the existing `TimeProfile`. Search and index labels store absolute
arrival time `A(t)`; the reported fastest travel-time function is `A(t)-t`.
Since all candidates share the same root departure `t`, minimizing arrival
time and minimizing travel time have the same lower envelope.

`THopIndex.build` runs a forward all-destinations profile search from every
vertex as an unpruned differential reference. For every reachable ordered pair
`(u,v)`, it stores the same immutable profile as `(v,F(u,v))` in `Lout(u)` and
`(u,F(u,v))` in `Lin(v)`. `THopIndex.buildPruned` retains the same reference
builder unchanged and adds the M2B incremental construction. `THopIndex.query`
intersects the directed label maps, composes each source-to-hub arrival
mapping with its hub-to-destination mapping, restricts the resulting
functions to the complete query interval, and takes their exact lower
envelope. Empty support represents unreachable departures; each processed hub
stores a diagonal identity label so source-to-self remains queryable.

The index rejects graphs whose edge functions have different support domains.
That restriction is needed for the paper's single fastest-profile label at a
hub to remain composable: with heterogeneous availability windows, a slower
prefix may be the only prefix whose arrival lies in a downstream edge's
domain. The original THop paper does not define labels for that case. Direct
profile search still handles it exactly, but the paper's one-profile-per-hub
query theorem does not cover it. No route witness is returned by the profile
API; route pieces are not inferred from merged functions.

The M2B reverse-backward search traverses original incoming arcs. For an edge
`u->v`, it composes the edge arrival map with the already-computed `v->h`
suffix, yielding the `u->h` profile. It does not reverse the edge and reuse its
travel-time function unchanged. Because a final arc can arrive after the edge
departure horizon while the query result remains defined at that departure,
the backward root identity is supported over the min-to-max image of all
one-edge arrival profiles. Subsequent reverse relaxations restrict incoming
edge departures to their actual function support.

The independent test oracle in `SimplePathProfileOracle` recursively
enumerates vertex-simple routes, composes each route's edge functions, and
builds their envelope. Tests compare the functional search and THop query
against this oracle for every source-destination pair over the union of
breakpoints and open temporal cells. The test suite also covers switching
profiles, equal routes, multiple common hubs, support gaps and ownership,
FIFO plateaus, self paths, disconnected nodes, score changes, and different
`QuerySpec` travel-time budgets. THop's query API accepts only source,
destination, and temporal interval.

Index statistics report construction time, both directed label counts,
logical temporal-profile piece and breakpoint totals, a payload-based byte
estimate, search state and temporal operator counts, and sampled peak JVM heap
usage. Query statistics report duration, common hubs, temporal compositions,
envelope operations, result pieces, and sampled heap usage. The byte figure
is an estimate, and sampled heap is not a JVM allocation profiler.

M2A validation ran `mvn -q -Dtest=THopIndexTest test` successfully and the
final `mvn test -q` run passed all 259 tests. Two earlier full-suite attempts
hit the known intermittent PACE overlap assertion at
`PacePublicApiOracleIntegrationTest.java:173`; the isolated assertion passed,
and the final full run passed. No PACE or iSCOPE source files were changed.
No large-network benchmark was run. The tiny-graph construction/query metrics
are available from `THopIndex.IndexStatistics` and
`THopIndex.QueryStatistics`; tests verify that their timing, label, piece,
common-hub, composition, envelope, size-estimate, and sampled-heap fields are
populated.

M2A does not add pruning, partitioning, approximation, compression, SICSP, or
large-network experiments.

## M2B implementation delivered

The default `THopIndex.buildPruned` order sorts vertices by Euclidean distance
to the arithmetic mean of the stored coordinates, breaking ties by node ID.
If every coordinate is identical, it uses ascending node ID. The selected
order is exposed by `hubOrder()`; callers can pass another complete permutation
to the explicit-order overload for reproducible order comparisons.

For each hub, the pruned builder executes one forward and one reverse-backward
function-valued search. At each settled vertex it composes only labels whose
hubs were processed before the current hub and checks whether their exact
lower envelope dominates the complete new profile. A dominated label is
removed and its frontier is not expanded. Partially dominated profiles remain
available as labels and are expanded. The test suite checks full, partial, and
crossing dominance, endpoint ownership, singleton cells, support gaps, and
FIFO plateaus. `ExactTemporalProfile.dominates` uses only piecewise-linear slab
endpoints and owned points, not departure-time sampling.

The current temporal-function API supports this reverse operation by exact
composition over the original incoming arcs. The primary THop index still
requires a common edge-function support domain, as in M2A. The paper does not
specify heterogeneous edge support; that remains outside the claimed THop
reproduction.

`PreprocessingStatistics` reports construction time, processed hubs, forward
and backward search calls and expansions, constructed profiles, retained and
pruned labels, skipped expansions, temporal pieces and operators, a payload
size estimate, and sampled peak heap use. Query-level `QueryStatistics` stays
separate and reports common hubs, compositions, envelope operations, query
time, result pieces, and sampled heap use. The index-size value is a documented
payload estimate rather than measured JVM object size; heap use is sampled,
not allocation-profiler output. The small-graph tests compare pruned and
unpruned outputs against `SimplePathProfileOracle` in more than one hub order.

The implementation-plan document was reconciled without changing local
history: `docs/PACE_FINAL_ALGORITHM_IMPLEMENTATION_PLAN.md` already exists in
the worktree and its Git blob matches GitHub `main` at
`840cbc699b3a391f6129e7b25fee5fb6dd3de72f`. No reset, merge, or copy was
needed.
