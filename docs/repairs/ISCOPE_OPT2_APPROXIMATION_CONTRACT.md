# M5-R4: Opt2 approximate-search contract

## Search representation

Opt2 changes only partial functions used during search. At each label extension it gathers the exact rational cut points from the supported component boundaries, prefix-arrival segments, and prefix-score segments. A deterministic left-to-right scan forms clusters of at least three cuts whose total span is no greater than the configured threshold. The default threshold is **2 minutes**. The first and last cut remain; interior cuts are removed. Support-component endpoints are always retained.

On each merged arrival span `[t1,t2]`, the search representation is the line through the original exact endpoint arrivals `(t1,a1)` and `(t2,a2)`. The local maximum error metric evaluates the absolute interpolation error at every removed event point; because the source arrival is piecewise linear, these breakpoints include the maxima of the absolute error over the merged span. The metric is exact for each local merge but is not a whole-route error bound after further edge composition.

For a merged score span, the code starts with the score on the first open cell and folds each interior-cell score from left to right. Each pair uses a uniform integer draw `r ∈ {0,...,100}`. For current score `s1` and incoming score `sin`, it selects `s1` exactly when `r(s1+sin) <= 100s1`; otherwise it selects `sin`. This is the paper's inclusive `r <= P1` test implemented with integer arithmetic. If both scores are zero, the ratio is undefined in the paper; the adaptation uses current for draws 0–50 and incoming for 51–100.

Each query's `SplittableRandom` seed is derived deterministically from the configured seed, source/destination, canonical departure interval, and canonical budget. Output includes the effective seed, threshold, roulette rule, random draw range, merge/draw totals, before/after function-piece totals, observed local maximum arrival error, and the explicit marker that exact final replay does not restore removed candidates.

## Exact result boundary

Approximate prefix functions may change support and budget pruning, which may remove routes that the exact search would keep. They may also fail to remove a route that exact replay later rejects. At a destination, only the directed route identity is emitted. `ExactPreferenceObjective.evaluateIncrementally` then replays that full route against the original edge functions using exact rational arrival and edge-entry score functions, computes its exact feasible departure domain, and commits its exact retained-registry envelope only after the entire replay and envelope update finish.

Therefore:

- Every returned selected cell is exact for its retained route registry: route, budget feasibility, score, arrival, endpoint ownership, and profile checksum are exact under the canonical-decimal rational contract.
- Exact replay cannot reinsert candidates omitted by Opt2.
- `iscope-optimized` always returns `ExactnessScope.NOT_CERTIFIED`, even when its approximate DFS exhausts.
- It does not return a global `NO_FEASIBLE_PATH` claim when no approximate candidate survives; it reports unresolved/no generated candidate.
- No quality or δ guarantee is asserted. The paper's reported 1–5 minute observations are empirical properties of its datasets, not a universal bound for this implementation.

## Paper details and declared deviations

The paper names nearby clustering and gives its interpolation and roulette descriptions, but does not provide a numeric threshold or enough pseudocode to uniquely reproduce cluster formation and repeated score merges. This code chooses and freezes a two-minute maximum span, greedy left-to-right grouping, and sequential pairwise roulette, and labels these choices as an adaptation. The paper's underlying departure interval is discrete; this repository's exact search profile is continuous. The paper also uses Euclidean distance divided by maximum speed for Opt1, while this code uses the separately proved exact minimum-edge lower bound because coordinate units/speed validity are not established. The main paper reports work stealing; this version uses one worker.

## Differential evidence

The deterministic Opt2 fixture creates a cluster with an exact local arrival interpolation error of one minute, removes at least one breakpoint, reduces represented function pieces, and performs at least one roulette draw. Repeating the query with the same seed yields the same profile checksum and candidate registry. The emitted exact envelope matches an independent whole-cell rational oracle over `retained_route_registry`, while its scope remains `NOT_CERTIFIED`.

This fixture validates implementation and metadata behavior. It does not show broad quality retention, road-network runtime improvement, or global route completeness. The compact config lists `iscope-optimized` as the prospective primary method after this small differential validation, keeps `iscope-basic` as a separate ablation, preserves the original 50 query IDs and two measured trials, and dispatches no jobs in M5-R4.
