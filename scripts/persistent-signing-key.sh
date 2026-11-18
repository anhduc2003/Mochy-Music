#!/usr/bin/env bash
# One signing key for every build, so a new APK installs *over* the one on the phone and keeps its
# library. Without it each build is signed with a different key and Android refuses the update.
#
# Run once:
#   ./scripts/persistent-signing-key.sh          # writes app/keystore/persistent-debug.keystore
#   ./scripts/persistent-signing-key.sh --print  # also prints the base64 to paste into GitHub secrets
#
# Then add the printed value as the repository secret MOCHY_KEYSTORE_B64 (Settings → Secrets and
# variables → Actions). The CI workflows decode it into the same file before building, so every
# release, PR build and nightly shares one signature.
#
# The file is a signing key: it is in .gitignore and must never be committed. Keep a copy — losing it
# means the next build cannot install over the one already on people's phones.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target="$repo_root/app/keystore/persistent-debug.keystore"

if [ -f "$target" ]; then
  echo "A key is already here: $target"
else
  mkdir -p "$(dirname "$target")"
  keytool -genkeypair \
    -keystore "$target" \
    -storepass android \
    -alias androiddebugkey \
    -keypass android \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Mochy Music,O=Mochy,C=VN"
  echo "Wrote $target"
fi

echo
echo "Fingerprint of the key every build will carry:"
keytool -list -v -keystore "$target" -storepass android -alias androiddebugkey 2>/dev/null |
  grep -E "SHA256:|Valid from" | sed 's/^[[:space:]]*/  /'

if [ "${1:-}" = "--print" ]; then
  echo
  echo "Add this as the repository secret MOCHY_KEYSTORE_B64:"
  echo
  base64 -w0 "$target" 2>/dev/null || base64 "$target"
  echo
fi

cat <<'EOF'

Next: add MOCHY_KEYSTORE_B64 (above) as a repository secret. Until then CI keeps generating a
one-off key per build, and an APK from one run cannot replace an APK from another.
EOF
