#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

HEAD_SHA="$(git rev-parse HEAD)"
git diff --quiet -- . || { echo 'worktree must be clean before P0-P2 qualification' >&2; exit 1; }
git diff --exit-code develop...HEAD -- .github/workflows

python3 scripts/remediation/check-p0-p2.py
python3 scripts/docs/product-facts.py --check
python3 scripts/architecture/check-module-boundaries.py

if [[ "${1:-}" == "--targeted" ]]; then
  ./mvnw -pl minos-application,minos-runtime-local -am test
else
  ./mvnw clean verify
fi

python3 scripts/quality/check-jacoco.py
git diff --quiet -- . || { echo 'qualification modified the worktree' >&2; exit 1; }
[[ "$(git rev-parse HEAD)" == "$HEAD_SHA" ]] || { echo 'HEAD changed during qualification' >&2; exit 1; }

echo 'P0-P2 LINUX AUDIT REMEDIATION VALIDATION SUCCESS'
echo "Validated HEAD: $HEAD_SHA"
echo 'GitHub Actions / M21-S2 were not invoked by this July-safe runner.'
