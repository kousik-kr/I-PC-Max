# PACE-X completion invariant repair (R1)

Source baseline: 0d0fa20c8b979b1db9916d5489649d73e8cc4993. Production PACE code matches the M5 audited revision 508a1d8. This repair changes reporting only.

## Saved failure and root cause

PaceCompletionInvariantTest.feasibleChainWithInsufficientDepthIsUnresolvedInsteadOfThrowing was added before the repair. The graph is 1→2→3, travel 1 and score 7 on each arc; departure [0,5], budget 5, theta 0. The original test failed with IllegalArgumentException: NO_FEASIBLE_PATH requires independent global certification.

ForwardLayeredFrontierGenerator derived completion from emptiness/policy and derived exactness from completion/pivot coverage. An incomplete empty PACE-X frontier therefore combined NO_FEASIBLE_PATH with RETAINED_FRONTIER, which the public result correctly rejected. Completion was being used as an input to the certificate while being assigned without knowing that certificate.

## Repair

PaceGenerationOutcome.resolve now derives the completion/scope pair once from raw generation facts: policy, frontier emptiness, cap state, coverage of all score pivots/depth, and retained-frontier policy. No completion-to-exactness circular dependency remains. The generator computes the existing sufficient pivot/depth condition and passes it to the classifier.

An uncapped incomplete empty PACE-X frontier now returns UNRESOLVED_NO_GENERATED_CANDIDATE / NOT_CERTIFIED. A globally exhaustive empty frontier still returns NO_FEASIBLE_PATH / GLOBAL_CERTIFIED. Caps take precedence. PaceGenerationResult also rejects an empty-frontier status attached to candidates or COMPLETE attached to an empty frontier.

Candidate generation, connector order, path replay, pruning, budgets and score envelopes are unchanged.

## Before/after state table

| State | Before | After |
|---|---|---|
| A: X complete coverage, feasible frontier | COMPLETE / GLOBAL_CERTIFIED | Preserved |
| B: X complete coverage, empty frontier | NO_FEASIBLE_PATH / GLOBAL_CERTIFIED | Preserved |
| C: X incomplete coverage, feasible frontier | COMPLETE / RETAINED_FRONTIER | Preserved |
| D: X incomplete coverage, empty frontier | Contradictory NO_FEASIBLE_PATH / RETAINED_FRONTIER; exception | UNRESOLVED_NO_GENERATED_CANDIDATE / NOT_CERTIFIED |
| E/F: B cap with feasible/empty frontier | RESOURCE_TRUNCATED / retained policy scope | Preserved; no global certificate |
| E/F/G: X cap/abort with feasible/empty partial frontier | ABORTED / NOT_CERTIFIED | Preserved; PACE.run raises typed LIMIT_EXCEEDED and runner records it |
| H: invalid query/unsupported horizon | Validated exception, no answer | Preserved; no infeasibility certificate |

The bounded empty-frontier retained scope is preserved for compatibility; it is not a global infeasibility claim. Empty envelope cells represent no generated candidate unless a global certificate accompanies the result.

## Independent and integration evidence

The depth-0 chain now returns an unresolved envelope covering [0,5] with bottom cells and experiment status UNRESOLVED_NO_GENERATED_CANDIDATE. Depth 2 returns route [0,1], score 14, travel time 2 and GLOBAL_CERTIFIED; it matches the independent exhaustive tiny continuous oracle.

The state-table regression covers every empty/nonempty, exhaustive/incomplete and capped combination. Other regressions cover disconnected globally empty output, feasible but incomplete coverage, invalid query and unsupported horizon. Existing emergency-frontier guard, PACE-B cap and public API tests remain intact.

Saved red command (also included the two missing-adapter regressions):

    mvn -B -ntp -Dtest=PaceCompletionInvariantTest,ObjectiveAdapterRegistrationTest test
    Tests run: 3, Failures: 1, Errors: 2, Skipped: 0
    The R1 failure was the expected completion invariant exception.
    The two R4 errors were the expected missing registry IDs.

R1 was validated in /tmp/m5r-r1-checkout, a clean clone of the baseline plus only the four R1 Java files, to exclude concurrent phase changes:

    mvn -B -ntp -Dtest=PaceCompletionInvariantTest,ScalablePaceCandidateEngineTest,PacePublicApiOracleIntegrationTest,PaceExactOracleDifferentialTest,ExperimentAlgorithmsTest,PaceBenchFrameworkTest test
    Tests run: 60, Failures: 0, Errors: 0, Skipped: 0
    BUILD SUCCESS; 14.952 s; 2026-10-16T01:54:30Z

The PACE-X 1,000 fixed-seed differential corpus and PACE-B/public runner regressions passed. This fixes P1-A. It does not resolve the separate shared temporal-model limitation recorded under R2.
