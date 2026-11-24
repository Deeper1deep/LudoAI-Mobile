package com.ludoai.assistant

import com.ludoai.assistant.core.*
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URL
import java.security.MessageDigest

class UpdateTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun json(apk: ByteArray, url: String, code: Int = 5) = """
        {"applicationId":"com.ludoai.assistant","versionName":"1.0.${code - 1}","versionCode":$code,"apkUrl":"$url",
         "sha256":"${sha(apk)}","sizeBytes":${apk.size},"minVersionCode":1,"mandatory":false,
         "certSha256":"${"ab".repeat(32)}","publishedAt":"2026-11-23T00:00:00Z",
         "releaseNotes":{"ar":"تحسينات","en":"Improvements"}}""".trimIndent()

    @Test fun parsesAndComparesVersionCode() {
        val info = UpdateLogic.parse(json(ByteArray(10), "https://example.com/a.apk", 7))
        assertTrue(UpdateLogic.isNewer(6, info)); assertFalse(UpdateLogic.isNewer(7, info)); assertFalse(UpdateLogic.isNewer(8, info))
        assertEquals("تحسينات", info.notes("ar")); assertEquals("Improvements", info.notes("en"))
    }

    @Test fun rejectsInsecureUrlAndBadHash() {
        try { UpdateLogic.parse(json(ByteArray(1), "http://example.com/a.apk")); fail() } catch (_: IllegalArgumentException) { }
        try { UpdateLogic.parse(json(ByteArray(1), "https://x/a.apk").replace(sha(ByteArray(1)), "zz")); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun metadataRetrievalAndVerifiedDownloadOverHttpWithRetry() {
        val apk = ByteArray(300_000) { (it * 31).toByte() }
        var apkHits = 0
        val server = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        val worker = Thread {
            try {
                while (true) {
                    val sock = server.accept()
                    val path = sock.getInputStream().bufferedReader().readLine().split(" ")[1]
                    val body = if (path == "/latest.json") json(apk, "https://localhost/ludoai.apk").toByteArray()
                    else { apkHits++; if (apkHits == 1) apk.copyOf(1000) else apk }   // first try is truncated
                    val out = sock.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(body); out.flush(); sock.close()
                }
            } catch (_: Exception) { }
        }
        worker.isDaemon = true; worker.start()
        try {
            val info = UpdateLogic.parse(URL("$base/latest.json").readText())
            val target = File.createTempFile("ludoai", ".apk")
            val ok = UpdateLogic.downloadWithRetry(3, { f -> URL("$base/apk").openStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } } }, target, info)
            assertTrue(ok); assertEquals(2, apkHits)
            assertTrue(UpdateLogic.verify(target, info))
            target.writeBytes(apk.copyOf().also { it[5] = 9 })
            assertFalse("tampered APK must fail SHA-256", UpdateLogic.verify(target, info))
        } finally { server.close() }
    }
}

class UpdateSecurityTest {
    private val cert = "ab".repeat(32)
    private val apk = ByteArray(5000) { it.toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun info(code: Int = 8, min: Int = 1, mandatory: Boolean = false, certv: String = cert, url: String = "https://github.com/Deeper1deep/LudoAI-Updates/releases/download/v1/a.apk", size: Long = apk.size.toLong()) =
        UpdateInfo("1.0.7", code, url, sha(apk), size, "", "", min, mandatory, certv, "", "com.ludoai.assistant")
    private val installed = UpdateLogic.ApkFacts("com.ludoai.assistant", 7, setOf(cert))
    private fun facts(pkg: String = "com.ludoai.assistant", code: Long = 8, signers: Set<String> = setOf(cert)) = UpdateLogic.ApkFacts(pkg, code, signers)
    private fun validate(i: UpdateInfo) = UpdateLogic.validate(i, "com.ludoai.assistant", "Deeper1deep", "LudoAI-Updates")

    @Test fun goodMetadataAndApkPass() {
        assertTrue(validate(info()).isEmpty())
        assertTrue(UpdateLogic.checkApk(info(), facts(), installed).isEmpty())
    }

    @Test fun metadataIsFailClosed() {
        assertTrue("CERT_MISSING" in validate(info(certv = "")))
        assertTrue("CERT_MISSING" in validate(info(certv = "xyz")))
        assertTrue("SIZE_MISSING" in validate(info(size = -1)))
        assertTrue("APK_URL_NOT_ALLOWED" in validate(info(url = "https://evil.example.com/a.apk")))
        assertTrue("APK_URL_NOT_ALLOWED" in validate(info(url = "https://github.com/someone/else/releases/download/v1/a.apk")))
        assertTrue("BAD_MIN_VERSION_CODE" in validate(info(code = 5, min = 9)))
        assertTrue("APPLICATION_ID_MISMATCH" in UpdateLogic.validate(info(), "other.app", "Deeper1deep", "LudoAI-Updates"))
    }

    @Test fun apkChecks() {
        assertTrue("APK_PACKAGE_MISMATCH" in UpdateLogic.checkApk(info(), facts(pkg = "evil.app"), installed))
        assertTrue("APK_VERSIONCODE_MISMATCH" in UpdateLogic.checkApk(info(), facts(code = 9), installed))
        assertTrue("DOWNGRADE" in UpdateLogic.checkApk(info(code = 7), facts(code = 7), installed))
        assertTrue("DOWNGRADE" in UpdateLogic.checkApk(info(code = 6), facts(code = 6), installed))
        assertTrue("CERT_MISMATCH_METADATA" in UpdateLogic.checkApk(info(), facts(signers = setOf("cd".repeat(32))), installed))
        assertTrue("APK_UNSIGNED" in UpdateLogic.checkApk(info(), facts(signers = emptySet()), installed))
        assertTrue("APK_UNREADABLE" in UpdateLogic.checkApk(info(), null, installed))
    }

    @Test fun metadataCertMatchingApkIsNotEnoughWhenInstalledSignerDiffers() {
        // attacker controls metadata AND APK: both carry the attacker cert, but the installed app is signed by the real key
        val evil = "ee".repeat(32)
        val r = UpdateLogic.checkApk(info(certv = evil), facts(signers = setOf(evil)), installed)
        assertEquals(listOf("CERT_MISMATCH_INSTALLED"), r)
        // debug builds skip this comparison (different key than releases)
        assertTrue(UpdateLogic.checkApk(info(certv = evil), facts(signers = setOf(evil)), installed, checkInstalledSigner = false).isEmpty())
    }

    @Test fun mandatoryAndMinVersionCodeMakeTheUpdateRequired() {
        assertFalse(UpdateLogic.isRequired(7, info()))
        assertTrue(UpdateLogic.isRequired(7, info(mandatory = true)))
        assertTrue(UpdateLogic.isRequired(7, info(min = 7 + 1)))
        assertFalse(UpdateLogic.isRequired(8, info(mandatory = true)))   // already up to date
    }

    @Test fun corruptDownloadsNeverReachTheTarget() {
        val dir = createTempDir(); val target = File(dir, "u.apk")
        assertEquals(UpdateLogic.DownloadResult.OK, UpdateLogic.downloadVerified(1, { it.writeBytes(apk) }, target, info()))
        assertTrue(target.exists()); assertFalse(File(target.path + ".part").exists())
        val t2 = File(dir, "u2.apk")
        assertEquals(UpdateLogic.DownloadResult.SIZE_MISMATCH, UpdateLogic.downloadVerified(2, { it.writeBytes(apk.copyOf(100)) }, t2, info()))
        assertFalse(t2.exists())
        val flipped = apk.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertEquals(UpdateLogic.DownloadResult.HASH_MISMATCH, UpdateLogic.downloadVerified(2, { it.writeBytes(flipped) }, t2, info()))
        assertFalse(t2.exists()); assertFalse(File(t2.path + ".part").exists())
        assertEquals(UpdateLogic.DownloadResult.IO_ERROR, UpdateLogic.downloadVerified(2, { throw java.io.IOException("net") }, t2, info()))
    }

    @Test fun retryRecoversFromATransientCorruption() {
        val dir = createTempDir(); val target = File(dir, "u.apk"); var n = 0
        val r = UpdateLogic.downloadVerified(3, { n++; it.writeBytes(if (n == 1) apk.copyOf(10) else apk) }, target, info())
        assertEquals(UpdateLogic.DownloadResult.OK, r); assertEquals(2, n)
    }
}
