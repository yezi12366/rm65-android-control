package com.realman.rm65webview

import android.content.SharedPreferences
import org.json.JSONArray

class ActionStore(private val preferences: SharedPreferences) {
    fun list(): List<ActionRecord> = runCatching {
        val array = JSONArray(preferences.getString(KEY, "[]") ?: "[]")
        List(array.length()) { index ->
            runCatching { ActionRecord.fromJson(array.getJSONObject(index)) }.getOrNull()
        }.filterNotNull()
            .sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    fun get(id: String): ActionRecord? = list().firstOrNull { it.id == id }

    fun save(action: ActionRecord) {
        val actions = list().filterNot { it.id == action.id } + action
        write(actions)
    }

    fun rename(id: String, displayName: String, now: Long): Boolean {
        val cleanName = displayName.trim()
        if (cleanName.isBlank()) return false
        var changed = false
        val actions = list().map {
            if (it.id == id) {
                changed = true
                it.copy(displayName = cleanName, updatedAt = now)
            } else it
        }
        if (changed) write(actions)
        return changed
    }

    fun delete(id: String): ActionRecord? {
        val actions = list()
        val removed = actions.firstOrNull { it.id == id } ?: return null
        write(actions.filterNot { it.id == id })
        return removed
    }

    private fun write(actions: List<ActionRecord>) {
        val array = JSONArray().apply { actions.forEach { put(it.toJson()) } }
        preferences.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val KEY = "action_library_v1"
    }
}
