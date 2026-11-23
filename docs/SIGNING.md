# Permanent signing key

The APK signing key is created **once** and never replaced. Every update must be signed with it, otherwise Android refuses to install the update over the installed app.

Never change: the keystore, the alias (`ludoai`), the applicationId (`com.ludoai.assistant`).

## 1. Generate (once)
On a machine with Java (Termux: `pkg install openjdk-17`):

    bash tools/generate-keystore.sh

Creates `~/ludoai-signing/` (outside the repo) with `ludoai-release.jks` and four files, one per secret. Passwords are random and are never printed. The script refuses to run again if a keystore exists.

**Back up `~/ludoai-signing/` right away** (encrypted cloud storage or a second device). If this key is lost, installed copies can never be updated.

## 2. GitHub secrets
Repository LudoAI-Mobile > Settings > Secrets and variables > Actions > New repository secret:

| Name | Value (file in ~/ludoai-signing) |
|---|---|
| KEYSTORE_BASE64 | KEYSTORE_BASE64.txt |
| KEYSTORE_PASSWORD | KEYSTORE_PASSWORD.txt |
| KEY_ALIAS | KEY_ALIAS.txt (`ludoai`) |
| KEY_PASSWORD | KEY_PASSWORD.txt |

Plus `UPDATES_REPO_TOKEN`: a fine-grained personal access token limited to the repository LudoAI-Updates with *Contents: Read and write* (nothing else).

With the GitHub CLI: `bash tools/set-github-secrets.sh Deeper1deep/LudoAI-Mobile`.

## 3. Safety rules built into the build
- The release workflow stops with a clear error if any secret is missing. It never generates a key.
- Gradle refuses to build a release without the four variables (`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).
- After building, the workflow verifies the signature, checks the applicationId, and compares the certificate SHA-256 with the previous published release. A different key aborts the release.
- The keystore is decoded to a temporary runner file, never printed, and deleted at the end.
