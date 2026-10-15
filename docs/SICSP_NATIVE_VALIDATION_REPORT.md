# Native SICSP validation report

## Test coverage

`SicspNativeEngineTest` and its independent `SicspSmallGraphOracle` cover:

| Case | Test coverage |
|---|---|
| Constant FIFO edge functions and native accumulated-weight bound | `nativeWeightBoundIsIndependentAndEqualityIsFeasible` |
| Piecewise-constant weight breakpoint and a different optimal departure | `weightBreakpointMovesEarliestFeasibleDepartureAndRetainsProfiles` |
| Competing routes, O-point functions, I-point intersection and W-point pieces | `constructsPathLinkedLowerEnvelopeAndArrivalIntersection` |
| Equal arrivals, equal weights and deterministic route tie | `equalWeightAndArrivalTiesUseDeterministicRouteAndDepartureRules` |
| Arrival plateau and weight tie boundary | `arrivalPlateauUsesWeightThenEarliestDepartureForTies` |
| Closed temporal endpoint and unreachable destination | `endpointAndUnreachableCasesAreExact` |
| LST prefix that reaches before the intended event | `inconsistentLatestPrefixIsRecordedWithoutInsertingWait` |
| Repeated vertex in a concatenated prefix/event/suffix witness | `concatenatedWitnessMayRepeatVerticesAndIsNotDiscardedAsNonSimple` |
| Missed feasible path from non-shortest prefix plus non-shortest suffix | `oracleReportsFeasiblePathsMissedByThePublishedShortestPathHeuristic` |
| Strict Algorithm 1 dominance inequalities and skyline equality | `dominanceUsesExactStrictInequalitiesFromAlgorithmOne` |
| Yuan et al. Figure 1 edge functions, weight profile and query | `paperFigureOneExampleRetainsItsFifoPlateauAndWeightProfile` |
| Weight support coverage check | `rejectsNativeWeightFunctionsThatDoNotCoverTravelEntryTimes` |

The oracle enumerates every vertex-simple source-destination path on each tiny
fixture. It independently replays edge travel time and weight, finds path
event departures by pulling edge time/weight breakpoints back through each
prefix, evaluates the whole query interval, and computes the constrained
earliest arrival. The oracle compares candidate event values, exact retained
route profiles, native result over retained paths, global optimum, feasible
paths missed by the heuristic, and native arrival error.

## Oracle findings

- On the constant chain, weight-breakpoint, crossing, plateau, and paper
  Figure 1 fixtures, candidate replay values and retained route profiles
  matched the independent oracle at every checked event/root. Native selection
  matched the oracle over the retained candidate paths.
- The Figure 1 query is `(v1,v3,[0,30],Delta=80)`. Its selected answer is
  departure `0`, arrival `50`, accumulated weight `25`. The plotted
  `e2` travel function has the printed breakpoints `(50,40)` and `(80,10)`;
  its slope `-1` segment yields a FIFO arrival plateau. The plotted weights
  yield total path weights `25` at departure `0`, `45` at `10`, and `35` at
  `50`.
- The heuristic miss fixture has a feasible simple path with arc IDs
  `[2,3,6,7]`, assembled from a non-shortest source prefix and a
  non-shortest destination suffix. Its weight is `0`, so it is feasible under
  `Delta=1`, and its arrival is `10`. Per-event reverse/forward shortest path
  choices produce mixed routes whose fast prefix or fast suffix has weight
  above the bound. SICSP returns no retained feasible route for this case.
  The oracle therefore reports infinite arrival error for the missing native
  result and lists `[2,3,6,7]` as a missed feasible path. This is an expected
  limitation of the paper's heuristic, not a test failure.
- A separate candidate witness `[0,1,2,3]` repeats a vertex after
  prefix/event/suffix concatenation. Native SICSP preserves its arc sequence
  and profile; it does not apply the oracle's vertex-simple constraint.
- Candidate event arrivals and accumulated weights matched independent replay
  on all checked candidates. Retained route profiles matched replay on the
  constant, breakpoint, crossing, plateau and missed-path fixtures.
- The Figure 1 route profile has one discrepancy at departure
  `6.666666666667`: replay gives arrival `60.000000000001`, while the
  composed profile gives `60.0`; both give weight `45.0`. The difference is
  one 12-decimal time tick (`1e-12`) at a canonicalized breakpoint root. The
  Figure 1 native selection at departure `0` still matches the oracle. This
  root-rounding discrepancy is unresolved and should be revisited before
  relying on sub-tick boundary decisions.

## Commands and results

The initial foundation/THop regression run before SICSP edits passed:

```text
mvn -q -Dtest=FifoTimeDependentDijkstraTest,TemporalArrivalProfilesTest,THopIndexTest test
PASS
```

SICSP verification during implementation:

```text
mvn -q -DskipTests compile
PASS after implementation fixes

mvn -q -Dtest=SicspNativeEngineTest test
PASS, 12 SICSP tests

mvn -q -Dtest=FifoTimeDependentDijkstraTest,TemporalArrivalProfilesTest,THopIndexTest test
PASS, shared temporal foundation and THop regression

mvn test -q
PASS, 275 tests; 0 failures, 0 errors, 0 skipped

git diff --check
PASS
```

## Instrumentation exposed by native results

`SicspNativeStatistics` reports edges considered, travel-time and weight
breakpoints examined, reverse/forward Dijkstra calls, O/W points, candidate
routes, duplicate candidates, dominance comparisons/removals, temporal pieces,
feasible departure regions, per-phase wall time, total query time, and sampled
peak used heap. The test oracle reports approximation error and missed
feasible simple paths separately; this prevents production query timing from
being presented as an exactness check.
