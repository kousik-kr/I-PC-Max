import unittest

from experiments.scripts.run_stratified_paper_pilot import (
    _inclusive_iqr,
    _kaplan_meier_median,
)


class StratifiedPilotStatisticsTest(unittest.TestCase):
    def test_inclusive_iqr_uses_completed_observations_only(self) -> None:
        self.assertEqual(
            [168.0, 254.0],
            _inclusive_iqr([99.0, 237.0, 271.0]),
        )

    def test_kaplan_meier_median_is_not_invented_from_censor_times(
        self,
    ) -> None:
        observations = [
            (99.0, True),
            (237.0, True),
            (271.0, True),
        ] + [(300.0, False)] * 13
        self.assertIsNone(
            _kaplan_meier_median(observations)
        )


if __name__ == "__main__":
    unittest.main()
