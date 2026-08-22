#!/usr/bin/env bash
# Delegates a cgroup v2 subtree to MINOS so the Linux worker sandbox can build a real aggregate
# resource job boundary (memory.max, memory.swap.max, pids.max, cpu.max, cgroup.kill).
#
# Without such a delegation MINOS is fail-closed: the Linux backend declares
# BLOCKED_NO_AGGREGATE_RESOURCE_JOB_BOUNDARY and untrusted remote execution is refused. CI must
# therefore provision the delegation explicitly, exactly like it provisions bubblewrap and the
# AppArmor userns profile, so the qualified path is really exercised instead of skipped.
#
# GitHub-hosted CI is also fail-closed on its Linux sandbox toolchain. apt repositories are mutable,
# so accepting whatever package versions happen to be current would make the same MINOS commit run
# against an unreviewed containment environment. The versions below are intentionally qualified
# inputs: when GitHub/Ubuntu changes them, CI fails until the new toolchain is reviewed and this file
# is updated deliberately.
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
# `--attach-pid` places the shell that will run the MINOS test/build workload directly inside
# `$ROOT/minos-controller`. MINOS then finds itself already in the controller cgroup, skips the
# migration entirely (see LinuxCgroupJob.relocateSelf), and only ever writes inside the subtree it
# actually owns. This mirrors the shape systemd `Delegate=yes` produces natively.
#
# Usage: sudo-capable host.
#   bash scripts/ci/delegate-linux-cgroup.sh                  # provision only
#   bash scripts/ci/delegate-linux-cgroup.sh --attach-pid $$   # provision + place THIS shell inside
# It exports MINOS_SANDBOX_CGROUP_ROOT and, inside GitHub Actions, appends it to $GITHUB_ENV.
# Because each GitHub Actions `run:` block is a separate shell, the step that actually executes the
# MINOS workload must itself pass --attach-pid $$; provisioning alone does not place a later step's
# shell inside the boundary.
set -euo pipefail

ROOT="${MINOS_SANDBOX_CGROUP_ROOT:-/sys/fs/cgroup/minos.slice}"
CONTROLLERS='+memory +pids +cpu'
ATTACH_PID=''

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
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

# Strip trailing slashes so the attach verification below can compare the resolved cgroup path
# reported by /proc/PID/cgroup against this one literally.
while [ "${ROOT%/}" != "$ROOT" ]; do ROOT="${ROOT%/}"; done
CONTROLLER="$ROOT/minos-controller"

case "$ROOT" in
  /sys/fs/cgroup/?*) ;;
  *)
    echo "MINOS_SANDBOX_CGROUP_ROOT must be a proper subdirectory of /sys/fs/cgroup, got: $ROOT" >&2
    exit 2
    ;;
esac

verify_github_actions_sandbox_toolchain() {
  if [ "${GITHUB_ACTIONS:-false}" != "true" ]; then
    return 0
  fi

  if [ ! -r /etc/os-release ]; then
    echo 'MINOS CI sandbox qualification requires readable /etc/os-release.' >&2
    return 1
  fi
  # shellcheck disable=SC1091
  . /etc/os-release
  if [ "${ID:-}" != "ubuntu" ] || [ "${VERSION_ID:-}" != "24.04" ]; then
    echo "MINOS CI sandbox runner drift: expected Ubuntu 24.04, got ${ID:-unknown} ${VERSION_ID:-unknown}." >&2
    return 1
  fi

  local expected package version actual
  for expected in \
    'bubblewrap=0.9.0-1ubuntu0.1' \
    'util-linux=2.39.3-9ubuntu6.5' \
    'apparmor=4.0.1really4.0.1-0ubuntu0.24.04.7' \
    'apparmor-profiles=4.0.1really4.0.1-0ubuntu0.24.04.7'; do
    package="${expected%%=*}"
    version="${expected#*=}"
    actual="$(dpkg-query -W -f='${Version}' "$package" 2>/dev/null || true)"
    if [ "$actual" != "$version" ]; then
      echo "MINOS CI sandbox package drift: expected $package=$version, got ${actual:-missing}." >&2
      return 1
    fi
  done
}

verify_github_actions_sandbox_toolchain

test -f /sys/fs/cgroup/cgroup.controllers

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
sudo chown -R "$(id -u):$(id -g)" "$ROOT"

for controller in memory pids cpu; do
  grep -qw "$controller" "$ROOT/cgroup.controllers"
  grep -qw "$controller" "$ROOT/cgroup.subtree_control"
done

if [ -n "$ATTACH_PID" ]; then
  case "$ATTACH_PID" in
    ''|*[!0-9]*)
      echo "--attach-pid must be a numeric PID, got: $ATTACH_PID" >&2
      exit 2
      ;;
  esac
  test -d "/proc/$ATTACH_PID"
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

export MINOS_SANDBOX_CGROUP_ROOT="$ROOT"
if [ -n "${GITHUB_ENV:-}" ]; then
  echo "MINOS_SANDBOX_CGROUP_ROOT=$ROOT" >> "$GITHUB_ENV"
fi
echo "MINOS delegated cgroup root: $ROOT"
