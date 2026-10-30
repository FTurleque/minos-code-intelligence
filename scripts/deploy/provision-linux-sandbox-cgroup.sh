#!/usr/bin/env bash
# Delegates a cgroup v2 subtree to MINOS so the Linux worker sandbox can build a real aggregate
# resource job boundary (memory.max, memory.swap.max, pids.max, cpu.max, cgroup.kill).
#
# Without such a delegation MINOS is fail-closed: the Linux backend declares
# BLOCKED_NO_AGGREGATE_RESOURCE_JOB_BOUNDARY and untrusted remote execution is refused. This is the
# manual alternative to running MINOS under a systemd unit with `Delegate=yes` (the recommended
# production setup, which needs no extra provisioning); use this script when MINOS is not run under
# such a unit, e.g. an interactive shell or a non-systemd supervisor.
#
# DELEGATION CONTAINMENT
# ----------------------
# cgroup v2 lets an unprivileged delegatee migrate a process only when it can write BOTH the
# destination `cgroup.procs` AND the `cgroup.procs` of the common ancestor of the source and
# destination cgroups. A MINOS process started outside the delegated subtree therefore has the root
# cgroup as that common ancestor -- and granting the MINOS account durable write access to
# /sys/fs/cgroup/cgroup.procs would let it migrate processes anywhere in the hierarchy, including
# *out* of its own delegated boundary. That is a delegation escape, so this script never grants it.
#
# Instead the one migration MINOS needs is performed here, while this script is still privileged:
# `--attach-pid` places the shell/supervisor that will launch MINOS directly inside
# `$ROOT/minos-controller`. MINOS then finds itself already in the controller cgroup, skips the
# migration entirely (see LinuxCgroupJob.relocateSelf), and only ever writes inside the subtree it
# actually owns. This mirrors the shape systemd `Delegate=yes` produces natively.
#
# Usage:
#   # one-time provisioning of the delegated subtree
#   scripts/deploy/provision-linux-sandbox-cgroup.sh
#
#   # per shell/session: provision (idempotent) and place THIS shell in the delegated subtree,
#   # so MINOS launched from it inherits the boundary
#   scripts/deploy/provision-linux-sandbox-cgroup.sh --attach-pid $$
#   export MINOS_SANDBOX_CGROUP_ROOT=/sys/fs/cgroup/minos.slice
#
#   # optionally append MINOS_SANDBOX_CGROUP_ROOT=<root> to an environment file once provisioned
#   # (a systemd EnvironmentFile, $GITHUB_ENV in CI)
#   scripts/deploy/provision-linux-sandbox-cgroup.sh --env-file FILE
#
# DELEGATED ROOT
# --------------
# MINOS_SANDBOX_CGROUP_ROOT (default /sys/fs/cgroup/minos.slice) is chowned recursively to the
# MINOS account and interpolated into privileged commands. It must therefore be a canonical path
# under /sys/fs/cgroup -- no empty, `.` or `..` component, only letters, digits, `.`, `_`, `@` and
# `-` -- whose last component starts with `minos`, so that no existing foreign cgroup (user.slice,
# a system service) and no path outside the cgroup hierarchy can ever be handed over. Anything else
# is refused before the first privileged command.
#
# Privileged operations are performed through `sudo`, so run this as a sudo-capable account.
# See docs/user/remote-indexing.md for the full Linux sandbox operator prerequisites (bubblewrap,
# util-linux, userns/LSM policy, and this cgroup delegation). scripts/ci/delegate-linux-cgroup.sh is
# the CI wrapper of this script: it first checks the exact GitHub Actions Ubuntu package versions so
# CI never silently qualifies against an unreviewed toolchain, which is not meaningful outside CI.
set -euo pipefail

CONTROLLERS='+memory +pids +cpu'
TARGET_USER="${SUDO_UID:-$(id -u)}"
TARGET_GROUP="${SUDO_GID:-$(id -g)}"
ATTACH_PID=''
ENV_FILE=''

# Prints the canonical form of a delegated cgroup root (trailing slashes removed), or explains on
# stderr why it is refused and returns 2. See DELEGATED ROOT above.
require_minos_cgroup_root() {
  local LC_ALL=C
  local root="$1"
  while [ "${root%/}" != "$root" ]; do root="${root%/}"; done
  local relative="${root#/sys/fs/cgroup/}"
  local refused=''
  case "$root" in
    /sys/fs/cgroup/?*) ;;
    *) refused='it is not a subdirectory of /sys/fs/cgroup' ;;
  esac
  if [ -z "$refused" ]; then
    case "$relative" in
      *[!A-Za-z0-9._@/-]*) refused='it contains a character other than letters, digits, ".", "_", "@", "-" and "/"' ;;
    esac
  fi
  if [ -z "$refused" ]; then
    case "/$relative/" in
      *//*|*/./*|*/../*) refused='it has an empty, "." or ".." component' ;;
    esac
  fi
  if [ -z "$refused" ]; then
    case "${relative##*/}" in
      minos*) ;;
      *) refused='its last component does not start with "minos"' ;;
    esac
  fi
  if [ -n "$refused" ]; then
    echo "MINOS_SANDBOX_CGROUP_ROOT must be a canonical cgroup dedicated to MINOS, but $refused: $1" >&2
    return 2
  fi
  printf '%s\n' "$root"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --attach-pid)
      ATTACH_PID="${2:-}"
      if [ -z "$ATTACH_PID" ]; then
        echo '--attach-pid requires a PID argument.' >&2
        exit 2
      fi
      shift 2
      ;;
    --env-file)
      ENV_FILE="${2:-}"
      if [ -z "$ENV_FILE" ]; then
        echo '--env-file requires a file argument.' >&2
        exit 2
      fi
      shift 2
      ;;
    --help|-h)
      sed -n '2,52p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

# Everything is validated before the first privileged command.
if [ -n "$ATTACH_PID" ]; then
  case "$ATTACH_PID" in
    *[!0-9]*)
      echo "--attach-pid must be a numeric PID, got: $ATTACH_PID" >&2
      exit 2
      ;;
  esac
fi
# Canonical, so the attach verification below can compare the resolved cgroup path reported by
# /proc/PID/cgroup against it literally.
ROOT="$(require_minos_cgroup_root "${MINOS_SANDBOX_CGROUP_ROOT:-/sys/fs/cgroup/minos.slice}")" || exit 2
CONTROLLER="$ROOT/minos-controller"

test -f /sys/fs/cgroup/cgroup.controllers || {
  echo 'MINOS sandbox cgroup delegation requires a cgroup v2 mount at /sys/fs/cgroup.' >&2
  exit 1
}

sudo mkdir -p "$ROOT"
# The root cgroup is exempt from the "no internal processes" rule, so granting controllers to its
# children is allowed even while processes live in it. It may already be granted.
sudo sh -c "echo '$CONTROLLERS' > /sys/fs/cgroup/cgroup.subtree_control" || true
# $ROOT must still be process-free here: cgroup v2 refuses subtree_control on a non-root cgroup that
# holds processes. Everything MINOS-related lives in the $CONTROLLER child created below, so $ROOT
# stays empty for the lifetime of the delegation.
sudo sh -c "echo '$CONTROLLERS' > '$ROOT/cgroup.subtree_control'"
sudo mkdir -p "$CONTROLLER"
# Delegate the subtree -- and only the subtree. /sys/fs/cgroup/cgroup.procs is deliberately NOT
# chowned: see DELEGATION CONTAINMENT above.
sudo chown -R "$TARGET_USER:$TARGET_GROUP" "$ROOT"

for controller in memory pids cpu; do
  grep -qw "$controller" "$ROOT/cgroup.controllers"
  grep -qw "$controller" "$ROOT/cgroup.subtree_control"
done

if [ -n "$ATTACH_PID" ]; then
  test -d "/proc/$ATTACH_PID" || {
    echo "--attach-pid target process does not exist: $ATTACH_PID" >&2
    exit 1
  }
  # Performed while still privileged, so the MINOS account never needs write access to the root
  # cgroup.procs to place itself inside the delegated boundary.
  sudo sh -c "echo '$ATTACH_PID' > '$CONTROLLER/cgroup.procs'"
  attached="$(tr -d '\0' < "/proc/$ATTACH_PID/cgroup" | sed -n 's/^0:://p' | tr -d '[:space:]')"
  expected="${CONTROLLER#/sys/fs/cgroup}"
  if [ "$attached" != "$expected" ]; then
    echo "Failed to attach PID $ATTACH_PID to $CONTROLLER (now in '$attached')." >&2
    exit 1
  fi
  echo "Attached PID $ATTACH_PID to $CONTROLLER"
fi

if [ -n "$ENV_FILE" ]; then
  echo "MINOS_SANDBOX_CGROUP_ROOT=$ROOT" >> "$ENV_FILE"
fi
echo "MINOS delegated cgroup root: $ROOT"
echo "Export it before launching MINOS: export MINOS_SANDBOX_CGROUP_ROOT=$ROOT"
if [ -z "$ATTACH_PID" ]; then
  echo "Re-run with --attach-pid \$\$ from the shell that will launch MINOS, unless MINOS runs"
  echo "under a systemd unit with Delegate=yes (which places it in a delegated cgroup already)."
fi
