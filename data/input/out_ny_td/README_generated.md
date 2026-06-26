# Generated Time-Dependent Graph

Inputs:

- Coordinates: `data\USA-road-d.NY.co`
- Static travel time: `data\USA-road-t.NY.gr`
- Static distance: `data\USA-road-d.NY.gr`

Outputs:

- `nodes.csv.gz`: node coordinates with columns `node_id,x,y`
- `edges_static.csv.gz`: static edge data with columns `arc_id,u,v,distance,base_travel_time`
- `travel_time_functions.jsonl.gz`: one JSON object per arc with piecewise-linear travel-time breakpoints
- `score_functions.jsonl.gz`: score intervals for selected arcs, or every arc when zero-score edges are materialized
- `manifest.json`: generation metadata
- `README_generated.md`: this summary

Summary:

- Nodes: 264346
- Arcs: 733846
- Seed: 42
- Rush windows: {"morning": [420, 600], "evening": [1020, 1200]}
- Multiplier ranges: {"morning": [1.1, 2.0], "evening": [1.15, 2.5]}
- Integer travel times: False
- Travel-time decimals: 3
- Score edge fraction: 0.2
- Selected score edges: 146769
- Score range: 1 to 15
- Score intervals per rush: 3
- Materialized zero-score edges: False

Travel-time functions cover one day from minute 0 to minute 1440.
`travel_time_breakpoints` are `[minute, travel_time]` points for a continuous
piecewise-linear function. Outside rush windows, travel time equals
`base_travel_time`.

`score_intervals` are `[start_minute, end_minute, score]` intervals for a
piecewise-constant function. Scores are nonzero only inside rush windows.
Unlisted edges have score zero for the full day.

Example:

```bash
python generate_td_graph.py \
  --coords USA-road-d.NY.co.gz \
  --travel-time USA-road-t.NY.gr.gz \
  --distance USA-road-d.NY.gr.gz \
  --output-dir out_ny_td \
  --seed 42 \
  --morning-rush 420 600 \
  --evening-rush 1020 1200 \
  --score-edge-fraction 0.20 \
  --score-min 1 \
  --score-max 15 \
  --score-intervals-per-rush 3
```
