# NYC story validation

Diagnostic inputs are frozen to query manifest `29842bc57033ab93b1dac5790276c886335a3a6f65c2bf97edc475dcddfec347` and official result bundle `c0427dc91d2e7195e847436841f0faf1463311ee2fc4cabb7208c2cada16045d`. The official five-second JSONL was not modified.

## 1. Correctness issues discovered

No query-region, temporal-coordinate, budget-construction, exact replay, or envelope correctness bug was found. All 12,100 fastest witnesses validate, match independent time-dependent labeling, satisfy their budgets, and lie in their safe query regions. The correctness issue is semantic reporting: the three bounded-empty results were labeled `NO_FEASIBLE_PATH` even though feasible paths exist; they are unresolved, non-certified outcomes. A diagnostic-only raw-distance field was also mislabeled as meters and has been corrected in source; geometric analyses use `geometric_length_m`.

## 2. Correctness fixes required

Change the public/result interpretation of an empty PACE-B frontier to `UNRESOLVED/NO_GENERATED_CANDIDATE` (or an equivalent explicitly non-certified status). Do not expose it as infeasibility. No scientific graph or score fix is required, so the original 100-query result bundle was not rerun or replaced.

## 3. Why NO_FEASIBLE_PATH occurred

All three cases fail the selected-pivot representation because each fastest witness contains more than `theta=2` selected pivots. Their earliest classified omission is `PIVOT_DEPTH` for all 363 departure samples. They remain bounded-empty at 5, 10, and 30 seconds, proving that added wall time does not resolve this policy restriction.

| Cause | Loss samples | Compressed cells | Share of loss time |
| --- | --- | --- | --- |
| QUERY_REGION_BUG | 0 | 0 | 0.00% |
| PIVOT_LIMIT | 0 | 0 | 0.00% |
| PIVOT_DEPTH | 423 | 5 | 4.72% |
| CONNECTOR_POLICY | 1033 | 55 | 11.53% |
| KF_RETENTION | 0 | 0 | 0.00% |
| TIME_LIMIT | 7502 | 62 | 83.75% |
| BREAKPOINT/ENVELOPE | 0 | 0 | 0.00% |
| OTHER | 0 | 0 | 0.00% |

## 4. Exact connector semantics

The implementation is `LOWER_BOUND_STATIC_WITNESS`, mode `PACE_B_FASTEST_LOWER_BOUND_WITNESS_V1`. It reuses one global static lower-bound F/B witness, rejects requests not represented by those labels, and replays that path over the temporal domain. It is not a time-dependent fastest connector. In 67 representative production requests, 23 matched and 44 differed from point time-dependent fastest labeling. Only 23 of 306 fastest-profile paths had every connector segment equal to the static witness.

Minimum correction: For each connector request, run exact FIFO time-dependent fastest labeling over the request entry domain (splitting when the fastest structural path changes), while enforcing the corridor, selected-pivot exclusions, visited masks, and residual budget; return that temporal single-fastest connector profile instead of reusing only global static F/B witnesses.

## 5. Full-vs-resolved score analysis

| Group | Resolved coverage | Full DeltaS | Resolved DeltaS | Improve | Equal | Loss | Completed-only DeltaS |
| --- | --- | --- | --- | --- | --- | --- | --- |
| R20 | 36.81% | -58.414 | 3.802 | 51.73% | 26.18% | 22.09% | 3.802 |
| R50 | 32.00% | -63.421 | -1.085 | 42.67% | 30.01% | 27.32% | -1.085 |

Across all 50 queries per rho, the dominant full-interval penalty is unresolved time caused by the five-second cap. Conditional on resolved time, R50 still has a negative score difference, and the completed-only R50 difference is negative. Thus the previously reported R50 loss is not primarily an unresolved-cell accounting artifact; lower-quality bounded candidates are also present.

## 6. Evidence that path choice varies over I

Among 35 official completed profiles, MultiPathRate is 20.00%. Profile cells have median 1.000 and IQR [1.000, 2.000]; distinct paths have median 1.000 and IQR [1.000, 1.000]. Route switches have median 0.000. For fully resolved profiles, ChangeCoverage has median 0.00%. Assignment-cell durations have median 102.000 sampled minutes and IQR [17.000, 121.000]. The unchanged 30-second diagnostic yields 97 resolved profiles and MultiPathRate 19.59%. Path variation is therefore real but concentrated in a nontrivial minority, not typical of the median query.

## 7. Interval-best coverage analysis

“Sufficiently resolved” was predeclared as official `COMPLETE` with at least 95% coverage. For 31 such candidate registries, interval-best path coverage has median 100.00% and IQR [100.00%, 100.00%]. The interval-best path is absent from at least 25%, 50%, and 75% of the window in 16.13%, 16.13%, and 12.90% of queries, respectively. This supports the profile motivation for a minority of queries, not as a universal requirement.

## 8. Score/travel-time trade-off

Across 4163 resolved samples, median DeltaS is 0.000, median DeltaT is 0.184 minutes, score-improvement coverage is 47.51%, and aggregate travel-time premium is 10.679%. Median budget utilization is 68.92%. For samples with added travel, median and aggregate score gain per added minute are 6.447 and 1.844. Negative points are retained in the scatter and CSV.

## 9. PACE-X exact real-data validation

The predeclared subset contains 20 queries. All 20 hit the 60-second external machine-protection cap in connector generation before producing a complete descriptor; certified count is **0**. Therefore PathAgreement, score gap, and ResolvedCoverage against a certified PACE-X optimum are N/A, and the NYC experiment supplies no real-data global-optimality certificate. The non-exact subset’s bounded descriptive PathAgreement is 21.95%, score gap 9.443, and mean resolved coverage 93.76%; these are not exact-validation statistics.

## 10. Effect of the five-second cap

| Cap | Group | Complete | Bounded empty | Timeout | Resolved | Full score | Resolved score | Candidates | Replayed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| official_5s | R20 | 19 | 2 | 29 | 36.81% | 22.012 | 59.799 | 2.000 | N/A |
| official_5s | R50 | 16 | 1 | 33 | 32.00% | 17.005 | 53.140 | 1.765 | N/A |
| diagnostic_10s | R20 | 40 | 2 | 8 | 77.07% | 59.770 | 77.548 | 3.119 | 3.040 |
| diagnostic_10s | R50 | 39 | 1 | 10 | 78.00% | 63.656 | 81.610 | 2.450 | 2.960 |
| diagnostic_30s | R20 | 48 | 2 | 0 | 90.94% | 80.198 | 88.186 | 4.260 | 3.360 |
| diagnostic_30s | R50 | 49 | 1 | 0 | 97.77% | 92.203 | 94.308 | 3.500 | 3.280 |

At 30 seconds, 97 queries complete and the same three return bounded-empty, with zero external timeouts. A 60-second sweep was stopped as redundant and unreasonable because it could not change the external-truncation classification. The instrumented five-second rerun showed one boundary perturbation relative to the official bundle, so official 5-second statuses remain authoritative.

## 11. Effect of increasing budget

Paired R50/R20 median ratios are corridor_nodes=1.499, corridor_edges=1.460, score_relevant_edges=1.000, connector_requests=1.000, generated_states=1.000, retained_states=1.000, completed_descriptors=1.000, replayed_candidates=1.000. Nondecrease fractions are corridor_nodes=100.00%, corridor_edges=100.00%, score_relevant_edges=98.00%, connector_requests=86.00%, generated_states=88.00%, retained_states=88.00%, completed_descriptors=90.00%, replayed_candidates=92.00%. Corridor size grows systematically, but generated/retained work does not justify a blanket monotonicity claim; see `budget_growth_pairs.csv` for all 50 pairs.

## 12. Score-proxy robustness

For selected PACE path-time samples, corr(score, edge count) is 0.431, while corr(score, geometric length) is 0.406. Score/km has median 5.871 and IQR [2.046, 12.682]. The predeclared severe-bias rule evaluates to **False**. Proposed but not activated: length-normalized transit affinity, e.g. integrate active-route density over geometric arc length rather than adding one unit per segmented arc.

## 13. Query-relevant real-data coverage

| Scope | Arcs | DIRECT | BOROUGH_CLASS | CITYWIDE | STATIC |
| --- | --- | --- | --- | --- | --- |
| NYC_BOUNDARY_MIDPOINT | 175622 | 1.12% | 44.79% | 54.09% | 0.00% |
| QUERY_REGION_UNION | 714535 | 0.32% | 11.03% | 88.64% | 0.00% |
| PACE_B_OFFICIAL_OUTPUTS | 1160 | 1.09% | 53.54% | 45.37% | 0.00% |
| FASTEST_OUTPUTS | 7535 | 3.28% | 44.69% | 52.03% | 0.00% |

The NYC boundary scope uses the preserved official DCP 26B water-excluded polygon and arc midpoints. The union of safe query regions is broad (714535 of 733,846 arcs), explaining why it resembles regional whole-graph provenance. Of the active `(route_id, shape_id, 15-minute bin)` activity units, 41572 (44.55%) are exact-shape matched, 0 (0%) use route fallback, and 51750 (55.45%) are unmatched. Exact matches contribute 2935650 graph arc-route-bin activations. No route-level fallback exists, the route/shape mismatch count is 0, and inactive directions/geometries are not activated through fallback.

## 14. Claims supported by the NYC experiment

- Fastest baseline feasibility and safe-corridor containment hold for every query/departure sample.
- Departure-time path choice varies within the two-hour interval for a nontrivial minority (20% of official completed profiles), so a single route is insufficient for those cases.
- Bounded PACE can trade additional travel for preference score, sometimes positively and sometimes negatively.
- The five-second limit is the main reason for the low official completion rate; 30 seconds removes all external truncations under unchanged bounded parameters.
- The connector is a deterministic static lower-bound witness policy, and pivot depth plus connector policy explain most structural fastest-path omissions.

## 15. Claims NOT supported

- The three bounded-empty results do not establish infeasibility.
- PACE-B does not uniformly dominate the fastest path in score; negative cells and negative R50 resolved gaps exist.
- “Single fastest connector” cannot currently mean exact time-dependent fastest connector.
- No real-data global-optimality or PACE-X certificate was obtained.
- The experiment does not support monotone candidate/state growth for every family as rho increases.
- The primary additive score is a transit-affinity proxy, not validated user preference or a segmentation-invariant objective.
- The median completed query does not require multiple paths, and the interval-best path covers the full window for the median sufficiently resolved query; profile necessity is not universal.

## Diagnostic figures and second example

The three added figures are `nyc_profile_necessity`, `nyc_score_travel_tradeoff`, and `nyc_budget_growth` (PDF and PNG). The second route-profile example is `NYC-Q012-MORNING-R20`, selected without manual inspection by minimizing absolute deviation from its period/rho group median among completed, 100%-resolved, >=3-cell, positive-gain queries from a different pair than the original representative.
