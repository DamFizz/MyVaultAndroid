package com.damfizz.myvault.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ motion

/** Liquid press: gives way under the finger, then springs back with a small overshoot. */
@Composable
fun Modifier.liquidPress(source: MutableInteractionSource, pressedScale: Float = 0.95f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = if (pressed) 0.9f else 0.42f, stiffness = if (pressed) 900f else 420f),
        label = "press",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

// ------------------------------------------------------------------ text

@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) = Text(text, style = Type.largeTitle, color = LocalGlassColors.current.text, modifier = modifier)

@Composable
fun Body(text: String, modifier: Modifier = Modifier) = Text(text, style = Type.callout, color = LocalGlassColors.current.text2, modifier = modifier)

@Composable
fun Footnote(text: String, modifier: Modifier = Modifier, color: Color = LocalGlassColors.current.text3) =
    Text(text, style = Type.footnote, color = color, modifier = modifier)

@Composable
fun ErrorText(text: String?) {
    if (text != null) Text(text, style = Type.footnote, color = LocalGlassColors.current.danger, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
}

@Composable
fun SectionHeader(text: String) =
    Text(text.uppercase(), style = Type.caption, color = LocalGlassColors.current.text3, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp))

@Composable
fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = LocalGlassColors.current.accent) {
    val source = remember { MutableInteractionSource() }
    Text(
        text,
        style = Type.callout.copy(fontWeight = FontWeight.Medium),
        color = color,
        modifier = modifier
            .liquidPress(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

// ------------------------------------------------------------------ buttons

/** Capsule glass button. Prominent = tinted with the accent colour, like iOS's primary glass button. */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = true,
    enabled: Boolean = true,
    destructive: Boolean = false,
    icon: ImageVector? = null,
) {
    val c = LocalGlassColors.current
    val source = remember { MutableInteractionSource() }
    val tint = when {
        prominent && destructive -> c.danger.copy(alpha = 0.82f)
        prominent -> c.accent.copy(alpha = if (c.dark) 0.62f else 0.82f)
        else -> c.glassTint
    }
    val fg = when {
        prominent -> c.onAccent
        destructive -> c.danger
        else -> c.text
    }
    GlassSurface(
        modifier
            .liquidPress(source)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 52.dp),
        shape = Capsule,
        tint = tint,
        elevation = if (prominent) 14.dp else 8.dp,
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = Type.headline, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 46.dp, tint: Color = LocalGlassColors.current.text) {
    val source = remember { MutableInteractionSource() }
    GlassSurface(
        modifier
            .size(size)
            .liquidPress(source, 0.9f)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        shape = Capsule,
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Small icon button used inside content rows (not glass). */
@Composable
fun RowIconButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = LocalGlassColors.current.accent) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(40.dp)
            .liquidPress(source, 0.85f)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

// ------------------------------------------------------------------ content groups (iOS inset-grouped lists)

private val GroupShape = RoundedCornerShape(24.dp)

@Composable
fun InsetGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(GroupShape).background(LocalGlassColors.current.fill), content = content)
}

@Composable
fun GroupDivider(inset: Dp = 16.dp) {
    Box(Modifier.fillMaxWidth().padding(start = inset).height(0.75.dp).background(LocalGlassColors.current.separator))
}

@Composable
fun IconTile(icon: ImageVector, size: Dp = 38.dp, color: Color = LocalGlassColors.current.accent) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    titleColor: Color = LocalGlassColors.current.text,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalGlassColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .background(if (pressed) c.pill else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .defaultMinSize(minHeight = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, style = Type.footnote, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

// ------------------------------------------------------------------ inputs

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    mono: Boolean = false,
    leading: ImageVector? = null,
    keyboard: KeyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onDone: (() -> Unit)? = null,
) {
    val c = LocalGlassColors.current
    var shown by rememberSaveable { mutableStateOf(false) }
    val style = Type.body.copy(color = c.text, fontFamily = if (mono || (secret && shown)) FontFamily.Monospace else FontFamily.Default)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(c.accent),
        visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onDone?.invoke() }),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.field).padding(start = 14.dp, end = 6.dp).defaultMinSize(minHeight = 52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    Icon(leading, null, tint = c.text3, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                    if (value.isEmpty()) Text(placeholder, style = Type.body, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                if (secret) {
                    RowIconButton(if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, placeholder, { shown = !shown }, tint = c.text2)
                } else {
                    Spacer(Modifier.width(8.dp))
                }
            }
        },
    )
}

/** iOS-style switch: the thumb slides with a spring and stretches slightly while moving. */
@Composable
fun GlassToggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalGlassColors.current
    val p by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 520f), label = "toggle")
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(width = 54.dp, height = 32.dp)
            .clip(Capsule)
            .drawBehind {
                drawRect(c.pill)
                drawRect(c.accent, alpha = p.coerceIn(0f, 1f))
                val r = size.height / 2f - 2.5.dp.toPx()
                val x = size.height / 2f + (size.width - size.height) * p
                val stretch = (1f - kotlin.math.abs(p - 0.5f) * 2f).coerceIn(0f, 1f) * 4.dp.toPx()
                drawRoundRect(
                    Color.White,
                    topLeft = Offset(x - r - stretch, size.height / 2f - r),
                    size = Size((r + stretch) * 2f, r * 2f),
                    cornerRadius = CornerRadius(r, r),
                )
            }
            .clickable(interactionSource = source, indication = null, role = Role.Switch) { onChange(!checked) },
    )
}

@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    ListRow(title, subtitle = subtitle, onClick = { onChange(!checked) }) { GlassToggle(checked, onChange) }
}

// ------------------------------------------------------------------ flowing selection (tab bar, segmented control)

/**
 * Equal-width items with a selection pill that flows between them: the leading edge moves first
 * and the trailing edge catches up, so the pill stretches like a drop of liquid, then settles.
 */
@Composable
fun FlowingSelector(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    pillColor: Color = LocalGlassColors.current.pill,
    item: @Composable (index: Int, selected: Boolean) -> Unit,
) {
    val left = remember { Animatable(selected.toFloat()) }
    val right = remember { Animatable(selected + 1f) }
    LaunchedEffect(selected) {
        val forward = selected >= left.targetValue
        val fast = spring<Float>(dampingRatio = 0.72f, stiffness = 520f)
        val slow = spring<Float>(dampingRatio = 0.62f, stiffness = 190f)
        launch { left.animateTo(selected.toFloat(), if (forward) slow else fast) }
        launch { right.animateTo(selected + 1f, if (forward) fast else slow) }
    }
    BoxWithConstraints(modifier) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                val w = size.width / count
                val l = left.value * w
                val r = right.value * w
                val inset = 4.dp.toPx()
                drawRoundRect(
                    pillColor,
                    topLeft = Offset(l + inset, inset),
                    size = Size((r - l - inset * 2).coerceAtLeast(0f), size.height - inset * 2),
                    cornerRadius = CornerRadius(size.height, size.height),
                )
            },
        )
        Row(Modifier.fillMaxSize()) {
            repeat(count) { i ->
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .liquidPress(source, 0.9f)
                        .clickable(interactionSource = source, indication = null, role = Role.Tab) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) { item(i, i == selected) }
            }
        }
    }
}

class TabItem(val icon: ImageVector, val label: String)

/** Floating glass tab bar; the content scrolls underneath and is blurred and refracted by it. */
@Composable
fun GlassTabBar(tabs: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalGlassColors.current
    GlassSurface(modifier.height(66.dp), shape = Capsule, blur = 24.dp, refraction = 16.dp, elevation = 18.dp) {
        FlowingSelector(tabs.size, selected, onSelect, Modifier.fillMaxSize()) { i, sel ->
            val color = if (sel) c.accent else c.text2
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(tabs[i].icon, null, tint = color, modifier = Modifier.size(23.dp))
                Text(tabs[i].label, style = Type.caption, color = color, maxLines = 1)
            }
        }
    }
}

/** Horizontal filter chip: selected chips are filled with the accent colour. */
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalGlassColors.current
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .liquidPress(source, 0.92f)
            .clip(Capsule)
            .background(if (selected) c.accent else c.fill)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
    ) {
        Text(label, style = Type.footnote.copy(fontWeight = FontWeight.SemiBold), color = if (selected) c.onAccent else c.text)
    }
}
