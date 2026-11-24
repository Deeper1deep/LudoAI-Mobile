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
        "learning" to ("وضع التعلّم: سجّل لقطات اللوحة والنرد على هاتفي" to "Learning mode: save board and dice snapshots on my phone"),
        "share" to ("مشاركة ملف التعلّم" to "Share learning file"),
        "clearlearn" to ("مسح بيانات التعلّم" to "Clear learning data"),
        "learnnote" to ("يحفظ لقطات صغيرة للوحة وخانات النرد (بدون أسماء أو صور اللاعبين) وسجل أحداث. لا يُرسل شيء إلا لو ضغطت مشاركة." to "Saves a few small snapshots of the board and dice slots (no player names or avatars) and an event log. Nothing is sent unless you tap Share."),
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
        "extra6" to ("رمية إضافية عند الحصول على 6" to "Extra turn after rolling a 6"),
        "autoHint" to ("مفعّل افتراضياً. يحلل فقط عند تغيّر الشاشة ولا يستهلك الكثير من البطارية" to "On by default. Analyses only when the screen changes, so idle screens cost almost nothing"),
        "manualOn" to ("✋ نرد يدوي نشط — لهذه الرمية فقط" to "✋ Manual dice active — this roll only"),
        "manualClear" to ("إلغاء" to "Clear"),
        "finishedDecl" to ("🏁 قطع وصلت للنهاية" to "🏁 Pieces finished"),
        "alternatives" to ("بدائل" to "Alternatives"),
        "risks" to ("المخاطر" to "Risks"),
        "required" to ("تحديث مطلوب" to "Update required"),
        "waiting" to ("بانتظار تحليل الشاشة..." to "Waiting to analyse the screen..."),
        "recommendOnly" to ("يقترح الحركة فقط، ولا يضغط على الشاشة أبداً" to "Recommends only; it never taps the game"),
    )
    fun get(key: String, lang: String): String = t[key]?.let { if (lang == "ar") it.first else it.second } ?: key

    /** Human text for a problem/notice code from the analysis. Unknown codes are shown as-is (never hidden). */
    fun problem(code: String, lang: String): String {
        val ar = lang == "ar"
        return when {
            code == "BOARD_NOT_FOUND" -> if (ar) "لم أجد لوحة اللعبة على الشاشة" else "Game board not found on screen"
            code == "BOARD_OVERLAY" -> if (ar) "شيء يغطي اللوحة (إشعار أو نافذة)" else "Something covers the board (popup or toast)"
            code == "DICE_NOT_FOUND" -> if (ar) "لا أرى نرداً على الشاشة" else "No dice visible on screen"
            code == "DICE_ANIMATING" -> if (ar) "النرد يتحرك، انتظر قليلاً" else "Dice is rolling, wait a moment"
            code == "DICE_NOT_STABLE" -> if (ar) "أتأكد من رقم النرد..." else "Confirming the dice value..."
            code == "DICE_STALE" -> if (ar) "هذا النرد قديم (استُخدم بالفعل). انتظر رمية جديدة" else "This dice was already used. Wait for a new roll"
            code == "TURN_UNCERTAIN" -> if (ar) "لست متأكداً من صاحب الدور" else "Not sure whose turn it is"
            code == "NOT_YOUR_TURN" -> if (ar) "ليس دورك الآن" else "It is not your turn"
            code == "PIECES_UNKNOWN_USER" -> if (ar) "لم أتعرف على كل قطعك الأربع. التقط مرة أخرى أو حدد عدد القطع الواصلة" else "Could not identify all four of your pieces. Capture again or set how many have finished"
            code == "INCONSISTENT_PIECES" -> if (ar) "قراءة اللوحة غير متسقة" else "Board reading is inconsistent"
            code.startsWith("TOO_MANY_PIECES") -> if (ar) "عدد القطع المكتشفة أكبر من 4" else "More than four pieces detected"
            code == "DUPLICATE_SEAT_COLORS" -> if (ar) "لونان متشابهان في مقعدين" else "Two seats look the same colour"
            code.startsWith("SEAT_COLOR_UNKNOWN") -> if (ar) "لم أتعرف على لون مقعدك" else "Could not read your seat colour"
            code == "NO_LEGAL_MOVE" -> if (ar) "لا توجد حركة متاحة بهذا الرقم" else "No legal move with this roll"
            code == "RECALIBRATED" -> if (ar) "تمت إعادة المعايرة (مباراة أو تخطيط جديد)" else "Recalibrated (new match or layout)"
            code == "HIDDEN_OPPONENT_PIECES" -> if (ar) "بعض قطع الخصوم غير ظاهرة" else "Some opponent pieces are not visible"
            code == "LOW_CONFIDENCE" -> if (ar) "ثقة التعرف منخفضة" else "Recognition confidence is low"
            else -> code
        }
    }

    fun risk(code: com.ludoai.assistant.core.RiskCode, p: Double, lang: String): String {
        val pc = (p * 100).toInt(); val ar = lang == "ar"
        return when (code) {
            com.ludoai.assistant.core.RiskCode.MOVED_PIECE_CAPTURED -> if (ar) "احتمال أكل القطعة المتحركة $pc%" else "$pc% chance the moved piece is captured"
            com.ludoai.assistant.core.RiskCode.ANY_PIECE_CAPTURED -> if (ar) "احتمال أكل إحدى قطعك $pc%" else "$pc% chance one of your pieces is captured"
            com.ludoai.assistant.core.RiskCode.LANDS_ON_DANGER -> if (ar) "تقف في مربع مهدد ($pc%)" else "Lands on a threatened square ($pc%)"
        }
    }

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
