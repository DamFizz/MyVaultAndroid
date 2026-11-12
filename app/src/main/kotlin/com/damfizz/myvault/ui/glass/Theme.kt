package com.damfizz.myvault.ui.glass

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

data class GlassColors(
    val dark: Boolean,
    val accent: Color,
    val onAccent: Color,
    val base: Color,
    val blobs: List<Color>,
    val text: Color,
    val text2: Color,
    val text3: Color,
    /** fill of content groups (not glass): a quiet translucent material */
    val fill: Color,
    val field: Color,
    val separator: Color,
    val glassTint: Color,
    /** used instead of live blur on Android 8-11 */
    val glassFallback: Color,
    val rimHigh: Color,
    val rimLow: Color,
    val pill: Color,
    val scrim: Color,
    val danger: Color,
    val star: Color,
)

private val Indigo = Color(0xFF5B7CFA)

val DarkGlass = GlassColors(
    dark = true,
    accent = Color(0xFF7C9CFF),
    onAccent = Color.White,
    base = Color(0xFF070A16),
    blobs = listOf(Color(0x8C6D4DFF), Color(0x6622D3EE), Color(0x66EC4899), Color(0x4D3B82F6)),
    text = Color(0xFFF4F6FF),
    text2 = Color(0xB3E6EAFF),
    text3 = Color(0x73E6EAFF),
    fill = Color(0x14FFFFFF),
    field = Color(0x1AFFFFFF),
    separator = Color(0x1FFFFFFF),
    glassTint = Color(0x380A0F22),
    glassFallback = Color(0xE61A2036),
    rimHigh = Color(0x73FFFFFF),
    rimLow = Color(0x14FFFFFF),
    pill = Color(0x2EFFFFFF),
    scrim = Color(0x73000000),
    danger = Color(0xFFFF6B6B),
    star = Color(0xFFFFD166),
)

val LightGlass = GlassColors(
    dark = false,
    accent = Indigo,
    onAccent = Color.White,
    base = Color(0xFFEDF0F8),
    blobs = listOf(Color(0x80A5B4FC), Color(0x7399F6E4), Color(0x73FBCFE8), Color(0x66BAE6FD)),
    text = Color(0xFF0B1020),
    text2 = Color(0xA60B1020),
    text3 = Color(0x660B1020),
    fill = Color(0xA6FFFFFF),
    field = Color(0x0F0B1020),
    separator = Color(0x1A0B1020),
    glassTint = Color(0x47FFFFFF),
    glassFallback = Color(0xF2FFFFFF),
    rimHigh = Color(0xE6FFFFFF),
    rimLow = Color(0x40FFFFFF),
    pill = Color(0x1A0B1020),
    scrim = Color(0x40000000),
    danger = Color(0xFFE5484D),
    star = Color(0xFFF5A524),
)

val LocalGlassColors = staticCompositionLocalOf { DarkGlass }

object Type {
    val largeTitle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, lineHeight = 40.sp)
    val title = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp, lineHeight = 30.sp)
    val headline = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 23.sp)
    val callout = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp)
}

/** Accent choices shared with the web app (name → light-theme colour, dark-theme colour). */
val Accents: Map<String, Pair<Color, Color>> = linkedMapOf(
    "blue" to (Color(0xFF5B7CFA) to Color(0xFF7C9CFF)),
    "teal" to (Color(0xFF0D9488) to Color(0xFF2DD4BF)),
    "violet" to (Color(0xFF7C3AED) to Color(0xFFA78BFA)),
    "emerald" to (Color(0xFF059669) to Color(0xFF34D399)),
    "rose" to (Color(0xFFE11D48) to Color(0xFFFB7185)),
    "amber" to (Color(0xFFD97706) to Color(0xFFFBBF24)),
)

@Composable
fun GlassTheme(dark: Boolean = isSystemInDarkTheme(), accent: String = "blue", content: @Composable () -> Unit) {
    val pair = Accents[accent] ?: Accents.getValue("blue")
    val colors = (if (dark) DarkGlass else LightGlass).copy(accent = if (dark) pair.second else pair.first)
    val scheme = if (dark) {
        darkColorScheme(primary = colors.accent, background = colors.base, surface = colors.base, onSurface = colors.text, onBackground = colors.text)
    } else {
        lightColorScheme(primary = colors.accent, background = colors.base, surface = colors.base, onSurface = colors.text, onBackground = colors.text)
    }
    CompositionLocalProvider(LocalGlassColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * Soft colour field behind everything; the glass refracts and tints it. Animated only where asked
 * (auth screens): a still wallpaper lets idle screens stop redrawing completely.
 */
@Composable
fun Wallpaper(modifier: Modifier = Modifier, animated: Boolean = false) {
    val c = LocalGlassColors.current
    val phase: State<Float> = if (animated) {
        rememberInfiniteTransition(label = "wallpaper").animateFloat(0f, 1f, infiniteRepeatable(tween(16_000), RepeatMode.Reverse), label = "phase")
    } else {
        remember { mutableFloatStateOf(0.35f) }
    }
    Box(
        modifier
            .background(c.base)
            .drawBehind {
                val w = size.width
                val h = size.height
                val p = phase.value
                fun blob(cx: Float, cy: Float, r: Float, color: Color) =
                    drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
                blob(w * (0.12f + 0.22f * p), h * (0.14f + 0.04f * p), w * 0.85f, c.blobs[0])
                blob(w * (0.98f - 0.26f * p), h * (0.42f + 0.1f * p), w * 0.75f, c.blobs[1])
                blob(w * (0.25f + 0.32f * p), h * (0.96f - 0.06f * p), w * 0.9f, c.blobs[2])
                blob(w * (0.8f - 0.1f * p), h * 0.02f, w * 0.55f, c.blobs[3])
            },
    )
}
