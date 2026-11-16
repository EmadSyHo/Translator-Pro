package com.translatorpro.app

import com.google.mlkit.nl.translate.Translator

object TranslationEngine {

    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 300
        }
    }

    // تصحيحات بعد الترجمة: "الصيغة الخاطئة" to "الصيغة الصحيحة"
    // أضف هنا أي كلمة تراها مترجمة بشكل سيئ في لعبتك
    private val fixes = mapOf<String, String>(
        // "كلمة خاطئة" to "كلمة صحيحة",
    )

    // مصطلحات الألعاب (المفتاح بحروف صغيرة)
    private val terms = mapOf(
        "start" to "ابدأ", "start game" to "ابدأ اللعبة", "press start" to "اضغط للبدء",
        "new game" to "لعبة جديدة", "continue" to "متابعة", "load" to "تحميل",
        "load game" to "تحميل اللعبة", "save" to "حفظ", "save game" to "حفظ اللعبة",
        "game saved" to "تم حفظ اللعبة", "settings" to "الإعدادات", "options" to "الخيارات",
        "resume" to "استئناف", "pause" to "إيقاف مؤقت", "paused" to "اللعبة متوقفة مؤقتاً",
        "quit" to "خروج", "exit" to "خروج", "menu" to "القائمة", "main menu" to "القائمة الرئيسية",
        "back" to "رجوع", "next" to "التالي", "previous" to "السابق", "skip" to "تخطي",
        "yes" to "نعم", "no" to "لا", "ok" to "موافق", "okay" to "موافق",
        "cancel" to "إلغاء", "confirm" to "تأكيد", "accept" to "قبول", "decline" to "رفض",
        "close" to "إغلاق", "retry" to "إعادة المحاولة", "select" to "اختيار", "apply" to "تطبيق",
        "game over" to "انتهت اللعبة", "you died" to "لقد مُتَّ", "you win" to "لقد فزت",
        "you lose" to "لقد خسرت", "victory" to "النصر", "defeat" to "الهزيمة",
        "level up" to "ارتفع المستوى", "level" to "المستوى", "new quest" to "مهمة جديدة",
        "quest" to "مهمة", "quests" to "المهام", "quest complete" to "اكتملت المهمة",
        "quest completed" to "اكتملت المهمة", "quest updated" to "تم تحديث المهمة",
        "mission" to "مهمة", "mission complete" to "اكتملت المهمة", "mission failed" to "فشلت المهمة",
        "objective" to "الهدف", "objectives" to "الأهداف", "main quest" to "المهمة الرئيسية",
        "side quest" to "مهمة جانبية", "inventory" to "الحقيبة", "items" to "العناصر",
        "item" to "عنصر", "weapons" to "الأسلحة", "weapon" to "سلاح", "armor" to "الدرع",
        "skills" to "المهارات", "skill" to "مهارة", "abilities" to "القدرات", "map" to "الخريطة",
        "health" to "الصحة", "hp" to "نقاط الصحة", "mana" to "المانا", "mp" to "نقاط المانا",
        "stamina" to "التحمل", "energy" to "الطاقة", "experience" to "الخبرة", "exp" to "الخبرة",
        "xp" to "الخبرة", "gold" to "الذهب", "coins" to "العملات", "coin" to "عملة",
        "gems" to "الجواهر", "money" to "المال", "shop" to "المتجر", "store" to "المتجر",
        "buy" to "شراء", "sell" to "بيع", "equip" to "تجهيز", "unequip" to "إزالة",
        "use" to "استخدام", "upgrade" to "ترقية", "craft" to "صناعة", "crafting" to "الصناعة",
        "loading" to "جارٍ التحميل", "loading..." to "جارٍ التحميل...", "saving" to "جارٍ الحفظ",
        "press any button" to "اضغط أي زر", "press any key" to "اضغط أي مفتاح",
        "tap to continue" to "المس للمتابعة", "tap to start" to "المس للبدء",
        "touch to start" to "المس للبدء", "checkpoint" to "نقطة حفظ",
        "achievements" to "الإنجازات", "achievement unlocked" to "تم فتح إنجاز",
        "tutorial" to "التعليمات", "hint" to "تلميح", "help" to "مساعدة",
        "boss" to "الزعيم", "enemy" to "عدو", "enemies" to "الأعداء", "attack" to "هجوم",
        "defend" to "دفاع", "defense" to "الدفاع", "run" to "هروب", "flee" to "هروب",
        "talk" to "تحدث", "examine" to "فحص", "open" to "فتح", "search" to "بحث",
        "pick up" to "التقاط", "drop" to "إسقاط", "characters" to "الشخصيات",
        "character" to "الشخصية", "party" to "الفريق", "team" to "الفريق",
        "sound" to "الصوت", "music" to "الموسيقى", "language" to "اللغة", "controls" to "التحكم",
        "graphics" to "الرسومات", "volume" to "مستوى الصوت", "difficulty" to "الصعوبة",
        "easy" to "سهل", "normal" to "عادي", "hard" to "صعب", "auto" to "تلقائي",
        "on" to "تشغيل", "off" to "إيقاف", "reward" to "المكافأة", "rewards" to "المكافآت",
        "congratulations" to "تهانينا", "warning" to "تحذير", "error" to "خطأ",
        "new" to "جديد", "locked" to "مقفل", "unlocked" to "مفتوح", "damage" to "الضرر",
        "critical hit" to "ضربة قاضية", "miss" to "أخطأت", "turn" to "الدور",
        "your turn" to "دورك", "ready" to "جاهز", "wait" to "انتظر", "go" to "انطلق",
        "chapter" to "الفصل", "stage" to "المرحلة", "day" to "اليوم", "night" to "الليل",
        "time" to "الوقت", "score" to "النتيجة", "best score" to "أفضل نتيجة",
        "ranking" to "الترتيب", "friends" to "الأصدقاء", "gallery" to "المعرض",
        "credits" to "شكر وتقدير", "thank you" to "شكراً لك", "thanks" to "شكراً",
        "sorry" to "آسف", "hello" to "مرحباً", "goodbye" to "وداعاً"
    )

    fun translate(translator: Translator, raw: String, onDone: (String) -> Unit) {
        val parts = split(clean(raw))
        if (parts.isEmpty()) {
            onDone(raw)
            return
        }
        val results = arrayOfNulls<String>(parts.size)
        var remaining = parts.size
        parts.forEachIndexed { i, s ->
            translateOne(translator, s) { r ->
                results[i] = r
                remaining--
                if (remaining == 0) onDone(results.joinToString(" "))
            }
        }
    }

    private fun translateOne(translator: Translator, s: String, cb: (String) -> Unit) {
        terms[key(s)]?.let { cb(it); return }
        cache[s]?.let { cb(it); return }
        translator.translate(s)
            .addOnSuccessListener { t ->
                val f = applyFixes(t)
                cache[s] = f
                cb(f)
            }
            .addOnFailureListener { cb(s) }
    }

    private fun key(s: String): String =
        s.lowercase().trim().trimEnd('.', '!', ':', '…', ' ')

    private fun applyFixes(t: String): String {
        var r = t
        for ((bad, good) in fixes) r = r.replace(bad, good)
        return r
    }

    private fun clean(t: String): String {
        var s = t.replace('’', '\'').replace('‘', '\'')
            .replace('“', '"').replace('”', '"')
        s = s.replace(Regex("\\|(?=[a-z'])"), "I")
        s = s.replace(Regex("(?<=\\s)\\|(?=\\s|$)"), "I")
        s = s.replace(Regex("^\\|(?=\\s)"), "I")

        val letters = s.filter { it.isLetter() }
        if (letters.length > 3) {
            val up = letters.count { it.isUpperCase() }
            if (up * 100 / letters.length >= 70) {
                s = s.lowercase()
                s = s.replace(Regex("\\bi\\b"), "I")
                s = s.replace(Regex("(^|[.!?]\\s+)([a-z])")) {
                    it.groupValues[1] + it.groupValues[2].uppercase()
                }
            }
        }
        return s.trim()
    }

    private fun split(t: String): List<String> {
        return t.split(Regex("(?<=[.!?…])\\s+"))
            .map { it.trim() }
            .filter { p -> p.any { it.isLetter() } }
    }
}
