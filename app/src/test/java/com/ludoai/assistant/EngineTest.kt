package com.ludoai.assistant

import com.ludoai.assistant.core.*
import org.junit.Assert.*
import org.junit.Test

class BoardTest {
    @Test fun trackHas52UniqueCellsAndIsContiguous() {
        assertEquals(52, Board.track.size)
        assertEquals(52, Board.track.toSet().size)
        for (i in 0 until 52) {
            val a = Board.track[i]; val b = Board.track[(i + 1) % 52]
            assertEquals("gap between $i and next", 1, maxOf(Math.abs(a.col - b.col), Math.abs(a.row - b.row)))
        }
    }

    @Test fun startCellsAndSafeSquares() {
        assertEquals(Cell(1, 6), Board.track[Seat.TOP_LEFT.startIdx])
        assertEquals(Cell(8, 1), Board.track[Seat.TOP_RIGHT.startIdx])
        assertEquals(Cell(13, 8), Board.track[Seat.BOTTOM_RIGHT.startIdx])
        assertEquals(Cell(6, 13), Board.track[Seat.BOTTOM_LEFT.startIdx])
        assertEquals(8, Board.safeTrackIdx.size)
        val stars = setOf(Cell(6, 2), Cell(12, 6), Cell(8, 12), Cell(2, 8))
        assertTrue(stars.all { Board.trackIndexOf(it) in Board.safeTrackIdx })
    }

    @Test fun seatsAreClockwiseFromBottomLeft() {
        assertEquals(Seat.TOP_LEFT, Seat.BOTTOM_LEFT.next())
        assertEquals(Seat.TOP_RIGHT, Seat.TOP_LEFT.next())
        assertEquals(Seat.BOTTOM_RIGHT, Seat.TOP_RIGHT.next())
        assertEquals(Seat.BOTTOM_LEFT, Seat.BOTTOM_RIGHT.next())
    }

    @Test fun everyPieceWalksContiguouslyToTheCentre() {
        for (seat in Seat.values()) {
            var prev: Cell? = null
            for (rel in 1..56) {
                val c = Board.cellOf(seat, rel)!!
                if (prev != null) assertEquals("$seat rel $rel", 1, maxOf(Math.abs(c.col - prev.col), Math.abs(c.row - prev.row)))
                prev = c
            }
            // last home cell is adjacent to the centre
            assertTrue(Board.cellOf(seat, 56)!!.let { it.col in 6..8 && it.row in 6..8 || Board.homeOwner(it)?.first == seat })
        }
    }

    @Test fun skippedCellBeforeStart() {
        for (seat in Seat.values()) assertNull(Board.relOnTrack(seat, (seat.startIdx + 51) % 52))
        for (seat in Seat.values()) assertEquals(1, Board.relOnTrack(seat, seat.startIdx))
    }

    @Test fun homeColumnCellsAreOwnedAndNotOnTrack() {
        for (seat in Seat.values()) for (rel in 52..56) {
            val c = Board.cellOf(seat, rel)!!
            assertNull(Board.trackIndexOf(c))
            assertEquals(seat to rel, Board.homeOwner(c))
        }
    }
}

class RulesTest {
    private fun st(mode: GameMode, vararg set: Triple<Seat, Int, Int>): GameState {
        val g = GameState.initial(mode)
        for ((s, i, r) in set) g.pieces.getValue(s)[i] = r
        return g
    }

    @Test fun needsSixToEnter() {
        val g = GameState.initial(GameMode.CLASSIC_4P)
        assertTrue(Rules.legalMoves(g, Seat.BOTTOM_LEFT, 3).isEmpty())
        val m = Rules.legalMoves(g, Seat.BOTTOM_LEFT, 6)
        assertEquals(1, m.size)             // 4 identical yard pieces collapse to one option
        assertEquals(1, m[0].to)
    }

    @Test fun exactFinishRequired() {
        val g = st(GameMode.QUICK_2P, Triple(Seat.BOTTOM_LEFT, 0, 55))
        assertTrue(Rules.legalMoves(g, Seat.BOTTOM_LEFT, 3).isEmpty())
        assertEquals(57, Rules.legalMoves(g, Seat.BOTTOM_LEFT, 2).single().to)
        assertEquals(57, Rules.legalMoves(g, Seat.BOTTOM_LEFT, 3, RuleSet(exactFinish = false)).single().to)
    }

    @Test fun captureOnNormalCellButNotOnSafeCell() {
        // BL rel 10 -> idx 48.  Opponent TR piece at idx 48 -> rel (48-13)+1 = 36
        val g = st(GameMode.QUICK_2P, Triple(Seat.BOTTOM_LEFT, 0, 8), Triple(Seat.TOP_RIGHT, 0, 36))
        val m = Rules.legalMoves(g, Seat.BOTTOM_LEFT, 2).single()
        assertEquals(listOf(Seat.TOP_RIGHT to 0), m.captures)
        // star square idx 47 (rel 9 for BL) is safe -> no capture
        val g2 = st(GameMode.QUICK_2P, Triple(Seat.BOTTOM_LEFT, 0, 8), Triple(Seat.TOP_RIGHT, 0, 35))
        assertTrue(Rules.legalMoves(g2, Seat.BOTTOM_LEFT, 1).single().captures.isEmpty())
    }

    @Test fun wrapAroundIndexing() {
        // BL rel 20 -> idx (39+19)%52 = 6 ; TL piece rel 7 -> idx 6
        assertEquals(6, Board.trackIdx(Seat.BOTTOM_LEFT, 20))
        assertEquals(6, Board.trackIdx(Seat.TOP_LEFT, 7))
    }

    @Test fun homeColumnPieceCannotBeCaptured() {
        val g = st(GameMode.QUICK_2P, Triple(Seat.BOTTOM_LEFT, 0, 50), Triple(Seat.TOP_RIGHT, 0, 53))
        assertTrue(Rules.legalMoves(g, Seat.BOTTOM_LEFT, 3).single().captures.isEmpty())
    }
}

class StrategyTest {
    private fun st(mode: GameMode, vararg set: Triple<Seat, Int, Int>): GameState {
        val g = GameState.initial(mode)
        for ((s, i, r) in set) g.pieces.getValue(s)[i] = r
        return g
    }

    @Test fun capturePreferredOverFurthestPiece() {
        val g = st(GameMode.QUICK_2P,
            Triple(Seat.BOTTOM_LEFT, 0, 30), Triple(Seat.BOTTOM_LEFT, 1, 8),
            Triple(Seat.TOP_RIGHT, 0, 36)) // opponent sits at BL rel 10
        val best = Strategy.rank(g, Seat.BOTTOM_LEFT, 2).first()
        assertEquals(1, best.move.pieceIdx)
        assertTrue(best.reasons.any { it.code == ReasonCode.CAPTURE })
    }

    @Test fun notSimplyTheFurthestPiece() {
        // Lead piece would land right in front of an opponent, trailing piece reaches a safe square.
        // BL rel 20 (idx 6) +3 = rel 23 (idx 9); opponent TL piece at idx 5 (TL rel 6) threatens idx 9 (d=4).
        // BL rel 6 (idx 44) +3 -> rel 9 (idx 47) is a star square.
        val g = st(GameMode.CLASSIC_4P,
            Triple(Seat.BOTTOM_LEFT, 0, 20), Triple(Seat.BOTTOM_LEFT, 1, 6),
            Triple(Seat.TOP_LEFT, 0, 6))
        val best = Strategy.rank(g, Seat.BOTTOM_LEFT, 3).first()
        assertEquals(1, best.move.pieceIdx)
        assertTrue(best.reasons.any { it.code == ReasonCode.SAFE_SQUARE })
    }

    @Test fun escapesDangerWhenSameDistance() {
        // Both pieces move 5. Piece 0 sits 3 ahead of an opponent (threatened); piece 1 is far from everything.
        // BL rel 20 = idx 6.  TL piece at idx 3 = TL rel 4.
        val g = st(GameMode.CLASSIC_4P,
            Triple(Seat.BOTTOM_LEFT, 0, 20), Triple(Seat.BOTTOM_LEFT, 1, 40),
            Triple(Seat.TOP_LEFT, 0, 4))
        val r = Strategy.rank(g, Seat.BOTTOM_LEFT, 5)
        val best = r.first()
        assertEquals(0, best.move.pieceIdx)
        assertTrue(best.reasons.any { it.code == ReasonCode.ESCAPE_DANGER || it.code == ReasonCode.PROTECT_ADVANCED })
    }

    @Test fun finishingBeatsEverythingElseWhenAvailable() {
        val g = st(GameMode.QUICK_2P, Triple(Seat.BOTTOM_LEFT, 0, 55), Triple(Seat.BOTTOM_LEFT, 1, 10))
        assertEquals(0, Strategy.rank(g, Seat.BOTTOM_LEFT, 2).first().move.pieceIdx)
    }

    @Test fun enteringWithSixWhenNothingElseUseful() {
        val g = st(GameMode.QUICK_2P, Triple(Seat.BOTTOM_LEFT, 0, 12))
        assertEquals(2, Strategy.rank(g, Seat.BOTTOM_LEFT, 6).size)
    }

    @Test fun weightsDifferByMode() {
        val g2 = StrategyWeights.forMode(GameMode.QUICK_2P)
        val g4 = StrategyWeights.forMode(GameMode.CLASSIC_4P)
        assertTrue(g4.risk > g2.risk && g4.safeSquare > g2.safeSquare && g2.capture > g4.capture)
    }

    @Test fun hitProbabilityScalesWithOpponents() {
        // cell idx 9, TL piece at idx 5 (d=4) and TR piece at idx 8 (d=1): 4P has two threats, 2P (TR only) one.
        val three = st(GameMode.CLASSIC_4P, Triple(Seat.TOP_LEFT, 0, 6), Triple(Seat.TOP_RIGHT, 0, 48))
        val p4 = Strategy.hitProbability(three, 9, Seat.BOTTOM_LEFT)
        val two = st(GameMode.QUICK_2P, Triple(Seat.TOP_RIGHT, 0, 48))
        val p2 = Strategy.hitProbability(two, 9, Seat.BOTTOM_LEFT)
        assertTrue("p4=$p4 p2=$p2", p4 > p2 && p2 > 0.15)
    }

    @Test fun safeCellHasZeroRisk() {
        val g = st(GameMode.CLASSIC_4P, Triple(Seat.TOP_LEFT, 0, 6))
        assertEquals(0.0, Strategy.hitProbability(g, 8, Seat.BOTTOM_LEFT), 0.0)
    }
}
