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

    var driveConnected by mutableStateOf(sp.getBoolean("driveConnected", false)); private set
    var driveEmail by mutableStateOf(sp.getString("driveEmail", null)); private set
    var autoSync by mutableStateOf(sp.getBoolean("autoSync", true)); private set

    fun updateDrive(connected: Boolean, email: String?) {
        driveConnected = connected; driveEmail = email
        sp.edit().putBoolean("driveConnected", connected).putString("driveEmail", email).apply()
    }
    var biometricOfferDismissed by mutableStateOf(sp.getBoolean("biometricOfferDismissed", false)); private set
    fun updateBiometricOfferDismissed(v: Boolean) { biometricOfferDismissed = v; sp.edit().putBoolean("biometricOfferDismissed", v).apply() }

    var autoUpdateCheck by mutableStateOf(sp.getBoolean("autoUpdateCheck", true)); private set
    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0)
        set(v) { sp.edit().putLong("lastUpdateCheck", v).apply() }
    fun updateAutoUpdateCheck(v: Boolean) { autoUpdateCheck = v; sp.edit().putBoolean("autoUpdateCheck", v).apply() }

    /** midnight · graphite · ocean · sunset · aurora · plain · photo */
    var wallpaper by mutableStateOf(sp.getString("wallpaper", "midnight")!!); private set
    /** bumped whenever the photo file changes, so it is reloaded */
    var wallpaperVersion by mutableStateOf(sp.getInt("wallpaperVersion", 0)); private set
    fun updateWallpaper(v: String) { wallpaper = v; sp.edit().putString("wallpaper", v).apply() }
    fun bumpWallpaperVersion() { wallpaperVersion += 1; sp.edit().putInt("wallpaperVersion", wallpaperVersion).apply() }

    /** Optional name for the lock-screen greeting. Stored on this device only, outside the vault
     *  (it must be readable before unlocking), so it should not be anything secret. */
    var displayName by mutableStateOf(sp.getString("displayName", "")!!); private set
    fun updateDisplayName(v: String) { displayName = v.trim().take(40); sp.edit().putString("displayName", displayName).apply() }

    /** Frostier, more opaque glass without refraction. */
    var reduceTransparency by mutableStateOf(sp.getBoolean("reduceTransparency", false)); private set
    /** Stronger outlines and more opaque glass. */
    var highContrast by mutableStateOf(sp.getBoolean("highContrast", false)); private set
    fun updateReduceTransparency(v: Boolean) { reduceTransparency = v; sp.edit().putBoolean("reduceTransparency", v).apply() }
    fun updateHighContrast(v: Boolean) { highContrast = v; sp.edit().putBoolean("highContrast", v).apply() }

    fun updateAutoSync(v: Boolean) { autoSync = v; sp.edit().putBoolean("autoSync", v).apply() }

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
