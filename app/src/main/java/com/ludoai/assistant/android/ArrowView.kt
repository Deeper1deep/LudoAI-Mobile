package com.ludoai.assistant.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Full-screen, touch-transparent view that draws a ring and an arrow over the recommended piece. */
class ArrowView(ctx: Context) : View(ctx) {
    var target: Pair<Float, Float>? = null
    var radius = 40f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f; color = Color.WHITE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#FFEB3B") }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.BLACK }

    override fun onDraw(c: Canvas) {
        val (x, y) = target ?: return
        c.drawCircle(x, y, radius, edge.apply { strokeWidth = 14f }); c.drawCircle(x, y, radius, ring)
        val p = Path().apply {
            val tip = y - radius - 6f; val h = radius * 1.4f; val w = radius * 0.7f
            moveTo(x, tip); lineTo(x - w, tip - h * 0.55f); lineTo(x - w * 0.35f, tip - h * 0.55f)
            lineTo(x - w * 0.35f, tip - h); lineTo(x + w * 0.35f, tip - h); lineTo(x + w * 0.35f, tip - h * 0.55f)
            lineTo(x + w, tip - h * 0.55f); close()
        }
        c.drawPath(p, fill); c.drawPath(p, edge.apply { strokeWidth = 4f })
    }
}
