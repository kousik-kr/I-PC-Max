# iSCOPE specification, complete-profile oracle, and accounting

Date: 2026-10-16 UTC. Milestone: M5-R, R3.

## Disposition

The full publication is available locally and has been read in full. Source-fidelity review is no longer blocked by a missing paper. The existing implementation is preserved and classified **DEVIATION** from the optimized iSCOPE used in the paper's experiments. It is a deterministic, single-threaded continuous-profile adaptation of the paper's basic simple-path recursion. It must not be described as a reproduction of the paper's optimized, parallel implementation.

Independent structural oracle coverage has been expanded to compare the complete represented profile, including every endpoint, constant score, selected route, affine arrival coefficient, travel time, and NO_PATH cell. The supported fixtures pass. A separate fractional-root fixture establishes that iSCOPE inherits the shared legacy profile/replay disagreement. That limitation remains unresolved; passing the positive oracle corpus does not establish exact real-time arithmetic for arbitrary inputs.

Production changes in this phase add truthful variant, numerical-model, objective, and measured deadline metadata. Candidate enumeration, lower-bound searches, profiling, envelope selection, and existing cap/status behavior are preserved.

## Publication identity and availability

Kousik Kumar Dutta and Venkata M. V. Gunturi, *Interval Based Constrained Path Optimization in Time-Dependent Road Networks*, WISE 2025, LNCS 16368, pp. 165–176, published 2026. DOI: [10.1007/978-981-95-7251-9_12](https://doi.org/10.1007/978-981-95-7251-9_12).

Local authorized source: `Baseline papar/WISE 2025.pdf`, 12 pages, 882,873 bytes. SHA-256:

    50016f19605f7e32dc6de9c3a5e2e684e5131937497ca7d5f451057fecdceffc

The audit used `pdftotext -layout` to read the complete paper, including problem definition, the basic algorithm, both optimizations, experimental setup/results, and references. The PDF was preserved. Earlier M5 reports correctly describe its absence at that audit time; the M5-R report supersedes that availability limitation.

## Paper-to-code mapping

| Paper stage or parameter | Full-paper specification | Current implementation and classification |
|---|---|---|
| Graph model, pp.167–168 | Directed graph; continuous nondecreasing piecewise-linear arrival functions, piecewise-constant scores, FIFO; simple no-loop routes; edge score at edge-entry time | Same structural objective and input families through TDGraph and legacy temporal profiles. Numerical representation differs from unrestricted real arithmetic. |
| Query budget, §2.1 | Budget is fastest travel time over the entire departure interval plus a positive overhead | QuerySpec receives an absolute maximum travel time. A shared explicit absolute budget is a profile-comparison adaptation, not the original overhead input convention. |
| Departure/output, §2.1 | Discrete departure interval; return one best path/departure pair over the interval | Returns a continuous departure-to-route envelope. This is an output/objective adaptation required by the PACE comparison. |
| Basic recursion, §3.1 | A label contains current node, partial arrival and score functions, and predecessor history. Extend each unvisited neighbor recursively | SimplePathSearch.exhaustiveStreaming recursively explores vertex-simple paths. Path history and loop prevention are preserved. Partial labels contain arcs and static lower-bound weight; temporal functions are constructed only when a full destination route is visited. This changes work scheduling. |
| Destination retrieval, pp.168–169 | Collect destination labels, inspect score breakpoints, and select the best feasible interval solution | Each completed path is replayed; a score-maximizing feasible envelope is committed incrementally. Only completed route profiles enter the result. |
| Opt1 split functions, pp.169–170 | At every partial label, discard departure subranges exceeding the residual budget bound based on Euclidean distance/maxSpeed | Static minimum-edge-weight lower bounds prune entire DFS branches. Completed route replay restricts feasible temporal support. It does not split and propagate partial temporal label functions. This optimization is absent. |
| Opt2 merging, pp.170–171 | Cluster nearby breakpoints, retain boundary points, approximate arrival functions; use roulette-wheel score selection | No breakpoint clustering, approximate arrival replacement, or randomized score assignment. This optimization is absent. |
| Approximation, pp.170–171,174 | Merging can under/overestimate arrival and change allowed travel by δ; observed errors reach 1–5 minutes. Subsequent experiments call the both-optimization variant iSCOPE | Current variant does not intentionally accept the paper's merging error. It still uses the legacy 12-decimal profile representation, which has a demonstrated fractional-root limit. Omitting merging changes runtime/quality and does not reproduce the paper's optimized baseline. |
| Parallelism, §4, Fig.5 | Work-stealing scheduler; main reported comparison uses 48 threads; a separate comparison uses single-core iSCOPE | Current algorithm uses one deterministic DFS worker. Its lower-bound witness is committed before DFS. Neither work stealing nor the main paper thread allocation is reproduced. |
| Cap | The paper evaluates capped anytime search, including five seconds | Default five seconds; CLI enforces `--timeout-seconds 5`; a 10% finalization reserve, up to one second, yields exploration before the deadline. This scheduling policy is an adaptation. |
| Complexity, p.171 | Exponential time/space analysis for partial-function recursion; proof omitted for space | Its formula and measured speedups are not asserted for this different construction schedule, retained-envelope strategy, and thread count. |
| Ties | No detailed total-order rule for equal score/arrival solutions | Repository order is score descending, travel time ascending, edge count ascending, stable arc IDs ascending. This is an explicitly deterministic adaptation. |

The paper does not fully specify a reproducible breakpoint clustering threshold, every stochastic clustering/selection detail, or an experimental random seed. Its empirical error range is not a proof that a fixed δ budget relaxation holds for every input. Porting Opt2 would require an explicit approximate baseline mode, declared randomness, permitted budget relaxation, and a separate experiment protocol. That is outside the authorized search-preserving repair.

## Independent whole-profile validation

`TinyContinuousEnvelopeOracle` independently enumerates vertex-simple routes and performs rational edge interpolation, edge-entry score evaluation, event preimages, budget roots, and travel-time tie roots. It does not call production TimeProfile composition, ExactPathProfileBuilder, EnvelopeExtractor, or iSCOPE searches to construct expected values.

An additive `solveCells` API now exposes unmerged temporal cells. Every event has a separately owned singleton point cell; each intervening cell is open at both ends. A feasible cell carries the selected route, constant integer score, and exact rational slope/intercept of its arrival function. NO_PATH cells carry no route/value. Existing `solve` callers retain their previous path-only API and behavior.

`IScopeWholeProfileOracleTest` common-refines all independent oracle cuts with all output envelope, arrival, score, and candidate-domain cuts. It checks unique coverage/endpoint ownership at every point and open cell. On every open cell it compares route, score, arrival slope, and arrival intercept as exact fractions. Equality of arrival functions establishes equality of travel functions `arrival(t)-t` over that entire cell. Midpoints identify the already piecewise-defined cell; expected values are not inferred from sampled production outputs. No tolerance is used for the structural comparisons.

The suite covers:

- Right-continuous score discontinuities, including score changes at the final endpoint and singleton queries.
- Exact budget equality, interior budget roots, separated feasible regions, explicit NO_PATH cells, and a FIFO arrival plateau.
- Equal-score travel crossings, equal arrival ties, edge-count ties, and stable arc-ID ties.
- Three-edge temporal composition and multiple score-step preimages evaluated at actual edge-entry times.
- Multiple competing paths, directed asymmetry, disconnected destinations, and over-budget-only routes.
- Forty fixed-seed tiny FIFO DAGs (`0x4953434f5045`) with competing routes and score discontinuities.
- Complete exhaustive execution, capped execution, and repeated queries after prepared lower-bound weights.

For capped output, `solveCellsForPaths` independently validates the envelope over the selected output route registry. It checks route continuity and vertex simplicity itself and independently replays each route. This proves validity/envelope consistency for the returned registry; it does not assert that the capped run found every graph path or every route it might have found with more time.

## Caps, atomic commitment, and preparation accounting

`QueryDeadline` supplies a monotonic cancellation predicate to lower-bound graph creation, reverse lower-bound Dijkstra, DFS, path profiling, and envelope extraction. DFS checks at entry and before edge expansion. Path replay checks within temporal construction. If profiling or envelope extraction is cancelled, only the previously committed envelope survives. Existing cancellation regressions and the new capped whole-profile check verify this behavior.

Statuses retain their distinct meanings:

| State | Status | Scope |
|---|---|---|
| Exhausted uncapped search, at least one feasible route | CERTIFIED_COMPLETE | GLOBAL_CERTIFIED under the implementation's declared model; the numeric limitation below prevents a general real-time exactness claim |
| Exhausted uncapped search, no feasible route | NO_FEASIBLE_PATH | GLOBAL_CERTIFIED under the implementation's declared model |
| Query deadline reached | TIME_CAPPED_NOT_CERTIFIED | NOT_CERTIFIED |
| Path limit reached | PATH_CAPPED_NOT_CERTIFIED | NOT_CERTIFIED |

No completion percentage is invented. Departure coverage measures where a returned feasible route exists; it is not a percentage of the complete search. A capped output can happen to cover the entire interval and still remains NOT_CERTIFIED.

Query-independent lower-bound edge weights are built by `prepare`. PaceBench calls `prepare` before its query timer and includes its wall time in `preprocessing_total`. Target-specific reverse Dijkstra remains inside the query's `lower_bound_preprocessing` phase. Direct API calls without `prepare` also construct weights inside the deadline and report `query_independent_lower_bound_weights_prepared=false`. Prepared/repeated query tests verify that source distinction and complete output equality. The existing total preprocessing field includes graph loading as well as algorithm preparation; it is not an isolated measurement of only iSCOPE's weights.

The algorithm cap is cooperative, not a real-time scheduling guarantee. PaceBench additionally gives iSCOPE a two-second watchdog finalization grace beyond its five-second algorithm limit; the grace must not be described as additional search time. Post-search statistics and serialization can consume time. Existing timing records retain observed query duration. The adapter now explicitly reports `measured_query_elapsed_ns`, `deadline_overrun`, and `deadline_overrun_ns=max(0,elapsed-limit)` so an observed overrun is visible. The outer runner's end-to-end duration remains authoritative for total process/query overhead.

## Numerical limit: unresolved

The new `fractionalInverseRootDocumentsTheSharedLegacyArithmeticLimit` regression applies the original SICSP-Pref fixture to iSCOPE without changing its search:

| Quantity at departure 6.666666666667 | Observed value |
|---|---:|
| Exact event root | 20/3, distinct from the supplied canonical decimal |
| Legacy scalar first-edge arrival | 20.000000000001 |
| Legacy scalar final arrival | 60.000000000001 |
| iSCOPE composed profile final arrival | 60.0 |

The independently constructed rational oracle includes the true root `20/3`. The positive whole-cell tests intentionally have exactly representable roots and coefficients; they do not clear this separate counterexample. No tolerance, moved breakpoint, test deletion, or global arithmetic rewrite hides it.

Metadata `numerical_contract=LEGACY_CANONICAL_PROFILE_12DP-v1` identifies the represented profile arithmetic. It is not a claim that scalar per-operation replay and profile composition coincide. `GLOBAL_CERTIFIED` remains a search-space/cap label used by the existing implementation, not an independent certificate of arbitrary-real arithmetic. A shared temporal API repair or explicitly reviewed numerical migration is still required before benchmark claims depending on exact boundary arrival/feasibility can pass.

This is a representation issue. Write `q=10^-12`. For a travel function `t/2+10`, scalar evaluation at a canonical departure `n*q` gives an intermediate arrival of `q*round_half_even(3*n/2+10/q)`. Its successive increments depend on the residue of `n`. Composing another rounded affine edge introduces nested rounding; one affine function on the original temporal cell cannot in general represent every canonical-tick value. Over real input, the initial canonicalization itself creates a staircase. A continuous PWL profile cannot equal that discontinuous scalar function at every real departure. Preserving the legacy operation semantics exactly would require a discrete/nested-rounding representation or potentially enormous tick-wise subdivisions, not a tolerance at one root.

An opt-in rational model is mathematically possible and can be added without replacing the search, but the existing rational arrival API alone is insufficient: exact score-step preimages, budget domains, tie-aware envelopes, and output serialization must also be supplied. That mode would deliberately use a different arithmetic contract from legacy PACE/iSCOPE and would require an explicit comparison protocol. This report does not claim that such an additive implementation is mathematically impossible or that the present API limitation has repaired the numerical discrepancy.

## Changed files and validation evidence

- `src/main/java/edu/ipcmax/experiments/algorithms/IScopeAlgorithm.java`: documentation and output metadata only; explicit optimized-paper deviation, objective, temporal contract, thread count, omitted optimization flags, and measured deadline overrun.
- `src/test/java/edu/ipcmax/experiments/algorithms/IScopeAlgorithmTest.java`: deterministic missing-accounting regression and five-second-default test; all prior tests preserved.
- `src/test/java/edu/ipcmax/experiments/algorithms/IScopeWholeProfileOracleTest.java`: structural cell comparisons, cap/preparation cases, and documented fractional-root limitation.
- `src/test/java/edu/ipcmax/testoracle/TinyContinuousEnvelopeOracle.java`: additive complete-cell and restricted-registry APIs; previous path-only solve semantics unchanged.

Before the metadata repair, the deterministic regression failed because measured elapsed/overrun metadata was absent: expected measured elapsed `60`, actual `null`. The whole-cell positive suite passed against unchanged search. After the metadata repair, this focused command passed:

    mvn -B -ntp -Dtest=IScopeAlgorithmTest,IScopeWholeProfileOracleTest test

Final focused run, including the explicit numerical-limit regression: **20 tests, 0 failures/errors/skips, BUILD SUCCESS**, 4.652 s, 2026-10-16T01:57:25Z. Log: `/tmp/m5r-r3-final.log`. The original metadata regression red log is `/tmp/m5r-r3-red.log`. `git diff --check` passed for these source/test files.

## Gate consequence

Full-text availability and source-level fidelity review are complete. Faithful reproduction of the optimized paper variant is **not passed**; it is an explicitly labeled deviation. Whole-profile correctness has strong independent tiny-fixture evidence and honest capped statuses, but the shared fractional-root numerical discrepancy remains. G3 cannot be presented as an unconditional pass for arbitrary continuous inputs. Preserve iSCOPE in future preference matrices under its actual variant, numerical contract, one-thread allocation, five-second cap, and source/JAR/config identities.
