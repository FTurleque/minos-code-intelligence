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
# See docs/user/remote-indexing.md for the full Linux sandbox operator prerequisites (bubblewrap,
# util-linux, and this cgroup delegation). scripts/ci/delegate-linux-cgroup.sh is the CI-specific
# sibling of this script: it additionally pins the exact GitHub Actions Ubuntu package versions so
# CI never silently qualifies against an unreviewed toolchain, which is not meaningful outside CI.
#
# Usage: run as (or via sudo to) a sudo-capable account, then run the exported
# MINOS_SANDBOX_CGROUP_ROOT before launching MINOS in the same shell/session, or persist it in the
# environment MINOS runs under.
set -euo pipefail

ROOT="${MINOS_SANDBOX_CGROUP_ROOT:-/sys/fs/cgroup/minos.slice}"
CONTROLLERS='+memory +pids +cpu'
TARGET_USER="${SUDO_UID:-$(id -u)}"
TARGET_GROUP="${SUDO_GID:-$(id -g)}"

test -f /sys/fs/cgroup/cgroup.controllers || {
  echo 'MINOS sandbox cgroup delegation requires a cgroup v2 mount at /sys/fs/cgroup.' >&2
  exit 1
}

sudo mkdir -p "$ROOT"
# The root cgroup is exempt from the "no internal processes" rule, so granting controllers to its
# children is allowed even while processes live in it. It may already be granted.
sudo sh -c "echo '$CONTROLLERS' > /sys/fs/cgroup/cgroup.subtree_control" || true
sudo sh -c "echo '$CONTROLLERS' > '$ROOT/cgroup.subtree_control'"
sudo chown -R "$TARGET_USER:$TARGET_GROUP" "$ROOT"
# cgroup v2 delegation containment: migrating a process also requires write access to the
# cgroup.procs of the common ancestor of its source and destination cgroups.
sudo chown "$TARGET_USER:$TARGET_GROUP" /sys/fs/cgroup/cgroup.procs

for controller in memory pids cpu; do
  grep -qw "$controller" "$ROOT/cgroup.controllers"
  grep -qw "$controller" "$ROOT/cgroup.subtree_control"
done

echo "MINOS delegated cgroup root: $ROOT"
echo "Export it before launching MINOS: export MINOS_SANDBOX_CGROUP_ROOT=$ROOT"
