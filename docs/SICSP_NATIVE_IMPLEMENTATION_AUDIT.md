# Native SICSP implementation audit

## Scope

This milestone implements Yuan et al.'s original sequential SICSP for the
interval constrained shortest path (ICSP) problem. It does not implement
SICSP-Pref. The native objective is minimum destination arrival subject to
the total time-dependent edge weight being at most `Delta`.

The implementation is under
`src/main/java/edu/ipcmax/baselines/sicsp/`. It reuses `TDGraph` and
`PiecewiseLinearFn` for directed edge topology and FIFO travel-time functions.
Native edge weights use separate double-valued step functions; PACE edge
scores and travel-time budgets are not used.

## Source-to-code map

Paper page numbers below are the printed PVLDB page numbers.

| Source | Paper rule | Implementation |
|---|---|---|
| Section 2.1, pp. 1060-1061 | Edge travel time is FIFO and piecewise linear; edge weight is piecewise constant and evaluated at the edge's actual departure/entry time. | `SicspEdgeWeightFn`, `SicspNativeGraph`, `SicspRouteReplay`, and `SicspRouteProfile`. The route profile pulls each edge weight back through that route's actual edge-entry arrival profile. |
| Definition 2, p. 1061 | Query departure is in `[t1,t2]`; minimize arrival subject to accumulated weight `<= Delta`. | `SicspNativeQuery` and `SicspArrivalFunction.selectBestFeasible`. The bound is an accumulated weight bound, independent of PACE's travel-time budget. |
| Section 3.1.1, p. 1062 | For an edge event, use an LST prefix to its source, the edge event, and an EAT suffix from its target. | `SicspNativeEngine.generateBreakpointCandidate` and `SicspTemporalDijkstra`. The candidate stores the event, source departure, prefix, complete arc-ID path, EAT, replayed arrival and accumulated weight. |
| Algorithm 1, lines 3-8, p. 1063 | For each travel-time breakpoint, reverse-Dijkstra LST, forward-Dijkstra EAT, compute weight, add an O-point candidate. | `SicspNativeEngine.query` enumerates travel-time breakpoints; `generateBreakpointCandidate` does the two searches and independent replay. |
| Algorithm 1, lines 9-18, p. 1063 | Group points by complete path; compare a candidate against other path groups at the same departure; remove it only under the stated dominance test. | `filterArrivalCandidates`, `SicspDominance.strictlyDominates`, and `SicspArrivalCandidates`. Arc-ID path equality defines a group. Candidate points are sorted deterministically by departure and event. |
| Algorithm 1, lines 19-22, p. 1063; Section 3.1.2, p. 1062 | Sort retained O-points on a path by departure, connect consecutive points linearly, and derive I-points from crossings between path pieces. | `SicspArrivalFunction` exposes O-point path functions, linear pieces, I-points, and the O-point lower envelope. It also retains the exact composed arrival function for each candidate path. |
| Section 3.1.3, p. 1062 | Weight breakpoints yield W-point candidates; retain skyline points at equal departure and connect equal-weight W-points to represent constant pieces. | Weight events use the analogous shortest-path candidate construction. `filterWeightCandidates` applies a conventional skyline test at equal departure; `SicspWeightCandidates.constructWeightPieces` connects equal-weight W-points on the same path. |
| Section 3.2, p. 1064 | ICSP_Wgh is analogous to ICSP_Arr; its detailed pseudocode is omitted. | The reconstruction is recorded in the inference ledger below. Exact route weight profiles are independently composed from actual edge-entry times and remain linked to the route. |
| Algorithm 2, p. 1063 | Use feasible weight regions to select the earliest destination arrival and return the corresponding departure. | `SicspArrivalFunction.selectBestFeasible` intersects each retained route's own `weight <= Delta` domain with the query interval and that same route's arrival support. Selection is separate from interval-wide profile construction. |

The independent tiny-graph oracle is in
`src/test/java/edu/ipcmax/baselines/sicsp/SicspSmallGraphOracle.java`. It
enumerates vertex-simple paths, replays edge entries and weights itself, finds
all route event departures, and compares the native result with both the
retained-path optimum and the global simple-path optimum.

## ICSP_Arr interpretation

For each edge `e=(u,v)` and each travel-time breakpoint `tau` in its valid
domain:

1. Reverse time-dependent Dijkstra computes `LST`, the latest source departure
   that reaches `u` no later than `tau`.
2. Forward time-dependent Dijkstra starts at `v` at the actual edge arrival
   `tau + f_e(tau)` and returns EAT and its suffix witness.
3. The complete witness is `Prefix(s,u) + e + Suffix(v,d)`.
4. `SicspRouteReplay` independently replays that complete arc-ID path without
   waiting, evaluates every weight at its actual edge-entry time, and checks
   the intended EAT.
5. The point is an O-point only when the prefix reaches `u` exactly at `tau`,
   the full replay reaches the Dijkstra EAT, and its departure belongs to the
   query interval. A mismatch remains in the event diagnostics and is not
   repaired by inserting a wait.
6. Retained O-points are grouped by complete arc-ID path, sorted by source
   departure, and interpolated. Pairwise crossings produce I-points.

Algorithm 1 line 16 uses the strict test
`otherArrival(LST_i) < EAT_i && otherWeight(LST_i) < WGH_i`. The implementation
keeps both inequalities strict. Equality in either component is not enough to
remove an O-point.

An event-consistent route whose LST lies outside `[t1,t2]` is recorded as an
out-of-query event diagnostic, then its exact route profile is restricted to
the requested interval. This lets the interval query use a route whose event
breakpoint falls outside the requested departure range. The event itself is
not inserted into the in-range O-point set.

## ICSP_Wgh reconstruction

The paper omits ICSP_Wgh pseudocode. The implementation follows the Section
3.1.3 analogy conservatively:

1. Enumerate each edge-weight piece boundary that intersects the travel-time
   domain.
2. Use reverse latest-departure Dijkstra to reach the event edge's source by
   that boundary.
3. Run forward earliest-arrival Dijkstra from the event edge's target at the
   edge's event arrival.
4. Concatenate the two witnesses with the event edge and replay the complete
   path. Compute accumulated weight by evaluating every edge's weight at its
   actual entry time.
5. Record the source departure, arrival, accumulated weight, path and event
   provenance. Reject inconsistent event concatenations from W-point output
   and preserve their diagnostics.
6. At a common source departure, remove a W-point only when another point is
   no worse in both arrival and weight and strictly better in at least one.
   This is the conventional Pareto skyline interpretation of the paper's
   prose-only “skyline point” test.
7. Connect retained W-points with equal accumulated weight only within the
   same complete path group. Keep the exact replay-composed route weight
   function as the query profile.

The reconstructed W-point pieces and exact route weight functions are both
exposed. The exact route function is a piecewise-constant pullback of native
edge weights through the actual route arrival profile, so it does not pair one
path's arrival profile with a different path's weight profile.

## ArrTime interpretation

For each retained candidate route, the implementation constructs a full
interval arrival profile and accumulated-weight profile from the same path.
For that path, it obtains all departure regions where accumulated weight is
`<= Delta`, intersects them with the query interval, and considers the
interval endpoints plus all arrival and weight breakpoints. FIFO arrival
profiles are nondecreasing, so a region's earliest feasible departure gives
its minimum arrival. Breakpoint checks preserve endpoint ownership and allow
the tie rule to choose lower accumulated weight on an arrival plateau.

The deterministic tie order is: lower arrival, lower accumulated weight,
earlier departure, then lexicographically smaller arc-ID sequence. The paper
does not specify ties or inversion of a non-strict/plateau arrival function.

The paper's O-point interpolants and their lower envelope are exposed
separately from the exact route-profile lower envelope used by ArrTime. The
exact route profile is composed from all edge functions on each retained
candidate path; it fills intervals not covered by in-range O-points and lets
the final query keep arrival and weight functions linked to one actual route.
The implementation reports any sampled difference between an O-point
interpolant and the exact composed path profile. This is a documented
implementation choice and can affect the heuristic's result where the paper's
candidate points do not fully determine a route function.

## Inference and ambiguity ledger

Every inferred step includes its source, mathematical choice, reason, and
validation.

| Inference or ambiguity | Paper location | Interpretation and reason | Validation |
|---|---|---|---|
| “Latest start” can arrive before the event edge time. | Section 3.1.1, p. 1062; Algorithm 1, lines 5-6, p. 1063 | Treat `LST` as a deadline-constrained reverse search, then replay the concatenation without waiting. If prefix arrival differs from `tau`, retain a diagnostic only; the source prohibits silently adding a wait. | `inconsistentLatestPrefixIsRecordedWithoutInsertingWait`. |
| Section 3.1.2 describes enumerating prefix/suffix path sets, while Algorithm 1 calls one reverse and one forward Dijkstra per event. | Section 3.1.2, p. 1062; Algorithm 1, p. 1063 | Follow Algorithm 1 and retain its single shortest-path witness for each event. Enumerating path sets would be a materially different, potentially exponential method. | Competing-route and missed-path oracle tests. |
| ICSP_Wgh event node and candidate details are not fully specified. | Section 3.1.3, p. 1062; Section 3.2, p. 1064 | Reuse the Algorithm 1 `u` LST, edge event and `v` EAT construction for every weight boundary; use actual no-wait replay for weight. | Weight-breakpoint, Figure 1 and independent-oracle tests. |
| W-point “skyline” inequalities and equal points are not written. | Section 3.1.3, p. 1062 | Use weak non-worse comparisons in both components and require one strict improvement. Equal metric points remain tied; exact duplicate event candidates are deduplicated. | `dominanceUsesExactStrictInequalitiesFromAlgorithmOne`; W-point competing-route fixture. |
| “Connect W-points with the same WGH” lacks endpoint ownership details. | Section 3.1.3, p. 1062 | Build horizontal pieces between equal-weight points from the same complete path. Maintain a separate exact step function for every candidate route. | Weight breakpoint, equal-weight and Figure 1 tests. |
| Algorithm 2 line 3 says weight `< Delta`, while Definition 2 and path retrieval use `<= Delta`. | Definition 2, p. 1061; Algorithm 2, p. 1063 | Follow the problem definition and the requested native objective: equality is feasible. | `nativeWeightBoundIsIndependentAndEqualityIsFeasible`. |
| Algorithm 2's TL/WL construction and `Arr^{-1}` do not explain ties, plateaus or the association between path functions. | Algorithm 2, p. 1063 | Evaluate each retained path's linked arrival and weight functions directly. Handle plateaus and ties by explicit breakpoint evaluation and the deterministic rule above. | Plateau, equal-arrival/equal-weight, and native-versus-oracle tests. |
| O-points can provide only partial route coverage after inconsistent or out-of-range events are filtered. | Sections 3.1.1-3.1.2, pp. 1062-1063 | Preserve the literal O-point interpolant and report its support; compose the exact route profile for ArrTime and compare the two where both are defined. | Figure 1, endpoint, and profile discrepancy checks. |
| Paper uses continuous time; repository functions canonicalize time values. | Section 2.1, p. 1060; `Domain` numerical contract | Use repository 12-decimal time canonicalization consistently for event roots and endpoints. This does not claim arbitrary-rational arithmetic. | Endpoint, crossing and independent profile tests. |
| Reverse LST requires an attained latest departure. | Algorithm 1, p. 1063 | Require closed travel-time domains for native reverse searches. Open-domain supremum handling is not specified by SICSP; reject it rather than approximate an unattained time. | Engine constructor validation and existing domain tests for the shared model. |

## Assumptions, deviations and unresolved correctness issues

- Edge weights are finite nonnegative doubles and must cover every edge
  travel-time support point. The paper does not state a numeric encoding.
- Travel-time functions must be FIFO and have closed domains for reverse LST
  searches. Travel times and weights use the repository's 12-decimal
  canonicalization.
- The search is label-setting Dijkstra with one deterministic label per node.
  It does not explicitly reject repeated vertices. Concatenated prefix/event/
  suffix witnesses that revisit a node are preserved and replayed. This avoids
  silently imposing the oracle's vertex-simple rule on native SICSP.
- The sequential paper method is heuristic. The oracle test intentionally
  finds a feasible simple path made of a non-shortest prefix and a
  non-shortest suffix that is absent from the event-generated retained set.
  In that fixture the native result is unreachable while the global oracle
  finds the path. No global exactness claim is made.
- The Figure 1 route profile has one one-tick (`1e-12`) profile discrepancy at
  source departure `6.666666666667`: independent replay returns arrival
  `60.000000000001`, while the composed route profile returns `60.0`. The
  accumulated weight agrees. This canonicalized breakpoint-root issue remains
  unresolved; the Figure 1 query's selected answer is unaffected.
- The source's omitted ICSP_Wgh pseudocode, its inconsistent “path set” versus
  single-Dijkstra descriptions, Algorithm 2 endpoint conventions, and its
  interpolation coverage remain unresolved source ambiguities. These are
  listed as implementation limits rather than attributed to the paper as
  fully specified behavior.
- Peak memory is sampled used heap, not a true allocation peak. The exact
  approximation error is available only when the independent small-graph
  oracle is run; it is not computed on production-sized queries.

## Validation pointers

See [SICSP_NATIVE_VALIDATION_REPORT.md](SICSP_NATIVE_VALIDATION_REPORT.md) for
the test matrix, oracle comparisons, command results, and the small-graph
heuristic miss.
