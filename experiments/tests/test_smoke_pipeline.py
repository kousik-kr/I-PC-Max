from pathlib import Path
import tempfile
import unittest

from experiments.scripts.build_matrices import build_all
from experiments.scripts.common.config import load_design


class SmokePlanTest(unittest.TestCase):
    def test_smoke_has_six_exact_jobs_and_no_road_dataset(self):
        design = load_design(Path("experiments/configs/paper_smoke.yaml"))
        with tempfile.TemporaryDirectory() as directory:
            report = build_all(design, Path(directory))
            lines = (Path(directory) / "e01.jsonl").read_text(encoding="utf-8").splitlines()
        self.assertEqual(6, report["total_jobs"])
        self.assertTrue(all('"dataset_id":"demo"' in line for line in lines))


if __name__ == "__main__":
    unittest.main()
