package com.ludoai.assistant.core

import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

/** Contents of latest.json in the LudoAI-Updates repository. */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notesAr: String,
    val notesEn: String,
    val minVersionCode: Int,
    val mandatory: Boolean,
    val certSha256: String,
    val publishedAt: String,
    val applicationId: String,
) {
    fun notes(lang: String) = if (lang == "ar" && notesAr.isNotBlank()) notesAr else notesEn
}

object UpdateLogic {
    fun metadataUrl(owner: String, repo: String) = "https://raw.githubusercontent.com/$owner/$repo/main/latest.json"

    fun parse(json: String): UpdateInfo {
        val o = JSONObject(json)
        val notes = o.optJSONObject("releaseNotes")
        val info = UpdateInfo(
            versionName = o.getString("versionName"),
            versionCode = o.getInt("versionCode"),
            apkUrl = o.getString("apkUrl"),
            sha256 = o.getString("sha256").lowercase(),
            sizeBytes = o.optLong("sizeBytes", -1),
            notesAr = notes?.optString("ar") ?: "",
            notesEn = notes?.optString("en") ?: o.optString("releaseNotes", ""),
            minVersionCode = o.optInt("minVersionCode", 1),
            mandatory = o.optBoolean("mandatory", false),
            certSha256 = o.optString("certSha256", "").lowercase(),
            publishedAt = o.optString("publishedAt", ""),
            applicationId = o.optString("applicationId", ""),
        )
        require(Regex("[0-9a-f]{64}").matches(info.sha256)) { "bad sha256 in metadata" }
        require(info.apkUrl.startsWith("https://")) { "apkUrl must be https" }
        return info
    }

    fun isNewer(installedVersionCode: Int, info: UpdateInfo) = info.versionCode > installedVersionCode

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun verify(file: java.io.File, info: UpdateInfo): Boolean =
        file.exists() && (info.sizeBytes < 0 || file.length() == info.sizeBytes) && file.inputStream().use { sha256Hex(it) } == info.sha256

    /**
     * Strict, fail-closed metadata checks. Returns a list of problem codes (empty = acceptable).
     * [owner]/[repo] pin the APK host to this project's Updates repository releases.
     */
    fun validate(info: UpdateInfo, expectedApplicationId: String, owner: String, repo: String): List<String> {
        val p = ArrayList<String>()
        if (info.applicationId != expectedApplicationId) p += "APPLICATION_ID_MISMATCH"
        if (info.versionCode <= 0) p += "BAD_VERSION_CODE"
        if (info.minVersionCode > info.versionCode) p += "BAD_MIN_VERSION_CODE"
        if (info.sizeBytes <= 0) p += "SIZE_MISSING"
        if (!Regex("[0-9a-f]{64}").matches(info.certSha256)) p += "CERT_MISSING"
        val allowed = listOf("https://github.com/$owner/$repo/releases/download/", "https://raw.githubusercontent.com/$owner/$repo/")
        if (allowed.none { info.apkUrl.startsWith(it) }) p += "APK_URL_NOT_ALLOWED"
        return p
    }

    /** A mandatory flag, or an installed version older than minVersionCode, makes the update required. */
    fun isRequired(installedVersionCode: Int, info: UpdateInfo) = isNewer(installedVersionCode, info) && (info.mandatory || installedVersionCode < info.minVersionCode)

    /** What the Android package manager reports about an APK file (or about the installed app). Signer values are SHA-256 hex of the certificates. */
    data class ApkFacts(val packageName: String, val versionCode: Long, val signers: Set<String>)

    /**
     * Checks a downloaded APK before the installer is opened. [installed] is the running app.
     * [checkInstalledSigner] is false only for debug builds, which are signed with a different key than releases.
     */
    fun checkApk(info: UpdateInfo, apk: ApkFacts?, installed: ApkFacts, checkInstalledSigner: Boolean = true): List<String> {
        if (apk == null) return listOf("APK_UNREADABLE")
        val p = ArrayList<String>()
        if (apk.packageName != info.applicationId) p += "APK_PACKAGE_MISMATCH"
        if (apk.versionCode != info.versionCode.toLong()) p += "APK_VERSIONCODE_MISMATCH"
        if (apk.versionCode <= installed.versionCode) p += "DOWNGRADE"
        if (apk.signers.isEmpty()) p += "APK_UNSIGNED"
        else if (info.certSha256.isEmpty() || info.certSha256 !in apk.signers) p += "CERT_MISMATCH_METADATA"
        if (checkInstalledSigner && apk.signers.isNotEmpty() && installed.signers.isNotEmpty() && apk.signers.intersect(installed.signers).isEmpty()) p += "CERT_MISMATCH_INSTALLED"
        return p
    }

    enum class DownloadResult { OK, SIZE_MISMATCH, HASH_MISMATCH, IO_ERROR }

    /**
     * Writes to "<target>.part", verifies size and SHA-256, then renames atomically. A corrupt or truncated download
     * never ends up at [target]. Returns the result of the last attempt.
     */
    fun downloadVerified(attempts: Int, fetch: (java.io.File) -> Unit, target: java.io.File, info: UpdateInfo): DownloadResult {
        val part = java.io.File(target.path + ".part")
        var last = DownloadResult.IO_ERROR
        repeat(attempts) {
            part.delete(); target.delete()
            last = try {
                fetch(part)
                when {
                    !part.exists() -> DownloadResult.IO_ERROR
                    info.sizeBytes >= 0 && part.length() != info.sizeBytes -> DownloadResult.SIZE_MISMATCH
                    part.inputStream().use { sha256Hex(it) } != info.sha256 -> DownloadResult.HASH_MISMATCH
                    else -> DownloadResult.OK
                }
            } catch (_: Exception) { DownloadResult.IO_ERROR }
            if (last == DownloadResult.OK) {
                if (part.renameTo(target)) return DownloadResult.OK
                last = DownloadResult.IO_ERROR
            }
        }
        part.delete(); target.delete()
        return last
    }

    /** Download with retries; the caller supplies the transport so it can be tested without a network. */
    fun downloadWithRetry(attempts: Int, fetch: (java.io.File) -> Unit, target: java.io.File, info: UpdateInfo): Boolean {
        repeat(attempts) {
            try { target.delete(); fetch(target); if (verify(target, info)) return true } catch (_: Exception) { }
        }
        target.delete()
        return false
    }
}
