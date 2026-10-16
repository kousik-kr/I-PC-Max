"""The prospective design preserves objectives and explicit resource policies."""
from pathlib import Path
import copy
import pytest

from experiments.scripts.common.config import load_design, validate_icde2027
from experiments.scripts.build_matrices import expand_study


ROOT = Path("experiments/configs/icde2027_v1")


def test_prospective_preference_matrix_matches_workloads_and_explicit_caps():
    design = load_design(ROOT / "preference.yaml")
    assert design["resources"]["algorithm_timeout_seconds"] == {"pace-b": 10, "iscope": 5, "sicsp-pref": 10}
    for study in design["study_definitions"]:
        jobs = expand_study(study, design)
        keys = {}
        for method in ["pace-b", "iscope", "sicsp-pref"]:
            keys[method] = {(j["dataset_id"], j["query_id"], j["trial_id"])
                            for j in jobs if j["algorithm_id"] == method}
        assert keys["pace-b"] == keys["iscope"] == keys["sicsp-pref"]
        assert not ({"thop-partitioned", "sicsp-native", "pace-x"} & {j["algorithm_id"] for j in jobs})


def test_objective_specific_tracks_and_tiny_reference():
    fastest = load_design(ROOT / "fastest.yaml")
    reference = load_design(ROOT / "reference.yaml")
    assert {a["id"] for s in fastest["study_definitions"] for a in s["algorithms"]} == {"thop-partitioned"}
    assert set(reference["datasets"]) == {"NY-EXACT"}
    assert reference["exact_algorithm_guard"]["datasets"] == ["NY-EXACT"]
    assert {a["id"] for s in reference["study_definitions"] for a in s["algorithms"]} == {"pace-x", "pace-b", "iscope", "sicsp-pref"}


def test_reject_preference_objective_leakage_and_inherited_caps():
    design = load_design(ROOT / "preference.yaml")
    bad = copy.deepcopy(design)
    bad["study_definitions"][0]["algorithms"].append({"id": "thop-partitioned"})
    with pytest.raises(ValueError, match="objective"):
        validate_icde2027(bad, bad["study_definitions"])
    bad = copy.deepcopy(design)
    del bad["resources"]["algorithm_timeout_seconds"]["iscope"]
    with pytest.raises(ValueError, match="explicit"):
        validate_icde2027(bad, bad["study_definitions"])
