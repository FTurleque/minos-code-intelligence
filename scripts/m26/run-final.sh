#!/usr/bin/env bash
set -euo pipefail

EXPECTED_HEAD="${1:-}"
M26_BASE="e37cf39fcf4f7e417c618fa0b16590100c1e0b91"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  echo "M26 LINUX RUNTIME DYNAMIC INTELLIGENCE VALIDATION FAILED: $*" >&2
  exit 1
}

[[ "$EXPECTED_HEAD" =~ ^[0-9a-f]{40}$ ]] || fail "usage: ./scripts/m26/run-final.sh <lowercase-expected-head-sha>"
[[ "$(uname -s)" == "Linux" ]] || fail "Linux final qualification must run on Linux"
[[ "$(uname -m)" == "x86_64" || "$(uname -m)" == "amd64" ]] || fail "M26 Linux qualification requires x86_64"

cd "$REPO_ROOT"
head_sha() { git rev-parse HEAD | head -n 1; }
assert_clean() {
  local dirty
  dirty="$(git status --porcelain)"
  [[ -z "$dirty" ]] || fail "worktree is dirty during $1: $dirty"
}
assert_no_workflow_changes() {
  git diff --quiet "$M26_BASE" HEAD -- .github/workflows || fail "M26 forbids changes under .github/workflows"
}
resolve_python() { command -v python3 || command -v python || fail "Python is required"; }
require_command() { command -v "$1" >/dev/null 2>&1 || fail "$1 is required"; }
run_semantic_disabled() {
  env -u MINOS_SEMANTIC_MODEL -u MINOS_SEMANTIC_DIMENSIONS \
    -u MINOS_SEMANTIC_ENDPOINT -u MINOS_SEMANTIC_TIMEOUT_SECONDS \
    MINOS_SEMANTIC_PROVIDER=disabled "$@"
}

printf '%s\n' '=== MINOS M26 - FINAL Runtime & Dynamic Intelligence Linux exact-head qualification ==='
assert_clean preflight
HEAD_SHA="$(head_sha)"
[[ "$HEAD_SHA" == "$EXPECTED_HEAD" ]] || fail "exact-head mismatch: expected=$EXPECTED_HEAD actual=$HEAD_SHA"
assert_no_workflow_changes
PYTHON="$(resolve_python)"
require_command java
require_command javac
require_command git
JAVA_VERSION="$(java -version 2>&1 | head -n 1)"
[[ "$JAVA_VERSION" =~ \"24([\.\"]|$) ]] || fail "Java 24 is required; got $JAVA_VERSION"
[[ -x ./mvnw ]] || fail "./mvnw is required and must be executable"
echo "HEAD: $HEAD_SHA"
echo "Java: $JAVA_VERSION"

echo '[1/7] M26 static, documentation and prior-milestone contracts...'
"$PYTHON" scripts/m26/check-runtime-dynamic.py
"$PYTHON" scripts/docs/check-current-docs.py
"$PYTHON" scripts/m25/check-remote-distributed.py
"$PYTHON" scripts/m24/check-polyglot.py

echo '[2/7] Full Java 24 Maven reactor...'
run_semantic_disabled ./mvnw clean verify

echo '[3/7] JaCoCo including M26 scope...'
"$PYTHON" scripts/quality/check-jacoco.py

echo '[4/7] Historical provider and semantic regression contracts...'
"$PYTHON" scripts/m22/check-provider.py
"$PYTHON" scripts/m23/check-semantic.py

echo '[5/7] Shaded CLI runtime import/correlation/storage/report e2e...'
run_semantic_disabled "$PYTHON" scripts/m26/run-runtime-e2e.py \
  --expected-head "$HEAD_SHA" --output target/m26/runtime-e2e-linux.json

echo '[6/7] Static, docs and detailed evidence recheck...'
"$PYTHON" scripts/m26/check-runtime-dynamic.py
"$PYTHON" scripts/docs/check-current-docs.py
"$PYTHON" - "$HEAD_SHA" <<'PY'
import json
import sys
from pathlib import Path
evidence = json.loads(Path("target/m26/runtime-e2e-linux.json").read_text(encoding="utf-8"))
expected = sys.argv[1]
assert evidence["status"] == "PASS"
assert evidence["commit"] == expected
assert evidence["format"] == "minos-runtime-observation-v1"
assert evidence["nature"] == "OBSERVED_PARTIAL"
assert evidence["exhaustive"] is False
assert evidence["session"] == {"id": "run-m26-e2e", "completeness": "PARTIAL", "activeSnapshotAligned": True}
assert evidence["correlation"] == {"resolved": 4, "ambiguous": 1, "unresolved": 1}
assert evidence["staticSnapshot"] == {"id": "snapshot-m26-e2e", "symbols": 4, "observedSymbols": 2}
assert evidence["failClosed"] == {"completeRejected": True, "sessionMutationRejected": True}
PY

echo '[7/7] Exact HEAD, workflow diff and clean-worktree final gate...'
FINAL_HEAD="$(head_sha)"
[[ "$FINAL_HEAD" == "$HEAD_SHA" ]] || fail "HEAD changed during qualification: start=$HEAD_SHA end=$FINAL_HEAD"
assert_no_workflow_changes
assert_clean final

printf '%s\n' 'M26 LINUX RUNTIME DYNAMIC INTELLIGENCE VALIDATION SUCCESS'
printf 'Validated HEAD: %s\n' "$HEAD_SHA"
