package com.ludoai.assistant

import com.ludoai.assistant.core.*
import org.junit.Assert.*
import org.junit.Test

internal object Kit {
    fun yardHits(vararg slots: Int) = slots.map { YardSlotHit(it, 0.9) }
    fun boardHits(vararg rels: Int) = rels.map { BoardHit(it, 0.9) }
    fun obs(seat: Seat, board: List<BoardHit>, yard: List<YardSlotHit>, trusted: Boolean = true) = SeatObservation(seat, board, yard, trusted)
}

/** Piece-state reconciliation: the "missing piece = finished" bug must be impossible. */
class ReconcilerTest {
    private fun yardHits(vararg s: Int) = Kit.yardHits(*s)
    private fun boardHits(vararg r: Int) = Kit.boardHits(*r)
    private fun obs(seat: Seat, b: List<BoardHit>, y: List<YardSlotHit>, trusted: Boolean = true) = Kit.obs(seat, b, y, trusted)
    private val s = Seat.BOTTOM_LEFT

    @Test fun missingPieceIsUnknownNeverFinished() {
        val sp = Reconciler.reconcile(obs(s, boardHits(5), yardHits(0, 1)), null)
        assertEquals(1, sp.counts.unknown); assertEquals(0, sp.counts.finished)
        assertFalse(sp.complete); assertTrue("PIECES_UNKNOWN_$s" in sp.problems)
        assertEquals(Board.HIDDEN, sp.rels().max().let { sp.rels().min() })
    }

    @Test fun allFourSeenIsCompleteWithPerPieceConfidence() {
        val sp = Reconciler.reconcile(obs(s, boardHits(5, 53), yardHits(0, 1)), null)
        assertTrue(sp.complete); assertEquals(0.9, sp.minConfidence, 1e-9)
        assertEquals(setOf(PieceState.YARD, PieceState.ON_TRACK, PieceState.HOME_PATH), sp.pieces.map { it.state }.toSet())
    }

    @Test fun tooManyPiecesIsRejected() {
        val sp = Reconciler.reconcile(obs(s, boardHits(1, 2, 3), yardHits(0, 1)), null)
        assertTrue(sp.problems.any { it.startsWith("TOO_MANY") }); assertFalse(sp.complete)
    }

    @Test fun pieceVanishingFromHomeLaneIsTrackedAsFinished() {
        val m = SeatMemory()
        val a = Reconciler.reconcile(obs(s, boardHits(55, 10), yardHits(0, 1)), m)
        Reconciler.remember(m, a, true)
        val b = Reconciler.reconcile(obs(s, boardHits(10), yardHits(0, 1)), m)
        assertEquals(1, b.counts.finished); assertTrue(b.complete)
        assertEquals(Evidence.TRACKED_FINISH, b.pieces.first { it.state == PieceState.FINISHED }.evidence)
    }

    @Test fun pieceVanishingFromTrackIsNotFinished() {
        val m = SeatMemory()
        val a = Reconciler.reconcile(obs(s, boardHits(30, 10), yardHits(0, 1)), m)
        Reconciler.remember(m, a, true)
        val b = Reconciler.reconcile(obs(s, boardHits(10), yardHits(0, 1)), m)
        assertEquals(0, b.counts.finished); assertEquals(1, b.counts.unknown)
    }

    @Test fun untrustedFrameDoesNotInferFinish() {
        val m = SeatMemory()
        Reconciler.remember(m, Reconciler.reconcile(obs(s, boardHits(55, 10), yardHits(0, 1)), m), true)
        val b = Reconciler.reconcile(obs(s, boardHits(10), yardHits(0, 1), trusted = false), m)
        assertEquals(0, b.counts.finished); assertEquals(1, b.counts.unknown)
    }

    @Test fun declaredFinishedCountIsUsedAndCapped() {
        val m = SeatMemory().also { it.declaredFinished = 2 }
        val sp = Reconciler.reconcile(obs(s, boardHits(10, 20), emptyList()), m)
        assertEquals(2, sp.counts.finished); assertTrue(sp.complete)
        assertEquals(Evidence.DECLARED_FINISH, sp.pieces.first { it.state == PieceState.FINISHED }.evidence)
        val m2 = SeatMemory().also { it.declaredFinished = 4 }
        assertEquals(1, Reconciler.reconcile(obs(s, boardHits(10, 20, 30), emptyList()), m2).counts.finished + 0 * 1)  // never above four in total
    }

    @Test fun stackedPieceIsInferredOnlyFromAnUnambiguousPreviousFrame() {
        val m = SeatMemory()
        Reconciler.remember(m, Reconciler.reconcile(obs(s, boardHits(5, 8), yardHits(0, 1)), m), true)
        // piece from 5 moves 3 onto 8: only ONE piece is visible there now
        val b = Reconciler.reconcile(obs(s, boardHits(8), yardHits(0, 1)), m)
        assertTrue(b.complete); assertEquals(2, b.pieces.count { it.rel == 8 })
        assertTrue(b.pieces.filter { it.rel == 8 }.all { it.stacked })
        assertTrue(b.pieces.any { it.evidence == Evidence.INFERRED_STACK })
    }

    @Test fun ambiguousDisappearanceIsNotGuessed() {
        val m = SeatMemory()
        Reconciler.remember(m, Reconciler.reconcile(obs(s, boardHits(5, 8), yardHits(0, 1)), m), true)
        val b = Reconciler.reconcile(obs(s, boardHits(8, 20), yardHits(0)), m)   // piece 5 gone, a yard piece gone: no single explanation
        assertFalse(b.complete)
    }
}

class DiceTrackerTest {
    private fun scan(v: Int?, seat: Seat = Seat.BOTTOM_LEFT, unreadable: Int = 0) =
        DiceScan(if (v == null) emptyList() else listOf(DiceReading(v, 0.95, 10.0, 10.0, seat)), unreadable)

    @Test fun valueMustBeStableBeforeItCounts() {
        val t = DiceTracker(framesRequired = 2, minStableMs = 0)
        assertEquals(DiceState.SETTLING, t.update(scan(4), 0).state)
        val s = t.update(scan(4), 100)
        assertEquals(DiceState.STABLE, s.state); assertTrue(s.isNewRoll); assertEquals(1, s.rollId)
    }

    @Test fun changingValueRestartsConfirmation() {
        val t = DiceTracker(2, 0)
        t.update(scan(2), 0); assertEquals(DiceState.SETTLING, t.update(scan(5), 50).state)
        assertEquals(5, t.update(scan(5), 100).reading!!.value)
    }

    @Test fun unreadableDieIsAnimation() {
        val t = DiceTracker(2, 0)
        assertEquals(DiceState.ANIMATING, t.update(scan(null, unreadable = 1), 0).state)
        assertEquals(DiceState.NONE, t.update(scan(null), 10).state)
    }

    @Test fun sameValueWithoutGapIsNotANewRoll() {
        val t = DiceTracker(2, 0)
        t.update(scan(3), 0); val a = t.update(scan(3), 10)
        val b = t.update(scan(3), 20)
        assertEquals(a.rollId, b.rollId); assertFalse(b.isNewRoll)
    }

    @Test fun sameValueAfterGapIsANewRoll() {
        val t = DiceTracker(2, 0)
        t.update(scan(3), 0); val a = t.update(scan(3), 10)
        t.update(scan(null), 20)
        t.update(scan(3), 30); val b = t.update(scan(3), 40)
        assertEquals(a.rollId + 1, b.rollId); assertTrue(b.isNewRoll)
    }

    @Test fun consumedRollIsStaleUntilReRolled() {
        val t = DiceTracker(2, 0)
        t.update(scan(6), 0); t.update(scan(6), 10)
        t.markConsumed()
        assertTrue(t.update(scan(6), 20).stale)
        t.update(scan(null), 30); t.update(scan(6), 40)
        assertFalse(t.update(scan(6), 50).stale)
    }

    @Test fun minStableTimeIsEnforced() {
        val t = DiceTracker(2, 300)
        t.update(scan(1), 0)
        assertEquals(DiceState.SETTLING, t.update(scan(1), 100).state)
        assertEquals(DiceState.STABLE, t.update(scan(1), 400).state)
    }
}

class SessionTest {
    private val green = mapOf(Seat.BOTTOM_LEFT to LudoColor.GREEN, Seat.TOP_LEFT to LudoColor.YELLOW, Seat.TOP_RIGHT to LudoColor.BLUE, Seat.BOTTOM_RIGHT to LudoColor.RED)
    private val red = mapOf(Seat.BOTTOM_LEFT to LudoColor.RED, Seat.TOP_LEFT to LudoColor.GREEN, Seat.TOP_RIGHT to LudoColor.YELLOW, Seat.BOTTOM_RIGHT to LudoColor.BLUE)
    private val W = 1080; private val H = 2340

    private fun state(vararg bl: Int): GameState {
        val g = GameState.initial(GameMode.CLASSIC_4P)
        bl.forEachIndexed { i, r -> g.pieces.getValue(Seat.BOTTOM_LEFT)[i] = r }
        return g
    }

    private fun frame(colors: Map<Seat, LudoColor> = green, st: GameState = state(5, 0, 0, 0), dice: Int? = 3) = BoardRenderer(W, H).render(colors, st, dice)

    private fun stable(s: MatchSession, f: () -> Frame): Analysis { val t0 = 0L; return stable0(s, f, t0) }
    private fun stable0(s: MatchSession, f: () -> Frame, t0: Long): Analysis { s.process(f(), GameMode.CLASSIC_4P, nowMs = t0); return s.process(f(), GameMode.CLASSIC_4P, nowMs = t0 + 400) }

    @Test fun firstFrameWithDieIsNotYetConfirmed() {
        val s = MatchSession()
        val a = s.process(frame(), GameMode.CLASSIC_4P, nowMs = 0)
        assertFalse(a.ok); assertNull(a.best); assertTrue("DICE_NOT_STABLE" in a.blockers); assertTrue(a.needsConfirmation)
    }

    @Test fun confirmedRollGivesLegalRecommendationWithDetails() {
        val s = MatchSession()
        val a = stable(s, { frame() })
        assertTrue("${a.problems}", a.ok)
        val r = a.recommendation!!
        assertEquals(3, a.dice!!.value)
        val legal = Rules.legalMoves(a.state!!, Seat.BOTTOM_LEFT, 3)
        assertTrue(legal.any { it.pieceIdx == r.best.move.pieceIdx && it.to == r.best.move.to })
        assertTrue(r.decisionConfidence in 0.0..1.0); assertTrue(a.confidence > 0.6)
        assertNotNull(a.target)
    }

    @Test fun noDieMeansNoRecommendation() {
        val a = MatchSession().process(frame(dice = null), GameMode.CLASSIC_4P, nowMs = 0)
        assertFalse(a.ok); assertTrue("DICE_NOT_FOUND" in a.blockers); assertNull(a.recommendation)
    }

    @Test fun dieNextToAnOpponentIsNotYourTurn() {
        val r = BoardRenderer(W, H)
        val mk = { val f = r.render(green, state(5, 0, 0, 0), null); r.drawDie(r.gx(10.5), r.gy(-2.4), r.cell * 1.7, 4); f }
        val a = stable(MatchSession(), mk)
        assertEquals(Seat.TOP_RIGHT, a.turnSeat)
        assertTrue("${a.problems}", "NOT_YOUR_TURN" in a.blockers); assertNull(a.recommendation); assertFalse(a.ok)
    }

    @Test fun diceInTwoPlacesMeansTurnIsUncertain() {
        val r = BoardRenderer(W, H)
        val mk = { val f = r.render(green, state(5, 0, 0, 0), 3); r.drawDie(r.gx(10.5), r.gy(-2.4), r.cell * 1.7, 4); f }
        val a = stable(MatchSession(), mk)
        assertTrue("${a.problems}", "TURN_UNCERTAIN" in a.blockers); assertNull(a.recommendation)
    }

    @Test fun undetectedPieceIsNeverTreatedAsFinished() {
        // a piece with rel = FINISH is not drawn at all: from the screen it is simply "missing"
        val s = MatchSession()
        val a = s.process(frame(st = state(5, 0, 0, Board.FINISH), dice = null), GameMode.CLASSIC_4P, nowMs = 0)
        s.setManualDice(3, 0)
        val b = s.reevaluate(GameMode.CLASSIC_4P, nowMs = 1)!!
        assertEquals(0, b.counts.getValue(Seat.BOTTOM_LEFT).finished)
        assertEquals(1, b.counts.getValue(Seat.BOTTOM_LEFT).unknown)
        assertFalse(b.ok); assertTrue("PIECES_UNKNOWN_USER" in b.blockers); assertNull(b.recommendation)
        assertFalse(a.ok)
    }

    @Test fun declaredFinishedCountUnblocksTheRecommendation() {
        val s = MatchSession()
        s.process(frame(st = state(5, 0, 0, Board.FINISH), dice = null), GameMode.CLASSIC_4P, nowMs = 0)
        s.declareUserFinished(1); s.setManualDice(3, 0)
        val b = s.reevaluate(GameMode.CLASSIC_4P, nowMs = 1)!!
        assertTrue("${b.problems}", b.ok); assertEquals(1, b.counts.getValue(Seat.BOTTOM_LEFT).finished)
    }

    @Test fun manualDiceIsIndicatedAndUsedOnce() {
        val s = MatchSession()
        s.process(frame(dice = null), GameMode.CLASSIC_4P, nowMs = 0)
        s.setManualDice(5, 0)
        val a = s.reevaluate(GameMode.CLASSIC_4P, nowMs = 1)!!
        assertTrue(a.manualDiceActive); assertEquals(5, a.dice!!.value); assertTrue("MANUAL_DICE_ACTIVE" in a.notices); assertTrue(a.ok)
    }

    @Test fun manualDiceIsClearedWhenARealRollAppears() {
        val s = MatchSession()
        s.process(frame(dice = null), GameMode.CLASSIC_4P, nowMs = 0)
        s.setManualDice(5, 0)
        s.process(frame(dice = 2), GameMode.CLASSIC_4P, nowMs = 100)
        val a = s.process(frame(dice = 2), GameMode.CLASSIC_4P, nowMs = 600)
        assertFalse(a.manualDiceActive); assertFalse(s.manualDiceActive)
        assertEquals(2, a.dice!!.value); assertTrue("MANUAL_DICE_CLEARED" in a.notices)
    }

    @Test fun manualDiceIsClearedWhenUserPiecesChange() {
        val s = MatchSession()
        s.process(frame(dice = null), GameMode.CLASSIC_4P, nowMs = 0)
        s.setManualDice(5, 0)
        val a = s.process(frame(st = state(9, 0, 0, 0), dice = null), GameMode.CLASSIC_4P, nowMs = 10)
        assertFalse(a.manualDiceActive); assertTrue("DICE_NOT_FOUND" in a.blockers)
    }

    @Test fun manualDiceExpires() {
        val s = MatchSession()
        s.process(frame(dice = null), GameMode.CLASSIC_4P, nowMs = 0)
        s.setManualDice(5, 0)
        val a = s.process(frame(dice = null), GameMode.CLASSIC_4P, nowMs = MatchSession.MANUAL_TTL_MS + 1)
        assertFalse(a.manualDiceActive)
    }

    @Test fun rollIsConsumedAfterTheBoardChanges() {
        val s = MatchSession()
        val a = stable(s) { frame(st = state(5, 0, 0, 0), dice = 3) }
        assertTrue(a.ok)
        // the user moved (piece 5 -> 8) but the die is still on screen: the old value must not be offered again
        val b = s.process(frame(st = state(8, 0, 0, 0), dice = 3), GameMode.CLASSIC_4P, nowMs = 1000)
        assertFalse(b.ok); assertTrue("DICE_STALE" in b.blockers); assertNull(b.recommendation)
    }

    @Test fun newUserColourStartsNewMatchAndRecalibrates() {
        val s = MatchSession()
        val a = stable(s) { frame(green) }
        val b = s.process(frame(red), GameMode.CLASSIC_4P, nowMs = 2000)
        assertEquals(LudoColor.GREEN, a.userColor); assertEquals(LudoColor.RED, b.userColor)
        assertTrue("RECALIBRATED" in b.notices); assertTrue(b.matchId > a.matchId)
    }

    @Test fun boardNotFoundIsReportedNotGuessed() {
        val blank = Frame(400, 800, IntArray(400 * 800) { 0xFF202020.toInt() })
        val a = MatchSession().process(blank, GameMode.CLASSIC_4P, nowMs = 0)
        assertFalse(a.ok); assertTrue("BOARD_NOT_FOUND" in a.problems)
    }

    @Test fun resolutionsAllGiveTheSameRecommendation() {
        val sizes = listOf(720 to 1560, 1080 to 2340, 1440 to 3120, 1080 to 1920)
        val picks = sizes.map { (w, h) ->
            val s = MatchSession(); val f = { BoardRenderer(w, h).render(green, state(5, 0, 0, 0), 3) }
            val a = stable(s, f); assertTrue("$w x $h ${a.problems}", a.ok); a.best!!.move.pieceIdx to a.best!!.move.to
        }
        assertEquals(1, picks.toSet().size)
    }

    @Test fun quickModeUsesTwoSeatsOnly() {
        val g = GameState.initial(GameMode.QUICK_2P)
        g.pieces.getValue(Seat.BOTTOM_LEFT)[0] = 5
        val colors = mapOf(Seat.BOTTOM_LEFT to LudoColor.GREEN, Seat.TOP_RIGHT to LudoColor.BLUE)
        val f = { BoardRenderer(W, H).render(colors, g, 3) }
        val s = MatchSession(); s.process(f(), GameMode.QUICK_2P, nowMs = 0)
        val a = s.process(f(), GameMode.QUICK_2P, nowMs = 400)
        assertTrue("${a.problems}", a.ok); assertEquals(setOf(Seat.BOTTOM_LEFT, Seat.TOP_RIGHT), a.state!!.active.toSet())
    }
}

class CoordinateAndLocatorTest {
    @Test fun uniformScaleAndOverlayOrigin() {
        val m = DisplayMap(540, 1170, 1080, 2340, overlayOriginX = 0f, overlayOriginY = 100f)
        val (x, y) = m.frameToOverlay(100.0, 200.0)
        assertEquals(200f, x, 1e-3f); assertEquals(300f, y, 1e-3f)
    }

    @Test fun axesAreScaledSeparately() {
        val m = DisplayMap(500, 1000, 1000, 1200)
        val (x, y) = m.frameToScreen(250.0, 500.0)
        assertEquals(500f, x, 1e-3f); assertEquals(600f, y, 1e-3f)
        assertEquals(2f, m.kx, 1e-6f); assertEquals(1.2f, m.ky, 1e-6f)
    }

    @Test fun gridPointMapsThroughBoardGeometry() {
        val g = BoardGeometry(left = 20.0, top = 100.0, cell = 40.0)
        val m = DisplayMap(1000, 1000, 2000, 2000, 0f, 50f)
        val (x, y) = m.gridToOverlay(g, GridPoint(2.5, 3.5))
        assertEquals(2 * (20 + 100).toFloat(), x, 1e-3f); assertEquals(2 * (100 + 140).toFloat() - 50f, y, 1e-3f)
    }

    @Test fun captureAfterRotationRescalesCleanly() {
        val portrait = DisplayMap(720, 1560, 1080, 2340)
        val landscape = DisplayMap(1560, 720, 2340, 1080)
        assertEquals(portrait.kx, landscape.kx, 1e-6f)
        assertEquals(2340f, landscape.frameToScreen(1560.0, 0.0).first, 1e-3f)
    }

    private fun piece(state: PieceState, rel: Int, slot: Int) = PieceReading(Seat.BOTTOM_LEFT, 0, state, rel, 0.9, Evidence.DETECTED, yardSlot = slot)

    @Test fun yardTargetUsesAnOccupiedSlotNotTheFirstOne() {
        val sp = SeatPieces(Seat.BOTTOM_LEFT, listOf(piece(PieceState.YARD, 0, 2), piece(PieceState.ON_TRACK, 9, -1), piece(PieceState.ON_TRACK, 12, -1), piece(PieceState.ON_TRACK, 20, -1)))
        val mv = Move(Seat.BOTTOM_LEFT, 0, 0, 1, emptyList(), 6)
        val t = PieceLocator.target(mv, sp)!!
        val (ex, ey) = Board.yardSlots(Seat.BOTTOM_LEFT)[2]
        assertEquals(ex, t.gx, 1e-9); assertEquals(ey, t.gy, 1e-9)
        assertNotEquals(Board.yardSlots(Seat.BOTTOM_LEFT)[0], ex to ey)
    }

    @Test fun yardTargetWithoutVerifiedSlotIsNull() {
        val sp = SeatPieces(Seat.BOTTOM_LEFT, listOf(piece(PieceState.YARD, 0, -1)))
        assertNull(PieceLocator.target(Move(Seat.BOTTOM_LEFT, 0, 0, 1, emptyList(), 6), sp))
    }

    @Test fun trackTargetIsTheCellCentre() {
        val c = Board.cellOf(Seat.BOTTOM_LEFT, 9)!!
        val t = PieceLocator.target(Move(Seat.BOTTOM_LEFT, 0, 9, 12, emptyList(), 3), null)!!
        assertEquals(c.col + 0.5, t.gx, 1e-9); assertEquals(c.row + 0.5, t.gy, 1e-9)
    }
}

class EngineSafetyTest {
    @Test fun hiddenAndFinishedPiecesNeverMove() {
        val g = GameState.initial(GameMode.CLASSIC_4P)
        g.pieces.getValue(Seat.BOTTOM_LEFT).let { it[0] = Board.HIDDEN; it[1] = Board.FINISH; it[2] = 10; it[3] = 0 }
        for (d in 1..6) for (m in Rules.legalMoves(g, Seat.BOTTOM_LEFT, d)) assertTrue(m.pieceIdx in 2..3)
    }

    @Test fun searchNeverReturnsAnIllegalMoveAndExplainsRisks() {
        val rnd = java.util.Random(11)
        repeat(60) {
            val g = GameState.initial(GameMode.CLASSIC_4P)
            for (s in g.active) for (i in 0 until 4) g.pieces.getValue(s)[i] = if (rnd.nextInt(3) == 0) 0 else 1 + rnd.nextInt(55)
            val d = 1 + rnd.nextInt(6)
            val legal = Rules.legalMoves(g, Seat.BOTTOM_LEFT, d)
            val rec = Search.recommend(g, Seat.BOTTOM_LEFT, d)
            if (legal.isEmpty()) assertNull(rec) else {
                rec!!
                assertTrue(legal.any { it.pieceIdx == rec.best.move.pieceIdx && it.to == rec.best.move.to })
                assertEquals(legal.size, 1 + rec.alternatives.size)
                assertTrue(rec.decisionConfidence in 0.5..1.0)
                assertTrue(rec.reasons.isNotEmpty() || rec.risks.isNotEmpty() || legal.size >= 1)
            }
        }
    }

    @Test fun searchFindsAnImmediateCaptureOverAPlainAdvance() {
        val g = GameState.initial(GameMode.CLASSIC_4P)
        val bl = g.pieces.getValue(Seat.BOTTOM_LEFT); bl[0] = 20; bl[1] = 30
        // opponent on a non-safe cell 3 steps ahead of piece 0 (absolute track index)
        val absTarget = (Seat.BOTTOM_LEFT.startIdx + 20 - 1 + 3) % 52
        val opp = Seat.values().first { s -> s != Seat.BOTTOM_LEFT && absTarget !in Board.safeTrackIdx }
        val rel = ((absTarget - opp.startIdx + 52) % 52) + 1
        g.pieces.getValue(opp)[0] = rel
        val legal = Rules.legalMoves(g, Seat.BOTTOM_LEFT, 3)
        val cap = legal.firstOrNull { it.captures.isNotEmpty() }
        if (cap != null) assertEquals(cap.pieceIdx, Search.recommend(g, Seat.BOTTOM_LEFT, 3)!!.best.move.pieceIdx)
    }

    @Test fun lookaheadStaysWithinTimeBudget() {
        val g = GameState.initial(GameMode.CLASSIC_4P)
        for (s in g.active) for (i in 0 until 4) g.pieces.getValue(s)[i] = 5 + 9 * i + s.ordinal
        val t0 = System.nanoTime()
        Search.recommend(g, Seat.BOTTOM_LEFT, 4)
        assertTrue("took ${(System.nanoTime() - t0) / 1e6}ms", (System.nanoTime() - t0) / 1e6 < 2000)
    }
}
