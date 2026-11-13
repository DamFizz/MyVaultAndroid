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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Liquid Glass engine, v2.
 *
 * Built on Apple's description of the material (WWDC25 "Meet Liquid Glass"): glass is defined by
 * LENSING (it bends and concentrates light) rather than by blur; it materialises by modulating that
 * lensing instead of fading; it "illuminates from within" starting under the fingertip; it has a
 * gel-like flex while touched; bigger glass reads as thicker. Implementation notes, informed by the
 * open-source Kyant0/backdrop library (Apache-2.0) as used in BitChord: refraction uses a circular
 * edge profile (1 - sqrt(1 - x^2)) along the rounded-rect SDF gradient, rim light is the SDF normal
 * dotted with the light direction, and the AGSL program is compiled ONCE per surface and only its
 * uniforms change per frame.
 *
 * A [Backdrop] is a live recording of what is behind the glass (redrawn every frame from the real
 * content, never kept as a stale image), so nothing from a previous screen can linger after the
 * vault locks.
 *
 *   API 33+   blur → AGSL lens (refraction, edge dispersion, vibrancy, tint, rim light, touch glow)
 *   API 31-32 blur + saturation, tint, rim and touch glow drawn by Compose
 *   API 26-30 frosted tinted panel with the same rim and touch glow (no live blur on these versions)
 */
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

/** What a [GlassSurface] placed here should see behind itself; null = no live backdrop. */
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * True inside a glass surface. Glass is not stacked on glass (Apple: "avoid glass on glass"): a
 * glass control placed on a sheet or bar draws as a vibrant fill of that glass instead.
 */
val LocalInsideGlass = staticCompositionLocalOf { false }

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

// ------------------------------------------------------------------ materials

/**
 * Optical recipe of a glass surface. Apple: Regular adapts and stays legible over anything; Clear is
 * more transparent and needs dimming; bigger/thicker glass has deeper shadows and stronger lensing.
 */
@Immutable
data class GlassMaterial(
    val blur: Dp,
    /** width of the curved edge band that bends light */
    val bevel: Dp,
    /** how far light is displaced at the very edge */
    val refraction: Dp,
    /** 0..1: adds a dome-like radial component (thicker look) */
    val depth: Float,
    /** 0..1: subtle colour fringe in the edge band only */
    val dispersion: Float,
    val saturation: Float,
    val rim: Float,
    /** strength of the light that blooms under the finger */
    val glow: Float,
    val elevation: Dp,
)

object GlassMaterials {
    /** Bars, buttons: the versatile default. */
    val Regular = GlassMaterial(blur = 12.dp, bevel = 16.dp, refraction = 14.dp, depth = 0.12f, dispersion = 0.5f, saturation = 1.55f, rim = 0.8f, glow = 0.42f, elevation = 12.dp)

    /** Over photos/media: more transparent, shallow blur, refraction concentrated at the edge. */
    val Clear = GlassMaterial(blur = 3.dp, bevel = 14.dp, refraction = 18.dp, depth = 0.05f, dispersion = 0.7f, saturation = 1.25f, rim = 0.9f, glow = 0.45f, elevation = 8.dp)

    /** Sheets, alerts, menus: thicker material, deeper shadow, softer scattering. */
    val Thick = GlassMaterial(blur = 26.dp, bevel = 26.dp, refraction = 20.dp, depth = 0.22f, dispersion = 0.35f, saturation = 1.45f, rim = 0.65f, glow = 0.25f, elevation = 26.dp)

    /** A lens lifted off a bar (tab selection): almost no blur, strong magnifying refraction. */
    val Lens = GlassMaterial(blur = 0.dp, bevel = 18.dp, refraction = 16.dp, depth = 0.35f, dispersion = 0.8f, saturation = 1.3f, rim = 1.0f, glow = 0.3f, elevation = 2.dp)
}

// ------------------------------------------------------------------ interaction (touch light, gel flex, finger pull)

/**
 * Live touch state shared between a control's pointer handling and its [GlassSurface].
 * Everything is read in the draw/layer phase, so touching never recomposes.
 */
@Stable
class GlassInteraction internal constructor(private val scope: CoroutineScope, private val access: () -> GlassAccessibility, private val flexTo: Float) {
    /** 0 at rest, 1 while pressed: drives the inner light, extra lensing and flex. */
    val energy = Animatable(0f)
    val flex = Animatable(1f)
    /** Finger pull in px (the control leans toward the finger while held). */
    val pull = Animatable(Offset.Zero, Offset.VectorConverter)
    /** Touch position inside the surface, px; Unspecified when not touched. */
    var touch by mutableStateOf(Offset.Unspecified)
        internal set
    internal var size = Size.Zero

    internal fun press(at: Offset) {
        val a = access()
        touch = at
        scope.launch { energy.animateTo(1f, GlassMotion.energize(a.reduceMotion)) }
        if (!a.reduceMotion) scope.launch { flex.animateTo(flexTo, GlassMotion.flexIn(a.reduceMotion)) }
    }

    internal fun move(at: Offset) {
        touch = at
        if (access().reduceMotion || size == Size.Zero) return
        val c = Offset(size.width / 2f, size.height / 2f)
        val d = at - c
        // rubber-band: proportional near the control, saturating further away
        val limit = minOf(size.width, size.height) * 0.22f
        fun band(v: Float) = limit * (v / (abs(v) + limit * 2.5f)) * 2.5f * GlassMotion.PULL / 0.07f
        scope.launch { pull.snapTo(Offset(band(d.x), band(d.y))) }
    }

    internal fun release(velocity: Offset = Offset.Zero) {
        val a = access()
        scope.launch { energy.animateTo(0f, GlassMotion.relax(a.reduceMotion)) }
        scope.launch { flex.animateTo(1f, GlassMotion.flexOut(a.reduceMotion)) }
        scope.launch { pull.animateTo(Offset.Zero, GlassMotion.offsetSpring(a.reduceMotion), velocity * 0.15f) }
    }
}

@Composable
fun rememberGlassInteraction(flex: Float = GlassMotion.FLEX_MEDIUM): GlassInteraction {
    val scope = rememberCoroutineScope()
    val access = LocalGlassAccessibility.current
    val latest = remember { mutableStateOf(access) }
    latest.value = access
    return remember(flex) { GlassInteraction(scope, { latest.value }, flex) }
}

/**
 * Press handling for glass controls: the response starts on finger-down (no tap delay), follows the
 * finger, and a tap fires on release inside the control. Accessible: role + click actions.
 */
fun Modifier.glassPress(
    interaction: GlassInteraction,
    enabled: Boolean = true,
    role: Role = Role.Button,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = this
    .semantics {
        this.role = role
        if (!enabled) disabled()
        if (enabled) {
            onClick { onClick(); true }
            if (onLongClick != null) onLongClick { onLongClick(); true }
        }
    }
    .pointerInput(enabled, onClick, onLongClick) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            interaction.size = Size(size.width.toFloat(), size.height.toFloat())
            interaction.press(down.position)
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var inside = true
            var longFired = false
            val longAt = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
            while (true) {
                // Final pass: if a scrolling parent took the gesture, this is a scroll, not a press.
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) {
                    interaction.release()
                    break
                }
                tracker.addPosition(change.uptimeMillis, change.position)
                if (change.positionChange() != Offset.Zero) interaction.move(change.position)
                val slop = viewConfiguration.touchSlop * 3
                inside = change.position.x in -slop..(size.width + slop) && change.position.y in -slop..(size.height + slop)
                if (onLongClick != null && !longFired && inside && change.uptimeMillis >= longAt) {
                    longFired = true
                    onLongClick()
                }
                if (!change.pressed) {
                    val v = tracker.calculateVelocity()
                    interaction.release(Offset(v.x, v.y))
                    if (inside && !longFired) onClick()
                    break
                }
            }
            if (interaction.energy.targetValue != 0f) interaction.release()
        }
    }

// ------------------------------------------------------------------ the lens program

private const val LENS = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float bevel;
uniform float refraction;
uniform float depth;
uniform float dispersion;
uniform float2 light;
uniform float sat;
uniform float4 tint;
uniform float rim;
uniform float rimWidth;
uniform float2 touch;
uniform float energy;
uniform float glow;
uniform float presence;

float sdRoundedRect(float2 p, float2 h, float r) {
    float2 q = abs(p) - (h - float2(r, r));
    return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
}

float2 gradRoundedRect(float2 p, float2 h, float r) {
    float2 q = abs(p) - (h - float2(r, r));
    if (q.x >= 0.0 || q.y >= 0.0) {
        float2 m = max(q, float2(0.0, 0.0));
        float l = length(m);
        return l > 0.0001 ? sign(p) * (m / l) : float2(0.0, 0.0);
    }
    float gx = step(q.y, q.x);
    return sign(p) * float2(gx, 1.0 - gx);
}

half4 main(float2 xy) {
    float2 h = size * 0.5;
    float2 p = xy - h;
    float sd = sdRoundedRect(p, h, radius);
    float alpha = clamp(0.5 - sd, 0.0, 1.0);
    if (alpha <= 0.0) { return half4(0.0); }
    float inside = max(-sd, 0.0);

    // Normal of the curved edge (softened corners), plus a dome term for thicker glass.
    float gr = min(radius * 1.5, min(h.x, h.y));
    float2 n = gradRoundedRect(p, h, gr);
    float pl = length(p);
    float2 radial = pl > 0.0001 ? p / pl : float2(0.0, 0.0);
    float2 nd = n + depth * radial;
    float ndl = length(nd);
    nd = ndl > 0.0001 ? nd / ndl : float2(0.0, 0.0);

    // Circular edge profile: flat in the middle, bending most at the rim (a convex bevel).
    float band = bevel * (1.0 + 0.25 * energy);
    float x = clamp(1.0 - inside / max(band, 1.0), 0.0, 1.0);
    float bend = (1.0 - sqrt(max(1.0 - x * x, 0.0))) * refraction * presence * (1.0 + 0.35 * energy);
    float2 s = xy + nd * bend;

    // Premultiplied colour + coverage of what is behind (a lens over a bar's icons sees mostly
    // transparent pixels; it must stay see-through there, not turn black).
    half3 col;
    half a;
    if (dispersion > 0.0 && bend > 0.05) {
        float k = dispersion * 0.22 * x;
        half4 r = content.eval(s + nd * bend * k);
        half4 g = content.eval(s);
        half4 b = content.eval(s - nd * bend * k);
        col = half3(r.r, g.g, b.b);
        a = g.a;
    } else {
        half4 g = content.eval(s);
        col = g.rgb;
        a = g.a;
    }

    // Vibrancy and tint (tint already chosen for light/dark by the theme).
    half luma = dot(col, half3(0.2126, 0.7152, 0.0722));
    col = mix(half3(luma), col, half(sat));
    col = mix(col, half3(tint.rgb), half(tint.a));
    a = mix(a, 1.0, half(tint.a));

    // Light on the geometry: a thin rim facing the light, a fainter one on the far side,
    // and a broad sheen across the curved band.
    float facing = dot(n, light);
    float e = clamp(1.0 - inside / max(rimWidth, 0.5), 0.0, 1.0);
    float key = pow(max(facing, 0.0), 1.4);
    float fill = pow(max(-facing, 0.0), 2.0) * 0.35;
    float rimLight = e * (key + fill) * rim;
    float sheen = x * x * max(facing, 0.0) * 0.18 * rim;

    // Illumination from within, starting under the fingertip and spreading.
    float inner = 0.0;
    if (touch.x >= 0.0 && energy > 0.001) {
        float2 d = xy - touch;
        float sigma = max(max(size.x, size.y) * 0.32, 24.0);
        float gauss = exp(-dot(d, d) / (2.0 * sigma * sigma));
        inner = energy * glow * (0.5 * gauss + 0.1);
        rimLight *= 1.0 + energy * 1.3 * gauss;
    }

    half lightAdd = half((rimLight + sheen) * presence + inner);
    col = col + half3(lightAdd);
    a = clamp(a + lightAdd, 0.0, 1.0);
    return half4(clamp(col, half3(0.0), half3(a)) * half(alpha), a * half(alpha));
}
"""

/** One compiled lens per surface; uniforms are updated per frame. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class LensProgram {
    val shader = RuntimeShader(LENS)
    private var lastBlur = -1f
    private var blurEffect: android.graphics.RenderEffect? = null

    fun effect(blurPx: Float): RenderEffect {
        val lens = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
        val chained = if (blurPx > 0.5f) {
            if (blurPx != lastBlur || blurEffect == null) {
                blurEffect = android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                lastBlur = blurPx
            }
            android.graphics.RenderEffect.createChainEffect(lens, blurEffect!!)
        } else lens
        return chained.asComposeRenderEffect()
    }
}

/**
 * Compiled lens programs are recycled: a surface that leaves the screen (a button that disappears
 * when switching tabs) hands its program back, and the next surface reuses it instead of compiling
 * the AGSL again. Main thread only (composition).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object LensPool {
    private val free = ArrayDeque<LensProgram>()
    fun acquire(): LensProgram = free.removeLastOrNull() ?: LensProgram()
    fun release(p: LensProgram) { if (free.size < 12) free.addLast(p) }
}

@RequiresApi(Build.VERSION_CODES.S)
private object Effects31 {
    fun blurVibrant(px: Float, saturation: Float): RenderEffect {
        val m = ColorMatrix().apply { setSaturation(saturation) }
        val blur = android.graphics.RenderEffect.createBlurEffect(px.coerceAtLeast(0.1f), px.coerceAtLeast(0.1f), Shader.TileMode.CLAMP)
        return android.graphics.RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(m), blur).asComposeRenderEffect()
    }
}

/**
 * A piece of Liquid Glass. Put it OUTSIDE the content its [LocalBackdrop] records (bars, sheets),
 * or inside content whose backdrop is only the wallpaper.
 *
 * @param interaction live touch state (touch light, gel flex, finger pull); null = not interactive
 * @param presence 0..1: materialise by modulating lensing and rim instead of fading
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = Capsule,
    tint: Color = LocalGlassColors.current.glassTint,
    material: GlassMaterial = GlassMaterials.Regular,
    blur: Dp? = null,
    refraction: Dp? = null,
    elevation: Dp? = null,
    interaction: GlassInteraction? = null,
    presence: () -> Float = { 1f },
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalGlassColors.current
    val access = LocalGlassAccessibility.current
    val nested = LocalInsideGlass.current
    val backdrop = if (nested) null else LocalBackdrop.current
    val light = LocalGlassLight.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    val live = backdrop != null && Build.VERSION.SDK_INT >= 31
    val lens = live && Build.VERSION.SDK_INT >= 33
    val program = remember(lens) { if (lens && Build.VERSION.SDK_INT >= 33) LensPool.acquire() else null }
    DisposableEffect(program) { onDispose { if (program != null && Build.VERSION.SDK_INT >= 33) LensPool.release(program) } }
    val frosty = access.reduceTransparency
    val blurDp = (blur ?: material.blur) * (if (frosty) 2.2f else 1f)
    val refractionDp = if (frosty) 0.dp else (refraction ?: material.refraction)
    val effTint = when {
        frosty -> colors.glassFallback.copy(alpha = 0.88f)
        access.highContrast -> tint.copy(alpha = (tint.alpha + 0.25f).coerceAtMost(0.92f))
        else -> tint
    }
    val shadowElevation = if (nested) 0.dp else (elevation ?: material.elevation)

    Box(
        modifier
            .graphicsLayer {
                // Gel flex and finger pull (layer-only; never recomposes).
                if (interaction != null) {
                    val f = interaction.flex.value
                    val pl = interaction.pull.value
                    val sx = 1f + abs(pl.x) / size.width.coerceAtLeast(1f) * 0.6f
                    val sy = 1f + abs(pl.y) / size.height.coerceAtLeast(1f) * 0.6f
                    scaleX = f * sx / sqrt(sy)
                    scaleY = f * sy / sqrt(sx)
                    translationX = pl.x
                    translationY = pl.y
                }
                transformOrigin = TransformOrigin.Center
            }
            .shadow(shadowElevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.16f), spotColor = Color.Black.copy(alpha = 0.28f))
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
                        val blurPx = blurDp.toPx()
                        renderEffect = if (program != null && Build.VERSION.SDK_INT >= 33) {
                            val s = program.shader
                            val minSide = minOf(size.width, size.height)
                            val r = shape.topStart.toPx(size, this).coerceAtMost(minSide / 2f)
                            val t = interaction?.touch ?: Offset.Unspecified
                            s.setFloatUniform("size", size.width, size.height)
                            s.setFloatUniform("radius", r)
                            s.setFloatUniform("bevel", minOf(material.bevel.toPx(), minSide / 2f))
                            s.setFloatUniform("refraction", refractionDp.toPx())
                            s.setFloatUniform("depth", material.depth)
                            s.setFloatUniform("dispersion", if (frosty) 0f else material.dispersion)
                            s.setFloatUniform("light", light.value.x, light.value.y)
                            s.setFloatUniform("sat", material.saturation)
                            s.setFloatUniform("tint", effTint.red, effTint.green, effTint.blue, effTint.alpha)
                            s.setFloatUniform("rim", material.rim * (if (colors.dark) 0.75f else 0.55f))
                            s.setFloatUniform("rimWidth", 1.4.dp.toPx())
                            s.setFloatUniform("touch", if (t.isSpecified) t.x else -1f, if (t.isSpecified) t.y else -1f)
                            s.setFloatUniform("energy", interaction?.energy?.value ?: 0f)
                            s.setFloatUniform("glow", material.glow * (if (colors.dark) 1f else 0.8f))
                            s.setFloatUniform("presence", presence().coerceIn(0f, 1f))
                            program.effect(blurPx)
                        } else {
                            Effects31.blurVibrant(blurPx.coerceAtLeast(1f), material.saturation)
                        }
                    }
                    .drawBehind {
                        val o = backdrop!!.origin - pos
                        translate(o.x, o.y) { drawLayer(backdrop.layer) }
                    },
            )
        }
        // Tint where the lens program does not apply it, plus the rim and touch light for fallbacks.
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(
                    when {
                        lens -> Color.Transparent
                        live -> effTint
                        nested -> if (effTint.alpha > 0.5f) effTint else colors.fill
                        else -> colors.glassFallback.compositeOverTint(effTint)
                    },
                )
                .then(if (!lens && interaction != null) Modifier.touchGlow(interaction, if (nested) material.glow * 0.7f else material.glow) else Modifier)
                .border(
                    if (access.highContrast) 1.5.dp else 1.dp,
                    if (access.highContrast) Brush.linearGradient(listOf(colors.text.copy(alpha = 0.5f), colors.text.copy(alpha = 0.5f)))
                    else Brush.linearGradient(
                        listOf(colors.rimHigh, colors.rimLow, colors.rimLow, colors.rimHigh.copy(alpha = colors.rimHigh.alpha * 0.55f)),
                        start = Offset.Zero,
                        end = Offset.Infinite,
                    ),
                    shape,
                ),
        )
        CompositionLocalProvider(LocalInsideGlass provides true) { content() }
    }
}

/** Fallback touch light for devices without the lens program. */
private fun Modifier.touchGlow(interaction: GlassInteraction, strength: Float): Modifier = drawBehind {
    val e = interaction.energy.value
    val t = interaction.touch
    if (e > 0.01f && t.isSpecified) {
        val r = maxOf(size.width, size.height) * 0.7f
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f * e * strength), Color.Transparent), t, r), r, t)
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
