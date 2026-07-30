# PACE Timeout Bottleneck Analysis

> **Historical timeout evidence.** This report explains the stopped,
> pre-optimization run. Its 53,286-job plan and old temporal-retention
> implementation are retained for provenance, not as the current readiness
> decision. See `docs/PACE_PAPER_READINESS_FINAL_REPORT.md`.

Date: 2026-07-29 UTC

Run ID: `pace_q1_full_24c_250g_100g_nousa_clean_20260729T022350Z`

## Executive conclusion

The full experiment launcher, controller, watcher, and Java workers were
stopped. A host-level process check found no remaining Java or Python process.
The launcher now reports `lifecycle=UNKNOWN` because it was externally stopped
before it could write an exit record; this is a stopped run, not a running
experiment.

The timeouts are not caused by the NY dataset, the 250 GB heap limit, connector
search reaching `M_c`, total query work reaching `M_q`, a deadlock, or GC pause
time. The dominant bottleneck is repeated, serial, full-frontier temporal cell
reconstruction and profile rebuilding during incremental frontier compression.
The innermost time comparison performs decimal-string conversion and
`BigDecimal` rounding for already-repeated breakpoint values.

The strongest causal result is the repository's existing `no-compression`
ablation on the exact timed-out evaluation query:

- normal PACE-B did not complete in 1,800 seconds;
- `no-merge` still did not complete in a 360-second diagnostic window;
- `no-compression` completed in exactly `204.126899262` query seconds;
- it used only 80 total candidate-work reservations and 20,423 connector
  expansions, generated 316 candidates, and triggered no cap.

This establishes frontier compression/cell construction as the primary cause.
It also exposes secondary copies of the same cost in statistics collection and
final envelope extraction.

## Stopped-state and evidence inventory

The repository currently contains:

- 13 raw records: 6 `SUCCESS` fixture records and 7 `TIMEOUT` records;
- 9 archived `INTERNAL_ERROR` records from the earlier invalid multi-process
  attempt; these are not timeouts;
- no live Java or Python experiment process;
- 53,286 planned matrix jobs, with the run stopped in E03.

The serialized count is seven timeouts, not ten. Five E03 timeout records are
repeated trials of only two evaluation query instances. If a status display
showed ten attempted jobs, three did not become serialized `TIMEOUT` records in
this run directory.

The diagnostic recordings are under:

`experiments/results/diagnostics/pace_timeout_20260729`

| Recording | Duration | Outcome | Query-worker allocation | GC pause |
|---|---:|---|---:|---:|
| `eval_c510_threads1.jfr` | 359 s | bounded diagnostic stop | 473.8 GB | 2.05 s |
| `pilot_c1110_threads1.jfr` | 240 s | bounded diagnostic stop | 302.9 GB | 1.61 s |
| `eval_c510_no_merge.jfr` | 360 s | bounded diagnostic stop | 449.8 GB | 1.24 s |
| `eval_c510_no_compression.jfr` | 223 s JVM / 204.126899262 s query | completed | completed record available | 1.60 s |
| `eval_c510_threads24.jfr` | 150 s | bounded diagnostic stop | 165.4 GB on serial query worker | recorded in JFR |

SHA-256 checksums:

| Recording | SHA-256 |
|---|---|
| `eval_c510_threads1.jfr` | `db313919f13971b8de71a365754e6b3ff92af8fc54a5bf5ef0e40aa6de0295a8` |
| `pilot_c1110_threads1.jfr` | `929a9ffada8abf37837345bf44e67f7c6843bf130a88060cb5245cf24bbb2121` |
| `eval_c510_no_merge.jfr` | `e8ca8e00605890228d8302e579b85fd7e7bd2a8c97a64ae1d4c89d00ce80bf7c` |
| `eval_c510_no_compression.jfr` | `2c6d080e25d11f465b7d1271463458bd01be6cc7f646e15bea481c3afd4734e1` |
| `eval_c510_threads24.jfr` | `d4da9abe7dfdc2e9f2beaeed4a9e09010ae01ffa243e564407665ad4711d831d` |

## Timeout records

Every serialized timeout used PACE-B, one thread, `L=4`, `K_c=8`, `K_f=8`,
`M_c=5,000,000`, `M_b=1,000,000`, `M_q=5,000,000`, and a 1,800-second
algorithm timeout.

| Study | Job | Query | Raw wall time |
|---|---|---|---:|
| E02 | `066db9f5b39f212315532751` | `NY-PILOT-P001-C1110-W120-RHO030` | 1,837.904 s |
| E02 | `0721881706bfabe0e6ed9265` | `NY-PILOT-P001-C510-W120-RHO030` | 1,839.238 s |
| E03 | `3b74f3aa35b27352af6e3998` | `NY-EVAL-P001-C1110-W120-RHO030` | 1,842.976 s |
| E03 | `6cd3b8583cda63897a06610d` | `NY-EVAL-P001-C510-W120-RHO030` | 1,841.256 s |
| E03 | `a77ae2b11a5db635b6dac987` | `NY-EVAL-P001-C510-W120-RHO030` | 1,838.946 s |
| E03 | `b667556eeb2b24aaedc7defa` | `NY-EVAL-P001-C510-W120-RHO030` | 1,839.081 s |
| E03 | `f40a7bee2aca6ccb12d82ac1` | `NY-EVAL-P001-C1110-W120-RHO030` | 1,840.527 s |

The approximately 1,839-1,843 second raw wall times are consistent with the
1,800-second child limit plus process termination and forced-record fallback
overhead.

## Why the timeout records contain no usable phase timings

`PaceBench.runIsolated` waits for `timeout + 15` seconds, forcibly destroys the
query worker, and then launches a new fallback JVM to serialize a forced
`TIMEOUT` result:

- `src/main/java/edu/ipcmax/experiments/PaceBench.java:227`
- `src/main/java/edu/ipcmax/experiments/PaceBench.java:234`
- `src/main/java/edu/ipcmax/experiments/PaceBench.java:242`

The fallback creates a fresh `ExperimentInstrumentation`, sets
`query_total=0`, and has no counters or phase state from the killed worker:

- `src/main/java/edu/ipcmax/experiments/PaceBench.java:421`
- `src/main/java/edu/ipcmax/experiments/PaceBench.java:426`
- `src/main/java/edu/ipcmax/experiments/PaceBench.java:427`

Consequently, the non-null `preprocessing_total` in a timeout record belongs
to the fallback serialization JVM's graph/index preparation, not to the
terminated query worker's exact execution timeline. All internal timing and
counter fields are null. Exact completed phase durations cannot be recovered
from those timeout JSON records after the fact.

This is an instrumentation defect that should be fixed before the next full
run: timeout-safe progress snapshots must be written by the live worker or
sampled by the parent.

## Production call graph and bottleneck

The production path is:

1. `PaceExperimentAlgorithm.run`
   (`src/main/java/edu/ipcmax/experiments/algorithms/PaceExperimentAlgorithm.java:45`)
2. `PACE.run`
   (`src/main/java/edu/ipcmax/core/pcmax/PACE.java:116`)
3. `ForwardLayeredFrontierGenerator.generate`
   (`src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:70`)
4. `reduceFinal`
   (`src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:414`)
5. `IncrementalFrontier.insert`
   (`src/main/java/edu/ipcmax/core/pcmax/IncrementalFrontier.java:46`)
6. `FrontierCompressor.compress`
   (`src/main/java/edu/ipcmax/core/pcmax/FrontierCompressor.java:46`)
7. `ProfileCellPartition.cells`
   (`src/main/java/edu/ipcmax/core/pcmax/ProfileCellPartition.java:18`)

### Recompressing the whole retained frontier on every insertion

`IncrementalFrontier.insert` copies every retained candidate, adds one new
candidate, and recompresses the complete set:

- copy and append:
  `src/main/java/edu/ipcmax/core/pcmax/IncrementalFrontier.java:65`
- full compression:
  `src/main/java/edu/ipcmax/core/pcmax/IncrementalFrontier.java:68`

This is not an incremental update of only affected temporal cells.

### Pairwise temporal equality reconstruction

For every compression call, `ProfileCellPartition.cells` collects every domain,
arrival, and score breakpoint and then evaluates every candidate pair for
travel-equality roots:

- breakpoint collection:
  `src/main/java/edu/ipcmax/core/pcmax/ProfileCellPartition.java:22`
- pairwise loop:
  `src/main/java/edu/ipcmax/core/pcmax/ProfileCellPartition.java:31`
- equality-cell partition:
  `src/main/java/edu/ipcmax/core/pcmax/ProfileCellPartition.java:66`

This makes cell construction at least quadratic in retained candidate count
before accounting for profile breakpoint work. It is repeated after each
insertion.

### Quadratic score-breakpoint deduplication

`ScoreProfile.breakpoints` appends both endpoints from every interval and calls
`addDistinct`:

- endpoint loop:
  `src/main/java/edu/ipcmax/core/profile/ScoreProfile.java:133`
- linear duplicate scan:
  `src/main/java/edu/ipcmax/core/profile/ScoreProfile.java:382`

The scan calls `Domain.sameTime` for each existing point:

- `src/main/java/edu/ipcmax/core/profile/ScoreProfile.java:392`

Therefore one breakpoint list construction is quadratic in score interval
count. Compression, restriction, metric calculation, and merging repeatedly
reconstruct that list.

### Decimal conversion in the innermost comparison

`Domain.sameTime` canonicalizes both operands:

- `src/main/java/edu/ipcmax/core/function/Domain.java:527`

`Domain.canonicalTime` converts a double through
`BigDecimal.valueOf(value).setScale(12, HALF_EVEN)`:

- scale declaration:
  `src/main/java/edu/ipcmax/core/function/Domain.java:26`
- conversion:
  `src/main/java/edu/ipcmax/core/function/Domain.java:517`

The normal evaluation profile's top nine sampled leaf methods—all decimal
conversion/rounding implementation—account for 90.12% of execution samples.
The top seven allocation sites for `BigDecimal`, decimal strings, byte/char
arrays, and `DoubleToDecimal` account for 95.17% of sampled allocation pressure.

The independent pilot profile reproduced the same call stacks and allocation
rate. Four live stacks from the evaluation query and three from the pilot query
found the query worker in:

- score breakpoint restriction/merging;
- score integration for bounded retention;
- temporal equality-cell construction;
- all reached through `FrontierCompressor.compress` during `reduceFinal`.

### Repeated post-generation cell construction

The same expensive cell constructor runs again for statistics:

- `IncrementalFrontier.cellCount`:
  `src/main/java/edu/ipcmax/core/pcmax/IncrementalFrontier.java:99`
- final stats snapshot calls it:
  `src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:874`

It runs again during envelope extraction:

- `src/main/java/edu/ipcmax/core/pcmax/EnvelopeExtractor.java:31`

The completed `no-compression` diagnostic was observed first in final
`cellCount`, then in `EnvelopeExtractor.extract`, confirming these secondary
costs.

## Causal ablation details

### Normal, one thread

`NY-EVAL-P001-C510-W120-RHO030`:

- did not finish in the original 1,800-second limit;
- did not finish in the 359-second diagnostic profile;
- query worker allocated 473.8 GB transiently;
- live heap after collection was approximately 1.8 GB, with observed used heap
  oscillating roughly 5-11 GB;
- total GC pause was 2.05 seconds over 359 seconds.

This is CPU/allocation churn, not heap exhaustion or stop-the-world GC.

### Different pilot query, one thread

`NY-PILOT-P001-C1110-W120-RHO030`:

- did not finish in the original 1,800-second limit;
- did not finish in the 240-second diagnostic profile;
- query worker allocated 302.9 GB transiently;
- total GC pause was 1.61 seconds;
- reached the same compression and breakpoint stacks.

This rules out a single source-destination pair as the cause.

### No adjacent merge

The same evaluation query with `ablation=no-merge`:

- still did not finish in 360 seconds;
- allocated 449.8 GB on the query worker;
- remained in `ProfileCellPartition.cells` inside `FrontierCompressor.compress`;
- spent only 1.24 seconds in GC pauses.

Adjacent fragment merging adds cost, but is not the primary defect.

### No compression

The same evaluation query with `ablation=no-compression` completed:

- query time: `204.126899262` seconds;
- preprocessing time: `24.646489349` seconds;
- peak RSS: `23,654,141,952` bytes;
- peak heap: `17,018,022,040` bytes;
- corridor: 532 nodes, 1,178 edges, 4 cells;
- score-relevant edges: 228;
- selected pivots: 4;
- connector calls: 80;
- connector expansions: 20,423;
- valid connectors: 349;
- generated and retained candidates: 316;
- total candidate work: 80;
- peak frontier size: 252;
- final envelope: 13 intervals and 5 selected paths;
- cap hits: none.

The result record is:

`experiments/results/diagnostics/pace_timeout_20260729/eval_c510_no_compression.jsonl`

This ablation changes algorithm behavior and is diagnostic evidence only; it is
not proposed as the final experimental configuration.

## Why 24 threads do not fix this timeout

Parallel tasks are created only for pivot connector calls:

- task construction:
  `src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:232`
- parallel invocation:
  `src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:265`
- deterministic serial reduction:
  `src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:269`

Final connector generation and `reduceFinal` run on the query/reducer thread:

- `src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:179`
- `src/main/java/edu/ipcmax/core/pcmax/ForwardLayeredFrontierGenerator.java:189`

In the 24-thread JFR:

- the query worker started at 11:12:25 UTC;
- only four `pace-worker` threads were created, at 11:13:11 UTC, matching
  `L=4`;
- the query/reducer thread allocated 165.4 GB, or 82.84% of all allocation;
- all four connector workers together allocated under 0.5 GB;
- connector-worker CPU load was negligible compared with the serial query
  worker;
- sampled hot methods remained decimal canonicalization.

Setting `threads=24` is valid for one-query-at-a-time execution, but it cannot
parallelize the current compression/cell-partition bottleneck.

Also, every recorded E02/E03 timeout actually used `threads=1`, so those
records did not implement the intended 24-thread per-query policy.

## Cap semantics do not protect this work

`M_q` increments once for a canonical connector work item:

- `src/main/java/edu/ipcmax/core/pcmax/PaceWorkLedger.java:27`

It does not count:

- candidate-profile replay operations;
- breakpoint comparisons;
- candidate-pair equality roots;
- temporal cells constructed;
- compression calls;
- dominance comparisons;
- fragment restrictions or merges;
- final statistics/envelope cell construction.

`M_b` checks the breakpoint count of one candidate/profile:

- `src/main/java/edu/ipcmax/core/pcmax/PaceWorkLedger.java:46`

It does not cap the cumulative number of breakpoint operations across repeated
recompression. This is why the completed diagnostic reports only 80 units of
query work while consuming 204 seconds without compression, and the normal run
can exceed 1,800 seconds without any cap firing.

## Resolution order for discussion

No algorithm fix was applied during this analysis. The recommended order is:

1. Make timeout instrumentation trustworthy.
   Persist phase transitions, elapsed nanoseconds, counters, and memory from the
   live worker so a forced timeout preserves partial progress. Add explicit
   timings for corridor construction, pivot selection, connector search, path
   replay, breakpoint collection, equality-root construction, dominance,
   bounded retention, fragment restriction/merge, stats, and envelope
   extraction.
2. Replace full-frontier recompression on every insertion with a genuinely
   incremental temporal-cell frontier update. Recompute only cells touched by
   the new candidate and retain cached breakpoints/metrics for unchanged
   candidates.
3. Store canonical breakpoints once in `ScoreProfile` and `TimeProfile`.
   `breakpoints()` must not perform a quadratic duplicate scan on every call.
4. Remove `BigDecimal`/decimal-string conversion from inner comparisons while
   preserving the repository's 12-decimal half-even contract. This requires a
   correctness-preserving canonical tick representation or an equivalent
   pre-canonicalized representation, followed by boundary and determinism
   tests.
5. Reuse a single temporal partition between compression, statistics, and
   envelope extraction where the retained frontier is unchanged.
6. Extend `M_q`/instrumentation with measurable compression work, such as
   candidate-pair checks, breakpoint operations, and temporal cells. A query
   must terminate with an explicit retained-frontier cap status before the
   external 1,800-second kill.
7. After the serial work is reduced, re-evaluate deterministic parallelism.
   Parallel connector tasks alone cannot compensate for serial quadratic or
   worse cell reconstruction.

The next run should not be launched until items 1-6 are implemented and the
two profiled queries complete within the intended timeout under the normal
PACE-B configuration.
