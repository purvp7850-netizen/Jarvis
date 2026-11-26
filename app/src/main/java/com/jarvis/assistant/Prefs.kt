package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun get(c: Context, key: String, default: String): String =
        sp(c).getString(key, default)?.takeIf { it.isNotBlank() } ?: default

    fun put(c: Context, key: String, value: String) {
        sp(c).edit().putString(key, value).apply()
    }

    fun memories(c: Context): List<String> {
        val arr = try { JSONArray(sp(c).getString("memory", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun setMemories(c: Context, items: List<String>) {
        val arr = JSONArray()
        items.takeLast(100).forEach { arr.put(it) }
        sp(c).edit().putString("memory", arr.toString()).apply()
    }
}
