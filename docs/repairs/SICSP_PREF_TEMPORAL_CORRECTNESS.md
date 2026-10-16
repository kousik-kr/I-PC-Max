# SICSP-Pref temporal correctness status (M5-R R2)

Date: 2026-10-16. This report records an **unresolved P1 numerical defect**. SICSP-Pref's implementation has not been numerically repaired. The change in this milestone is a safety downgrade that prevents the affected output from claiming a certified retained frontier.

## Arithmetic contracts and reproduction

The fixture has two consecutive travel functions `T(t)=2t+1` and `T(t)=2t+20`, with the first score changing at the true inverse event `20/3`. Its departure interval is `[0,20]`. The supplied decimal departure is `6.666666666667`, which is not the rational event `20/3`.

| Interpretation at the supplied decimal departure | First edge arrival / second edge entry | Final arrival | Final score |
|---|---:|---:|---:|
| Exact rational continuous replay of the decimal input | `20.0000000000005` | `60.0000000000005` | 10 |
| Legacy scalar replay, canonicalized to 12 decimal places after each operation | `20.000000000001` | `60.000000000001` | 10 |
| Legacy continuous PWL composition over the full `[0,20]` interval | `20.0` | `60.0` | 10 |

At the exact rational event `20/3`, unrounded rational replay produces the intermediate entry time `20` and final arrival `60`. The discrepancy appears when the rounded decimal departure is replayed edge by edge: the rounded first edge entry changes which score interval owns the event and which point the second travel function evaluates. Whole-interval composition stores a continuous PWL function and does not preserve that nested per-operation rounding. Restricting the whole-interval profile to the singleton still returns `60.0`; independently constructing a singleton profile returns `60.000000000001`.

These are three different models: THop's exact rational functions after decimal input conversion; legacy scalar edge-by-edge evaluation with 12-decimal canonicalization per operation; and legacy continuous PWL composition. The last two are not equivalent. No tolerance was introduced, no global arithmetic was changed, and no rational values were mixed into the legacy result.

## Demonstrated impact beyond the original value mismatch

The independent route-winner fixture adds a third constant-time edge to the two-edge chain and a direct alternative. At departure `6.666666666665`, the independent legacy scalar replay gives the chain score 10, while the direct route scores 15. Both the exhaustive simple-route oracle and the raw and retained SICSP candidate registries choose the direct route. The composed retained envelope instead selects the chain with score 20. Thus the mismatch changes the reported winning path and selected score.

A separate one-edge fixture has travel time `3t+1`, query interval `[0,9]`, and travel-time budget 21. The true continuous feasibility boundary is `20/3`. Legacy root rounding admits departure `6.666666666667` into the production envelope, although independent scalar replay gives travel time `21.000000000001` and exact rational replay says there is no feasible route there. The independent rational cell oracle leaves that departure infeasible.

The fixed regression also checks the original decimal root on both adjacent `10^-12` departures, score-step ownership at event boundaries, budget immediately below/equal/above the scalar travel time, a FIFO arrival plateau, multiple score crossings, three-edge temporal composition, alternative composition groupings, and full-profile restriction versus direct singleton construction.

## Independent evidence and required boundary cases

`SicspPrefTemporalContractTest` constructs separate references directly from the graph's raw edge knots and score intervals. Its decimal replay applies independently coded `HALF_EVEN` rounding after each operation; its rational replay interpolates exact fractions without quantization. `TinyContinuousEnvelopeOracle` independently enumerates simple routes and rational temporal cells. The tests separately compare the global optimum, best raw SICSP candidate, best dominance-retained SICSP candidate, route profile, event replay values, and reported envelope. The oracle does not construct expected values by calling the tested SICSP profile functions.

| Required case | Evidence |
|---|---|
| Fractional inverse root `20/3`; supplied departure `6.666666666667` | `fractionalRootSeparatesRationalDecimalReplayAndComposedProfile` |
| Immediately before and after the event; edge-entry and event values | `independentReplayAndEventValuesAgreeBeforeAtAndAfterTheScoreRoot` |
| Right-continuous score endpoints and multiple score crossings | `exactScoreBoundaryAndMultipleCrossingsAgreeOnRepresentableEvents` |
| Budget below, equal to, and above replayed travel time | `originalRootBudgetEqualityAndAdjacentTicksAreRecordedWithoutTolerance` |
| FIFO arrival plateau; multi-edge composition; alternate grouping | `plateauAndMultipleCompositionGroupingsHaveASeparateExactReference` |
| Full interval, restricted singleton, and direct singleton | `fullProfileRestrictionAndDirectSingletonReplayDisagree` |
| False feasibility at the rounded travel-time boundary | `roundedBudgetRootIncludesAProvenInfeasibleDeparture` |
| Wrong selected route/score; raw vs retained vs exhaustive oracle | `earlyRoundedScoreStepChangesTheWinningRouteInRawAndRetainedRegistries` |
| Original native SICSP Figure 1 and weight-bound semantics | Existing `SicspNativeEngineTest` Figure 1 tests; test source unchanged |

The fixed regression set covers the requested root, event ownership, score, budget, plateau, route selection, composition, singleton, repeated-grouping and feasibility cases. It is evidence for the counterexample and diagnostic surface; it does not certify every possible nested rounding pattern or repair the common model.

Explicit checklist for the 17 requested cases:

| # | Case | Test evidence |
|---:|---|---|
| 1 | Fractional inverse root `20/3` | `fractionalRootSeparatesRationalDecimalReplayAndComposedProfile` |
| 2 | Supplied departure `6.666666666667` | Same test; exact decimal, rational event and rounded replay are all asserted |
| 3 | Query immediately before a breakpoint | `independentReplayAndEventValuesAgreeBeforeAtAndAfterTheScoreRoot`, departure `6.666666666666` |
| 4 | Query immediately after a breakpoint | Same test, departure `6.666666666668` |
| 5 | Right-continuous score boundary | `exactScoreBoundaryAndMultipleCrossingsAgreeOnRepresentableEvents`; independent score-interval ownership |
| 6 | Exact budget equality | `originalRootBudgetEqualityAndAdjacentTicksAreRecordedWithoutTolerance`, exact matching travel-time decimal |
| 7 | Budget immediately below feasible travel time | Same test, `53.333333333333` |
| 8 | Budget immediately above feasible travel time | Same test, `53.333333333335` |
| 9 | FIFO arrival plateau | `plateauAndMultipleCompositionGroupingsHaveASeparateExactReference` |
| 10 | Candidate path-switching point | `earlyRoundedScoreStepChangesTheWinningRouteInRawAndRetainedRegistries`: exact oracle chooses direct before `20/3` and chain at `20/3`; legacy candidate oracle checks just after |
| 11 | Multiple temporal compositions | `plateauAndMultipleCompositionGroupingsHaveASeparateExactReference`, three-edge path |
| 12 | Singleton departure interval | `fullProfileRestrictionAndDirectSingletonReplayDisagree` |
| 13 | Full interval restricted to a singleton | Same test, `[0,20]` restricted to `{6.666666666667}` |
| 14 | Multiple score-step crossings on a route | `exactScoreBoundaryAndMultipleCrossingsAgreeOnRepresentableEvents` |
| 15 | Same route under different valid composition groupings | `plateauAndMultipleCompositionGroupingsHaveASeparateExactReference`; left- and right-associated exact and legacy composition |
| 16 | Travel-time feasibility crossing | `roundedBudgetRootIncludesAProvenInfeasibleDeparture`; rational boundary `20/3`, false legacy inclusion after it |
| 17 | Original native SICSP Figure 1 | Existing `SicspNativeEngineTest` Figure 1 regression; unchanged |

## Repair decision: documented API blocker (option C)

The audit evaluated the three allowed approaches.

1. **Consistent legacy replay/profile semantics:** not safe as a local change. Scalar replay's operation-by-operation rounding creates nested rounding steps; a continuous PWL function over real departures cannot represent that discrete canonicalization exactly. Adjusting only event roots or using a tolerance would move or blur the defect rather than establish one contract.
2. **Opt-in exact temporal mode:** mathematically viable, but the existing rational API covers arrival profiles only. A complete mode also needs rational score-step preimages and ownership, exact budget domains, tie-aware preference envelopes, and exact output serialization, plus an explicit comparison protocol. Adding only rational arrivals would silently combine incompatible models.
3. **Chosen:** retain the existing search and arithmetic, expose the known counterexamples, downgrade the experiment adapter to `NOT_CERTIFIED`, and document the common API work needed before claiming continuous-time correctness.

This blocker is scoped to the numerical result contract. SICSP-Pref still uses native SICSP's LST/EAT candidate generation and O/W temporal events; no score-guided candidate search was added. The documented heuristic-miss fixtures remain. Native SICSP production code and its Figure 1 tests are unchanged.

## Code and status changes

`SicspPrefExperimentAlgorithm` now reports:

- `exactness_scope=NOT_CERTIFIED` for both feasible and unresolved generated output;
- `numerical_contract=LEGACY_MIXED_PROFILE_AND_SCALAR_12DP-v1`;
- `known_temporal_issue=P1-B`, `temporal_correctness_verified=false`, and `numerical_repair_status=UNRESOLVED`;
- that its small-graph oracle is a legacy-profile diagnostic, not an independent temporal arithmetic certificate.

The numerical output is unchanged. The change prevents a one-tick profile/replay difference from being hidden inside `RETAINED_FRONTIER` exactness. `SicspPreferenceEngineTest.rootRoundingRemainsAOneTickDocumentedBoundaryCase` and all native SICSP regressions were preserved.

## Validation

The required red regression before the metadata downgrade is preserved at `/tmp/m5r-r2-reporting-red.log`: the test expected `NOT_CERTIFIED` but the adapter reported `RETAINED_FRONTIER`.

Focused verification after the downgrade and exact path-switch assertion:

```text
mvn -B -ntp -Dtest=SicspPrefTemporalContractTest,SicspPreferenceEngineTest,SicspNativeEngineTest,ExperimentAlgorithmsTest test
Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS; 2.499 s; 2026-10-16T10:49:39Z
```

The nine new temporal-contract tests pass; the existing SICSP-Pref engine tests, native engine tests, and experiment adapter tests pass. Passing tests establish that the defect is now disclosed and reproduced; they do **not** resolve it.

## Gate consequence

**P1-B remains open. G5 and G7 fail. M6 is blocked.** A future repair must implement one complete arithmetic model across scalar replay, profile construction, score ownership, budget feasibility, envelope ties, oracle comparison, and serialization, or continue to report `NOT_CERTIFIED` for this method.
