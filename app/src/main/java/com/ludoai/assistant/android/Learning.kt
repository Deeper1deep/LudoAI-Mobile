package com.ludoai.assistant.android

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.ludoai.assistant.core.Analysis
import com.ludoai.assistant.core.Frame
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Opt-in learning mode. Everything stays on the phone until the user taps Share.
 * Stored: a compact JSON event log and a few small snapshots of the BOARD AREA ONLY
 * (player names and avatars sit outside it and are never saved). Hard caps keep it small.
 */
object Learning {
    private const val MAX_FRAMES = 10
    private const val MAX_EVENTS_BYTES = 120_000L
    private const val FRAME_PX = 420
    private const val MIN_FRAME_GAP_MS = 4000L
    private var lastSig = ""
    private var lastFrameAt = 0L
    private var lastDice = -1

    fun dir(c: Context) = File(c.applicationContext.filesDir, "learning").apply { mkdirs() }
    private fun events(c: Context) = File(dir(c), "events.jsonl")
    private fun frames(c: Context) = dir(c).listFiles { f -> f.name.startsWith("frame_") }?.sortedBy { it.name } ?: emptyList()

    private fun diceFrames(c: Context) = dir(c).listFiles { f -> f.name.startsWith("dice_") }?.sortedBy { it.name } ?: emptyList()

    fun sizeBytes(c: Context): Long = dir(c).listFiles()?.sumOf { it.length() } ?: 0L
    fun frameCount(c: Context) = frames(c).size

    fun clear(c: Context) { dir(c).listFiles()?.forEach { it.delete() }; lastSig = ""; lastDice = -1 }

    @Synchronized
    fun record(c: Context, frame: Frame, a: Analysis?, players: Int, manualDice: Boolean) {
        val now = System.currentTimeMillis()
        val sig = a?.counts?.entries?.joinToString("|") { "${it.key}:${it.value.yard}/${it.value.onBoard}/${it.value.finished}/${it.value.unknown}" } ?: ""
        val diceV = a?.dice?.value ?: -1
        val ev = JSONObject().put("t", now).put("players", players).put("ok", a?.ok ?: false)
            .put("conf", a?.confidence?.let { Math.round(it * 100) / 100.0 } ?: 0.0)
            .put("user", a?.userColor?.name ?: "").put("dice", diceV).put("manualDice", manualDice)
            .put("problems", (a?.problems ?: emptyList<String>()).joinToString(",")).put("blockers", (a?.blockers ?: emptyList<String>()).joinToString(",")).put("turn", a?.turnSeat?.name ?: "").put("counts", sig)
            .put("frameW", frame.w).put("frameH", frame.h)
        a?.board?.let { ev.put("cell", Math.round(it.geometry.cell * 10) / 10.0).put("left", Math.round(it.geometry.left)).put("top", Math.round(it.geometry.top)) }
        a?.best?.let { ev.put("best", it.move.toString()) }
        val boardChanged = sig.isNotEmpty() && sig != lastSig
        val diceNew = diceV > 0 && diceV != lastDice
        val key = a?.board != null && (boardChanged || diceNew) && now - lastFrameAt >= MIN_FRAME_GAP_MS
        if (key) {
            val name = saveFrame(c, frame, a!!, now)
            if (name != null) { ev.put("frame", name); lastFrameAt = now }
            saveDice(c, frame, a, now)?.let { ev.put("diceFrame", it) }
        }
        lastSig = sig.ifEmpty { lastSig }; if (diceV > 0) lastDice = diceV
        val f = events(c)
        if (f.length() < MAX_EVENTS_BYTES && (key || boardChanged || diceNew)) f.appendText(ev.toString() + "\n")
    }

    private fun saveFrame(c: Context, frame: Frame, a: Analysis, now: Long): String? {
        val g = a.board!!.geometry
        val size = g.cell * 15.0
        val x0 = g.left.toInt().coerceIn(0, frame.w - 1); val y0 = g.top.toInt().coerceIn(0, frame.h - 1)
        val sz = size.toInt().coerceAtMost(minOf(frame.w - x0, frame.h - y0))
        if (sz < 60) return null
        val n = minOf(FRAME_PX, sz)
        val out = IntArray(n * n)
        for (j in 0 until n) { val sy = y0 + j * sz / n; for (i in 0 until n) out[j * n + i] = frame.px[sy * frame.w + x0 + i * sz / n] }
        val bmp = Bitmap.createBitmap(out, n, n, Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 55, bos); bmp.recycle()
        val name = "frame_$now.jpg"
        File(dir(c), name).writeBytes(bos.toByteArray())
        frames(c).dropLast(MAX_FRAMES).forEach { it.delete() }
        return name
    }

    /**
     * Small 2x2 mosaic of the four dice slots that sit just below/above the board (3x3 cells each, scaled to 128 px).
     * The crops start right of the avatar column, so names and avatars are not included. Skin-specific guess, validated on one skin.
     */
    private fun saveDice(c: Context, frame: Frame, a: Analysis, now: Long): String? {
        val g = a.board!!.geometry
        val t = 128
        val out = IntArray(2 * t * 2 * t)
        val cols = doubleArrayOf(3.2, 8.9); val rows = doubleArrayOf(-4.6, 16.6)
        var any = false
        for (ry in 0..1) for (cx in 0..1) {
            val x0 = g.x(cols[cx]); val y0 = g.y(rows[ry]); val sz = g.cell * 3.0
            for (j in 0 until t) for (i in 0 until t) {
                val sx = (x0 + i * sz / t).toInt(); val sy = (y0 + j * sz / t).toInt()
                if (sx in 0 until frame.w && sy in 0 until frame.h) { out[(ry * t + j) * 2 * t + cx * t + i] = frame.px[sy * frame.w + sx]; any = true }
                else out[(ry * t + j) * 2 * t + cx * t + i] = 0xFF000000.toInt()
            }
        }
        if (!any) return null
        val bmp = Bitmap.createBitmap(out, 2 * t, 2 * t, Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 60, bos); bmp.recycle()
        val name = "dice_$now.jpg"
        File(dir(c), name).writeBytes(bos.toByteArray())
        diceFrames(c).dropLast(MAX_FRAMES).forEach { it.delete() }
        return name
    }

    /** One plain-text file (WhatsApp accepts .txt): header, EVENT lines, FRAME lines (base64 JPEG). */
    fun exportText(c: Context): File? {
        val ev = events(c); val fr = frames(c) + diceFrames(c)
        if (!ev.exists() && fr.isEmpty()) return null
        val outDir = File(c.cacheDir, "learning-export").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(outDir, "ludoai-learning-${System.currentTimeMillis() / 1000}.txt")
        out.bufferedWriter().use { w ->
            w.write("LUDOAI-LEARNING v1 app=${com.ludoai.assistant.BuildConfig.VERSION_NAME} android=${android.os.Build.VERSION.RELEASE} device=${android.os.Build.MODEL}\n")
            if (ev.exists()) ev.forEachLine { w.write("EVENT $it\n") }
            fr.forEach { w.write("FRAME ${it.name} ${Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)}\n") }
        }
        return out
    }
}
