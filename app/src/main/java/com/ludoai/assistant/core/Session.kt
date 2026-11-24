package com.ludoai.assistant.core

import kotlin.math.abs

enum class DiceState { NONE, ANIMATING, SETTLING, STABLE }

/**
 * [rollId] changes whenever a new roll is confirmed. [stale] means this roll was already used (the board changed after a
 * recommendation was shown for it) and the die has not been re-rolled since.
 */
data class DiceStatus(
    val state: DiceState,
    val reading: DiceReading?,
    val rollId: Int,
    val isNewRoll: Boolean,
    val stale: Boolean,
    val stableFrames: Int,
)

/**
 * Turns a stream of per-frame dice scans into confirmed rolls.
 *  - a value must be seen unchanged for [framesRequired] frames and [minStableMs] before it counts (animation filter);
 *  - a roll is NEW when a confirmed value appears after the die vanished / was unreadable / showed another value;
 *  - the same value with no gap in between is the SAME roll, never counted twice.
 */
class DiceTracker(private val framesRequired: Int = 2, private val minStableMs: Long = 250) {
    var rollId = 0; private set
    private var cand: DiceReading? = null
    private var candFrames = 0
    private var candSince = 0L
    private var last: DiceReading? = null
    private var gap = true
    private var consumed = -1
    private var lastStatus = DiceStatus(DiceState.NONE, null, 0, false, false, 0)

    fun update(scan: DiceScan?, now: Long): DiceStatus {
        val readings = scan?.readings.orEmpty()
        if (readings.isEmpty()) {
            cand = null; candFrames = 0; gap = true
            return remember(DiceStatus(if ((scan?.unreadable ?: 0) > 0) DiceState.ANIMATING else DiceState.NONE, null, rollId, false, false, 0))
        }
        // the user's die wins when several are visible; the turn logic judges the others
        val r = readings.firstOrNull { it.seat == Seat.BOTTOM_LEFT } ?: readings.maxByOrNull { it.confidence }!!
        val same = cand != null && cand!!.value == r.value && cand!!.seat == r.seat
        if (same) candFrames++ else { cand = r; candFrames = 1; candSince = now }
        val stable = candFrames >= framesRequired && now - candSince >= minStableMs
        if (!stable) return remember(DiceStatus(DiceState.SETTLING, r, rollId, false, false, candFrames))
        val sameAsLast = last != null && last!!.value == r.value && last!!.seat == r.seat
        var isNew = false
        if (!sameAsLast || gap) { rollId++; isNew = true; gap = false; last = r }
        return remember(DiceStatus(DiceState.STABLE, r, rollId, isNew, rollId == consumed, candFrames))
    }

    /** The current roll has been used (the player moved). Until a new roll is seen, its value must not be reused. */
    fun markConsumed() { consumed = rollId; lastStatus = lastStatus.copy(stale = lastStatus.state == DiceState.STABLE) }

    fun status() = lastStatus
    private fun remember(s: DiceStatus): DiceStatus { lastStatus = s; return s }
}

data class GridPoint(val gx: Double, val gy: Double)

data class Analysis(
    /** True only when a recommendation (or an honest "no legal move") can be trusted. */
    val ok: Boolean,
    /** Recognition confidence (board, pieces, dice). Independent of how clear-cut the decision is. */
    val confidence: Double,
    /** Every code noted for this frame, blocking or not. */
    val problems: List<String>,
    /** The codes that stopped a recommendation. */
    val blockers: List<String>,
    /** Informational codes (recalibrated, manual dice active, ...). */
    val notices: List<String>,
    val board: BoardDetection?,
    val userColor: LudoColor?,
    val dice: DiceReading?,
    val diceStatus: DiceStatus?,
    val turnSeat: Seat?,
    val manualDiceActive: Boolean,
    val state: GameState?,
    val pieces: Map<Seat, SeatPieces>,
    val counts: Map<Seat, SeatCounts>,
    val recommendation: Recommendation?,
    /** Where the recommended piece actually is, in board grid units; null when it cannot be located reliably. */
    val target: GridPoint?,
    val matchId: Int,
    /** Dice was seen but is not yet confirmed: another frame is needed. */
    val needsConfirmation: Boolean = false,
) {
    val ranked: List<ScoredMove> get() = recommendation?.let { listOf(it.best) + it.alternatives } ?: emptyList()
    val best: ScoredMove? get() = recommendation?.best
    val needsRecapture get() = !ok
    /** Recognition x decision confidence, for display. */
    val overallConfidence: Double get() = confidence * (recommendation?.decisionConfidence ?: 1.0)

    companion object {
        fun failed(problems: List<String>, matchId: Int = 0, notices: List<String> = emptyList()) = Analysis(
            false, 0.0, problems, problems, notices, null, null, null, null, null, false, null, emptyMap(), emptyMap(), null, null, matchId,
        )
    }
}

/**
 * One match. Keeps the memory that makes frame-by-frame analysis honest: which pieces were where, which dice roll is
 * current/used, whose turn it is, and the user's one-shot manual dice. Recalibrates itself when the colours or the board
 * layout change.
 */
class MatchSession(
    private val search: SearchConfig? = null,
    framesRequired: Int = 2,
    minStableMs: Long = 250,
) {
    companion object {
        const val MIN_CONFIDENCE = 0.6
        /** A manual dice entry is dropped after this long even if nothing else changed. */
        const val MANUAL_TTL_MS = 120_000L
    }

    private class ManualDice(val value: Int, val rollId: Int, val turnId: Int, val sig: String?, val atMs: Long)
    private class Shown(val rollId: Int, val sig: String)
    private class Calib(val w: Int, val h: Int, val colors: Map<Seat, LudoColor>, val g: BoardGeometry)

    private val mem = HashMap<Seat, SeatMemory>()
    private var dice = DiceTracker(framesRequired, minStableMs)
    private val framesRequired = framesRequired
    private val minStableMs = minStableMs
    private var turnId = 0
    private var lastTurnSeat: Seat? = null
    private var calib: Calib? = null
    private var manual: ManualDice? = null
    private var shown: Shown? = null
    private var lastUserSig: String? = null
    private var lastObs: FrameObservation? = null
    var matchId = 1; private set

    // ---- user actions ----

    /** Manual dice for the CURRENT analysis only: cleared by a new roll, a turn change, a move, or after [MANUAL_TTL_MS]. */
    fun setManualDice(value: Int, nowMs: Long) {
        require(value in 1..6)
        manual = ManualDice(value, dice.rollId, turnId, lastUserSig, nowMs)
    }

    fun clearManualDice() { manual = null }
    val manualDiceActive get() = manual != null

    /** The user states how many of their own pieces have already finished (cannot be read from the screen reliably). */
    fun declareUserFinished(count: Int?) { mem.getOrPut(Seat.BOTTOM_LEFT) { SeatMemory() }.declaredFinished = count?.coerceIn(0, Board.PIECES) }

    fun declaredUserFinished(): Int? = mem[Seat.BOTTOM_LEFT]?.declaredFinished

    /** Forget everything about the current match (new match, or the user pressed reset). */
    fun resetMatch() {
        mem.clear(); dice = DiceTracker(framesRequired, minStableMs); turnId = 0; lastTurnSeat = null
        manual = null; shown = null; lastUserSig = null; matchId++
    }

    // ---- analysis ----

    fun process(frame: Frame, mode: GameMode, rules: RuleSet = RuleSet(), nowMs: Long = 0L): Analysis {
        noteFrameSize(frame.w, frame.h)
        val obs = Observer.observe(frame, mode)
        lastObs = obs
        return evaluate(obs, mode, rules, nowMs, repeat = false)
    }

    /** Re-judges the last frame without advancing any counters (used after a manual dice pick). */
    fun reevaluate(mode: GameMode, rules: RuleSet = RuleSet(), nowMs: Long = 0L): Analysis? {
        val obs = lastObs ?: return null
        return evaluate(obs, mode, rules, nowMs, repeat = true)
    }

    private fun sig(p: SeatPieces?): String? = if (p != null && p.complete) p.pieces.map { it.rel }.sorted().joinToString(",") else null

    private fun calibrationChange(old: Calib, new: Calib): String? {
        for ((s, c) in new.colors) { val o = old.colors[s]; if (o != null && o != c) return "NEW_MATCH_COLOURS" }
        if (old.w != new.w || old.h != new.h) return "LAYOUT_CHANGED"
        val cell = old.g.cell
        if (abs(new.g.cell - cell) / cell > 0.06 || abs(new.g.left - old.g.left) > 0.6 * cell || abs(new.g.top - old.g.top) > 0.6 * cell) return "LAYOUT_CHANGED"
        return null
    }

    private fun evaluate(obs: FrameObservation, mode: GameMode, rules: RuleSet, now: Long, repeat: Boolean): Analysis {
        val bd = obs.board ?: return Analysis.failed(obs.problems, matchId)
        val problems = ArrayList<String>(obs.problems)
        val blockers = ArrayList<String>()
        val notices = ArrayList<String>()

        // ---- recalibration: new colours or a changed layout start a new match ----
        val newCalib = Calib(lastFrameW, lastFrameH, obs.seatColors, bd.geometry)
        if (!repeat) {
            val old = calib
            val why = if (old != null) calibrationChange(old, newCalib) else null
            if (why != null) { resetMatch(); notices += "RECALIBRATED"; notices += why }
            calib = newCalib
        }

        var conf = obs.confidence
        val colors = obs.seatColors
        val userColor = colors[Seat.BOTTOM_LEFT]
        val active = GameState.activeSeats(mode)
        if (userColor == null) blockers += "SEAT_COLOR_UNKNOWN_BOTTOM_LEFT"
        if ("DUPLICATE_SEAT_COLORS" in problems) blockers += "DUPLICATE_SEAT_COLORS"
        if ("INCONSISTENT_PIECES" in problems) blockers += "INCONSISTENT_PIECES"

        // ---- pieces ----
        val pieces = HashMap<Seat, SeatPieces>()
        for ((seat, so) in obs.seats) {
            val m = mem.getOrPut(seat) { SeatMemory() }
            val sp = Reconciler.reconcile(so, m)
            pieces[seat] = sp
            problems += sp.problems
            if (!repeat && "INCONSISTENT_PIECES" !in obs.problems && sp.problems.none { it.startsWith("TOO_MANY") }) Reconciler.remember(m, sp, so.trusted)
        }
        for (seat in active) {
            if (pieces[seat]?.problems?.any { it.startsWith("TOO_MANY") } == true) { blockers += "TOO_MANY_PIECES_$seat"; conf *= 0.4 }
        }
        // every active seat back in its yard after progress: a new match began (same colours and layout)
        if (!repeat && mem.isNotEmpty()) {
            val allYard = active.all { s -> pieces[s]?.let { it.complete && it.pieces.all { p -> p.state == PieceState.YARD } } == true }
            val hadProgress = lastUserSig != null && lastUserSig != "0,0,0,0"
            if (allYard && hadProgress) { resetMatch(); notices += "RECALIBRATED"; notices += "NEW_MATCH"; calib = newCalib
                for ((seat, so) in obs.seats) { val m = mem.getOrPut(seat) { SeatMemory() }; Reconciler.remember(m, Reconciler.reconcile(so, m), so.trusted) } }
        }
        val user = pieces[Seat.BOTTOM_LEFT]
        val userSig = sig(user)
        if (user == null || !user.complete) { blockers += "PIECES_UNKNOWN_USER" } else conf *= user.minConfidence
        if (active.filter { it != Seat.BOTTOM_LEFT }.any { pieces[it]?.complete == false }) { notices += "HIDDEN_OPPONENT_PIECES"; conf *= 0.9 }

        // ---- dice, roll bookkeeping, turn ----
        var status = if (repeat) dice.status() else dice.update(obs.dice, now)
        if (!repeat) {
            val sh = shown
            if (sh != null && status.rollId == sh.rollId && userSig != null && userSig != sh.sig) {
                dice.markConsumed(); shown = null; notices += "ROLL_CONSUMED"; status = dice.status()
            }
            val seatsNow = obs.dice?.readings?.map { it.seat }?.toSet().orEmpty()
            val ts = if (status.state == DiceState.STABLE && seatsNow.size == 1) seatsNow.first() else null
            if (ts != null && ts != lastTurnSeat) { turnId++; lastTurnSeat = ts }
        }
        var m = manual
        if (m != null) {
            val stale = status.rollId != m.rollId || turnId != m.turnId || (m.sig != null && userSig != null && m.sig != userSig) || now - m.atMs > MANUAL_TTL_MS
            if (stale) { manual = null; m = null; notices += "MANUAL_DICE_CLEARED" }
        }
        lastUserSig = userSig ?: lastUserSig

        var diceReading: DiceReading? = null
        var turnSeat: Seat? = null
        var needsConfirmation = false
        if (m != null) {
            diceReading = DiceReading(m.value, 1.0, 0.0, 0.0, Seat.BOTTOM_LEFT); turnSeat = Seat.BOTTOM_LEFT
            notices += "MANUAL_DICE_ACTIVE"
        } else {
            when (status.state) {
                DiceState.NONE -> blockers += "DICE_NOT_FOUND"
                DiceState.ANIMATING -> blockers += "DICE_ANIMATING"
                DiceState.SETTLING -> { blockers += "DICE_NOT_STABLE"; needsConfirmation = true }
                DiceState.STABLE -> if (status.stale) blockers += "DICE_STALE" else diceReading = status.reading
            }
            if (status.reading != null) {
                val seats = obs.dice?.readings?.map { it.seat }?.toSet().orEmpty()
                turnSeat = if (seats.size == 1) seats.first() else null
                if (turnSeat == null) blockers += "TURN_UNCERTAIN" else if (turnSeat != Seat.BOTTOM_LEFT) blockers += "NOT_YOUR_TURN"
            }
        }
        if (diceReading != null) conf *= diceReading.confidence

        problems += blockers; problems += notices.filter { it.startsWith("RECAL") }
        val state = GameState(mode, active, active.associateWith { s -> pieces[s]?.rels() ?: IntArray(Board.PIECES) { Board.HIDDEN } })
        val ok = blockers.isEmpty() && conf >= MIN_CONFIDENCE && diceReading != null
        var rec: Recommendation? = null
        var target: GridPoint? = null
        if (ok) {
            val cfg = search ?: SearchConfig.forMode(mode)
            rec = Search.recommend(state, Seat.BOTTOM_LEFT, diceReading!!.value, rules, StrategyWeights.forMode(mode), cfg)
            if (rec != null) {
                val legal = Rules.legalMoves(state, Seat.BOTTOM_LEFT, diceReading.value, rules)
                if (legal.none { it.pieceIdx == rec!!.best.move.pieceIdx && it.to == rec!!.best.move.to }) { problems += "INTERNAL_ILLEGAL_MOVE"; rec = null }
            }
            if (rec == null && "INTERNAL_ILLEGAL_MOVE" !in problems) problems += "NO_LEGAL_MOVE"
            if (rec != null) {
                target = PieceLocator.target(rec.best.move, pieces[rec.best.move.seat])
                if (!repeat && userSig != null) shown = Shown(status.rollId, userSig)
            }
        }
        return Analysis(
            ok = ok && "INTERNAL_ILLEGAL_MOVE" !in problems, confidence = conf, problems = problems.distinct(), blockers = blockers.distinct(), notices = notices.distinct(),
            board = bd, userColor = userColor, dice = diceReading, diceStatus = status, turnSeat = turnSeat, manualDiceActive = m != null,
            state = state, pieces = pieces, counts = pieces.mapValues { it.value.counts }, recommendation = rec, target = target,
            matchId = matchId, needsConfirmation = needsConfirmation,
        )
    }

    private var lastFrameW = 0
    private var lastFrameH = 0
    internal fun noteFrameSize(w: Int, h: Int) { lastFrameW = w; lastFrameH = h }
}

object PieceLocator {
    /**
     * Grid position of the piece that the move starts from. For a yard piece the position of a yard slot that was
     * actually seen occupied is returned; if none was verified there is no target (never "the first slot").
     */
    fun target(move: Move, pieces: SeatPieces?): GridPoint? {
        if (move.from == 0) {
            val slot = pieces?.pieces?.filter { it.state == PieceState.YARD && it.yardSlot >= 0 }?.maxByOrNull { it.confidence }?.yardSlot ?: return null
            val (x, y) = Board.yardSlots(move.seat)[slot]
            return GridPoint(x, y)
        }
        val c = Board.cellOf(move.seat, move.from) ?: return null
        return GridPoint(c.col + 0.5, c.row + 0.5)
    }
}

/** Converts between capture-frame pixels, real screen pixels and overlay-window pixels. */
data class DisplayMap(
    val frameW: Int, val frameH: Int,
    val screenW: Int, val screenH: Int,
    /** Where the overlay window's top-left corner is on the real screen (status bar / cutout offsets). */
    val overlayOriginX: Float = 0f, val overlayOriginY: Float = 0f,
) {
    init { require(frameW > 0 && frameH > 0 && screenW > 0 && screenH > 0) }
    val kx get() = screenW.toFloat() / frameW
    val ky get() = screenH.toFloat() / frameH
    fun frameToScreen(x: Double, y: Double) = (x * kx).toFloat() to (y * ky).toFloat()
    fun frameToOverlay(x: Double, y: Double): Pair<Float, Float> { val (sx, sy) = frameToScreen(x, y); return (sx - overlayOriginX) to (sy - overlayOriginY) }
    /** Ring radius for a board cell fraction. */
    fun radius(g: BoardGeometry, cells: Double) = (g.cell * cells * (kx + ky) / 2.0).toFloat()
    fun gridToOverlay(g: BoardGeometry, p: GridPoint) = frameToOverlay(g.x(p.gx), g.y(p.gy))
}

/** Stateless single-frame analysis (one frame is enough to confirm the dice). Kept for callers without a session. */
object Analyzer {
    fun analyze(frame: Frame, mode: GameMode, rules: RuleSet = RuleSet(), manualDice: Int? = null): Analysis {
        val s = MatchSession(framesRequired = 1, minStableMs = 0)
        if (manualDice != null) {
            s.process(frame, mode, rules, 0L)
            s.setManualDice(manualDice, 0L)
            return s.reevaluate(mode, rules, 0L) ?: Analysis.failed(listOf("BOARD_NOT_FOUND"))
        }
        return s.process(frame, mode, rules, 0L)
    }
}
