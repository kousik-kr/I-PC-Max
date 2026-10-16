# ICDE 2027 prospective benchmark freeze

Date: 2026-10-16. This document records identity only. **Both M5-R2 freezes have `readiness.approved=false`; no benchmark campaign was started or approved.** SICSP-Pref's final evaluator is repaired only for its retained candidate registry. These freezes do not cure historical provenance or establish a common exact numerical contract across preference algorithms.

## Source and executable

| Item | Frozen value |
|---|---|
| Branch | `feature/icde27-baseline-implementation` |
| Clean source commit | `72005400036bd244fae407cc65e9a219cd6657d4` |
| Git tree | `954c118a530c56d42486c880019830798955d19e` |
| Source status | Clean release checkout; `source.clean=true` |
| Build command | `mvn -B -ntp -DskipTests package` |
| Build result | `BUILD SUCCESS`, 8.000 s from a clean worktree at the frozen source commit; Maven Shade reported overlapping dependency metadata/resources |
| JAR | `target/pace-bench.jar`, 3,941,325 bytes, SHA-256 `ca26344145908f14595ccac63a9d1879991aa29fbc8b017669f9afbd238f0a53` |
| Java / Maven / Python | OpenJDK 21.0.7 / Maven 3.6.3 / Python 3.10.12 |
| OS | Linux 6.8.0-138-generic, x86_64 |

The JAR is a local ignored build artifact; the freeze records its checksum and the clean source needed to rebuild it. The machine-readable records include a SHA-256 inventory of execution-affecting Java, runner/config, schema, and fixture files, plus runner and aggregator checksums.

## Preference comparison identity

Machine record: [`experiments/manifests/icde2027_v1/freezes/preference_m5r2.json`](../experiments/manifests/icde2027_v1/freezes/preference_m5r2.json). The earlier `preference.json` remains preserved as the pre-repair identity.

| Identity | Value |
|---|---|
| Run series | `icde2027-v1-preference-identity-only-20261016-m5r2` |
| Configuration | `experiments/configs/icde2027_v1/preference.yaml` |
| Effective config hash | `77c1abbc34a962c16b2a5fda0e950bba97ecdd77306dc85122d505641561e2e5` |
| Methods | PACE-B, iSCOPE, SICSP-Pref; same workload identity per dataset |
| Objective | Continuous-interval preference profile; THop is excluded from this track |
| SICSP-Pref numerical contract | `EXACT_RATIONAL_PREFERENCE_PROFILE-v1`, scoped to its generated retained registry |
| Per-method caps | PACE-B 10 s; iSCOPE 5 s; SICSP-Pref 10 s |
| Threads | 1 per query |
| Datasets | NY, FLA, CAL, seed 42, declared centisecond normalization |
| Result namespace | `experiments/results/icde2027_v1/preference`; historical result reuse disabled |
| Execution approval | **false** |

| Dataset | Graph checksum | Dataset manifest SHA-256 | Query manifest SHA-256 |
|---|---|---|---|
| NY | `97999bd124ed9639234bc1a9334b0426911a234b1ed80b5093ec1b30a8ade675` | `b15d1433609e3a44fdb44c1bfb6c282c700057c17010b8e0655844b429fd44d9` | `d825450cbc9f7a0b244ce3e35c5c61a82813c0862eeb5b630bb6928350c8f244` |
| FLA | `19329caf5c0f725589bd30667f513ea4b6b6bbd24854cebd0ad36aa8b3e7b34d` | `e4aa3188e112b3b5265c83721ac364e40fe8510d119fd4ae64e3be7f298c0dc2` | `b014b3809ed26bb572cc481ee4d6bfa55e7fb67cf0f7d447a30f59637e8964f8` |
| CAL | `13e4c7b0c625fedb110c1af4c392122cda95aac29ec67f6f61fb6b24ed5fc52b` | `d49667277a22bbfab99e67b6c6d02dfecf6771997697688b3c02d0225668fa44` | `0317943644704eabff42bbe8f5887d341199565c5892bfcf1ad7693cd283d44a` |

The complete machine records also hash every manifest-listed graph payload, query file, configuration, runner, aggregator, fixture, and the JAR. Local input paths are recorded as absolute workspace paths, so a different machine must supply and verify the same bytes before using this identity.

## Tractable reference identity

Machine record: [`experiments/manifests/icde2027_v1/freezes/reference_m5r2.json`](../experiments/manifests/icde2027_v1/freezes/reference_m5r2.json). The earlier `reference.json` remains preserved as the pre-repair identity.

| Identity | Value |
|---|---|
| Run series | `icde2027-v1-reference-identity-only-20261016-m5r2` |
| Configuration | `experiments/configs/icde2027_v1/reference.yaml` |
| Effective config hash | `3292e0f3fbf919e679868e35710f93d8ba2ff88de6b33e4ebe2fa52013889763` |
| Dataset | NY-EXACT, seed 42 |
| Methods | PACE-X, PACE-B, iSCOPE, SICSP-Pref |
| Caps | PACE-X 30 s, PACE-B 10 s, iSCOPE 5 s, SICSP-Pref 10 s |
| Threads | 1 per query |
| Graph checksum | `6f878449ad1aeb924c2ba7388698ac1f1a407b99bc362dff7b819eb9bfceb683` |
| Dataset manifest SHA-256 | `39ddb9f6e0045f25fb975ea1e5f20a2678320dbcee6c5333c0019b6d490cf9e1` |
| Query manifest SHA-256 | `fb7e0ffaa5a3378af3d516a8c45d1af9c39485de8af72a47ebf4323be7a25314` |
| Execution approval | **false** |

PACE-X output is a certified reference only when the actual result carries `GLOBAL_CERTIFIED`. SICSP-Pref can report `RETAINED_FRONTIER` only over its exact, nonempty generated retained registry; it does not claim global completeness. The current freeze does not approve execution.

## Separate objective and missing freeze inputs

`experiments/configs/icde2027_v1/fastest.yaml` specifies THop-Partitioned separately with its original fastest-arrival profile objective and a 10-second query cap. Its configured NY/FLA/CAL index directories and partition manifests under `data/indices/icde2027_v1/` are absent. The freeze writer correctly refuses to create a THop freeze without real partition metadata and built, graph-bound v2 index files. No placeholder index was created, and no network index build or query was run. Native SICSP has its separate deterministic Figure 1 CLI fixture with per-arc native weights and `Delta=80`; it is not treated as a preference method.

## Gate behavior and historical isolation

The freeze writer defaults to `readiness.approved=false` and does not dispatch Java. The new runner checks approval and source, JAR, config, data, query, and resource identities before creating a new run namespace. New configs disable historical-row reuse and use the `icde2027_v1` results root. Existing raw experiment directories were not modified. The original NY/FLA missing completion markers were not fabricated.

The six historical manuscript scalability values are validated as archival aggregates but remain tied to unrecovered dirty source trees. They are excluded from these prospective identities. The manuscript's scalability table and figure are marked for replacement; the other historical NY exactness, stage, sensitivity, and parallel artifacts require their own source-specific validation or replacement.

## Readiness

These records bind the post-repair source/JAR and input identities for the preference track and tractable reference on this workspace. They do not authorize execution. Cross-algorithm preference quality comparisons remain blocked until PACE-X, PACE-B and iSCOPE search-time pruning and final profiles use a compatible numerical contract or comparisons are explicitly qualified. The THop fastest-profile freeze is incomplete until real partition/index artifacts exist. Historical result reproducibility remains unresolved independently of future-run reproducibility.
