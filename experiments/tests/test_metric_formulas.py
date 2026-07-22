import unittest

from experiments.scripts.summarize_results import _bootstrap_median, _holm, _percentile, _wilcoxon


class MetricFormulaTest(unittest.TestCase):
    def test_percentile_and_bootstrap_are_deterministic(self):
        values = [1.0, 2.0, 3.0, 4.0]
        self.assertEqual(1.0, _percentile(values, 0.25))
        self.assertEqual(3.0, _percentile(values, 0.75))
        self.assertEqual(_bootstrap_median(values, 7, 100), _bootstrap_median(values, 7, 100))

    def test_empty_metrics_remain_null(self):
        self.assertIsNone(_percentile([], 0.5))
        self.assertEqual((None, None), _bootstrap_median([], 7))

    def test_paired_statistics_and_holm(self):
        p_value, effect = _wilcoxon([(3, 1), (4, 2), (5, 3)])
        self.assertIsNotNone(p_value)
        self.assertEqual(1.0, effect)
        rows = [{"p_value": 0.01}, {"p_value": 0.04}]
        _holm(rows)
        self.assertEqual([0.02, 0.04], [row["holm_p_value"] for row in rows])


if __name__ == "__main__":
    unittest.main()
