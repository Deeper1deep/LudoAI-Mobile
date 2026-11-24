package com.ludoai.assistant.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Full-screen, touch-transparent view that draws a ring and an arrow over the recommended piece.
 * [target] is in REAL SCREEN pixels; the view subtracts its own on-screen origin, so status bars, cutouts and
 * window offsets cannot shift the arrow.
 */
class ArrowView(ctx: Context) : View(ctx) {
    var target: Pair<Float, Float>? = null
    var radius = 40f
    private val loc = IntArray(2)
    private val path = Path()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f; color = Color.WHITE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#FFEB3B") }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.BLACK }

    /** Screen rectangle (x0, y0, x1, y1) covered by the ring and arrow, or null; used to mask our own drawing out of frame comparison. */
    fun screenBounds(): IntArray? {
        val (x, y) = target ?: return null
        val r = radius
        return intArrayOf((x - r * 1.8f).toInt(), (y - r * 3.2f).toInt(), (x + r * 1.8f).toInt(), (y + r * 1.6f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val (sx, sy) = target ?: return
        getLocationOnScreen(loc)
        val x = sx - loc[0]; val y = sy - loc[1]
        c.drawCircle(x, y, radius, edge.apply { strokeWidth = 14f }); c.drawCircle(x, y, radius, ring)
        // when the ring is near the top edge the arrow points up from below instead of being cut off
        val up = y - radius * 3f > 0
        path.reset()
        val h = radius * 1.4f; val w = radius * 0.7f
        if (up) {
            val tip = y - radius - 6f
            path.moveTo(x, tip); path.lineTo(x - w, tip - h * 0.55f); path.lineTo(x - w * 0.35f, tip - h * 0.55f)
            path.lineTo(x - w * 0.35f, tip - h); path.lineTo(x + w * 0.35f, tip - h); path.lineTo(x + w * 0.35f, tip - h * 0.55f)
            path.lineTo(x + w, tip - h * 0.55f)
        } else {
            val tip = y + radius + 6f
            path.moveTo(x, tip); path.lineTo(x - w, tip + h * 0.55f); path.lineTo(x - w * 0.35f, tip + h * 0.55f)
            path.lineTo(x - w * 0.35f, tip + h); path.lineTo(x + w * 0.35f, tip + h); path.lineTo(x + w * 0.35f, tip + h * 0.55f)
            path.lineTo(x + w, tip + h * 0.55f)
        }
        path.close()
        c.drawPath(path, fill); c.drawPath(path, edge.apply { strokeWidth = 4f })
    }
}
