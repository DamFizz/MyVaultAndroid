package com.damfizz.myvault

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
            GlassTheme(dark = dark, accent = vm.prefs.accent, wallpaper = wallpaper, wallpaperImage = photo) { AppRoot(vm) }
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
