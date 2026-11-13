package com.damfizz.myvault.ui.glass

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class TabItem(val icon: ImageVector, val label: String)

/** How much bigger the lens is than the resting pill when lifted. */
private const val LENS_RISE = 0.10f

/**
 * Floating glass tab bar.
 *
 * At rest the selection is a flat pill (Apple: glass is quiet at rest and comes alive on touch).
 * When the bar is touched the pill lifts into a lens of clear glass that magnifies and refracts the
 * bar's own icons. It rises centred on the touched tab and holds still while the finger rests; a
 * clear sideways drag moves it, and on release it keeps the fling velocity, snaps to the nearest
 * tab and sinks back into a flat pill. Every phase is an interruptible, lightly damped spring.
 */
@Composable
fun GlassTabBar(tabs: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalGlassColors.current
    val access = LocalGlassAccessibility.current
    val rm = access.reduceMotion
    val scope = rememberCoroutineScope()
    val count = tabs.size
    val pos = remember { Animatable(selected.toFloat()) }
    val lift = remember { Animatable(0f) }
    val presence = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    val barContent = rememberBackdrop()
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val lensCapable = Build.VERSION.SDK_INT >= 31 && !access.reduceTransparency

    // Selection changed from elsewhere (e.g. a shortcut): the pill travels without lifting.
    LaunchedEffect(selected) {
        if (!dragging && pos.targetValue != selected.toFloat()) pos.animateTo(selected.toFloat(), GlassMotion.travel(rm))
    }

    GlassSurface(modifier.height(66.dp), shape = Capsule, material = GlassMaterials.Regular, blur = 22.dp, elevation = 18.dp) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .pointerInput(count, rm) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = size.width / count.toFloat()
                        fun index(x: Float) = (x / w - 0.5f).coerceIn(0f, count - 1f)
                        val tapped = (down.position.x / w).toInt().coerceIn(0, count - 1)
                        scope.launch { presence.animateTo(1f, GlassMotion.presence(rm)) }
                        scope.launch { lift.animateTo(1f, GlassMotion.lift(rm)) }
                        // The lens rises centred on the tab under the finger, not wherever the finger is.
                        if (tapped.toFloat() != pos.targetValue) scope.launch { pos.animateTo(tapped.toFloat(), GlassMotion.travel(rm)) }
                        // A finger resting on the bar always trembles a little: only a clear sideways
                        // movement counts as dragging the lens.
                        val dragStart = maxOf(viewConfiguration.touchSlop * 2.5f, 20.dp.toPx())
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var dragged = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            if (!dragged && abs(ch.position.x - down.position.x) > dragStart) {
                                dragged = true
                                dragging = true
                            }
                            if (dragged) {
                                ch.consume()
                                val target = index(ch.position.x)
                                scope.launch { pos.snapTo(target) }
                            }
                            if (!ch.pressed) break
                        }
                        val vx = if (dragged) tracker.calculateVelocity().x / w else 0f
                        // Project the fling a little forward, then snap to the nearest tab.
                        val target = if (dragged) (pos.value + vx * 0.12f).roundToInt().coerceIn(0, count - 1) else tapped
                        dragging = false
                        if (target != currentSelected) currentOnSelect(target)
                        scope.launch {
                            pos.animateTo(target.toFloat(), GlassMotion.travel(rm), initialVelocity = vx)
                            launch { lift.animateTo(0f, GlassMotion.flexOut(rm)) }
                            presence.animateTo(0f, GlassMotion.relax(rm))
                        }
                    }
                },
        ) {
            val cell = maxWidth / count
            // The bar's own content (icons + resting pill), recorded so the lens can refract it.
            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { barContent.origin = it.positionInRoot() }
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        barContent.layer.record { this@drawWithContent.drawContent() }
                        drawLayer(barContent.layer)
                        // While the lens is up it shows these icons refracted: hide the flat copy under it,
                        // so the bar's own glass shows through the lens instead of a double image.
                        val pr = presence.value
                        if (lensCapable && pr > 0.01f) {
                            val w = size.width / count
                            val up = 1f + LENS_RISE * lift.value
                            val lw = w * up - 2.dp.toPx()
                            val lh = size.height * up - 2.dp.toPx()
                            val cx = pos.value * w + w / 2f
                            drawRoundRect(
                                Color.Black,
                                topLeft = Offset(cx - lw / 2f, size.height / 2f - lh / 2f),
                                size = Size(lw, lh),
                                cornerRadius = CornerRadius(lh / 2f, lh / 2f),
                                alpha = pr.coerceIn(0f, 1f),
                                blendMode = BlendMode.DstOut,
                            )
                        }
                    }
                    .drawBehind {
                        val w = size.width / count
                        val inset = 4.dp.toPx()
                        val p = if (lensCapable) presence.value else 0f
                        val l = if (lensCapable) 0f else lift.value
                        val grow = l * 3.dp.toPx()
                        drawRoundRect(
                            c.pill.copy(alpha = c.pill.alpha * (1f - p * 0.85f)),
                            topLeft = Offset(pos.value * w + inset - grow, inset - grow),
                            size = Size(w - inset * 2 + grow * 2, size.height - inset * 2 + grow * 2),
                            cornerRadius = CornerRadius(size.height, size.height),
                        )
                    },
            ) {
                Row(Modifier.fillMaxSize()) {
                    repeat(count) { i ->
                        val sel = i == selected
                        val color = if (sel) c.accent else c.text2
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .semantics(mergeDescendants = true) {
                                    role = Role.Tab
                                    this.selected = sel
                                    onClick { currentOnSelect(i); true }
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(tabs[i].icon, null, tint = color, modifier = Modifier.size(23.dp))
                            Text(tabs[i].label, style = Type.caption, color = color, maxLines = 1)
                        }
                    }
                }
            }
            if (lensCapable) {
                CompositionLocalProvider(LocalBackdrop provides barContent, LocalInsideGlass provides false) {
                    GlassSurface(
                        Modifier
                            .offset { IntOffset((pos.value * cell.toPx()).roundToInt(), 0) }
                            .width(cell)
                            .fillMaxHeight()
                            .graphicsLayer {
                                val pr = presence.value
                                alpha = (pr * 4f).coerceIn(0f, 1f)
                                val up = 1f + LENS_RISE * lift.value
                                scaleX = up
                                scaleY = up
                            },
                        shape = Capsule,
                        tint = Color.White.copy(alpha = if (c.dark) 0.04f else 0.10f),
                        material = GlassMaterials.Lens,
                        presence = { presence.value },
                    ) {}
                }
            }
        }
    }
}

/**
 * Search that morphs: a round glass button that expands into a capsule field filling the top bar,
 * absorbing the other bar buttons as it grows. The keyboard is requested only once the field is wide
 * enough to type in, and dismissed before it shrinks back. Rapid taps retarget the same spring, so
 * it reverses smoothly from wherever it is. Back closes it.
 */
@Composable
fun GlassSearchMorph(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = LocalGlassColors.current
    val rm = LocalGlassAccessibility.current.reduceMotion
    val progress = remember { Animatable(if (open) 1f else 0f) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var actionsWidth by remember { mutableIntStateOf(0) }
    val interaction = rememberGlassInteraction(GlassMotion.FLEX_SMALL)

    LaunchedEffect(open) {
        if (open) {
            launch {
                // Focus once the field is wide enough to show the caret (~60% of the morph).
                snapshotFlow { progress.value > 0.6f }.first { it }
                runCatching { focus.requestFocus() }
                keyboard?.show()
            }
            progress.animateTo(1f, GlassMotion.morph(rm))
        } else {
            keyboard?.hide()
            focusManager.clearFocus()
            progress.animateTo(0f, GlassMotion.morph(rm))
        }
    }
    if (open) BackHandler { onOpenChange(false) }

    BoxWithConstraints(modifier.height(48.dp)) {
        val full = constraints.maxWidth
        val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.roundToPx() }
        val closed = with(androidx.compose.ui.platform.LocalDensity.current) { 46.dp.roundToPx() }
        // Bar buttons, drawn first so the growing capsule passes over them.
        Row(
            Modifier
                .align(Alignment.CenterEnd)
                .onSizeChanged { actionsWidth = it.width }
                .graphicsLayer {
                    val p = progress.value
                    alpha = (1f - p * 1.6f).coerceIn(0f, 1f)
                    val s = 1f - 0.35f * p
                    scaleX = s; scaleY = s
                    translationX = -p * 24.dp.toPx()
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
        GlassSurface(
            Modifier
                .layout { m, _ ->
                    val p = progress.value
                    val startX = (full - actionsWidth - (if (actionsWidth > 0) gap else 0) - closed).coerceAtLeast(0)
                    val w = (closed + (full - closed) * p).roundToInt()
                    val x = (startX * (1f - p)).roundToInt()
                    val pl = m.measure(Constraints.fixed(w, closed))
                    layout(full, closed) { pl.place(x, 0) }
                }
                .then(
                    if (!open) Modifier
                        .glassPress(interaction) { onOpenChange(true) }
                        .semantics { contentDescription = placeholder }
                    else Modifier,
                ),
            shape = Capsule,
            interaction = interaction,
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Search, null, tint = if (open) c.text2 else c.text, modifier = Modifier.size(22.dp))
                }
                Box(
                    Modifier
                        .weight(1f)
                        .graphicsLayer { alpha = ((progress.value - 0.35f) / 0.65f).coerceIn(0f, 1f) },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (progress.value > 0.05f || open) {
                        if (query.isEmpty()) Text(placeholder, style = Type.body, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            enabled = open,
                            textStyle = Type.body.copy(color = c.text),
                            cursorBrush = SolidColor(c.accent),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            modifier = Modifier.fillMaxSize().padding(vertical = 13.dp).focusRequester(focus).semantics { contentDescription = placeholder },
                        )
                    }
                }
                if (open || progress.value > 0.05f) {
                    Box(Modifier.graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }) {
                        RowIconButton(Icons.Filled.Close, androidx.compose.ui.res.stringResource(android.R.string.cancel), { onOpenChange(false) }, tint = c.text2)
                    }
                    Spacer(Modifier.width(3.dp))
                }
            }
        }
    }
}

/**
 * Press feedback for CONTENT (cards, tiles, rows): not glass (Apple keeps glass for the navigation
 * layer), but it still answers the finger: a soft light blooms where it is touched and the element
 * gives way a little, springing back on release.
 */
fun Modifier.pressScale(interaction: GlassInteraction, depth: Float = 0.035f): Modifier = graphicsLayer {
    val s = 1f - depth * interaction.energy.value
    scaleX = s; scaleY = s
}

/** Light that blooms where content is touched; place it after the content's clip + background. */
fun Modifier.pressGlow(interaction: GlassInteraction, highlight: Color): Modifier = drawBehind {
    val e = interaction.energy.value
    val t = interaction.touch
    if (e > 0.01f && t.x >= 0f) {
        val r = maxOf(size.width, size.height) * 0.9f
        drawCircle(
            androidx.compose.ui.graphics.Brush.radialGradient(listOf(highlight.copy(alpha = highlight.alpha * e), Color.Transparent), t, r),
            r,
            t,
        )
    }
}
