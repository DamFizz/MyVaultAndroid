package com.damfizz.myvault.ui.glass

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.math.sqrt

/**
 * Liquid Glass engine.
 *
 * A [Backdrop] is a recorded picture of "what is behind the glass" (wallpaper, scrolling content).
 * A [GlassSurface] redraws that picture inside its own shape through a GPU effect chain:
 *
 *   API 33+   blur → AGSL lens: edge refraction, chromatic fringe, vibrancy, tint, specular rim
 *   API 31-32 blur → saturation, tint and rim drawn by Compose
 *   API 26-30 no blur available: a more opaque tinted panel with the same rim
 *
 * Everything runs in the draw / render phase; nothing here recomposes per frame.
 */
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

/** What a [GlassSurface] placed here should see behind itself; null = no live backdrop. */
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Direction the light comes from (unit vector, y down); follows device tilt. */
val LocalGlassLight = staticCompositionLocalOf<State<Offset>> { mutableStateOf(Offset(-0.6f, -0.8f)) }

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/** Record everything this node draws into [backdrop] (and still draw it normally). */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

val Capsule = RoundedCornerShape(percent = 50)

private const val LENS = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float band;
uniform float strength;
uniform float2 light;
uniform float sat;
uniform float4 tint;
uniform float spec;

float sdf(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r, r);
    return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 xy) {
    float2 c = size * 0.5;
    float2 p = xy - c;
    float d = sdf(p, c, radius);
    float2 n = float2(
        sdf(p + float2(1.0, 0.0), c, radius) - sdf(p - float2(1.0, 0.0), c, radius),
        sdf(p + float2(0.0, 1.0), c, radius) - sdf(p - float2(0.0, 1.0), c, radius));
    float nl = length(n);
    if (nl > 0.0001) { n = n / nl; } else { n = float2(0.0, 0.0); }

    // 0 in the flat middle, 1 at the rim: the glass bends light only near its edge.
    float t = clamp(1.0 + d / band, 0.0, 1.0);
    float bend = t * t;
    float2 off = -n * bend * strength;
    float ca = 0.2 * bend;

    half4 base = content.eval(xy + off);
    half3 col = half3(content.eval(xy + off * (1.0 + ca)).r, base.g, content.eval(xy + off * (1.0 - ca)).b);

    half luma = dot(col, half3(0.2126, 0.7152, 0.0722));
    col = mix(half3(luma, luma, luma), col, half(sat));
    col = mix(col, half3(tint.rgb), half(tint.a));

    float rim = pow(t, 5.0);
    float facing = dot(n, light);
    float hl = rim * (0.6 * max(facing, 0.0) + 0.28 * max(-facing, 0.0) + 0.06) * spec;
    col = col + half3(hl, hl, hl);

    float a = clamp(0.5 - d, 0.0, 1.0);
    return half4(clamp(col, half3(0.0, 0.0, 0.0), half3(1.0, 1.0, 1.0)) * half(a), half(a));
}
"""

@RequiresApi(Build.VERSION_CODES.S)
private object Effects31 {
    fun blur(px: Float): android.graphics.RenderEffect =
        android.graphics.RenderEffect.createBlurEffect(px.coerceAtLeast(0.1f), px.coerceAtLeast(0.1f), Shader.TileMode.CLAMP)

    fun blurVibrant(px: Float, saturation: Float): RenderEffect {
        val m = ColorMatrix().apply { setSaturation(saturation) }
        return android.graphics.RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(m), blur(px)).asComposeRenderEffect()
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object Effects33 {
    fun lens(size: Size, radius: Float, blurPx: Float, band: Float, strength: Float, light: Offset, saturation: Float, tint: Color, spec: Float): RenderEffect {
        val s = RuntimeShader(LENS)
        s.setFloatUniform("size", size.width, size.height)
        s.setFloatUniform("radius", radius)
        s.setFloatUniform("band", band.coerceAtLeast(1f))
        s.setFloatUniform("strength", strength)
        s.setFloatUniform("light", light.x, light.y)
        s.setFloatUniform("sat", saturation)
        s.setFloatUniform("tint", tint.red, tint.green, tint.blue, tint.alpha)
        s.setFloatUniform("spec", spec)
        return android.graphics.RenderEffect
            .createChainEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(s, "content"), Effects31.blur(blurPx))
            .asComposeRenderEffect()
    }
}

/**
 * A piece of Liquid Glass. Put it OUTSIDE the content its [LocalBackdrop] records (bars, sheets),
 * or inside content whose backdrop is only the wallpaper.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = Capsule,
    tint: Color = LocalGlassColors.current.glassTint,
    blur: Dp = 20.dp,
    refraction: Dp = 14.dp,
    elevation: Dp = 12.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalGlassColors.current
    val backdrop = LocalBackdrop.current
    val light = LocalGlassLight.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    val live = backdrop != null && Build.VERSION.SDK_INT >= 31
    val lens = live && Build.VERSION.SDK_INT >= 33

    Box(
        modifier
            .shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.3f))
            .onGloballyPositioned { pos = it.positionInRoot() },
        contentAlignment = contentAlignment,
    ) {
        if (live) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        clip = true
                        this.shape = shape
                        val blurPx = blur.toPx()
                        renderEffect = if (lens) {
                            val r = shape.topStart.toPx(size, this).coerceAtMost(minOf(size.width, size.height) / 2f)
                            Effects33.lens(
                                size = size,
                                radius = r,
                                blurPx = blurPx,
                                band = minOf(refraction.toPx() * 1.6f, minOf(size.width, size.height) / 2f),
                                strength = refraction.toPx(),
                                light = light.value,
                                saturation = 1.45f,
                                tint = tint,
                                spec = if (colors.dark) 0.55f else 0.38f,
                            )
                        } else {
                            Effects31.blurVibrant(blurPx, 1.4f)
                        }
                    }
                    .drawBehind {
                        val o = backdrop!!.origin - pos
                        translate(o.x, o.y) { drawLayer(backdrop.layer) }
                    },
            )
        }
        // Tint (where the shader does not do it) and the bright rim that catches the light.
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(
                    when {
                        lens -> Color.Transparent
                        live -> tint
                        else -> colors.glassFallback.compositeOverTint(tint)
                    },
                )
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(colors.rimHigh, colors.rimLow, colors.rimLow, colors.rimHigh.copy(alpha = colors.rimHigh.alpha * 0.55f)),
                        start = Offset.Zero,
                        end = Offset.Infinite,
                    ),
                    shape,
                ),
        )
        content()
    }
}

private fun Color.compositeOverTint(tint: Color): Color =
    if (tint.alpha > 0.5f) tint else this

/** Light direction that follows how the phone is tilted (stops listening when the app is paused). */
@Composable
fun rememberTiltLight(): State<Offset> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(Offset(-0.6f, -0.8f)) }
    LifecycleResumeEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var fx = 0f
        var fy = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                fx += (e.values[0] / 9.81f - fx) * 0.12f
                fy += (e.values[1] / 9.81f - fy) * 0.12f
                val x = -0.6f - fx * 1.1f
                val y = -1.25f + fy * 0.75f
                val len = sqrt(x * x + y * y).coerceAtLeast(0.001f)
                val v = Offset(x / len, y / len)
                if ((v - state.value).getDistance() > 0.025f) state.value = v
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) sm?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onPauseOrDispose { sm?.unregisterListener(listener) }
    }
    return state
}
