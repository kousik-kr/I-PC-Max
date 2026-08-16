# NYC feasibility invariant

The current official result bundle and query manifest were treated as immutable inputs. Every stored fastest path was replayed with `ExactPathValidator`, and an independent `PointForwardLabeling` run was performed for every integer departure.

| Check | Result |
| --- | --- |
| Queries | 100 |
| Departure samples | 12100 |
| Stored fastest witnesses valid | 12100 |
| Fresh fastest witnesses valid | 12100 |
| Fresh/stored structural agreement | 12100 |
| Fastest budget violations | 0 |
| Fastest query-region omissions | 0 |
| Traced checksum agreement | 38/38 |

## Current bounded-empty outcomes

| Query | Feasible replay | Feasible path exists | Correct classification | Earliest omission |
| --- | --- | --- | --- | --- |
| NYC-Q008-MORNING-R20 | 121/121 | yes | UNRESOLVED/NO_GENERATED_CANDIDATE | PIVOT_DEPTH: 121 |
| NYC-Q016-EVENING-R20 | 121/121 | yes | UNRESOLVED/NO_GENERATED_CANDIDATE | PIVOT_DEPTH: 121 |
| NYC-Q016-EVENING-R50 | 121/121 | yes | UNRESOLVED/NO_GENERATED_CANDIDATE | PIVOT_DEPTH: 121 |

All three official `NO_FEASIBLE_PATH` labels are non-certified bounded outcomes. They must be interpreted as unresolved empty generated frontiers, not graph infeasibility. The safe query region contains every one of the 12,100 feasible fastest witnesses; no query-region correctness bug was found.
