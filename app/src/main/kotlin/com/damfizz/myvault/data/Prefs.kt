package com.damfizz.myvault.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.damfizz.myvault.core.SortBy

/**
 * Device preferences — port of the web's `state/prefs.ts`. Not sensitive (appearance, timers), so
 * they live in SharedPreferences and are available before unlocking. No vault data is stored here.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("myvault.prefs", Context.MODE_PRIVATE)

    /** "dark" | "light" | "system" */
    var theme by mutableStateOf(sp.getString("theme", "system")!!); private set
    /** blue, teal, violet, emerald, rose, amber */
    var accent by mutableStateOf(sp.getString("accent", "blue")!!); private set
    /** "system" | "reduced" | "full" */
    var motion by mutableStateOf(sp.getString("motion", "system")!!); private set
    /** minutes of inactivity before locking; 0 = never */
    var autoLockMinutes by mutableStateOf(sp.getInt("autoLockMinutes", 10)); private set
    /** seconds in background before locking; 0 = immediately, -1 = never */
    var lockOnHiddenSeconds by mutableStateOf(sp.getInt("lockOnHiddenSeconds", 60)); private set
    /** seconds before a revealed secret is hidden again; 0 = keep until hidden manually */
    var revealSeconds by mutableStateOf(sp.getInt("revealSeconds", 20)); private set
    /** seconds before the clipboard is cleared; 0 = never */
    var clipboardClearSeconds by mutableStateOf(sp.getInt("clipboardClearSeconds", 30)); private set
    var sortBy by mutableStateOf(runCatching { SortBy.valueOf(sp.getString("sortBy", "TITLE")!!) }.getOrDefault(SortBy.TITLE)); private set
    var backupReminderDays by mutableStateOf(sp.getInt("backupReminderDays", 30)); private set

    fun updateTheme(v: String) { theme = v; sp.edit().putString("theme", v).apply() }
    fun updateAccent(v: String) { accent = v; sp.edit().putString("accent", v).apply() }
    fun updateMotion(v: String) { motion = v; sp.edit().putString("motion", v).apply() }
    fun updateAutoLockMinutes(v: Int) { autoLockMinutes = v; sp.edit().putInt("autoLockMinutes", v).apply() }
    fun updateLockOnHiddenSeconds(v: Int) { lockOnHiddenSeconds = v; sp.edit().putInt("lockOnHiddenSeconds", v).apply() }
    fun updateRevealSeconds(v: Int) { revealSeconds = v; sp.edit().putInt("revealSeconds", v).apply() }
    fun updateClipboardClearSeconds(v: Int) { clipboardClearSeconds = v; sp.edit().putInt("clipboardClearSeconds", v).apply() }
    fun updateSortBy(v: SortBy) { sortBy = v; sp.edit().putString("sortBy", v.name).apply() }
    fun updateBackupReminderDays(v: Int) { backupReminderDays = v; sp.edit().putInt("backupReminderDays", v).apply() }
}
