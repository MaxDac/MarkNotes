#!/usr/bin/env bash
set -euo pipefail

checker="$(dirname "$0")/check-apk-permissions.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Stand in for aapt2 so failure paths do not need a rebuilt APK for every permission change.
cat > "$tmp/aapt2" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
test "$1" = dump
test "$2" = permissions
cat "$3"
if [[ "$3" == *partial-error ]]; then exit 1; fi
EOF
chmod +x "$tmp/aapt2"
export AAPT2="$tmp/aapt2"

cat > "$tmp/expected" <<'EOF'
package: io.github.maxdac.marknotes
uses-permission: name='io.github.maxdac.marknotes.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
uses-permission: name='android.permission.USE_BIOMETRIC'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.NFC'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
EOF

expect_failure() {
  if bash "$checker" "$@" > "$tmp/output" 2>&1; then
    echo "Expected permission check to fail for: $*" >&2
    exit 1
  fi
}

bash "$checker" "$tmp/expected" "$tmp/expected"
sed 's/$/\r/' "$tmp/expected" > "$tmp/crlf"
bash "$checker" "$tmp/crlf"

for permission in READ_PHONE_STATE READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE CAMERA; do
  cp "$tmp/expected" "$tmp/extra"
  printf "uses-permission: name='android.permission.%s'\n" "$permission" >> "$tmp/extra"
  expect_failure "$tmp/extra"
  grep -q 'permissions changed' "$tmp/output"
done

cp "$tmp/expected" "$tmp/sdk23"
echo "uses-permission-sdk-23: name='android.permission.READ_PHONE_STATE'" >> "$tmp/sdk23"
expect_failure "$tmp/sdk23"
expect_failure "$tmp/expected" "$tmp/extra"
sed '/USE_BIOMETRIC/d' "$tmp/expected" > "$tmp/missing"
expect_failure "$tmp/missing"
sed 's/marknotes.DYNAMIC/marknotes.libre.DYNAMIC/' "$tmp/expected" > "$tmp/libre"
expect_failure "$tmp/libre"
touch "$tmp/empty"
expect_failure "$tmp/empty"
expect_failure "$tmp/nonexistent"
cp "$tmp/expected" "$tmp/partial-error"
expect_failure "$tmp/partial-error"
expect_failure
AAPT2="$tmp/nonexistent" expect_failure "$tmp/expected"
echo "APK permission guard regression tests passed."
