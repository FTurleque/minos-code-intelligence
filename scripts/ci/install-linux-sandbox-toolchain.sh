#!/usr/bin/env bash
# Installs and authorizes the Linux worker sandbox toolchain (bubblewrap, util-linux, AppArmor and
# the userns profile) that MINOS-01 containment qualification depends on.
#
# WHY THIS IS NOT A PLAIN `apt-get install`
# -----------------------------------------
# GitHub-hosted runners resolve packages through a mirrorlist (/etc/apt/apt-mirrors.txt) that points
# at azure.archive.ubuntu.com. When that mirror is degraded it does not refuse the connection -- it
# accepts and then stalls, so apt produces `Ign` lines and keeps waiting instead of returning a
# non-zero exit. Observed on 19 August 2026: the step hung past 26 minutes with no output on an
# unbounded run, and different Linux jobs of the *same commit* diverged (some cleared it, some did
# not), which is the signature of a per-runner mirror failure rather than a repository defect.
#
# Two consequences drive the design below:
#   * a shell `if ! apt-get ...` fallback can never fire, because apt does not return at all -- the
#     attempt has to be bounded externally with `timeout`;
#   * a high Acquire::Retries is actively harmful here: it burns the budget re-attempting the dead
#     mirror before apt is willing to fall back to a working one.
#
# So: bound each attempt, keep retries low, and if the default mirrorlist attempt fails, pin the
# mirrorlist to archive.ubuntu.com (proven responsive in the same logs) and try once more. The fast
# Azure mirror is still preferred whenever it works; the pin is only a recovery path.
#
# This never masks a failure: if both attempts fail the script exits non-zero and the CI step fails.
set -euo pipefail

PACKAGES=(bubblewrap util-linux apparmor apparmor-profiles)
APT_OPTIONS=(-o Acquire::Retries=1 -o Acquire::http::Timeout=15 -o Acquire::https::Timeout=15)
ATTEMPT_TIMEOUT_SECONDS="${MINOS_APT_ATTEMPT_TIMEOUT_SECONDS:-180}"
MIRRORLIST=/etc/apt/apt-mirrors.txt
FALLBACK_MIRROR='https://archive.ubuntu.com/ubuntu/'

attempt_install() {
  local label="$1"
  echo "MINOS sandbox toolchain: apt attempt via $label (bounded to ${ATTEMPT_TIMEOUT_SECONDS}s per command)"
  timeout "$ATTEMPT_TIMEOUT_SECONDS" sudo apt-get "${APT_OPTIONS[@]}" update \
    && timeout "$ATTEMPT_TIMEOUT_SECONDS" sudo apt-get "${APT_OPTIONS[@]}" install \
         --yes --no-install-recommends "${PACKAGES[@]}"
}

if ! attempt_install "the runner's default mirrorlist"; then
  echo "MINOS sandbox toolchain: default mirrors did not complete in time; pinning $FALLBACK_MIRROR" >&2
  if [ -f "$MIRRORLIST" ]; then
    printf '%s\n' "$FALLBACK_MIRROR" | sudo tee "$MIRRORLIST" >/dev/null
  else
    echo "MINOS sandbox toolchain: $MIRRORLIST is absent; retrying without repinning" >&2
  fi
  attempt_install "the pinned $FALLBACK_MIRROR mirror"
fi

# Fail closed on the tools the sandbox qualification actually needs.
command -v bwrap
command -v prlimit
command -v unshare
command -v setpriv

profile='/usr/share/apparmor/extra-profiles/bwrap-userns-restrict'
test -f "$profile"
sudo cp "$profile" /etc/apparmor.d/minos-bwrap-userns-restrict
sudo apparmor_parser -r /etc/apparmor.d/minos-bwrap-userns-restrict
echo 'MINOS sandbox toolchain: installed and authorized'
