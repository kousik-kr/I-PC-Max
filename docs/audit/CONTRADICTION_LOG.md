# PACE Audit Contradiction Log

Audit date: 2026-07-21

| Prior claim or strategy item | Current evidence | Resolution |
|---|---|---|
| Definitions 3-7 and Algorithm 1 were previously inferred. | The supplied PDF defines them literally on pages 3-5 and 8. | Replaced the inferred numbering in `PACE_REQUIREMENTS_MATRIX.md`. |
| The older audit reported 78 tests. | The current checkout passes 134 tests at commit `19bb54b`. | Older count is superseded, not evidence of current coverage. |
| Query generation and schema v2 were missing. | `experiments/querygen`, schema-v2 fields, writer/parser tests, and 41 query-generation tests are present. | Marked the old findings superseded; the implemented workload is still not the paper workload. |
| USA contains only a README. | The current worktree contains all five required USA payload files, including the large function files. | USA availability is no longer the immediate blocker. Provenance and preflight remain open. |
| PACE-X silently truncates at `maxFrontierFragments`. | `PaceFrontierGenerator` throws `PaceException(LIMIT_EXCEEDED)` before compression, and `PaceBench` records failure without a profile. | Corrected: this is an aborting resource guard. Add a regression test, but do not describe it as silent truncation. |
| PACE-X is globally exact whenever policy X is selected. | Theorem 2 has seven conditions. X retains caller theta, and the fraction oracle also exposes unresolved temporal-boundary drift. | The reporting defect is fixed: schema-v2 records separate policy and scope, and current X is `RETAINED_FRONTIER`, never globally certified. The algorithmic/numerical gate remains open. |
| Use a one-minute grid to compute the paper budget. | Section 6.4 defines `T_min` over the continuous interval. Section 6.6 mentions a discrete resolution only for average-score evaluation and does not define one minute. | A grid budget would be a paper change, not conformance. Use the existing exact fastest profile for literal paper budgets. |
| The Phase-5 quartile/four-regime query sets reproduce Section 6.4. | The paper uses 100 base pairs, two rush-centered windows, lengths 120-360, rho 10-50%, and a minimum-fastest budget. | Keep the newer deterministic study separate and add a distinct paper workload. |
| Main budget should be `1.25*max(T_fast)`. | The paper specifies `B=(1+rho)*min(T_fast)` with default rho 0.30. | Existing query-generation budget is valid only for its separate full-interval-feasible study. |
| Exact rational time was only a suggested hardening. | Section 4.2 explicitly says normalized domains should use exact integer/rational endpoints rather than raw floating point. | Raised canonical-double arithmetic to a critical traceability gap. |
| Extending function support is automatically the preferred horizon fix. | The paper explicitly declares support `[0,1440]`; extending it changes the experimental model. | First produce a violation report. Any support or workload change requires a recorded manuscript decision. |
| Section 6 fully specifies reproducibility. | The draft contains setup placeholders and omits several generator, sampling, grid, repetition, and metric rules. | Recorded as paper ambiguities/blockers rather than invented defaults. |
