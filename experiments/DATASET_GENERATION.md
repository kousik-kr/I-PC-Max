# Dataset Asset Generation

The paper assets use a declared release normalization for DIMACS transit
weights:

`minutes = DIMACS_weight * 1 / 6000`

This is an author decision for this artifact. The DIMACS documentation treats
transit-time weights as arbitrary, so this is not an official physical-unit
claim about the DIMACS data.

Generated travel-time and score functions must cover `[0,10080]` minutes. The
first day `[0,1440]` uses the checked rush-window construction. The extension
from `1440` through `10080` is explicit: travel time remains equal to the
converted static travel time and score is zero. The generator does not use
weekly repetition or query-time extrapolation.

The conversion contract is stored in
`experiments/configs/dataset_generation.yaml` and copied into every generated
`manifest.json`. Since `manifest.json` is one of the files hashed by the graph
identity checksum, a change to the conversion contract changes dataset identity.

Only NY, FLA, CAL, and USA are in scope. The generator uses the single
`iter_dimacs_arcs` conversion path to rebuild `edges_static.csv.gz`; temporal
payloads are then derived from those canonical directed arcs. It never rescales
an already converted graph. Directed arc IDs must be unique and consecutive
from zero.

Each schema-version-3 dataset manifest records two non-self-referential
identities:

- `dataset_checksum` hashes `nodes.csv.gz` and `edges_static.csv.gz`;
- `temporal_attribute_checksum` hashes the travel-time and score payloads.

The legacy full graph checksum additionally includes `manifest.json` and is
used as the exact query-generation input identity.

The four NY density variants select exactly
`floor(density * directed_arc_count)` arcs using one stable affine permutation.
Consequently 5% is a subset of 10%, which is a subset of 20%, which is a
subset of 40%. Seeds 42, 43, and 44 are retained where the study matrix
requires them.

Commands:

```sh
# Generate/rebuild every configured base dataset and NY variant.
make paper-generate-assets

# Validate IDs, counts, raw conversion, checksums, support, FIFO, positive
# lower-bound travel times, density counts/nesting, and graph seeds.
make paper-validate-assets

# Refresh legacy manifests and generate only missing preparation state.
make paper-resume-assets

# Print intended preparation actions without writing or loading full graphs.
make paper-plan-assets
```

Validation is deliberately a full payload scan. A missing, malformed,
checksum-inconsistent, non-FIFO, non-positive, or horizon-short asset is a
preflight blocker.
