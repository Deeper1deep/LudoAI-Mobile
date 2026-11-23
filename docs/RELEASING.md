# Releasing and updating (phone only)

1. Push your changes to `main` (Termux: `git add -A && git commit -m "msg" && git push`).
2. GitHub app or website: LudoAI-Mobile > Actions > Release > Run workflow. Fill the notes (English/Arabic). Keep "Publish" on.
3. The workflow numbers the version automatically: versionCode = latest published + 1, versionName = `MAJOR.MINOR.(code-1)` from `version.properties`.
4. It builds, tests, signs, verifies and publishes the APK, `latest.json`, `versions.json` and `CHANGELOG.md` to LudoAI-Updates.
5. Installed apps see the update on next launch (or Settings > Check for updates), download it, verify the SHA-256 and open the Android installer. User data stays because it is an in-place update with the same key and applicationId.

To change the major/minor version edit `version.properties`. Never lower VERSION_CODE.

Local build in Termux (optional): `./gradlew testDebugUnitTest assembleDebug`.
