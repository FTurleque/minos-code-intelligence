#!/usr/bin/env bash
# Real offline Maven indexing under the qualified Linux sandbox (ADR SEC-0075).
#
# 1. A qualification Maven repository is populated OUTSIDE the sandbox, with network, from the trusted fixture
#    fixtures/java/java-simple only. Running Maven on an untrusted project to populate a repository is exactly
#    what MINOS forbids its users; the fixture is part of this repository and reviewed like the code.
# 2. MINOS installs scip-java (MINOS downloads and verifies its tools), the repository is designated by
#    MINOS_SCIP_JAVA_MAVEN_REPOSITORY, and `minos index` builds a copy of the fixture under the sandbox with the
#    network denied. The sandbox is required: without it scip-java is refused, and so is this job.
# 3. The SCIP artifact must exist and the designated repository must be unchanged (file list and mtimes).
#
# Usage (Linux, delegated cgroup v2, bubblewrap, Maven 3.9.x under /usr, JAVA_HOME = a JDK with javac):
#   bash scripts/ci/delegate-linux-cgroup.sh --attach-pid $$ && bash scripts/ci/qualify-linux-offline-maven-index.sh
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$(mktemp -d "${RUNNER_TEMP:-/tmp}/minos-offline-maven.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

shopt -s nullglob
SHADED_JARS=("$REPOSITORY_ROOT"/target/minos-code-intelligence-*-all.jar)
shopt -u nullglob
if [[ ${#SHADED_JARS[@]} -ne 1 ]]; then
  echo "expected exactly one shaded MINOS jar under target/ (run the Maven verify first)" >&2
  exit 1
fi
JAR="${SHADED_JARS[0]}"

MVN="$(command -v mvn || true)"
if [[ -z "$MVN" ]]; then
  echo "Maven 3.9.x is required in PATH" >&2
  exit 1
fi
MAVEN_VERSION="$(mvn -v | sed -n 's/^Apache Maven \([0-9.]*\).*/\1/p')"
if [[ "$MAVEN_VERSION" != 3.9.* ]]; then
  echo "Maven 3.9.x is required, found ${MAVEN_VERSION:-none}" >&2
  exit 1
fi

QUALIFICATION_REPOSITORY="$WORK/maven-repository"
mkdir -p "$QUALIFICATION_REPOSITORY"
chmod 700 "$WORK" "$QUALIFICATION_REPOSITORY"

# 1. Trusted population, outside the sandbox, on a throwaway copy of the fixture.
cp -R "$REPOSITORY_ROOT/fixtures/java/java-simple" "$WORK/populate"
mvn -B -ntp -f "$WORK/populate/pom.xml" "-Dmaven.repo.local=$QUALIFICATION_REPOSITORY" clean verify -DskipTests
rm -rf "$WORK/populate"
# The designated repository must look like a Maven repository: no settings or hidden entry at its root.
find "$QUALIFICATION_REPOSITORY" -maxdepth 1 -mindepth 1 \( -name '.*' -o -name 'settings*.xml' \) -exec rm -rf {} +

snapshot() {
  (cd "$QUALIFICATION_REPOSITORY" && find . -printf '%p %T@ %s\n' | sort)
}
BEFORE="$(snapshot)"

# 2. MINOS: tools, designation, project, offline index under the required sandbox.
export MINOS_HOME="$WORK/minos-home"
export MINOS_SCIP_JAVA_MAVEN_REPOSITORY="$QUALIFICATION_REPOSITORY"
mkdir -p "$MINOS_HOME"
minos() {
  java -cp "$JAR" com.minos.cli.MinosLauncher "$@"
}
cp -R "$REPOSITORY_ROOT/fixtures/java/java-simple" "$WORK/java-simple"

# On Linux, `minos tools install scip-java` resolves its classpath with the Coursier of the PATH. The launcher is
# the one the release image pins: same commit and SHA-256, read from docker/Dockerfile.mcp.release (single source).
DOCKERFILE="$REPOSITORY_ROOT/docker/Dockerfile.mcp.release"
COURSIER_COMMIT="$(sed -n 's/^ARG COURSIER_LAUNCHERS_COMMIT=//p' "$DOCKERFILE" | tr -d '\r')"
COURSIER_SHA256="$(sed -n 's/^ARG COURSIER_LINUX_SHA256=//p' "$DOCKERFILE" | tr -d '\r')"
if [[ -z "$COURSIER_COMMIT" || -z "$COURSIER_SHA256" ]]; then
  echo "the pinned Coursier launcher is not declared in docker/Dockerfile.mcp.release" >&2
  exit 1
fi
mkdir -p "$WORK/bin"
curl --retry 5 --retry-all-errors --retry-delay 2 --max-time 60 -fsSLo "$WORK/cs.gz"   "https://raw.githubusercontent.com/coursier/launchers/${COURSIER_COMMIT}/cs-x86_64-pc-linux.gz"
printf '%s  %s
' "$COURSIER_SHA256" "$WORK/cs.gz" | sha256sum -c -
gunzip -c "$WORK/cs.gz" > "$WORK/bin/cs"
chmod 755 "$WORK/bin/cs"
export PATH="$WORK/bin:$PATH"

minos tools install scip-java
# Diagnostics for the log only: scip-java is untrusted code, so `minos index` itself is refused when the Linux
# sandbox is not qualified, and `set -e` then fails this job.
minos doctor || true
minos project add "$WORK/java-simple" --name java-simple
if ! minos index java-simple --format json; then
  echo "the offline index under the Linux sandbox failed; see the doctor output and the run logs above" >&2
  find "$MINOS_HOME/runs" -name '*.log' -size +0 -exec tail -n 40 {} + >&2 || true
  exit 1
fi

# 3. Proof: an artifact, and an unchanged designated repository.
ARTIFACTS="$(find "$MINOS_HOME" -name '*.scip' -size +0 | wc -l)"
if [[ "$ARTIFACTS" -lt 1 ]]; then
  echo "no SCIP artifact was produced" >&2
  exit 1
fi
AFTER="$(snapshot)"
if [[ "$BEFORE" != "$AFTER" ]]; then
  echo "the designated Maven repository was modified by the sandboxed build" >&2
  diff <(echo "$BEFORE") <(echo "$AFTER") | head -20 >&2 || true
  exit 1
fi
echo "offline Maven index under the Linux sandbox: artifact produced, designated repository unchanged"
