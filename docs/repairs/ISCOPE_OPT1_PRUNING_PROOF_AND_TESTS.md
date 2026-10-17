# M5-R4: Opt1 conservative pruning proof and differential tests

## Bound construction

For every directed graph edge `e`, let `w_e(t)` be its accepted piecewise-linear travel-time function on its supported time domain. The implementation computes

`m(e) = min { w_e(t) : t is in e's support }`

as the exact minimum of the canonical-decimal rational values at all function breakpoints. A piecewise-linear function attains its minimum on a closed linear piece at an endpoint, so scanning all stored knots finds this minimum exactly. It is nonnegative by the graph input contract.

For destination `d`, reverse Dijkstra uses `m(e)` as the static weight of directed edge `e` and returns `LB(v,d)`, the shortest directed walk weight from `v` to `d`. Every time-dependent suffix route `R` from `v` to `d` traverses some edge at some valid entry time `t_i`; for every edge, `w_e(t_i) >= m(e)`. Therefore:

`duration(R) = sum_i w_ei(t_i) >= sum_i m(e_i) >= LB(v,d)`.

The last inequality follows because the shortest static walk is no greater than the static weight of this particular directed suffix. It remains true for simple suffixes, regardless of time dependence, score steps, or direction. The lower bound ignores temporal coordination and may be loose, which only reduces pruning.

At a partial path with exact source-departure arrival function `A(t)` and remaining budget `B`, every feasible completion must satisfy:

`A(t) + LB(v,d) <= t + B`.

The accepted departure set is exactly

`support(A) ∩ { t : A(t) <= t + B - LB(v,d) }`.

`ExactTravelBudgetDomain.compute` solves this rational piecewise-affine inequality, retaining equality roots and endpoint ownership. Opt1 intersects it with the label support and recurses separately over the remaining exact temporal cells. It uses neither Euclidean coordinates nor a rounded floating lower bound. If reverse Dijkstra has no entry for `v`, no directed suffix exists and the label is safely discarded.

## Proof obligations and assumptions

The proof depends on the input edge travel functions being nonnegative, piecewise linear over their declared support, and queried only where the original exact partial-arrival composition is supported. The minimum is exact relative to canonical decimal inputs: input doubles first cross the repository's 12-place canonicalization boundary; thereafter all comparisons and roots are rational. This does not recover a different source decimal spelling or alter the repository's input contract.

No FIFO assumption is needed for the static minimum inequality itself. The graph and oracle fixtures remain FIFO to match the stated road-network model. No waiting is inserted: each edge is evaluated at the preceding edge's arrival. Reverse adjacency preserves directed asymmetry. The shortest-path bound may use a static walk that is not temporally realizable; that makes it smaller and remains safe.

The WISE paper instead proposes `EuclideanDistance(v,d)/maxSpeed`. Current graph coordinates are stored as `long`, but the repository does not declare a physical coordinate unit, a compatible unit conversion, or a proved maximum network speed. Applying that expression could overestimate true remaining travel time, so this code disables it (`euclidean_speed_bound_used=false`, `coordinates_used=false`) and records the exact minimum-edge fallback.

## Tests

`IScopePartialLabelOracleTest` performs whole-cell comparisons with the independent `TinyContinuousEnvelopeOracle`, which constructs expected routes and rational cells separately from the production exact-profile classes.

- Basic `iscope` and `iscope-basic` retain their prior unpruned behavior. Their exact retained route registries agree.
- `iscope-opt1` and basic agree on the tested feasible route registry, and both exact envelopes match the full-graph oracle.
- A budget-constrained FIFO route has two disconnected exact feasible intervals. Opt1 retains both exact equality boundaries at `2/3` and `22/3`, and leaves the interval between them as `NO_PATH`.
- A second fixture keeps the `20/3` fractional root and a FIFO arrival plateau; every owned point and open interval matches the oracle.
- Other fixtures cover score switches at edge-entry times, directed asymmetry, no route, eleven nested edge compositions, and a cycle that must not revisit a vertex.
- A branching disconnected subgraph demonstrates measurable pruning: basic examines 36 outgoing edges; Opt1 examines 6 and counts 2 safely pruned temporal components. This is a deterministic tiny fixture, not a general speedup claim.
- An injected monotonic clock forces a five-second-protocol cap. The result stays `NOT_CERTIFIED`, and the exact envelope is checked against the actually committed exact retained registry.

Opt1 is accepted for the declared canonical-rational contract only when its search exhausts, no deadline/path cap fires, and incremental final evaluation completes. A capped result's complete exact profile over emitted routes does not certify that the search examined every route.
