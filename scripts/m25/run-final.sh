#!/usr/bin/env bash
set -euo pipefail

EXPECTED_HEAD="${1:-}"
M25_BASE="b17631de59871848351a4139b12be6e0354989bc"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  echo "M25 LINUX REMOTE DISTRIBUTED INDEXING VALIDATION FAILED: $*" >&2
  exit 1
}

[[ "$EXPECTED_HEAD" =~ ^[0-9a-f]{40}$ ]] || fail "usage: ./scripts/m25/run-final.sh <lowercase-expected-head-sha>"
[[ "$(uname -s)" == "Linux" ]] || fail "Linux final qualification must run on Linux"
[[ "$(uname -m)" == "x86_64" || "$(uname -m)" == "amd64" ]] || fail "M25 Linux qualification requires x86_64"

cd "$REPO_ROOT"

head_sha() { git rev-parse HEAD | head -n 1; }
assert_clean() {
  local dirty
  dirty="$(git status --porcelain)"
  [[ -z "$dirty" ]] || fail "worktree is dirty during $1: $dirty"
}
assert_no_workflow_changes() {
  git diff --quiet "$M25_BASE" HEAD -- .github/workflows || fail "M25 forbids changes under .github/workflows"
}
resolve_python() { command -v python3 || command -v python || fail "Python is required"; }
require_command() { command -v "$1" >/dev/null 2>&1 || fail "$1 is required"; }
run_semantic_disabled() {
  env -u MINOS_SEMANTIC_MODEL -u MINOS_SEMANTIC_DIMENSIONS \
    -u MINOS_SEMANTIC_ENDPOINT -u MINOS_SEMANTIC_TIMEOUT_SECONDS \
    MINOS_SEMANTIC_PROVIDER=disabled "$@"
}

printf '%s\n' '=== MINOS M25 - FINAL Remote & Distributed Indexing Linux exact-head qualification ==='
assert_clean preflight
HEAD_SHA="$(head_sha)"
[[ "$HEAD_SHA" == "$EXPECTED_HEAD" ]] || fail "exact-head mismatch: expected=$EXPECTED_HEAD actual=$HEAD_SHA"
assert_no_workflow_changes
PYTHON="$(resolve_python)"
require_command java
require_command git
require_command go
JAVA_VERSION="$(java -version 2>&1 | head -n 1)"
GO_VERSION="$(go version 2>&1)"
[[ "$JAVA_VERSION" =~ \"24([\.\"]|$) ]] || fail "Java 24 is required; got $JAVA_VERSION"
[[ -x ./mvnw ]] || fail "./mvnw is required and must be executable"
echo "HEAD: $HEAD_SHA"
echo "Java: $JAVA_VERSION"
echo "Go: $GO_VERSION"

echo '[1/7] M25 static, documentation and M24 regression contracts...'
"$PYTHON" scripts/m25/check-remote-distributed.py
"$PYTHON" scripts/docs/check-current-docs.py
"$PYTHON" scripts/m24/check-polyglot.py

echo '[2/7] Full Java 24 Maven reactor...'
run_semantic_disabled ./mvnw clean verify

echo '[3/7] JaCoCo including M25 scope...'
"$PYTHON" scripts/quality/check-jacoco.py

echo '[4/7] Historical capability/provider regressions...'
"$PYTHON" scripts/m22/check-provider.py
"$PYTHON" scripts/m23/check-semantic.py

echo '[5/7] Real GitHub exact-revision/cache/worker/artifact/snapshot e2e...'
run_semantic_disabled "$PYTHON" scripts/m25/run-remote-e2e.py \
  --expected-head "$HEAD_SHA" --output target/m25/remote-e2e-linux.json

echo '[6/7] Static, docs and detailed evidence recheck...'
"$PYTHON" scripts/m25/check-remote-distributed.py
"$PYTHON" scripts/docs/check-current-docs.py
"$PYTHON" - "$HEAD_SHA" <<'PY'
import json
import sys
from pathlib import Path
evidence = json.loads(Path("target/m25/remote-e2e-linux.json").read_text(encoding="utf-8"))
expected = sys.argv[1]
assert evidence["status"] == "PASS"
assert evidence["commit"] == expected
assert evidence["provider"] == {"id": "scip-go", "version": "0.2.7", "runtime": "READY"}
assert evidence["sourceCache"] == {"first": "MISS", "second": "HIT", "index": "HIT"}
PY

echo '[7/7] Exact HEAD, workflow diff and clean-worktree final gate...'
FINAL_HEAD="$(head_sha)"
[[ "$FINAL_HEAD" == "$HEAD_SHA" ]] || fail "HEAD changed during qualification: start=$HEAD_SHA end=$FINAL_HEAD"
assert_no_workflow_changes
assert_clean final

printf '%s\n' 'M25 LINUX REMOTE DISTRIBUTED INDEXING VALIDATION SUCCESS'
printf 'Validated HEAD: %s\n' "$HEAD_SHA"
