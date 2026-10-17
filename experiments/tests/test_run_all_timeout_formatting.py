from experiments.scripts.run_all import _format_timeout_upper_bound


def test_unlimited_timeout_upper_bound_is_rendered_explicitly():
    assert _format_timeout_upper_bound(None) == "unlimited"


def test_finite_timeout_upper_bound_keeps_grouped_seconds():
    assert _format_timeout_upper_bound(12500) == "12,500 seconds"
