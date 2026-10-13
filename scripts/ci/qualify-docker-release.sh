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
command -v java >/dev/null
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
MCP_CONTAINER=""
cleanup() {
  if [[ -n "$MCP_CONTAINER" ]]; then
    docker rm --force "$MCP_CONTAINER" >/dev/null 2>&1 || true
  fi
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
RUNTIME_USER="$(docker image inspect --format '{{.Config.User}}' "$IMAGE")"
ENTRYPOINT="$(docker image inspect --format '{{json .Config.Entrypoint}}' "$IMAGE")"
[[ "$REVISION" == "$ACTUAL_HEAD" ]]
[[ "$VERSION" == "ci" ]]
[[ "$PREPARED" == "true" ]]
[[ "$RUNTIME_USER" == "10001:10001" ]]
[[ "$ENTRYPOINT" == '["java","-cp","/opt/minos/minos.jar","com.minos.mcp.MinosMcpServer"]' ]]

# AUD-DEP-16: the image ships the notices and licence files of the JAR it embeds, readable without
# running MINOS. One "<sha256>  ./<path>" line per file of /opt/minos/licenses, compared below with
# the entries of the shaded JAR the image was built from: a missing directory, an empty notice or a
# file that differs from the JAR fails the qualification.
docker run --rm --network none --read-only --entrypoint sh "$IMAGE" -ec \
  'cd /opt/minos/licenses && find . -type f -exec sha256sum {} +' \
  > "$QUALIFICATION_ROOT/image-licenses-sha256.txt"
python3 - "${SHADED_JARS[0]}" "$QUALIFICATION_ROOT/image-licenses-sha256.txt" <<'PY'
import hashlib
import pathlib
import sys
import zipfile

jar_path = pathlib.Path(sys.argv[1])
image_hashes = {}
for line in pathlib.Path(sys.argv[2]).read_text(encoding="utf-8").splitlines():
    # "<sha256>  ./<path>" (text mode) or "<sha256> *./<path>" (binary mode).
    digest, _, name = line.partition(" ")
    name = name.lstrip(" *")
    if name:
        image_hashes[name.removeprefix("./")] = digest

expected = {}
with zipfile.ZipFile(jar_path) as jar:
    for info in jar.infolist():
        if info.is_dir():
            continue
        if info.filename in ("META-INF/NOTICE", "META-INF/LICENSE") or info.filename.startswith("META-INF/licenses/"):
            content = jar.read(info)
            if not content:
                raise SystemExit(f"empty notice or licence file in the shaded JAR: {info.filename}")
            expected[info.filename] = hashlib.sha256(content).hexdigest()

for required in ("META-INF/NOTICE", "META-INF/LICENSE"):
    if required not in expected:
        raise SystemExit(f"the shaded JAR has no {required}")
if not any(name.startswith("META-INF/licenses/") for name in expected):
    raise SystemExit("the shaded JAR keeps no dependency licence under META-INF/licenses/")

problems = []
for name, digest in sorted(expected.items()):
    if name not in image_hashes:
        problems.append(f"missing from /opt/minos/licenses of the image: {name}")
    elif image_hashes[name] != digest:
        problems.append(f"/opt/minos/licenses/{name} of the image differs from the shaded JAR")
if problems:
    raise SystemExit("\n".join(problems))
print(f"image licences: {len(expected)} notice and licence files identical to those of the shaded JAR")
PY

docker run --rm --network none --read-only --entrypoint cat "$IMAGE" \
  /opt/minos/provider-evidence/provider-inventory.json \
  > "$QUALIFICATION_ROOT/provider-inventory.json"
docker run --rm --network none --read-only --entrypoint cat "$IMAGE" \
  /opt/minos/provider-evidence/binary-sha256.txt \
  > "$QUALIFICATION_ROOT/provider-binary-sha256.txt"

# What the toolchain binaries of the built image report, one "<binary>=<raw output>" line each: the
# provider inventory must say the same (it is provenance evidence, written at build time).
docker run --rm --network none --read-only \
  --tmpfs /tmp:rw,nosuid,nodev,noexec,size=64m,mode=1777 \
  --env HOME=/tmp --env DOTNET_CLI_HOME=/tmp \
  --entrypoint sh "$IMAGE" -ec \
  'printf "dotnet=%s\n" "$(dotnet --version)"; printf "go=%s\n" "$(go version)"; printf "rustc=%s\n" "$(rustc --version)"; printf "node=%s\n" "$(node --version)"' \
  > "$QUALIFICATION_ROOT/toolchain-versions.txt"
cat "$QUALIFICATION_ROOT/toolchain-versions.txt"

python3 - "$QUALIFICATION_ROOT/provider-inventory.json" "$ACTUAL_HEAD" "$QUALIFICATION_ROOT/toolchain-versions.txt" <<'PY'
import json
import pathlib
import sys

inventory_path = pathlib.Path(sys.argv[1])
expected_head = sys.argv[2]
toolchain_path = pathlib.Path(sys.argv[3])
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

# Every toolchain version of the inventory equals what the binary of the image reports.
reported = dict(
    line.split("=", 1) for line in toolchain_path.read_text(encoding="utf-8").splitlines() if "=" in line
)


def word(binary, index, prefix=""):
    words = reported.get(binary, "").split()
    if len(words) <= index or not words[index].startswith(prefix):
        raise SystemExit(f"cannot read the version of the {binary} binary of the image: {reported.get(binary)!r}")
    return words[index][len(prefix):]


# provider id, inventory field, binary, version the binary reports, value the inventory must carry
installed = [
    ("scip-dotnet", "sdk", "dotnet", word("dotnet", 0)),              # 10.0.401
    ("scip-go", "toolchain", "go", word("go", 2, "go")),              # go version go1.27.0 linux/amd64
    ("rust-analyzer-scip", "toolchain", "rustc", word("rustc", 1)),   # rustc 1.99.0 (<commit> <date>)
    ("scip-typescript", "runtime", "node", "Node " + word("node", 0, "v")),  # v24.20.0
]
providers = {provider.get("id"): provider for provider in data.get("providers", [])}
mismatches = []
for provider_id, field, binary, version in installed:
    recorded = providers[provider_id].get(field)
    if recorded != version:
        mismatches.append(
            f"provider inventory toolchain mismatch: {provider_id}.{field} is {recorded!r} "
            f"but the {binary} binary of the image reports {version!r}"
        )
    else:
        print(f"provider inventory {provider_id}.{field}={recorded!r} matches the {binary} binary of the image")
if mismatches:
    raise SystemExit("\n".join(mismatches))
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

# Run the repository's production MCP smoke client against the exact built image. The helper
# intentionally waits for initialize(id=1) before sending notifications/initialized + tools/list;
# sending all three JSON-RPC messages up front races the MCP initialization state machine and can
# drop the post-initialize request even when the server is healthy.
MCP_CONTAINER="minos-docker-release-ci-mcp-${SHORT_HEAD}-$$"
docker run --rm --detach \
  --name "$MCP_CONTAINER" \
  --network none --read-only \
  --tmpfs /var/lib/minos:rw,nosuid,nodev,noexec,size=64m,mode=700,uid=10001,gid=10001 \
  --tmpfs /tmp:rw,nosuid,nodev,noexec,size=64m,mode=1777 \
  --tmpfs /run/minos-native:rw,nosuid,nodev,exec,size=16m,mode=1777 \
  --entrypoint sleep "$IMAGE" 600 \
  > "$QUALIFICATION_ROOT/mcp-container-id.txt"

MCP_COMPOSE_SENTINEL="$WORK_ROOT/mcp-compose-sentinel.yaml"
MCP_ENV_SENTINEL="$WORK_ROOT/mcp-env-sentinel"
: > "$MCP_COMPOSE_SENTINEL"
: > "$MCP_ENV_SENTINEL"
java docker/scripts/MinosDockerMcpSmoke.java \
  "$MCP_COMPOSE_SENTINEL" "$MCP_ENV_SENTINEL" "$MCP_CONTAINER" \
  > "$QUALIFICATION_ROOT/mcp-smoke.txt" \
  2> "$QUALIFICATION_ROOT/mcp-stderr.log"
grep -F 'MINOS Docker MCP smoke SUCCESS' "$QUALIFICATION_ROOT/mcp-smoke.txt" >/dev/null
if grep -E 'NoClassDefFoundError|Exception in thread "main"' "$QUALIFICATION_ROOT/mcp-stderr.log" >/dev/null; then
  cat "$QUALIFICATION_ROOT/mcp-stderr.log" >&2
  exit 1
fi

docker rm --force "$MCP_CONTAINER" >/dev/null
MCP_CONTAINER=""

cat > "$QUALIFICATION_ROOT/qualification.json" <<EOF
{
  "formatVersion": 1,
  "head": "$ACTUAL_HEAD",
  "image": "$IMAGE",
  "result": "PASS"
}
EOF

echo "MINOS provider-complete Docker release qualification SUCCESS: $ACTUAL_HEAD"
