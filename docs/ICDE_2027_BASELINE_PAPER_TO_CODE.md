# ICDE 2027 baseline paper-to-code map

## Scope and repository state

This note records the paper specifications and maps their components to the
existing PACE code and the shared baseline foundation. It does not claim that
either complete baseline has been implemented. The current code adds only
fixed-departure FIFO earliest-arrival search, deadline-based latest-departure
search, and exact path replay.

The two source PDFs in `Baseline papar/` were readable with `pdftotext`:

* Yuan et al., “Constrained Shortest Path Query in a Large Time-Dependent
  Graph,” PVLDB 12(10), 2019, 1058–1071, 13 pages.
* Li et al., “Time-Dependent Hop Labeling on Road Network,” ICDE 2019,
  903–914, 12 pages.

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
| Single departure fastest path / FPP | §III-A, Definitions 1–2 | `FifoTimeDependentDijkstra` supplies a scalar fixed-departure primitive. The all-departure profile is not implemented in this phase. |
| Profile composition `⊕` | §III-A-B, §IV-C | `TimeProfile.compose` already composes exact piecewise-linear arrival profiles; future THop code should adapt/reuse it for travel-time profiles with the correct `t + f(t)` entry mapping. |
| Pointwise `Min` envelope | §III-B, §IV-C | `TimeProfile.pointwiseMinimum` supplies an existing exact profile envelope where domains and continuity contracts fit. |
| Out-label / in-label | §III-B | Future THop index types must store complete hub IDs and profiles; no label index is implemented. |
| Unpruned 2-hop labels | §IV-A | Future builder uses forward and reverse profile searches; scalar baseline search is only a foundation and is not an index. |
| Pruned labels and hub order | §IV-A, §IV-D | Future builder uses an all-time profile-dominance test against earlier labels and the documented hub order. No PACE pivots/connectors are involved. |
| Label-intersection query | §IV-C | Future query intersects hub IDs, composes profiles, and takes their minimum envelope. |
| Partition and boundary labels | §V-A–E | Future partition metadata/index/query layers; existing `GraphPartitionMetadata` is PACE metadata and must not be assumed to implement THop's partition scheme without conformance checks. |
| AT-Dijkstra | §VI-A | Future optional approximate profile-search mode with the paper's multiplicative error budget. It does not add scores or a PACE budget. |
| Bottom-up compression | §VI-B Algorithm 1 | Future optional turning-point simplifier with paper error thresholds. It must be evaluated separately from exact profile composition. |

### Ambiguities to resolve before implementing THop

1. The paper cites a fastest-path profile search but does not fully specify
   the exact profile-Dijkstra relaxation and inverse operations in this paper.
   Its profile search should be matched to the cited method and repository
   `TimeProfile` domain/continuity contracts before coding.
2. “Dominates for all `t`” is used for pruning, but the paper does not define
   whether weak equality, endpoint-only equality, or domain gaps affect label
   retention. The precise domain on which each profile comparison is made
   must be fixed.
3. The barycenter-distance hub order is a heuristic; treatment of disconnected
   components, absent coordinates, and equal distances is not specified.
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

## Shared foundation delivered in this phase

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
empty profile domain is reported explicitly. No THop or SICSP candidate
generation, index, or experiment runner is added in this phase.
