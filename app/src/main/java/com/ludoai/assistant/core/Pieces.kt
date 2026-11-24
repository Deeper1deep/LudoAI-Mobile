package com.ludoai.assistant.core

/**
 * Explicit state of every one of a seat's four pieces.
 *
 * UNKNOWN is the honest answer for a piece that was not seen: it is NEVER silently turned into FINISHED.
 * A piece is only FINISHED with positive evidence (see [Evidence]).
 */
enum class PieceState { YARD, ON_TRACK, HOME_PATH, FINISHED, UNKNOWN }

enum class Evidence {
    /** Seen in this frame. */
    DETECTED,
    /** Seen inside its home lane in an earlier frame, then gone, with all other pieces accounted for. Home-lane pieces can only move to the finish. */
    TRACKED_FINISH,
    /** The user declared the number of finished pieces. */
    DECLARED_FINISH,
    /** Two own pieces on one cell: deduced from the previous frame (a piece moved onto an occupied cell). */
    INFERRED_STACK,
    /** Nothing is known. */
    NONE,
}

/** rel: 0 yard, 1..56 on board, 57 finished, [Board.HIDDEN] unknown. */
data class PieceReading(
    val seat: Seat,
    val slot: Int,
    val state: PieceState,
    val rel: Int,
    val confidence: Double,
    val evidence: Evidence,
    val yardSlot: Int = -1,
    val stacked: Boolean = false,
) {
    val known get() = state != PieceState.UNKNOWN
}

data class SeatCounts(val yard: Int, val onBoard: Int, val finished: Int, val unknown: Int)

data class SeatPieces(val seat: Seat, val pieces: List<PieceReading>, val problems: List<String> = emptyList()) {
    val counts: SeatCounts
        get() = SeatCounts(
            yard = pieces.count { it.state == PieceState.YARD },
            onBoard = pieces.count { it.state == PieceState.ON_TRACK || it.state == PieceState.HOME_PATH },
            finished = pieces.count { it.state == PieceState.FINISHED },
            unknown = pieces.count { it.state == PieceState.UNKNOWN },
        )

    /** True when all four pieces are accounted for. */
    val complete get() = problems.none { it.startsWith("TOO_MANY") } && pieces.size == Board.PIECES && pieces.none { it.state == PieceState.UNKNOWN }

    /** Lowest confidence among the pieces (0 when any piece is unknown). */
    val minConfidence get() = if (pieces.isEmpty()) 0.0 else pieces.minOf { it.confidence }

    /** rel values for [GameState]. Unknown pieces become [Board.HIDDEN] (ignored by the rules; never "finished"). */
    fun rels(): IntArray = IntArray(Board.PIECES) { i -> pieces.getOrNull(i)?.rel ?: Board.HIDDEN }
}

data class BoardHit(val rel: Int, val confidence: Double)
data class YardSlotHit(val slot: Int, val confidence: Double)

/** What one frame shows for one seat. [trusted] is false when an overlay (toast, popup) covers the seat's lane. */
data class SeatObservation(val seat: Seat, val board: List<BoardHit>, val yard: List<YardSlotHit>, val trusted: Boolean = true)

/** Per-seat memory kept for the duration of one match. */
class SeatMemory {
    var prev: List<PieceReading>? = null
    /** Finished pieces confirmed by evidence (tracked or declared). */
    var finished: Int = 0
    /** User-declared finished count; overrides the tracked value when set. */
    var declaredFinished: Int? = null

    fun effectiveFinished(): Int = (declaredFinished ?: finished).coerceIn(0, Board.PIECES)
}

object Reconciler {
    /**
     * Turns a frame's raw detections into exactly four [PieceReading]s.
     *
     * Rules:
     *  - every detected board cell is one piece, every occupied yard slot is one piece;
     *  - finished pieces come only from memory (tracked / declared), never from "missing";
     *  - a missing piece is explained only by evidence (vanished from the home lane, or stacked on an own piece);
     *  - everything else stays UNKNOWN.
     */
    fun reconcile(obs: SeatObservation, mem: SeatMemory?): SeatPieces {
        val seat = obs.seat
        val problems = ArrayList<String>()
        val board = obs.board.sortedBy { it.rel }
        val yard = obs.yard.sortedBy { it.slot }

        if (board.size + yard.size > Board.PIECES) {
            problems += "TOO_MANY_PIECES_$seat"
            val readings = ArrayList<PieceReading>()
            board.forEach { readings += boardReading(seat, readings.size, it) }
            yard.forEach { readings += yardReading(seat, readings.size, it) }
            return SeatPieces(seat, readings, problems)
        }

        var finishedKnown = (mem?.effectiveFinished() ?: 0)
        var finishedEvidence = if (mem?.declaredFinished != null) Evidence.DECLARED_FINISH else Evidence.TRACKED_FINISH
        // memory can never push the total above four
        if (board.size + yard.size + finishedKnown > Board.PIECES) finishedKnown = Board.PIECES - board.size - yard.size

        var missing = Board.PIECES - board.size - yard.size - finishedKnown
        val stackAt = ArrayList<Int>()
        val prev = mem?.prev

        if (missing > 0 && prev != null && obs.trusted && mem?.declaredFinished == null) {
            // 1. pieces that were inside their home lane and are no longer there have reached the finish
            val boardRels = board.map { it.rel }.toSet()
            val vanished = prev.filter { it.state == PieceState.HOME_PATH && it.rel !in boardRels }
            val f = minOf(missing, vanished.size)
            if (f > 0) { finishedKnown += f; missing -= f }
        }
        if (missing > 0 && prev != null && obs.trusted) {
            // 2. an own piece moved onto (or stays on) a cell that already holds another own piece
            val prevComplete = prev.none { it.state == PieceState.UNKNOWN } && prev.size == Board.PIECES
            if (prevComplete) {
                val prevMulti = prev.filter { it.state == PieceState.YARD || it.state == PieceState.ON_TRACK || it.state == PieceState.HOME_PATH }
                    .map { it.rel }.toMutableList()
                // pieces that finished are no longer on the board
                var toDrop = finishedKnown - prev.count { it.state == PieceState.FINISHED }
                if (toDrop > 0) {
                    val boardRels = board.map { it.rel }.toSet()
                    val it2 = prevMulti.filter { r -> r in 52..56 && r !in boardRels }.toMutableList()
                    for (r in it2) { if (toDrop > 0) { prevMulti.remove(r); toDrop-- } }
                }
                val now = board.map { it.rel }.toMutableList().also { l -> repeat(yard.size) { l += 0 } }
                val prevOnly = ArrayList(prevMulti); now.forEach { prevOnly.remove(it) }
                val nowOnly = ArrayList(now); prevMulti.forEach { nowOnly.remove(it) }
                if (nowOnly.isEmpty() && prevOnly.size == missing) {
                    val cells = board.map { it.rel }.distinct()
                    val chosen = ArrayList<Int>()
                    var ok = true
                    for (a in prevOnly) {
                        val cand = cells.filter { b -> b == a || (a >= 1 && b - a in 1..6) || (a == 0 && b == 1) }
                        if (cand.size == 1) chosen += cand[0] else { ok = false; break }
                    }
                    if (ok) { stackAt += chosen; missing -= chosen.size }
                }
            }
        }

        val readings = ArrayList<PieceReading>()
        val stackedRels = stackAt.toSet()
        for (h in board) readings += boardReading(seat, readings.size, h).copy(stacked = h.rel in stackedRels)
        for (r in stackAt) {
            val base = board.first { it.rel == r }
            readings += PieceReading(seat, readings.size, stateOf(r), r, base.confidence * 0.7, Evidence.INFERRED_STACK, stacked = true)
        }
        for (y in yard) readings += yardReading(seat, readings.size, y)
        repeat(finishedKnown) { readings += PieceReading(seat, readings.size, PieceState.FINISHED, Board.FINISH, if (finishedEvidence == Evidence.DECLARED_FINISH) 0.95 else 0.8, finishedEvidence) }
        repeat(missing) { readings += PieceReading(seat, readings.size, PieceState.UNKNOWN, Board.HIDDEN, 0.0, Evidence.NONE) }
        if (missing > 0) problems += "PIECES_UNKNOWN_$seat"
        return SeatPieces(seat, readings, problems)
    }

    /** Stores the reconciled pieces so that the next frame can be explained. */
    fun remember(mem: SeatMemory, sp: SeatPieces, trusted: Boolean) {
        if (!trusted) return
        mem.prev = sp.pieces
        if (mem.declaredFinished == null) mem.finished = sp.pieces.count { it.state == PieceState.FINISHED }
    }

    private fun stateOf(rel: Int) = if (rel >= Board.FIRST_HOME_REL) PieceState.HOME_PATH else PieceState.ON_TRACK
    private fun boardReading(seat: Seat, slot: Int, h: BoardHit) = PieceReading(seat, slot, stateOf(h.rel), h.rel, h.confidence, Evidence.DETECTED)
    private fun yardReading(seat: Seat, slot: Int, y: YardSlotHit) = PieceReading(seat, slot, PieceState.YARD, 0, y.confidence, Evidence.DETECTED, yardSlot = y.slot)
}
