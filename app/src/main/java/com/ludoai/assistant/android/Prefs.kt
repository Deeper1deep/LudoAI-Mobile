package com.ludoai.assistant.android

import android.content.Context
import com.ludoai.assistant.core.GameMode
import com.ludoai.assistant.core.RuleSet

class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("ludoai", Context.MODE_PRIVATE)
    var lang: String get() = p.getString("lang", "ar")!!; set(v) = p.edit().putString("lang", v).apply()
    var mode: GameMode get() = if (p.getInt("players", 4) == 2) GameMode.QUICK_2P else GameMode.CLASSIC_4P
        set(v) = p.edit().putInt("players", v.players).apply()
    /** Explicit default: ON. Analysis only runs when the screen actually changes (see AutoRefreshGate), so an idle screen costs almost nothing. */
    var autoRefresh: Boolean get() = p.getBoolean("auto", DEFAULT_AUTO_REFRESH); set(v) = p.edit().putBoolean("auto", v).apply()
    var showArrow: Boolean get() = p.getBoolean("arrow", true); set(v) = p.edit().putBoolean("arrow", v).apply()
    var autoUpdateCheck: Boolean get() = p.getBoolean("autoupd", true); set(v) = p.edit().putBoolean("autoupd", v).apply()
    var sixToEnter: Boolean get() = p.getBoolean("six", true); set(v) = p.edit().putBoolean("six", v).apply()
    var exactFinish: Boolean get() = p.getBoolean("exact", true); set(v) = p.edit().putBoolean("exact", v).apply()
    var safeSquares: Boolean get() = p.getBoolean("safe", true); set(v) = p.edit().putBoolean("safe", v).apply()
    /** Whether rolling a 6 gives another turn. Real Yalla Ludo behaviour is NOT verified for every mode, so the user can change it. */
    var extraTurnOnSix: Boolean get() = p.getBoolean("extra6", true); set(v) = p.edit().putBoolean("extra6", v).apply()
    var overlayX: Int get() = p.getInt("ox", 20); set(v) = p.edit().putInt("ox", v).apply()
    var overlayY: Int get() = p.getInt("oy", 300); set(v) = p.edit().putInt("oy", v).apply()
    var learning: Boolean get() = p.getBoolean("learn", false); set(v) = p.edit().putBoolean("learn", v).apply()
    var lastDiag: String get() = p.getString("diag", "")!!; set(v) = p.edit().putString("diag", v).apply()
    var lastUpdateJson: String get() = p.getString("updjson", "")!!; set(v) = p.edit().putString("updjson", v).apply()
    val rules get() = RuleSet(sixToEnter, exactFinish, safeSquares, extraTurnOnSix)

    companion object { const val DEFAULT_AUTO_REFRESH = true }
}
