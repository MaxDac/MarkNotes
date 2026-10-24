#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "Usage: $0 APK [APK ...]" >&2
  exit 1
fi

aapt2="${AAPT2:-}"
if [ -z "$aapt2" ]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME or ANDROID_SDK_ROOT, or AAPT2}}"
  aapt2="$(find "$sdk/build-tools" -type f \( -name aapt2 -o -name aapt2.exe \) | sort -V | tail -n 1)"
fi
if [ ! -x "$aapt2" ]; then
  echo "Cannot execute aapt2: $aapt2" >&2
  exit 1
fi

# Pro adds network-state access and MSAL's YubiKit NFC support, and uses its own application ID.
expected="$(printf '%s\n' \
  android.permission.ACCESS_NETWORK_STATE \
  android.permission.INTERNET \
  android.permission.NFC \
  android.permission.USE_BIOMETRIC \
  io.github.maxdac.marknotes.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION | LC_ALL=C sort)"

for apk in "$@"; do
  actual="$("$aapt2" dump permissions "$apk" \
    | tr -d '\r' | sed -n "s/^uses-permission[^:]*: name='\([^']*\)'.*/\1/p" | LC_ALL=C sort)"
  printf 'Permissions in %s:\n%s\n' "$apk" "$actual"
  if [ "$actual" != "$expected" ]; then
    echo "::error::The Pro APK's permissions changed. Review the merged manifest and update the allowlist only if intended." >&2
    diff <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") >&2
    exit 1
  fi
done
