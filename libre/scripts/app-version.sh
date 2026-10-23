#!/usr/bin/env bash
# Prints "<versionName> <versionCode>" from the literals in app/build.gradle.kts (which F-Droid reads) and checks
# that versionCode = X*10000 + Y*100 + Z for versionName X.Y.Z, the scheme every MarkNotes Android release uses.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
gradle="$(tr -d '\r' < "$root/app/build.gradle.kts")"
name="$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' <<< "$gradle")"
code="$(sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' <<< "$gradle")"

if [[ ! "$name" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "versionName '$name' in app/build.gradle.kts is not X.Y.Z" >&2
  exit 1
fi
major=${BASH_REMATCH[1]} minor=${BASH_REMATCH[2]} patch=${BASH_REMATCH[3]}
if (( 10#$minor > 99 || 10#$patch > 99 )); then
  echo "versionName $name: minor and patch must be <= 99" >&2
  exit 1
fi
expected=$(( 10#$major * 10000 + 10#$minor * 100 + 10#$patch ))
if [ "$code" != "$expected" ]; then
  echo "versionCode '$code' in app/build.gradle.kts should be $expected for versionName $name" >&2
  exit 1
fi
echo "$name $code"
