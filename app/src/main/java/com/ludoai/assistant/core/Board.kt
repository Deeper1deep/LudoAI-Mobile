package com.ludoai.assistant.core

enum class LudoColor { RED, GREEN, YELLOW, BLUE }

/** A cell on the 15x15 board grid. col 0 = left, row 0 = top (screen space). */
data class Cell(val col: Int, val row: Int)

/**
 * Seats are fixed screen positions, in clockwise order of play.
 * BOTTOM_LEFT is ALWAYS the user. Colours are never attached to seats here:
 * the colour of each seat is detected from the screen at the start of every match.
 */
enum class Seat(val startIdx: Int, val qx: Int, val qy: Int) {
    BOTTOM_LEFT(39, 0, 9),
    TOP_LEFT(0, 0, 0),
    TOP_RIGHT(13, 9, 0),
    BOTTOM_RIGHT(26, 9, 9);

    fun next(): Seat = values()[(ordinal + 1) % 4]
}

/**
 * Piece progress ("rel"): 0 = in yard, 1 = own start cell, 1..51 = shared track,
 * 52..56 = own home column, 57 = finished.
 */
object Board {
    const val GRID = 15
    const val TRACK_LEN = 52
    const val LAST_TRACK_REL = 51
    const val FIRST_HOME_REL = 52
    const val FINISH = 57
    const val PIECES = 4

    val track: List<Cell> = buildList {
        for (c in 1..5) add(Cell(c, 6))
        for (r in 5 downTo 0) add(Cell(6, r))
        add(Cell(7, 0)); add(Cell(8, 0))
        for (r in 1..5) add(Cell(8, r))
        for (c in 9..14) add(Cell(c, 6))
        add(Cell(14, 7)); add(Cell(14, 8))
        for (c in 13 downTo 9) add(Cell(c, 8))
        for (r in 9..14) add(Cell(8, r))
        add(Cell(7, 14)); add(Cell(6, 14))
        for (r in 13 downTo 9) add(Cell(6, r))
        for (c in 5 downTo 0) add(Cell(c, 8))
        add(Cell(0, 7)); add(Cell(0, 6))
    }

    private val homeColumns: Map<Seat, List<Cell>> = mapOf(
        Seat.TOP_LEFT to (1..5).map { Cell(it, 7) },
        Seat.TOP_RIGHT to (1..5).map { Cell(7, it) },
        Seat.BOTTOM_RIGHT to (13 downTo 9).map { Cell(it, 7) },
        Seat.BOTTOM_LEFT to (13 downTo 9).map { Cell(7, it) },
    )

    private val trackIndexByCell: Map<Cell, Int> = track.withIndex().associate { it.value to it.index }
    private val homeByCell: Map<Cell, Pair<Seat, Int>> = buildMap {
        for ((seat, cells) in homeColumns) cells.forEachIndexed { i, c -> put(c, seat to (FIRST_HOME_REL + i)) }
    }

    /** Start cells and the star squares 8 steps after each start are safe. */
    val safeTrackIdx: Set<Int> = Seat.values().flatMap { listOf(it.startIdx, (it.startIdx + 8) % TRACK_LEN) }.toSet()

    fun isSafeTrack(idx: Int) = idx in safeTrackIdx

    /** Track index for a piece of [seat] at rel 1..51. */
    fun trackIdx(seat: Seat, rel: Int): Int {
        require(rel in 1..LAST_TRACK_REL) { "rel $rel not on shared track" }
        return (seat.startIdx + rel - 1) % TRACK_LEN
    }

    /** Grid cell for rel 1..56, null for yard (0) and finish (57). */
    fun cellOf(seat: Seat, rel: Int): Cell? = when (rel) {
        in 1..LAST_TRACK_REL -> track[trackIdx(seat, rel)]
        in FIRST_HOME_REL..56 -> homeColumns.getValue(seat)[rel - FIRST_HOME_REL]
        else -> null
    }

    fun trackIndexOf(cell: Cell): Int? = trackIndexByCell[cell]
    fun homeOwner(cell: Cell): Pair<Seat, Int>? = homeByCell[cell]

    /** rel of a track index for a given seat, or null when that cell is skipped by the seat (the cell before its start). */
    fun relOnTrack(seat: Seat, idx: Int): Int? {
        val rel = ((idx - seat.startIdx) % TRACK_LEN + TRACK_LEN) % TRACK_LEN + 1
        return if (rel <= LAST_TRACK_REL) rel else null
    }

    /** Centres (in grid units) of the 4 yard circles for a seat. */
    fun yardSlots(seat: Seat): List<Pair<Double, Double>> {
        val off = listOf(2.0, 3.95)
        return off.flatMap { y -> off.map { x -> (seat.qx + x) to (seat.qy + y) } }
    }
}
