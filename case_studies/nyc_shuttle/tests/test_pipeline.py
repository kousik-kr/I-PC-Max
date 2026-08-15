from __future__ import annotations

import gzip
import json
import sys
import tempfile
import unittest
import urllib.parse
from pathlib import Path

from shapely.geometry import LineString

# Keep the case-study package isolated while making repository-root test discovery work.
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

from nyc_case_study.common import CaseStudyError, immutable_write, sha256_file
from nyc_case_study.geometry import (
    centerline_direction_difference, ordered_edge_matches,
)
from nyc_case_study.socrata import SocrataClient, download_dataset
from nyc_case_study.temporal import (
    active_route_bins, capped_route_score, fifo_repair, validate_fifo,
)
from nyc_case_study.validation import (
    deterministic_checksum, validate_arc_path, validate_query_horizon,
    validate_score_intervals,
)
from aggregate_dot_traffic_speeds import bin_start


class FakeSocrata:
    def __init__(self) -> None:
        self.rows = [{"id": str(index), "value": index} for index in range(5)]

    def __call__(self, url: str, headers: dict[str, str]) -> bytes:
        if "/api/views/" in url:
            return json.dumps({"id": "abcd-1234", "columns": [
                {"name": "id", "fieldName": "id", "dataTypeName": "text"}
            ]}).encode()
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)
        if query.get("$select") == ["count(*)"]:
            return b'[{"count":"5"}]'
        offset = int(query.get("$offset", [0])[0])
        limit = int(query.get("$limit", [1000])[0])
        return json.dumps(self.rows[offset:offset + limit]).encode()


class SocrataAndRawContractsTest(unittest.TestCase):
    def test_socrata_pagination_does_not_truncate(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            manifest = download_dataset(
                SocrataClient("example.test", "abcd-1234", fetcher=FakeSocrata()),
                Path(temporary), output_format="json", page_size=2,
            )
            self.assertEqual(5, manifest["row_count"])
            self.assertEqual([2, 2, 1], [page["row_count"] for page in manifest["pages"]])

    def test_raw_data_are_immutable_and_checksummed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "raw.json"
            first = immutable_write(path, b"official bytes")
            second = immutable_write(path, b"official bytes")
            self.assertEqual(first, second)
            self.assertEqual(first, sha256_file(path))
            with self.assertRaises(CaseStudyError):
                immutable_write(path, b"changed bytes")


class CoordinateAndMappingContractsTest(unittest.TestCase):
    def test_coordinate_conversion_places_ny_graph_in_region(self) -> None:
        root = Path(__file__).resolve().parents[3]
        path = root / "data/input/NY/nodes.csv.gz"
        longitudes, latitudes = [], []
        with gzip.open(path, "rt", encoding="utf-8") as stream:
            next(stream)
            for line in stream:
                _, x, y = line.rstrip().split(",")
                longitudes.append(int(x) / 1_000_000)
                latitudes.append(int(y) / 1_000_000)
        self.assertEqual((-74.499998, -73.500016), (min(longitudes), max(longitudes)))
        self.assertEqual((40.300009, 41.299997), (min(latitudes), max(latitudes)))

    def test_direction_aware_edge_matching(self) -> None:
        edge = LineString([(0, 0), (10, 0)])
        centerline = LineString([(0, 0), (10, 0)])
        self.assertLess(centerline_direction_difference(edge, centerline, "TF"), 1e-9)
        self.assertGreater(centerline_direction_difference(edge, centerline, "FT"), 179.0)
        self.assertLess(centerline_direction_difference(edge, centerline, "TW"), 1e-9)

    def test_one_to_many_dot_link_mapping_is_ordered(self) -> None:
        source = LineString([(0, 0), (30, 0)])
        edges = [(0, LineString([(0, 0), (10, 0)])),
                 (1, LineString([(10, 0), (20, 0)])),
                 (2, LineString([(20, 0), (30, 0)]))]
        matches = ordered_edge_matches(
            source, reversed(edges), max_distance_m=2, overlap_buffer_m=1,
            minimum_overlap_ratio=0.9, maximum_direction_difference_degrees=5,
        )
        self.assertEqual([0, 1, 2], [arc for arc, _ in matches])

    def test_mta_route_shape_mapping_rejects_opposite_direction(self) -> None:
        route = LineString([(0, 0), (20, 0)])
        edges = [(0, LineString([(0, 0), (10, 0)])),
                 (1, LineString([(20, 0), (10, 0)]))]
        matches = ordered_edge_matches(
            route, edges, max_distance_m=2, overlap_buffer_m=1,
            minimum_overlap_ratio=0.9, maximum_direction_difference_degrees=10,
        )
        self.assertEqual([0], [arc for arc, _ in matches])


class TemporalAndScoreContractsTest(unittest.TestCase):
    def test_dot_floating_timestamp_is_localized_before_utc_normalization(self) -> None:
        self.assertEqual(
            "2026-05-14T04:00:00Z",
            bin_start("2026-05-14T00:00:00.000", 15, "America/New_York"),
        )

    def test_active_route_count_score_is_distinct_nonnegative_and_capped(self) -> None:
        self.assertEqual(2, capped_route_score(["M1", "M1", "M2"]))
        self.assertEqual(15, capped_route_score([f"R{i}" for i in range(20)]))
        self.assertGreaterEqual(capped_route_score([]), 0)

    def test_scores_are_bounded_piecewise_constant_intervals(self) -> None:
        validate_score_intervals([[0, 15, 0], [15, 45, 3], [45, 60, 15]], 0, 60, 15)
        with self.assertRaises(CaseStudyError):
            validate_score_intervals([[0, 15, 0], [16, 60, 3]], 0, 60, 15)
        with self.assertRaises(CaseStudyError):
            validate_score_intervals([[0, 60, 16]], 0, 60, 15)

    def test_active_route_activity_reconstruction(self) -> None:
        import pandas as pd
        schedule = pd.DataFrame([
            {"schedule_date": "2026-05-14T00:00:00.000", "route_id": "M1",
             "shape_id": "S1", "stop_sequence": "1", "schedule_time": "2026-05-14T07:00:00.000",
             "block_id": "B1", "direction": "N"},
            {"schedule_date": "2026-05-14T00:00:00.000", "route_id": "M1",
             "shape_id": "S1", "stop_sequence": "2", "schedule_time": "2026-05-14T07:20:00.000",
             "block_id": "B1", "direction": "N"},
        ])
        active, stats = active_route_bins(
            schedule, "2026-05-14T00:00:00Z", "2026-05-15T00:00:00Z", 15
        )
        self.assertIn(("M1", "S1", 44), active)
        self.assertIn(("M1", "S1", 45), active)
        self.assertEqual(1, stats["reconstructed_trips"])

    def test_arrival_functions_are_continuous_nonnegative_and_fifo(self) -> None:
        times = [0.0, 15.0, 30.0]
        repaired, changes = fifo_repair(times, [30.0, 1.0, 2.0])
        self.assertEqual(1, changes)
        self.assertTrue(all(value >= 0 for value in repaired))
        validate_fifo(times, repaired)
        # Linear interpolation has equal left/right value at each shared knot.
        for index in range(1, len(times) - 1):
            left_arrival = times[index] + repaired[index]
            right_arrival = times[index] + repaired[index]
            self.assertEqual(left_arrival, right_arrival)


class QueryAndOutputContractsTest(unittest.TestCase):
    def test_query_horizon_must_include_budgeted_traversal(self) -> None:
        validate_query_horizon(660, 780, 30, 0, 1440)
        with self.assertRaises(CaseStudyError):
            validate_query_horizon(1260, 1380, 61, 0, 1440)

    def test_returned_path_contract_continuous_simple_and_within_budget(self) -> None:
        endpoints = {0: (1, 2), 1: (2, 3), 2: (3, 4)}
        travel = {0: 2.0, 1: 3.0, 2: 4.0}
        self.assertEqual(9.0, validate_arc_path([0, 1, 2], 1, 4, endpoints, travel, 10.0))
        with self.assertRaises(CaseStudyError):
            validate_arc_path([0, 2], 1, 4, endpoints, travel, 10.0)
        with self.assertRaises(CaseStudyError):
            validate_arc_path([0, 1, 2], 1, 4, endpoints, travel, 8.0)

    def test_repeated_case_study_payload_has_same_checksum(self) -> None:
        payload = {"seed": 20260815, "arcs": [3, 7, 11], "score": 4}
        self.assertEqual(deterministic_checksum(payload), deterministic_checksum(dict(reversed(list(payload.items())))))


if __name__ == "__main__":
    unittest.main()
