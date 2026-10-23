#!/usr/bin/env bash
# Builds marknotes-sync (https://github.com/MaxDac/marknotes-sync: the Rust sync core and its UniFFI Kotlin bindings)
# from source, for the version pinned in gradle/libs.versions.toml, so the app can be built without the prebuilt
# GitHub release artifacts:
#
#   scripts/build-sync-from-source.sh [marknotes-sync checkout]   # default: build/marknotes-sync (cloned if missing)
#   ./gradlew assembleRelease -Pmarknotes.sync.fromSource=<same checkout>
#
# This is the build path of F-Droid (.fdroid.yml) and of the release workflow. Needs Linux, rustup with the pinned
# toolchain and the aarch64-linux-android/x86_64-linux-android targets, cargo-ndk, the pinned NDK (ANDROID_NDK_HOME)
# and a JDK. The flags below keep the native libraries byte-for-byte reproducible across machines.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
version="$(tr -d '\r' < "$root/gradle/libs.versions.toml" | sed -n 's/^marknotesSync = "\(.*\)"$/\1/p')"
[ -n "$version" ] || { echo "marknotesSync is missing from gradle/libs.versions.toml" >&2; exit 1; }
dir="${1:-$root/build/marknotes-sync}"

# Pinned so rebuilds (F-Droid, the reproducible-build check) produce identical libraries.
export RUSTUP_TOOLCHAIN="${RUSTUP_TOOLCHAIN:-1.99.1}"
export MARKNOTES_NDK_VERSION="${MARKNOTES_NDK_VERSION:-27.3.13750724}"

if [ ! -f "$dir/Cargo.toml" ]; then
  git clone --quiet --depth 1 --branch "v$version" https://github.com/MaxDac/marknotes-sync "$dir"
fi
dir="$(cd "$dir" && pwd)"
cd "$dir"
grep -q "^version = \"$version\"" Cargo.toml \
  || { echo "$dir is not marknotes-sync $version (check out tag v$version)" >&2; exit 1; }

if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
  export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$MARKNOTES_NDK_VERSION"
fi
[ -d "${ANDROID_NDK_HOME:-}" ] || { echo "Android NDK $MARKNOTES_NDK_VERSION not found (set ANDROID_NDK_HOME)" >&2; exit 1; }
grep -q "Pkg.Revision = $MARKNOTES_NDK_VERSION" "$ANDROID_NDK_HOME/source.properties" \
  || echo "warning: $ANDROID_NDK_HOME is not NDK $MARKNOTES_NDK_VERSION; the build won't be reproducible" >&2

# Reproducibility: no absolute build paths or build ids in the binaries, fixed timestamps.
cargo_home="${CARGO_HOME:-$HOME/.cargo}"
export RUSTFLAGS="--remap-path-prefix=$dir=/marknotes-sync --remap-path-prefix=$cargo_home=/cargo \
-C link-arg=-Wl,--build-id=none"
SOURCE_DATE_EPOCH="$(git log -1 --format=%ct 2>/dev/null || echo 1)"
export SOURCE_DATE_EPOCH

scripts/android-libs.sh
scripts/kotlin-bindings.sh
(cd bindings/kotlin && ${GRADLE:-./gradlew} assemble --console=plain --no-daemon)

ls -l bindings/kotlin/build/libs
echo "Built marknotes-sync $version. Build the app with -Pmarknotes.sync.fromSource=$dir"
