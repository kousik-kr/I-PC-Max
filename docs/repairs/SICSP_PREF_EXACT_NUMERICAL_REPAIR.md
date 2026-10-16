# SICSP-Pref exact numerical repair

Status: P1-B repaired for final route-profile evaluation under `EXACT_RATIONAL_PREFERENCE_PROFILE-v1`. The claim is scoped to SICSP's generated, vertex-simple, dominance-retained, budget-feasible route registry. It is not a global route-optimality claim.

## What changed

`SicspNativeEngine` and its LST/EAT Dijkstra, O/W event loop, event paths, prefix-edge-suffix reconstruction, raw witness provenance and candidate grouping were not modified. `SicspPreferenceEngine` still invokes that generator and applies vertex-simple filtering at the PACE adaptation boundary. Event-level O/W arrival and score comparisons now replay the route at the canonical event departure through exact rational primitives; this can change which preference-adapted event witnesses survive compared with legacy per-operation rounding. It does not change the generated event set. Native SICSP regression tests and native/preference event-signature comparisons pass.

The preference result keeps separate raw routes, simple routes, retained routes, and retained routes with nonempty exact budget-feasible domains. Exact replay and exact envelope construction run after candidate generation. The old `EnvelopeProfile` and `CandidateProfile` are retained for compatibility tests only. The `sicsp-pref` adapter marks `EXACT_RATIONAL_PREFERENCE_PROFILE-v1`, reports `RETAINED_FRONTIER` only when the exact retained envelope has a feasible cell, emits the exact schema/checksum, and does not pass a rounded profile to the experiment runner. Empty generated feasible registries stay `NOT_CERTIFIED` / `UNRESOLVED_NO_GENERATED_CANDIDATE`.

## Repaired counterexamples

| Case | Legacy result | Exact rational result |
|---|---|---|
| Fractional event | Event `20/3` was represented by decimal `6.666666666667`. At that decimal, rational replay gives final arrival `60.0000000000005`; legacy scalar replay gives `60.000000000001`; the composed legacy profile gives `60.0`. | `20/3` and `6.666666666667` remain distinct. Exact replay at `20/3` gives arrival `60`; exact replay at the decimal gives `60.0000000000005`. Full-domain replay and direct singleton replay agree at either same rational departure. |
| Wrong winner | At `6.666666666665`, composed legacy score preimages select the chain with reported score 20. | Direct route wins with score 15 immediately before `20/3`; the chain wins at `20/3` with score 20 under right-continuous score-step ownership. |
| Budget `B=21` | Rounded root admission selects the route at decimal `6.666666666667`, although its actual travel time is `21.000000000001`. | Exact travel time equals 21 at `20/3`, which is feasible. The canonical decimal `6.666666666667` and the adjacent higher decimal are infeasible. No tolerance extends the domain. |
| Boundary/support behavior | Rounded operations could disagree between full-interval and singleton replay. | Exact full-profile restriction and direct singleton replay agree; score steps own their right-continuous boundary; budget equality is included; unsupported pieces remain `NO_PATH`. |

The permanent tests retain the legacy calculations as explicit negative evidence while asserting repaired exact outputs. They also cover FIFO plateaus, nested multi-edge compositions, multiple score changes, exact crossings, ties, disconnected and singleton support, identity routes, unsupported routes, large `BigInteger` scores, deterministic serialization, and 512 score pieces without a cap.

The corrected outputs including temporal ownership are recorded explicitly here:

| Fixture and departure | Arrival / travel time | Score and selected path | Feasibility and ownership |
|---|---|---|---|
| Two-edge root fixture at exact `20/3` | Arrival `60`; travel `160/3` | Score 10; path `[0, 1]` | Feasible under `B=80`. The event is represented as an owned singleton cell; adjacent cells are open at `20/3`. |
| Same fixture at canonical decimal `6.666666666667` | Arrival `60.0000000000005`; travel `106666666666667/2000000000000` (displayed as `53.3333333333335`) | Score 10; path `[0, 1]` | Feasible under `B=80`. This is a distinct rational departure from `20/3`. Full-profile and singleton evaluation agree. |
| Winner fixture at `6.666666666665` | Direct route travel time `70` | Score 15; path `[3]` | Feasible under `B=80`; selected on the open interval before `20/3`. At the owned event singleton `20/3`, the chain `[0,1,2]` wins with score 20. |
| `3t+1`, `B=21`, at exact `20/3` | Arrival `83/3`; travel `21` | Score 7; path `[0]` | Feasible; equality endpoint is included in `[0,20/3]`. |
| Same budget fixture at `6.666666666667` | Exact travel time `21.000000000001` | No selected route; `NO_PATH` | Infeasible; lies outside the exact closed feasible interval. Legacy root rounding had admitted it. |

## Independent evidence and candidate quality

Production replay/envelope classes are not used by `TinyContinuousEnvelopeOracle`. The oracle replays paths edge by edge with a separate rational implementation and partitions entire cells at score, support, travel-budget and tie events. Tests compare path, score and affine arrival across every oracle cell, including singleton event cells. The generated raw-simple, exact retained-feasible, and global simple-path registries are checked separately on the SICSP heuristic-miss fixture.

The exact envelope agrees with the independent oracle over the retained feasible registry. The global simple-path oracle finds the known score-40 route `[2, 3, 6, 7]` at departure 0; SICSP does not generate it. This remains a search-scope miss, not a numerical-envelope defect. Accordingly the adapter reports `candidate_generation_complete=false`, does not report `GLOBAL_CERTIFIED`, and makes no global quality claim.

## Adapter and instrumentation

The authoritative JSON uses schema `exact-preference-envelope-v1`, stores exact rational endpoints and affine values as reduced rational strings, gives endpoint ownership per cell, and includes score, arc IDs, arrival, travel-time relation and budget status. `objective_result.profile` contains the exact object; the compatibility `AlgorithmResult.profile` is null. Recorded metrics include replay, envelope and serialization time; query end-to-end time; exact profile piece count; exact score-event preimages; exact budget-root operations; envelope comparisons; maximum rational component bit length; serialized bytes; and sampled heap. The independent oracle is test-only and its runtime is not added to query time.

Tiny CLI smoke: `experiments/manifests/tiny.jsonl` produces three rows. The two generated-candidate queries complete with retained-frontier scope; the infeasible/absent-candidate query remains `UNRESOLVED_NO_GENERATED_CANDIDATE` and `NOT_CERTIFIED`. The existing Figure 1 native SICSP smoke and native test suite remain unchanged.

## Remaining benchmark blockers

PACE-X, PACE-B and iSCOPE still use legacy temporal profile arithmetic and/or budget pruning. Their results cannot be compared as though all methods use this exact model. Historical P1-C source/JAR lineage is independently unrecovered. Therefore the M5-R2 repair closes the SICSP-Pref numerical defect within its declared retained registry, but preference-track readiness remains false and M6/M7 are not started. The migration matrix lists source-level work required for a common numerical comparison.
