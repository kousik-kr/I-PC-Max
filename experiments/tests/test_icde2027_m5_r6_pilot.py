import json
from pathlib import Path

from experiments.scripts.build_matrices import expand_study
from experiments.scripts.common.config import load_design
from experiments.scripts.validate_results import validate_planned_cells


ROOT = Path(__file__).resolve().parents[2]


def test_m5_r6_iscope_pilot_freezes_one_historical_query_at_ten_seconds():
    design = load_design(ROOT / "experiments/configs/icde2027_m5_r6/iscope10.yaml")
    assert design["datasets"] == ["NY"]
    assert design["resources"]["algorithm_timeout_seconds"] == {
        "iscope-optimized": 10
    }
    assert design["resources"]["max_concurrent"] == 1
    study = design["study_definitions"][0]
    assert validate_planned_cells(expand_study(study, design), design)["passed"]
    assert study["trials"] == 1
    assert study["algorithms"][0]["parameters"]["threads"] == 1
    row = json.loads(
        (ROOT / study["query_manifest_pattern"]).read_text(encoding="utf-8")
    )
    assert row["query_id"] == "NY-EVAL-P003-C510-W120-RHO030"


def test_m5_r6_sicsp_pilot_has_watchdog_without_candidate_cutoff():
    design = load_design(ROOT / "experiments/configs/icde2027_m5_r6/sicsp_pref.yaml")
    assert design["resources"]["algorithm_timeout_seconds"] == {"sicsp-pref": 600}
    algorithm = design["study_definitions"][0]["algorithms"][0]
    assert validate_planned_cells(
        expand_study(design["study_definitions"][0], design), design
    )["passed"]
    assert algorithm["id"] == "sicsp-pref"
    assert algorithm["parameters"] == {"threads": 1}


def test_m5_r6_sicsp_fixture_uses_one_unchanged_tractable_query():
    design = load_design(
        ROOT / "experiments/configs/icde2027_m5_r6/sicsp_fixture.yaml"
    )
    study = design["study_definitions"][0]
    jobs = expand_study(study, design)
    assert len(jobs) == 1
    assert validate_planned_cells(jobs, design)["passed"]
    assert jobs[0]["query_id"] == "tiny-1-4-wide"
    assert jobs[0]["pair_index"] == 1
    assert jobs[0]["time_center"] == 425

