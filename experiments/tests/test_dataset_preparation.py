import gzip
from pathlib import Path
import tempfile
import unittest

from experiments.scripts.common.atomic_io import atomic_write_json
from experiments.scripts.common.config import load_document
from experiments.scripts.common.hashing import (
    dataset_checksum,
    temporal_attribute_checksum,
)
from experiments.scripts.generate_dataset_assets import (
    generate_score_for_edges,
    generate_travel_for_edges,
    iter_jsonl,
    validate_conversion_against_raw,
    validate_dataset_directory,
    validate_score_payload,
    validate_travel_payload,
)


class DatasetPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        config = load_document(
            Path("experiments/configs/dataset_generation.yaml")
        )
        self.settings = config["defaults"]
        self.edge_count = 20
        self.edges = self.root / "edges_static.csv.gz"
        self._write_gzip(
            self.edges,
            "arc_id,u,v,distance,base_travel_time\n"
            + "".join(
                f"{arc_id},1,2,{6000 + arc_id},"
                f"{1 + arc_id / 6000:.9f}\n"
                for arc_id in range(self.edge_count)
            ),
        )

    def tearDown(self):
        self.temporary.cleanup()

    def test_generation_is_byte_identical_fifo_and_horizon_safe(self):
        travel_one = self.root / "travel-one.jsonl.gz"
        travel_two = self.root / "travel-two.jsonl.gz"

        generate_travel_for_edges(
            self.edges, travel_one, self.settings, 42
        )
        generate_travel_for_edges(
            self.edges, travel_two, self.settings, 42
        )

        self.assertEqual(travel_one.read_bytes(), travel_two.read_bytes())
        self.assertEqual(
            (self.edge_count, self.edge_count),
            validate_travel_payload(self.edges, travel_one, 10080),
        )

    def test_score_density_variants_are_exact_stable_and_nested(self):
        selected_sets = []
        for percent in (5, 10, 20, 40):
            first = self.root / f"score-{percent}-first.jsonl.gz"
            second = self.root / f"score-{percent}-second.jsonl.gz"
            expected = self.edge_count * percent // 100
            self.assertEqual(
                expected,
                generate_score_for_edges(
                    self.edges,
                    first,
                    self.settings,
                    42,
                    percent / 100,
                    self.edge_count,
                ),
            )
            generate_score_for_edges(
                self.edges,
                second,
                self.settings,
                42,
                percent / 100,
                self.edge_count,
            )
            self.assertEqual(first.read_bytes(), second.read_bytes())
            selected = {
                int(record["arc_id"]) for record in iter_jsonl(first)
            }
            self.assertEqual(expected, len(selected))
            selected_sets.append(selected)
        for lower, higher in zip(selected_sets, selected_sets[1:]):
            self.assertTrue(lower.issubset(higher))

    def test_duplicate_and_malformed_score_payloads_are_rejected(self):
        duplicate = self.root / "duplicate.jsonl.gz"
        record = (
            '{"arc_id":0,"u":1,"v":2,"selected_for_score":true,'
            '"score_intervals":[[0,10080,1]]}\n'
        )
        self._write_gzip(duplicate, record + record)
        with self.assertRaisesRegex(ValueError, "unique and increasing"):
            validate_score_payload(
                self.edges,
                duplicate,
                self.edge_count,
                10080,
                False,
            )

        malformed = self.root / "malformed.jsonl.gz"
        self._write_gzip(malformed, "{not-json}\n")
        with self.assertRaises(ValueError):
            list(iter_jsonl(malformed))

    def test_conversion_and_manifest_checksum_mismatches_are_detected(self):
        dataset = self.root / "dataset"
        dataset.mkdir()
        (dataset / "edges_static.csv.gz").write_bytes(
            self.edges.read_bytes()
        )
        self._write_gzip(
            dataset / "nodes.csv.gz",
            "node_id,x,y\n1,0,0\n2,1,1\n",
        )
        generate_travel_for_edges(
            dataset / "edges_static.csv.gz",
            dataset / "travel_time_functions.jsonl.gz",
            self.settings,
            42,
        )
        score_count = generate_score_for_edges(
            dataset / "edges_static.csv.gz",
            dataset / "score_functions.jsonl.gz",
            self.settings,
            42,
            0.2,
            self.edge_count,
        )
        raw = self.root / "raw.gr"
        raw.write_text(
            f"p sp 2 {self.edge_count}\n"
            + "".join(
                f"a 1 2 {6000 + arc_id}\n"
                for arc_id in range(self.edge_count)
            ),
            encoding="utf-8",
        )
        self.assertEqual(
            self.edge_count,
            validate_conversion_against_raw(
                dataset / "edges_static.csv.gz", raw, 1, 6000
            ),
        )
        manifest = {
            "schema_version": 3,
            "num_nodes": 2,
            "num_arcs": self.edge_count,
            "seed": 42,
            "score_edge_fraction": 0.2,
            "selected_score_edge_count": score_count,
            "conversion_contract": {
                "contract_id":
                    "declared_centisecond_normalization-v1"
            },
            "temporal_support": {"start": 0, "end": 10080},
            "dataset_checksum": dataset_checksum(dataset),
            "temporal_attribute_checksum":
                temporal_attribute_checksum(dataset),
        }
        atomic_write_json(dataset / "manifest.json", manifest)
        valid = validate_dataset_directory(
            dataset,
            2,
            self.edge_count,
            42,
            0.2,
            "declared_centisecond_normalization-v1",
            10080,
            raw,
            1,
            6000,
        )
        self.assertEqual([], valid["errors"])

        generate_score_for_edges(
            dataset / "edges_static.csv.gz",
            dataset / "score_functions.jsonl.gz",
            self.settings,
            43,
            0.2,
            self.edge_count,
        )
        invalid = validate_dataset_directory(
            dataset,
            2,
            self.edge_count,
            42,
            0.2,
            "declared_centisecond_normalization-v1",
            10080,
            raw,
            1,
            6000,
        )
        self.assertIn(
            "temporal-attribute checksum mismatch",
            invalid["errors"],
        )

    @staticmethod
    def _write_gzip(path: Path, content: str):
        with path.open("wb") as raw:
            with gzip.GzipFile(
                filename="", mode="wb", fileobj=raw, mtime=0
            ) as compressed:
                compressed.write(content.encode("utf-8"))


if __name__ == "__main__":
    unittest.main()
