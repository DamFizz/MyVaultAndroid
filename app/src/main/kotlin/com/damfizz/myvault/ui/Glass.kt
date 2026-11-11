package com.damfizz.myvault.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Early "liquid glass" look: translucent layered panels with a specular edge over a slowly drifting
 * colour field, and a spring press swell. Real backdrop blur + AGSL refraction come in stage 4.
 * All animation runs in the draw/graphics-layer phase (no recomposition per frame).
 */
val Accent = Color(0xFF7C9CFF)
private val Bg = Color(0xFF0B1020)

@Composable
fun MyVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF0B1020),
            background = Bg,
            surface = Color(0xFF151B2E),
            onSurface = Color(0xFFE8ECF8),
            onBackground = Color(0xFFE8ECF8),
            error = Color(0xFFFF8A80),
        ),
        content = content,
    )
}

@Composable
fun LiquidBackground(content: @Composable BoxScope.() -> Unit) {
    val t = rememberInfiniteTransition(label = "bg")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(18_000), RepeatMode.Reverse), label = "phase")
    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .drawBehind {
                fun blob(cx: Float, cy: Float, r: Float, c: Color) =
                    drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
                val w = size.width
                val h = size.height
                blob(w * (0.15f + 0.2f * phase), h * 0.18f, w * 0.75f, Color(0x557C5CFF))
                blob(w * (0.95f - 0.25f * phase), h * (0.45f + 0.1f * phase), w * 0.7f, Color(0x4422D3EE))
                blob(w * (0.3f + 0.3f * phase), h * 0.95f, w * 0.8f, Color(0x40EC4899))
            },
        content = content,
    )
}

private val GlassShape = RoundedCornerShape(24.dp)

/** Translucent panel with a light top edge, like a sheet of glass catching light. */
fun Modifier.glass(shape: Shape = GlassShape): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.05f))))
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.06f))), shape)

/** Spring "press swell": shrinks slightly while pressed and settles back with a small overshoot. */
@Composable
fun Modifier.liquidPress(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    var m = modifier.liquidPress(source).glass()
    if (onClick != null) m = m.clickable(interactionSource = source, indication = null, onClick = onClick)
    Column(m.padding(18.dp), content = content)
}

@Composable
fun GlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    val bg = if (primary) {
        Brush.verticalGradient(listOf(Accent.copy(alpha = if (enabled) 0.95f else 0.35f), Color(0xFF5B7BF0).copy(alpha = if (enabled) 0.95f else 0.35f)))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.06f)))
    }
    Box(
        modifier
            .liquidPress(source)
            .clip(shape)
            .background(bg)
            .border(1.dp, Color.White.copy(alpha = if (primary) 0.35f else 0.18f), shape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (primary) Color(0xFF0B1020) else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    keyboard: KeyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
    placeholder: String? = null,
) {
    var shown by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        trailingIcon = if (secret) {
            { IconButton(onClick = { shown = !shown }) { Icon(if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) } }
        } else null,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            unfocusedBorderColor = Color.White.copy(alpha = 0.22f),
            focusedContainerColor = Color.White.copy(alpha = 0.06f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun Title(text: String) = Text(text, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)

@Composable
fun Body(text: String, modifier: Modifier = Modifier) = Text(text, color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp, lineHeight = 20.sp, modifier = modifier)

@Composable
fun ErrorText(text: String?) {
    if (text != null) Text(text, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
}
