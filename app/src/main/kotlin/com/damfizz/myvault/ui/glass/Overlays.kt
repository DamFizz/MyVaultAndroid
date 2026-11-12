package com.damfizz.myvault.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ------------------------------------------------------------------ root + overlay host

class OverlayHost {
    internal val entries = mutableStateMapOf<Any, @Composable () -> Unit>()
    internal var toast by mutableStateOf<String?>(null)
    internal var toastId by mutableIntStateOf(0)

    internal var toastActionLabel by mutableStateOf<String?>(null)
    internal var toastAction: (() -> Unit)? = null

    fun toast(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        toast = message
        toastActionLabel = actionLabel
        toastAction = action
        toastId++
    }
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHost> { error("LiquidGlassRoot is missing") }

/**
 * Root of the glass UI. Records the whole screen so that sheets, alerts and toasts drawn above it
 * can blur and refract everything underneath (including the bars).
 */
@Composable
fun LiquidGlassRoot(content: @Composable () -> Unit) {
    val root = rememberBackdrop()
    val host = remember { OverlayHost() }
    CompositionLocalProvider(LocalOverlayHost provides host, LocalGlassLight provides rememberTiltLight()) {
        Box(Modifier.fillMaxSize().background(LocalGlassColors.current.base)) {
            Box(Modifier.fillMaxSize().backdropSource(root)) { content() }
            CompositionLocalProvider(LocalBackdrop provides root) {
                for ((k, entry) in host.entries) key(k) { entry() }
                ToastLayer(host)
            }
        }
    }
}

/** Compose [content] in the overlay layer above every screen. */
@Composable
fun GlassOverlay(content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current
    val current by rememberUpdatedState(content)
    val id = remember { Any() }
    DisposableEffect(id) {
        host.entries[id] = { current() }
        onDispose { host.entries.remove(id) }
    }
}

// ------------------------------------------------------------------ screen scaffold

/**
 * One screen: wallpaper + content (which scrolls under the bars) + floating glass bars.
 * Inside [content], glass sees only the wallpaper; the bars see wallpaper + content.
 */
@Composable
fun GlassScaffold(
    animatedWallpaper: Boolean = false,
    topBar: (@Composable BoxScope.() -> Unit)? = null,
    bottomBar: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val wall = rememberBackdrop()
    val behindBars = rememberBackdrop()
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val top = insets.calculateTopPadding()
    val bottom = insets.calculateBottomPadding()
    val padding = PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = top + if (topBar != null) TopBarHeight else 8.dp,
        bottom = bottom + if (bottomBar != null) BottomBarHeight else 16.dp,
    )
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(behindBars)) {
            Wallpaper(Modifier.fillMaxSize().backdropSource(wall), animatedWallpaper)
            CompositionLocalProvider(LocalBackdrop provides wall) {
                Box(Modifier.fillMaxSize().imePadding()) { content(padding) }
            }
        }
        CompositionLocalProvider(LocalBackdrop provides behindBars) {
            if (topBar != null) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = top + 6.dp, start = 16.dp, end = 16.dp).height(48.dp), content = topBar)
            }
            if (bottomBar != null) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = bottom + 10.dp, start = 20.dp, end = 20.dp), content = bottomBar)
            }
        }
    }
}

val TopBarHeight: Dp = 64.dp
val BottomBarHeight: Dp = 96.dp

// ------------------------------------------------------------------ sheet

/** Bottom sheet of glass: springs up, follows the finger, and flies away when dragged down. */
@Composable
fun GlassSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    GlassOverlay {
        val c = LocalGlassColors.current
        val progress by animateFloatAsState(if (visible) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 330f), label = "sheet")
        if (!visible && progress < 0.002f) return@GlassOverlay
        val scope = rememberCoroutineScope()
        val drag = remember { Animatable(0f) }
        var height by remember { mutableIntStateOf(0) }
        LaunchedEffect(visible) { if (visible) drag.snapTo(0f) }
        if (visible) BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress.coerceIn(0f, 1f) }
                    .background(c.scrim)
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
            val insets = WindowInsets.safeDrawing.asPaddingValues()
            GlassSurface(
                Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .padding(start = 8.dp, end = 8.dp, bottom = insets.calculateBottomPadding() + 8.dp)
                    .fillMaxWidth()
                    .onSizeChanged { height = it.height }
                    .offset { IntOffset(0, ((1f - progress) * (height + 200) + drag.value).roundToInt()) }
                    .draggable(
                        rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(-24f)) } },
                        Orientation.Vertical,
                        onDragStopped = { v ->
                            if (drag.value > height * 0.3f || v > 1400f) onDismiss()
                            else drag.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 400f))
                        },
                    ),
                shape = RoundedCornerShape(36.dp),
                blur = 30.dp,
                refraction = 18.dp,
                elevation = 24.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(width = 38.dp, height = 5.dp).clip(Capsule).background(c.text3))
                    content()
                }
            }
        }
    }
}

// ------------------------------------------------------------------ alert

@Composable
fun GlassAlert(
    visible: Boolean,
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    dismiss: String,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    GlassOverlay {
        val c = LocalGlassColors.current
        val progress by animateFloatAsState(if (visible) 1f else 0f, spring(dampingRatio = 0.68f, stiffness = 420f), label = "alert")
        if (!visible && progress < 0.002f) return@GlassOverlay
        if (visible) BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = progress.coerceIn(0f, 1f) }.background(c.scrim)
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
            GlassSurface(
                Modifier
                    .padding(32.dp)
                    .widthIn(max = 340.dp)
                    .graphicsLayer {
                        val s = 0.85f + 0.15f * progress
                        scaleX = s; scaleY = s
                        alpha = progress.coerceIn(0f, 1f)
                    },
                shape = RoundedCornerShape(32.dp),
                blur = 30.dp,
                elevation = 24.dp,
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(title, style = Type.headline, color = c.text)
                    Text(message, style = Type.callout, color = c.text2)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton(dismiss, onDismiss, Modifier.weight(1f), prominent = false)
                        GlassButton(confirm, onConfirm, Modifier.weight(1f), destructive = destructive)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ toast + busy

@Composable
private fun ToastLayer(host: OverlayHost) {
    val c = LocalGlassColors.current
    var shown by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(host.toastId) {
        val t = host.toast ?: return@LaunchedEffect
        text = t
        shown = true
        delay(if (host.toastActionLabel != null) 5000 else 2200)
        shown = false
    }
    val progress by animateFloatAsState(if (shown) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 380f), label = "toast")
    if (progress < 0.002f) return
    val top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        GlassSurface(
            Modifier
                .padding(top = top + 10.dp, start = 24.dp, end = 24.dp)
                .offset { IntOffset(0, ((progress - 1f) * 120.dp.toPx()).roundToInt()) }
                .graphicsLayer { alpha = progress.coerceIn(0f, 1f) },
            shape = Capsule,
            blur = 24.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, style = Type.callout, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.padding(start = 20.dp, end = if (host.toastActionLabel != null) 4.dp else 20.dp, top = 13.dp, bottom = 13.dp))
                host.toastActionLabel?.let { label ->
                    TextLink(label, { shown = false; host.toastAction?.invoke() }, Modifier.padding(end = 6.dp))
                }
            }
        }
    }
}

/** Blocks input while a slow operation (Argon2id) runs. */
@Composable
fun BusyOverlay(visible: Boolean, label: String) {
    GlassOverlay {
        val c = LocalGlassColors.current
        val progress by animateFloatAsState(if (visible) 1f else 0f, label = "busy")
        if (!visible && progress < 0.002f) return@GlassOverlay
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = progress }.background(c.scrim).pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center,
        ) {
            GlassSurface(shape = Capsule, blur = 26.dp) {
                Row(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(label, style = Type.callout, color = c.text)
                }
            }
        }
    }
}
