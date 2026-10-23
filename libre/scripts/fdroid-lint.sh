#!/usr/bin/env bash
# Checks the F-Droid recipe (.fdroid.yml) the way an fdroiddata merge request is checked: `fdroid lint`, plus
# `fdroid rewritemeta` must leave it unchanged, so the file can be copied to fdroiddata as
# metadata/io.github.maxdac.marknotes.libre.yml as is. Needs fdroidserver (`pip install fdroidserver`) and git.
#
#   scripts/fdroid-lint.sh
set -euo pipefail

appid=io.github.maxdac.marknotes.libre
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# A minimal fdroiddata checkout: lint validates categories and anti-features against its config/.
git clone --quiet --depth 1 --filter=blob:none --sparse https://gitlab.com/fdroid/fdroiddata.git "$work/fdroiddata"
cd "$work/fdroiddata"
git sparse-checkout set --no-cone /config /srclibs/rustup.yml
mkdir -p metadata
cp "$root/.fdroid.yml" "metadata/$appid.yml"
cp "$root"/fdroid/srclibs/*.yml srclibs/

fdroid lint "$appid"
fdroid rewritemeta "$appid"
if ! diff -u "$root/.fdroid.yml" "metadata/$appid.yml"; then
  echo "::error file=.fdroid.yml::not in fdroid rewritemeta format; apply the diff above" >&2
  exit 1
fi

# The recipe must build the current app version against the marknotes-sync version the app is pinned to.
recipe="$(tr -d '\r' < "$root/.fdroid.yml")"
read -r name code < <("$root/scripts/app-version.sh")
sync="$(tr -d '\r' < "$root/gradle/libs.versions.toml" | sed -n 's/^marknotesSync = "\(.*\)"$/\1/p')"
fail=0
check() {
  if ! grep -qxF -- "$1" <<< "$recipe"; then
    echo "::error file=.fdroid.yml::expected the line '$1' ($2)" >&2
    fail=1
  fi
}
check "      - marknotes-sync@v$sync" "marknotesSync in gradle/libs.versions.toml"
check "  - versionName: $name" "the last build must be versionName in app/build.gradle.kts"
check "    versionCode: $code" "the last build must be versionCode in app/build.gradle.kts"
check "    commit: v$name" "the last build must build the release tag"
check "CurrentVersion: $name" "versionName in app/build.gradle.kts"
check "CurrentVersionCode: $code" "versionCode in app/build.gradle.kts"
[ "$fail" = 0 ] || exit 1
echo "F-Droid recipe OK"
