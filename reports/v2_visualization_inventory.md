# NYC-REAL-V2 visualization inventory

Normally completed results from the frozen 5-, 10-, and 30-second runs. Rows are deduplicated by `(query_id, PACE profile checksum)`; `First cap` is the first bounded run in which that exact profile normally completed.

- Unique completed query/profile rows: 15
- CSV SHA-256: `fa18d204f40e07611593b436185fedf4fb783dfceb32e5767fcd7b43b1339991`
- Source 30-second result SHA-256: `1026fecb511254b3310796d7a08caa6e53d64bce026bd777b48007e1b2945bc2`

| Query | Pair | Period | rho | B | First cap | Cells | Paths | Switches | PACE S | Fastest S | Delta S | PACE T | Fastest T | Premium | IB coverage | Replay | B violations |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| NYC-Q002-EVENING-R50 | NYC-002 | EVENING | 0.50 | 8.803 | 5 s | 1 | 1 | 0 | 50.000 | 47.860 | +2.140 | 4.218 | 4.201 | +0.39% | 1.000 | YES | 0 |
| NYC-Q002-MORNING-R20 | NYC-002 | MORNING | 0.20 | 6.375 | 10 s | 1 | 1 | 0 | 39.000 | 44.471 | -5.471 | 4.953 | 4.798 | +3.22% | 1.000 | YES | 0 |
| NYC-Q002-MORNING-R50 | NYC-002 | MORNING | 0.50 | 7.968 | 5 s | 1 | 1 | 0 | 39.000 | 44.471 | -5.471 | 4.953 | 4.798 | +3.22% | 1.000 | YES | 0 |
| NYC-Q008-EVENING-R20 | NYC-008 | EVENING | 0.20 | 5.714 | 10 s | 1 | 1 | 0 | 35.000 | 35.000 | +0.000 | 4.136 | 4.136 | +0.00% | 1.000 | YES | 0 |
| NYC-Q008-EVENING-R50 | NYC-008 | EVENING | 0.50 | 7.143 | 5 s | 1 | 1 | 0 | 35.000 | 35.000 | +0.000 | 4.136 | 4.136 | +0.00% | 1.000 | YES | 0 |
| NYC-Q008-MORNING-R50 | NYC-008 | MORNING | 0.50 | 6.232 | 5 s | 1 | 1 | 0 | 35.000 | 34.438 | +0.562 | 3.415 | 3.380 | +1.04% | 1.000 | YES | 0 |
| NYC-Q012-EVENING-R20 | NYC-012 | EVENING | 0.20 | 9.442 | 5 s | 1 | 1 | 0 | 21.000 | 21.471 | -0.471 | 6.821 | 6.810 | +0.17% | 1.000 | YES | 0 |
| NYC-Q012-EVENING-R50 | NYC-012 | EVENING | 0.50 | 11.803 | 5 s | 1 | 1 | 0 | 21.000 | 21.471 | -0.471 | 6.821 | 6.810 | +0.17% | 1.000 | YES | 0 |
| NYC-Q012-MORNING-R20 | NYC-012 | MORNING | 0.20 | 8.383 | 30 s | 12 | 5 | 7 | 27.893 | 21.612 | +6.281 | 6.700 | 5.845 | +14.62% | 0.132 | YES | 0 |
| NYC-Q012-MORNING-R50 | NYC-012 | MORNING | 0.50 | 10.478 | 5 s | 1 | 1 | 0 | 17.000 | 21.612 | -4.612 | 6.012 | 5.845 | +2.85% | 1.000 | YES | 0 |
| NYC-Q020-EVENING-R20 | NYC-020 | EVENING | 0.20 | 15.050 | 10 s | 1 | 1 | 0 | 81.000 | 79.116 | +1.884 | 10.769 | 10.768 | +0.01% | 1.000 | YES | 0 |
| NYC-Q020-MORNING-R20 | NYC-020 | MORNING | 0.20 | 13.172 | 10 s | 1 | 1 | 0 | 81.000 | 78.802 | +2.198 | 8.707 | 8.705 | +0.02% | 1.000 | YES | 0 |
| NYC-Q024-EVENING-R20 | NYC-024 | EVENING | 0.20 | 15.726 | 30 s | 1 | 1 | 0 | 94.893 | 96.893 | -2.000 | 9.831 | 9.692 | +1.44% | 1.000 | YES | 0 |
| NYC-Q024-MORNING-R20 | NYC-024 | MORNING | 0.20 | 10.165 | 10 s | 1 | 1 | 0 | 145.810 | 145.669 | +0.140 | 7.856 | 7.804 | +0.67% | 1.000 | YES | 0 |
| NYC-Q024-MORNING-R50 | NYC-024 | MORNING | 0.50 | 12.707 | 10 s | 1 | 1 | 0 | 145.810 | 145.669 | +0.140 | 7.856 | 7.804 | +0.67% | 1.000 | YES | 0 |

`S` is additive corridor-affinity score; `T` and `B` are minutes. Interval-best coverage is measured only within the generated path registry.
