"""THop output has a separate exact fastest-arrival aggregation path."""
from __future__ import annotations

import hashlib
import json

import pytest

from experiments.scripts.collect_results import flatten
from experiments.scripts.common.exact_fastest_profile import decode_fastest_profile


def _objective():
    return {
        "objective": "fastest_profile",
        "precision": "exact_rational",
        "feasible": True,
        "requested_departure_domain": [{
            "start": 0.0, "end": 10.0,
            "start_inclusive": True, "end_inclusive": True,
        }],
        "fastest_travel_time_profile": [
            {
                "start": "0", "end": "5",
                "start_inclusive": True, "end_inclusive": False,
                "slope": "0", "intercept": "10",
            },
            {
                "start": "5", "end": "10",
                "start_inclusive": True, "end_inclusive": True,
                "slope": "0", "intercept": "5",
            },
        ],
    }


def _checksum(value):
    encoded = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ) + "\n"
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def test_exact_fastest_profile_preserves_events_and_reports_travel_only_metrics():
    objective = _objective()
    profile = decode_fastest_profile(objective, _checksum(objective))
    assert len(profile.cells) == 2
    assert profile.coverage == 1
    assert profile.integrated_travel_time == 75
    assert profile.average_travel_time == pytest.approx(7.5)


def test_fastest_profile_rejects_double_owned_boundary_and_bad_checksum():
    objective = _objective()
    objective["fastest_travel_time_profile"][0]["end_inclusive"] = True
    with pytest.raises(ValueError, match="overlap"):
        decode_fastest_profile(objective)
    objective = _objective()
    with pytest.raises(ValueError, match="checksum"):
        decode_fastest_profile(objective, "0" * 64)


def test_fastest_profile_rejects_overlapping_requested_domain_intervals():
    objective = _objective()
    objective["requested_departure_domain"].append({
        "start": 9.0, "end": 12.0,
        "start_inclusive": True, "end_inclusive": True,
    })
    with pytest.raises(ValueError, match="requested departure intervals overlap"):
        decode_fastest_profile(objective)


def test_result_collection_reads_nested_thop_profile_without_legacy_profile():
    objective = _objective()
    checksum = _checksum(objective)
    record = {
        "run_id": "pilot", "study_id": "M01", "job_id": "thop-job",
        "trial_id": 0, "completion_status": "SUCCESS", "input_hash": "a" * 64,
        "java_record": {
            "configuration": {"algorithm": "thop-partitioned"},
            "status": {"status_code": "COMPLETED", "exactness_scope": "GLOBAL_CERTIFIED"},
            "dataset": {"dataset_id": "NY"},
            "query": {"query_id": "NY-EVAL-P001-C510"},
            "output": {
                "profile_checksum": checksum,
                "objective_result": objective,
            },
            "timing_ns": {"query_total": 1234},
            "memory_bytes": {}, "counters": {},
        },
    }
    normalized = flatten(record)
    assert normalized["reporting_track"] == "FASTEST_PROFILE_REFERENCE"
    assert normalized["fastest_profile_cells"] == 2
    assert normalized["fastest_profile_coverage_exact"] == "1"
    assert normalized["fastest_profile_integrated_travel_time"] == "75"
    assert normalized["fastest_profile_preference_scores_used"] is False
    assert normalized["fastest_profile_pace_budget_used"] is False
    assert "exact_profile_schema" not in normalized
