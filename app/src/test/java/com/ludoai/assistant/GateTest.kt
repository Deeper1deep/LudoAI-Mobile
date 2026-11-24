package com.ludoai.assistant

import com.ludoai.assistant.core.*
import org.junit.Assert.*
import org.junit.Test

class GateTest {
    private fun frame(v: Int, w: Int = 200, h: Int = 400) = Frame(w, h, IntArray(w * h) { (0xFF shl 24) or (v shl 16) or (v shl 8) or v })

    @Test fun identicalFramesHaveZeroDiffAndDifferentSizesAreDifferent() {
        val a = FrameSignature.of(frame(100)); val b = FrameSignature.of(frame(100))
        assertEquals(0.0, a.diff(b), 0.0)
        assertTrue(a.diff(FrameSignature.of(frame(200))) > 0.3)
        assertEquals(1.0, a.diff(FrameSignature.of(frame(100), 24, 40)), 0.0)
    }

    @Test fun firstFrameIsAnalysedThenIdenticalFramesAreNot() {
        val g = AutoRefreshGate(); val s = FrameSignature.of(frame(100))
        assertTrue(g.shouldAnalyze(s, 0)); g.started(s, 0); g.finished(false, 10)
        repeat(20) { i -> assertFalse(g.shouldAnalyze(FrameSignature.of(frame(100)), 1000L + i * 400)) }
    }

    @Test fun neverRunsTwoAnalysesAtOnce() {
        val g = AutoRefreshGate(); val a = FrameSignature.of(frame(100))
        assertTrue(g.shouldAnalyze(a, 0)); g.started(a, 0)
        assertFalse(g.shouldAnalyze(FrameSignature.of(frame(30)), 5000))
        g.finished(false, 5100)
    }

    @Test fun changeIsAnalysedOnceThePictureSettles() {
        val g = AutoRefreshGate(); val a = FrameSignature.of(frame(100))
        g.shouldAnalyze(a, 0); g.started(a, 0); g.finished(false, 10)
        val b = FrameSignature.of(frame(180)); val c = FrameSignature.of(frame(60))
        assertFalse("still moving", g.shouldAnalyze(b, 1000))     // 100 -> 180 differs from previous
        assertFalse("still moving", g.shouldAnalyze(c, 1400))
        assertTrue("settled", g.shouldAnalyze(FrameSignature.of(frame(60)), 1800))
    }

    @Test fun endlessAnimationIsEventuallyAnalysed() {
        val g = AutoRefreshGate(maxWaitMs = 2000); val a = FrameSignature.of(frame(100))
        g.shouldAnalyze(a, 0); g.started(a, 0); g.finished(false, 10)
        var fired = false; var t = 1000L; var v = 150
        while (t < 6000 && !fired) { v = if (v == 150) 50 else 150; fired = g.shouldAnalyze(FrameSignature.of(frame(v)), t); t += 400 }
        assertTrue(fired)
    }

    @Test fun minimumIntervalIsRespected() {
        val g = AutoRefreshGate(minIntervalMs = 600); val a = FrameSignature.of(frame(100))
        g.shouldAnalyze(a, 0); g.started(a, 0); g.finished(false, 10)
        val b = FrameSignature.of(frame(200))
        g.shouldAnalyze(b, 100)
        assertFalse(g.shouldAnalyze(FrameSignature.of(frame(200)), 300))
        assertTrue(g.shouldAnalyze(FrameSignature.of(frame(200)), 700))
    }

    @Test fun confirmationFrameIsAnalysedEvenWhenUnchanged() {
        val g = AutoRefreshGate(); val a = FrameSignature.of(frame(100))
        g.shouldAnalyze(a, 0); g.started(a, 0); g.finished(true, 100)
        assertTrue(g.shouldAnalyze(FrameSignature.of(frame(100)), 700))
    }

    @Test fun forceBypassesTheThrottle() {
        val g = AutoRefreshGate(); val a = FrameSignature.of(frame(100))
        g.shouldAnalyze(a, 0); g.started(a, 0); g.finished(false, 10)
        g.force(); assertTrue(g.shouldAnalyze(FrameSignature.of(frame(100)), 20))
    }

    @Test fun idleBackoffGrows() {
        val g = AutoRefreshGate(); val a = FrameSignature.of(frame(100))
        g.shouldAnalyze(a, 0); g.started(a, 0); g.finished(false, 10)
        val early = g.nextDelayMs(1000); val late = g.nextDelayMs(60_000)
        assertTrue(late > early)
    }

    @Test fun signatureOfRealFixtureIsStable() {
        val f = Fixtures.load("real_4p_green_bl.rgb.gz")
        assertEquals(0.0, FrameSignature.of(f).diff(FrameSignature.of(f)), 0.0)
    }

    @Test fun maskedRegionsAreIgnored() {
        val a = frame(100); val b = frame(100)
        for (y in 0 until 100) for (x in 0 until 100) b.px[y * b.w + x] = 0xFFFFFFFF.toInt()      // our own panel changed
        val mask = listOf(intArrayOf(0, 0, 110, 110))
        assertEquals(0.0, FrameSignature.of(a, mask = mask).diff(FrameSignature.of(b, mask = mask)), 0.0)
        assertTrue(FrameSignature.of(a).diff(FrameSignature.of(b)) > 0.005)
    }

    @Test fun maskCoveringMostOfTheScreenCountsAsChanged() {
        val a = frame(100)
        val mask = listOf(intArrayOf(0, 0, 200, 380))
        assertEquals(1.0, FrameSignature.of(a, mask = mask).diff(FrameSignature.of(a, mask = mask)), 0.0)
    }

    @Test fun samplerAndFrameConstructorsAgree() {
        val f = Fixtures.load("real_4p_green_bl.rgb.gz")
        val s1 = FrameSignature.of(f); val s2 = FrameSignature.sample(f.w, f.h) { x, y -> f.px[y * f.w + x] }
        assertEquals(0.0, s1.diff(s2), 0.0)
    }

    @Test fun manualStartDoesNotCauseASecondAnalysis() {
        val g = AutoRefreshGate(); val a = FrameSignature.of(frame(100))
        g.shouldAnalyze(a, 0); g.started(a, 0); g.finished(false, 10)
        g.startedManually(5000); g.finished(false, 5200)
        assertFalse(g.shouldAnalyze(FrameSignature.of(frame(100)), 6000))   // adopted as baseline
        assertFalse(g.shouldAnalyze(FrameSignature.of(frame(100)), 7000))   // nothing changed
        assertTrue(g.shouldAnalyze(FrameSignature.of(frame(220)), 8000).let { it || g.shouldAnalyze(FrameSignature.of(frame(220)), 9000) })
    }
}
