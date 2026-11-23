package com.ludoai.assistant.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Plain ARGB pixel buffer, so the whole pipeline runs (and is tested) on the JVM. */
class Frame(val w: Int, val h: Int, val px: IntArray) {
    init { require(px.size >= w * h) }
    fun at(x: Int, y: Int): Int = px[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
}

enum class Cls { RED, GREEN, YELLOW, BLUE, WHITE, GRAY, DARK }

object Colors {
    fun classify(c: Int): Cls {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val v = mx / 255f
        if (v < 0.28f) return Cls.DARK
        val s = if (mx == 0) 0f else (mx - mn) / mx.toFloat()
        if (s < 0.25f) return if (v > 0.78f) Cls.WHITE else Cls.GRAY
        return hueClass(hue(r, g, b, mx, mn))
    }

    /** Saturated, bright colour only; null for white/grey/dark/pale. */
    fun saturated(c: Int): LudoColor? {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        if (mx < 110) return null // dark shadows
        val s = (mx - mn) / mx.toFloat()
        if (s < 0.55f) return null
        val h = hue(r, g, b, mx, mn)
        return when {
            h < 16f || h >= 335f -> LudoColor.RED
            h < 38f -> null            // orange/brown: the wooden frame, never a Ludo colour
            h < 75f -> LudoColor.YELLOW
            h < 175f -> LudoColor.GREEN
            h < 295f -> LudoColor.BLUE
            else -> null               // purple/magenta: app background and UI
        }
    }

    private fun hue(r: Int, g: Int, b: Int, mx: Int, mn: Int): Float {
        val d = (mx - mn).toFloat()
        if (d == 0f) return 0f
        val h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return if (h < 0) h + 360f else h
    }

    private fun hueClass(h: Float): Cls = when {
        h < 30f -> Cls.RED
        h < 75f -> Cls.YELLOW
        h < 175f -> Cls.GREEN
        h < 265f -> Cls.BLUE
        h < 295f -> Cls.BLUE
        else -> Cls.RED
    }

    fun luma(c: Int): Int = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
    fun dist(a: Int, b: Int): Int =
        abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))
}

data class Blob(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int, val area: Int) {
    val w get() = maxX - minX + 1
    val h get() = maxY - minY + 1
    val cx get() = (minX + maxX) / 2.0
    val cy get() = (minY + maxY) / 2.0
    val fill get() = area.toDouble() / (w.toDouble() * h)
}

/** Connected components on a coarse sampling grid (stride [st] px). */
object Blobs {
    /**
     * [erode] (px) removes features thinner than 2*erode (single board cells, icons) so that large
     * regions such as yards are measured alone; returned boxes are grown back by [erode].
     */
    fun find(f: Frame, st: Int, erode: Int = 0, test: (Int) -> Boolean): List<Blob> {
        val gw = f.w / st; val gh = f.h / st
        var mask = BooleanArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) mask[gy * gw + gx] = test(f.px[(gy * st) * f.w + gx * st])
        val k = erode / st
        if (k > 0) {
            val tmp = BooleanArray(gw * gh)
            for (y in 0 until gh) { // horizontal erosion
                var run = 0
                for (x in 0 until gw) { run = if (mask[y * gw + x]) run + 1 else 0; tmp[y * gw + x] = run > 2 * k }
                // shift back: pixel x kept when [x-k..x+k] all set, computed from run ending at x+k
            }
            val h1 = BooleanArray(gw * gh)
            for (y in 0 until gh) for (x in 0 until gw) h1[y * gw + x] = (x + k < gw) && tmp[y * gw + x + k]
            val tmp2 = BooleanArray(gw * gh)
            for (x in 0 until gw) {
                var run = 0
                for (y in 0 until gh) { run = if (h1[y * gw + x]) run + 1 else 0; tmp2[y * gw + x] = run > 2 * k }
            }
            val out2 = BooleanArray(gw * gh)
            for (y in 0 until gh) for (x in 0 until gw) out2[y * gw + x] = (y + k < gh) && tmp2[(y + k) * gw + x]
            mask = out2
        }
        val seen = BooleanArray(gw * gh)
        val out = ArrayList<Blob>()
        val stack = IntArray(gw * gh)
        for (i in mask.indices) {
            if (!mask[i] || seen[i]) continue
            var sp = 0; stack[sp++] = i; seen[i] = true
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1; var n = 0
            while (sp > 0) {
                val p = stack[--sp]; val x = p % gw; val y = p / gw
                n++; if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                    val q = ny * gw + nx
                    if (mask[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
                }
            }
            out.add(Blob(minX * st - erode, minY * st - erode, maxX * st + st - 1 + erode, maxY * st + st - 1 + erode, n * st * st))
        }
        return out
    }
}

/** Board position on screen: grid unit (0,0) top-left, [cell] px per grid unit. */
data class BoardGeometry(val left: Double, val top: Double, val cell: Double) {
    fun x(gx: Double) = left + gx * cell
    fun y(gy: Double) = top + gy * cell
    fun centerOf(c: Cell) = x(c.col + 0.5) to y(c.row + 0.5)
}

data class BoardDetection(
    val geometry: BoardGeometry,
    val seatColors: Map<Seat, LudoColor>,
    val confidence: Double,
    val notes: List<String>,
)

object BoardDetector {
    private data class Cand(val color: LudoColor, val blob: Blob) { val cell get() = (blob.w + blob.h) / 12.0 }

    /** Tries several assumed board sizes (erosion radius) and keeps the most consistent result. */
    fun detect(f: Frame): BoardDetection? {
        var best: BoardDetection? = null
        for (div in listOf(16, 12, 22, 30)) {
            val d = detectWith(f, (0.55 * f.w / div).toInt())
            if (d != null && (best == null || d.confidence > best.confidence)) best = d
            if (best != null && best.confidence >= 0.95) break
        }
        return best
    }

    private fun detectWith(f: Frame, erode: Int): BoardDetection? {
        val st = max(1, min(f.w, f.h) / 240)
        val minSide = min(f.w, f.h) * 0.08
        val cands = ArrayList<Cand>()
        for (color in LudoColor.values()) {
            val blobs = Blobs.find(f, st, erode) { Colors.saturated(it) == color }
                .filter { it.w >= minSide && it.h >= minSide && it.w.toDouble() / it.h in 0.75..1.33 && it.fill > 0.2 }
            blobs.maxByOrNull { it.area }?.let { cands.add(Cand(color, it)) }
        }
        if (cands.size < 2) return null
        val med = cands.map { it.cell }.sorted()[cands.size / 2]
        val kept = cands.filter { abs(it.cell - med) / med < 0.2 }
        if (kept.size < 2) return null

        // Yards may be drawn inset inside their 6x6 area (some skins), so try a few cell sizes when matching.
        var best: Triple<Double, Map<Seat, Cand>, Double>? = null
        val size0 = kept.map { it.cell }.average()
        for (shrink in listOf(1.0, 1.08, 1.17, 1.3)) {
            val cell0 = size0 * shrink
            for (c in kept) for (s in Seat.values()) {
                val ox = c.blob.minX - s.qx * cell0; val oy = c.blob.minY - s.qy * cell0
                val assign = HashMap<Seat, Cand>(); assign[s] = c
                for (o in kept) {
                    if (o === c) continue
                    for (s2 in Seat.values()) {
                        if (assign.containsKey(s2)) continue
                        if (abs(o.blob.minX - (ox + s2.qx * cell0)) < 0.5 * cell0 && abs(o.blob.minY - (oy + s2.qy * cell0)) < 0.5 * cell0) {
                            assign[s2] = o; break
                        }
                    }
                }
                if (!assign.containsKey(Seat.BOTTOM_LEFT)) continue
                val centred = 1.0 - min(1.0, abs(ox + 7.5 * cell0 - f.w / 2.0) / f.w) // tie-break only
                val score = assign.size + centred * 0.1 - (if (shrink > 1.0) 0.01 else 0.0)
                if (best == null || score > best.first) best = Triple(score, assign, cell0)
            }
        }
        val b = best ?: return null
        val assign = b.second
        val notes = ArrayList<String>()
        var conf = if (assign.size >= 4) 1.0 else if (assign.size == 3) 0.85 else 0.65
        if (assign.size < 4) notes += "only ${assign.size} corner areas found"
        var cell = b.third
        val left = assign.filter { it.key.qx == 0 }.values; val right = assign.filter { it.key.qx == 9 }.values
        val top = assign.filter { it.key.qy == 0 }.values; val bot = assign.filter { it.key.qy == 9 }.values
        // yard spacing is exact (9 cells between left and right yard corners), independent of any inset
        val cxs = if (left.isNotEmpty() && right.isNotEmpty()) (right.map { it.blob.minX }.average() - left.map { it.blob.minX }.average()) / 9.0 else null
        val cys = if (top.isNotEmpty() && bot.isNotEmpty()) (bot.map { it.blob.minY }.average() - top.map { it.blob.minY }.average()) / 9.0 else null
        if (cxs != null && cys != null && abs(cxs - cys) / cxs > 0.08) { conf *= 0.6; notes += "board is not square" }
        val spacing = listOfNotNull(cxs, cys)
        if (spacing.isNotEmpty()) cell = spacing.average()
        val margin = max(0.0, (6 * cell - kept.map { (it.blob.w + it.blob.h) / 2.0 }.average()) / 2.0)
        val xs = assign.map { (s, c) -> c.blob.minX - margin - s.qx * cell }
        val ys = assign.map { (s, c) -> c.blob.minY - margin - s.qy * cell }
        val ox = xs.average(); val oy = ys.average()
        val g = BoardGeometry(ox, oy, cell)
        if (g.x(0.0) < -cell || g.y(0.0) < -cell || g.x(15.0) > f.w + cell || g.y(15.0) > f.h + cell) { conf *= 0.4; notes += "board outside screen" }
        if (cell < 8) { conf *= 0.5; notes += "board too small" }
        return BoardDetection(g, assign.mapValues { it.value.color }, conf, notes)
    }
}

data class DiceReading(val value: Int, val confidence: Double, val x: Double, val y: Double)

object DiceDetector {
    private val corners = setOf(0, 2, 6, 8)

    /** Pip layouts of a standard die as 3x3 occupancy sets (rotation-tolerant for 2, 3, 6). */
    private fun validLayout(count: Int, cells: Set<Int>): Boolean = when (count) {
        1 -> cells == setOf(4)
        2 -> cells == setOf(0, 8) || cells == setOf(2, 6)
        3 -> cells == setOf(0, 4, 8) || cells == setOf(2, 4, 6)
        4 -> cells == corners
        5 -> cells == corners + 4
        6 -> cells == setOf(0, 3, 6, 2, 5, 8) || cells == setOf(0, 1, 2, 6, 7, 8)
        else -> false
    }

    fun detect(f: Frame, g: BoardGeometry, userSeat: Seat = Seat.BOTTOM_LEFT): DiceReading? {
        val st = max(1, (g.cell / 10).toInt())
        val found = ArrayList<DiceReading>()
        val whites = Blobs.find(f, st) { Colors.classify(it) == Cls.WHITE && Colors.luma(it) > 215 }
        for (b in whites) {
            if (b.w < 0.55 * g.cell || b.w > 2.4 * g.cell || b.h < 0.55 * g.cell || b.h > 2.4 * g.cell) continue
            if (b.w.toDouble() / b.h !in 0.8..1.25) continue
            if (b.fill < 0.5) continue
            // skip the white track cross (its cells are blob-merged and much larger, but guard anyway)
            val inCross = (b.cx in g.x(6.0)..g.x(9.0) || b.cy in g.y(6.0)..g.y(9.0)) &&
                b.cx in g.x(0.0)..g.x(15.0) && b.cy in g.y(0.0)..g.y(15.0)
            if (inCross) continue
            // pips: dark blobs inside
            val inset = 0.1
            val x0 = b.minX + (b.w * inset).toInt(); val x1 = b.maxX - (b.w * inset).toInt()
            val y0 = b.minY + (b.h * inset).toInt(); val y1 = b.maxY - (b.h * inset).toInt()
            val pips = pipBlobs(f, x0, y0, x1, y1, b)
            if (pips.isEmpty() || pips.size > 6) continue
            val cells = pips.map { p ->
                val gx = (((p.cx - b.minX) / b.w) * 3).toInt().coerceIn(0, 2)
                val gy = (((p.cy - b.minY) / b.h) * 3).toInt().coerceIn(0, 2)
                gy * 3 + gx
            }.toSet()
            val ok = validLayout(pips.size, cells) && cells.size == pips.size
            found.add(DiceReading(pips.size, if (ok) 0.95 else 0.3, b.cx, b.cy))
        }
        val good = found.filter { it.confidence > 0.6 }
        if (good.isEmpty()) return null
        val ux = g.x(3.0); val uy = g.y(12.0)
        val nearest = good.minByOrNull { (it.x - ux) * (it.x - ux) + (it.y - uy) * (it.y - uy) }!!
        val disagree = good.any { it.value != nearest.value }
        return if (disagree) nearest.copy(confidence = nearest.confidence * 0.7) else nearest
    }

    private fun pipBlobs(f: Frame, x0: Int, y0: Int, x1: Int, y1: Int, die: Blob): List<Blob> {
        if (x1 <= x0 || y1 <= y0) return emptyList()
        val w = x1 - x0 + 1; val h = y1 - y0 + 1
        val sub = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) sub[y * w + x] = f.at(x0 + x, y0 + y)
        val sf = Frame(w, h, sub)
        val dieArea = die.w.toDouble() * die.h
        return Blobs.find(sf, 1) { Colors.luma(it) < 110 }
            .filter { it.area > 0.004 * dieArea && it.area < 0.1 * dieArea && it.w.toDouble() / it.h in 0.6..1.6 }
            .map { Blob(it.minX + x0, it.minY + y0, it.maxX + x0, it.maxY + y0, it.area) }
    }
}

data class SeatCounts(val yard: Int, val onBoard: Int, val finishedInferred: Int)

data class Analysis(
    val ok: Boolean,
    val confidence: Double,
    val problems: List<String>,
    val board: BoardDetection?,
    val userColor: LudoColor?,
    val dice: DiceReading?,
    val state: GameState?,
    val counts: Map<Seat, SeatCounts>,
    val ranked: List<ScoredMove>,
) {
    val best: ScoredMove? get() = ranked.firstOrNull()
    val needsRecapture get() = !ok
}

object Analyzer {
    const val MIN_CONFIDENCE = 0.6

    fun analyze(f: Frame, mode: GameMode, rules: RuleSet = RuleSet(), manualDice: Int? = null): Analysis {
        val problems = ArrayList<String>()
        val bd = BoardDetector.detect(f)
            ?: return Analysis(false, 0.0, listOf("BOARD_NOT_FOUND"), null, null, null, null, emptyMap(), emptyList())
        problems += bd.notes
        var conf = bd.confidence
        val seatColors = bd.seatColors
        val userColor = seatColors[Seat.BOTTOM_LEFT]
        val active = GameState.activeSeats(mode)
        for (s in active) if (!seatColors.containsKey(s)) { problems += "SEAT_COLOR_UNKNOWN_$s"; conf *= 0.5 }
        val colorToSeat = seatColors.entries.associate { it.value to it.key }

        val pieces = HashMap<Seat, IntArray>()
        for (s in Seat.values()) pieces[s] = IntArray(Board.PIECES)
        val boardRels = HashMap<Seat, MutableList<Int>>()
        val g = bd.geometry
        var invalid = 0
        fun note(seat: Seat, rel: Int) { boardRels.getOrPut(seat) { ArrayList() }.add(rel) }
        for (idx in 0 until Board.TRACK_LEN) {
            val cell = Board.track[idx]
            val startOwner = Seat.values().firstOrNull { it.startIdx == idx }
            val color = PieceDetector.pieceAt(f, g, cell.col, cell.row, startOwner?.let { seatColors[it] }, startOwner != null) ?: continue
            val seat = colorToSeat[color] ?: continue
            val rel = Board.relOnTrack(seat, idx)
            if (rel == null) { invalid++; continue }
            note(seat, rel)
        }
        for (seat in Seat.values()) {
            val stds = (Board.FIRST_HOME_REL..56).map { rel -> Board.cellOf(seat, rel)!!.let { PieceDetector.stats(f, g.x(it.col + 0.5), g.y(it.row + 0.5), 0.26 * g.cell).std } }.sorted()
            val baseline = stds[2]
            if (baseline > 15.0) { if ("BOARD_OVERLAY" !in problems) problems += "BOARD_OVERLAY"; conf *= 0.85 }
            for (rel in Board.FIRST_HOME_REL..56) {
            val cell = Board.cellOf(seat, rel)!!
            val color = PieceDetector.pieceAt(f, g, cell.col, cell.row, seatColors[seat], false, baseline) ?: continue
            if (seatColors[seat] == color) note(seat, rel) else invalid++
            }
        }
        if (invalid > 0) { problems += "INCONSISTENT_PIECES"; conf *= 0.6 }

        val counts = HashMap<Seat, SeatCounts>()
        for ((seat, color) in seatColors) {
            val onBoard = boardRels[seat].orEmpty().sorted()
            if (onBoard.size > Board.PIECES) { problems += "TOO_MANY_PIECES_$seat"; conf *= 0.4 }
            val yard = PieceDetector.yardOccupied(f, g, seat, color)
            val fin = (Board.PIECES - min(Board.PIECES, onBoard.size) - yard).coerceAtLeast(0)
            counts[seat] = SeatCounts(yard, onBoard.size, fin)
            if (onBoard.size + yard > Board.PIECES) { problems += "COUNT_MISMATCH_$seat"; conf *= 0.6 }
            val arr = pieces.getValue(seat)
            onBoard.take(Board.PIECES).forEachIndexed { i, r -> arr[i] = r }
            // pieces beyond onBoard stay at 0 (yard) up to yard count, rest are finished
            var i = onBoard.size
            repeat(fin) { if (i < Board.PIECES) arr[i++] = Board.FINISH }
        }
        val state = GameState(mode, active, active.associateWith { pieces.getValue(it) })

        val diceR = if (manualDice != null) DiceReading(manualDice, 1.0, 0.0, 0.0) else DiceDetector.detect(f, g)
        if (diceR == null) { problems += "DICE_NOT_FOUND"; conf *= 0.0 } else conf *= diceR.confidence
        val ok = conf >= MIN_CONFIDENCE && diceR != null && userColor != null
        val ranked = if (ok) Strategy.rank(state, Seat.BOTTOM_LEFT, diceR!!.value, rules) else emptyList()
        if (ok && ranked.isEmpty()) problems += "NO_LEGAL_MOVE"
        return Analysis(ok, conf, problems, bd, userColor, diceR, state, counts, ranked)
    }
}

/** Reference look of the (empty) start-cell icon for each colour, measured from real Yalla Ludo screenshots. */
object StartTemplates {
    val mean = mapOf(
        LudoColor.YELLOW to ((190 shl 16) or (148 shl 8) or 9),
        LudoColor.BLUE to ((18 shl 16) or (105 shl 8) or 190),
        LudoColor.RED to ((153 shl 16) or (43 shl 8) or 33),
        LudoColor.GREEN to ((9 shl 16) or (146 shl 8) or 52),
    )
}

object PieceDetector {
    class Stats(val n: Int, val sat: Map<LudoColor, Int>, val mean: Int, val std: Double)

    fun stats(f: Frame, cx: Double, cy: Double, r: Double): Stats {
        val step = max(1.0, r / 6.0)
        var n = 0; var sr = 0L; var sg = 0L; var sb = 0L; var sl = 0L; var sl2 = 0L
        val sat = HashMap<LudoColor, Int>()
        var y = -r
        while (y <= r) {
            var x = -r
            while (x <= r) {
                if (x * x + y * y <= r * r) {
                    val c = f.at((cx + x).toInt(), (cy + y).toInt())
                    n++; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
                    val l = Colors.luma(c); sl += l; sl2 += l * l
                    Colors.saturated(c)?.let { sat.merge(it, 1, Int::plus) }
                }
                x += step
            }
            y += step
        }
        if (n == 0) return Stats(0, emptyMap(), 0, 0.0)
        val ml = sl.toDouble() / n
        val std = sqrt(max(0.0, sl2.toDouble() / n - ml * ml))
        val mean = (0xFF shl 24) or ((sr / n).toInt() shl 16) or ((sg / n).toInt() shl 8) or (sb / n).toInt()
        return Stats(n, sat, mean, std)
    }

    private fun ringMean(f: Frame, g: BoardGeometry, gx: Double, gy: Double, off: Double, rad: Double): Int {
        var sr = 0; var sg = 0; var sb = 0
        for ((dx, dy) in listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)) {
            val m = stats(f, g.x(gx + dx * off), g.y(gy + dy * off), rad * g.cell).mean
            sr += (m shr 16) and 255; sg += (m shr 8) and 255; sb += m and 255
        }
        return (0xFF shl 24) or ((sr / 4) shl 16) or ((sg / 4) shl 8) or (sb / 4)
    }

    /**
     * Colour of a piece standing on the board cell at grid (col,row), or null.
     * [cellColor] is the flat colour of that cell (home lane / start cell) or null for white cells.
     */
    fun pieceAt(f: Frame, g: BoardGeometry, col: Int, row: Int, cellColor: LudoColor?, isStart: Boolean, laneStdBaseline: Double = 0.0): LudoColor? {
        val st = stats(f, g.x(col + 0.5), g.y(row + 0.5), 0.26 * g.cell)
        if (st.n == 0) return null
        val top = st.sat.maxByOrNull { it.value } ?: return null
        if (top.value < 0.35 * st.n) return null
        val x = top.key
        return when {
            cellColor == null -> if (st.std > 18.0) x else null
            x != cellColor -> if (st.std > 12.0) x else null
            isStart -> {
                val ref = StartTemplates.mean.getValue(x)
                if (Colors.dist(st.mean, ref) > 45) x else null
            }
            else -> {
                // A lane is flat; a token adds texture. A translucent toast textures every lane cell alike, so
                // compare with the median of the same lane instead of an absolute value.
                if (st.std > 22.0 && st.std - laneStdBaseline > 15.0) x else null
            }
        }
    }

    /** Number of occupied yard circles: a token has texture, the empty circle behind it is flat. */
    fun yardOccupied(f: Frame, g: BoardGeometry, seat: Seat, color: LudoColor): Int {
        var count = 0
        for ((sx, sy) in Board.yardSlots(seat)) {
            var hit = false
            for ((dx, dy) in listOf(0.0 to 0.0, 0.2 to 0.0, -0.2 to 0.0, 0.0 to 0.2, 0.0 to -0.2)) {
                val st = stats(f, g.x(sx + dx), g.y(sy + dy), 0.3 * g.cell)
                if (st.n > 0 && (st.sat[color] ?: 0) >= 0.3 * st.n && st.std > 12.0) { hit = true; break }
            }
            if (hit) count++
        }
        return count
    }
}
