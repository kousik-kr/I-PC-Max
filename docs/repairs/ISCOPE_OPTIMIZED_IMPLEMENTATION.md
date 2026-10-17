# M5-R4: partial-function iSCOPE implementation

**Baseline:** M5-R3 `5e09e5e6c50cad61492b624b4b86544c2e2e7a7d` on `feature/icde27-baseline-implementation`.
**Disposition:** Opt1 is implemented and passed deterministic tiny-graph differential checks. `iscope-optimized` is a clearly labeled Opt1+Opt2 approximation and remains `NOT_CERTIFIED`; the WISE paper's exact optimized implementation is not claimed.

## Variants and compatibility

| Registry ID | Implementation | Search scope |
| --- | --- | --- |
| `iscope` | Historical alias for the pre-existing `IScopeAlgorithm` | Unpruned, vertex-simple enumeration; old behavior preserved. |
| `iscope-basic` | Same basic implementation under an explicit name | Unpruned, vertex-simple enumeration. |
| `iscope-opt1` | `IScopePartialLabelAlgorithm` with rational prefix functions and conservative budget pruning | Globally certified only after exhaustive uncapped search. |
| `iscope-optimized` | Opt1 plus breakpoint merging and roulette score adaptation | Approximate search; never globally certified. |

The basic search code remains in `IScopeAlgorithm`; its legacy `iscope` registry ID is not redirected. All new variants use `ExactPreferenceObjective.evaluateIncrementally` for final replay and envelope commitment. Only complete exact route profiles enter that envelope. The final envelope is authoritative under `objective_result.profile`, schema `exact-preference-envelope-v1`.

## Partial path labels

`ExactPartialPathLabel` stores the current vertex, directed arc sequence, visited vertices, valid source-departure support, exact prefix-arrival function, exact prefix-score function, feasible departure subdomains, and a marker indicating whether Opt2 has approximated either function. Prefix score is the partial preference function. Each edge extension rejects a repeated vertex, takes the exact preimage of the edge's temporal support, pulls edge-entry score steps back through the prefix arrival map, adds the score profile, composes the edge arrival map, and intersects support. Rational roots, interval ownership, disconnected domains, score steps, and budget equality are kept by the exact temporal primitives; there is no point-departure sampling.

The implementation is single-worker and deterministic for a fixed query/config seed. It reports DFS states, outgoing edges examined, created labels, function-cell/piece propagation, pruned domain components, completed destination paths, exact function operations, lower-bound preprocessing work, estimated live-label payload, observed JVM heap, search timing, finalization timing, end-to-end elapsed time, and deadline overrun.

## Five-second policy and result handling

Java CLI validation requires `--timeout-seconds 5` for all four IDs. Search yields at 4.5 seconds, reserving 0.5 seconds for exact replay and envelope commitment (the general policy reserves up to one second for a five-second limit). The monotonic deadline is checked during reverse lower-bound preparation, DFS expansion, edge extension, budget-domain construction, Opt2 clustering, exact route replay, and exact envelope construction. A completed route is the smallest search item emitted; cancelled partial functions are discarded. Incremental exact evaluation commits a route only after replay and the resulting envelope update both complete.

A cap yields `TIME_CAPPED_NOT_CERTIFIED`. Opt1's `GLOBAL_CERTIFIED` requires exhaustive DFS and completed exact evaluation. Opt2 is always `NOT_CERTIFIED`, including when its approximate search exhausts. Completing over the retained route registry is not a graph-wide completeness claim. The result records elapsed nanoseconds and overrun explicitly. The runner allows a watchdog grace for cooperative finalization, which does not extend the algorithm's five-second search window.

## Compact configuration

Only the prospective `icde2027_compact_v1` configuration was updated. C01 names `iscope-optimized` as the primary iSCOPE method and includes `iscope-basic` as a separate ablation, preserving 50 logical queries per NY/FLA/CAL dataset and two measured trials. C02 names `iscope-basic` explicitly. The per-method policy keeps PACE-B and THop unlimited, keeps all iSCOPE IDs at five seconds, and leaves SICSP-Pref in the controlled-only track. Matrix construction is a planning check; M5-R4 dispatches no full-network or controlled jobs. Existing query records and historical configurations were not edited. The compact config remains unapproved pending the repository's freeze and execution gates.

## Differential evidence and measured fixture

`IScopePartialLabelOracleTest` compares complete rational cells against `TinyContinuousEnvelopeOracle`: event points, open intervals, exact selected arc IDs, score, arrival function, budget feasibility, and NO_PATH cells. Basic and Opt1 retained route registries and global envelopes agree on the tested exhaustive graphs. The corpus covers score switches, right-continuous endpoints, exact budget equality, the `2/3` and `22/3` budget roots, disconnected feasible support, the existing `20/3` fractional-root fixture, a FIFO plateau, directed asymmetry, no route, a nested 11-edge path, and cycle rejection. The Opt1 pruning test also includes a disconnected branching subgraph.

On that one deterministic branching fixture, basic examined **36** outgoing edges and Opt1 examined **6** (**83.3% fewer**); Opt1 safely removed two temporal support components. One diagnostic run measured:

| Metric | Basic | Opt1 |
| --- | ---: | ---: |
| DFS outgoing edges | 36 | 6 |
| Search phase | 1.528 ms | 0.601 ms |
| Exact replay/envelope finalization | 1.248 ms | 1.232 ms |
| Total algorithm elapsed | 3.266 ms | 1.956 ms |
| Estimated one-label payload | — | 656 bytes |
| Observed JVM heap while the test process ran | — | 119,645,776 bytes |

This is a structural test fixture and a single run in a Maven test JVM, not a road-network speedup or memory claim. The heap sample includes unrelated test-process allocations; the estimated label payload describes one live label, not total retained memory. These measurements validate instrumentation and illustrate the fixture only.

Opt2 tests assert deterministic output for the same query seed, observed breakpoint removal and roulette draws, reduced function-piece count on the fixture, and a measured local arrival interpolation error of one minute. The production metric is the maximum exact error observed at the removed breakpoints for each merge operation. It is not a bound on a full route after later composition.

## Remaining limits

The WISE paper's Opt1 bound uses Euclidean distance divided by a network maximum speed. The repository does not establish compatible coordinate units or a safe network-wide maximum speed, so this implementation uses an exact reverse shortest path over per-edge minimum rational travel times; the proof and scope are in [the Opt1 audit](ISCOPE_OPT1_PRUNING_PROOF_AND_TESTS.md). This is conservative but may prune less. The paper does not specify an implementable breakpoint threshold; Opt2 uses a declared two-minute default and an explicitly labeled left-to-right pairwise roulette adaptation. The source paper studies a discrete departure interval; this repository propagates continuous canonical-decimal rational profiles. No approximation-quality guarantee is invented. Exact final replay repairs values for retained paths but cannot restore paths removed by approximate search.

M5-R3's reported foundation totals were 364 Java and 57 Python tests on the baseline commit. On M5-R4, `mvn -B -ntp clean test` passed **373 Java tests** (0 failures, errors, or skips), and `python3 -m pytest -q experiments/tests` passed **58 Python tests**. `git diff --check` also passed. The local M5-R4 commit identity and exact file list are reported in the completion summary; the pre-existing user edit to `EXACT_PREFERENCE_TEMPORAL_API.md` is excluded from that commit.
