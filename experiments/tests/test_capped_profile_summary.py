from fractions import Fraction

from experiments.scripts.common.exact_preference import decode_exact_preference_profile
from experiments.scripts.summarize_results import _capped_preference_profile_metrics


def _capped_record(status="TIME_CAPPED_NOT_CERTIFIED"):
    profile = {
        "schema": "exact-preference-envelope-v1",
        "domain": [{
            "start": "0", "end": "10",
            "start_inclusive": True, "end_inclusive": True,
        }],
        "cells": [{
            "interval": {
                "start": "0", "end": "10",
                "start_inclusive": True, "end_inclusive": True,
            },
            "status": "SELECTED", "budget_feasible": True,
            "score": "7", "path": [4],
            "arrival": {"slope": "1", "intercept": "3"},
            "travel_time": {"slope": "0", "intercept": "3"},
        }],
    }
    checksum = decode_exact_preference_profile(profile).checksum
    return {
        "completion_status": status,
        "java_record": {
            "status": {"completed": False, "exactness_scope": "NOT_CERTIFIED"},
            "timing_ns": {"query_total": 5_000_000_000},
            "output": {
                "profile_checksum": checksum,
                "path_switch_count": 0,
                "objective_result": {
                    "profile": profile,
                    "exact_profile_checksum": checksum,
                },
            },
        },
    }


def test_capped_profile_quality_is_reported_without_marking_completion():
    metrics = _capped_preference_profile_metrics(
        _capped_record(), "iscope-optimized"
    )
    assert metrics is not None
    assert metrics["wall_time_ns"] == 5_000_000_000
    assert metrics["profile_cells"] == 1
    assert metrics["feasible_coverage"] == Fraction(1)
    assert metrics["integrated_preference"] == Fraction(70)
    assert metrics["average_selected_preference"] == Fraction(7)
    assert metrics["path_switches"] == 0


def test_uncapped_or_nonpreference_rows_are_not_capped_profile_rows():
    assert _capped_preference_profile_metrics(
        _capped_record(status="SUCCESS"), "iscope-optimized"
    ) is None
    assert _capped_preference_profile_metrics(
        _capped_record(), "thop-partitioned"
    ) is None
