"""Bounded local process executor."""
from __future__ import annotations

import concurrent.futures
from typing import Any, Callable


def run_jobs(
    jobs: list[dict[str, Any]],
    worker: Callable[[dict[str, Any]], dict[str, Any]],
    max_concurrent: int,
) -> list[dict[str, Any]]:
    if max_concurrent < 1:
        raise ValueError("max_concurrent must be positive")
    results: list[dict[str, Any]] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=max_concurrent) as pool:
        futures = {pool.submit(worker, job): job for job in jobs}
        for future in concurrent.futures.as_completed(futures):
            results.append(future.result())
    return sorted(results, key=lambda item: item["job_id"])
