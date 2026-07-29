#!/usr/bin/env bash
set -euo pipefail

EXPECTED_HEAD="${1:-}"
M24_BASE="8dbe34cb9e524acb62becda4faa263d74b90b9a9"
RUST_ANALYZER_VERSION="0.3.2989"
RUST_ANALYZER_RELEASE="2026-07-27"
RUST_ANALYZER_COMMIT="12c3381"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  echo "M24 LINUX POLYGLOT EXPANSION VALIDATION FAILED: $*" >&2
  exit 1
}

[[ -n "$EXPECTED_HEAD" ]] || fail "usage: ./scripts/m24/run-final.sh <expected-head-sha>"
[[ "$(uname -s)" == "Linux" ]] || fail "Linux final qualification must run on Linux"
[[ "$(uname -m)" == "x86_64" || "$(uname -m)" == "amd64" ]] || fail "M24 Linux provider qualification requires x86_64"

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

require_command() {
  local command_name="$1"
  command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required for M24 Linux exact-head qualification"
}

assert_java24() {
  local version
  version="$(java -version 2>&1 | head -n 1)"
  echo "Java: $version"
  [[ "$version" =~ \"24([\.\"]|$) ]] || fail "Java 24 is required"
}

assert_provider_prerequisites() {
  local clang_version dotnet_version dotnet_major go_version cargo_version rustc_version rust_analyzer_version

  require_command cmake
  require_command scip-clang
  clang_version="$(scip-clang --version 2>&1)"
  echo "scip-clang: $clang_version"
  [[ "$clang_version" == *"0.4.0"* ]] || fail "scip-clang 0.4.0 is required"

  require_command dotnet
  dotnet_version="$(dotnet --version 2>&1)" || fail "dotnet --version failed"
  dotnet_major="${dotnet_version%%.*}"
  [[ "$dotnet_major" =~ ^[0-9]+$ ]] || fail "unable to parse dotnet SDK version: $dotnet_version"
  (( dotnet_major >= 10 )) || fail ".NET SDK 10+ is required for scip-dotnet 0.2.14; got $dotnet_version"
  echo ".NET SDK: $dotnet_version"

  require_command go
  go_version="$(go version 2>&1)"
  echo "Go: $go_version"

  require_command cargo
  require_command rustc
  require_command rust-analyzer
  cargo_version="$(cargo --version 2>&1)"
  rustc_version="$(rustc --version 2>&1)"
  rust_analyzer_version="$(rust-analyzer --version 2>&1)"
  echo "cargo: $cargo_version"
  echo "rustc: $rustc_version"
  echo "rust-analyzer: $rust_analyzer_version"
  [[ "$rust_analyzer_version" == *"$RUST_ANALYZER_VERSION"* \
    && "$rust_analyzer_version" == *"$RUST_ANALYZER_COMMIT"* ]] \
    || fail "rust-analyzer must match v$RUST_ANALYZER_VERSION / commit $RUST_ANALYZER_COMMIT from release $RUST_ANALYZER_RELEASE"
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
assert_provider_prerequisites

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
run_with_semantic_disabled "$PYTHON" scripts/m24/run-provider-e2e.py \
  --output target/m24/provider-evaluation-linux.json \
  --require-e2e 'scip-clang,scip-dotnet,scip-go,rust-analyzer-scip'

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
