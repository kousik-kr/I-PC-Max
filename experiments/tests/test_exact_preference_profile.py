import copy
import hashlib
import json
import sys
from fractions import Fraction
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from experiments.scripts.common.exact_preference import decode_exact_preference_profile


def interval(start, end, left, right):
    return {"start": start, "end": end,
            "start_inclusive": left, "end_inclusive": right}


def selected(start, end, left, right, score, path=(0,)):
    return {
        "interval": interval(start, end, left, right),
        "status": "SELECTED", "budget_feasible": True,
        "score": str(score), "path": list(path),
        "arrival": {"slope": "1", "intercept": "1"},
        "travel_time": {"slope": "0", "intercept": "1"},
    }


def no_path(start, end, left, right):
    return {"interval": interval(start, end, left, right),
            "status": "NO_PATH", "budget_feasible": False}


def fixture():
    return {
        "schema": "exact-preference-envelope-v1",
        "domain": [interval("0", "2", True, True)],
        "cells": [
            selected("0", "0", True, True, 99),
            selected("0", "1", False, False, 5),
            selected("1", "1", True, True, 7),
            no_path("1", "2", False, False),
            no_path("2", "2", True, True),
        ],
    }


def checksum(value):
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n"
    return hashlib.sha256(encoded.encode()).hexdigest()


def test_typed_round_trip_keeps_no_path_out_of_integrated_preference():
    value = fixture()
    profile = decode_exact_preference_profile(value, checksum(value))
    assert profile.metrics.total_measure == Fraction(2)
    assert profile.metrics.feasible_measure == Fraction(1)
    assert profile.metrics.coverage == Fraction(1, 2)
    assert profile.metrics.integrated_preference == Fraction(5)
    assert profile.metrics.average_selected_preference == Fraction(5)
    assert len(profile.cells) == 5


def test_decoder_rejects_corrupt_checksum_and_affine_travel_mismatch():
    value = fixture()
    with pytest.raises(ValueError, match="checksum mismatch"):
        decode_exact_preference_profile(value, "0" * 64)
    corrupt = copy.deepcopy(value)
    corrupt["cells"][1]["travel_time"]["intercept"] = "2"
    with pytest.raises(ValueError, match="inconsistent travel-time relation"):
        decode_exact_preference_profile(corrupt)


def test_decoder_rejects_a_missing_owned_singleton_or_domain_gap():
    value = fixture()
    del value["cells"][2]
    with pytest.raises(ValueError, match="do not partition"):
        decode_exact_preference_profile(value)


def test_zero_measure_identity_domain_preserves_point_feasibility():
    value = {
        "schema": "exact-preference-envelope-v1",
        "domain": [interval("3/2", "3/2", True, True)],
        "cells": [selected("3/2", "3/2", True, True, 10, ())],
    }
    profile = decode_exact_preference_profile(value)
    assert profile.metrics.coverage == 1
    assert profile.metrics.feasible_measure == 0
    assert profile.metrics.integrated_preference == 0
    assert profile.metrics.average_selected_preference is None
