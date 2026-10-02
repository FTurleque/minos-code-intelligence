#!/usr/bin/env bash
set -euo pipefail

SCIP_JAVA_VERSION="${SCIP_JAVA_VERSION:-0.13.1}"
COURSIER_COMMAND="${COURSIER_COMMAND:-cs}"
SCIP_COMMAND="${SCIP_COMMAND:-scip}"

usage() {
  cat <<'EOF'
Usage:
  scripts/m0/run-scip-java.sh <project-path> [output-directory]

Environment variables:
  SCIP_JAVA_VERSION  Version de scip-java (défaut: 0.13.1)
  COURSIER_COMMAND   Commande Coursier (défaut: cs)
  SCIP_COMMAND       Commande SCIP CLI (défaut: scip)
EOF
}

require_command() {
  local command_name="$1"
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Commande requise introuvable : $command_name" >&2
    exit 2
  fi
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage >&2
  exit 2
fi

PROJECT_PATH="$(cd "$1" && pwd)"
OUTPUT_DIRECTORY="${2:-$PROJECT_PATH/.minos-m0/scip-java}"
mkdir -p "$OUTPUT_DIRECTORY"
OUTPUT_DIRECTORY="$(cd "$OUTPUT_DIRECTORY" && pwd)"

require_command java
require_command "$COURSIER_COMMAND"
require_command "$SCIP_COMMAND"

METADATA_FILE="$OUTPUT_DIRECTORY/environment.txt"
INDEX_DESTINATION="$OUTPUT_DIRECTORY/index.scip"
LINT_FILE="$OUTPUT_DIRECTORY/lint.txt"
STATS_FILE="$OUTPUT_DIRECTORY/stats.txt"
SNAPSHOT_DIRECTORY="$OUTPUT_DIRECTORY/snapshot"

printf 'Projet       : %s\n' "$PROJECT_PATH"
printf 'Sorties      : %s\n' "$OUTPUT_DIRECTORY"
printf 'scip-java    : %s\n\n' "$SCIP_JAVA_VERSION"

{
  printf 'date=%s\n' "$(date -Iseconds)"
  printf 'project=%s\n' "$PROJECT_PATH"
  printf 'scipJavaVersion=%s\n' "$SCIP_JAVA_VERSION"
  printf '\n=== java -version ===\n'
  java -version 2>&1
  printf '\n=== scip --version ===\n'
  "$SCIP_COMMAND" --version 2>&1
} > "$METADATA_FILE"

pushd "$PROJECT_PATH" >/dev/null
trap 'popd >/dev/null 2>&1 || true' EXIT

COORDINATE="org.scip-code:scip-java:$SCIP_JAVA_VERSION"
SCIP_JAVA_MAIN_CLASS="org.scip_code.scip_java.ScipJava"

echo "==> Génération de index.scip avec scip-java"
"$COURSIER_COMMAND" launch "$COORDINATE" --jvm system --main "$SCIP_JAVA_MAIN_CLASS" -- index

if [[ ! -f index.scip ]]; then
  echo "scip-java n'a pas produit index.scip dans $PROJECT_PATH" >&2
  exit 3
fi

cp -f index.scip "$INDEX_DESTINATION"

echo "==> Validation scip lint"
"$SCIP_COMMAND" lint "$INDEX_DESTINATION" 2>&1 | tee "$LINT_FILE"

echo "==> Statistiques scip stats"
"$SCIP_COMMAND" stats --from "$INDEX_DESTINATION" 2>&1 | tee "$STATS_FILE"

rm -rf "$SNAPSHOT_DIRECTORY"
echo "==> Génération du snapshot SCIP"
"$SCIP_COMMAND" snapshot --from "$INDEX_DESTINATION" --to "$SNAPSHOT_DIRECTORY"

printf '\nExpérience scip-java terminée.\n'
printf 'Index     : %s\n' "$INDEX_DESTINATION"
printf 'Lint      : %s\n' "$LINT_FILE"
printf 'Stats     : %s\n' "$STATS_FILE"
printf 'Snapshot  : %s\n' "$SNAPSHOT_DIRECTORY"
printf 'Contexte  : %s\n' "$METADATA_FILE"
