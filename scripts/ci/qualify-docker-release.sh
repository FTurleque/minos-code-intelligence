#!/usr/bin/env bash
set -euo pipefail

EXPECTED_HEAD="${1:-${MINOS_EXPECTED_HEAD:-}}"
ACTUAL_HEAD="$(git rev-parse HEAD)"
if [[ -z "$EXPECTED_HEAD" ]]; then
  EXPECTED_HEAD="$ACTUAL_HEAD"
fi
if [[ "$ACTUAL_HEAD" != "$EXPECTED_HEAD" ]]; then
  echo "Docker release exact-head mismatch: expected=$EXPECTED_HEAD actual=$ACTUAL_HEAD" >&2
  exit 1
fi
if [[ "$(uname -m)" != "x86_64" ]]; then
  echo "Docker release qualification is defined for linux/amd64 only." >&2
  exit 1
fi

command -v docker >/dev/null
command -v python3 >/dev/null
docker version --format '{{.Server.Version}}'

QUALIFICATION_ROOT="target/qualification/docker-release"
rm -rf "$QUALIFICATION_ROOT"
mkdir -p "$QUALIFICATION_ROOT"

chmod +x mvnw
./mvnw -B -ntp -DskipTests -DskipITs package

mapfile -t SHADED_JARS < <(find target -maxdepth 1 -type f -name 'minos-code-intelligence-*-all.jar' -print | sort)
if [[ "${#SHADED_JARS[@]}" -ne 1 ]]; then
  printf 'Expected exactly one shaded MINOS JAR, found %s:\n' "${#SHADED_JARS[@]}" >&2
  printf '  %s\n' "${SHADED_JARS[@]:-<none>}" >&2
  exit 1
fi

WORK_ROOT="$(mktemp -d)"
BUILD_CONTEXT="$WORK_ROOT/build"
mkdir -p "$BUILD_CONTEXT"
SHORT_HEAD="${ACTUAL_HEAD:0:12}"
IMAGE="minos-code-intelligence:docker-release-ci-$SHORT_HEAD"
cleanup() {
  docker image rm --force "$IMAGE" >/dev/null 2>&1 || true
  rm -rf "$WORK_ROOT"
}
trap cleanup EXIT

cp "${SHADED_JARS[0]}" "$BUILD_CONTEXT/minos.jar"
cp minos-provider-scip/src/main/resources/com/minos/adapter/scip/runtime/scip-typescript-package-lock.json \
  "$BUILD_CONTEXT/scip-typescript-package-lock.json"
cp minos-provider-scip/src/main/resources/com/minos/adapter/scip/runtime/scip-python-package-lock.json \
  "$BUILD_CONTEXT/scip-python-package-lock.json"

BUILD_TIMESTAMP="$(git show -s --format=%cI "$ACTUAL_HEAD")"
docker build \
  --file docker/Dockerfile.mcp.release \
  --tag "$IMAGE" \
  --build-arg 'MINOS_VERSION=ci' \
  --build-arg "MINOS_GIT_COMMIT=$ACTUAL_HEAD" \
  --build-arg "MINOS_BUILD_TIMESTAMP=$BUILD_TIMESTAMP" \
  "$BUILD_CONTEXT"

docker image inspect "$IMAGE" > "$QUALIFICATION_ROOT/image-inspect.json"
REVISION="$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$IMAGE")"
VERSION="$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.version" }}' "$IMAGE")"
PREPARED="$(docker image inspect --format '{{ index .Config.Labels "io.minos.providers.prepared" }}' "$IMAGE")"
[[ "$REVISION" == "$ACTUAL_HEAD" ]]
[[ "$VERSION" == "ci" ]]
[[ "$PREPARED" == "true" ]]

docker run --rm --network none --read-only --entrypoint cat "$IMAGE" \
  /opt/minos/provider-evidence/provider-inventory.json \
  > "$QUALIFICATION_ROOT/provider-inventory.json"
docker run --rm --network none --read-only --entrypoint cat "$IMAGE" \
  /opt/minos/provider-evidence/binary-sha256.txt \
  > "$QUALIFICATION_ROOT/provider-binary-sha256.txt"

python3 - "$QUALIFICATION_ROOT/provider-inventory.json" "$ACTUAL_HEAD" <<'PY'
import json
import pathlib
import sys

inventory_path = pathlib.Path(sys.argv[1])
expected_head = sys.argv[2]
data = json.loads(inventory_path.read_text(encoding="utf-8"))
if data.get("formatVersion") != 1:
    raise SystemExit(f"unexpected provider inventory format: {data.get('formatVersion')!r}")
if data.get("platform") != "linux/amd64":
    raise SystemExit(f"unexpected provider platform: {data.get('platform')!r}")
if data.get("minosCommit") != expected_head:
    raise SystemExit(
        f"provider inventory commit mismatch: {data.get('minosCommit')!r} != {expected_head!r}"
    )
expected = {
    "scip-java",
    "scip-typescript",
    "scip-python",
    "scip-clang",
    "scip-dotnet",
    "scip-go",
    "rust-analyzer-scip",
}
actual = {provider.get("id") for provider in data.get("providers", [])}
if actual != expected:
    raise SystemExit(f"provider inventory mismatch: actual={sorted(actual)} expected={sorted(expected)}")
PY

docker run --rm --network none --read-only --entrypoint sh "$IMAGE" -ec \
  'sha256sum -c /opt/minos/provider-evidence/binary-sha256.txt'

docker run --rm --network none --read-only --entrypoint java "$IMAGE" -version

docker run --rm --network none --read-only \
  --tmpfs /tmp:rw,nosuid,nodev,noexec,size=64m,mode=1777 \
  --tmpfs /run/minos-native:rw,nosuid,nodev,exec,size=16m,mode=1777 \
  --entrypoint java "$IMAGE" \
  -cp /opt/minos/minos.jar com.minos.cli.MinosLauncher --help \
  > "$QUALIFICATION_ROOT/cli-help.txt"
grep -F 'MINOS' "$QUALIFICATION_ROOT/cli-help.txt" >/dev/null

MCP_INPUT="$WORK_ROOT/mcp-input.jsonl"
cat > "$MCP_INPUT" <<'EOF'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"minos-docker-release-ci","version":"1"}}}
{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
EOF

timeout 30s docker run --rm -i --network none --read-only \
  --tmpfs /var/lib/minos:rw,nosuid,nodev,noexec,size=64m,mode=700 \
  --tmpfs /tmp:rw,nosuid,nodev,noexec,size=64m,mode=1777 \
  --tmpfs /run/minos-native:rw,nosuid,nodev,exec,size=16m,mode=1777 \
  "$IMAGE" \
  < "$MCP_INPUT" \
  > "$QUALIFICATION_ROOT/mcp-stdout.jsonl" \
  2> "$QUALIFICATION_ROOT/mcp-stderr.log"

grep -F '"id":1' "$QUALIFICATION_ROOT/mcp-stdout.jsonl" >/dev/null
grep -F 'minos-code-intelligence' "$QUALIFICATION_ROOT/mcp-stdout.jsonl" >/dev/null
grep -F '"id":2' "$QUALIFICATION_ROOT/mcp-stdout.jsonl" >/dev/null
grep -F 'minos_search_code' "$QUALIFICATION_ROOT/mcp-stdout.jsonl" >/dev/null
grep -F 'minos_impact' "$QUALIFICATION_ROOT/mcp-stdout.jsonl" >/dev/null
if grep -E 'NoClassDefFoundError|Exception in thread "main"' "$QUALIFICATION_ROOT/mcp-stderr.log" >/dev/null; then
  cat "$QUALIFICATION_ROOT/mcp-stderr.log" >&2
  exit 1
fi

cat > "$QUALIFICATION_ROOT/qualification.json" <<EOF
{
  "formatVersion": 1,
  "head": "$ACTUAL_HEAD",
  "image": "$IMAGE",
  "result": "PASS"
}
EOF

echo "MINOS provider-complete Docker release qualification SUCCESS: $ACTUAL_HEAD"
