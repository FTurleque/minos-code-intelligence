#!/usr/bin/env bash
set -euo pipefail

EXPECTED_HEAD="${1:-}"
M27_BASE="5db06f2a778b60b318ae6d83ad76928c24672810"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() { echo "M27 LINUX TEAM HOSTED MODE VALIDATION FAILED: $*" >&2; exit 1; }
[[ "$EXPECTED_HEAD" =~ ^[0-9a-f]{40}$ ]] || fail "usage: ./scripts/m27/run-final.sh <lowercase-expected-head-sha>"
[[ "$(uname -s)" == "Linux" ]] || fail "Linux final qualification must run on Linux"
[[ "$(uname -m)" == "x86_64" || "$(uname -m)" == "amd64" ]] || fail "M27 Linux qualification requires x86_64"

cd "$REPO_ROOT"
head_sha() { git rev-parse HEAD | head -n 1; }
assert_clean() { local dirty; dirty="$(git status --porcelain)"; [[ -z "$dirty" ]] || fail "worktree is dirty during $1: $dirty"; }
assert_no_workflows() { git diff --quiet "$M27_BASE" HEAD -- .github/workflows || fail "M27 forbids changes under .github/workflows"; }
resolve_python() { command -v python3 || command -v python || fail "Python is required"; }
require_command() { command -v "$1" >/dev/null 2>&1 || fail "$1 is required"; }
run_clean_env() {
  env -u MINOS_SEMANTIC_MODEL -u MINOS_SEMANTIC_DIMENSIONS -u MINOS_SEMANTIC_ENDPOINT \
    -u MINOS_SEMANTIC_TIMEOUT_SECONDS -u MINOS_HOSTED_MODE -u MINOS_TEAM_TOKEN \
    MINOS_SEMANTIC_PROVIDER=disabled "$@"
}

echo '=== MINOS M27 - FINAL Team / Hosted Mode Linux exact-head qualification ==='
assert_clean preflight
HEAD_SHA="$(head_sha)"
[[ "$HEAD_SHA" == "$EXPECTED_HEAD" ]] || fail "exact-head mismatch: expected=$EXPECTED_HEAD actual=$HEAD_SHA"
assert_no_workflows
PYTHON="$(resolve_python)"
require_command java; require_command javac; require_command git
JAVA_VERSION="$(java -version 2>&1 | head -n 1)"
[[ "$JAVA_VERSION" =~ \"24([\.\"]|$) ]] || fail "Java 24 is required; got $JAVA_VERSION"
[[ -x ./mvnw ]] || fail "./mvnw is required and must be executable"
echo "HEAD: $HEAD_SHA"
echo "Java: $JAVA_VERSION"

echo '[1/7] M27 static, documentation and previous milestone contracts...'
"$PYTHON" scripts/m27/check-hosted.py
"$PYTHON" scripts/docs/check-current-docs.py
"$PYTHON" scripts/m26/check-runtime-dynamic.py

echo '[2/7] Full Java 24 Maven reactor...'
run_clean_env ./mvnw clean verify

echo '[3/7] JaCoCo including M27 scope...'
"$PYTHON" scripts/quality/check-jacoco.py

echo '[4/7] Historical polyglot, remote and runtime regression contracts...'
"$PYTHON" scripts/m24/check-polyglot.py
"$PYTHON" scripts/m25/check-remote-distributed.py
"$PYTHON" scripts/m26/check-runtime-dynamic.py

echo '[5/7] Shaded CLI tenant/auth/RBAC/encryption/audit/retention e2e...'
run_clean_env "$PYTHON" scripts/m27/run-hosted-e2e.py --expected-head "$HEAD_SHA" \
  --output target/m27/hosted-e2e-linux.json

echo '[6/7] Detailed evidence recheck...'
"$PYTHON" scripts/m27/check-hosted.py
"$PYTHON" scripts/docs/check-current-docs.py
"$PYTHON" - "$HEAD_SHA" <<'PY'
import json, sys
from pathlib import Path
e = json.loads(Path("target/m27/hosted-e2e-linux.json").read_text(encoding="utf-8"))
assert e["status"] == "PASS" and e["commit"] == sys.argv[1]
assert e["mode"] == "OPT_IN_LOCAL_CONTROL_PLANE"
assert e["isolation"]["crossTenantLeak"] is False
assert e["authentication"] == {"format": "mht1", "tokenInArguments": False, "oldKeyRejected": True}
assert e["authorization"] == {"viewerRead": True, "viewerMutationDenied": True, "denialAudited": True}
assert e["binding"]["snapshotId"] == "snapshot-m27-e2e" and e["binding"]["staleRejected"] is True
assert e["storage"] == {"algorithm": "AES-256-GCM", "plaintextAbsent": True, "tamperRejected": True}
assert e["retention"]["implicitDeletion"] is False
assert e["mcp"] == {"tools": 31, "readOnlyTeamTools": 5, "tokenArguments": False}
PY

echo '[7/7] Exact HEAD, workflow diff and clean-worktree final gate...'
FINAL_HEAD="$(head_sha)"
[[ "$FINAL_HEAD" == "$HEAD_SHA" ]] || fail "HEAD changed during qualification: start=$HEAD_SHA end=$FINAL_HEAD"
assert_no_workflows
assert_clean final
echo 'M27 LINUX TEAM HOSTED MODE VALIDATION SUCCESS'
echo "Validated HEAD: $HEAD_SHA"
