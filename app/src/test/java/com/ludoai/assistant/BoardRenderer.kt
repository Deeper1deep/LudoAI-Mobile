package com.ludoai.assistant

import com.ludoai.assistant.core.*
import kotlin.math.min

/** Draws a Yalla-Ludo-like screen (palette measured from a real screenshot) so the vision code can be tested at any size. */
class BoardRenderer(val w: Int, val h: Int, val boardFrac: Double = 0.96, val offsetY: Double = 0.30) {
    val px = IntArray(w * h) { rgb(60, 30, 90) }
    val side = (w * boardFrac)
    val left = (w - side) / 2
    val top = h * offsetY
    val cell = side / 15.0
    fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    companion object {
        val BASE = mapOf(LudoColor.YELLOW to intArrayOf(246, 191, 10), LudoColor.BLUE to intArrayOf(24, 138, 250),
            LudoColor.GREEN to intArrayOf(14, 189, 70), LudoColor.RED to intArrayOf(238, 57, 40))
        val DARK = mapOf(LudoColor.YELLOW to intArrayOf(195, 150, 21), LudoColor.BLUE to intArrayOf(28, 107, 186),
            LudoColor.GREEN to intArrayOf(27, 136, 60), LudoColor.RED to intArrayOf(181, 49, 36))
        val GLOBE = mapOf(LudoColor.YELLOW to intArrayOf(190, 148, 9), LudoColor.BLUE to intArrayOf(18, 105, 190),
            LudoColor.GREEN to intArrayOf(9, 146, 52), LudoColor.RED to intArrayOf(153, 43, 33))
        val TOKEN = mapOf(LudoColor.YELLOW to intArrayOf(221, 170, 37), LudoColor.BLUE to intArrayOf(81, 180, 216),
            LudoColor.GREEN to intArrayOf(100, 185, 60), LudoColor.RED to intArrayOf(205, 90, 70))
    }

    fun c(a: IntArray) = rgb(a[0], a[1], a[2])
    fun rect(x0: Double, y0: Double, x1: Double, y1: Double, col: Int) {
        for (y in y0.toInt().coerceAtLeast(0) until min(h, y1.toInt())) for (x in x0.toInt().coerceAtLeast(0) until min(w, x1.toInt())) px[y * w + x] = col
    }
    fun disc(cx: Double, cy: Double, r: Double, col: Int) {
        for (y in (cy - r).toInt().coerceAtLeast(0)..min(h - 1, (cy + r).toInt())) for (x in (cx - r).toInt().coerceAtLeast(0)..min(w - 1, (cx + r).toInt()))
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) px[y * w + x] = col
    }
    fun gx(g: Double) = left + g * cell
    fun gy(g: Double) = top + g * cell
    fun cellRect(col: Int, row: Int, color: Int) = rect(gx(col.toDouble()), gy(row.toDouble()), gx(col + 1.0), gy(row + 1.0), color)

    fun token(gxx: Double, gyy: Double, color: LudoColor, rad: Double = 0.38) {
        val cx = gx(gxx); val cy = gy(gyy); val r = rad * cell
        val t = TOKEN.getValue(color)
        disc(cx, cy, r, rgb(t[0] / 2, t[1] / 2, t[2] / 2))                // dark rim
        disc(cx, cy, r * 0.86, c(t))
        disc(cx - r * 0.2, cy - r * 0.25, r * 0.45, rgb(min(255, t[0] + 50), min(255, t[1] + 50), min(255, t[2] + 50))) // highlight
        disc(cx + r * 0.25, cy + r * 0.25, r * 0.25, rgb(t[0] * 3 / 4, t[1] * 3 / 4, t[2] * 3 / 4))
    }

    /** colors: seat -> colour. pieces: state with 4 rels per seat. */
    fun render(colors: Map<Seat, LudoColor>, state: GameState?, dice: Int?, finishedAsCentre: Boolean = false): Frame {
        rect(left - cell * 0.3, top - cell * 0.3, left + side + cell * 0.3, top + side + cell * 0.3, rgb(119, 72, 42)) // wooden frame
        rect(left, top, left + side, top + side, rgb(250, 250, 252))
        for ((seat, col) in colors) {
            rect(gx(seat.qx.toDouble()), gy(seat.qy.toDouble()), gx(seat.qx + 6.0), gy(seat.qy + 6.0), c(BASE.getValue(col)))
            disc(gx(seat.qx + 3.0), gy(seat.qy + 3.0), 2.4 * cell, c(DARK.getValue(col)))
        }
        for ((seat, col) in colors) for (rel in 52..56) Board.cellOf(seat, rel)!!.let { cellRect(it.col, it.row, c(BASE.getValue(col))) }
        for ((seat, col) in colors) {
            val s = Board.track[seat.startIdx]
            cellRect(s.col, s.row, c(BASE.getValue(col)))
            disc(gx(s.col + 0.5), gy(s.row + 0.5), 0.26 * cell, c(GLOBE.getValue(col)))
        }
        for (idx in Board.safeTrackIdx) if (Seat.values().none { it.startIdx == idx }) {
            val s = Board.track[idx]; disc(gx(s.col + 0.5), gy(s.row + 0.5), 0.3 * cell, rgb(120, 120, 120))
        }
        // grid lines between track cells
        // centre triangles
        for ((seat, col) in colors) rect(gx(7.0) - 0.4 * cell + (seat.qx - 4.5) * 0.0, gy(7.0), gx(7.0), gy(7.0) + 1, c(BASE.getValue(col)))
        for (s in Seat.values()) {
            val col = colors[s] ?: continue
            val (cx, cy) = when (s) { Seat.TOP_LEFT -> 6.4 to 7.5; Seat.TOP_RIGHT -> 7.5 to 6.4; Seat.BOTTOM_RIGHT -> 8.6 to 7.5; Seat.BOTTOM_LEFT -> 7.5 to 8.6 }
            disc(gx(cx), gy(cy), 0.35 * cell, c(BASE.getValue(col)))
        }
        if (state != null) for ((seat, rels) in state.pieces) {
            val col = colors[seat] ?: continue
            var slot = 0
            for (rel in rels) {
                when {
                    rel == 0 -> { val (sx, sy) = Board.yardSlots(seat)[slot++]; token(sx, sy, col) }
                    rel == Board.FINISH -> {}
                    else -> Board.cellOf(seat, rel)!!.let { token(it.col + 0.5, it.row + 0.5, col, 0.34) }
                }
            }
        }
        if (dice != null) drawDie(gx(4.4), gy(16.9), cell * 1.7, dice)
        return Frame(w, h, px)
    }

    fun drawDie(x: Double, y: Double, size: Double, value: Int) {
        rect(x - size * 0.15, y - size * 0.15, x + size * 1.15, y + size * 1.15, rgb(133, 132, 148))
        rect(x, y, x + size, y + size, rgb(245, 245, 245))
        val pos = listOf(0.25, 0.5, 0.75)
        val cells = when (value) {
            1 -> listOf(4); 2 -> listOf(0, 8); 3 -> listOf(0, 4, 8); 4 -> listOf(0, 2, 6, 8)
            5 -> listOf(0, 2, 4, 6, 8); else -> listOf(0, 3, 6, 2, 5, 8)
        }
        for (cc in cells) disc(x + pos[cc % 3] * size, y + pos[cc / 3] * size, size * 0.085, rgb(40, 40, 40))
    }
}
