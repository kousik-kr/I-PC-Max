"""Cross-platform Java/Maven discovery with a valid command-scoped JAVA_HOME."""
from __future__ import annotations

import os
from pathlib import Path
import re
import shutil
import subprocess


def executable(name: str) -> str:
    value = shutil.which(name)
    if value:
        return value
    if os.name == "nt":
        value = shutil.which(name + ".cmd") or shutil.which(name + ".exe")
        if value:
            return value
    return name


def resolved_java_home() -> str | None:
    configured = os.environ.get("JAVA_HOME")
    if configured and (Path(configured) / "bin" / ("java.exe" if os.name == "nt" else "java")).is_file():
        return configured
    try:
        completed = subprocess.run(
            [executable("java"), "-XshowSettings:properties", "-version"],
            check=False, capture_output=True, text=True, timeout=15,
        )
        match = re.search(r"^\s*java\.home\s*=\s*(.+?)\s*$", completed.stderr, re.MULTILINE)
        return match.group(1) if match else None
    except (OSError, subprocess.SubprocessError):
        return None


def environment() -> dict[str, str]:
    value = dict(os.environ)
    home = resolved_java_home()
    if home:
        value["JAVA_HOME"] = home
    return value
