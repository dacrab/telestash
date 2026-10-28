#!/usr/bin/env bash
# Verifies CHANGELOG.md against the repository's tags and version scheme.
#
# Checks:
#   - the heading format and ordering (Unreleased first, then descending semver)
#   - every released version matches an existing tag (v<version>)
#   - every tag has a changelog entry
#   - each version has a link reference definition
#   - a tag added by this push is announced at the top of a release section
#
# Usage: scripts/check-changelog.sh [--tag <tag>]
set -euo pipefail

cd "$(dirname "$0")/.."
CHANGELOG=CHANGELOG.md
tag_under_release=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag) tag_under_release="${2:-}"; shift 2 ;;
    *) printf 'Unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done

if [[ ! -f "$CHANGELOG" ]]; then
  printf '%s\n' '::error file=CHANGELOG.md::CHANGELOG.md is missing' >&2
  exit 1
fi

fail() {
  printf '::error file=%s::%s\n' "$CHANGELOG" "$1" >&2
  exit 1
}

# "## [0.3.0] - 2026-09-13" or "## [Unreleased]"
mapfile -t headings < <(grep -E '^## \[' "$CHANGELOG")

[[ ${#headings[@]} -gt 0 ]] || fail "No version headings found"

[[ "${headings[0]}" == '## [Unreleased]' ]] ||
  fail "First heading must be '## [Unreleased]', found: ${headings[0]}"

released=()
for heading in "${headings[@]:1}"; do
  if [[ "$heading" =~ ^##\ \[([0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?)\]\ -\ ([0-9]{4}-[0-9]{2}-[0-9]{2})$ ]]; then
    version="${BASH_REMATCH[1]}"
    date="${BASH_REMATCH[3]}"
    (( 10#${date//-/} > 0 )) || fail "Invalid date in: $heading"
    released+=("$version")
  else
    fail "Released heading must look like '## [1.2.3] - YYYY-MM-DD', found: $heading"
  fi
done

# Ordering: released versions must be strictly descending.
for ((i = 1; i < ${#released[@]}; i++)); do
  higher="${released[i-1]}"
  lower="${released[i]}"
  # semver-ish comparison: compare numeric parts, then prerelease presence.
  if [[ "$(printf '%s\n%s\n' "$higher" "$lower" | sort -V | tail -1)" != "$higher" ]]; then
    fail "Versions out of order: $higher should sort above $lower"
  fi
  [[ "$higher" != "$lower" ]] || fail "Duplicate version entry: $higher"
done

# Every tag needs an entry; every entry needs a tag.
mapfile -t tags < <(git tag -l 'v*' | sed 's/^v//' | sort -V)
for tag in "${tags[@]}"; do
  found=0
  for version in "${released[@]:-}"; do
    [[ "$version" == "$tag" ]] && found=1
  done
  (( found )) || fail "Tag v$tag has no changelog entry"
done
for version in "${released[@]}"; do
  found=0
  for tag in "${tags[@]:-}"; do
    [[ "$tag" == "$version" ]] && found=1
  done
  (( found )) || fail "Changelog entry $version has no matching tag v$version"
done

# Link reference definitions must exist for Unreleased and every version.
for ref in Unreleased "${released[@]}"; do
  grep -qE "^\[${ref}\]: " "$CHANGELOG" || fail "Missing link reference definition: [$ref]:"
done

# A release build must announce its own tag.
if [[ -n "$tag_under_release" ]]; then
  want="${tag_under_release#v}"
  [[ "${released[0]:-}" == "$want" ]] ||
    fail "Releasing $tag_under_release, but the top entry is '${released[0]:-none}'"
fi

printf 'CHANGELOG.md OK (%d released, %d unreleased)\n' \
  "${#released[@]}" "$([[ "${headings[0]}" == '## [Unreleased]' ]] && echo 1 || echo 0)"
