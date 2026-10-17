from pathlib import Path

from experiments.scripts.common.config import load_design
from experiments.scripts.preflight import (
    _primary_asset_design,
    _primary_compact_query_report,
)


def test_compact_primary_preflight_reports_only_declared_pilot_manifests():
    design = load_design(
        Path("experiments/configs/icde2027_compact_v1/primary_pilot.yaml")
    )
    report = _primary_compact_query_report(design)
    assert report["passed"] is True
    assert {
        row["dataset_id"]: (row["rows"], row["unique_query_ids"])
        for row in report["datasets"]
    } == {"NY": (5, 5), "FLA": (5, 5), "CAL": (5, 5)}


def test_primary_deep_preflight_excludes_unused_graph_variants():
    design = load_design(
        Path("experiments/configs/icde2027_compact_v1/primary_preference.yaml")
    )
    restricted = _primary_asset_design(design)
    assert restricted is not design
    for dataset in design["datasets"]:
        assert restricted["dataset_definitions"][dataset][
            "required_score_density_percent"
        ] == []
        assert restricted["dataset_definitions"][dataset][
            "required_graph_seeds"
        ] == []
    assert design["dataset_definitions"]["NY"][
        "required_score_density_percent"
    ]
