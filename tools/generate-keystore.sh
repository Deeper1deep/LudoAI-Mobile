#!/usr/bin/env bash
# Generates the ONE permanent Ludo AI Assistant release keystore. Run it once, ever.
# Output goes to $LUDOAI_SIGNING_DIR (default ~/ludoai-signing), never inside this repository.
# Passwords are random, written only to files with mode 600, and are never printed.
set -euo pipefail
DIR="${LUDOAI_SIGNING_DIR:-$HOME/ludoai-signing}"
ALIAS="ludoai"
if [ -e "$DIR/ludoai-release.jks" ]; then
  echo "A keystore already exists at $DIR/ludoai-release.jks. Refusing to create another one." >&2
  echo "Replacing the signing key would stop every installed copy from updating." >&2
  exit 1
fi
case "$(cd "$DIR" 2>/dev/null && pwd || echo "$DIR")" in
  "$(git rev-parse --show-toplevel 2>/dev/null || echo /nonexistent)"*) echo "Signing directory must be outside the git repository." >&2; exit 1;;
esac
umask 077
mkdir -p "$DIR"
STOREPASS="$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
keytool -genkeypair -v -keystore "$DIR/ludoai-release.jks" -storetype PKCS12 \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$STOREPASS" -keypass "$STOREPASS" \
  -dname "CN=Ludo AI Assistant, O=Deeper, C=EG" >/dev/null 2>&1
base64 -w0 "$DIR/ludoai-release.jks" > "$DIR/KEYSTORE_BASE64.txt"
printf '%s' "$STOREPASS" > "$DIR/KEYSTORE_PASSWORD.txt"
printf '%s' "$STOREPASS" > "$DIR/KEY_PASSWORD.txt"
printf '%s' "$ALIAS" > "$DIR/KEY_ALIAS.txt"
chmod 600 "$DIR"/*
echo "Keystore created in $DIR (4 secret files + the .jks). BACK THIS DIRECTORY UP NOW, outside git."
keytool -list -keystore "$DIR/ludoai-release.jks" -storepass "$STOREPASS" -alias "$ALIAS" 2>/dev/null | grep -i "SHA-256\|SHA256" || true
