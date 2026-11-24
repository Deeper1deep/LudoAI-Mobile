package com.ludoai.assistant.core

/** Reason codes are localised in the UI. */
enum class ReasonCode { CAPTURE, FINISH, ENTER, ENTER_HOME_COLUMN, SAFE_SQUARE, ESCAPE_DANGER, LOW_RISK, RISKY, PROGRESS, PRESSURE, STACK, PROTECT_ADVANCED, ONLY_MOVE }

data class Reason(val code: ReasonCode, val value: Double = 0.0)

/**
 * [riskAdj] is the part of [score] that comes from landing/escape risk (a first-order estimate);
 * the look-ahead replaces it with a simulated one.
 */
data class ScoredMove(val move: Move, val score: Double, val reasons: List<Reason>, val risk: Double, val riskAdj: Double = 0.0)

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
            val p = (direct.size / 6.0) + (if (rules.extraTurnOnSix) viaSix.size / 36.0 else 0.0)
            survive *= (1.0 - p.coerceAtMost(0.95))
        }
        return 1.0 - survive
    }

    internal fun pieceValue(w: StrategyWeights, rel: Int) = w.risk + w.riskProgress * rel

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
            var riskAdj = 0.0
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
                    s -= pen; riskAdj -= pen
                    reasons += if (risk >= 0.25) Reason(ReasonCode.RISKY, risk) else Reason(ReasonCode.LOW_RISK, risk)
                }
            }
            // leaving a dangerous cell
            if (m.from in 1..Board.LAST_TRACK_REL) {
                val fi = Board.trackIdx(seat, m.from)
                val rf = hitProbability(state, fi, seat, rules)
                if (rf > 0.0) {
                    val g = weights.escape * rf * pieceValue(weights, m.from)
                    s += g; riskAdj += g
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
            ScoredMove(m, s, reasons, risk, riskAdj)
        }.sortedByDescending { it.score }
        return if (scored.size == 1) listOf(scored[0].copy(reasons = scored[0].reasons + Reason(ReasonCode.ONLY_MOVE))) else scored
    }
}


enum class RiskCode { MOVED_PIECE_CAPTURED, ANY_PIECE_CAPTURED, LANDS_ON_DANGER }

/** [probability] is the chance within the next round of opponent turns. */
data class Risk(val code: RiskCode, val probability: Double)

/** Strength of the search, tuned per game mode. NOT tuned on real games: weights are heuristics. */
data class SearchConfig(
    val lookahead: Boolean = true,
    /** Weight of the expected best follow-up of the user's next roll. */
    val opportunityWeight: Double = 0.4,
    /** Hard time limit for the whole search; when exceeded the engine falls back to the immediate evaluation. */
    val budgetMs: Long = 120,
) {
    companion object {
        fun forMode(@Suppress("UNUSED_PARAMETER") mode: GameMode) = SearchConfig()
    }
}

data class Recommendation(
    val best: ScoredMove,
    val alternatives: List<ScoredMove>,
    /** Final strategic score of [best] (immediate evaluation + simulated outcome). */
    val score: Double,
    /** How clear-cut the decision is (0..1): 1 when it is the only legal move, lower when alternatives score nearly the same. */
    val decisionConfidence: Double,
    val reasons: List<Reason>,
    val risks: List<Risk>,
    /** 1 = immediate evaluation only, 2 = opponents' replies simulated, 3 = plus the user's next roll. */
    val searchDepth: Int,
)

/**
 * Expectimax-style look-ahead:
 *   my move  ->  every opponent in turn order rolls (uniform 1..6, no assumptions about future dice)
 *   and plays a greedy policy (capture > finish > enter > safe > advance)  ->  my next roll (expectation over 1..6).
 * Cost is bounded by [SearchConfig.budgetMs]; on timeout the result silently degrades to depth 1.
 */
object Search {
    private class Acc(val me: Seat, val moverIdx: Int) {
        var loss = 0.0           // expected value of my pieces captured
        var moverCaptured = 0.0
        var anyCaptured = 0.0
        var opportunity = 0.0    // expected best follow-up
    }

    private fun quick(state: GameState, m: Move): Double {
        var s = (m.to - m.from).toDouble().coerceAtMost(12.0)
        for ((o, j) in m.captures) s += 40.0 + 0.6 * state.pieces.getValue(o)[j]
        if (m.to == Board.FINISH) s += 60.0
        else if (m.to >= Board.FIRST_HOME_REL && m.from < Board.FIRST_HOME_REL) s += 20.0
        if (m.from == 0) s += 25.0
        if (m.to in 1..Board.LAST_TRACK_REL && Board.isSafeTrack(Board.trackIdx(m.seat, m.to))) s += 8.0
        return s
    }

    private fun pick(state: GameState, moves: List<Move>): Move = moves.maxByOrNull { quick(state, it) }!!

    private fun nextOpportunity(state: GameState, me: Seat, rules: RuleSet): Double {
        var sum = 0.0
        for (d in 1..6) {
            val mv = Rules.legalMoves(state, me, d, rules)
            sum += if (mv.isEmpty()) 0.0 else mv.maxOf { quick(state, it) }
        }
        return sum / 6.0
    }

    private fun recurse(
        state: GameState, order: List<Seat>, idx: Int, prob: Double, acc: Acc, rules: RuleSet, w: StrategyWeights,
        deadline: Long, capturedMine: BooleanArray, withOpportunity: Boolean,
    ): Boolean {
        if (System.nanoTime() > deadline) return false
        if (idx == order.size) {
            if (withOpportunity) acc.opportunity += prob * nextOpportunity(state, acc.me, rules)
            if (capturedMine[acc.moverIdx]) acc.moverCaptured += prob
            if (capturedMine.any { it }) acc.anyCaptured += prob
            return true
        }
        val opp = order[idx]
        for (d in 1..6) {
            val moves = Rules.legalMoves(state, opp, d, rules)
            if (moves.isEmpty()) {
                if (!recurse(state, order, idx + 1, prob / 6.0, acc, rules, w, deadline, capturedMine, withOpportunity)) return false
                continue
            }
            val mv = pick(state, moves)
            val hit = mv.captures.filter { it.first == acc.me }
            val before = state.pieces.getValue(acc.me)
            var lossHere = 0.0
            val newlyCaptured = ArrayList<Int>()
            for ((_, j) in hit) if (!capturedMine[j]) { capturedMine[j] = true; newlyCaptured += j; lossHere += Strategy.pieceValue(w, before[j]) }
            acc.loss += prob / 6.0 * lossHere
            val ns = Rules.apply(state, mv)
            val ok = recurse(ns, order, idx + 1, prob / 6.0, acc, rules, w, deadline, capturedMine, withOpportunity)
            for (j in newlyCaptured) capturedMine[j] = false
            if (!ok) return false
        }
        return true
    }

    /** Returns null when the budget ran out. */
    private fun simulate(state: GameState, seat: Seat, m: Move, rules: RuleSet, w: StrategyWeights, cfg: SearchConfig, deadline: Long): Acc? {
        val after = Rules.apply(state, m)
        // opponents act in turn order after me
        val order = ArrayList<Seat>()
        var s = seat.next()
        repeat(4) { if (s != seat && s in after.active) order += s; s = s.next() }
        val acc = Acc(seat, m.pieceIdx)
        val ok = recurse(after, order, 0, 1.0, acc, rules, w, deadline, BooleanArray(Board.PIECES), cfg.opportunityWeight > 0.0)
        return if (ok) acc else null
    }

    fun recommend(
        state: GameState,
        seat: Seat,
        dice: Int,
        rules: RuleSet = RuleSet(),
        weights: StrategyWeights = StrategyWeights.forMode(state.mode),
        cfg: SearchConfig = SearchConfig.forMode(state.mode),
    ): Recommendation? {
        val ranked = Strategy.rank(state, seat, dice, rules, weights)
        if (ranked.isEmpty()) return null
        val deadline = System.nanoTime() + cfg.budgetMs * 1_000_000L
        var depth = 1
        var finals: List<Triple<ScoredMove, Double, Acc?>> = ranked.map { Triple(it, it.score, null) }
        if (cfg.lookahead) {
            val sims = ranked.map { sm -> simulate(state, seat, sm.move, rules, weights, cfg, deadline) }
            if (sims.all { it != null }) {
                depth = if (cfg.opportunityWeight > 0.0) 3 else 2
                finals = ranked.mapIndexed { i, sm ->
                    val a = sims[i]!!
                    Triple(sm, sm.score - sm.riskAdj - a.loss + cfg.opportunityWeight * a.opportunity, a)
                }
            }
        }
        val sorted = finals.sortedByDescending { it.second }
        val (best, bestScore, acc) = sorted.first()
        val margin = if (sorted.size > 1) bestScore - sorted[1].second else Double.POSITIVE_INFINITY
        val decision = if (sorted.size == 1) 1.0 else (0.5 + 0.5 * Math.tanh(margin / 10.0)).coerceIn(0.5, 1.0)
        val risks = ArrayList<Risk>()
        if (best.risk > 0.0) risks += Risk(RiskCode.LANDS_ON_DANGER, best.risk)
        if (acc != null) {
            if (acc.moverCaptured > 0.005) risks += Risk(RiskCode.MOVED_PIECE_CAPTURED, acc.moverCaptured.coerceAtMost(1.0))
            if (acc.anyCaptured > acc.moverCaptured + 0.005) risks += Risk(RiskCode.ANY_PIECE_CAPTURED, acc.anyCaptured.coerceAtMost(1.0))
        }
        return Recommendation(
            best = best,
            alternatives = sorted.drop(1).map { it.first },
            score = bestScore,
            decisionConfidence = decision,
            reasons = best.reasons,
            risks = risks,
            searchDepth = depth,
        )
    }
}
