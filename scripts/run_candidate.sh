#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/target/pace-bench.jar"
if [[ ! -f "$JAR" ]]; then
  mvn -q -f "$ROOT/pom.xml" -DskipTests package
fi

args=()
while (($#)); do
  case "$1" in
    --queries) args+=(--query-file "$2"); shift 2 ;;
    --output) args+=(--output-jsonl "$2"); shift 2 ;;
    *) args+=("$1"); shift ;;
  esac
done
exec java -jar "$JAR" "${args[@]}"
