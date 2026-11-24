package com.ludoai.assistant.core

import kotlin.math.abs

/** Cheap fingerprint of a frame: luma sampled on a coarse grid. Comparing two of them costs ~2k operations. */
class FrameSignature(val cols: Int, val rows: Int, val luma: IntArray) {
    /**
     * Mean absolute luma difference, 0..1, over the cells that are visible in BOTH signatures
     * (masked cells hold -1: they belong to our own overlay and are ignored). Different grids count as completely different.
     */
    fun diff(o: FrameSignature): Double {
        if (cols != o.cols || rows != o.rows) return 1.0
        var s = 0L; var n = 0
        for (i in luma.indices) {
            val a = luma[i]; val b = o.luma[i]
            if (a < 0 || b < 0) continue
            s += abs(a - b); n++
        }
        return if (n < luma.size / 4) 1.0 else s / (255.0 * n)   // almost everything masked: cannot compare, treat as changed
    }

    companion object {
        fun of(f: Frame, cols: Int = 48, rows: Int = 84, mask: List<IntArray> = emptyList()): FrameSignature =
            sample(f.w, f.h, cols, rows, mask) { x, y -> f.px[y * f.w + x] }

        /**
         * Builds a signature by asking [pixel] (x, y -> ARGB) for ~cols*rows points, so a frame never has to be fully
         * converted just to see whether the screen changed. [mask] rectangles (x0, y0, x1, y1 in frame pixels) are skipped.
         */
        fun sample(w: Int, h: Int, cols: Int = 48, rows: Int = 84, mask: List<IntArray> = emptyList(), pixel: (Int, Int) -> Int): FrameSignature {
            val out = IntArray(cols * rows)
            for (j in 0 until rows) {
                val y = ((j + 0.5) * h / rows).toInt().coerceIn(0, h - 1)
                for (i in 0 until cols) {
                    val x = ((i + 0.5) * w / cols).toInt().coerceIn(0, w - 1)
                    if (mask.any { x >= it[0] && x < it[2] && y >= it[1] && y < it[3] }) { out[j * cols + i] = -1; continue }
                    val p = pixel(x, y)
                    out[j * cols + i] = (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000
                }
            }
            return FrameSignature(cols, rows, out)
        }
    }
}

/**
 * Decides, for every captured frame, whether a full analysis is worth running.
 *  - never while an analysis is running (no concurrent analyses);
 *  - never for a frame that looks like the last analysed one;
 *  - waits until the picture has stopped moving (animations), but not longer than [maxWaitMs];
 *  - at most one analysis per [minIntervalMs];
 *  - after an analysis that needs a confirming frame (dice settling) the next frame is analysed even if unchanged.
 */
class AutoRefreshGate(
    private val minIntervalMs: Long = 600,
    private val changeThreshold: Double = 0.010,
    private val maxWaitMs: Long = 2500,
    private val confirmDelayMs: Long = 250,
) {
    private var analysed: FrameSignature? = null
    private var prev: FrameSignature? = null
    private var lastAnalysisAt = Long.MIN_VALUE / 2
    private var changeSince = -1L
    private var confirmAt = -1L
    private var lastChangeAt = 0L
    private var adoptNext = false
    var busy = false; private set

    /** Called for every captured frame. When it returns true the caller must call [started] and later [finished]. */
    fun shouldAnalyze(sig: FrameSignature, now: Long): Boolean {
        val p = prev; prev = sig
        val moving = p != null && p.diff(sig) > changeThreshold
        if (moving) lastChangeAt = now
        if (busy) return false
        if (now - lastAnalysisAt < minIntervalMs) return false
        val a = analysed
        if (a == null) { if (adoptNext) { analysed = sig; adoptNext = false; return false }; return true }
        if (confirmAt >= 0 && now >= confirmAt) return true
        val changed = a.diff(sig) > changeThreshold
        if (!changed) { changeSince = -1; return false }
        if (changeSince < 0) changeSince = now
        return !moving || now - changeSince >= maxWaitMs
    }

    fun started(sig: FrameSignature, now: Long) { busy = true; analysed = sig; lastAnalysisAt = now; changeSince = -1; confirmAt = -1 }

    /** [needsConfirmation]: the analysis saw something unsettled (a die still rolling); look again soon. */
    fun finished(needsConfirmation: Boolean, now: Long) { busy = false; if (needsConfirmation) confirmAt = now + confirmDelayMs }

    /** The next frame must be analysed (e.g. the display geometry changed). */
    fun force() { analysed = null; adoptNext = false; lastAnalysisAt = Long.MIN_VALUE / 2; confirmAt = -1 }

    /**
     * The caller starts an analysis itself right now (refresh button). The next polled frame becomes the new
     * baseline instead of triggering a second analysis.
     */
    fun startedManually(now: Long) { busy = true; analysed = null; adoptNext = true; lastAnalysisAt = now; changeSince = -1; confirmAt = -1; lastChangeAt = now }

    /** How long the capture loop may sleep before the next look: short while the screen changes, longer when idle. */
    fun nextDelayMs(now: Long): Long = when {
        busy -> 200L
        confirmAt >= 0 -> confirmDelayMs
        now - lastChangeAt < 5_000 -> 350L
        now - lastChangeAt < 30_000 -> 800L
        else -> 1_500L
    }
}
