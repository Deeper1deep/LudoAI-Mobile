package com.ludoai.assistant.android

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.ludoai.assistant.core.*
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground service: owns the MediaProjection, the floating overlay and the analysis loop.
 * Screenshots live only in memory and are dropped after analysis. The game is never touched.
 */
class AssistantService : Service() {
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0; private var capH = 0
    private var screenW = 0; private var screenH = 0

    private var root: LinearLayout? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var bubble: TextView? = null
    private var resultView: TextView? = null
    private var reasonView: TextView? = null
    private var confBar: ProgressBar? = null
    private var arrow: ArrowView? = null
    private var expanded = true
    private var busy = false
    private var lastFrame: Frame? = null
    private var manualDice: Int? = null
    private val autoLoop = object : Runnable {
        override fun run() { if (prefs.autoRefresh && expanded && !busy) refresh(); main.postDelayed(this, 2500) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        prefs = Prefs(this); wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        try { return startCapture(intent) } catch (e: Throwable) {
            CrashLog.save(this, "service start: " + e.stackTraceToString().take(4000))
            try { shutdown() } catch (_: Throwable) { }
            return START_NOT_STICKY
        }
    }

    private fun startCapture(intent: Intent?): Int {
        startForegroundCompat()
        val code = intent?.getIntExtra("code", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION") val data = intent?.getParcelableExtra<Intent>("data")
        if (code != Activity.RESULT_OK || data == null) { shutdown(); return START_NOT_STICKY }
        if (projection == null) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { setResult(S.get("recapture", prefs.lang), 0.0); shutdown() } }
            }, main)
            setupCapture(); buildOverlay(); main.postDelayed(autoLoop, 2500)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("assistant", "Assistant", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 2, Intent(this, AssistantService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, "assistant").setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(S.get("title", prefs.lang)).setContentText(S.get("recommendOnly", prefs.lang))
            .addAction(0, S.get("stop", prefs.lang), stop).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1, n)
    }

    private fun setupCapture() {
        val dm = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels; screenH = dm.heightPixels
        val scale = min(1.0, 1080.0 / min(screenW, screenH))
        capW = (screenW * scale).toInt(); capH = (screenH * scale).toInt()
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        display = projection!!.createVirtualDisplay("ludoai", capW, capH, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun buildOverlay() {
        val lang = prefs.lang
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        // arrow layer: covers the screen, ignores touches
        arrow = ArrowView(this)
        wm.addView(arrow, WindowManager.LayoutParams(-1, -1, type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT))

        val bg = GradientDrawable().apply { setColor(Color.parseColor("#EE1B0F2E")); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Color.parseColor("#7C3AED")) }
        val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bubble = TextView(this).apply {
            text = "🎲"; textSize = 22f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#7C3AED")) }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        val title = TextView(this).apply { text = S.get("title", lang); setTextColor(Color.WHITE); textSize = 13f; setPadding(dp(8), 0, dp(8), 0) }
        fun small(label: String, click: () -> Unit) = Button(this).apply {
            text = label; textSize = 12f; minWidth = 0; minimumWidth = 0; setPadding(dp(8), 0, dp(8), 0); setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(36))
        }
        val minBtn = small("–") { setExpanded(!expanded) }
        val closeBtn = small("✕") { shutdown() }
        head.addView(bubble); head.addView(title); head.addView(minBtn); head.addView(closeBtn)

        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg; setPadding(dp(12), dp(10), dp(12), dp(10)) }
        resultView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; text = S.get("recommendOnly", lang) }
        confBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        reasonView = TextView(this).apply { setTextColor(Color.parseColor("#D1C4E9")); textSize = 13f }
        val refreshBtn = Button(this).apply { text = "⟳ " + S.get("refresh", lang); setOnClickListener { manualDice = null; refresh() } }
        val diceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        diceRow.addView(TextView(this).apply { text = S.get("pickdice", lang); setTextColor(Color.LTGRAY); textSize = 11f })
        val diceBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in 1..6) diceBtns.addView(Button(this).apply {
            text = "$v"; minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)); setOnClickListener { manualDice = v; analyzeLast() }
        })
        p.addView(resultView); p.addView(confBar); p.addView(reasonView); p.addView(refreshBtn); p.addView(diceRow); p.addView(diceBtns)
        panel = p
        r.addView(head); r.addView(p)
        root = r

        rootParams = WindowManager.LayoutParams(dp(300), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = prefs.overlayX; y = prefs.overlayY
        }
        // drag by the header/bubble; remembers its last position
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        head.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = rootParams!!.x; oy = rootParams!!.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - sx) + Math.abs(e.rawY - sy) > dp(6)) moved = true
                    rootParams!!.x = (ox + (e.rawX - sx)).toInt().coerceIn(0, max(0, screenW - dp(60)))
                    rootParams!!.y = (oy + (e.rawY - sy)).toInt().coerceIn(0, max(0, screenH - dp(60)))
                    wm.updateViewLayout(root, rootParams); true
                }
                MotionEvent.ACTION_UP -> { prefs.overlayX = rootParams!!.x; prefs.overlayY = rootParams!!.y; if (!moved && e.x < dp(56)) setExpanded(!expanded); true }
                else -> false
            }
        }
        wm.addView(root, rootParams)
        setExpanded(true)
    }

    private fun setExpanded(v: Boolean) {
        expanded = v
        panel?.visibility = if (v) View.VISIBLE else View.GONE
        rootParams?.width = if (v) dp(300) else WindowManager.LayoutParams.WRAP_CONTENT
        root?.let { try { wm.updateViewLayout(it, rootParams) } catch (_: Exception) { } }
    }

    private fun setResult(text: String, conf: Double, reasons: String = "") {
        resultView?.text = text; reasonView?.text = reasons; confBar?.progress = (conf * 100).toInt().coerceIn(0, 100)
    }

    /** Hide our own windows, grab one frame, analyse off the main thread. */
    private fun refresh() {
        if (busy || projection == null) return
        busy = true
        root?.visibility = View.INVISIBLE; arrow?.visibility = View.INVISIBLE
        main.postDelayed({
            val frame = try { grabFrame() } catch (e: Exception) { null }
            root?.visibility = View.VISIBLE; arrow?.visibility = View.VISIBLE
            if (frame == null) { busy = false; setResult(S.get("recapture", prefs.lang), 0.0); return@postDelayed }
            lastFrame = frame
            analyze(frame)
        }, 180)
    }

    private fun analyzeLast() { val f = lastFrame; if (f == null) refresh() else if (!busy) { busy = true; analyze(f) } }

    private fun analyze(frame: Frame) {
        val dice = manualDice
        worker.execute {
            val a = try { Analyzer.analyze(frame, prefs.mode, prefs.rules, dice) } catch (e: Exception) { null }
            main.post { busy = false; show(a, frame) }
        }
    }

    private fun grabFrame(): Frame? {
        val img = reader?.acquireLatestImage() ?: return null
        try {
            val pl = img.planes[0]; val rowPad = pl.rowStride - pl.pixelStride * img.width
            val bmp = Bitmap.createBitmap(img.width + rowPad / pl.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(pl.buffer)
            val px = IntArray(img.width * img.height)
            bmp.getPixels(px, 0, img.width, 0, 0, img.width, img.height)
            bmp.recycle()
            return Frame(img.width, img.height, px)
        } finally { img.close() }
    }

    private fun show(a: Analysis?, frame: Frame) {
        val lang = prefs.lang
        val yourColor = a?.userColor?.let { S.get(it.name.lowercase(), lang) } ?: "-"
        prefs.lastDiag = if (a == null) "analysis error" else buildString {
            append("ok=${a.ok} conf=${"%.2f".format(a.confidence)} user=${a.userColor} dice=${a.dice?.value}\nproblems=${a.problems}\n")
            a.board?.let { append("cell=${"%.1f".format(it.geometry.cell)} colours=${it.seatColors}\n") }
            a.counts.forEach { (s, c) -> append("$s yard=${c.yard} board=${c.onBoard} finished?=${c.finishedInferred}\n") }
        }
        arrow?.target = null; arrow?.invalidate()
        if (a == null || !a.ok) { setResult(S.get("recapture", lang), a?.confidence ?: 0.0, S.get("you", lang) + ": " + yourColor); return }
        val best = a.best
        if (best == null) { setResult(S.get("noMove", lang), a.confidence, "🎲 ${a.dice!!.value}  " + S.get("you", lang) + ": " + yourColor); return }
        val m = best.move
        val where = if (m.from == 0) S.get("yardPiece", lang) else Board.cellOf(m.seat, m.from)?.let { "${S.get("piece_at", lang)} (${it.col},${it.row})" } ?: ""
        setResult("🎲 ${a.dice!!.value}  ➜  ${S.get("play", lang)} $where", a.confidence,
            S.get("you", lang) + ": " + yourColor + "\n" + best.reasons.filter { it.code != ReasonCode.PROGRESS || best.reasons.size == 1 }.take(4).joinToString("\n") { "• " + S.reason(it.code, it.value, lang) })
        if (prefs.showArrow && a.board != null) {
            val g = a.board.geometry
            val c = if (m.from == 0) Board.yardSlots(m.seat).first().let { g.x(it.first) to g.y(it.second) } else Board.cellOf(m.seat, m.from)?.let { g.centerOf(it) }
            if (c != null) {
                val k = screenW.toFloat() / frame.w
                arrow?.radius = (g.cell * 0.55 * k).toFloat()
                arrow?.target = (c.first * k).toFloat() to (c.second * k).toFloat(); arrow?.invalidate()
            }
        }
    }

    private fun shutdown() {
        main.removeCallbacksAndMessages(null)
        try { root?.let { wm.removeView(it) } } catch (_: Exception) { }
        try { arrow?.let { wm.removeView(it) } } catch (_: Exception) { }
        root = null; arrow = null
        display?.release(); reader?.close(); projection?.stop(); projection = null
        lastFrame = null
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onCreate() { super.onCreate(); running = true }
    override fun onDestroy() { running = false; worker.shutdownNow(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "com.ludoai.assistant.STOP"
        @Volatile var running = false
    }
}
