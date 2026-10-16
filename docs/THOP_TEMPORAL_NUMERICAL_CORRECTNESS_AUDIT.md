# THop Temporal Numerical Correctness Audit

## Status

This audit reproduces both M2C-B failures. The repository does not currently
provide the representation needed to repair them while preserving exact
continuous-time semantics. No production numerical code has been changed. The
smallest safe next step is to add rational event locations and exact affine
coefficients to the shared temporal-profile API, then migrate THop's profile
operations to that API. The details below explain why a baseline-only adapter
cannot return an exact profile through the existing `Domain` and `TimeProfile`
types.

## Numerical contract for this audit

The intended THop model is a continuous, no-wait, piecewise-affine arrival
function. Edge breakpoints and travel-time values as accepted by the graph
input adapter are interpreted as exact decimal rationals; in the current
constructors, accepted values are first restricted to 12-decimal ticks. A
query departure is canonicalized once to the repository's 10^-12-minute tick
and that decimal is then treated as an exact rational query time. Every edge
is evaluated at the exact arrival of the previous edge. Composition, lower
envelopes, and support preimages use exact rational arithmetic. Algebraic
roots remain exact internally; endpoint ownership is decided at that exact
root. Only the final public numeric value is rounded to 12 decimal places
using HALF_EVEN.

These rules distinguish an exact root from its rounded display and from a
query at that displayed decimal. For example, `62/3` is the exact root
20.666666666666…, its canonical display is `20.666666666667`, and that decimal
query lies `1/3,000,000,000,000` minute to the right of the root. The query is
not the root.

This is a proposed consistent contract, not the behavior of the current
implementation. Current behavior is summarized here:

| Operation | Current behavior |
| --- | --- |
| Edge input | `PiecewiseLinearFn.Breakpoint` rounds the minute and travel-time value to 12-decimal ticks. |
| Query departure and domain membership | `Domain.contains` and profile evaluators first round the argument to a 12-decimal tick. |
| Scalar replay | Each edge's interpolated travel time is rounded, the arrival sum is rounded, then the next edge is evaluated. |
| Functional composition | `TimeProfile.preimage`, interpolation, breakpoint evaluation, and output values are rounded during each composition. Roots are stored as 12-decimal ticks. |
| Independent path oracle | `SimplePathProfileOracle` enumerates routes independently, but composes them with `ExactTemporalProfile` and `TimeProfile`; it is not a numerically independent reference. |
| Temporal attributes | `PiecewiseConstFn` uses right-continuous `[start,end)` pieces; a component's final endpoint belongs to its final piece. |
| Profile cells | `ExactTemporalProfile` stores disjoint open intervals and owned singleton points, but each boundary is still a 12-decimal `double` tick. |

Rounding at each stage is not equivalent to rounding the final exact result.
The scalar, profile, and oracle paths currently apply rounding at different
points, so the implementation does not yet satisfy one common exact
continuous-time contract.

## Failure A: query 4 -> 1 support crossing

The deterministic fixture is `ThopNumericalFailureReproductionTest` and the
existing complete-pairs test. The route causing the crossing uses arc IDs
`[3, 4, 5, 6, 1, 7]`:

| Arc | Route step | Travel time |
| --- | --- | --- |
| 3 | 4 -> 5 | piecewise linear, on `[20,30]` it is `(t-20)/2` |
| 4 | 5 -> 6 | 1 |
| 5 | 6 -> 7 | 1 |
| 6 | 7 -> 2 | 1 |
| 1 | 2 -> 3 | 2 |
| 7 | 3 -> 1 | 4 |

All original edge functions have closed support `[0,60]`; this route is
available in the original graph. The failing partition composition is from
source boundary 5 through destination boundary 3. Its source prefix has a
departure domain `[0,60]`. Its local stored cell on `[20,
23.333333333333]` maps endpoint values 20 and 25. That cell's exact affine
interpolation reaches 21 at
`103333333333333/5000000000000`; the original edge function reaches 21 at
`62/3`. These distinct real roots both canonicalize to `20.666666666667`.
The outer boundary-to-destination profile has overall domain `[0,55]` and
contains an owned singleton cell at input 21 with value 30.

At the canonical departure `20.666666666667`, the exact original edge map is
`21.0000000000005`; the exact interpolation through the stored prefix
endpoints is `21.0000000000006`, which rounds to `21.000000000001` in the
current profile arithmetic. `TimeProfile.composeOrNull` then tries to evaluate
that value in the outer cell whose closure is `{21}` and throws
`time is outside piecewise profile closure: 21.000000000001`. The exact
departure is to the right of both exact roots, even though the rounded root
display is the same. The outer right-hand open cell owns values greater than
21; the singleton cell owns exactly 21.

The current `PartitionedTHopIndex` counts the failed combination and rejects
the query rather than returning a partial profile. That is safe failure
reporting, but Gate A remains unresolved.

## Failure B: fractional departure 20.666666666667

At this departure, the independent exact route `4 -> 1` uses arcs
`[3,4,5,6,1,7]`. Its exact travel time at the query is
`9.3333333333335`; final HALF_EVEN rounding gives `9.333333333334`. The test
reference records edge-entry times `20.666666666667`, `21.0000000000005`,
`22.0000000000005`, `23.0000000000005`, `24.0000000000005`, and
`26.0000000000005`. On arc 3's `[20,30]` piece, travel time is
`(t-20)/2` (slope `1/2`, intercept `-10`) and arrival is
`3t/2-10`. The reference's minimum over all supported simple routes selects
this route. It reports arrival `30.0000000000005` and travel time
`9.3333333333335` before output rounding.

The first scalar/replay divergence is the first edge evaluation: exact travel
time is `0.3333333333335`; `PiecewiseLinearFn.travelTimeAt` returns
`0.333333333333`, and its arrival evaluation returns `21.0`. The exact route
continues from `21.0000000000005`. Thus scalar replay's per-edge canonical
operation changes the intermediate arrival before later edges run.

For `1 -> 7`, the exact fastest route is arcs `[0,1,2,3,4,5]`; arc-entry
times are `20.666666666667`, `22.666666666667`, `24.666666666667`,
`24.666666666667`, `27.0000000000005`, and `28.0000000000005`. Its exact
final arrival is `29.0000000000005`, so exact travel time is
`8.3333333333335` and the final rounded travel time is `8.333333333334`.

The variants report these travel-time values at the exact decimal query:

| Query and domain | Rational route reference | Profile oracle | Direct functional search | Unpruned THop | Pruned THop | Partitioned THop |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 4 -> 1, singleton | 9.333333333334 | 9.333333333334 | 9.333333333334 | 9.333333333333 | 9.333333333333 | 9.333333333333 |
| 4 -> 1, `[0,60]` | 9.333333333334 | 9.333333333334 | 9.333333333334 | 9.333333333334 | not measured | explicitly rejected |
| 1 -> 7, singleton | 8.333333333334 | 8.333333333334 | 8.333333333334 | 8.333333333333 | 8.333333333333 | 8.333333333333 |

`SimplePathProfileOracle`'s absolute-arrival profile evaluates to `30.0` at
the 4 -> 1 departure, so subtracting the departure there and rounding gives
`9.333333333333`. Its `asTravelTime()` profile gives `9.333333333334` because
it subtracts at stored breakpoints and then interpolates. That is the first
profile-level divergence: `asTravelTime()` and query-time subtraction do not
commute with the current rounding. Restricting the full-graph profile to a
singleton before this conversion changes its value from `.334` to `.333`.
The pruned full-graph THop also returns `.333` on the singleton domains.
Boundary THop returns travel time 5 for the relevant 5 -> 3 boundary
subroute. The path oracle shares the same `TimeProfile` arithmetic and is not
an independent numerical proof; the rational reference is independent.

## Independent rational reference

The regression reference is implemented in
`src/test/java/edu/ipcmax/baselines/RationalTemporalReference.java` and uses
`BigInteger` numerator/denominator arithmetic. It reads the decimal edge
breakpoints, performs piecewise-linear interpolation and route replay without
calling THop composition, and selects the minimum exact arrival over
vertex-simple paths at an exact query time. It calculates affine preimages and
line intersections exactly, and checks open/closed support ownership. The
reproductions exercise the support root `62/3`, distinguish it from its
rounded decimal query, and preserve every intermediate route entry and value.

## Representational limit and required API work

`Domain.Interval` and `TimeProfile.Breakpoint` canonicalize endpoints to
signed 10^-12 ticks at construction. `TimeProfile.composeOrNull` stores
preimage roots as those ticks, and `ExactTemporalProfile` delegates all affine
composition to `TimeProfile`. A rational root such as `62/3` cannot be stored
as its exact location, while its rounded decimal can be a different real
point with different endpoint ownership. Adding a baseline-local rational
profile and then converting every root to these public classes would lose the
same information before a THop result is returned.

Minimum shared API changes needed for a mathematically exact repair:

1. Represent temporal values and interval endpoints as exact rationals (or an
   equivalent exact algebraic representation), including open and closed
   endpoint ownership. The source and outer profile domains in Failure A are
   retained as exact endpoint sets during composition.
2. Keep exact affine coefficients and exact preimage/intersection roots during
   composition and lower-envelope construction.
3. Define explicit input conversion and final output formatting boundaries;
   evaluating or testing support must not canonicalize intermediate values.
4. Provide a public projection type that can expose exact roots and ownership,
   or label the existing 12-decimal profile as an approximation.

Changing only THop's internal code cannot meet the requested exactness while
returning a `TimeProfile`/`Domain` result. Shared PACE numerical behavior has
therefore been left untouched, and this milestone stops short of claiming or
implementing an exact repair.

## Source changes and gate status

Changed files:

- `src/test/java/edu/ipcmax/baselines/ThopNumericalFailureReproductionTest.java`:
  fixed reproductions for the 4 -> 1 rejection, the fractional output values
  across THop variants, exact intermediate route values, and endpoint/root
  assertions.
- `src/test/java/edu/ipcmax/baselines/RationalTemporalReference.java`:
  independent rational edge interpolation, scalar route replay, exact
  vertex-simple route minimum at a departure, support membership, affine
  preimage, and affine crossing calculations.
- `docs/THOP_TEMPORAL_NUMERICAL_CORRECTNESS_AUDIT.md`: this numerical contract,
  failure analysis, test evidence, and required shared API changes.

No production file changed. The rational reference validates exact route
arithmetic at the failing queries, but the requested full continuous-profile
property matrix (associativity, parenthesization, every crossing, plateaus,
and every endpoint) is not claimed: the production result type cannot expose
the exact roots required to compare those cells without projection loss.

Acceptance gates at this stop:

- Gate A: unresolved; query 4 -> 1 still explicitly rejects the crossing
  composition.
- Gate B: root cause and exact reference output are established; THop variants
  still disagree by one tick and can change output with the query domain.
- Gate C: not met; the tested unpruned and partitioned THop values differ from
  the rational reference.
- Gate D: all suite tests pass except the same explicit Partitioned-THop
  error; see validation counts.
- Gate E: met; no objective, candidate-generation mechanism, or production
  algorithm changed.
- Gate F: met for this audit; no exactness claim is made for the existing
  12-decimal result representation.

## Validation record

The deterministic failure tests and exact rational reference are
`ThopNumericalFailureReproductionTest` and
`RationalTemporalReference`. The complete-pairs THop differential still
`mvn -q -Dtest=THopIndexTest,BoundaryTHopIndexTest,PartitionedTHopIndexTest,ThopNumericalFailureReproductionTest test`
ran 23 tests: 22 passed and the existing complete-pairs differential errored
on query 4 -> 1 with the documented support-crossing exception. The full
`mvn -q test` suite ran 302 tests: 301 passed, zero assertion failures, and
one error from that same test. All PACE-X, PACE-B, iSCOPE, native SICSP,
SICSP-Pref, direct FPP, THop, and boundary tests passed apart from that
Partitioned-THop case. PACE's incremental differential reported 3,051
comparisons and zero mismatches. `git diff --check` passed. No production
profile code was changed, so there was no implementation performance delta to
measure.
