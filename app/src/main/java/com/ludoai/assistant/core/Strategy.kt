package com.ludoai.assistant.core

/** Reason codes are localised in the UI. */
enum class ReasonCode { CAPTURE, FINISH, ENTER, ENTER_HOME_COLUMN, SAFE_SQUARE, ESCAPE_DANGER, LOW_RISK, RISKY, PROGRESS, PRESSURE, STACK, PROTECT_ADVANCED, ONLY_MOVE }

data class Reason(val code: ReasonCode, val value: Double = 0.0)

data class ScoredMove(val move: Move, val score: Double, val reasons: List<Reason>, val risk: Double)

data class StrategyWeights(
    val progress: Double,
    val enter: Double,
    val capture: Double,
    val captureProgress: Double,
    val finish: Double,
    val homeColumn: Double,
    val safeSquare: Double,
    val risk: Double,
    val riskProgress: Double,
    val escape: Double,
    val pressure: Double,
    val stack: Double,
) {
    companion object {
        /** One opponent: threats are rare, captures decide the game. */
        val TWO_PLAYERS = StrategyWeights(
            progress = 1.0, enter = 12.0, capture = 30.0, captureProgress = 0.55, finish = 40.0, homeColumn = 14.0,
            safeSquare = 6.0, risk = 24.0, riskProgress = 0.35, escape = 1.0, pressure = 3.0, stack = 0.0,
        )

        /** Three opponents: a loose piece is hit far more often, so safety weighs more. */
        val FOUR_PLAYERS = StrategyWeights(
            progress = 1.0, enter = 14.0, capture = 24.0, captureProgress = 0.45, finish = 40.0, homeColumn = 16.0,
            safeSquare = 9.0, risk = 34.0, riskProgress = 0.5, escape = 1.0, pressure = 2.0, stack = 0.0,
        )

        fun forMode(mode: GameMode) = if (mode == GameMode.QUICK_2P) TWO_PLAYERS else FOUR_PLAYERS
    }
}

object Strategy {
    /** Probability that at least one opponent can land on track cell [idx] on its next turn. */
    fun hitProbability(state: GameState, idx: Int, seat: Seat, rules: RuleSet = RuleSet()): Double {
        if (rules.safeSquares && Board.isSafeTrack(idx)) return 0.0
        var survive = 1.0
        for (o in state.opponents(seat)) {
            val direct = HashSet<Int>()
            val viaSix = HashSet<Int>()
            for (r in state.pieces.getValue(o)) {
                if (r !in 1..Board.LAST_TRACK_REL) continue
                val a = Board.trackIdx(o, r)
                val d = ((idx - a) % Board.TRACK_LEN + Board.TRACK_LEN) % Board.TRACK_LEN
                if (d in 1..6 && r + d <= Board.LAST_TRACK_REL) direct.add(d)
                if (d in 7..12 && r + d <= Board.LAST_TRACK_REL) viaSix.add(d)
            }
            // yard pieces cannot hit unless the cell is their start (a safe square), so ignored
            val p = (direct.size / 6.0) + (if (viaSix.isNotEmpty()) 1.0 / 36.0 else 0.0)
            survive *= (1.0 - p.coerceAtMost(0.95))
        }
        return 1.0 - survive
    }

    private fun pieceValue(w: StrategyWeights, rel: Int) = w.risk + w.riskProgress * rel

    fun rank(
        state: GameState,
        seat: Seat,
        dice: Int,
        rules: RuleSet = RuleSet(),
        weights: StrategyWeights = StrategyWeights.forMode(state.mode),
    ): List<ScoredMove> {
        val moves = Rules.legalMoves(state, seat, dice, rules)
        val mine = state.pieces.getValue(seat)
        val outCount = mine.count { it in 1 until Board.FINISH }
        val scored = moves.map { m ->
            val reasons = ArrayList<Reason>()
            var s = 0.0
            // progress
            if (m.from == 0) {
                val e = weights.enter * (if (outCount < 2) 1.0 else 0.6)
                s += e; reasons += Reason(ReasonCode.ENTER, e)
            } else {
                s += weights.progress * dice; reasons += Reason(ReasonCode.PROGRESS, weights.progress * dice)
            }
            // captures
            if (m.captures.isNotEmpty()) {
                var c = 0.0
                for ((o, j) in m.captures) c += weights.capture + weights.captureProgress * state.pieces.getValue(o)[j]
                s += c; reasons += Reason(ReasonCode.CAPTURE, c)
            }
            if (m.to == Board.FINISH) { s += weights.finish; reasons += Reason(ReasonCode.FINISH, weights.finish) }
            else if (m.to >= Board.FIRST_HOME_REL && m.from < Board.FIRST_HOME_REL) {
                s += weights.homeColumn; reasons += Reason(ReasonCode.ENTER_HOME_COLUMN, weights.homeColumn)
            }
            // risk at destination
            var risk = 0.0
            if (m.to in 1..Board.LAST_TRACK_REL) {
                val idx = Board.trackIdx(seat, m.to)
                if (rules.safeSquares && Board.isSafeTrack(idx)) {
                    s += weights.safeSquare; reasons += Reason(ReasonCode.SAFE_SQUARE, weights.safeSquare)
                } else {
                    // after the move (captured pieces are gone)
                    val after = Rules.apply(state, m)
                    risk = hitProbability(after, idx, seat, rules)
                    // an own piece on the same cell shares the loss, count it once more
                    val pen = risk * pieceValue(weights, m.to)
                    s -= pen
                    reasons += if (risk >= 0.25) Reason(ReasonCode.RISKY, risk) else Reason(ReasonCode.LOW_RISK, risk)
                }
            }
            // leaving a dangerous cell
            if (m.from in 1..Board.LAST_TRACK_REL) {
                val fi = Board.trackIdx(seat, m.from)
                val rf = hitProbability(state, fi, seat, rules)
                if (rf > 0.0) {
                    val g = weights.escape * rf * pieceValue(weights, m.from)
                    s += g
                    reasons += if (m.from >= 26) Reason(ReasonCode.PROTECT_ADVANCED, rf) else Reason(ReasonCode.ESCAPE_DANGER, rf)
                }
            }
            // pressure: opponents within 6 steps ahead of the landing cell that I could hit next turn
            if (m.to in 1..Board.LAST_TRACK_REL) {
                val after = Rules.apply(state, m)
                val idx = Board.trackIdx(seat, m.to)
                var n = 0
                for (o in after.opponents(seat)) for (r in after.pieces.getValue(o)) {
                    if (r !in 1..Board.LAST_TRACK_REL) continue
                    val a = Board.trackIdx(o, r)
                    if (rules.safeSquares && Board.isSafeTrack(a)) continue
                    val d = ((a - idx) % Board.TRACK_LEN + Board.TRACK_LEN) % Board.TRACK_LEN
                    if (d in 1..6 && m.to + d <= Board.LAST_TRACK_REL) n++
                }
                if (n > 0) { val p = weights.pressure * n; s += p; reasons += Reason(ReasonCode.PRESSURE, n.toDouble()) }
                if (weights.stack != 0.0 && mine.withIndex().any { (i, r) -> i != m.pieceIdx && r == m.to }) {
                    s += weights.stack; reasons += Reason(ReasonCode.STACK, weights.stack)
                }
            }
            ScoredMove(m, s, reasons, risk)
        }.sortedByDescending { it.score }
        return if (scored.size == 1) listOf(scored[0].copy(reasons = scored[0].reasons + Reason(ReasonCode.ONLY_MOVE))) else scored
    }
}
