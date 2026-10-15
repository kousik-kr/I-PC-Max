# SICSP-Pref preference adaptation audit

## Scope and result

M4 adds `sicsp-pref`, an objective-adapted baseline built on Yuan et al.'s
sequential SICSP breakpoint search. The native `sicsp-native` query remains the
earliest-arrival query constrained by accumulated native edge weight. The new
query maximizes PACE preference subject to a travel-time budget, independently
at each departure in the requested interval.

The adaptation keeps SICSP's per-edge event loop, reverse latest-departure
search, forward earliest-arrival search, prefix-edge-suffix witness, and raw
event diagnostics. It changes the secondary event source and the secondary
attribute comparisons. It does not add another graph search. The final profile
is exact over the retained, PACE-valid candidate registry and carries
`RETAINED_FRONTIER` scope; no global preference-optimality claim is made.

## Paper-to-code traceability

The primary source is Yuan et al., *Constrained Shortest Path Query in a Large
Time-Dependent Graph*, PVLDB 12(10), 2019, pp. 1058-1070, provided at
`Baseline papar/p1058-yuan.pdf`. SICSP's native implementation is audited in
[`SICSP_NATIVE_IMPLEMENTATION_AUDIT.md`](SICSP_NATIVE_IMPLEMENTATION_AUDIT.md).
The PACE problem contract is `QuerySpec`: a departure interval and a maximum
travel time, with edge preference supplied by `Edge.scoreFunction`.

| Paper operation | Native code | SICSP-Pref code | M4 relationship |
|---|---|---|---|
| Reverse latest-start / LST search, §3.1.4 and Algorithm 1 line 5 | `SicspTemporalDijkstra.latestDeparture` | Same call through `SicspNativeEngine.generateCandidates` | Identical implementation and time functions |
| Forward earliest-arrival / EAT search, §3.1.4 and Algorithm 1 line 6 | `SicspTemporalDijkstra.earliestArrival` | Same call through `SicspNativeEngine.generateCandidates` | Identical implementation and time functions |
| O-point events from edge travel-time breakpoints | `SicspNativeEngine.generateCandidates` | Same event loop and event list | Identical |
| W-point events from the secondary function breakpoints, §3.1.3 | Native weight breakpoints | `Edge.scoreFunction().breakpoints()` | Event data changes; the generator mechanism is the same |
| Prefix + event edge + suffix, replay and provenance | `generateBreakpointCandidate`, `SicspRouteReplay`, `SicspBreakpointCandidate` | Same candidate-generation method; wrapped as `SicspPrefBreakpointCandidate` with PACE replay values | Identical witness generation; preference is separately replayed at actual edge-entry times |
| Candidate grouping by complete path | `SicspCandidateGroups.byPath` | Same `SicspCandidateGroups.byPath` utility | Identical grouping |
| O-point comparison, Algorithm 1 lines 9-18 | `SicspDominance.strictlyDominates` | `SicspPreferenceDominance.strictlyDominates` | Secondary direction changes from lower weight to larger preference; both comparisons remain strict |
| W-point skyline, §3.1.3 | `SicspDominance.skylineDominates` | `SicspPreferenceDominance.skylineDominates` | Earlier arrival and larger preference are better; weak non-worse tests plus one strict improvement |
| Point-to-profile replay and budget | Native route profiles and native `weight <= Delta` selection | `ExactPathProfileBuilder.budgetContext` with PACE travel-time budget | Objective and constraint change |
| Profile selection and ties | Native earliest-arrival selection | `EnvelopeExtractor.extract` and `EnvelopeExtractor.compareAt` | PACE's score, travel time, edge count, stable arc-ID order |
| PACE vertex simplicity | Native mode preserves repeated vertices | `LooplessChecker` at the adaptation boundary | Repeated witnesses are counted and rejected after generation |

The native engine now exposes `generateCandidates`. Its original constructor
still takes native weight breakpoints, and `query` still performs native
filtering and native ArrTime. SICSP-Pref calls only the shared generation
entry point. Its timing carrier has zero-valued weights covering each travel
function's domain; these values are not used for route ranking, dominance,
budgeting, or selection. The shared event provider supplies the original PACE
score breakpoints, and `SicspPrefBreakpointCandidate` records the actual PACE
score and travel time from a separate no-wait replay.

## Objective and selection semantics

For each retained path `P`, exact replay constructs arrival `A_P(t)`, travel
time `A_P(t) - t`, and preference `S_P(t)`. Every edge score is read at that
edge's actual entry time. Preference remains its original nonnegative integer
value; it is not inverted, negated, shifted, or normalized.

The continuous route profiles are restricted to the query departure domain and
`A_P(t) - t <= B`. The budget is not used by LST/EAT candidate generation and
does not cause an event route to be treated as feasible outside its replayed
feasible domain. Breakpoint endpoint ownership, score changes, budget roots,
singleton cells, and uncovered cells are delegated to the validated PACE
profile and envelope components.

At a departure, `EnvelopeExtractor.compareAt` selects by:

1. larger preference;
2. smaller travel time;
3. fewer edges;
4. stable lexicographic arc-ID path order.

For O-points, another path removes a candidate only when its arrival is
strictly earlier and its preference is strictly larger. Equality in either
coordinate does not remove an O-point. For W-points at the same source
departure, another point dominates when it is no later, has no smaller
preference, and is strictly better in at least one coordinate. Equal skyline
points remain tied. These are point-level adaptations of SICSP's pruning
rules, not a profile-level or global dominance guarantee.

## Candidate registry and PACE path policy

The registry distinguishes:

- raw unique O/W event points and their diagnostics;
- unique raw candidate paths, including repeated-vertex witnesses;
- vertex-simple paths after the PACE boundary check;
- O/W points retained by the adapted dominance tests;
- consistent out-of-query event paths carried into route-profile construction,
  matching the native implementation's documented interval-profile policy;
- retained paths whose exact PACE replay has a nonempty budget-feasible domain.

`SicspPrefBreakpointCandidate` stores the event arc/time, LST departure, prefix
arrival, EAT, replayed destination arrival, replayed travel time, actual-entry
preference score when defined, status, path, and PACE simplicity result. A
repeated-vertex candidate has a `REPEATED_VERTEX:<id>` rejection reason. No
replacement path is generated for a rejected witness.

`SicspPrefResult` exposes raw, simple, out-of-query, retained, and feasible
candidate registries separately. The envelope contains explicit `NO_PATH`
cells when no retained feasible candidate covers that cell. The experiment
runner reports `UNRESOLVED_NO_GENERATED_CANDIDATE` for an empty feasible
registry, with `RETAINED_FRONTIER` scope. This does not assert global
infeasibility.

## Candidate-generation equivalence and native regression

Both native and preference modes now invoke the same `SicspNativeEngine`
candidate-generation implementation. Its LST/EAT calls, travel-time event
enumeration, witness concatenation, replay consistency checks, provenance,
deduplication, and candidate grouping are shared. Only the W-event provider
and the policy applied after candidate generation differ. O events are
therefore identical for a fixed temporal graph. W events use native weight
breakpoints in native mode and PACE score breakpoints in preference mode.

`SicspPreferenceEngineTest.adaptsOnlyTheObjectiveAndKeepsNativeFastestResultAndEventPaths`
compares native and adapted O/W event witnesses on a graph where native weight
breakpoints equal PACE score breakpoints. The test also confirms that native
still selects the fastest path while SICSP-Pref selects a slower, higher-score
route under the travel budget. Existing native selection, event, Figure 1,
oracle, and equality tests run unchanged after the extraction. M4 does not
change `SicspTemporalDijkstra` or native `SicspDominance`.

## Independent validation

`SicspPreferenceEngineTest` exhaustively enumerates vertex-simple routes on
small fixtures and composes their profiles with `ExactPathProfileBuilder` and
`EnvelopeExtractor`. The expected envelope is checked against both the exact
global simple-path set and the engine's own feasible retained registry. This
checks that SICSP-Pref selects exactly the best path in its own generated,
retained, and feasible candidate set while allowing a global gap.

Fixtures cover:

- constant travel and preference, with a fastest route that is not the
  highest-preference route satisfying the budget;
- changing preference and a selected-path switch at a score breakpoint;
- score evaluation at actual entry time on a multi-edge path;
- travel-time breakpoint and FIFO arrival plateau;
- continuous budget crossing, budget equality, and a singleton departure;
- equal preferences with different travel times and deterministic route ties;
- a cyclic native witness rejected at the PACE boundary;
- a retained registry with no budget-feasible route;
- an exhaustive global oracle route missed by SICSP's LST/EAT event heuristic;
- root-rounding behavior at a temporal breakpoint.

The global-miss fixture has the simple route `[2,3,6,7]`, with preference 40,
but the event heuristic does not generate that path. SICSP-Pref's registry
therefore has a lower integrated score than the exhaustive path oracle. This is
an expected heuristic miss and does not change the algorithm.

The experiment runner also computes an exhaustive oracle quality gap only for
graphs with at most 12 nodes and 64 edges, bounded by the configured
`maxEnumeratedPaths` and a 2-million-state work cap. It reports the oracle
timing separately from the SICSP-Pref query time. Larger graphs report why the
oracle was not run.

## Numerical limitation

The Figure 1 discrepancy is caused by inverse-root rounding under the
repository's 12-decimal temporal contract. The first edge's composed arrival
on the relevant segment is `10 + 1.5t`. The mathematical inverse of the
second-edge entry breakpoint `20` is `t = 20/3`; canonicalization stores
`6.666666666667`. Independent replay at that stored departure reaches the
second edge at `20.000000000001` and then reaches the destination at
`60.000000000001`. The composed profile interpolates at the affine root before
the output canonicalization and gives `60.0`. The difference is approximately
one 12-decimal tick (`1e-12`); the existing Figure 1 native query's selected
answer is unaffected.

This is a finite-precision limitation, not arbitrary-rational arithmetic. Near
a budget equality, the same one-tick arrival difference can move a feasibility
root or endpoint. Near a right-continuous score breakpoint, root rounding can
assign the adjacent canonical departure to one side of the score change. Either
case can change route feasibility or the selected envelope path at a canonical
boundary cell. The Figure 1 score at the rounded root is evaluated using the
actual replayed entry time. No global `Domain` canonicalization change was made
because its impact would extend across PACE, SICSP, THop, and profile
operations; the native audit's limitation remains applicable.

## Runtime fields and known limits

The `sicsp-pref` runner records end-to-end query time, candidate-generation
time, candidate adaptation and event replay time, exact retained-profile
replay time, envelope construction time, forward/reverse search
calls, O/W events, duplicate event count, raw/simple/out-of-query/retained
route counts, dominance removals, non-simple rejections and reasons, candidates
with empty/nonempty feasible domains, profile cells, feasible coverage,
integrated preference, selected travel-time minimum/average/maximum, sampled
used heap, and the small-graph oracle gap when computed. The query timing
includes generation, adaptation/dominance, exact retained-path replay, and
envelope extraction. Oracle time is reported separately.

Known limits:

- SICSP's event heuristic may miss globally optimal preference routes or all
  feasible routes; the result is exact only over its retained registry.
- W-point skyline details are prose-specified in the paper and retain the
  documented conventional skyline interpretation.
- Profiles and departure roots use the repository's canonicalized double
  model, with the one-tick root-rounding limit above.
- Sampled heap is a phase/event sample, not an allocation peak.
- The exhaustive quality oracle is limited by graph-size and path-count caps.
- No large-network SICSP-Pref experiment, partitioned THop work, or full
  network experiment was run for M4.

## Validation record

The pre-M4 native report records the original 275-test Maven suite. After M4,
the complete Maven suite passes 288 tests with 0 failures, 0 errors, and 0
skips. The targeted validation run passes SICSP-Pref (11 tests), native SICSP
(12), THop (9), PACE differential oracle (11), PACE public API oracle (9), and
experiment-runner tests.

Commands run:

```text
mvn -q -Dtest=SicspPreferenceEngineTest,SicspNativeEngineTest,FifoTimeDependentDijkstraTest,TemporalArrivalProfilesTest,THopIndexTest,PaceExactOracleDifferentialTest,PacePublicApiOracleIntegrationTest,ExperimentAlgorithmsTest test
PASS

mvn -q test
PASS: 288 tests, 0 failures, 0 errors, 0 skipped

git diff --check
PASS
```
