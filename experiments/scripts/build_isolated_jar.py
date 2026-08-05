#!/usr/bin/env python3
"""Build a PACE benchmark JAR without writing the repository's live target tree."""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


REPO_ROOT = Path(__file__).resolve().parents[2]


def _live_process_uses(path: Path) -> bool:
    needle = path.resolve()
    proc = Path("/proc")
    for entry in proc.iterdir():
        if not entry.name.isdigit():
            continue
        try:
            command = [
                token.decode()
                for token in (entry / "cmdline").read_bytes().split(b"\0")
                if token
            ]
        except (OSError, UnicodeDecodeError):
            continue
        for index, token in enumerate(command[:-1]):
            if token != "-jar":
                continue
            try:
                if Path(command[index + 1]).resolve() == needle:
                    return True
            except OSError:
                continue
    return False


def build(
    output: Path,
    *,
    run_tests: bool = False,
    test_reports_output: Path | None = None,
) -> dict[str, object]:
    output = output if output.is_absolute() else REPO_ROOT / output
    output = output.resolve()
    if _live_process_uses(output):
        raise RuntimeError(f"refusing to replace JAR used by a live process: {output}")
    with tempfile.TemporaryDirectory(prefix="pace-isolated-build-") as temporary:
        work = Path(temporary) / "repo"

        def ignore(directory: str, names: list[str]) -> set[str]:
            relative = Path(directory).resolve().relative_to(REPO_ROOT)
            ignored = {".git", "target", ".idea"} & set(names)
            if relative == Path("data"):
                ignored.update(names)
            if relative == Path("experiments/results"):
                ignored.update(names)
            return ignored

        shutil.copytree(REPO_ROOT, work, ignore=ignore)
        if run_tests:
            tested = subprocess.run(
                ["mvn", "-q", "test"],
                cwd=work,
                check=False,
            )
            if tested.returncode != 0:
                raise RuntimeError(
                    f"isolated Maven tests failed: {tested.returncode}"
                )
            if test_reports_output is not None:
                reports = work / "target" / "surefire-reports"
                destination = (
                    test_reports_output
                    if test_reports_output.is_absolute()
                    else REPO_ROOT / test_reports_output
                ).resolve()
                destination.mkdir(parents=True, exist_ok=True)
                for report in reports.glob("TEST-*.xml"):
                    shutil.copy2(report, destination / report.name)
        completed = subprocess.run(
            ["mvn", "-q", "-DskipTests", "package"],
            cwd=work,
            check=False,
        )
        if completed.returncode != 0:
            raise RuntimeError(f"isolated Maven package failed: {completed.returncode}")
        built = work / "target" / "pace-bench.jar"
        if not built.is_file():
            raise RuntimeError("isolated build did not produce target/pace-bench.jar")
        output.parent.mkdir(parents=True, exist_ok=True)
        staged = output.with_name(output.name + f".tmp-{os.getpid()}")
        shutil.copy2(built, staged)
        os.replace(staged, output)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    return {"jar": str(output), "sha256": digest, "bytes": output.stat().st_size}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--run-tests",
        action="store_true",
        help="run the complete Maven test suite in the isolated copy first",
    )
    parser.add_argument(
        "--test-reports-output",
        type=Path,
        help=(
            "copy isolated Surefire XML evidence to this directory after "
            "all tests pass"
        ),
    )
    args = parser.parse_args()
    try:
        print(build(
            args.output,
            run_tests=args.run_tests,
            test_reports_output=args.test_reports_output,
        ))
        return 0
    except (OSError, RuntimeError) as failure:
        print(f"isolated build: {failure}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
