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
