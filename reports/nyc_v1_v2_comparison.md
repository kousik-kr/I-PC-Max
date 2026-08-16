# NYC V1/V2 diagnostic comparison

This report compares frozen executions and data artifacts. It does not make or modify manuscript claims.

## Frozen scientific inputs

- Query comparison pass: `True`.
- V1/V2 query records: 100/100; terminal pairs: 25/25.
- Compared fields: `query_id, pair_id, source, destination, period_id, interval_start, interval_end, rho, budget`; mismatches: 0.
- Frozen PACE-B parameters in every V2 timed record: `L=32`, `theta=2`, `Kf=16`, `Mb=1000000`.
- Travel artifacts are byte-identical for nodes, static edges, travel-time functions, and travel provenance. Provenance counts remain `{"BOROUGH_CLASS_IMPUTED": 7567514, "CITYWIDE_IMPUTED": 62659235, "DIRECT": 222467, "STATIC_FALLBACK": 0}`.

## CONNECTOR_FIX

PACE-B now chooses one exact FIFO time-dependent fastest structure at each connected connector-cell midpoint and exactly replays that structure over the cell. The production runner exposes no Kc, Mc, Mq, connector-portfolio, or connector-cache tuning options.

On the predeclared 20-query controlled subset at 30 seconds:

- V1 data + old connector: `{'COMPLETE': 19, 'NO_FEASIBLE_PATH': 1}`.
- V1 data + corrected connector: `{'EXTERNAL_TIMEOUT': 10, 'COMPLETE': 10}`.
- Common-completion mean score change versus the V1 control: -5.505785 across 10 queries.
- Common-completion point-path identity versus the V1 control: 30.000%.

The corrected connector is materially more expensive on this graph. This is an observed diagnostic outcome, not a basis for tuning.

## MTA_SHAPE_COMPLETENESS

- Active shape IDs: 4253.
- V1 exact active shapes mapped: 885; V2 exact geometries available/mapped: 1421/1418.
- Unmatched active shapes: V1 3368; V2 2835.
- Active route-shape-bin exact match: V1 44.546838%; V2 58.953944%.
- Score-bearing DIMACS edges: V1 21422; V2 24655.
- V2 route geometry fallbacks: 0.

Controlled old-connector executions resolved 20/20 queries. On common completions, the improved score data changed mean full-interval score by 5.166159 and retained identical point paths for 84.776% of samples.

## STATUS_ONLY

The status-only machine remap identifies 3 V1 bounded-empty records: `NYC-Q008-MORNING-R20, NYC-Q016-EVENING-R20, NYC-Q016-EVENING-R50`. Their non-certified label becomes `UNRESOLVED_NO_GENERATED_CANDIDATE` (`U`); no scientific numeric field changes. Certified `NO_FEASIBLE_PATH` remains available only with an independent global certificate.

V1 primary statuses were `{'TIMEOUT': 62, 'COMPLETE': 35, 'NO_FEASIBLE_PATH': 3}`. V2 primary statuses are `{'TIMEOUT': 93, 'COMPLETE': 7}`. The V2 count difference includes connector/data execution effects and must not be interpreted as status-only attribution.

## Full V2 execution and validation

- Primary 5 s: `{'TIMEOUT': 93, 'COMPLETE': 7}`.
- Diagnostic 10 s: `{'TIMEOUT': 87, 'COMPLETE': 13}`.
- Diagnostic 30 s: `{'TIMEOUT': 85, 'COMPLETE': 15}`.
- Fastest invariant: 12100/12100 path agreements; query-region containment 12100/12100.
- Exact PACE replay/budget validation: 1815/1815; violations 0.
- Determinism: 13/13 repeated cross-cap checksums and 15/15 independent trace checksums agree.

The controlled full-V2 subset produced `{'EXTERNAL_TIMEOUT': 10, 'COMPLETE': 10}`. External-cap status differences near 30 seconds are timing-sensitive; scientific comparisons use common completed queries.
