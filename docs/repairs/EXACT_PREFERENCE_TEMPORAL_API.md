    # Exact rational preference-profile API

    Status: implemented for opt-in route-registry evaluation; SICSP-Pref uses this API for its authoritative final profile. The API does not change the search completeness of an algorithm that supplies candidate routes.

    ## Numerical contract

    `EXACT_RATIONAL_PREFERENCE_PROFILE-v1` treats the already-canonicalized decimal knots and values stored on accepted graph edges as exact rationals. Public decimal departures, interval bounds and travel budgets are canonicalized once at the existing API boundary, then converted from their decimal spelling to reduced `ExactTime` values. Internal edge-entry times, affine compositions, score-event preimages, crossings and budget roots remain rational. In particular, `20/3` and the public decimal `6.666666666667` are different events.

    For an edge, the arrival map is `A_e(t) = t + travel_e(t)`. A route composes these maps without waiting and evaluates each edge score at its exact entry time. Score functions are right-continuous at shared knots: a step beginning at `x` owns `x`; the final edge support owns its final endpoint according to the input function's represented support. Support is intersected at every edge entry, including disconnected supports and singleton pieces. Unsupported entry times make that departure unavailable.

    Budget feasibility is the exact sublevel set `{t | A_P(t) - t <= B}`. Equality is included. Affine roots are solved in rational arithmetic and intersected with route support and the requested domain. No epsilon, rounded value, or breakpoint cap is used to decide feasibility.

    The envelope compares only budget-feasible profiles supplied in its route registry. At each departure, higher score wins, then lower exact travel time, fewer arcs, and lexicographically smaller arc-ID sequence. All score, support, budget and arrival-intersection events are retained as exact cuts. The returned cells are disjoint and cover the complete requested domain: open cells between event times plus owned singleton event cells. A `NO_PATH` cell means no supplied route is supported and budget-feasible there; it is not a global graph-unreachability claim.

    `ExactPreferenceReplay` validates directed continuity, source/destination agreement, vertex simplicity and edge support. The empty route is supported only for source equals destination. Scores accumulate in `BigInteger`.

    ## Components

    | Component | Responsibility |
    |---|---|
    | `ExactScoreProfile` | Exact score steps, endpoint ownership, pointwise `BigInteger` sum, and pullback through affine entry-time profiles. |
    | `ExactTravelBudgetDomain` | Exact piecewise-affine travel-time inequalities and equality-owned budget roots. |
    | `ExactPreferencePathProfile` | One route's exact arrival, score, support, feasible domain and event-operation diagnostics. |
    | `ExactPreferenceReplay` | Route validation, rational no-wait edge composition, exact score preimages and feasible-domain construction. |
    | `ExactPreferenceEnvelope` | Complete route selection over the supplied registry and exact temporal partition. |
    | `ExactPreferenceProfileCodec` | Deterministic schema `exact-preference-envelope-v1` JSON encoding and validated reload. |

    The implementation reuses the THop `ExactTime`, `ExactTemporalDomain` and `ExactArrivalProfile` primitives. It does not route authoritative roots through legacy `Domain`, `TimeProfile`, `EnvelopeProfile`, or `CandidateProfile`; conversion from a public query domain happens once at input. SICSP retains its old profile objects only as compatibility fields. Its experiment adapter returns `AlgorithmResult.profile = null` and places the exact JSON object under `objective_result.profile`, so the runner cannot silently evaluate a rounded projection.

    ## Serialization

    Each temporal endpoint, affine coefficient, score, and path arc ID is represented explicitly. Rational values are JSON strings: reduced integers use `"n"`; non-integral values use `"n/d"`. Every cell includes `start`, `end`, `start_inclusive`, and `end_inclusive`. A selected cell carries `status: SELECTED`, `budget_feasible: true`, an integer-string score, the arc-ID path, exact arrival slope/intercept, and exact travel-time slope/intercept. An uncovered cell carries `status: NO_PATH` and `budget_feasible: false`. The codec verifies the travel-time relation against arrival on reload and rejects overlapping or incomplete cells.

    The adapter separately emits the schema name, checksum, serialized byte count, profile-piece count, score-event preimages, budget-root operations, envelope comparisons and maximum rational component bit length. Display metrics may be rounded; they do not feed route choice or feasibility.

    ## Validation and scope

    `ExactPreferenceProfileTest` uses `TinyContinuousEnvelopeOracle`, which independently enumerates edge events, exact path replay and whole temporal cells without calling the production exact replay or envelope. SICSP's heuristic-miss fixture also compares exact envelopes for raw simple routes, retained feasible routes and all global simple routes against independent whole-cell oracle results. The retained registry matches its oracle; the known globally best path remains absent from the generator output.

    The API proves exact selection only over the supplied registry. It does not certify that a heuristic search generated every globally optimal route, and it does not establish a shared exact model for PACE-X, PACE-B or iSCOPE. See [PREFERENCE_ALGORITHM_NUMERICAL_MIGRATION_PLAN.md](PREFERENCE_ALGORITHM_NUMERICAL_MIGRATION_PLAN.md).
