# Corrected NYC-REAL-V2 visual case descriptions

## Role A -- profile variation

For the 07:00--09:00 departure interval, from 07:00--07:40 P1 has cell-average S=17 and T=6.512 min; at 07:41 P2 has cell-average S=38 and T=8.278 min; from 07:42--07:57 P3 has cell-average S=86 and T=8.119 min; at 07:58 P4 has cell-average S=42 and T=8.347 min; at 07:59 P2 has cell-average S=38 and T=8.362 min; from 08:00--08:37 P1 has cell-average S=17 and T=6.262 min; from 08:38--08:44 P5 has cell-average S=38 and T=8.205 min; from 08:45--09:00 P1 has cell-average S=17 and T=5.836 min. All exact minute-departure replays are valid and remain within B=8.383 min; the generated-registry interval-best path is selected for 16/121 departures.

## Role B -- preference/budget trade-off

For departures from 07:00--09:00, P1 is selected throughout with cell-average S=81 and T=8.707 min, below B=13.172 min. Across the same 121 departures, the fastest profile averages S=78.802 and T=8.705 min, while the PACE profile averages S=81.000 and T=8.707 min; the integrated score difference is +266, the travel-time premium is 0.021%, and every exact replay is valid.

## Role C -- deterministic minimum eligible coverage

For departures from 17:00--19:00, P1 is selected throughout with cell-average S=50 and T=4.218 min, below B=8.803 min. The fastest profile averages S=47.860 and T=4.201 min, and IntervalBestPathCoverage is 1.000; therefore this deterministic Role C selection does not itself show an interval-best mismatch, and all exact replays are valid.

## Visualization-only completion outcome

The predeclared Role C extension query NYC-Q024-EVENING-R50 timed out at actual PACE runtimes of 60.003 and 120.004 seconds. Both runs were labeled VISUALIZATION_ONLY and excluded from official statistics; no replacement query was run.
