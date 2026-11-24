package com.ludoai.assistant.android

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
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
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground service: owns the MediaProjection, the floating overlay and the analysis loop.
 * Screenshots live only in memory and are dropped after analysis. The game is never touched.
 *
 * Threading: UI/overlay/gate state lives on the main thread. The [MatchSession] is touched ONLY on [worker].
 * Only one analysis runs at a time ([busy]).
 */
class AssistantService : Service() {
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private lateinit var dmgr: DisplayManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0; private var capH = 0
    private var screenW = 0; private var screenH = 0; private var densityDpi = 0
    private var frameBuf: IntArray? = null            // worker thread only; reused to avoid a 10 MB allocation per frame

    private var root: LinearLayout? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var bubble: TextView? = null
    private var resultView: TextView? = null
    private var reasonView: TextView? = null
    private var manualRow: LinearLayout? = null
    private var manualText: TextView? = null
    private var finishedRow: LinearLayout? = null
    private var finishedText: TextView? = null
    private var confBar: ProgressBar? = null
    private var arrow: ArrowView? = null
    private var expanded = true
    private var busy = false
    @Volatile private var shuttingDown = false
    private var released = false

    private val session = MatchSession()               // worker thread only
    private val gate = AutoRefreshGate()               // main thread only
    private var declaredFinished = 0                   // main thread mirror of the user's finished-count
    private var lastDims = 0 to 0                      // dimensions of the frame of the last analysis

    private val tick = object : Runnable {
        override fun run() {
            if (shuttingDown) return
            if (!Settings.canDrawOverlays(this@AssistantService)) { shutdown(); return }   // permission revoked: never leave a dead overlay
            try { if (prefs.autoRefresh && expanded && !busy) poll() } catch (t: Throwable) { CrashLog.save(this@AssistantService, "poll: " + t.stackTraceToString().take(3000)) }
            main.postDelayed(this, gate.nextDelayMs(SystemClock.elapsedRealtime()))
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) main.post { onDisplayChanged() } }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); running = true }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        prefs = Prefs(this); wm = getSystemService(WINDOW_SERVICE) as WindowManager; dmgr = getSystemService(DISPLAY_SERVICE) as DisplayManager
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
            val mp = mpm.getMediaProjection(code, data)
            projection = mp
            // Android 14+: the callback MUST be registered before createVirtualDisplay
            val cb = object : MediaProjection.Callback() {
                override fun onStop() { main.post { if (!shuttingDown) shutdown() } }
                override fun onCapturedContentResize(width: Int, height: Int) { main.post { onDisplayChanged() } }
            }
            projectionCallback = cb
            mp.registerCallback(cb, main)
            readMetrics()
            setupCapture()
            buildOverlay()
            dmgr.registerDisplayListener(displayListener, main)
            main.postDelayed(tick, 1200)
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

    // ---------------------------------------------------------------- capture

    @Suppress("DEPRECATION")
    private fun readMetrics() {
        val dm = DisplayMetrics()
        val d = dmgr.getDisplay(Display.DEFAULT_DISPLAY) ?: wm.defaultDisplay
        d.getRealMetrics(dm)
        screenW = dm.widthPixels; screenH = dm.heightPixels; densityDpi = dm.densityDpi
    }

    /** Capture size: at most 1080 px on the short edge (accuracy first, memory second), even numbers. */
    private fun captureSize(w: Int, h: Int): Pair<Int, Int> {
        val scale = min(1.0, 1080.0 / min(w, h))
        return ((w * scale).toInt() and 1.inv()).coerceAtLeast(2) to ((h * scale).toInt() and 1.inv()).coerceAtLeast(2)
    }

    private fun setupCapture() {
        val (w, h) = captureSize(screenW, screenH)
        capW = w; capH = h
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        display = projection!!.createVirtualDisplay("ludoai", w, h, densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
    }

    /** Screen size or orientation changed: resize the existing virtual display (a second createVirtualDisplay is not allowed on Android 14+). */
    private fun onDisplayChanged() {
        if (shuttingDown || projection == null) return
        val ow = screenW; val oh = screenH
        readMetrics()
        if (ow == screenW && oh == screenH) return
        val (w, h) = captureSize(screenW, screenH)
        if (w != capW || h != capH) {
            try {
                val old = reader
                val nr = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
                display?.resize(w, h, densityDpi)
                display?.surface = nr.surface
                reader = nr; capW = w; capH = h
                old?.close()
            } catch (t: Throwable) { CrashLog.save(this, "resize: " + t.stackTraceToString().take(3000)); shutdown(); return }
        }
        arrow?.target = null; arrow?.invalidate()
        rootParams?.let { p ->
            p.x = p.x.coerceIn(0, max(0, screenW - dp(60))); p.y = p.y.coerceIn(0, max(0, screenH - dp(60)))
            try { root?.let { wm.updateViewLayout(it, p) } } catch (_: Exception) { }
        }
        gate.force()    // geometry changed: the old recognition is meaningless; the session recalibrates by itself
    }

    private fun sign(img: Image): FrameSignature {
        val pl = img.planes[0]; val buf = pl.buffer; val rs = pl.rowStride; val ps = pl.pixelStride
        return FrameSignature.sample(img.width, img.height, mask = overlayMask(img.width, img.height)) { x, y ->
            val o = y * rs + x * ps
            val r = buf.get(o).toInt() and 255; val g = buf.get(o + 1).toInt() and 255; val b = buf.get(o + 2).toInt() and 255
            (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Our own windows (panel, arrow) as rectangles in capture pixels, so they never count as "the screen changed". */
    private fun overlayMask(w: Int, h: Int): List<IntArray> {
        val out = ArrayList<IntArray>(2)
        val kx = w.toFloat() / screenW; val ky = h.toFloat() / screenH
        fun add(r: IntArray) { out.add(intArrayOf(((r[0] - 8) * kx).toInt(), ((r[1] - 8) * ky).toInt(), ((r[2] + 8) * kx).toInt(), ((r[3] + 8) * ky).toInt())) }
        root?.takeIf { it.visibility == View.VISIBLE && it.width > 0 }?.let { v ->
            val loc = IntArray(2); v.getLocationOnScreen(loc); add(intArrayOf(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height))
        }
        arrow?.screenBounds()?.let { add(it) }
        return out
    }

    /** Cheap look at the newest frame: sample ~4000 pixels, decide whether a full analysis is warranted. */
    private fun poll() {
        val img = reader?.acquireLatestImage() ?: return
        try {
            val sig = sign(img)
            val now = SystemClock.elapsedRealtime()
            if (gate.shouldAnalyze(sig, now)) { gate.started(sig, now); startAnalysis() }
        } finally { img.close() }
    }

    /** Full frame into the reusable buffer. Runs on the worker. Waits briefly for a frame if none is pending. */
    private fun grabFrame(): Frame? {
        val r = reader ?: return null
        var img: Image? = null
        for (i in 0 until 8) { img = r.acquireLatestImage(); if (img != null) break; Thread.sleep(60) }
        val im = img ?: return null
        try {
            val w = im.width; val h = im.height; val pl = im.planes[0]; val buf = pl.buffer; val rs = pl.rowStride; val ps = pl.pixelStride
            var px = frameBuf
            if (px == null || px.size != w * h) { px = IntArray(w * h); frameBuf = px }
            if (ps == 4 && ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                buf.rewind()
                val ib = buf.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                val stride = rs / 4
                for (y in 0 until h) {
                    ib.position(y * stride); ib.get(px, y * w, w)
                    for (i in y * w until y * w + w) { val v = px[i]; px[i] = (v and 0x0000FF00) or ((v and 0xFF) shl 16) or ((v ushr 16) and 0xFF) or (0xFF shl 24) }
                }
            } else {
                for (y in 0 until h) for (x in 0 until w) {
                    val o = y * rs + x * ps
                    px[y * w + x] = (255 shl 24) or ((buf.get(o).toInt() and 255) shl 16) or ((buf.get(o + 1).toInt() and 255) shl 8) or (buf.get(o + 2).toInt() and 255)
                }
            }
            return Frame(w, h, px)
        } finally { im.close() }
    }

    // ---------------------------------------------------------------- analysis

    /** Hide our own windows, grab one clean frame and analyse it off the main thread. One at a time. */
    private fun startAnalysis() {
        if (busy || projection == null || shuttingDown) return
        busy = true
        root?.visibility = View.INVISIBLE; arrow?.visibility = View.INVISIBLE
        main.postDelayed({
            if (shuttingDown) return@postDelayed
            val now = SystemClock.elapsedRealtime(); val mode = prefs.mode; val rules = prefs.rules
            worker.execute {
                var a: Analysis? = null; var dims = 0 to 0
                try {
                    val f = grabFrame()
                    main.post { if (!shuttingDown) { root?.visibility = View.VISIBLE; arrow?.visibility = View.VISIBLE } }   // frame is in memory: restore the overlay at once
                    if (f != null) {
                        dims = f.w to f.h; a = session.process(f, mode, rules, now)
                        if (prefs.learning) try { Learning.record(this@AssistantService, f, a, mode.players, a?.manualDiceActive == true) } catch (e: Throwable) { CrashLog.save(this@AssistantService, "learning: " + e.stackTraceToString().take(2000)) }
                    }
                } catch (t: Throwable) {
                    main.post { if (!shuttingDown) { root?.visibility = View.VISIBLE; arrow?.visibility = View.VISIBLE } }
                    CrashLog.save(this@AssistantService, "analysis: " + t.stackTraceToString().take(4000))
                }
                main.post { if (!shuttingDown) { busy = false; if (dims.first > 0) lastDims = dims; gate.finished(a?.needsConfirmation == true, SystemClock.elapsedRealtime()); show(a) } }
            }
        }, 150)
    }

    /** Manual dice: applies to the CURRENT analysis only (the session drops it on a new roll, a turn change or a board change). */
    private fun manualDice(v: Int) {
        if (busy || projection == null) return
        busy = true
        val now = SystemClock.elapsedRealtime(); val mode = prefs.mode; val rules = prefs.rules
        worker.execute {
            var a: Analysis? = null
            try { session.setManualDice(v, now); a = session.reevaluate(mode, rules, now) } catch (t: Throwable) { CrashLog.save(this, "manual: " + t.stackTraceToString().take(3000)) }
            main.post { if (!shuttingDown) { busy = false; if (a == null) { gate.force(); setResult(S.get("waiting", prefs.lang), 0.0) } else show(a) } }
        }
    }

    private fun clearManual() {
        if (busy) return
        busy = true
        val now = SystemClock.elapsedRealtime(); val mode = prefs.mode; val rules = prefs.rules
        worker.execute {
            var a: Analysis? = null
            try { session.clearManualDice(); a = session.reevaluate(mode, rules, now) } catch (t: Throwable) { CrashLog.save(this, "clear: " + t.stackTraceToString().take(3000)) }
            main.post { if (!shuttingDown) { busy = false; if (a != null) show(a) } }
        }
    }

    private fun changeFinished(delta: Int) {
        if (busy) return
        declaredFinished = (declaredFinished + delta).coerceIn(0, 3)
        busy = true
        val n = declaredFinished; val now = SystemClock.elapsedRealtime(); val mode = prefs.mode; val rules = prefs.rules
        worker.execute {
            var a: Analysis? = null
            try { session.declareUserFinished(n); a = session.reevaluate(mode, rules, now) } catch (t: Throwable) { CrashLog.save(this, "finished: " + t.stackTraceToString().take(3000)) }
            main.post { if (!shuttingDown) { busy = false; if (a != null) show(a) } }
        }
    }

    private fun refreshNow() {
        if (busy) return
        gate.startedManually(SystemClock.elapsedRealtime())
        // a refresh also drops a stale manual dice: the user asked for what is on screen now
        worker.execute { try { session.clearManualDice() } catch (_: Throwable) { } }
        startAnalysis()
    }

    // ---------------------------------------------------------------- results

    private fun setResult(text: String, conf: Double, reasons: String = "") {
        resultView?.text = text; reasonView?.text = reasons; confBar?.progress = (conf * 100).toInt().coerceIn(0, 100)
    }

    private fun show(a: Analysis?) {
        val lang = prefs.lang
        val yourColor = a?.userColor?.let { S.get(it.name.lowercase(), lang) } ?: "-"
        prefs.lastDiag = if (a == null) "analysis error / no frame" else buildString {
            append("ok=${a.ok} conf=${"%.2f".format(a.confidence)} decision=${a.recommendation?.decisionConfidence?.let { "%.2f".format(it) }} depth=${a.recommendation?.searchDepth} user=${a.userColor} dice=${a.dice?.value} manual=${a.manualDiceActive} turn=${a.turnSeat}\n")
            append("blockers=${a.blockers} notices=${a.notices} match=${a.matchId}\n")
            a.board?.let { append("cell=${"%.1f".format(it.geometry.cell)} colours=${it.seatColors}\n") }
            a.counts.forEach { (s, c) -> append("$s yard=${c.yard} onBoard=${c.onBoard} finished=${c.finished} unknown=${c.unknown}\n") }
        }
        arrow?.target = null; arrow?.invalidate()
        manualRow?.visibility = if (a?.manualDiceActive == true) View.VISIBLE else View.GONE
        val needFinished = a?.blockers?.contains("PIECES_UNKNOWN_USER") == true || declaredFinished > 0
        finishedRow?.visibility = if (needFinished) View.VISIBLE else View.GONE
        finishedText?.text = S.get("finishedDecl", lang) + ": " + declaredFinished
        if (a == null) { setResult(S.get("recapture", lang), 0.0); return }

        if (!a.ok) {
            val why = (a.blockers.ifEmpty { listOf("LOW_CONFIDENCE") }).take(2).joinToString("\n") { S.problem(it, lang) }
            setResult(why, a.confidence, S.get("you", lang) + ": " + yourColor); return
        }
        val rec = a.recommendation
        if (rec == null) { setResult(S.get("noMove", lang), a.confidence, "🎲 ${a.dice?.value}  " + S.get("you", lang) + ": " + yourColor); return }

        val best = rec.best; val m = best.move
        val where = if (m.from == 0) S.get("yardPiece", lang) else Board.cellOf(m.seat, m.from)?.let { "${S.get("piece_at", lang)} (${it.col},${it.row})" } ?: ""
        val reasons = best.reasons.filter { it.code != ReasonCode.PROGRESS || best.reasons.size == 1 }.take(3).joinToString("\n") { "• " + S.reason(it.code, it.value, lang) }
        val risks = rec.risks.take(2).joinToString("\n") { "⚠ " + S.risk(it.code, it.probability, lang) }
        val alts = rec.alternatives.take(2).joinToString("\n") { alt ->
            val w = if (alt.move.from == 0) S.get("yardPiece", lang) else Board.cellOf(alt.move.seat, alt.move.from)?.let { "(${it.col},${it.row})" } ?: ""
            "↳ $w  ${"%.1f".format(alt.score)}"
        }
        val text = buildString {
            append(S.get("you", lang)).append(": ").append(yourColor)
            append("  •  ").append(S.get("confidence", lang)).append(" ").append((a.overallConfidence * 100).toInt()).append("%\n")
            append(reasons)
            if (risks.isNotEmpty()) append("\n").append(risks)
            if (alts.isNotEmpty()) append("\n").append(S.get("alternatives", lang)).append(":\n").append(alts)
            if (a.notices.contains("HIDDEN_OPPONENT_PIECES")) append("\n").append(S.problem("HIDDEN_OPPONENT_PIECES", lang))
        }
        val head = (if (a.manualDiceActive) "✋ " else "🎲 ") + "${a.dice!!.value}  ➜  ${S.get("play", lang)} $where"
        setResult(head, a.overallConfidence, text)
        manualText?.text = S.get("manualOn", lang)

        val t = a.target; val g = a.board?.geometry
        if (prefs.showArrow && t != null && g != null && lastDims.first > 0 && screenW > 0) {
            val dm = DisplayMap(lastDims.first, lastDims.second, screenW, screenH)
            val (x, y) = dm.frameToScreen(g.x(t.gx), g.y(t.gy))
            arrow?.radius = dm.radius(g, 0.55); arrow?.target = x to y; arrow?.invalidate()
        }
    }

    // ---------------------------------------------------------------- overlay

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun buildOverlay() {
        if (root != null) removeViews()                // never two overlay windows
        val lang = prefs.lang
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        try {
            arrow = ArrowView(this)
            val ap = WindowManager.LayoutParams(-1, -1, type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT)
            if (Build.VERSION.SDK_INT >= 30) ap.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else if (Build.VERSION.SDK_INT >= 28) ap.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            wm.addView(arrow, ap)

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
            head.addView(bubble); head.addView(title); head.addView(small("–") { setExpanded(!expanded) }); head.addView(small("✕") { shutdown() })

            val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg; setPadding(dp(12), dp(10), dp(12), dp(10)) }
            resultView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; text = S.get("recommendOnly", lang) }
            confBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
            reasonView = TextView(this).apply { setTextColor(Color.parseColor("#D1C4E9")); textSize = 13f }
            manualText = TextView(this).apply { setTextColor(Color.parseColor("#FFEB3B")); textSize = 12f; text = S.get("manualOn", lang) }
            manualRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
                addView(manualText, LinearLayout.LayoutParams(0, -2, 1f))
                addView(small(S.get("manualClear", lang)) { clearManual() }.apply { layoutParams = LinearLayout.LayoutParams(dp(64), dp(36)) })
            }
            finishedText = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12f }
            finishedRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
                addView(finishedText, LinearLayout.LayoutParams(0, -2, 1f))
                addView(small("−") { changeFinished(-1) }); addView(small("+") { changeFinished(+1) })
            }
            val refreshBtn = Button(this).apply { text = "⟳ " + S.get("refresh", lang); setOnClickListener { refreshNow() } }
            val diceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            diceRow.addView(TextView(this).apply { text = S.get("pickdice", lang); setTextColor(Color.LTGRAY); textSize = 11f })
            val diceBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (v in 1..6) diceBtns.addView(Button(this).apply {
                text = "$v"; minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)); setOnClickListener { manualDice(v) }
            })
            p.addView(resultView); p.addView(confBar); p.addView(reasonView); p.addView(manualRow); p.addView(finishedRow); p.addView(refreshBtn); p.addView(diceRow); p.addView(diceBtns)
            panel = p
            r.addView(head); r.addView(p)
            root = r

            rootParams = WindowManager.LayoutParams(dp(300), WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.overlayX.coerceIn(0, max(0, screenW - dp(60))); y = prefs.overlayY.coerceIn(0, max(0, screenH - dp(60)))
            }
            // drag by the header/bubble; remembers its last position
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            head.setOnTouchListener { _, e ->
                val rp = rootParams ?: return@setOnTouchListener false
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = rp.x; oy = rp.y; moved = false; true }
                    MotionEvent.ACTION_MOVE -> {
                        if (Math.abs(e.rawX - sx) + Math.abs(e.rawY - sy) > dp(6)) moved = true
                        rp.x = (ox + (e.rawX - sx)).toInt().coerceIn(0, max(0, screenW - dp(60)))
                        rp.y = (oy + (e.rawY - sy)).toInt().coerceIn(0, max(0, screenH - dp(60)))
                        try { root?.let { wm.updateViewLayout(it, rp) } } catch (_: Exception) { }
                        true
                    }
                    MotionEvent.ACTION_UP -> { prefs.overlayX = rp.x; prefs.overlayY = rp.y; if (!moved && e.x < dp(56)) setExpanded(!expanded); true }
                    else -> false
                }
            }
            wm.addView(root, rootParams)
            setExpanded(true)
        } catch (t: Throwable) {           // e.g. BadTokenException when the overlay permission was revoked
            CrashLog.save(this, "overlay: " + t.stackTraceToString().take(3000))
            shutdown()
        }
    }

    private fun removeViews() {
        try { root?.let { wm.removeViewImmediate(it) } } catch (_: Exception) { }
        try { arrow?.let { wm.removeViewImmediate(it) } } catch (_: Exception) { }
        root = null; arrow = null; panel = null; bubble = null; resultView = null; reasonView = null; confBar = null
        manualRow = null; manualText = null; finishedRow = null; finishedText = null
    }

    private fun setExpanded(v: Boolean) {
        expanded = v
        panel?.visibility = if (v) View.VISIBLE else View.GONE
        rootParams?.width = if (v) dp(300) else WindowManager.LayoutParams.WRAP_CONTENT
        root?.let { try { wm.updateViewLayout(it, rootParams) } catch (_: Exception) { } }
    }

    // ---------------------------------------------------------------- lifecycle

    /** Releases everything this service owns. Safe to call more than once. */
    private fun release() {
        if (released) return
        released = true; shuttingDown = true
        main.removeCallbacksAndMessages(null)
        try { rootParams?.let { prefs.overlayX = it.x; prefs.overlayY = it.y } } catch (_: Throwable) { }
        removeViews()
        try { dmgr.unregisterDisplayListener(displayListener) } catch (_: Throwable) { }
        try { projectionCallback?.let { projection?.unregisterCallback(it) } } catch (_: Throwable) { }
        try { display?.release() } catch (_: Throwable) { }
        try { reader?.close() } catch (_: Throwable) { }
        try { projection?.stop() } catch (_: Throwable) { }
        display = null; reader = null; projection = null; projectionCallback = null; frameBuf = null
        running = false
    }

    private fun shutdown() {
        release()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) { }
        stopSelf()
    }

    override fun onDestroy() { release(); worker.shutdownNow(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "com.ludoai.assistant.STOP"
        @Volatile var running = false
    }
}
