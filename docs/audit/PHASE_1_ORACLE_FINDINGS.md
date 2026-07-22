# Phase 1 Independent Oracle Findings

Date: 2026-07-21

## Scope

The test-only oracle in `src/test/java/edu/ipcmax/testoracle`:

- enumerates every vertex-simple path and preserves parallel arc IDs;
- replays PWL travel and PWC score functions at actual entry times with normalized `BigInteger`
  fractions;
- derives function, score, feasibility, and equal-score travel-tie roots;
- applies score, travel time, edge count, and numeric arc-ID tie-breaking;
- emits endpoint-aware path and `NO_PATH` cells and checks coverage/maximality;
- does not call production PACE stitching, compression, envelope, profile, or path-validation code.

The suite covers deterministic score pullback, budget roots, parallel-arc ties, identity paths,
self-loop exclusion, zero-score anchor traversal, disjoint feasible cells, coincident roots, and 12
fixed-seed FIFO DAGs with parallel arcs.

## Correctness Fix

The oracle found that `FrontierCompressor` skipped a cell with no active midpoint candidate before
checking a candidate owned only at that cell's start endpoint. A path feasible on `[0,1]` was
therefore returned on `[0,1)`.

The compressor now runs endpoint retention even when the cell interior is empty. The direct
compressor regression and the end-to-end rational oracle case pass.

## Numerical Regression And Remaining Certification Gate

All ten oracle methods now pass. The two previously failing cases exposed production boundaries
that drifted by more than half of the repository's nine-decimal output quantum.

Smallest counterexample:

- one two-edge, all-zero-score path;
- query interval `[0,8]`, budget `5.5`;
- exact inclusive feasibility root `80/11 = 7.272727272727...`;
- PACE boundary `7.272727271`.

The first loss occurs when `TimeProfile.compose` pulls an outer breakpoint back to the exact root
`80/19`, materializes it through a nine-decimal `Domain.Interval`, and reconstructs a composed
affine segment from rounded coordinates. The later budget-root calculation compounds that error.
Seeded cases reproduce the same mechanism at `228/77` and `149152/23509`.

A trial that preserved selected raw `double` ordinates fixed one root but caused composition-domain
errors and did not fix all seeded drift. It was reverted. Production profile/domain arithmetic now
uses three guard decimals beyond the public `10^-9`-minute query-budget unit. This closes the known
multi-edge drift regressions without weakening the oracle tolerance, while query budgets continue
to round upward at exactly `10^-9` minute.

This is a deterministic normalized-decimal implementation contract, not arbitrary-rational storage.
The paper's literal Section 4.2 rational-representation claim therefore remains uncertified until
the production endpoint and affine types retain rational values through every operation.

## Gate Result

The independent-oracle regression gate is accepted for the repository's documented normalized
numerical model: 10/10 methods pass, including 12 fixed-seed FIFO DAGs. Global real-arithmetic
exactness remains unavailable, and production adapters continue to avoid `GLOBAL_CERTIFIED`.
