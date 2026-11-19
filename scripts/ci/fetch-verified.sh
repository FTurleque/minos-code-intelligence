#!/usr/bin/env bash
# Downloads a pinned file from the first mirror that serves it, then publishes it only once its SHA-256 matches.
#
#   bash scripts/ci/fetch-verified.sh <sha256> <output> <url> [<url>...]
#
# A mirror that cannot be reached, or answers an HTTP error after the retries, hands over to the next URL: a public
# repository may answer 404 or 429 for a few seconds. A file that is served but does not match the pinned digest
# fails at once, without trying another mirror: a mismatch is an integrity failure, never an availability one.
# The output is written atomically and never replaces an existing file.
# FETCH_VERIFIED_RETRIES and FETCH_VERIFIED_RETRY_DELAY (seconds) tune the retries of each mirror.
set -euo pipefail

usage() {
  echo "usage: fetch-verified.sh <sha256> <output> <url> [<url>...]" >&2
  exit 2
}

[[ $# -ge 3 ]] || usage
expected="$1"
output="$2"
shift 2
[[ "$expected" =~ ^[0-9a-f]{64}$ ]] || usage
if [[ -e "$output" ]]; then
  echo "fetch-verified: $(basename "$output") already exists; it is not replaced" >&2
  exit 1
fi

retries="${FETCH_VERIFIED_RETRIES:-5}"
delay="${FETCH_VERIFIED_RETRY_DELAY:-5}"
partial="$(mktemp "$(dirname "$output")/.fetch-verified.XXXXXX")"
trap 'rm -f "$partial"' EXIT

mirror=0
for url in "$@"; do
  mirror=$((mirror + 1))
  if ! curl --retry "$retries" --retry-all-errors --retry-delay "$delay" --max-time 120 -fsSLo "$partial" "$url"; then
    echo "fetch-verified: mirror $mirror of $# did not serve $(basename "$output"); trying the next mirror" >&2
    continue
  fi
  # Read from stdin: sha256sum prefixes its line with a backslash for a file name that contains one.
  actual="$(sha256sum < "$partial" | cut -d' ' -f1)"
  if [[ "$actual" != "$expected" ]]; then
    echo "fetch-verified: mirror $mirror served $(basename "$output") with a SHA-256 that is not the pinned one; refused" >&2
    exit 1
  fi
  mv -n "$partial" "$output"
  if [[ -e "$partial" ]]; then
    echo "fetch-verified: $(basename "$output") appeared during the download; it is not replaced" >&2
    exit 1
  fi
  exit 0
done
echo "fetch-verified: no mirror served $(basename "$output") ($# tried)" >&2
exit 1
