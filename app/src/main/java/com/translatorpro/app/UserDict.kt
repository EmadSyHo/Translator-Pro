package com.translatorpro.app

import android.content.Context

object UserDict {
    private const val FIXED = "\u0001"
    private var map: Map<String, String> = emptyMap()
    private var regex: Regex? = null

    fun load(c: Context) {
        val m = LinkedHashMap<String, String>()
        for (line in Prefs.getString(c, "userDict", "").lines()) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            val en = line.substring(0, i).trim()
            val ar = line.substring(i + 1).trim()
            if (en.isNotEmpty() && ar.isNotEmpty()) m[en.lowercase()] = ar
        }
        map = m
        regex = if (m.isEmpty()) null else {
            val alt = m.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
            Regex("(?<![A-Za-z])($alt)(?![A-Za-z])", RegexOption.IGNORE_CASE)
        }
    }

    // يقسم النص عند كلمات القاموس. الأجزاء التي تبدأ بـ \u0001 جاهزة ولا تُترجم
    fun parts(text: String): List<String> {
        val rx = regex ?: return listOf(text)
        val out = ArrayList<String>()
        var last = 0
        for (m in rx.findAll(text)) {
            addPlain(out, text.substring(last, m.range.first))
            val ar = map[m.value.lowercase()] ?: m.value
            out.add(FIXED + ar)
            last = m.range.last + 1
        }
        addPlain(out, text.substring(last))
        return out
    }

    private fun addPlain(out: MutableList<String>, s: String) {
        val t = s.trim()
        if (t.isEmpty()) return
        if (t.any { it.isLetter() }) out.add(t)
        else out.add(FIXED + t.replace(',', '،').replace('?', '؟'))
    }
}
