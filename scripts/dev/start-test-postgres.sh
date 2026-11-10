#!/usr/bin/env bash
# Starts the PostgreSQL/pgvector server the integration tests of minos-storage connect to (AUD-DEP-14, R-27).
#
# The image is the pinned default of MINOS_POSTGRES_IMAGE in docker/compose-mcp.connected.yaml, its single source.
# The container listens on 127.0.0.1 only, its password is generated at every start and reaches docker through its
# environment, never through its arguments. Same contract as start-test-postgres.ps1.
#
# Usage: start-test-postgres.sh [--port <n>] [--print-image | --github-env | --stop]
#   (no option)    start the container on 127.0.0.1:55432, wait for its healthcheck, print what mvnw needs
#   --port <n>     another local port
#   --print-image  print the pinned image and exit (the CI pulls it before logging out of Docker Hub)
#   --github-env   CI: mask the password, then write MINOS_TEST_POSTGRES_URL, _USER and _PASSWORD to $GITHUB_ENV;
#                  the image must already be present (docker run --pull=never)
#   --stop         remove the container
# Exit codes: 0 success; 1 image missing or unpinned, Docker missing, container not healthy in time; 2 usage error.
set -euo pipefail

CONTAINER="minos-test-postgres"
USER_NAME="minos"
ADMIN_DATABASE="postgres"
HEALTH_TIMEOUT_SECONDS=60
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE="$ROOT/docker/compose-mcp.connected.yaml"

port=55432
mode=start
while [ "$#" -gt 0 ]; do
  case "$1" in
    --port)
      [ "$#" -ge 2 ] || { echo "--port needs a value" >&2; exit 2; }
      port="$2"
      shift 2
      ;;
    --print-image) mode=print-image; shift ;;
    --github-env) mode=github-env; shift ;;
    --stop) mode=stop; shift ;;
    *) echo "unknown option: $1 (expected --port <n>, --print-image, --github-env or --stop)" >&2; exit 2 ;;
  esac
done
if ! [[ "$port" =~ ^[0-9]{1,5}$ ]] || [ "$port" -lt 1 ] || [ "$port" -gt 65535 ]; then
  echo "--port must be a TCP port number: '$port'" >&2
  exit 2
fi

pinned_image() {
  if [ ! -f "$COMPOSE" ]; then
    echo "docker/compose-mcp.connected.yaml is missing: the default of MINOS_POSTGRES_IMAGE cannot be read" >&2
    return 1
  fi
  local defaults count
  defaults="$(grep -o 'MINOS_POSTGRES_IMAGE:-[^}"[:space:]]*' "$COMPOSE" | sed 's/^MINOS_POSTGRES_IMAGE:-//' | sort -u || true)"
  count="$(printf '%s' "$defaults" | grep -c . || true)"
  if [ "$count" != "1" ]; then
    echo "docker/compose-mcp.connected.yaml must give MINOS_POSTGRES_IMAGE exactly one default, found $count" >&2
    return 1
  fi
  if ! printf '%s' "$defaults" | grep -Eq '^[a-z0-9][a-z0-9._/:-]*:[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}@sha256:[0-9a-f]{64}$'; then
    echo "the default of MINOS_POSTGRES_IMAGE is not <image>:<tag>@sha256:<64 hex>: $defaults" >&2
    return 1
  fi
  printf '%s\n' "$defaults"
}

if [ "$mode" = "print-image" ]; then
  pinned_image
  exit 0
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is not on PATH: the PostgreSQL test server cannot be started" >&2
  exit 1
fi

if [ "$mode" = "stop" ]; then
  docker rm -f "$CONTAINER" >/dev/null
  echo "container $CONTAINER removed" >&2
  exit 0
fi

image="$(pinned_image)"
if [ "$mode" = "github-env" ] && [ -z "${GITHUB_ENV:-}" ]; then
  echo "--github-env needs GITHUB_ENV (a GitHub Actions step)" >&2
  exit 1
fi

# 24 random bytes as hex. od reads /dev/urandom on Linux and under Git Bash alike.
password="$(od -An -tx1 -N24 /dev/urandom | tr -d ' \n')"
if [ "${#password}" -ne 48 ]; then
  echo "the password could not be generated" >&2
  exit 1
fi
if [ "$mode" = "github-env" ]; then
  # Masked before anything else can print it.
  echo "::add-mask::$password"
  pull=never
else
  pull=missing
fi

url="jdbc:postgresql://127.0.0.1:${port}/${ADMIN_DATABASE}"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
echo "starting $CONTAINER from $image on 127.0.0.1:$port" >&2
# --env POSTGRES_PASSWORD without a value: docker copies it from its own environment, so it is not an argument.
POSTGRES_PASSWORD="$password" docker run --detach --name "$CONTAINER" --pull="$pull" \
  --publish "127.0.0.1:${port}:5432" \
  --env POSTGRES_PASSWORD --env "POSTGRES_USER=$USER_NAME" --env "POSTGRES_DB=$ADMIN_DATABASE" \
  --health-cmd "pg_isready -h 127.0.0.1 -p 5432 -U $USER_NAME -d $ADMIN_DATABASE" \
  --health-interval 2s --health-timeout 5s --health-retries 30 \
  "$image" >/dev/null

# The TCP check of the healthcheck only succeeds on the final server: the initialization server of the image
# listens on its Unix socket only.
deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
status=""
while :; do
  status="$(docker inspect --format '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || true)"
  if [ "$status" = "healthy" ]; then
    break
  fi
  if [ "$status" = "unhealthy" ] || [ "$SECONDS" -ge "$deadline" ]; then
    echo "$CONTAINER is not healthy (status: ${status:-unknown}) within ${HEALTH_TIMEOUT_SECONDS} s: removed" >&2
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    exit 1
  fi
  sleep 1
done
echo "$CONTAINER is healthy" >&2

if [ "$mode" = "github-env" ]; then
  {
    echo "MINOS_TEST_POSTGRES_URL=$url"
    echo "MINOS_TEST_POSTGRES_USER=$USER_NAME"
    echo "MINOS_TEST_POSTGRES_PASSWORD=$password"
  } >> "$GITHUB_ENV"
  echo "-Dminos.postgresql.test.url=$url"
  echo "-Dminos.postgresql.test.user=$USER_NAME"
  exit 0
fi

# Local use: the password goes through the environment of mvnw, never through its arguments.
echo "-Dminos.postgresql.test.url=$url"
echo "-Dminos.postgresql.test.user=$USER_NAME"
echo "export MINOS_TEST_POSTGRES_PASSWORD=$password"
echo "then: ./mvnw -B -ntp -pl minos-storage -am verify \"-Dminos.postgresql.tests.required=true\" \"-Dminos.postgresql.test.url=$url\" \"-Dminos.postgresql.test.user=$USER_NAME\"" >&2
echo "stop: bash scripts/dev/start-test-postgres.sh --stop" >&2
