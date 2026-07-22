#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
profile=release
clean=false
sanitizers=false
while (($#)); do
  case "$1" in
    --debug) profile=debug ;;
    --release) profile=release ;;
    --clean) clean=true ;;
    --sanitizers) sanitizers=true ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done
echo "java=$(java -version 2>&1 | head -n 1)"
echo "maven=$(mvn -version | head -n 1)"
echo "build_type=$profile sanitizers=$sanitizers"
$clean && python "$ROOT/scripts/run_maven.py" -f "$ROOT/pom.xml" clean
extra=()
$sanitizers && extra+=(-Psanitizers)
python "$ROOT/scripts/run_maven.py" -f "$ROOT/pom.xml" -Dpace.build.type="$profile" "${extra[@]}" test package
