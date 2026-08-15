# NYC case-study findings

Experiment status: EXPERIMENT COMPLETED.

## Supported findings

- 35/100 queries returned a complete PACE-B profile within the bounded protocol; status counts were `{'COMPLETE': 35, 'NO_FEASIBLE_PATH': 3, 'TIMEOUT': 62}`.
- Exact replay budget violations: 0.
- For rho=0.20, 19/50 queries completed; mean absolute score gain was 3.877, mean relative gain was 6.92%, and mean travel-time premium was 10.23%.
- For rho=0.50, 16/50 queries completed; mean absolute score gain was -1.085, mean relative gain was -2.00%, and mean travel-time premium was 10.87%.
- Across the 15 pair/period families completed at both budgets, raising rho from 0.20 to 0.50 changed mean score by -0.921, candidate count by -0.533, and profile cells by -0.533.

## Unsupported/failed hypotheses

- Do not claim score or budget monotonicity beyond the measured paired-family summary.
- A blanket claim that bounded PACE-B improves mean score over fastest routing is unsupported; nonpositive completed-query mean absolute gain was observed at rho=0.50: -1.085.
- Only 15 pair/period families completed at both budgets; budget-effect conclusions are limited to those paired observations.
- The five-second protocol timed out on 62/100 queries, so this configuration does not support a general practical-runtime claim.

## Data limitations

- DOT coverage is limited to instrumented links; most regional arcs use observed citywide multipliers.
- The 2026 schedule day contains historical shape IDs absent from the current route-shape view.

## Mapping limitations

- Spatial overlap and direction reduce but cannot eliminate ambiguity on parallel roads.

## Recommended manuscript statements

- Describe the score as an active transit-corridor affinity proxy, not an MTA ground-truth preference.
- Report direct and imputed travel-time proportions alongside results.
- Report score gains only on the completed-query subset, together with timeout and no-feasible-profile counts.
- Use the automatically selected profile figure as evidence that the selected road path can change with departure time, not as evidence of universal score gain.

## Statements we should NOT make

- Do not claim PACE performs scheduling, assignment, stop sequencing, fleet planning, or VRP optimization.
- Do not claim unmapped or imputed arcs have direct sensor observations.
- Do not claim larger budget monotonically improves this bounded implementation's score or profile complexity.
- Do not claim iSCOPE was evaluated; the optional comparison was not run for this isolated case study.
