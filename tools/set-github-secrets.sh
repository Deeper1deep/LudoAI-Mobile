#!/usr/bin/env bash
# Uploads the 4 signing secrets from $LUDOAI_SIGNING_DIR to the GitHub repo using the GitHub CLI (gh).
# Values are streamed from files; nothing is printed.
set -euo pipefail
DIR="${LUDOAI_SIGNING_DIR:-$HOME/ludoai-signing}"
REPO="${1:?usage: set-github-secrets.sh OWNER/LudoAI-Mobile}"
for n in KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
  gh secret set "$n" --repo "$REPO" < "$DIR/$n.txt"
done
echo "Secrets set for $REPO: KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD"
