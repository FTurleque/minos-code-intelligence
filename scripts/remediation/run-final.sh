#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-}"
PROGRAM_GRAPH_FILES="${2:-1000}"
if ! [[ "$PROGRAM_GRAPH_FILES" =~ ^[0-9]+$ ]] || (( PROGRAM_GRAPH_FILES < 4 || PROGRAM_GRAPH_FILES > 2000 )); then
  echo "ProgramGraph file count must be between 4 and 2000" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

HEAD_SHA="$(git rev-parse HEAD)"
STATUS_BEFORE="$(git status --porcelain=v1 --untracked-files=all)"
[[ -z "$STATUS_BEFORE" ]] || { echo "worktree must be clean before M28 qualification: $STATUS_BEFORE" >&2; exit 1; }

if [[ "$(date +%s)" -lt "$(date -d '2026-08-01T00:00:00+02:00' +%s)" ]]; then
  if [[ -n "${GITHUB_BASE_REF:-}" ]]; then
    BASE_REF="origin/${GITHUB_BASE_REF}"
  elif git show-ref --verify --quiet refs/heads/develop; then
    BASE_REF="develop"
  else
    BASE_REF="origin/develop"
  fi
  git diff --exit-code "${BASE_REF}...HEAD" -- .github/workflows
fi

python3 scripts/remediation/check-p0-p2.py
python3 scripts/m28/check-m28.py
python3 scripts/docs/product-facts.py --check
python3 scripts/m28/check-current-docs.py
python3 scripts/architecture/check-module-boundaries.py

if [[ "$MODE" == "--targeted" ]]; then
  ./mvnw -pl minos-application,minos-runtime-local -am test
else
  ./mvnw clean verify
fi

python3 scripts/quality/check-jacoco.py
bash scripts/m28/run-program-graph-performance.sh "$PROGRAM_GRAPH_FILES"

STATUS_AFTER="$(git status --porcelain=v1 --untracked-files=all)"
[[ -z "$STATUS_AFTER" ]] || { echo "qualification modified the worktree: $STATUS_AFTER" >&2; exit 1; }
[[ "$(git rev-parse HEAD)" == "$HEAD_SHA" ]] || { echo 'HEAD changed during qualification' >&2; exit 1; }

echo 'M28 CROSS-CUTTING VALIDATION SUCCESS'
echo "Validated HEAD: $HEAD_SHA"
