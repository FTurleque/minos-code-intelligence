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
# The delegation itself -- validation of MINOS_SANDBOX_CGROUP_ROOT, controllers, contained chown and
# the privileged `--attach-pid` placement -- is the operator script
# scripts/deploy/provision-linux-sandbox-cgroup.sh, so CI qualifies exactly what operators run. See
# its DELEGATION CONTAINMENT and DELEGATED ROOT sections. This wrapper only adds the package check
# and, inside GitHub Actions, records the delegated root in $GITHUB_ENV.
#
# Usage: sudo-capable host.
#   bash scripts/ci/delegate-linux-cgroup.sh                  # provision only
#   bash scripts/ci/delegate-linux-cgroup.sh --attach-pid $$   # provision + place THIS shell inside
# Because each GitHub Actions `run:` block is a separate shell, the step that actually executes the
# MINOS workload must itself pass --attach-pid $$; provisioning alone does not place a later step's
# shell inside the boundary.
set -euo pipefail

REPOSITORY="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROVISION="$REPOSITORY/scripts/deploy/provision-linux-sandbox-cgroup.sh"

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
    'bubblewrap=0.9.0-1ubuntu0.3' \
    'util-linux=2.39.3-9ubuntu6.6' \
    'apparmor=4.0.1really4.0.1-0ubuntu0.24.04.9' \
    'apparmor-profiles=4.0.1really4.0.1-0ubuntu0.24.04.9'; do
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

if [ -n "${GITHUB_ENV:-}" ]; then
  "$BASH" "$PROVISION" --env-file "$GITHUB_ENV" "$@"
else
  "$BASH" "$PROVISION" "$@"
fi
