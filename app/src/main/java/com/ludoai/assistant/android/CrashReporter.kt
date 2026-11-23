package com.ludoai.assistant.android

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/** Records the real error of any crash, shows it on a plain screen and keeps it for the diagnostics page. */
class LudoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val text = "v" + com.ludoai.assistant.BuildConfig.VERSION_NAME + " | Android " + android.os.Build.VERSION.RELEASE +
                    " | " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " | thread " + t.name + "\n\n" + sw.toString().take(6000)
                CrashLog.save(this, text)
                startActivity(Intent(this, CrashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("trace", text))
            } catch (_: Throwable) { }
            if (previous != null && false) previous.uncaughtException(t, e)
            Process.killProcess(Process.myPid()); exitProcess(10)
        }
    }
}

object CrashLog {
    private fun file(c: Context) = File(c.applicationContext.filesDir, "last_crash.txt")
    fun save(c: Context, text: String) { try { file(c).writeText(text) } catch (_: Throwable) { } }
    fun read(c: Context): String = try { file(c).takeIf { it.exists() }?.readText() ?: "" } catch (_: Throwable) { "" }
    fun clear(c: Context) { try { file(c).delete() } catch (_: Throwable) { } }
}

/** Plain-view screen (no Compose) so it can still show up when the main UI is what crashed. */
class CrashActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val trace = intent.getStringExtra("trace") ?: CrashLog.read(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#14091F")); setPadding(32, 64, 32, 32) }
        col.addView(TextView(this).apply { text = "Ludo AI Assistant: crash report / تقرير الخطأ"; setTextColor(Color.parseColor("#FFC107")); textSize = 18f; gravity = Gravity.CENTER })
        val tv = TextView(this).apply { text = trace; setTextColor(Color.WHITE); textSize = 11f; setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        col.addView(ScrollView(this).apply { addView(tv) }, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(Button(this).apply {
            text = "Copy / نسخ"
            setOnClickListener { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("crash", trace)) }
        })
        setContentView(col)
    }
}
