# NY theta scalability run report

Run ID: `pace_ny_theta_scalability_20260731_v2`

The opt-in NY plan completed all 28 planned jobs with one query process at a
time.  The bounded cells used theta 1, 2, and 3, `L=4`, `K_c=4`, `K_f=2`,
`M_c=250000`, `M_b=100000`, `M_q=5000000`, 24 requested internal workers, and
`-Xmx250g`.  The four PACE-X cells were restricted to NY evaluation rho=0.10;
no PACE-X rho=0.20 job was planned or executed.

Observed terminal result:

- 28/28 `SUCCESS` records;
- 24 PACE-B records and 4 PACE-X records;
- zero timeout, OOM, cap-triggered, invalid, or horizon errors;
- result validation passed (`release/validation_report.json`);
- summary status counts are all `SUCCESS` (`summaries/summary_report.json`).

The PACE-X records are policy executions but report
`RETAINED_FRONTIER`, not `GLOBAL_CERTIFIED`, because the queries expose
111--284 score-relevant pivots while the requested theta is 2.  A follow-up
theta=512 exact probe on the longer P003 NY query was stopped after several
minutes without a terminal record.  Therefore this pilot demonstrates the
scalable bounded policy and exact-policy guard, but it does not establish a
globally certified exact comparator on the full NY graph.

The frozen E00--E13 paper matrix was not launched.
