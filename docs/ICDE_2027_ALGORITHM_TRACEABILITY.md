# ICDE 2027 Algorithm Traceability

Audit revision: 508a1d8cc422fd4595ad36857e51990fd86c562e on feature/icde27-baseline-implementation.
Classification: **NOT READY**; see the full repository audit for findings and acceptance gates.

## M5-R superseding status (2026-10-16)

### M5-R4 iSCOPE update (2026-10-17)

M5-R4 preserves the existing basic search under `iscope` (historical alias) and explicit `iscope-basic`, and adds `iscope-opt1` plus `iscope-optimized`. Opt1 propagates exact rational prefix arrival/score functions and applies a conservative exact reverse shortest-path bound over per-edge minimum travel times. It passed exhaustive tiny-graph whole-cell oracle checks. Since coordinate units and a safe maximum road speed are not established, the paper's Euclidean/maxSpeed bound is disabled. Opt2 is an explicitly approximate breakpoint/roulette adaptation, uses a two-minute configured threshold, exact-replays only retained routes, and remains `NOT_CERTIFIED`. The prospective compact primary selects `iscope-optimized` after this tiny-graph implementation validation and keeps basic as an ablation. No benchmark jobs were dispatched. See [M5-R4 report](repairs/ISCOPE_OPTIMIZED_IMPLEMENTATION.md) and the paper/Opt1/Opt2 audits linked there.

PACE-X's contradictory incomplete-empty-frontier result is repaired and covered by `PaceCompletionInvariantTest`; its certification remains conditional on the reviewed completeness conditions. PACE-B search is unchanged and its bounded candidate scope remains explicit. The full iSCOPE publication is now available locally and was read; the current one-thread continuous-profile implementation is a material deviation from the paper's optimized partial-function, temporal-splitting, breakpoint-merging, randomized, parallel method. Native SICSP's original weight objective and candidate search remain unchanged and now have a separate CLI adapter. SICSP-Pref remains numerically unresolved: independent tests show wrong score/winner and false budget feasibility, so its result scope is `NOT_CERTIFIED`. THop remains exact-rational for its fastest-profile objective, with its search-first then prune/materialize schedule disclosed as a paper deviation. See [M5 repair report](ICDE_2027_M5_REPAIR_REPORT.md) for gate-level evidence.

## Source materials

| Method | Paper/specification | Availability and scope |
|---|---|---|
| PACE | Tracked manuscript PACE.tex, especially query model and algorithm sections | Source of the proposed objective, candidate construction, certificate and experimental claims; claims are checked against current code and result provenance. |
| iSCOPE | Dutta and Gunturi, WISE 2025, “Interval Based Constrained Path Optimization in Time-Dependent Road Networks,” DOI 10.1007/978-981-95-7251-9_12 | Full 12-page paper read from `Baseline papar/WISE 2025.pdf` during M5-R; SHA-256 `50016f19605f7e32dc6de9c3a5e2e684e5131937497ca7d5f451057fecdceffc`. The earlier M5 audit's missing-paper note describes its audit-time state. Fidelity is now reviewed and explicitly classified as DEVIATION. |
| Native SICSP / ICSP | Yuan et al., PVLDB 12(10), 2019, pp. 1058–1070, DOI 10.14778/3339490.3339491 | Local source PDF p1058-yuan.pdf was read. The paper specifies time-dependent edge travel, piecewise-constant edge weight, bounded earliest arrival and the O-point heuristic. |
| THop | Li, Wang and Zhou, ICDE 2019, DOI 10.1109/ICDE.2019.00085 | Local Time-Dependent_Hop_Labeling_on_Road_Network.pdf and the M2/M2C implementation audits were read. |

## PACE-X

| Contract / manuscript statement | Current implementation | Audit evidence and scope |
|---|---|---|
| Query returns a preference-maximizing route profile over a continuous departure interval, subject to travel-time budget | QuerySpec, ExactPathProfileBuilder, CandidateProfile, EnvelopeExtractor and PACE | Path profiles are restricted by budget support and replayed through edge functions. Score is evaluated at actual edge-entry time. Legacy time values are canonicalized at 12 decimal places. |
| Routes must be vertex-simple and traversal is no-wait | Path/profile validation and loopless candidate generation in core/pcmax | Public API and oracle tests check route feasibility, looplessness and entry-time replay on tractable cases. No waiting is inserted to align a prefix with a breakpoint event. |
| PACE-X exhaustively certifies only under stated sufficient conditions | ForwardLayeredFrontierGenerator.exactness(): no cap, selected pivots equal score-relevant pivots, and theta at least selected-pivot count | The certificate is conditional. PACE-X naming alone does not imply global exactness. The tiny continuous oracle independently enumerates all vertex-simple paths; fixed-seed corpus size is 1,000 DAGs. |
| Empty output means no feasible path only after global search | M5-R `PaceGenerationOutcome` resolves completion and exactness together from cap, emptiness, and pivot/depth coverage facts | `PaceCompletionInvariantTest` confirms that an incomplete empty frontier is `UNRESOLVED_NO_GENERATED_CANDIDATE / NOT_CERTIFIED`; a global empty result still requires complete coverage. The original P1-A throw is fixed. |
| Endpoint and breakpoint ownership | Domain interval algebra, piecewise travel and score functions, envelope/profile classes | Tests cover open/closed endpoints, singleton intervals, score steps, ties and crossings. The model is the repository's canonical 12-decimal semantics, not arbitrary real arithmetic. |

The 1,000-case PACE-X differential corpus uses fixed seed 0x50414345, FIFO DAGs with parallel arcs, and an independent rational simple-route oracle. The oracle independently evaluates edge travel and edge-entry scores and forms the candidate envelope. It is not derived from PACE composition or pruning. This is strong tiny-instance evidence, not a proof for arbitrary graph sizes or for agreement with arbitrary-real arithmetic.

## PACE-B

| Stage | Code path / contract | Traceability |
|---|---|---|
| Query region | Query corridor and lower-bound feasibility checks | Corridor property test verifies every arc on enumerated lower-bound-feasible simple paths survives the reduction on tiny graphs. |
| Pivot selection | PivotIndex and PACE options L/theta | Bounded policy selects a limited score-relevant pivot set. Omitted pivots make candidate coverage incomplete. |
| Connector generation | BoundedConnectorGenerator | Deterministic FAST, SCORE, DIVERSITY, FAST schedule. Selected pivots are excluded; unselected score edges remain in connector graph. Connector path simplicity respects prefix visited vertices/edges. |
| Stitching/replay | ForwardLayeredFrontierGenerator and CanonicalPathProfileBuilder | Layered partial-state expansion, loopless path descriptors, canonical replay using the original temporal edge functions, residual budget support. |
| Retention/caps | PaceWorkLedger, PaceCapStatus and frontier limits | Cap-truncated results are not globally certified. A single-fastest-connector ablation is explicitly NOT_CERTIFIED; bounded retained mode is scoped to the retained frontier. |
| Final selection | Envelope/profile operations | Profile is optimal only over candidates actually generated and retained. No global PACE-B optimality claim is supported. |

The tracked manuscript claims safety of corridor reduction, residual-budget pruning, connector composition and envelope extraction over a fixed candidate registry. M5 tests provide small-case evidence for these contracts. Large-network claims in the manuscript remain untraceable to the audited code revision because local final results have dirty older source provenance (P1-C).

## iSCOPE

| Stage | Source behavior | Audit |
|---|---|---|
| Registration | AlgorithmRegistry ID iscope | Registered and invoked by the generic runner. BenchOptions enforces the five-second protocol. |
| Preparation | LowerBoundGraph precomputation | Prepared once for a graph object; query-specific fallback observes the deadline. |
| Candidate search | IScopeAlgorithm uses SimplePathSearch.exhaustiveStreaming over vertex-simple source/destination paths | This is an anytime exhaustive enumeration adaptation. It does not add preference-guided graph search. |
| Temporal profile | ExactPathProfileBuilder builds budget-aware route profiles; committed envelope incorporates fully profiled paths | Edge scores are read at true edge entry times. In-progress routes are not committed. |
| Stop/status | Query deadline and path cap reported as TIME_CAPPED_NOT_CERTIFIED or PATH_CAPPED_NOT_CERTIFIED, scope NOT_CERTIFIED | Search exhaustion without caps is globally certified for this implementation's candidate space (all simple paths). |
| Independent validation | IScopeAlgorithmTest now has exhaustiveTinyQueryMatchesIndependentContinuousOracle | Added in M5 because existing tests lacked an independent continuous-profile comparison. It checks feasibility and path identity at oracle endpoints and midpoints. |

The full WISE 2025 paper is now available locally and was read in M5-R. The current implementation is a material deviation: deterministic, single-threaded vertex-simple route DFS with continuous profile adaptation, rather than the paper's optimized partial-function recursion, temporal splitting, breakpoint merging/randomized choice, and parallel work stealing. `IScopeWholeProfileOracleTest` now compares complete cells, scores, routes, affine arrival functions and endpoint ownership on supported tiny fixtures. Those fixtures do not clear the separate fractional-root shared arithmetic counterexample; see [ISCOPE_FIDELITY_AND_ORACLE.md](repairs/ISCOPE_FIDELITY_AND_ORACLE.md).

## Native SICSP / ICSP

### Paper-to-code map

| Paper element | Current code | Finding |
|---|---|---|
| Travel-time function is FIFO and piecewise linear; edge weight is accumulated at edge entry | SicspNativeGraph, SicspEdgeWeightFn and native replay | Supported for validated input domains; native weight is not PACE travel-time budget. |
| For each travel breakpoint, reverse latest-start-time (LST) and forward earliest-arrival-time (EAT) searches identify an event path | SicspNativeEngine event loop, LST/EAT helpers, generateBreakpointCandidate | Follows the paper's single witness construction. Does not enumerate every path prefix/suffix at each event. |
| Reconstruct source-prefix + event edge + destination-suffix | SicspNativeEngine candidate construction | Candidate diagnostics retain mismatches and repeated-vertex witnesses. Native behavior does not impose the oracle's simple-path condition. |
| O-point dominance and path-group interpolation / I-points | SicspDominance, SicspArrivalFunction | Algorithm 1 has explicit strict dominance. Tests compare event values and retained profiles. |
| Weight breakpoints and W-point skyline | Native engine's secondary event stream and SicspWeightCandidates | The paper omits ICSP_Wgh pseudocode. Implementation analogizes the travel-time event construction to every native weight breakpoint. |
| Earliest-arrival answer under native weight bound | SicspNativeResult selection | Selects within its candidate/retained function set. Paper calls candidate generation heuristic; no global completeness guarantee. |

### Inferences required by the paper

The following details are not fully specified in the ICSP_Wgh prose/pseudocode and are recorded as implementation inferences, not paper-established facts:

1. Reuse the Algorithm 1 edge-event construction for each weight breakpoint, with the native weight value replacing travel-time event value.
2. Apply a two-component skyline at equal source departure: earlier arrival and lower accumulated weight are weakly better, with at least one strict improvement; exact ties remain tied and deterministic.
3. Connect equal-weight W-points only when they belong to the same full route, yielding a step profile over that route's departure support.
4. Preserve the route-specific native arrival function for query selection and diagnostics when the paper's interpolated O-point coverage is partial.
5. Keep repeated-vertex concatenated witnesses in native SICSP because the paper does not impose vertex simplicity; the independent oracle's simple-path restriction is used for constrained preference adaptation and comparison, not injected into native behavior.

The native independent oracle enumerates simple paths and independently replays time/weight. It documents a feasible path [2,3,6,7] with weight 0 under bound 1 and arrival 10 that the event heuristic misses. This is a known method limitation and must be disclosed; it is not a failed test.

## SICSP-Pref adaptation

| Native SICSP behavior | Preference adaptation | Resulting scope |
|---|---|---|
| Native accumulated edge weight is the constrained metric | PACE travel-time budget is the feasibility rule; preference score is maximized | Different query objective; native SICSP results cannot be used as preference results. |
| Native W events come from weight breakpoints | Same generator receives score-function breakpoints as secondary events | Event source changes; LST/EAT search mechanism and witness generation are shared, with no preference-directed graph search. |
| Native objective minimizes arrival under weight bound | Score is evaluated at actual no-wait edge-entry times; route travel-time profile is checked against budget | Retained route candidates are reprofiled for the preference query. |
| Native may yield repeated-vertex route witnesses | Adaptation filters vertex-simple routes after generation | Some raw native candidates are intentionally removed. |
| Native candidate output and O/W diagnostics | Adaptation separately records raw, simple, in-query, retained and feasible route registries | Global oracle, raw generated set and dominance-retained set remain distinct. |

M5-R2 supersedes the preceding historical SICSP-Pref paragraph. Final route evaluation now uses `EXACT_RATIONAL_PREFERENCE_PROFILE-v1`: exact edge-entry replay, score preimages, travel-budget domains, and a complete envelope over the generated, simple, dominance-retained route registry. Independent rational whole-cell checks validate the retained registry and separately confirm that the global best route in the heuristic-miss fixture was not generated. The adapter claims `RETAINED_FRONTIER` only for a nonempty exact retained envelope; it never claims `GLOBAL_CERTIFIED`. The native candidate generator is unchanged. Exact event-level replay can change preference-adapted O/W dominance survivors, while route generation remains under native SICSP's legacy canonical-decimal contract. See [SICSP_PREF_EXACT_NUMERICAL_REPAIR.md](repairs/SICSP_PREF_EXACT_NUMERICAL_REPAIR.md) and [EXACT_PREFERENCE_TEMPORAL_API.md](repairs/EXACT_PREFERENCE_TEMPORAL_API.md).

## THop

| Paper operation | Current implementation | Evidence and deviation |
|---|---|---|
| Exact functional fastest path | FifoFastestPathProfileSearch and exact profile types | Direct search checked against the independent rational simple-route reference. |
| Unpruned/pruned 2-hop labels and hub ordering | THopIndex and PrunedFifoProfileSearch | Tiny all-pairs comparison and pruning tests. Exact profile operations used internally. |
| Boundary metadata and two-phase boundary labels | GraphPartitionMetadata and BoundaryTHopIndex | Directed boundary arcs and exact shortcut profiles; BTHP v2 serialization with graph identity/checksum. |
| Inner-partition labels and cross/same-partition queries | PartitionedTHopIndex | Uses original directed internal arcs plus boundary shortcuts, allowing an optimal route to leave/reenter a partition. Tests compare all pairs on tiny partitions. |
| Section V-C search/prune construction | PartitionedTHopIndex currently searches first, then prunes/materializes labels | This differs from paper's incremental search-prune schedule. It may affect work and memory; the source report documents the difference, and no claim of identical construction bounds is made. |
| Exact rational profile storage | ExactTime, ExactTemporalDomain, ExactAffineSegment, ExactArrivalProfile, ExactProfileEnvelope | Rational arithmetic follows canonicalized input. Exact APIs are authoritative; rounded compatibility projections must not be used for exact comparisons. |
| Versioned reload | BTHP and PTHP magic/version checks, format version 2 | Version 2 stores rational coefficients and endpoint ownership. Version 1 is rejected; reload and checksum tests are present. |

The full-pairs THop oracle has its own rational arithmetic, edge interpolation, composition, support preimages, crossings and lower-envelope code. It enumerates vertex-simple routes rather than using THop operations to form expected results. Tests compare direct search, unpruned/pruned index, boundary index and partitioned index for the relevant tiny all-pairs domains.

THop optimizes travel-time arrival profiles. It has no preference score objective and does not apply PACE's travel-time budget; comparisons must remain objective-specific.

## Numerical model comparison

| Model | Arithmetic scope | Meaning of exact |
|---|---|---|
| PACE shared model | 12-decimal canonical times; legacy per-operation function evaluation and arrival addition | Exact only relative to the declared finite-tick operations and endpoint rules, not arbitrary real-valued temporal functions. |
| SICSP / SICSP-Pref | Legacy LST/EAT and scalar edge-entry replay; SICSP-Pref also composes continuous PWL profiles | SICSP-Pref scalar/profile mismatch is demonstrated; its experiment result scope is `NOT_CERTIFIED`. Native SICSP retains its original heuristic weight objective. |
| THop | Input graph canonicalization followed by rational composition, restriction, preimages, envelopes and storage | Exact over decimal-canonical input coefficients; not exact relative to any higher precision values discarded at input conversion. |

No exactness result transfers between these rows.
