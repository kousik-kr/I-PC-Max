# Prospective ICDE 2027 experiment family, version 1

These configurations are for future runs. They preserve the original E03/T01/T03 files and never reuse historical result rows. Loading or planning this family does not authorize an experiment.

| Configuration | Methods | Objective and scope | Explicit query caps | Threads |
|---|---|---|---|---|
| preference.yaml | pace-b, iscope, sicsp-pref | Identical NY/FLA/CAL query IDs, intervals, budget and score inputs; versioned E03 default and T03 sensitivity views | 10 s, 5 s, 10 s respectively | 1 each |
| fastest.yaml | thop-partitioned | Matching default graph/query inputs; original fastest-arrival profile, budget and preference ignored | 10 s, index preparation separately accounted | 1 |
| reference.yaml | pace-x, pace-b, iscope, sicsp-pref | NY-EXACT only; accept a PACE-X quality reference only when the actual result is GLOBAL_CERTIFIED | 30 s, 10 s, 5 s, 10 s respectively | 1 each |
| sicsp_native_figure1.yaml | sicsp-native | Standalone deterministic original Figure 1 fixture, native Delta=80; schema budget is ignored | 30 s | 1 |

The methods have different candidate and resource semantics. The caps above are explicit choices for this new family; they are not a reconstructed historical timeout boundary. Capped iSCOPE profiles remain uncertified. PACE-B and SICSP-Pref are candidate-limited methods, and completed generation does not imply global optimality. A PACE-X result with insufficient pivot/depth coverage cannot serve as a certified reference.

`common/config.py` validates objective separation, required method IDs, matched preference workloads, explicit cap policies, one-thread allocation and disabled historical reuse. The underlying runner preserves method-specific completion/status/coverage metadata. Report completion with a terminal denominator, successful-only timing/quality with a successful denominator, and capped-profile coverage separately. An absent metric remains absent; it is never synthesized from a method with another objective. Integrated preference score, when derived from a profile, must account for feasible interval length and endpoint ownership; the median of query averages must not be labeled an arithmetic mean.

THop uses the existing partitioned rational index. `fastest.yaml` only loads an existing v2 index. Required complete partition metadata and graph-bound BTHP/PTHP files must be built and frozen first; no real-network index is claimed to exist. Its construction metadata must disclose `SEARCH_FIRST_THEN_PRUNE_MATERIALIZE`. Missing indexes or unsupported coverage are errors, never preference NO_PATH results. Preprocessing, loading, first cold query, subsequent warm query, disk bytes and query functional work are reported separately. Do not compute a preference score gap for THop.

`sicsp_native_figure1.yaml` is an explicit CLI reproduction specification, not a fourth preference-aware method or a paper-design `load_design` document. Its weights are supplied separately from preference scores. Its native weight bound is 80; the query JSON budget field is a compatibility placeholder. For a tiny local reproduction, use its `cli` array with the benchmark JAR. No medium/full network run is authorized by this fixture.

## Required freeze before dispatch

`execute_matrix._command` rejects this family when its configured freeze is missing, unapproved or differs in source files, JAR, configuration, dataset payload, workload or resource settings. Verification hashes the bytes, then caches only unchanged file identities within a runner. The validated freeze is archived in the new run's provenance directory, with no overwrite of a different run identity. Final report prose may refer to the release source commit without requiring a recursive final-document commit identity.

`freeze_benchmark.py` records identities without starting any query. It requires a clean release checkout, a benchmark JAR identical to that checkout's `target/pace-bench.jar`, explicit dataset/query manifest paths, dataset payload byte checksums, source/config/schema/fixture inventories, runner/aggregator hashes, environment, seeds and caps. It refuses dirty source, missing artifacts or an existing output file. THop freezes additionally require its partition and index artifacts. It defaults to `readiness.approved=false`; only separate run authorization may change that policy in a new freeze.

Example preparation command (paths must refer to real validated artifacts):

```bash
python3 experiments/scripts/freeze_benchmark.py \
  --design experiments/configs/icde2027_v1/preference.yaml \
  --source-repository /tmp/icde2027-clean-release \
  --jar target/pace-bench-icde2027-v1.jar \
  --dataset-manifest NY=data/input/NY/manifest.json \
  --dataset-manifest FLA=data/input/FLA/manifest.json \
  --dataset-manifest CAL=data/input/CAL/manifest.json \
  --query-manifest NY=experiments/manifests/queries/NY/paper_q1.jsonl \
  --query-manifest FLA=experiments/manifests/queries/FLA/paper_q1.jsonl \
  --query-manifest CAL=experiments/manifests/queries/CAL/paper_q1.jsonl \
  --run-series icde2027-v1-release-identity-only \
  --output experiments/manifests/icde2027_v1/freezes/preference.json
```

This creates an unapproved identity record. It does not clear iSCOPE paper fidelity, SICSP-Pref numerical correctness, partition availability or the M6/M7 authorization gate. Those dispositions are in the M5-R report. Existing raw artifacts remain historical evidence.
