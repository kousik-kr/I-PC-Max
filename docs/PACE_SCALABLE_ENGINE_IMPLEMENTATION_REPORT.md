# PACE Scalable Candidate Engine Implementation Report

Date: 2026-07-27

## Outcome

The forward-layered scalable PACE candidate engine is the default production
path. The former recursive left/right engine remains available only through
`PaceEngineMode.LEGACY` / `--pace-engine legacy` for diagnostics.

PACE-X is an exhaustive tiny-instance validation policy. It receives
`GLOBAL_CERTIFIED` only after all score-relevant corridor pivots are selected,
`theta` covers them, and no cap is present. PACE-B always reports
`RETAINED_FRONTIER`, including when it returns a deterministic
resource-truncated frontier. Resource-truncated PACE-X reports `ABORTED`,
fails closed, and never carries a global certificate.

No NY/FLA/CAL/USA experiment matrix was launched. Only unit/integration
tests, validation commands, and the six-job demo paper-smoke workflow ran.

## Production call path

The public call path is:

1. `PACE.generate` selects `ForwardLayeredFrontierGenerator` by default
   (`src/main/java/edu/ipcmax/core/pcmax/PACE.java:50`).
2. `QueryPreparationIndexes` supplies one reusable summary/partition/score
   bundle for the canonical `TDGraph`. The default `PACE` constructor keeps a
   weak per-graph cache, and an overload accepts an explicitly prepared bundle.
3. `ForwardLayeredFrontierGenerator.generate`
   (`src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:65`)
   builds the query corridor, selects pivots, initializes the work ledger,
   creates the connector operator, and advances deterministic depth layers.
4. Every completed path is replayed through
   `CanonicalPathProfileBuilder.replay`; the existing exact envelope extractor
   consumes the retained root frontier.

The legacy production classes were not deleted. `PaceEngineMode.LEGACY`
selects `PaceFrontierGenerator`, and legacy results are explicitly
`NOT_CERTIFIED`.

## Bounded controls

`PaceOptions` now stores independent controls
(`src/main/java/edu/ipcmax/core/pcmax/PaceOptions.java:14`):

| Control | Meaning | Enforcement |
|---|---|---|
| `L` | selected query pivots | `PivotSelector` prefix |
| `theta` | explicit pivot depth | forward layer bound |
| `K_c` | valid connector profiles/call | canonical connector stream prefix |
| `M_c` | connector states popped/call | `PaceWorkLedger` typed connector cap |
| `K_f` | fragments/temporal frontier cell | incremental compressor |
| `M_b` | exact breakpoints/profile | replay and insertion checks |
| `M_q` | canonical candidate work/query | reservation before task submission |

The historical `--k` option remains only as a compatibility alias that sets
both `K_c` and `K_f`. New CLI options serialize all independent values.
PACE-X normalizes the six bounded limits to unbounded sentinels; the emergency
frontier guard remains separately observable.

## Phase implementation

### A. Safe lower-bound corridor

`QueryCorridor.build`
(`src/main/java/edu/ipcmax/core/pcmax/QueryCorridor.java:66`) retains arc
`e=(x,y)` only if:

`d_lb(s,x) + tau_lb(e) + d_lb(y,d) <= B`.

Forward and reverse Dijkstra searches stop at `B`. Corridor assembly visits
only outgoing arcs of settled forward-active vertices. The corridor stores
stable vertex IDs, directed arc IDs, active cells, outgoing and incoming
adjacency, plus a SHA-256 checksum.

Prepared edge minima are reused through `QueryLowerBounds`; query construction
does not rescan all temporal functions. Radius-keyed distance searches are
cached. Connector heuristics run in reverse over corridor incoming arcs, so
connector calls do not perform whole-USA scans.

### B. Prefix-stable pivot selection

`PivotSelector.select`
(`src/main/java/edu/ipcmax/core/pcmax/PivotSelector.java:35`) retrieves
score-bearing arcs only through active `ScoreSupportIndex` cells, filters them
to the corridor, and computes:

- maximum score in the conservative time range;
- positive-score temporal coverage;
- lower-bound detour.

It ranks deterministically within cells and then performs a stable spatial
round-robin. Any top-`L1` selection is a prefix of top-`L2` for `L1 <= L2`.
Only selected pivots are removed from the connector graph. Non-selected
score-bearing arcs remain connector arcs and contribute their exact scores.

### C. Candidate representation

`PartialCandidate`
(`src/main/java/edu/ipcmax/core/pcmax/PartialCandidate.java:22`) holds the
endpoint, exact candidate profile/domain, cloned visited-vertex and used-pivot
bit sets, pivot depth, deterministic SHA-256 candidate ID, and cap provenance.

`PathPointer.CompositePathPointer` is a persistent prefix/suffix pair. Complete
arc sequences are materialized lazily and cached, rather than copied on each
extension.

### D. Deterministic bounded connectors

`BoundedConnectorGenerator.connect`
(`src/main/java/edu/ipcmax/core/pcmax/BoundedConnectorGenerator.java:73`)
implements:

- exact vertex simplicity using a visited bit set;
- selected-pivot exclusion;
- reverse corridor-distance target heuristics;
- residual lower-bound budget pruning;
- identity connectors for equal endpoints;
- fixed `FAST, SCORE, DIVERSITY, FAST` portfolio interleaving;
- canonical path deduplication and deterministic ties;
- `K_c` valid-profile prefix retention;
- hard `M_c` popped-state enforcement;
- exact temporal replay and `M_b` enforcement.

Connector-template and temporal-profile results use query-local
`SingleFlightCache` instances. Cache lookups, hits, misses, and contention
waits are recorded.

PACE-X enumerates every vertex-simple connector path in the corridor and is
intended only for validation fixtures.

### E. Forward layered expansion

`ForwardLayeredFrontierGenerator` starts with the source identity candidate.
For each retained layer state it:

1. reserves canonical `M_q` work and connects to the destination;
2. checks the safe score bound;
3. obtains unused residual-feasible pivots in stable order;
4. reserves work before submitting pivot jobs;
5. connects to each pivot tail;
6. joins prefix, connector, and pivot through full canonical replay;
7. rejects discontinuous or repeated-vertex paths;
8. inserts immediately into the next `(endpoint, depth, used-pivots)`
   frontier.

The engine has no recursive future graph and never first collects an
unbounded query-wide candidate set.

### F. Safe score upper bound

`SafeScoreUpperBound`
(`src/main/java/edu/ipcmax/core/pcmax/SafeScoreUpperBound.java:20`) computes:

`R_q = max_e(max_score(e) / min_travel_time(e))`

and:

`UB_C(t) = S_C(t) + (B - T_C(t)) * R_q`.

Zero-time positive-score edges make the rate infinite and disable pruning.
Pruning requires strict inferiority at both closures and owned endpoints of
every exact temporal cell; equality is retained for downstream travel/path
tie-breaking.

### G. Incremental frontier

`IncrementalFrontier.insert`
(`src/main/java/edu/ipcmax/core/pcmax/IncrementalFrontier.java:46`) normalizes,
checks `M_b`, enforces the emergency guard, and immediately invokes the
existing exact duplicate removal, extension-safe dominance, cell partitioning,
bounded retention, and adjacent merge path.

The representative stream is canonical and prefix-truncated:

1. cell champion;
2. earliest arrival;
3. greatest temporal coverage;
4. least restrictive path;
5. canonical fill rank.

Duplicate representative choices are skipped without changing the stream.
Consequently a larger `K_f` includes every path retained by a smaller `K_f`
when no other cap intervenes.

### H. Status and exactness

`PaceGenerationResult`
(`src/main/java/edu/ipcmax/core/pcmax/PaceGenerationResult.java:9`) returns:

- retained candidate frontier;
- `COMPLETE`, `NO_FEASIBLE_PATH`, `RESOURCE_TRUNCATED`, or `ABORTED`;
- `GLOBAL_CERTIFIED`, `RETAINED_FRONTIER`, or `NOT_CERTIFIED`;
- the complete typed cap set and deterministic canonical cap work item;
- counters, corridor checksum, selected pivot IDs, and candidate-output
  checksum.

The result-record schema is v3. It includes engine mode, all six controls,
generation completion, cap array, partial-output policy, certificate
conditions, corridor/candidate/cache/worker counters, wall time, process CPU
time, and heap samples.

### I. Parallel execution

`IPCMaxParallelExecutor.invokeAllDeterministic`
(`src/main/java/edu/ipcmax/core/pcmax/IPCMaxParallelExecutor.java:58`) uses a
fixed worker count and bounded queue. Saturation executes in the submitting
thread rather than creating an unbounded backlog. Results reduce in submission
order. Failure/interruption cancels sibling futures, preserves interruption,
and `close` waits for termination before forcing shutdown.

Cap work-item selection uses deterministic canonical ordering rather than
worker completion order. Candidate output checksums exclude scheduling
observations.

## Instrumentation and analysis artifacts

- `PaceGenerationStats` records all requested corridor, pivot, connector,
  candidate, cap, frontier, cache, and worker counters.
- `PaceExperimentAlgorithm` transfers those counters and status scalars to
  the experiment framework.
- `PaceBench` emits schema v3 and samples wall, process CPU, and JVM heap.
- `experiments/scripts/collect_results.py` exports engine controls, caps, and
  internal counters.
- `experiments/scripts/summarize_results.py` aggregates CPU, heap, cap rate,
  corridor size, expansions, candidate counts, and total work.
- table T8 consumes the new work/cap summaries; table T6 consumes CPU and heap.

## Verification evidence

Focused production-path tests:

- corridor feasible-path property:
  `QueryCorridorPropertyTest.everyArcOnEveryLowerBoundFeasibleSimplePathSurvives`;
- non-pivot score replay, hard caps, looplessness, `L`/`K_c` prefixes,
  hand-computable score bound, PACE-X abort semantics, and legacy diagnostics:
  `ScalablePaceCandidateEngineTest`;
- `K_f` prefix nesting and representative order:
  `FrontierCompressorTest`;
- single-flight contention:
  `SingleFlightCacheTest`;
- canonical reduction, cancellation, and worker termination:
  `IPCMaxParallelExecutorTest`;
- typed cap/status/result serialization:
  `PaceBenchFrameworkTest`;
- repeated and cross-thread byte equality:
  `PacePublicApiOracleIntegrationTest`.

Exactness corpus:

- `PaceExactOracleDifferentialTest` uses
  `SEEDED_CORPUS_CASES = 1000`;
- all seeded FIFO multigraph cases, parallel-arc cases, endpoint cells,
  compressed/uncompressed cases, and public PACE-X envelope comparisons passed;
- observed mismatch count: zero.

Observed final gates:

- `mvn test`: 174 tests passed after the final reusable-index,
  corridor-heuristic, and connector-cache refinements;
- `python3 -m pytest -q experiments/tests`: 20 passed;
- v3 smoke Java records: 6 records, 0 schema errors;
- `make paper-smoke`: completed all stages, with 6/6 terminal jobs successful,
  validation hard gates passed, and 8 plots plus 8 tables generated.

## Changed-file inventory

Core engine and reusable query preparation:

- `src/main/java/edu/ipcmax/core/cache/SingleFlightCache.java`;
- `src/main/java/edu/ipcmax/core/index/` (edge summaries, lower-bound oracle,
  deterministic partition metadata, score-support index, prepared bundle);
- `src/main/java/edu/ipcmax/core/pcmax/BoundedConnectorGenerator.java`,
  `ConnectorResult.java`, `ForwardLayeredFrontierGenerator.java`,
  `IncrementalFrontier.java`, `PartialCandidate.java`, `PivotIndex.java`,
  `PivotSelector.java`, `QueryCorridor.java`, and
  `SafeScoreUpperBound.java`;
- `src/main/java/edu/ipcmax/core/pcmax/PaceCapKind.java`,
  `PaceCapStatus.java`, `PaceCompletion.java`, `PaceEngineMode.java`,
  `PaceExactnessScope.java`, `PaceGenerationResult.java`, and
  `PaceWorkLedger.java`;
- extended `PACE.java`, `PaceOptions.java`, `PaceGenerationStats.java`,
  `QueryLowerBounds.java`, `CanonicalPathProfileBuilder.java`,
  `ConnectorProfiles.java`, `FrontierCompressor.java`,
  `IPCMaxParallelExecutor.java`, `CandidateProfile.java`, and
  `PathPointer.java`.
- `PaperQuerySetGenerator.java` now chunks independent
  `(source, departure-range)` fixed-departure budget work through the same
  bounded deterministic executor. This closes the serial-preparation defect
  discovered by real-data validation without changing query semantics.

Execution, schema, and analysis integration:

- `BenchOptions.java`, `PaceBench.java`, `PaceCli.java`,
  `PaceExperimentAlgorithm.java`, `AlgorithmConfig.java`,
  `AlgorithmResult.java`, `ExperimentAlgorithm.java`, and
  `ProfileSupport.java`;
- `experiments/schemas/result_record.schema.json`;
- `experiments/scripts/collect_results.py`,
  `experiments/scripts/summarize_results.py`,
  `experiments/tables/make_all_tables.py`, and
  `scripts/summarize_results.py`;
- `experiments/README.md`, `experiments/configs/paper_q1.yaml`, and
  `Makefile`.

Engine verification:

- `ScalablePaceCandidateEngineTest.java`,
  `QueryCorridorPropertyTest.java`, `FrontierCompressorTest.java`,
  `IPCMaxParallelExecutorTest.java`, `SingleFlightCacheTest.java`,
  `PacePublicApiOracleIntegrationTest.java`, and
  `PaceExactOracleDifferentialTest.java`;
- experiment option, framework, schema, CLI, and result exactness tests under
  `src/test/java/edu/ipcmax/experiments/`.

The audit and dataset/query preparation files already present in the worktree
were preserved. They are listed separately in
`PACE_REPOSITORY_AUDIT.md`, `PACE_REUSE_AND_GAP_MAP.md`,
`PACE_FINAL_ALGORITHM_IMPLEMENTATION_PLAN.md`,
`DATASET_GENERATION.md`, and `QUERY_SET_GENERATION.md`.

## Observed commands

The acceptance evidence came from these repository commands:

```text
mvn test
python3 -m pytest -q experiments/tests
make paper-smoke
python3 scripts/validate_results.py --schema experiments/schemas/result_record.schema.json experiments/results/pace_q1_smoke/work/*/java-result.jsonl
git diff --check
```

The paper smoke target ran only its six demo jobs. No command that starts the
NY/FLA/CAL/USA experiment matrix was invoked.

Two preparation diagnostics were also observed:

```text
make paper-preflight-server
make paper-generate-queries
```

The first failed closed on legacy query budget-definition metadata after its
deep graph-asset checks. The second canonically completed NY, then was stopped
during FLA after the exact-routing scalability blocker described below was
measured. Neither command executes the PACE candidate algorithm.

## Dataset metadata note

The graph/temporal payloads were not regenerated. Existing NY, FLA, CAL, USA
and NY variant manifests predated mandatory checksum fields. Their structural
and temporal checksums were computed using the repository's existing framed
checksum utility and written into the ignored data manifests. The Python asset
validation suite then passed for all required datasets and variants.

The deep server preflight subsequently verified the real graph payloads but
stopped on the existing real query manifests: all four had been produced by
the retired `python-graph-witness-querygen-v1` path and declared
`GRID_LOWER_BOUND_WITNESS_PATH_TRAVEL_TIME`, not the required
`GRID_FIXED_DEPARTURE_FASTEST_TRAVEL_TIME`.

Canonical regeneration through `PaperQuerySetGenerator` completed NY and
produced `pace-paper-query-preparation-v2` records with `Delta = 1` and
`t_hat_min_delta`. Its 2,960 combined rows passed the standard validator, the
strong preparation validator, sidecar checksum validation, and exact base-pair
counts (20 pilot, 10 warm-up, 100 evaluation). FLA exposed a remaining
external scalability blocker:
after bounded 24-worker departure-chunk parallelization, exact one-minute-grid
time-dependent Dijkstra had consumed about 300 aggregate CPU-minutes without
finishing FLA. CAL and USA were therefore not started. The temporary FLA run
was stopped before its atomic destination write; no experiment was run.

## Remaining scope

There is no candidate-engine implementation blocker. The scalable production
path implements the target design and the paper-smoke gates pass.

There is an `EXTERNAL_BLOCKER` for the earlier all-four-dataset query
preparation gate: the repository has no scalable exact time-dependent
fixed-departure routing index/oracle for materializing every one-minute budget
grid on FLA/CAL/USA. The existing exact `PointForwardLabeling` fallback is
correct and now bounded-parallel, but its observed real-data runtime is not
practical for USA. Until such an index is supplied or implemented, FLA, CAL,
and USA retain their legacy query manifests and full server preflight must
fail closed.

The full NY/FLA/CAL/USA experiment matrix remains deliberately unstarted. Its
runtime, memory, and quality claims still require the separately authorized
full server run.
