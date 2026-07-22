import json
from pathlib import Path
import tempfile
import unittest

from experiments.plots.common import make_figure


class PlotArtifactTest(unittest.TestCase):
    def test_all_formats_and_provenance_are_written(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            summary = root / "summary.jsonl"
            summary.write_text(json.dumps({"study_id": "E03", "dataset_id": "NY", "algorithm_id": "pace-b", "median_wall_time_ns": 12}) + "\n", encoding="utf-8")
            sidecar = make_figure(summary, root / "figures", "F1", "Runtime", {"E03"})
            self.assertEqual(1, sidecar["row_count"])
            self.assertTrue((root / "figures" / "f1.svg").read_text(encoding="utf-8").startswith("<svg"))
            self.assertTrue((root / "figures" / "f1.png").read_bytes().startswith(b"\x89PNG"))
            self.assertTrue((root / "figures" / "f1.pdf").read_bytes().startswith(b"%PDF"))


if __name__ == "__main__":
    unittest.main()
