package com.damfizz.myvault

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.fragment.app.FragmentActivity
import com.damfizz.myvault.ui.AppRoot
import com.damfizz.myvault.ui.glass.GlassTheme
import com.damfizz.myvault.ui.VaultViewModel

/** FragmentActivity (not ComponentActivity) because BiometricPrompt needs it. */
class MainActivity : FragmentActivity() {
    private val vm: VaultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, screen recording or recents thumbnails of vault contents (release builds;
        // debug builds allow screenshots so the UI can be tested on an emulator).
        if (!BuildConfig.DEBUG) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            val dark = when (vm.prefs.theme) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            val wallpaper = vm.prefs.wallpaper
            val version = vm.prefs.wallpaperVersion
            val photo = androidx.compose.runtime.remember(wallpaper, version) {
                if (wallpaper != "photo") null
                else runCatching { android.graphics.BitmapFactory.decodeFile(vm.wallpaperFile().path)?.asImageBitmap() }.getOrNull()
            }
            val access = rememberGlassAccessibility(vm.prefs)
            GlassTheme(dark = dark, accent = vm.prefs.accent, wallpaper = wallpaper, wallpaperImage = photo, accessibility = access) { AppRoot(vm) }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        vm.onUserInteraction()
    }

    override fun onStop() {
        super.onStop()
        vm.onBackground()
    }
}

object BuildConfigCompat {
    fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
}

/**
 * Motion / transparency / contrast settings for the glass: the in-app choices, plus the system's
 * "Remove animations" (animator scale 0) and, on Android 14+, the system contrast level.
 * Re-read whenever the app comes back to the foreground.
 */
@androidx.compose.runtime.Composable
private fun rememberGlassAccessibility(prefs: com.damfizz.myvault.data.Prefs): com.damfizz.myvault.ui.glass.GlassAccessibility {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val systemNoAnim = androidx.compose.runtime.remember(tick) {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val systemContrast = androidx.compose.runtime.remember(tick) {
        if (android.os.Build.VERSION.SDK_INT >= 34) (context.getSystemService(android.app.UiModeManager::class.java)?.contrast ?: 0f) >= 0.5f else false
    }
    return com.damfizz.myvault.ui.glass.GlassAccessibility(
        reduceMotion = prefs.motion == "reduced" || (prefs.motion == "system" && systemNoAnim),
        reduceTransparency = prefs.reduceTransparency,
        highContrast = prefs.highContrast || systemContrast,
    )
}
