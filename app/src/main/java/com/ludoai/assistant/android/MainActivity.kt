package com.ludoai.assistant.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import com.ludoai.assistant.BuildConfig
import com.ludoai.assistant.core.GameMode
import com.ludoai.assistant.core.UpdateInfo
import com.ludoai.assistant.core.UpdateLogic
import kotlin.concurrent.thread

private val Purple = Color(0xFF7C3AED)
private val Gold = Color(0xFFFFC107)
private val Dark = Color(0xFF14091F)
private val Card = Color(0xFF241338)

class MainActivity : ComponentActivity() {
    private var screenState = mutableStateOf("home")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("screen")?.let { screenState.value = it }
        try { UpdateManager.schedulePeriodic(this) } catch (e: Throwable) { CrashLog.save(this, "schedulePeriodic: " + e.stackTraceToString().take(3000)) }
        setContent { App(screenState) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); intent.getStringExtra("screen")?.let { screenState.value = it } }
}

@Composable
private fun App(screen: MutableState<String>) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var lang by remember { mutableStateOf(prefs.lang) }
    val dir = if (lang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
    fun t(k: String) = S.get(k, lang)
    CompositionLocalProvider(LocalLayoutDirection provides dir) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Purple, secondary = Gold, background = Dark, surface = Card)) {
            Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A1450), Dark))).statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🎲 " + t("title"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Gold, modifier = Modifier.weight(1f))
                    if (screen.value != "home") TextButton(onClick = { screen.value = "home" }) { Text(if (lang == "ar") "رجوع" else "Back") }
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    when (screen.value) {
                        "settings" -> SettingsScreen(prefs, lang, { lang = it; prefs.lang = it }) { t(it) }
                        "updates" -> UpdatesScreen(prefs, lang) { t(it) }
                        "diag" -> DiagScreen(prefs, lang) { t(it) }
                        else -> Home(prefs, lang, screen) { t(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Card, RoundedCornerShape(16.dp)).padding(16.dp), content = content)
}

@Composable
private fun Home(prefs: Prefs, lang: String, screen: MutableState<String>, t: (String) -> String) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(prefs.mode) }
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var notifOk by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    var running by remember { mutableStateOf(AssistantService.running) }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, AssistantService::class.java).putExtra("code", r.resultCode).putExtra("data", r.data))
            running = true
        }
    }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifOk = it }
    val overlay = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { overlayOk = Settings.canDrawOverlays(ctx) }

    Panel {
        Text(t("mode"), fontWeight = FontWeight.Bold)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == GameMode.CLASSIC_4P, onClick = { mode = GameMode.CLASSIC_4P; prefs.mode = mode }, label = { Text(t("m4")) })
            FilterChip(selected = mode == GameMode.QUICK_2P, onClick = { mode = GameMode.QUICK_2P; prefs.mode = mode }, label = { Text(t("m2")) })
        }
    }
    Panel {
        PermRow(t("perm_overlay"), overlayOk, t("grant"), t("granted")) { overlay.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) }
        PermRow(t("perm_notif"), notifOk, t("grant"), t("granted")) { if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) }
        Text(t("perm_capture") + ": " + (if (lang == "ar") "يُطلب عند البدء" else "asked when you start"), color = Color.LightGray, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
    if (!running) {
        Button(onClick = {
            if (!Settings.canDrawOverlays(ctx)) { overlay.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))); return@Button }
            val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
            capture.launch(mpm.createScreenCaptureIntent())
        }, modifier = Modifier.fillMaxWidth().height(56.dp).padding(vertical = 4.dp)) { Text(t("start"), fontSize = 18.sp) }
    } else {
        Button(onClick = { ctx.startService(Intent(ctx, AssistantService::class.java).setAction(AssistantService.ACTION_STOP)); running = false },
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(vertical = 4.dp)) { Text(t("stop"), fontSize = 18.sp) }
    }
    Text(t("recommendOnly"), color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
    for ((k, s) in listOf("settings" to "settings", "updates" to "updates", "diag" to "diag"))
        OutlinedButton(onClick = { screen.value = s }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(t(k)) }
    Text(t("version") + " " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
}

@Composable
private fun PermRow(name: String, ok: Boolean, grant: String, granted: String, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text((if (ok) "✅ " else "⬜ ") + name, modifier = Modifier.weight(1f))
        if (!ok) TextButton(onClick = onGrant) { Text(grant) } else Text(granted, color = Color(0xFF66BB6A), fontSize = 12.sp)
    }
}

@Composable
private fun SettingsScreen(prefs: Prefs, lang: String, setLang: (String) -> Unit, t: (String) -> String) {
    var autoR by remember { mutableStateOf(prefs.autoRefresh) }; var arrow by remember { mutableStateOf(prefs.showArrow) }
    var autoU by remember { mutableStateOf(prefs.autoUpdateCheck) }; var six by remember { mutableStateOf(prefs.sixToEnter) }
    var exact by remember { mutableStateOf(prefs.exactFinish) }; var safe by remember { mutableStateOf(prefs.safeSquares) }
    val ctx = LocalContext.current
    Panel { OutlinedButton(onClick = { setLang(if (lang == "ar") "en" else "ar") }) { Text(t("lang")) } }
    Panel {
        SwitchRow(t("auto"), autoR) { autoR = it; prefs.autoRefresh = it }
        SwitchRow(t("arrow"), arrow) { arrow = it; prefs.showArrow = it }
        SwitchRow(t("autoupd"), autoU) { autoU = it; prefs.autoUpdateCheck = it; UpdateManager.schedulePeriodic(ctx) }
    }
    Panel {
        SwitchRow(t("six"), six) { six = it; prefs.sixToEnter = it }
        SwitchRow(t("exact"), exact) { exact = it; prefs.exactFinish = it }
        SwitchRow(t("safe"), safe) { safe = it; prefs.safeSquares = it }
    }
}

@Composable
private fun SwitchRow(label: String, v: Boolean, on: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, modifier = Modifier.weight(1f)); Switch(checked = v, onCheckedChange = on) }
}

@Composable
private fun UpdatesScreen(prefs: Prefs, lang: String, t: (String) -> String) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun runCheck() {
        busy = true; status = t("checking")
        thread {
            val r = UpdateManager.check(ctx)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                busy = false
                when (r) {
                    is UpdateManager.Result.Available -> { info = r.info; status = t("newver") + ": " + r.info.versionName }
                    UpdateManager.Result.UpToDate -> { info = null; status = t("uptodate") }
                    is UpdateManager.Result.Error -> status = t("failed") + " (" + r.message + ")"
                }
            }
        }
    }
    Panel {
        Text(t("version") + " " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
        Button(onClick = { runCheck() }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text(t("check")) }
        if (status.isNotEmpty()) Text(status, modifier = Modifier.padding(top = 8.dp), color = Gold)
    }
    info?.let { i ->
        Panel {
            Text(t("notes"), fontWeight = FontWeight.Bold); Text(i.notes(lang), modifier = Modifier.padding(top = 4.dp))
            Button(onClick = {
                busy = true; status = t("checking")
                thread {
                    val f = UpdateManager.download(ctx, i) { d, total -> if (total > 0) android.os.Handler(android.os.Looper.getMainLooper()).post { status = "${d * 100 / total}%" } }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        busy = false
                        if (f == null) status = t("failed") + " - SHA-256 / " + t("retry")
                        else status = if (UpdateManager.install(ctx, f)) "" else t("needsInstall")
                    }
                }
            }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text(t("download")) }
        }
    }
}

@Composable
private fun DiagScreen(prefs: Prefs, lang: String, t: (String) -> String) {
    Panel {
        Text(t("diag"), fontWeight = FontWeight.Bold)
        val ctx = LocalContext.current
        var crash by remember { mutableStateOf(CrashLog.read(ctx)) }
        if (crash.isNotEmpty()) {
            Text(if (lang == "ar") "آخر خطأ:" else "Last error:", color = Gold, modifier = Modifier.padding(top = 6.dp))
            Text(crash, fontSize = 10.sp, color = Color.White)
            TextButton(onClick = { CrashLog.clear(ctx); crash = "" }) { Text(if (lang == "ar") "مسح" else "Clear") }
        }
        Text(prefs.lastDiag.ifEmpty { "-" }, fontSize = 12.sp, color = Color.LightGray, modifier = Modifier.padding(top = 6.dp))
    }
}
