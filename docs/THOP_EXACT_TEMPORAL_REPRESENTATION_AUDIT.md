# THop Exact Temporal Representation Audit

## Status and scope

M2C-BR2 is implemented. THop now performs its profile calculations with exact
rational arrival profiles, and the direct search, unpruned THop, pruned THop,
Boundary-THop, and Partitioned-THop agree with an independent rational oracle
on the tiny-graph all-pairs suite. PACE-X, PACE-B, iSCOPE, native SICSP, and
SICSP-Pref continue to use their existing temporal arithmetic.

This repair stops before M5. It does not run full-network experiments or
benchmark the repository. The performance measurements below use only the
deterministic 10-vertex, 15-edge regression graph.

## Exact API and numerical contract

The additive API is in `edu.ipcmax.baselines.exact`:

- `ExactTime` stores a normalized rational as arbitrary-precision integer
  numerator and denominator.
- `ExactTemporalDomain` stores empty, singleton, connected, or disconnected
  supports with exact rational endpoints and independent open/closed flags.
- `ExactAffineSegment` stores exact slope, intercept, and support ownership.
- `ExactArrivalProfile` stores a disjoint piecewise-affine function and
  implements exact evaluation, restriction, affine preimages, composition,
  image domains, and serialization.
- `ExactProfileEnvelope` computes pointwise minima, dominance, equality, and
  exact breakpoint crossings.

The contract is:

1. The existing graph constructors canonicalize accepted double inputs at
   their documented 12-decimal boundary. The resulting decimal spelling is
   interpreted as an exact rational.
2. A public double departure is canonicalized once using the existing
   `Domain.canonicalTime` rule and then converted from its shortest decimal
   spelling to a rational.
3. Edge-entry times, affine coefficients, event roots, support preimages,
   comparisons, and profile arithmetic remain rational and exact thereafter.
4. Open/closed membership is decided by exact rational comparison. No
   floating-point tolerance participates in equality or membership.
5. Public display values are rounded HALF_EVEN to 12 decimal places.

For example, the exact event `62/3` displays as `20.666666666667`, but the
query at that decimal is `1/3,000,000,000,000` minute to its right. Tests cover
the decimal queries `20.666666666666` and `20.666666666667` separately from
the exact root.

Existing binary double inputs do not retain their original source spelling.
`ExactTime.fromCanonicalDouble` converts the canonical double through
`BigDecimal.valueOf`; it is exact for values already accepted at the
repository's decimal canonicalization boundary, but cannot reconstruct a
different decimal that was lost before that boundary. Rational arithmetic
itself has no configured precision or breakpoint cap.

The old `ExactTemporalProfile` accessors remain rounded compatibility views.
They are named/documented as projections and are not used as internal THop
intermediates. Exact labels and query results are available from the new
`exact...` accessors and exact result fields.

## Algorithm migration and source changes

Only THop profile arithmetic was migrated. The existing functional relaxation,
directed 2-hop labeling, pruning order, boundary construction phases, boundary
shortcuts, inner labeling, hub intersection, and cross-partition composition
remain in place. Partition shortcut graph edges carry topology only; their
authoritative travel profiles are held and composed as exact arrival profiles.
Exact preimages retain the valid domain `{t in domain(F) : F(t) in domain(G)}`,
including constant-map preimages and open/closed event boundaries. A required
composition is no longer discarded after a rounded closure failure.

Changed production files:

- `src/main/java/edu/ipcmax/baselines/exact/ExactTime.java`
- `src/main/java/edu/ipcmax/baselines/exact/ExactTemporalDomain.java`
- `src/main/java/edu/ipcmax/baselines/exact/ExactAffineSegment.java`
- `src/main/java/edu/ipcmax/baselines/exact/ExactArrivalProfile.java`
- `src/main/java/edu/ipcmax/baselines/exact/ExactProfileEnvelope.java`
- `src/main/java/edu/ipcmax/baselines/FifoFastestPathProfileSearch.java`
- `src/main/java/edu/ipcmax/baselines/PrunedFifoProfileSearch.java`
- `src/main/java/edu/ipcmax/baselines/THopIndex.java`
- `src/main/java/edu/ipcmax/baselines/BoundaryTHopIndex.java`
- `src/main/java/edu/ipcmax/baselines/PartitionedTHopIndex.java`

Changed tests:

- `src/test/java/edu/ipcmax/baselines/exact/ExactTemporalApiTest.java`
- `src/test/java/edu/ipcmax/baselines/RationalTemporalReference.java`
- `src/test/java/edu/ipcmax/baselines/ThopNumericalFailureReproductionTest.java`
- `src/test/java/edu/ipcmax/baselines/PartitionedTHopIndexTest.java`
- `src/test/java/edu/ipcmax/baselines/BoundaryTHopIndexTest.java`

No changes were made to `Domain`, `TimeProfile`, `PiecewiseLinearFn`,
`EnvelopeExtractor`, `ExactPathProfileBuilder`, or the PACE, iSCOPE, and SICSP
implementations.

## Original failures and their resolution

### Query 4 -> 1 support crossing

The regression now composes the exact source-inner profile, boundary profile,
and destination-inner profile. At the exact root `62/3`, the prefix maps to
21; the boundary-to-destination profile owns 21 and evaluates to 30 there.
The composition retains the exact root and its endpoint ownership, and the
public decimal query `20.666666666667` remains supported. The partitioned query
returns a complete profile with zero support-boundary rejections instead of
throwing or returning an incomplete result.

### Fractional departure `20.666666666667`

The independent route evaluation is `9.3333333333335` before display rounding
and `9.333333333334` after HALF_EVEN rounding. The earlier one-tick difference
came from rounding an edge arrival before evaluating the next edge. Exact
composition now carries the intermediate arrival `21.0000000000005`; direct
functional search, unpruned THop, pruned THop, boundary query, and partitioned
query agree with the independent rational result. The separate 1 -> 7
regression also returns `8.333333333334`.

## Independent exact validation

`RationalTemporalReference` has its own rational type, edge interpolation,
piecewise-affine segment model, path composition, exact domain preimages,
crossing calculation, and lower-envelope construction. Expected profiles are
built by enumerating vertex-simple paths; production THop composition and
envelope operations are not used to construct them. Comparisons check exact
support membership at all collected event endpoints and exact affine
coefficients on every open interval between events.

The deterministic suite covers:

- Exact root `62/3`, the nearest decimal queries on either side, and the 4 -> 1
  crossing at input 21.
- Full-profile restriction to a singleton against independently queried
  singleton values.
- Composition associativity, exact preimages, open/closed support,
  singleton domains, constant/plateau maps, and rational crossings.
- Right-continuous event ownership and exact profile serialization/reload,
  including the `62/3` root in a partitioned result.
- A three-profile boundary composition, partition routes that leave and
  re-enter a partition, and changing fastest paths.
- Every source-destination pair on the tiny graph for direct functional
  search, unpruned THop, pruned THop, and Partitioned-THop; Boundary-THop is
  compared for every boundary pair. Inner partition pairs are also checked.

The original full-pairs differential remains in place and now uses the
independent rational profile oracle. It was not removed or weakened.

## Storage compatibility

Boundary-THop and Partitioned-THop serialization versions changed from 1 to 2.
Version 2 stores exact segment endpoints, endpoint ownership, slopes, and
intercepts as rational numerator/denominator byte arrays. Existing version 1
indexes cannot represent these values and are rejected by the version check;
they must be rebuilt. Checksums, deterministic writing, and graph identity
validation remain covered. Boundary and partitioned save/reload tests compare
the exact profiles, not only rounded projections.

## Performance feasibility sample

The instrumentation retains the existing counters for construction/query
time, profile pieces, compositions, envelope operations, sampled heap, index
bytes, disk I/O, and reload time. `ExactArrivalProfile` also exposes the
maximum bit length across rational numerators and denominators in a profile.
One diagnostic run on the 10-vertex, 15-edge regression graph produced:

| Measurement | Observed value |
| --- | ---: |
| Direct search wall time / generated states | 57.7 ms / 7 |
| Direct compositions / envelopes | 10 / 4 |
| Direct source profiles / maximum rational component bit length | 25 / 6 bits |
| Unpruned THop build time / retained profile pieces | 45.6 ms / 280 |
| Unpruned THop maximum label component bit length | 7 bits |
| THop query time / compositions / envelopes | 4.5 ms / 7 / 6 |
| Boundary build time / pieces / compositions / envelopes | 65.5 ms / 119 / 76 / 34 |
| Boundary index size / reload time | 6,658 bytes / 3.4 ms |
| Partitioned build time / pieces / compositions / envelopes | 40.4 ms / 92 / 48 / 18 |
| Partitioned index size / reload time | 5,650 bytes / 5.1 ms |
| Partitioned query time / result pieces / compositions / envelopes | 9.9 ms / 19 / 21 / 8 |
| Serialized exact query profile size / write / read | 802 bytes / 0.078 ms / 0.091 ms |

The heap counters reported sampled process heap in the approximate 43–101 MB
range during this run. They are not incremental per-index allocation
measurements. These are single-run measurements on a tiny fixture; they do not
estimate full-network scaling or isolate exact arithmetic overhead against a
numerically incorrect legacy run. No full-network benchmark was run. The
observed rational sizes are small for this graph, but they do not bound growth
on larger instances. The exact format and arithmetic have no application-level
precision or breakpoint count cap; practical limits are available memory,
serialization byte lengths, and JVM collection limits.

## Regression results

`mvn -q test` passed all 309 tests across 68 suites with zero failures, errors,
or skips. This includes exact arithmetic, THop, boundary and partitioned
THop, PACE, iSCOPE, native SICSP, and SICSP-Pref tests. The PACE incremental
differential reported 3,051 comparisons and zero mismatches. The focused exact
API and THop suites also passed after the final regression additions.
`git diff --check` passed.

## Scientific limitations

This representation is exact for rational piecewise-affine edge inputs and
the rational operations performed by THop. It does not support irrational
algebraic coefficients or roots, non-affine functions, waiting, or
non-FIFO shortest-path objectives. Public inputs still enter through the
existing double and 12-decimal canonicalization boundary; exactness begins
after that boundary. The legacy rounded projection cannot preserve every
rational event location and must not be used for exact downstream computation.

## Commit

Commit: pending commit.

The implementation follows the functional-profile, pointwise minimum,
label-pruning, and boundary-query structure of Li, Wang, and Zhou, “Time-
Dependent Hop Labeling on Road Network,” ICDE 2019, DOI
[10.1109/ICDE.2019.00085](https://doi.org/10.1109/ICDE.2019.00085).
