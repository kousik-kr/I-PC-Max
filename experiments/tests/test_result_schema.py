import unittest

from experiments.scripts.common.status import TERMINAL_STATUSES


class ResultStatusTest(unittest.TestCase):
    def test_all_required_terminal_statuses_are_declared(self):
        self.assertEqual(
            {
                "SUCCESS", "TIMEOUT", "OUT_OF_MEMORY", "FUNCTION_HORIZON_EXCEEDED",
                "RESOURCE_LIMIT_EXCEEDED", "INVALID_INPUT", "INTERNAL_ERROR",
                "INFRASTRUCTURE_BLOCKED",
            },
            set(TERMINAL_STATUSES),
        )


if __name__ == "__main__":
    unittest.main()
