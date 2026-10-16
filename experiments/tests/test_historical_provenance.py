"""Adversarial fixtures for non-destructive historical-result accounting."""
import json

from experiments.scripts.audit_historical_results import audit_dataset, summarize_rows


def test_terminal_timeout_and_anytime_rows_are_never_completed():
    rows = [
        {"query_id": "q", "status": "SUCCESS", "completed": True, "runtime_s": 2, "score": 10},
        {"query_id": "q", "status": "TIMEOUT", "completed": False, "runtime_s": 999, "score": 999},
        {"query_id": "r", "status": "TIME_CAPPED_NOT_CERTIFIED", "completed": False, "runtime_s": 5, "score": 4},
    ]
    got = summarize_rows(rows)
    assert got["terminal_rows"] == 3
    assert got["completed_rows"] == 1
    assert got["completed_only_runtime_s"] == {"count": 1, "mean": 2, "median": 2}
    assert got["completed_only_score"]["mean"] == 10
    assert got["time_capped_rows"] == 1
    assert got["timeout_rows"] == 1
    assert got["completion_percentage"] == 100 / 3


def test_ledger_identity_detects_duplicates_missing_and_extra(tmp_path):
    (tmp_path / "plan/matrices").mkdir(parents=True)
    (tmp_path / "raw/T03").mkdir(parents=True)
    ledger = [{"job_id": f"j{i}", "query_id": f"q{i}", "trial_id": 0,
               "dataset_id": "NY", "algorithm_id": "pace-b"} for i in range(2)]
    (tmp_path / "plan/matrices/canonical_job_ledger.jsonl").write_text(
        "".join(json.dumps(r) + "\n" for r in ledger))
    rows = [{"job_id": j, "completion_status": "TIMEOUT", "java_record": None} for j in ["j0", "j0", "unknown"]]
    raw = tmp_path / "raw/T03/a.jsonl"
    raw.write_text("".join(json.dumps(r) + "\n" for r in rows))
    before = raw.read_bytes()
    got = audit_dataset(tmp_path, "NY")
    assert got["identity"]["duplicate_job_ids"] == {"j0": 2}
    assert got["identity"]["unknown_job_ids"] == ["unknown"]
    assert got["methods"]["pace-b"]["missing_job_ids"] == ["j1"]
    assert got["original_main_marker_present"] is False
    assert raw.read_bytes() == before
    assert not (tmp_path / "markers/main.complete.json").exists()


def test_completed_flag_and_status_must_both_agree():
    rows = [{"query_id": "q", "status": status, "completed": completed,
             "runtime_s": 1, "score": 1}
            for status, completed in [("SUCCESS", False), ("TIMEOUT", True),
                                      ("TIME_CAPPED_NOT_CERTIFIED", True)]]
    assert summarize_rows(rows)["completed_rows"] == 0
