package com.ludoai.assistant.core

enum class GameMode(val players: Int) { QUICK_2P(2), CLASSIC_4P(4) }

/** Rule switches. Defaults follow the common Yalla Ludo rules; all can be changed in settings. */
data class RuleSet(
    val sixToEnter: Boolean = true,
    val exactFinish: Boolean = true,
    val safeSquares: Boolean = true,
)

/** pieces[seat] = 4 rel positions. Only [active] seats play. */
class GameState(
    val mode: GameMode,
    val active: List<Seat>,
    val pieces: Map<Seat, IntArray>,
) {
    fun copyWith(seat: Seat, idx: Int, rel: Int, removed: List<Pair<Seat, Int>> = emptyList()): GameState {
        val np = pieces.mapValues { it.value.clone() }
        np.getValue(seat)[idx] = rel
        for ((s, i) in removed) np.getValue(s)[i] = 0
        return GameState(mode, active, np)
    }

    fun opponents(seat: Seat) = active.filter { it != seat }

    companion object {
        fun activeSeats(mode: GameMode): List<Seat> = when (mode) {
            GameMode.CLASSIC_4P -> Seat.values().toList()
            GameMode.QUICK_2P -> listOf(Seat.BOTTOM_LEFT, Seat.TOP_RIGHT)
        }

        fun initial(mode: GameMode): GameState {
            val a = activeSeats(mode)
            return GameState(mode, a, a.associateWith { IntArray(Board.PIECES) })
        }
    }
}

data class Move(
    val seat: Seat,
    val pieceIdx: Int,
    val from: Int,
    val to: Int,
    val captures: List<Pair<Seat, Int>>,
    val diceValue: Int,
)

object Rules {
    fun legalMoves(state: GameState, seat: Seat, dice: Int, rules: RuleSet = RuleSet()): List<Move> {
        require(dice in 1..6)
        val mine = state.pieces.getValue(seat)
        val out = ArrayList<Move>()
        for (i in 0 until Board.PIECES) {
            val from = mine[i]
            if (from == Board.FINISH) continue
            val to: Int = if (from == 0) {
                if (rules.sixToEnter && dice != 6) continue
                1
            } else {
                val t = from + dice
                if (t > Board.FINISH && rules.exactFinish) continue
                minOf(t, Board.FINISH)
            }
            out.add(Move(seat, i, from, to, captures(state, seat, to, rules), dice))
        }
        // pieces at the same spot are interchangeable: keep only the first
        return out.distinctBy { it.from to it.to }
    }

    /** Opponent pieces sent home when [seat] lands on rel [to]. */
    fun captures(state: GameState, seat: Seat, to: Int, rules: RuleSet = RuleSet()): List<Pair<Seat, Int>> {
        if (to !in 1..Board.LAST_TRACK_REL) return emptyList()
        val idx = Board.trackIdx(seat, to)
        if (rules.safeSquares && Board.isSafeTrack(idx)) return emptyList()
        val res = ArrayList<Pair<Seat, Int>>()
        for (o in state.opponents(seat)) {
            val p = state.pieces.getValue(o)
            for (j in p.indices) {
                val r = p[j]
                if (r in 1..Board.LAST_TRACK_REL && Board.trackIdx(o, r) == idx) res.add(o to j)
            }
        }
        return res
    }

    fun apply(state: GameState, m: Move): GameState = state.copyWith(m.seat, m.pieceIdx, m.to, m.captures)
}
