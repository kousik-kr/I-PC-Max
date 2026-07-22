import json
from pathlib import Path
import tempfile
import unittest

from experiments.scripts.run_all import _prepare_run


class ResumeSemanticsTest(unittest.TestCase):
    def test_resume_requires_identical_immutable_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            design = {
                "paths": {"results_root": str(Path(directory) / "results")},
                "config_hash": "a" * 64,
            }
            _prepare_run(design, "run-a", "local", False)
            _prepare_run(design, "run-a", "local", True)
            changed = dict(design, config_hash="b" * 64)
            with self.assertRaisesRegex(ValueError, "different config"):
                _prepare_run(changed, "run-a", "local", True)


if __name__ == "__main__":
    unittest.main()
