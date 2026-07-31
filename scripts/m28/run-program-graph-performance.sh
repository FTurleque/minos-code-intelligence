#!/usr/bin/env bash
set -euo pipefail

FILE_COUNT="${1:-1000}"
OUTPUT_JSON="${2:-}"
if ! [[ "$FILE_COUNT" =~ ^[0-9]+$ ]] || (( FILE_COUNT < 4 || FILE_COUNT > 2000 )); then
  echo "file count must be an integer between 4 and 2000" >&2
  exit 2
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MAVEN="$REPO_ROOT/mvnw"
if [[ ! -f "$MAVEN" ]]; then
  echo "Maven wrapper not found: $MAVEN" >&2
  exit 2
fi
if [[ -z "$OUTPUT_JSON" ]]; then
  OUTPUT_JSON="$REPO_ROOT/target/qualification/m28/program-graph-linux.json"
fi
mkdir -p "$(dirname "$OUTPUT_JSON")"
rm -f "$OUTPUT_JSON"

(
  cd "$REPO_ROOT"
  "$MAVEN" \
    -pl minos-application \
    -am \
    -Dtest=ProgramGraphPerformanceQualificationTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    "-Dminos.m28.programGraph.files=$FILE_COUNT" \
    "-Dminos.m28.programGraph.result=$OUTPUT_JSON" \
    test
)

python3 - "$OUTPUT_JSON" "$FILE_COUNT" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
expected_files = int(sys.argv[2])
if not path.is_file():
    raise SystemExit(f"M28 ProgramGraph performance result was not produced: {path}")
result = json.loads(path.read_text(encoding="utf-8"))
if result.get("profile") != "M28_PROGRAM_GRAPH_JAVA":
    raise SystemExit(f"unexpected profile: {result.get('profile')}")
if int(result.get("file_count", -1)) != expected_files:
    raise SystemExit(f"unexpected file count: {result.get('file_count')}")
if int(result.get("cache_hits", -1)) != 1 or int(result.get("cache_misses", -1)) != 1:
    raise SystemExit(
        f"expected one cache hit and one miss, got hits={result.get('cache_hits')} misses={result.get('cache_misses')}")
if result.get("warm_identity_hit") is not True:
    raise SystemExit("warm ProgramGraph path was not an identity cache hit")
if result.get("modified_source_disposition") != "JAVA_ADVANCED_PROVIDER_SOURCE_DIFFERS_FROM_SNAPSHOT_FINGERPRINT":
    raise SystemExit(f"unexpected modified-source disposition: {result.get('modified_source_disposition')}")
if result.get("decision") != "KEEP_FINGERPRINT_CONSTRAINED_IN_MEMORY_CACHE":
    raise SystemExit(f"unexpected backend decision: {result.get('decision')}")
print(
    "Files={file_count} bytes={source_bytes} cold={cold_nanos}ns warm={warm_nanos}ns modified={modified_source_nanos}ns"
    .format(**result))
PY

HEAD="$(git -C "$REPO_ROOT" rev-parse HEAD)"
echo "M28 PROGRAM GRAPH PERFORMANCE QUALIFICATION SUCCESS"
echo "Validated HEAD: $HEAD"
echo "Result: $OUTPUT_JSON"
