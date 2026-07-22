from pathlib import Path
import unittest

from experiments.scripts.common.config import load_design


class ConfigContractTest(unittest.TestCase):
    def test_paper_design_has_four_datasets_and_all_studies(self):
        design = load_design(Path("experiments/configs/paper_q1.yaml"))
        self.assertEqual(["NY", "FLA", "CAL", "USA"], design["datasets"])
        self.assertNotIn("OL", design["datasets"])
        self.assertEqual([f"E{index:02d}" for index in range(14)], [row["study_id"] for row in design["study_definitions"]])

    def test_smoke_design_is_dependency_free_json_compatible_yaml(self):
        design = load_design(Path("experiments/configs/paper_smoke.yaml"))
        self.assertTrue(design["smoke"])
        self.assertEqual([], design["datasets"])


if __name__ == "__main__":
    unittest.main()
