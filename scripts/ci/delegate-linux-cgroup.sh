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
# Usage: sudo-capable host, then `source scripts/ci/delegate-linux-cgroup.sh` or run it directly.
# It exports MINOS_SANDBOX_CGROUP_ROOT and, inside GitHub Actions, appends it to $GITHUB_ENV.
set -euo pipefail

ROOT="${MINOS_SANDBOX_CGROUP_ROOT:-/sys/fs/cgroup/minos.slice}"
CONTROLLERS='+memory +pids +cpu'

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
sudo sh -c "echo '$CONTROLLERS' > '$ROOT/cgroup.subtree_control'"
sudo chown -R "$(id -u):$(id -g)" "$ROOT"
# cgroup v2 delegation containment: migrating a process also requires write access to the
# cgroup.procs of the common ancestor of its source and destination cgroups.
sudo chown "$(id -u):$(id -g)" /sys/fs/cgroup/cgroup.procs

for controller in memory pids cpu; do
  grep -qw "$controller" "$ROOT/cgroup.controllers"
  grep -qw "$controller" "$ROOT/cgroup.subtree_control"
done

export MINOS_SANDBOX_CGROUP_ROOT="$ROOT"
if [ -n "${GITHUB_ENV:-}" ]; then
  echo "MINOS_SANDBOX_CGROUP_ROOT=$ROOT" >> "$GITHUB_ENV"
fi
echo "MINOS delegated cgroup root: $ROOT"
