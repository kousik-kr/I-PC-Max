# ICDE 2027 compact query manifest audit

**Family:** `icde2027_compact_v1`
**Status:** frozen query identities and sidecars prepared locally. No jobs were dispatched and no freeze was approved.

## Selection rule and identity preservation

The source is each dataset's existing `paper-q1-v1` historical query manifest. Selection first filters the 200 evaluation rows with graph seed 42, no graph variant, 120-minute windows, and `rho=0.30`. It then takes five existing original OD pairs in each of five distance bands and preserves both original centers (510 and 1110). The script `experiments/scripts/prepare_icde2027_compact_manifests.py` copies each selected JSONL line unchanged. It does not regenerate pairs, departures, or budgets.

Every compact dataset therefore has 25 original OD pairs, 50 logical query IDs, and both source departure-center records. Absolute budget, source and destination, interval endpoints, distance band, dataset identity, and graph checksum are unchanged. The sidecar manifest records the 200-row source-slice hash, full source manifest hash, selected original pair IDs, graph checksum, and new subset hash.

| Dataset | Source manifest SHA-256 | Source default 200 rows SHA-256 | Compact 50 rows SHA-256 | Graph checksum |
| --- | --- | --- | --- | --- |
| NY | `d825450cbc9f7a0b244ce3e35c5c61a82813c0862eeb5b630bb6928350c8f244` | `390ae65944d0e710e5f3bff37af1cdc18649c94dc585f129c16562b78206cad5` | `0094e026e67dd6f8e6cc9bb16d6302588fa02b6491431713113c256cfe2e0609` | `97999bd124ed9639234bc1a9334b0426911a234b1ed80b5093ec1b30a8ade675` |
| FLA | `b014b3809ed26bb572cc481ee4d6bfa55e7fb67cf0f7d447a30f59637e8964f8` | `a7d1eb90302abe06103ccfb50a9f573bce59721d28f4ed56a7649a9bef426a75` | `e417c799d094c4e8dfeff1a421621f317a6a2d4a938ec974fba5360573e6c09e` | `19329caf5c0f725589bd30667f513ea4b6b6bbd24854cebd0ad36aa8b3e7b34d` |
| CAL | `0317943644704eabff42bbe8f5887d341199565c5892bfcf1ad7693cd283d44a` | `185ee4e0b0ff97f6bfd53b86c9e6ce11093b1c530364370c23a8793e71af1f3a` | `46c50a51c0b453bba0ac776bacef226fcc18eb17d51b606a99c9496773e9dab4` | `13e4c7b0c625fedb110c1af4c392122cda95aac29ec67f6f61fb6b24ed5fc52a` |

The exact selected query IDs and original pair IDs are stored in `experiments/manifests/icde2027_compact_v1/queries/{NY,FLA,CAL}/manifest.json`. Config loading rechecks each compact JSONL hash, row count, default window/budget/split, and both centers per pair. Matrix expansion binds every job identity to the exact manifest file hash and query ID.

## Controlled reference workload

No distinct verified `NY-CTRL` graph exists. The controlled workload therefore identifies the dataset by its actual name, `NY-EXACT`, and retains graph checksum `6f878449ad1aeb924c2ba7388698ac1f1a407b99bc362dff7b819eb9bfceb683` from its dataset manifest. Twenty original query records are selected (two original pairs per band, both centers), one measured trial each. The controlled subset hash is `8a0d553ed7d7b0682c80c7706ae3566a4c157b8809dc2764d272d487dbe4ad6d`; its selected pair IDs and source hashes are in `queries/NY-EXACT/controlled_manifest.json`.

PACE-X, PACE-B, iSCOPE, and SICSP-Pref share these same 20 records. PACE-X is a global exact reference only if it returns `GLOBAL_CERTIFIED` and the rational whole-profile oracle validates it. The current PACE-X adapter is `NOT_CERTIFIED`, so this controlled configuration does not promise a usable global reference. No dataset is silently renamed.

## Planned trials and exclusions

The three historical methods PACE-B, iSCOPE, and THop use the identical 50 IDs per dataset with two measured trials: 100 planned jobs per method per dataset. THop uses the query interval but explicitly ignores preference scores and travel-time budgets because it measures the fastest profile. SICSP-Pref is included only in the 20-query controlled reference study; large-network participation remains pilot-dependent.

Historical runtime and score rows are excluded: the family has a new result namespace, new matrix identities, and `historical_result_reuse: false`. The source and compact JSONL files and sidecars remain in the repository's version-controlled manifest tree; generated result data remains outside this family commit.
