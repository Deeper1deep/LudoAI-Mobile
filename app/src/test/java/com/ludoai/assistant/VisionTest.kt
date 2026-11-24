package com.ludoai.assistant

import com.ludoai.assistant.core.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class RealScreenshotTest {
    private val frame = Fixtures.load("real_4p_green_bl.rgb.gz")

    @Test fun realBoardIsFoundAndUserColourIsDetectedFromBottomLeft() {
        val a = Analyzer.analyze(frame, GameMode.CLASSIC_4P)
        val bd = a.board!!
        assertEquals(1.0, bd.confidence, 0.0)
        assertEquals(LudoColor.GREEN, bd.seatColors[Seat.BOTTOM_LEFT])
        assertEquals(LudoColor.YELLOW, bd.seatColors[Seat.TOP_LEFT])
        assertEquals(LudoColor.BLUE, bd.seatColors[Seat.TOP_RIGHT])
        assertEquals(LudoColor.RED, bd.seatColors[Seat.BOTTOM_RIGHT])
        assertEquals(47.1, bd.geometry.cell, 1.5)
        assertEquals(LudoColor.GREEN, a.userColor)
    }

    @Test fun realScreenshotAllPiecesInYardsAndNoFalsePiecesOnBoard() {
        val a = Analyzer.analyze(frame, GameMode.CLASSIC_4P)
        for (s in Seat.values()) {
            val c = a.counts.getValue(s)
            assertEquals("$s yard", 4, c.yard); assertEquals("$s board", 0, c.onBoard); assertEquals("$s fin", 0, c.finished)
        }
    }

    @Test fun realScreenshotWithoutVisibleDieAsksForNewCapture() {
        val a = Analyzer.analyze(frame, GameMode.CLASSIC_4P)
        assertTrue(a.needsRecapture); assertNull(a.best); assertTrue("DICE_NOT_FOUND" in a.problems)
    }

    @Test fun manualDiceGivesRecommendationOnRealBoard() {
        val six = Analyzer.analyze(frame, GameMode.CLASSIC_4P, manualDice = 6)
        assertTrue(six.ok); assertEquals(1, six.best!!.move.to)       // only legal move: bring a piece out
        val three = Analyzer.analyze(frame, GameMode.CLASSIC_4P, manualDice = 3)
        assertTrue(three.ok); assertTrue(three.ranked.isEmpty()) // nothing can move
    }
}

class SyntheticVisionTest {
    private val perms = listOf(
        listOf(LudoColor.GREEN, LudoColor.YELLOW, LudoColor.BLUE, LudoColor.RED),   // like the real game (BL, TL, TR, BR)
        listOf(LudoColor.RED, LudoColor.GREEN, LudoColor.YELLOW, LudoColor.BLUE),
        listOf(LudoColor.YELLOW, LudoColor.BLUE, LudoColor.RED, LudoColor.GREEN),
        listOf(LudoColor.BLUE, LudoColor.RED, LudoColor.GREEN, LudoColor.YELLOW),
    )
    private val sizes = listOf(720 to 1560, 1080 to 2340, 1440 to 3120, 1080 to 1920, 900 to 1600)

    private fun randomState(rnd: Random, mode: GameMode): GameState {
        val g = GameState.initial(mode)
        val usedCells = HashSet<Cell>()   // one piece per cell: stacks are not detected (documented limitation)
        for (s in g.active) {
            for (i in 0 until 4) {
                val r = when (rnd.nextInt(5)) { 0, 1 -> 0; 2 -> rnd.nextInt(52, 57); else -> rnd.nextInt(1, 52) }
                if (r != 0 && !usedCells.add(Board.cellOf(s, r)!!)) continue
                g.pieces.getValue(s)[i] = r
            }
        }
        return g
    }

    private fun colours(p: List<LudoColor>) = mapOf(Seat.BOTTOM_LEFT to p[0], Seat.TOP_LEFT to p[1], Seat.TOP_RIGHT to p[2], Seat.BOTTOM_RIGHT to p[3])

    @Test fun userColourIsDynamicForEveryColourAssignment() {
        for (p in perms) for ((w, h) in sizes) {
            val f = BoardRenderer(w, h).render(colours(p), GameState.initial(GameMode.CLASSIC_4P), 4)
            val a = Analyzer.analyze(f, GameMode.CLASSIC_4P)
            assertEquals("$p ${w}x$h", p[0], a.userColor)
            assertEquals(p[1], a.board!!.seatColors[Seat.TOP_LEFT])
            assertTrue("conf ${a.confidence} $p ${w}x$h ${a.problems}", a.ok)
        }
    }

    @Test fun diceValuesOneToSixAreRead() {
        for (v in 1..6) for ((w, h) in sizes) {
            val f = BoardRenderer(w, h).render(colours(perms[0]), GameState.initial(GameMode.CLASSIC_4P), v)
            val a = Analyzer.analyze(f, GameMode.CLASSIC_4P)
            assertEquals("dice $v ${w}x$h ${a.problems}", v, a.dice?.value)
        }
    }

    @Test fun piecePositionsAreRecoveredFor4PlayersAcrossResolutions() {
        val rnd = Random(7)
        repeat(12) { n ->
            val (w, h) = sizes[n % sizes.size]; val p = perms[n % perms.size]
            val st = randomState(rnd, GameMode.CLASSIC_4P)
            val dice = rnd.nextInt(1, 7)
            val a = Analyzer.analyze(BoardRenderer(w, h).render(colours(p), st, dice), GameMode.CLASSIC_4P, manualDice = dice)
            val got = a.state!!
            for (s in Seat.values()) {
                val want = st.pieces.getValue(s).filter { it in 1..56 }.sorted()
                val have = got.pieces.getValue(s).filter { it in 1..56 }.sorted()
                assertEquals("run $n seat $s ${w}x$h ${a.problems}", want, have)
                assertEquals(st.pieces.getValue(s).count { it == 0 }, a.counts.getValue(s).yard)
            }
            val truth = Strategy.rank(got, Seat.BOTTOM_LEFT, dice)
            // the ranking now includes lookahead, so the ORDER may differ from the greedy one; the set of legal moves must not
            assertEquals(truth.map { it.move.pieceIdx to it.move.to }.toSet(), a.ranked.map { it.move.pieceIdx to it.move.to }.toSet())
            assertTrue("best must be legal", a.best == null || truth.any { it.move.pieceIdx == a.best!!.move.pieceIdx && it.move.to == a.best!!.move.to })
        }
    }

    @Test fun twoPlayerModeUsesBottomLeftAndTopRight() {
        val rnd = Random(3)
        repeat(6) { n ->
            val p = perms[n % 4]
            val st = randomState(rnd, GameMode.QUICK_2P)
            val colors = mapOf(Seat.BOTTOM_LEFT to p[0], Seat.TOP_RIGHT to p[2])
            val a = Analyzer.analyze(BoardRenderer(1080, 2340).render(colors, st, 5), GameMode.QUICK_2P)
            assertEquals(p[0], a.userColor)
            assertEquals(listOf(Seat.BOTTOM_LEFT, Seat.TOP_RIGHT), a.state!!.active)
            for (s in st.active) assertEquals(st.pieces.getValue(s).filter { it in 1..56 }.sorted(), a.state!!.pieces.getValue(s).filter { it in 1..56 }.sorted())
            assertEquals(5, a.dice?.value)
        }
    }

    @Test fun missingDiceOrBlankScreenRequestsNewCapture() {
        val st = GameState.initial(GameMode.CLASSIC_4P)
        val noDice = Analyzer.analyze(BoardRenderer(1080, 2340).render(colours(perms[0]), st, null), GameMode.CLASSIC_4P)
        assertTrue(noDice.needsRecapture); assertNull(noDice.best)
        val blank = Analyzer.analyze(Frame(1080, 2340, IntArray(1080 * 2340) { 0xFF202020.toInt() }), GameMode.CLASSIC_4P)
        assertTrue(blank.needsRecapture); assertNull(blank.board)
    }
}
