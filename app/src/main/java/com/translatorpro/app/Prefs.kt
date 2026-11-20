package com.translatorpro.app

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("tp_prefs", Context.MODE_PRIVATE)
    fun getInt(c: Context, key: String, def: Int): Int = sp(c).getInt(key, def)
    fun putInt(c: Context, key: String, v: Int) { sp(c).edit().putInt(key, v).apply() }
    fun getString(c: Context, key: String, def: String): String = sp(c).getString(key, def) ?: def
    fun putString(c: Context, key: String, v: String) { sp(c).edit().putString(key, v).apply() }
}
