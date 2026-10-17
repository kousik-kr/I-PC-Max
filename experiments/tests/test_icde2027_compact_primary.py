from pathlib import Path
import json
from unittest.mock import patch

from experiments.scripts.build_matrices import build_all, expand_study
from experiments.scripts.common.config import load_design
from experiments.scripts.execute_matrix import _command


ROOT = Path(__file__).resolve().parents[2]


def test_predeclared_pilot_is_five_original_queries_per_dataset(tmp_path):
    design = load_design(ROOT / "experiments/configs/icde2027_compact_v1/primary_pilot.yaml")
    report = build_all(design, tmp_path / "pilot-plan")
    assert report["study_counts"] == {"P01": 30}
    assert report["total_jobs"] == 30
    for dataset in ("NY", "FLA", "CAL"):
        pilot = ROOT / "experiments/manifests/icde2027_compact_v1/queries" / dataset / "pilot_5.jsonl"
        compact = ROOT / "experiments/manifests/icde2027_compact_v1/queries" / dataset / "compact_50.jsonl"
        assert len(pilot.read_text(encoding="utf-8").splitlines()) == 5
        compact_by_id = {
            json.loads(line)["query_id"]: line
            for line in compact.read_text(encoding="utf-8").splitlines()
        }
        for line in pilot.read_text(encoding="utf-8").splitlines():
            row = json.loads(line)
            assert line == compact_by_id[row["query_id"]]
        assert {
            json.loads(line)["distance_bin"]
            for line in pilot.read_text(encoding="utf-8").splitlines()
        } == {1, 2, 3, 4, 5}


def test_primary_main_matrix_is_600_predeclared_preference_jobs(tmp_path):
    design = load_design(ROOT / "experiments/configs/icde2027_compact_v1/primary_preference.yaml")
    report = build_all(design, tmp_path / "main-plan")
    assert report["study_counts"] == {"M01": 600}
    assert report["total_jobs"] == 600
    assert report["pace_b_jobs"] == 300


def test_primary_commands_keep_unlimited_pace_b_and_five_second_iscope(tmp_path):
    design = load_design(ROOT / "experiments/configs/icde2027_compact_v1/primary_pilot.yaml")
    jobs = expand_study(design["study_definitions"][0], design)
    with patch(
        "experiments.scripts.common.benchmark_freeze.capture_run_freeze",
        return_value={},
    ):
        commands = {
            algorithm: _command(
                next(job for job in jobs if job["algorithm_id"] == algorithm),
                design,
                tmp_path / "run",
                tmp_path / f"{algorithm}.queries.jsonl",
                tmp_path / f"{algorithm}.results.jsonl",
            )
            for algorithm in ("pace-b", "iscope-optimized")
        }
    for algorithm, timeout in (("pace-b", "unlimited"), ("iscope-optimized", "5")):
        command = commands[algorithm]
        assert command[command.index("--timeout-seconds") + 1] == timeout
        assert command[command.index("--threads") + 1] == "1"
        assert command[command.index("--memory-limit-mb") + 1] == "256000"
    pace_b = commands["pace-b"]
    assert pace_b[pace_b.index("--connector-expansion-cap-mc") + 1] == "250000"
    assert pace_b[pace_b.index("--breakpoint-cap-mb") + 1] == "100000"
    assert pace_b[pace_b.index("--query-work-cap-mq") + 1] == "5000000"
