# ICDE 2027 M5-R6 Performance Report

**Run date:** 2026-10-17  
**Decision:** Full campaign is **NO-GO**. The designated NY iSCOPE query did not produce a validated feasible route within the 10-second deadline.

M5-R6 ran one iSCOPE query and one full-network SICSP-Pref query. Because SICSP-Pref reached its watchdog, one existing synthetic fixture query was run to characterize its candidate and profile stages. No 50-query workload, PACE-X run, or parallel iSCOPE run was started.

## A. 10-second iSCOPE result

`iscope-optimized` completed with `TIME_CAPPED_NOT_CERTIFIED`. Its status reports `ISCOPE_10_SECOND_CAP`; the declared query deadline was 10 seconds, with one worker. The run preserved Opt1 pruning, the existing 2-minute Opt2 threshold, the existing roulette rule and seed policy, exact replay, and the existing result schema.

The total query measurement was 9.013 seconds in the algorithm result and 9.041 seconds in the PaceBench wrapper. Dataset loading took a separate 15.975 seconds. The deadline did not overrun.

## B. Query identity and selection

The selector ranked the 50 existing NY `compact_50` records by ascending distance band, lower-bound distance, fastest-path-derived `t_hat_min_delta`, budget, and query ID. It selected rank one before running either algorithm.

| Field | Value |
|---|---:|
| Query ID | `NY-EVAL-P003-C510-W120-RHO030` |
| Source / destination | `198026 / 199179` |
| Departure interval | `[450, 570]` minutes |
| Distance band | B1 |
| Lower-bound distance | 22.918166667 |
| `t_hat_min_delta` | 23.349095428732 |
| Budget (`rho=0.30`) | 30.353824057352 |
| Witness path checksum | `cb01da15b6c36dccb2a8a2b307ddc94f52a45ef50dfc102da0e750a275db16fb` |

The selected row is byte-for-byte the existing historical record. Its one-row manifest SHA-256 is `ea476f7da1f51ebd4fb19e4d54eb9aa8faa980e29deabf2f911b70d74585f153`.

## C. iSCOPE feasibility and profile

No complete route was discovered. The selected-route registry is empty, exact feasible coverage is zero, and the output has three `NO_PATH` cells covering the requested departure domain. No selected score or travel time exists. The exact profile checksum is `339973676679e3f93d3b9fda223fe28d18f1e7c755863c4aaf4bfb982b7c1a3b`.

This is a time-capped partial search result, not a proof that the graph has no feasible route. It fails the M5-R6 success condition because it contains no selected exactly budget-feasible cell.

## D. iSCOPE search, finalization, and memory

| Measurement | Result |
|---|---:|
| Exact lower-bound preparation inside query deadline | 7.257259 s |
| Algorithm search | 1.698659 s |
| Exact candidate replay and envelope | 0.012398 s |
| Finalization reserve | 1.000000 s |
| Measured query elapsed | 9.012680 s |
| PaceBench query wrapper total | 9.040592 s |
| Reverse lower-bound edges scanned / weights read | 733,846 / 733,846 |
| Search states / edge expansions / partial labels | 67 / 173 / 118 |
| Completed paths evaluated | 0 |
| Peak JVM heap / process RSS | 6.378 GB / 7.603 GB |

The search deadline includes lower-bound preparation, search, and finalization. The implementation uses a 1-second finalization reserve; the exact final replay phase took about 12.4 ms because no complete path had been emitted. The Java watchdog allows 2 seconds of cooperative cleanup after the algorithm deadline, and the Python runner adds its separate preprocessing allowance and 90-second process grace. Neither changes the algorithm’s 10-second deadline.

The precise failure point was the search deadline after lower-bound preparation consumed about 7.26 seconds. Search then visited 67 states and expanded 173 edges without completing a source-to-destination route.

## E. THop indexing and query progress

No NY, FLA, or CAL BTHP/PTHP v2 index exists under `data/indices`, and no frozen real-network THop index was found. The only local index pair inspected was a temporary demo fixture; it does not match the NY graph or a frozen partition and was not used.

The existing [THop M2C-B implementation report](ICDE_2027_THOP_M2C_B_PARTITIONED_IMPLEMENTATION_REPORT.md) documents an exactness blocker: an unsupported profile-composition endpoint and a one-tick fractional-breakpoint mismatch. The implementation therefore does not currently provide a validated exact partitioned result for a measured city query. No new partition or index construction was launched, and no city query time is claimed.

## F. THop feasibility and prior small-fixture costs

The [THop index feasibility report](ICDE_2027_THOP_INDEX_FEASIBILITY.md) records deterministic spatial-median metadata for 64 shards: NY has 9,993 boundary vertices, FLA 16,181, and CAL 19,171. These are spatial-shard counts, not Natural Cut counts. That report also records a partial 128-vertex NY induced-graph attempt: 5 of 39 boundary sources, 195 profiles, 14,488 profile pieces, 2,289,856 serialized bytes, about 6.3 GB peak RSS, and 3 minutes 14 seconds of CPU time. It did not produce a complete index; full-network partition construction time and peak JVM heap remain unavailable.

Previously recorded performance on the 10-vertex, 15-arc, 5-partition correctness fixture is retained as tiny-fixture evidence only:

| Stage | Prior measurement |
|---|---|
| Boundary index | 9 source searches; 45 profiles generated; 32 retained; 119 pieces; 6,720 bytes; 212 ms; sampled heap 57.5 MB |
| Inner index | 10 functional searches; 22 profiles generated; 37 directed labels retained; 119 pieces; 87 compositions; 56 envelope operations; 7,641 bytes; 64 ms; sampled heap 74.2 MB |
| Index load | 6.7 ms |
| Cross-partition query | 15.2 ms cold and 10.3 ms warm; 4 boundary combinations; 19 compositions; 6 envelope operations; 4,228 lazy bytes cold |

The known exactness failure applies to the THop implementation, so these timings are not treated as validated THop performance claims.

## G. SICSP-Pref candidate generation and runtime

The full-network run used the same designated NY query as iSCOPE, one worker, a 600-second query watchdog, and a 32 GB memory limit. It ended with `TIMEOUT`. The recorded Java query wrapper total was 630.006 seconds, including the 30-second worker-cancellation wait after the 600-second watchdog. Runner timestamps span 652.193 seconds including about 15.948 seconds of dataset preprocessing and process overhead.

Peak process RSS was 14.394 GB and peak heap was 12.615 GB; the memory limit did not trigger. Process CPU time was 1,311.21 seconds. The worker did not return a completed candidate-generation result, so LST/EAT search calls, O/W counts, candidate counts, profile-construction time, score, coverage, and profile checksum are **unavailable**, not zero. The terminal record contains no preference profile.

## H. SICSP-Pref preference quality on the controlled fixture

Because the full-network run exceeded its watchdog, a separate frozen follow-up used the existing four-node, five-edge synthetic graph and its unchanged `tiny-1-4-wide` query on `[420, 430]`, with budget 60. This fixture result does not characterize full-network quality.

| Measurement | Result |
|---|---:|
| Native SICSP forward / reverse search calls | 20 / 24 |
| O / W temporal events | 10 / 14 |
| Raw O / W points | 0 / 2 |
| Raw / simple / retained candidate routes | 3 / 3 / 3 |
| Retained routes with nonempty exact feasible domains | 3 |
| Candidate generation time | 39.284 ms |
| Candidate adaptation / exact profile replay | 65.740 / 47.856 ms |
| Envelope construction | 12.304 ms |
| Query wrapper total | 265.515 ms |
| Peak heap / process RSS | 55.9 MB / 118.4 MB |

The exact retained-route profile selected path `[0, 1]`, score 8, and travel time 20. Feasible departure coverage was 1.0; integrated preference was 80 over the 10-minute interval. Its checksum is `a69975a75c7f06baff00acefc83b7125772f8d3f598a451476a5f3c6addb7958`. SICSP-Pref reports exactness over its retained route registry only; `candidate_generation_complete=false`, so this is not a global-optimality claim.

## I. Correctness and test totals

| Check | Result |
|---|---|
| `mvn -B -ntp clean test` | 373 passed, 0 failures, 0 errors, 0 skipped |
| Clean release `mvn -B -ntp clean package` | Passed; JAR SHA-256 recorded below |
| `python3 -m pytest -q experiments/tests` | 77 passed |
| `git diff --check` | Passed |
| M5-R6 result validation | 1 planned cell and 1 terminal record validated for each of the three runs |
| Exact profile checksums | Validated for iSCOPE and the completed fixture SICSP-Pref profile; the full-network SICSP-Pref timeout produced no profile |

The M5-R6-aware validator compatibility change is in post-run source commit `8709e0ac990f0db4b75faff94eceab1b5a761802`. It lets one-row fixture manifests without paper pair metadata pass the standard plan and result checks. The executed algorithms and JAR remain identified by their pre-run freezes below.

## J. Freeze and provenance hashes

| Run series | Frozen source commit | Config SHA-256 | Freeze SHA-256 |
|---|---|---|---|
| `icde2027-m5-r6-iscope10-20261017` | `2ea028e0e93bcee82642224658018628d9554775` | `ab5846aa13aea5deb627794d2cbf1d12d03e34064eca7b822d0cbe6a3a559a82` | `379e6b85402dd23f441961736ead0d981418018bd8792532d2e96723df29d959` |
| `icde2027-m5-r6-sicsp-pref-20261017` | `2ea028e0e93bcee82642224658018628d9554775` | `d71068956acb85d2e3552042bef386cf4baa080405bf42c399bc064a5f3a0b8b` | `7dbb1d445cb45c04decfb1560918a4a70ccd606e9eb393caf5d160de72cf31c4` |
| `icde2027-m5-r6-sicsp-fixture-20261017` | `50b35859507b7079bee2465d6fae9bc9fbaa6807` | `8817acd071691c202eaab995ea26de07d173e76eae73137747dbe7f4a8ef854d` | `84d79b94ef2ee4dbae30a3696a396cad8d21708dcc4895a4c8b68cdedf2e1fa9` |

All three freezes have `readiness.approved=true`, identify a clean source tree, and disable historical result reuse. The two NY runs use the same frozen NY dataset manifest and the same one-row query manifest. The clean release JAR SHA-256 is `c9500d9db6a0d9d0a1f6ad2b771ecd34cf335d0809f3ab88b60ee821d0b51cff`; the runner SHA-256 is `9f08d7d4018f5641f438a19968452f70d64dc4998f4edb60564ee20aea026e12`; the aggregator SHA-256 is `7b7d3947c897b363d2905151b74ffe70168372164ecb9f9304c177a85d808d00`. The validator hash frozen before execution was `f88100719701d88ce39f49c0d6640e37e03e3c15a1c0d0c61df34daa22445cdf`; the post-run M5-R6-aware validator source is commit `8709e0a` with script SHA-256 `69396c64ba682cfbcfb63fc991703c5b6315c74240951aca9830f5388664b4d6`.

The original M5-R5 result tree remains byte-for-byte unchanged: 395 files match the pre-run SHA-256 snapshot (`e5df99d5fa9cc2d2f125a199c0b8f5cd79e85ddcf915d0f05a5ade3f7630df57`). Its approved `primary_preference.json` freeze SHA-256 remains `4c3c22a05f513711e28da7a4362ac386ac08f08cb457969f0a81daba0d68ec73`.

## K. Full-campaign recommendation

**NO-GO.** The designated iSCOPE query produced no selected route and zero feasible coverage within 10 seconds. Do not launch the 50-query workloads under this milestone gate. The full-network SICSP-Pref run also exceeded its 600-second watchdog; the fixture result is useful only for controlled-range characterization. THop remains blocked on exact profile-composition correctness, with no validated city index or query result.

The M5-R6 raw runs, freezes, plans, normalized records, and validation reports are under `experiments/results/icde2027_m5_r6/`. The first iSCOPE dispatch attempt is also retained; it was rejected before Java execution because its generated plan was inside the clean frozen checkout. The clean retry is the measured iSCOPE run reported above.
