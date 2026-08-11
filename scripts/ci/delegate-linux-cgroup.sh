#!/usr/bin/env bash
# Delegates a cgroup v2 subtree to MINOS so the Linux worker sandbox can build a real aggregate
# resource job boundary (memory.max, memory.swap.max, pids.max, cpu.max, cgroup.kill).
#
# Without such a delegation MINOS is fail-closed: the Linux backend declares
# BLOCKED_NO_AGGREGATE_RESOURCE_JOB_BOUNDARY and untrusted remote execution is refused. CI must
# therefore provision the delegation explicitly, exactly like it provisions bubblewrap and the
# AppArmor userns profile, so the qualified path is really exercised instead of skipped.
#
# Usage: sudo-capable host, then `source scripts/ci/delegate-linux-cgroup.sh` or run it directly.
# It exports MINOS_SANDBOX_CGROUP_ROOT and, inside GitHub Actions, appends it to $GITHUB_ENV.
set -euo pipefail

ROOT="${MINOS_SANDBOX_CGROUP_ROOT:-/sys/fs/cgroup/minos.slice}"
CONTROLLERS='+memory +pids +cpu'

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
