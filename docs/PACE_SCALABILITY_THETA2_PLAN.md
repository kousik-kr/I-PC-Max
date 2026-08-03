# PACE NY scalability pilot

This is an opt-in execution profile; it does not modify or launch the frozen
E00--E13 paper matrix.

`experiments/configs/pace_ny_scalability_theta.yaml` reuses the generated NY
paper query manifest and selects two existing B1 evaluation pairs (`P003` and
`P006`) at both declared time centers.  It runs PACE-B at theta 1, 2, and 3
for rho 0.10 and 0.20, with one query process, 24 internal workers, separate
`L=4`, `K_c=4`, and `K_f=2`, and conservative pilot caps
(`M_c=250,000`, `M_b=100,000`, `M_q=5,000,000`).  The Java worker receives
`-Xmx250g`; the controller allows only one process and plans below the 100 GiB
disk budget.

PACE-X is present only as the exact-policy small-budget comparator for rho
0.10.  The matrix builder and executor both reject PACE-X outside dataset NY,
split evaluation, and rho <= 0.10.  No exact-policy run is emitted for the
rho 0.20 cells.  With theta fixed at 2, PACE-X records its execution policy
and retains a frontier, but it is not globally certified when the query has
more than two score-relevant pivots.  A globally certified PACE-X run requires
an exact-only theta at least as large as the selected pivot count; the longer
P003 probe with theta=512 was stopped after several minutes because this is
not a scalable comparator on the large NY graph.

Plan and launch commands:

```bash
python3 experiments/scripts/run_all.py \
  --config experiments/configs/pace_ny_scalability_theta.yaml \
  --run-id pace_ny_theta_scalability_20260731 \
  --stages preflight,build,data,queries,plan,scalability,collect,validate,summarize \
  --max-concurrent 1
```

The existing `background_run.py` wrapper can run the same command with durable
stdout/stderr and `--resume`; it skips terminal raw records by input hash.
