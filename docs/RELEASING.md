# Releasing and updating (phone only)

1. Push your changes to `main` (Termux: `git add -A && git commit -m "msg" && git push`).
2. GitHub app or website: LudoAI-Mobile > Actions > Release > Run workflow. Fill the notes (English/Arabic). Keep "Publish" on.
3. The workflow numbers the version automatically: versionCode = latest published + 1, versionName = `MAJOR.MINOR.(code-1)` from `version.properties`.
4. It builds, tests, signs, verifies and publishes the APK, `latest.json`, `versions.json` and `CHANGELOG.md` to LudoAI-Updates.
5. Installed apps see the update on next launch (or Settings > Check for updates), download it, verify the SHA-256 and open the Android installer. User data stays because it is an in-place update with the same key and applicationId.

To change the major/minor version edit `version.properties`. Never lower VERSION_CODE.

Local build in Termux (optional): `./gradlew testDebugUnitTest assembleDebug`.

## Release pipeline guarantees (workflow `release.yml`)
* Signing certificate must equal the pinned `release/EXPECTED_CERT_SHA256` **and** the one in the published `latest.json`; otherwise the run aborts. No key is ever generated.
* The APK must verify (v2/v3), have exactly one signer, the right package and versionCode, not be debuggable, and be zip-aligned.
* `versionCode` = max(latest.json, versions.json, tags, version.properties) + 1; unreadable remote metadata aborts instead of guessing.
* `latest.json` is generated only from the verified APK (sha256, size, cert), then re-validated against it.
* Publishing order: **draft release -> re-download asset and compare hash -> publish -> anonymous download and compare -> push latest.json/versions.json/CHANGELOG last.** If anything fails before the metadata push the unreferenced release and tag are deleted. Existing releases are never overwritten.
* Release notes travel as environment variables / files, never interpolated into shell.
* Inputs: `mandatory` (force this update) and `min_version_code` (older installs are forced to update).
* One release at a time (`concurrency`). A release can only be published when `KEYSTORE_*` and `UPDATES_REPO_TOKEN` secrets exist.
