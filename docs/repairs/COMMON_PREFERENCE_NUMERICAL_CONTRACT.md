# Common exact preference objective and result contract

**Status:** implemented for final route evaluation; search scope remains method-specific. This document records the M5-R3 contract and does not approve an experiment freeze.

## Objective

For a directed vertex-simple route `P` and departure `t`, route replay is no-wait:

* `A_P(t)` composes each edge's arrival function at the preceding edge's arrival.
* `S_P(t)` adds each edge's integer preference at its edge-entry time.
* `P` is feasible at `t` exactly when `A_P(t) - t <= B`.
* Among feasible routes, maximize `S_P(t)`. Resolve equal scores by smaller exact travel time, fewer arcs, then lexicographically smaller directed arc-ID sequence.

`ExactPreferenceObjective` is the common final evaluator for PACE-X, PACE-B, basic iSCOPE, and SICSP-Pref. It replays every route in the adapter's emitted registry through `ExactPreferenceReplay.replayExact`, drops routes whose exact feasible domain is empty, and builds the route-registry envelope with `ExactPreferenceEnvelope.overRetained`.

## Numeric representation

Accepted graph/query doubles are first canonicalized through the repository's 12-decimal `Domain` contract. The exact layer then treats those canonical decimal values as rationals. From that boundary onward, edge-entry times, affine arrivals, score-step preimages, budget sublevel roots, profile intersections, and endpoint ownership remain rational. There is no epsilon feasibility test and no intermediate temporal rounding in the exact evaluator.

The exact envelope partitions the full requested departure domain, including open cells and owned singleton cells. `NO_PATH` cells contain no score and contribute neither feasible coverage nor integrated score. A zero-measure singleton domain is handled explicitly for coverage. Rational integration is performed before display conversion.

The authoritative schema is `exact-preference-envelope-v1`; the numerical objective contract identifier is `EXACT_RATIONAL_PREFERENCE_PROFILE-v1`. Canonical numerator/denominator strings and the profile checksum are authoritative. Decimal/double summaries are presentation fields.

## Registry and certification scope

Every preference result carries `objective_result` with the exact profile, checksum, contract and completion status, declared exactness scope, generated/emitted/retained/selected route identities, exact feasible coverage, exact integrated and average preference, path switches, cell count, and end-to-end query time. SICSP-Pref additionally preserves its pre-retention simple candidate registry and native retained registry. PACE provenance keeps pivot and candidate-generation details. Legacy `AlgorithmResult.profile` is retained only as a `legacy_projection` and is not used as the authoritative result when an exact objective profile exists.

Exact evaluation of an emitted registry does not certify that search generated a global optimum:

| Method | Exact evaluation scope | Scope rule |
| --- | --- | --- |
| PACE-X | Emitted registry, exact | Always `NOT_CERTIFIED` in this migration because its search-time numerical pruning has not been proved under the rational model. |
| PACE-B | Retained registry, exact | At most `RETAINED_FRONTIER`; candidate limits and search pruning are part of its bounded policy. |
| iSCOPE | Emitted simple-path registry, exact | `GLOBAL_CERTIFIED` only when the unpruned DFS exhausts, and neither the five-second deadline nor path cap fires. |
| SICSP-Pref | Native retained feasible registry, exact | `RETAINED_FRONTIER`; the native event generator is a candidate search, not a global simple-path proof. |

### M5-R4 iSCOPE variants

The table above records the M5-R3 state. M5-R4 preserves `iscope` as the historical alias for `IScopeAlgorithm` and registers `iscope-basic` with the same unpruned behavior. `iscope-opt1` uses rational partial functions and the proved conservative edge-minimum bound; it may claim `GLOBAL_CERTIFIED` only after exhaustive uncapped search and complete exact replay. `iscope-optimized` adds approximate Opt2 merging and is always `NOT_CERTIFIED`, even though its reported values are exact over the retained route registry. See [the implementation report](ISCOPE_OPTIMIZED_IMPLEMENTATION.md), [Opt1 proof](ISCOPE_OPT1_PRUNING_PROOF_AND_TESTS.md), and [Opt2 contract](ISCOPE_OPT2_APPROXIMATION_CONTRACT.md).

A failed or empty exact registry is unresolved by an adapter unless its declared search scope proves no feasible route. In particular, a legacy PACE `NO_FEASIBLE_PATH` result is not upgraded by final replay.

## Aggregation and validation

Java writes the exact profile under `objective_result.profile`; `ProfileSupport` makes it authoritative in serialized output and preserves the legacy projection separately. Python's typed decoder in `experiments/scripts/common/exact_preference.py` validates the schema, canonical rational spellings, cell coverage and endpoint ownership, arrival relations, exact metrics, and checksum. Collection and summaries use `Fraction` for exact coverage, integrated preference, and average selected preference. Conversion to floats occurs only in display columns.

The cross-method regression uses the SICSP-Pref wrong-winner and false-budget fixtures. For each method it checks the exact evaluator's complete cells against the independent `TinyContinuousEnvelopeOracle` over that method's emitted registry. It separately enumerates the global simple-path optimum. Differences between those results are candidate-coverage differences; they are not reported as replay arithmetic mismatches. The PACE-X test also ensures an exhaustive legacy completion claim does not restore a global rational certificate.

Tests cover serialization/checksum round trips, `NO_PATH`, score boundaries, budget roots, tie rules, and exact result selection over a legacy projection. Full test totals and any remaining gates are reported with the M5-R3 commit.
