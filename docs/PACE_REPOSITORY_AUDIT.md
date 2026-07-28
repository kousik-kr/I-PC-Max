# PACE Repository Audit

Audit date: 2026-07-27  
Repository root: `/home/koushik/Kousik/I-PC-Max`

## Executive conclusion

The checkout passes its Java and Python test baselines, but the production PACE
implementation does **not** implement the target algorithm stated in the audit
request. The current engine is the earlier recursive, all-positive-edge-anchor
design. It has no query corridor, no query-wide selected pivot set that leaves
non-selected score edges in connectors, no separate `K_c`/`K_f`, no `M_c`,
`M_b`, or `M_q`, no residual-budget or safe score-upper-bound pruning, and no
forward layered expansion.

The temporal/profile substrate is substantial and reusable: directed arc
loading, canonical temporal functions, fixed-departure routing, query-horizon
lower bounds, exact path replay under the repository's 12-decimal model,
temporal stitching, path-consistency checks, safe dominance, envelope
extraction, single-flight memoization, and ordered parallel task reduction are
all present in production.

Literal comparison with the "current PACE manuscript" is an
`EXTERNAL_BLOCKER`: neither the manuscript nor
`PACE_Q1_Experiment_Design_and_Codex_Runbook.md` exists in this checkout, any
Git ref, or the searched `/home/koushik` tree. Repository documents refer to a
separately supplied `Information_Systems___2026 (2).pdf`
(`docs/audit/PACE_REQUIREMENTS_MATRIX.md:3-6`), but that PDF is absent. Against
the target design supplied in the audit request, the answer is unambiguous:
**the current code does not match it**.

No production file was modified by this audit. The only audit-created files are
the three requested files under `docs/`. The worktree was already dirty before
the audit; its pre-existing changes are recorded below.

## Audit method and instruction discovery

The working directory and Git root were independently checked with `pwd` and
`git rev-parse --show-toplevel`; both resolved to
`/home/koushik/Kousik/I-PC-Max`.

No `AGENTS.md` (case-insensitive) exists in the repository. `.agents/` and
`.codex/` are present but empty. Repository operating instructions and
repository-specific claims were read from:

- `README.md`
- `Makefile`
- `pom.xml`
- `experiments/README.md`
- `experiments/DATASET_GENERATION.md`
- `experiments/QUERY_SET_GENERATION.md`
- `docs/audit/CONTRADICTION_LOG.md`
- `docs/audit/PACE_REQUIREMENTS_MATRIX.md`
- `docs/audit/PHASE_1_ORACLE_FINDINGS.md`
- `docs/experiments/Q1_REUSE_AND_GAP_MAP.md`
- the current paper-experiment, dataset, query, and E00-E13 configurations
- every current preflight report under `experiments/results/*/provenance/`

Conclusions below come from the production call graph. Tests and preflight
records are corroborating evidence only.

## Repository identity and pre-existing worktree state

| Field | Observed value |
|---|---|
| Branch | `main` |
| Commit | `c79924409ad2264b89af5623272392c1b5244c9d` |
| Upstream status | `main...origin/main` |
| Dirty before audit | Yes |
| Tracked modifications before audit | 20 |
| Untracked paths before audit | 11 |

Pre-existing tracked modifications:

```text
Makefile
experiments/README.md
experiments/configs/datasets/cal.yaml
experiments/configs/datasets/fla.yaml
experiments/configs/datasets/ny.yaml
experiments/configs/datasets/usa.yaml
experiments/configs/paper_q1.yaml
experiments/schemas/result_record.schema.json
experiments/scripts/common/config.py
experiments/scripts/generate_queries.py
experiments/scripts/preflight.py
src/main/java/edu/ipcmax/core/pcmax/PaceFrontierGenerator.java
src/main/java/edu/ipcmax/core/pcmax/PaceGenerationStats.java
src/main/java/edu/ipcmax/core/pcmax/PaceOptions.java
src/main/java/edu/ipcmax/experiments/PaceBench.java
src/main/java/edu/ipcmax/experiments/algorithms/PaceExperimentAlgorithm.java
src/main/java/edu/ipcmax/experiments/framework/ProfileSupport.java
src/test/java/edu/ipcmax/core/pcmax/PacePublicApiOracleIntegrationTest.java
src/test/java/edu/ipcmax/experiments/framework/AlgorithmResultExactnessTest.java
src/test/java/edu/ipcmax/testoracle/PaceExactOracleDifferentialTest.java
```

Pre-existing untracked paths:

```text
experiments/DATASET_GENERATION.md
experiments/QUERY_SET_GENERATION.md
experiments/configs/dataset_generation.yaml
experiments/configs/paper_q1_server_24c_250g.yaml
experiments/scripts/generate_dataset_assets.py
experiments/scripts/generate_query_sets.py
experiments/tests/test_query_generation_pipeline.py
scripts/monitor_paper_q1_server.sh
scripts/run_paper_q1_server.sh
scripts/stop_paper_q1_server.sh
src/main/java/edu/ipcmax/experiments/querygen/PaperQuerySetGenerator.java
```

All production conclusions in this report describe the actual dirty working
tree, including the untracked paper generator.

## Toolchain and operating system

| Component | Observed value |
|---|---|
| Java runtime | OpenJDK `21.0.7+6-Ubuntu-0ubuntu122.04` |
| Java compiler | `javac 21.0.7` |
| Maven | Apache Maven `3.6.3`, `/usr/share/maven` |
| `python` | Not installed/on `PATH` |
| `python3` | Python `3.10.12` |
| pytest | `6.2.5` |
| OS | Ubuntu `22.04.5 LTS` |
| Kernel | Linux `6.8.0-124-generic`, x86_64 |

The POM targets Java 21 (`pom.xml:13-17`, `pom.xml:40-52`) and packages
`edu.ipcmax.experiments.PaceBench` as `target/pace-bench.jar`
(`pom.xml:57-73`).

### Current build and test commands

The directly working commands on this host are:

```sh
mvn clean test
mvn package
python3 -m pytest experiments/tests
```

`README.md:62-69` publishes the Maven commands, and
`experiments/pyproject.toml:10-11` points pytest at `experiments/tests`.

Several Make/script entry points currently use the unavailable `python`
executable rather than `python3`: `Makefile:30-38`, `Makefile:40-47`,
`Makefile:92-106`, `scripts/test_experiments.sh:7-12`, and
`scripts/build_experiments.sh:21-24`. Their underlying Maven/Python tests work,
but those wrappers are not runnable on this host without a `python` alias.

## Test baselines

| Baseline | Command | Result |
|---|---|---|
| Java | `mvn clean test` | PASS: 155 tests, 0 failures, 0 errors, 0 skipped |
| Python | `python3 -m pytest experiments/tests` | PASS: 14 tests |

The first sandboxed Maven attempt could not write the user's Maven cache; the
same command was rerun with normal filesystem access and passed. This was an
execution-sandbox issue, not a repository failure. The final Surefire reports
contain 44 suites and the 155 passing tests above.

## Manuscript, runbook, configuration, and preflight

### Missing required sources

The following required inputs are absent:

- current PACE manuscript;
- `PACE_Q1_Experiment_Design_and_Codex_Runbook.md`.

They are absent from the worktree, Git object tree, local branches/tags, and the
searched home directory. This prevents line-by-line verification against the
literal current paper and prevents resolving any paper-only semantics for
anchor ranking, cap behavior, or exactness claims.

### Current executable configuration

The latest full-run launcher names
`experiments/configs/paper_q1_server_24c_250g.yaml`
(`experiments/results/_launchers/pace_q1_server_24c_250g_20260723T084539Z/state.json:2-18`).
That configuration specifies:

- NY, FLA, CAL, USA and E00-E13
  (`experiments/configs/paper_q1_server_24c_250g.yaml:5-26`);
- 20 pilot, 10 warmup, and 100 evaluation pairs, centers 510/1110, windows
  120-360, overheads 0.10-0.50, and one-minute evaluation
  (`experiments/configs/paper_q1_server_24c_250g.yaml:38-50`);
- `GRID_LOWER_BOUND_WITNESS_PATH_TRAVEL_TIME`, not a continuous fastest-path
  budget (`experiments/configs/paper_q1_server_24c_250g.yaml:46-49`);
- three measured trials, 1800-second timeout, 256000 MiB JVM limit, 24 local
  jobs, and PACE thread candidates 1-24
  (`experiments/configs/paper_q1_server_24c_250g.yaml:51-65`);
- configured Java query-generator classes and the
  `declared_centisecond_normalization-v1` contract
  (`experiments/configs/paper_q1_server_24c_250g.yaml:67-83`);
- `data/input`, `experiments/results`, matrix/query manifests, and the shaded
  JAR paths (`experiments/configs/paper_q1_server_24c_250g.yaml:84-94`).

### Current preflight reports

Five preflight reports exist:

```text
experiments/results/audit_plan_20260723/provenance/preflight.json
experiments/results/pace_q1_full_20260723_bg/provenance/preflight.json
experiments/results/pace_q1_full_server_20260723/provenance/preflight.json
experiments/results/pace_q1_server_24c_250g_20260723T084539Z/provenance/preflight.json
experiments/results/pace_q1_smoke_server_20260723/provenance/preflight.json
```

The first three full-run reports failed on 1440-minute assets, missing variants
or queries, and unresolved gates. The current server report passes with no
declared blocker
(`experiments/results/pace_q1_server_24c_250g_20260723T084539Z/provenance/preflight.json:1-4`).
It records all four base datasets and six NY variants with support through
10080 (same file, lines 5-152), the current toolchain and dirty SHA (lines
154-168), four passing implementation gates (lines 170-227), and query row/pair
counts (lines 231-286).

That pass is narrower than an implementation audit:

- the implementation gates cite test names and a field-presence check
  (`experiments/results/pace_q1_server_24c_250g_20260723T084539Z/provenance/preflight.json:172-224`);
  they do not inspect the production call graph;
- dataset validation checks manifest contract ID, support, counts, and checksums,
  but not edge/function numeric consistency
  (`experiments/scripts/generate_dataset_assets.py:794-825`);
- the Java loader does not read or enforce the conversion contract
  (`GeneratedGraphLoader.load`, `src/main/java/edu/ipcmax/core/loader/GeneratedGraphLoader.java:43-94`;
  `readManifest`, lines 202-218);
- query preflight accepts the generated manifest bytes but does not reveal that
  the configured Java generator is bypassed.

The latest full run is incomplete. Its launcher PIDs no longer exist, its log
ends at `stage_start=pilot`, it has no pilot-complete marker, and it contains
only six E01 terminal records despite a 55,486-job plan. The completed
correctness marker reports six successes, but it is not a completed Q1 run.

## Dataset and directory inventory

### Dataset directories

```text
data/
data/input/
data/input/CAL/
data/input/FLA/
data/input/NY/
data/input/NY/variants/
data/input/NY/variants/score-density-005/
data/input/NY/variants/score-density-010/
data/input/NY/variants/score-density-020/
data/input/NY/variants/score-density-040/
data/input/NY/variants/seed-43/
data/input/NY/variants/seed-44/
data/input/USA/
```

### Query directories

```text
experiments/manifests/
experiments/manifests/queries/
experiments/manifests/queries/CAL/
experiments/manifests/queries/FLA/
experiments/manifests/queries/NY/
experiments/manifests/queries/USA/
```

The combined manifest in each dataset directory is `paper_q1.jsonl`; split
manifests and sidecars are defined at
`experiments/QUERY_SET_GENERATION.md:56-69`.

### Experiment source/configuration directories

```text
experiments/
experiments/configs/
experiments/configs/datasets/
experiments/configs/studies/
experiments/plots/
experiments/schemas/
experiments/scripts/
experiments/scripts/common/
experiments/scripts/executors/
experiments/tables/
experiments/tests/
```

Generated Python cache directories also exist below `experiments/`:
`.pytest_cache/`, `plots/__pycache__/`, `scripts/__pycache__/`,
`scripts/common/__pycache__/`, `scripts/executors/__pycache__/`, and
`tests/__pycache__/`.

### Result directories

Legacy result roots:

```text
results/
results/logs/
results/manifests/
results/profiles/
results/raw/
results/summaries/
```

Q1 launcher roots:

```text
experiments/results/
experiments/results/_launchers/
experiments/results/_launchers/pace_q1_full_20260723_bg/
experiments/results/_launchers/pace_q1_full_server_20260723/
experiments/results/_launchers/pace_q1_server_24c_250g/
experiments/results/_launchers/pace_q1_server_24c_250g_20260723T084539Z/
experiments/results/_launchers/pace_q1_smoke_server_20260723/
```

Q1 run roots:

```text
experiments/results/audit_plan_20260723/
experiments/results/pace_q1_full_20260723_bg/
experiments/results/pace_q1_full_server_20260723/
experiments/results/pace_q1_server_24c_250g/
experiments/results/pace_q1_server_24c_250g_20260723T084539Z/
experiments/results/pace_q1_smoke_server_20260723/
```

Every initialized Q1 run root except the small launcher-only
`pace_q1_server_24c_250g/`, whose only child is `launcher/`, has the standard
directories `figures/`, `logs/`, `markers/`, `normalized/`, `plan/`,
`provenance/`, `raw/`, `release/`, `summaries/`, `tables/`, and `work/`.
`plan/matrices/` is present in planned runs; raw study directories use
`raw/<study-id>/`. The current server run has 54 opaque `work/<job-id>/`
directories and `raw/E01/`; the smoke run has six `work/<job-id>/`
directories, `raw/E01/`, and copied `release/{figures,summaries,tables}/`.
These opaque directories are content-addressed job workspaces, not separately
configured data locations.

For completeness, the current server work directory IDs are:

```text
03ba65dbde2e1de9f88a5a5b
06f0775813228ce8807e8d88
0bb082804e3a6e2ad29e262f
0d45d8f1c77e556e7008be2b
0db1b6fb5e599ca8356f7f3b
155d29ed67e8bad10ae04c2b
1805ffb5fb8e872faa94b9a7
18b997095586df8482ef6314
1b1fd0063be5360080b22a8e
1d00db4df259597285191a36
25666e493b023ac797a4a5ca
2a98d02f7b3cc7c23fa152f5
364a7e78e336ac81279d44de
39d24de842c1fe7f14763f0b
4385647efccb442da6d7629b
462328c1a6ded2881fc56d5a
46b419627a6fe7dbe6324a8c
4dfb36aaff40912892ff667d
5120c093e37389852f013c97
5643a82c2fda1105226fba79
5b18f5192d1df9d565c9d315
5ffb801443fdf32c5370099e
626a06cdfd5541029787bdfd
63913d6fa50d73f89a245200
69363cfeb65b34b4e8ae91c0
74722cb6204c8d76e48e70ce
79d08e6fc72967b6b227ebe5
7b6889826d1f72ef0baed46b
7ca9482849081d59bb1b6ec4
7cbfcd5704f22285abef9c70
80574704f8808399223c3e2d
82dbaefece9b59d045674309
8570b2ac979a935a55e6eb4b
89a177876edb5c29c7fcf330
989540437730a9d4b50ffc68
9dc70d7cc6da1c60db73cfa6
a330b81a91611fdaa22d3220
abcb84098a77f7e6415f7f80
aefab8a725eb59d44dd3bb08
b0d7771859e7e9b5e9d73440
bbff70587c688027c95a76c8
c501f1df3021a12915ae9109
cc379d0ef220ebf94232150f
cc84a51ef97e4f17b1dce566
cedd059f7b4ad4df3523c065
d3047392832f1eaae0b54e6e
db66d8684d0ef81b6229077c
dca4f48a77aea28d49d14226
de31bbecae7315072fe3011e
def7c7501fd4e1cff4829494
e86cec1f076a50db17b65ded
e910797edc7a58d8870f75c3
f8218cc55fa4a1fc4800ddf1
fdb0ac2e092cce71bbace7c7
```

The smoke work directory IDs are:

```text
13170dac67be3b97c749e036
22e82034d95618761d3217b6
30f1b2ad19850910e2c50075
c31923cfbd0df16f56a28708
c820c65ba69777e30a395894
fee5d74f88b89834b6e56fa8
```

## Dataset conversion and support verification

`experiments/configs/dataset_generation.yaml:4-17` defines
`minutes = DIMACS_weight / 6000` and support `[0,10080]`, with flat travel and
zero score after 1440. The Python generator:

- parses DIMACS arcs in source order and assigns stable sequential IDs
  (`iter_dimacs_arcs`, `experiments/scripts/generate_dataset_assets.py:115-136`);
- zips distance and travel files and requires identical `(arc_id,u,v)` before
  writing directed edges (`write_edges_from_raw`, lines 163-192);
- applies Decimal division by 6000 (`dimacs_to_minutes`, lines 81-86);
- extends travel and score support through 10080
  (`transform_travel_file`, lines 195-230; `transform_score_file`, lines
  233 onward).

The materialized manifests declare the contract and support; for example NY
does so at `data/input/NY/manifest.json:2-17` and `:71-85`. Sample payloads also
agree numerically: NY DIMACS time weight 2008 becomes `0.334666667`, FLA 35469
becomes `5.9115`, and CAL 1139 becomes `0.189833333`.

The generation command is unsafe to repeat with `--overwrite`. After rewriting
`edges_static.csv.gz` from raw DIMACS input, `generate_base_dataset` calls
`transform_travel_file` with the already materialized function file as both
source and destination (`generate_dataset_assets.py:629-641`). A second
overwrite divides already-normalized function values by 6000 again while
static base times are reset from raw input. `Makefile:77-78` publishes exactly
that overwrite command. `validate_assets` would still pass because it does not
compare static edge times to function values (`generate_dataset_assets.py:794-825`).
This must be fixed before regenerating production assets.

## Production call-graph verification

The classifications are implementation decisions relative to the requested
target. They are expanded in `docs/PACE_REUSE_AND_GAP_MAP.md`.

| # | Required component | Decision | Production evidence and conclusion |
|---:|---|---|---|
| 1 | Graph parsing and directed arc IDs | `VERIFIED_REUSE` | `GeneratedGraphLoader.load/readStaticEdges` loads the five files, requires sequential `arc_id`, retains ordered `u -> v`, and validates function endpoints (`src/main/java/edu/ipcmax/core/loader/GeneratedGraphLoader.java:43-94`, `:127-198`). `TDGraph` sorts by arc ID and constructs distinct outgoing/incoming directed indexes (`src/main/java/edu/ipcmax/core/graph/TDGraph.java:25-68`, `:79-121`). Parallel arcs remain distinct by ID. |
| 2 | DIMACS distance/time loading and normalization | `EXTEND` | DIMACS parsing and Decimal `/6000` conversion are real (`experiments/scripts/generate_dataset_assets.py:81-86`, `:115-192`), and current payloads/manifests agree. However overwrite is non-idempotent (`:597-645`), validation is manifest-only (`:794-825`), and `GeneratedGraphLoader.readManifest` ignores the conversion contract (`src/main/java/edu/ipcmax/core/loader/GeneratedGraphLoader.java:202-218`). Preserve parsers; replace the transform source flow and add semantic validation/loader enforcement. |
| 3 | Temporal arrival and score functions | `VERIFIED_REUSE` | `PiecewiseLinearFn.arrivalTimeAt` implements `t + tau(t)` and FIFO checks (`src/main/java/edu/ipcmax/core/function/PiecewiseLinearFn.java:76-145`). `PiecewiseConstFn` implements nonnegative right-continuous PWC scores (`src/main/java/edu/ipcmax/core/function/PiecewiseConstFn.java:10-16`, `:25-41`, `:101-157`). `ScoreProfile.compose` pulls scores back through actual arrival (`src/main/java/edu/ipcmax/core/profile/ScoreProfile.java:193-232`). This is exact only under the repository's canonical 12-decimal `double` model (`src/main/java/edu/ipcmax/core/function/Domain.java:20-29`, `:517-529`), not arbitrary-rational arithmetic. |
| 4 | Temporal support through 10080 | `EXTEND` | Assets and the latest preflight verify support through 10080 (`experiments/results/pace_q1_server_24c_250g_20260723T084539Z/provenance/preflight.json:5-152`); PACE constructs `[domain.start, domain.end+B]` and `AnchorIndex.create` checks every edge's travel/score coverage (`src/main/java/edu/ipcmax/core/pcmax/PaceFrontierGenerator.java:103-111`; `src/main/java/edu/ipcmax/core/pcmax/AnchorIndex.java:37-58`, `:167-175`). Extend the loader/asset validator to enforce the manifest contract and numeric support rather than trusting metadata. |
| 5 | Fixed-departure fastest path | `VERIFIED_REUSE` | `PointForwardLabeling.run` is a FIFO earliest-arrival label-setting search over production outgoing arcs, evaluates each edge at its actual entry time, applies the deadline, and reconstructs predecessor arc IDs (`src/main/java/edu/ipcmax/core/labeling/PointForwardLabeling.java:30-62`, `:107-128`). Its public departure parameter is integer-valued. |
| 6 | Lower-bound distance or routing index | `EXTEND` | `QueryLowerBounds` builds query-horizon edge minima and cached forward/reverse Dijkstra distances (`src/main/java/edu/ipcmax/core/pcmax/QueryLowerBounds.java:22-60`, `:63-90`). `LowerBoundGraph` supplies global minima and stable witnesses for query generation (`src/main/java/edu/ipcmax/core/graph/LowerBoundGraph.java:22-48`, `:51-87`). There is no routing index and PACE never materializes a safe query corridor. Reuse distances to build it. |
| 7 | Query generation | `REPLACE` | The legacy Java path is real: `QuerySetGenerator.generate` calls `DatasetQueryGenerator.generate` (`src/main/java/edu/ipcmax/experiments/querygen/QuerySetGenerator.java:56-78`), which loads through `GeneratedGraphLoader`, calls `QueryCandidateSampler`, then `QueryBudgetBuilder` (`src/main/java/edu/ipcmax/experiments/querygen/DatasetQueryGenerator.java:58-72`, `:100-110`; `src/main/java/edu/ipcmax/experiments/querygen/QueryCandidateSampler.java:83-177`; `src/main/java/edu/ipcmax/experiments/querygen/QueryBudgetBuilder.java:178-243`). But the current paper script's `_run_java_generator` directly calls `_generate_graph_backed_queries_python` (`experiments/scripts/generate_query_sets.py:321-378`). Its `_StaticGraph.load` parses edge CSV itself (`:381-407`), and its budget replays a random-walk witness on a one-minute grid (`:658-695`). Even the uninvoked Java `PaperQuerySetGenerator` uses random-walk witnesses and grid maxima (`src/main/java/edu/ipcmax/experiments/querygen/PaperQuerySetGenerator.java:319-419`, `:674-767`). The configured Java main class is therefore not the production path. |
| 8 | Current anchor identification | `REPLACE` | `AnchorIndex.create` scans every edge and makes every edge with any positive score an anchor (`src/main/java/edu/ipcmax/core/pcmax/AnchorIndex.java:37-58`). `relevantAnchors` ranks and truncates to `L` only per recursive subproblem (`:89-165`). There is no stable query-wide selected top-`L` pivot set. |
| 9 | Current connector generation | `REPLACE` | `ConnectorProfiles.generate` selects exhaustive or bounded enumeration with the same `frontierLimit` (`src/main/java/edu/ipcmax/core/pcmax/ConnectorProfiles.java:43-77`). Both enumerators exclude `anchors.anchorArcIds()`, the set of **all** positive-score arcs (`:80-141`, `:143-210`), and `buildCandidate` rejects any such arc (`:213-236`). Thus non-selected score edges cannot appear inside connectors. There is no `M_c` expansion cap. |
| 10 | Candidate-profile representation | `EXTEND` | `CandidateProfile` stores domain, exact arrival/score profiles, path pointer, historic recursion-depth/explicit-anchor count, pivot ID, and compression flag (`src/main/java/edu/ipcmax/core/profile/CandidateProfile.java:15-37`, `:57-76`). It lacks layer, residual-budget, work/cap, completion, and selected-pivot-set metadata required by the target. |
| 11 | `GenerateFrontier` recursion | `REPLACE` | `PaceFrontierGenerator.generate` memoizes `(u,v,D,ell,...)`; `computeFrontier` generates connector baselines and relevant anchors (`src/main/java/edu/ipcmax/core/pcmax/PaceFrontierGenerator.java:137-246`). `stitchForAnchor` enumerates every left/right anchor-budget split and recursively generates both halves (`:248-297`). This is precisely the recursive split architecture the target replaces. |
| 12 | Temporal stitching and breakpoints | `EXTEND` | `TemporalStitch.stitch` computes valid-domain preimages, anchor/right compositions, actual-entry score pullbacks, budget filtering, and path concatenation (`src/main/java/edu/ipcmax/core/pcmax/TemporalStitch.java:54-134`). `CanonicalPathProfileBuilder.replay` independently rebuilds the final path edge by edge (`src/main/java/edu/ipcmax/core/pcmax/CanonicalPathProfileBuilder.java:29-123`). `ProfileCellPartition.cells` constructs exact candidate/function/equality cuts (`src/main/java/edu/ipcmax/core/pcmax/ProfileCellPartition.java:18-91`). Reuse stitching and cell construction, but add explicit `M_b` accounting/status; no breakpoint cap exists now. |
| 13 | Path-consistency checks | `VERIFIED_REUSE` | `TemporalStitch.isVertexSimpleConcatenation` rejects repeated vertices across left/anchor/right (`src/main/java/edu/ipcmax/core/pcmax/TemporalStitch.java:137-201`). `CandidateProfile.vertexSequence/internalVertices/isVertexSimple` validates the production arc sequence and signature (`src/main/java/edu/ipcmax/core/profile/CandidateProfile.java:86-139`). Canonical replay also checks continuity and simplicity (`src/main/java/edu/ipcmax/core/pcmax/CanonicalPathProfileBuilder.java:57-72`). |
| 14 | Frontier compression and dominance | `EXTEND` | `FrontierCompressor` normalizes/deduplicates, partitions cells, applies `SafeProfileDominance`, performs deterministic bounded retention, and merges fragments (`src/main/java/edu/ipcmax/core/pcmax/FrontierCompressor.java:32-123`, `:253-381`, `:560-754`). `SafeProfileDominance` requires compatible internal vertices, equal arrivals, no-worse score, and stable ties (`src/main/java/edu/ipcmax/core/profile/SafeProfileDominance.java:36-55`, `:57-121`). The target can reuse these primitives, but the current engine compresses only after generating whole recursive subproblem frontiers; it has no incremental frontier or `M_b`. |
| 15 | Memoization and single-flight | `EXTEND` | `CandidateCache.getOrCompute` uses `ConcurrentHashMap<MemoKey,CompletableFuture<...>>`, makes concurrent callers join one complete value, defensively copies results, and removes failed promises (`src/main/java/edu/ipcmax/core/cache/CandidateCache.java:14-55`). `MemoKey` includes recursive domain/budget/policy/L/K/horizon/version state (`src/main/java/edu/ipcmax/core/cache/MemoKey.java:13-60`, `:139-215`). The mechanism is reusable; the key and cached value must represent corridor, selected pivots, layers, separate caps, and completion status. |
| 16 | PACE-X and PACE-B policies | `EXTEND` | The enum is only `PACE_X`/`PACE_B` (`src/main/java/edu/ipcmax/core/pcmax/PaceExecutionPolicy.java:6-11`). `PaceOptions` gives B finite `L` and one shared `K`; X forces both unbounded but retains caller `theta` and a frontier guard (`src/main/java/edu/ipcmax/core/pcmax/PaceOptions.java:15-50`, `:65-84`). `AlgorithmConfig.paceOptions` hardcodes X to one thread (`src/main/java/edu/ipcmax/experiments/framework/AlgorithmConfig.java:24-45`). Both experiment adapters report `RETAINED_FRONTIER`, appropriately avoiding a global certificate (`src/main/java/edu/ipcmax/experiments/algorithms/PaceExperimentAlgorithm.java:29-46`). Preserve policy identity/exactness separation; replace option semantics. |
| 17 | `threadCount` and real parallel work | `EXTEND` | `computeFrontier` creates root-anchor tasks when `threadCount > 1` and merges their results in returned order (`src/main/java/edu/ipcmax/core/pcmax/PaceFrontierGenerator.java:187-208`). `IPCMaxParallelExecutor` owns a `ForkJoinPool(threadCount)` and reads futures in input order (`src/main/java/edu/ipcmax/core/pcmax/IPCMaxParallelExecutor.java:18-38`). This is real parallel work, but only at the root anchor fan-out. Adapt the executor to deterministic layered batches and cap accounting. |
| 18 | `PaceBench` and result instrumentation | `EXTEND` | `PaceExperimentAlgorithm.run` exports only eight PACE counters (`src/main/java/edu/ipcmax/experiments/algorithms/PaceExperimentAlgorithm.java:29-46`); `PaceGenerationStats` contains only those fields (`src/main/java/edu/ipcmax/core/pcmax/PaceGenerationStats.java:4-16`). `PaceBench.executeWithLimits` measures start/end heap and polls memory (`src/main/java/edu/ipcmax/experiments/PaceBench.java:424-481`), but `record` discards those values and writes every memory metric as null (`:510-583`). Phase timing and most counter schema fields also remain null in actual E01 records. Add target caps/status/work counters and wire measured values. |
| 19 | Schemas, summaries, tables, and plots | `EXTEND` | Schema v2 separates completion/policy/exactness but has only `anchor_limit` and shared `k`, with no target cap/status fields (`experiments/schemas/result_record.schema.json:49-75`). Collection reads Java memory, which is null (`experiments/scripts/collect_results.py:29-49`). Summaries aggregate only runtime, memory, profile cells, coverage, agreement, and regret (`summarize_results.py:78-147`). Tables T1-T8 select a small subset and T8 omits internal counters despite its title (`experiments/tables/make_all_tables.py:74-104`). F1-F8 are simple aggregate bar charts (`experiments/plots/make_all_plots.py:17-40`; `experiments/plots/common.py:59-102`). Extend schema through release artifacts and validate non-null metrics. |

## Target-design negative evidence

A production-source search found no safe score upper bound, residual-budget
pruning, candidate-work budget, connector/breakpoint cap, incremental frontier,
or PACE query-corridor class. The only "corridor" code is query-generation
anchor counting (`QueryBudgetBuilder.countCorridorAnchors`,
`src/main/java/edu/ipcmax/experiments/querygen/QueryBudgetBuilder.java:388-407`);
it never restricts PACE's graph. The only remaining-budget state is recursive
anchor count in `MemoKey`, not travel budget.

`PaceOptions` has one `frontierLimit` described as a connector/frontier `K`
(`PaceOptions.java:9-23`). `ConnectorProfiles` and `FrontierCompressor` both
consume it (`ConnectorProfiles.java:68-75`;
`PaceFrontierGenerator.java:236-245`). The only other PACE cap is
`maxFrontierFragments`, which throws `LIMIT_EXCEEDED` before compression
(`PaceFrontierGenerator.java:230-235`).

## Result pipeline findings

The structured-record skeleton is useful, but current publication metrics are
not complete:

- Actual E01 records have `peak_rss`, start/end memory, memoization peak, and
  frontier peak all null, matching `PaceBench.record`
  (`PaceBench.java:562-569`).
- `resolve_pace_b.py` requires a numeric `peak_rss` before accepting any E02
  row (`experiments/scripts/resolve_pace_b.py:25-51`). Therefore the current
  full workflow cannot resolve PACE-B parameters even though preflight passes.
- Most declared phase timings and counters are initialized to null and never
  updated (`PaceBench.java:555-574`; `PaceExperimentAlgorithm.java:35-42`).
- Validation checks record coverage, horizons, policy/exactness, checksums, and
  cross-thread checksum stability, but not non-null memory/phase/cap fields
  (`experiments/scripts/validate_results.py:33-129`).
- `summarize_results.py` does not aggregate
  `relative_score_gap_percent`; it uses `integrated_score_regret`
  (`summarize_results.py:85-92`). The pilot resolver consumes the relative field
  directly, so its presence is real but not propagated into general tables.
- T8 is titled "Ablation effects and internal counters" but includes only
  wall time and peak RSS (`make_all_tables.py:20-27`, `:98-104`).
- Figure generation produces deterministic SVG/PNG/PDF files and sidecars, but
  all eight figures are one-metric bar renderings from aggregate rows
  (`make_all_plots.py:17-40`; `plots/common.py:67-102`), not the specialized
  plot modules also present in `experiments/plots/`.

## Stale or misleading claims

| Claim | Production evidence | Disposition |
|---|---|---|
| Python "never loads or reimplements the road graph" (`experiments/README.md:5-7`) and paper query generation uses production Java loader/routing (`:43-49`). | `_run_java_generator` invokes Python directly; `_StaticGraph.load` parses `edges_static.csv.gz` (`generate_query_sets.py:321-407`). | Stale/false for the current paper path. |
| Validation regenerates through Java graph APIs (`experiments/QUERY_SET_GENERATION.md:11-18`) and the generator uses `GeneratedGraphLoader` (`:33-37`). | The invoked Python generator bypasses `PaperQuerySetGenerator`; output summaries are still stored under a `"java"` key (`generate_query_sets.py:1006-1063`). | Stale/false. |
| Q1 reuse map says the Python layer never parses road graphs and reuses the four legacy Java query-generation classes (`docs/experiments/Q1_REUSE_AND_GAP_MAP.md:9-16`). | Current script bypasses all four classes. The legacy Java path exists but is not the paper path. | Stale. |
| Current large manifests lack conversion provenance and fail support (`README.md:102-105`; `docs/audit/PACE_REQUIREMENTS_MATRIX.md:62-66`; `docs/experiments/Q1_REUSE_AND_GAP_MAP.md:24-27`). | Current manifests declare `/6000` and 10080 support (`data/input/NY/manifest.json:2-17`, `:71-85`); current preflight passes all datasets (`experiments/results/pace_q1_server_24c_250g_20260723T084539Z/provenance/preflight.json:5-152`). | Superseded. |
| `relative_score_gap_percent` is absent (`docs/experiments/Q1_REUSE_AND_GAP_MAP.md:28`). | `ProfileSupport.quality` emits it (`src/main/java/edu/ipcmax/experiments/framework/ProfileSupport.java:168-188`) and schema requires it (`result_record.schema.json:95-98`). | Superseded, although memory still blocks the pilot resolver. |
| PACE thread count performs no algorithm work (`docs/audit/PACE_REQUIREMENTS_MATRIX.md:69`; `docs/experiments/Q1_REUSE_AND_GAP_MAP.md:29`). | Root anchor tasks execute in a real `ForkJoinPool` and are reduced in input order (`PaceFrontierGenerator.java:187-208`; `IPCMaxParallelExecutor.java:18-38`). | Superseded; parallelism remains root-only. |
| `data/input/out_ny_td` is the current copied input (`README.md:45-60`). | Current configs and preflight use `data/input/NY` (`experiments/configs/datasets/ny.yaml:1`; `experiments/results/pace_q1_server_24c_250g_20260723T084539Z/provenance/preflight.json:16`). | Stale path. |
| The documented result-analysis outputs are `per_run.csv`, `per_query_method.csv`, etc. (`experiments/README.md:181-185`). | Current collection/summarization emits `run_records.{jsonl,csv}`, `aggregate_records.{jsonl,csv}`, `paired_comparisons.csv`, and reports (`collect_results.py:53-75`; `summarize_results.py:148-182`). | Stale list. |
| Passing preflight means the current full workflow is unblocked. | Preflight does not check graph-call-path truth or non-null memory; `resolve_pace_b` rejects every null-memory row (`resolve_pace_b.py:25-51`). The current run died at pilot. | Overbroad inference; not a safe conclusion. |

## Blockers

### External blockers

1. `EXTERNAL_BLOCKER`: the current manuscript is absent, so literal paper
   parity, paper line citations, and paper-specific semantics cannot be
   certified.
2. `EXTERNAL_BLOCKER`:
   `PACE_Q1_Experiment_Design_and_Codex_Runbook.md` is absent, so its operational
   acceptance rules cannot be verified.
3. `EXTERNAL_BLOCKER`: before implementation, the paper/author must define
   whether `M_c`, `M_b`, and `M_q` exhaustion aborts with no certified profile
   or returns a partial bounded profile, and must freeze the exact top-`L`
   ranking and candidate-work accounting. The target names the controls but
   does not specify these semantics.

### Repository blockers and defects

1. The paper query pipeline bypasses the configured Java generator and
   production graph loader.
2. Dataset overwrite can double-normalize travel functions.
3. Memory is measured for enforcement but not serialized, making the E02
   PACE-B resolver unusable.
4. The target PACE algorithm components listed in the executive conclusion are
   absent.
5. The latest full run is incomplete and its launcher is no longer active.

## Acceptance-gate result

| Gate | Result |
|---|---|
| Existing Java tests pass | PASS: 155/155 |
| Existing Python tests pass | PASS: 14/14 |
| No production file modified by this audit | PASS |
| Every requested implementation area has a reuse/change decision | PASS; see the 19-row call-graph table and `PACE_REUSE_AND_GAP_MAP.md` |
| Every target algorithm component has a concrete decision | PASS; see `PACE_REUSE_AND_GAP_MAP.md` |
| Blockers are explicit | PASS |
| Report states whether code matches current paper | PASS: literal paper match is not verifiable because the paper is absent; code definitively does not match the target design supplied with this audit |
