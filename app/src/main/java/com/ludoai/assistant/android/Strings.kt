package com.ludoai.assistant.android

import com.ludoai.assistant.core.ReasonCode

object S {
    private val t: Map<String, Pair<String, String>> = mapOf(
        "title" to ("مساعد لودو الذكي" to "Ludo AI Assistant"),
        "start" to ("ابدأ المساعد" to "Start assistant"),
        "stop" to ("إيقاف المساعد" to "Stop assistant"),
        "mode" to ("نوع اللعبة" to "Game mode"),
        "m4" to ("كلاسيك 4 لاعبين" to "Classic 4 players"),
        "m2" to ("سريع لاعبين" to "Quick 2 players"),
        "perm_overlay" to ("إذن الظهور فوق التطبيقات" to "Display over other apps"),
        "perm_capture" to ("إذن التقاط الشاشة" to "Screen capture"),
        "perm_notif" to ("الإشعارات" to "Notifications"),
        "granted" to ("مفعّل" to "Granted"),
        "grant" to ("تفعيل" to "Grant"),
        "settings" to ("الإعدادات" to "Settings"),
        "updates" to ("التحديثات" to "Updates"),
        "diag" to ("التشخيص" to "Diagnostics"),
        "about" to ("عن التطبيق" to "About"),
        "lang" to ("اللغة: العربية / English" to "Language: العربية / English"),
        "auto" to ("تحديث تلقائي للتوصية" to "Auto-refresh recommendation"),
        "arrow" to ("إظهار السهم فوق القطعة" to "Show arrow over the piece"),
        "autoupd" to ("فحص التحديثات تلقائياً" to "Check for updates automatically"),
        "six" to ("رقم 6 لإخراج القطعة" to "Need a 6 to leave the yard"),
        "exact" to ("رقم دقيق للدخول للمنزل" to "Exact roll needed to finish"),
        "safe" to ("المربعات الآمنة" to "Safe squares"),
        "check" to ("افحص التحديثات" to "Check for updates"),
        "download" to ("تحميل وتثبيت" to "Download and install"),
        "uptodate" to ("أنت على آخر إصدار" to "You are up to date"),
        "newver" to ("إصدار جديد متاح" to "New version available"),
        "checking" to ("جاري الفحص..." to "Checking..."),
        "failed" to ("فشل. حاول مرة أخرى" to "Failed. Try again"),
        "retry" to ("إعادة المحاولة" to "Retry"),
        "version" to ("الإصدار" to "Version"),
        "refresh" to ("تحديث" to "Refresh"),
        "recapture" to ("لم أتعرف على اللوحة أو النرد بثقة. التقط مرة أخرى" to "Board or dice not recognised with confidence. Capture again"),
        "pickdice" to ("أو اختر رقم النرد:" to "Or pick the dice:"),
        "confidence" to ("الثقة" to "Confidence"),
        "you" to ("لونك" to "Your colour"),
        "noMove" to ("لا توجد حركة متاحة بهذا الرقم" to "No legal move with this roll"),
        "play" to ("حرّك" to "Move"),
        "yardPiece" to ("قطعة من البيت (اطلعها للبداية)" to "a piece from the yard (bring it out)"),
        "piece_at" to ("القطعة في الخانة" to "the piece at cell"),
        "needsInstall" to ("اسمح بتثبيت التطبيقات من هذا المصدر ثم ارجع" to "Allow installing from this source, then come back"),
        "red" to ("أحمر" to "Red"), "green" to ("أخضر" to "Green"), "yellow" to ("أصفر" to "Yellow"), "blue" to ("أزرق" to "Blue"),
        "notes" to ("ملاحظات الإصدار" to "Release notes"),
        "recommendOnly" to ("يقترح الحركة فقط، ولا يضغط على الشاشة أبداً" to "Recommends only; it never taps the game"),
    )
    fun get(key: String, lang: String): String = t[key]?.let { if (lang == "ar") it.first else it.second } ?: key

    fun reason(code: ReasonCode, v: Double, lang: String): String {
        val ar = lang == "ar"
        return when (code) {
            ReasonCode.CAPTURE -> if (ar) "تأكل قطعة للخصم" else "Captures an opponent piece"
            ReasonCode.FINISH -> if (ar) "توصّل قطعة للنهاية" else "Brings a piece home"
            ReasonCode.ENTER -> if (ar) "تُدخل قطعة جديدة للعب" else "Brings a new piece into play"
            ReasonCode.ENTER_HOME_COLUMN -> if (ar) "تدخل ممر المنزل الآمن" else "Enters the safe home lane"
            ReasonCode.SAFE_SQUARE -> if (ar) "تقف على مربع آمن" else "Lands on a safe square"
            ReasonCode.ESCAPE_DANGER -> if (ar) "تهرب من خطر الأكل" else "Escapes a capture threat"
            ReasonCode.PROTECT_ADVANCED -> if (ar) "تحمي قطعة متقدمة من الأكل" else "Protects an advanced piece"
            ReasonCode.LOW_RISK -> if (ar) "خطر الأكل بعد الحركة قليل (${(v * 100).toInt()}%)" else "Low risk after moving (${(v * 100).toInt()}%)"
            ReasonCode.RISKY -> if (ar) "تنبيه: احتمال الأكل ${(v * 100).toInt()}%" else "Warning: ${(v * 100).toInt()}% chance of being captured"
            ReasonCode.PROGRESS -> if (ar) "تقدّم ${v.toInt()} خطوات" else "Advances ${v.toInt()} steps"
            ReasonCode.PRESSURE -> if (ar) "تهدد ${v.toInt()} من قطع الخصم" else "Threatens ${v.toInt()} opponent piece(s)"
            ReasonCode.STACK -> if (ar) "تجمع قطعتين" else "Stacks two pieces"
            ReasonCode.ONLY_MOVE -> if (ar) "الحركة الوحيدة المتاحة" else "Only legal move"
        }
    }
}
