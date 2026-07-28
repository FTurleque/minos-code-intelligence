#!/usr/bin/env bash
set -euo pipefail

EXPECTED_HEAD="${1:-}"
M24_BASE="8dbe34cb9e524acb62becda4faa263d74b90b9a9"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  echo "M24 LINUX POLYGLOT EXPANSION VALIDATION FAILED: $*" >&2
  exit 1
}

[[ -n "$EXPECTED_HEAD" ]] || fail "usage: ./scripts/m24/run-final.sh <expected-head-sha>"
[[ "$(uname -s)" == "Linux" ]] || fail "Linux final qualification must run on Linux"

cd "$REPO_ROOT"

head_sha() {
  git rev-parse HEAD | head -n 1
}

assert_clean() {
  local stage="$1"
  local dirty
  dirty="$(git status --porcelain)"
  [[ -z "$dirty" ]] || fail "worktree is dirty during $stage: $dirty"
}

assert_no_workflow_changes() {
  git diff --quiet "$M24_BASE" HEAD -- .github/workflows || fail "M24 forbids changes under .github/workflows"
}

resolve_python() {
  command -v python3 || command -v python || fail "Python is required"
}

assert_java24() {
  local version
  version="$(java -version 2>&1 | head -n 1)"
  echo "Java: $version"
  [[ "$version" =~ \"24([\.\"]|$) ]] || fail "Java 24 is required"
}

run_with_semantic_disabled() {
  env \
    -u MINOS_SEMANTIC_MODEL \
    -u MINOS_SEMANTIC_DIMENSIONS \
    -u MINOS_SEMANTIC_ENDPOINT \
    -u MINOS_SEMANTIC_TIMEOUT_SECONDS \
    MINOS_SEMANTIC_PROVIDER=disabled \
    "$@"
}

printf '%s\n' '=== MINOS M24 - FINAL Polyglot Expansion Linux exact-head qualification ==='
assert_clean preflight
HEAD_SHA="$(head_sha)"
[[ "$HEAD_SHA" == "$EXPECTED_HEAD" ]] || fail "exact-head mismatch: expected=$EXPECTED_HEAD actual=$HEAD_SHA"
assert_no_workflow_changes
PYTHON="$(resolve_python)"
assert_java24
[[ -x ./mvnw ]] || fail "./mvnw is required and must be executable"

echo "HEAD: $HEAD_SHA"
echo '[1/7] M24 static provider/discovery/documentation contract...'
"$PYTHON" scripts/m24/check-polyglot.py
"$PYTHON" scripts/docs/check-current-docs.py

echo '[2/7] Full Java 24 Maven reactor + JaCoCo with semantic opt-in isolated...'
run_with_semantic_disabled ./mvnw clean verify
"$PYTHON" scripts/quality/check-jacoco.py

echo '[3/7] M17/M22/M23/M24 functional and static regressions...'
"$PYTHON" scripts/m22/check-provider.py
"$PYTHON" scripts/m23/check-semantic.py
run_with_semantic_disabled ./mvnw -q -pl minos-application,minos-provider-scip,minos-app -am test \
  '-Dtest=M24PolyglotDiscoveryTest,M24PolyglotProviderTest,M24PolyglotIdentityProvenanceTest,M24PolyglotProcessPlanFactoryTest,ManagedPolyglotScipRuntimeManagerTest,M17ProviderPlatformTest' \
  '-Dsurefire.failIfNoSpecifiedTests=false'

echo '[4/7] Real M24 provider readiness/install/index/snapshot/identity/provenance evaluation on Linux...'
run_with_semantic_disabled "$PYTHON" scripts/m24/run-provider-e2e.py --output target/m24/provider-evaluation-linux.json

echo '[5/7] Recheck M22/M23/M24 contracts and documentation after provider execution...'
"$PYTHON" scripts/m22/check-provider.py
"$PYTHON" scripts/m23/check-semantic.py
"$PYTHON" scripts/m24/check-polyglot.py
"$PYTHON" scripts/docs/check-current-docs.py

echo '[6/7] Recheck JaCoCo aggregate gates...'
"$PYTHON" scripts/quality/check-jacoco.py

echo '[7/7] Exact HEAD, workflow-diff and clean-worktree final gate...'
FINAL_HEAD="$(head_sha)"
[[ "$FINAL_HEAD" == "$HEAD_SHA" ]] || fail "HEAD changed during qualification: start=$HEAD_SHA end=$FINAL_HEAD"
assert_no_workflow_changes
assert_clean final

printf '%s\n' 'M24 LINUX POLYGLOT EXPANSION VALIDATION SUCCESS'
printf 'Validated HEAD: %s\n' "$HEAD_SHA"
