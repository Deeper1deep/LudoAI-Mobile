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

    /** Download with retries; the caller supplies the transport so it can be tested without a network. */
    fun downloadWithRetry(attempts: Int, fetch: (java.io.File) -> Unit, target: java.io.File, info: UpdateInfo): Boolean {
        repeat(attempts) {
            try { target.delete(); fetch(target); if (verify(target, info)) return true } catch (_: Exception) { }
        }
        target.delete()
        return false
    }
}
