package com.damfizz.myvault.ui.glass

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset

/**
 * One motion system for the whole app. Springs everywhere, so any animation can be interrupted and
 * redirected from its current position AND velocity (Animatable keeps velocity when retargeted).
 *
 * Tuning follows Apple's Liquid Glass guidance (WWDC25 "Meet Liquid Glass"): glass "responds to
 * interaction by instantly flexing and energizing with light" and has "gel-like flexibility";
 * with Reduce Motion the elastic properties are disabled. Rough durations: press ≈ 100 ms,
 * small state ≈ 200 ms, search morph ≈ 300 ms, screen ≈ 320 ms.
 */
@Immutable
data class GlassAccessibility(
    /** No overshoot, no elastic flex/stretch; effects keep working but calmer. */
    val reduceMotion: Boolean = false,
    /** Frostier, more opaque glass with no refraction (Apple: "Reduce Transparency"). */
    val reduceTransparency: Boolean = false,
    /** Stronger outlines and more opaque tint for legibility. */
    val highContrast: Boolean = false,
)

val LocalGlassAccessibility = staticCompositionLocalOf { GlassAccessibility() }

object GlassMotion {
    /** How far an interactive glass control grows while held (gel flex), by size class. */
    const val FLEX_SMALL = 1.10f
    const val FLEX_MEDIUM = 1.045f

    /** Finger pull: how much a held control leans toward the finger (fraction of the offset). */
    const val PULL = 0.07f

    private fun <T> s(damping: Float, stiffness: Float, reduce: Boolean): FiniteAnimationSpec<T> =
        spring(if (reduce) Spring.DampingRatioNoBouncy else damping, stiffness)

    /** Light floods in under the finger: very fast, no overshoot. */
    fun <T> energize(reduce: Boolean): FiniteAnimationSpec<T> = spring(Spring.DampingRatioNoBouncy, 1600f)

    /** Light fades after release, a little slower than it came. */
    fun <T> relax(reduce: Boolean): FiniteAnimationSpec<T> = spring(Spring.DampingRatioNoBouncy, 420f)

    /** Gel flex outwards on press. */
    fun <T> flexIn(reduce: Boolean): FiniteAnimationSpec<T> = s(0.62f, 900f, reduce)

    /** Settle back after release, with a small rebound. */
    fun <T> flexOut(reduce: Boolean): FiniteAnimationSpec<T> = s(0.46f, 420f, reduce)

    /** Indicator / lens travel between tabs or segments. */
    fun <T> travel(reduce: Boolean): FiniteAnimationSpec<T> = s(0.78f, 380f, reduce)

    /** A lens lifting off its track (under-damped, so it overshoots a little and rebounds). */
    fun <T> lift(reduce: Boolean): FiniteAnimationSpec<T> = s(0.52f, 620f, reduce)

    /** Glass presence: materialize/dematerialize by modulating lensing, quickly. */
    fun <T> presence(reduce: Boolean): FiniteAnimationSpec<T> = spring(Spring.DampingRatioNoBouncy, 900f)

    /** Search control expanding into a field and back (~300 ms). */
    fun <T> morph(reduce: Boolean): FiniteAnimationSpec<T> = s(0.86f, 340f, reduce)

    /** Sheets and alerts. */
    fun <T> sheet(reduce: Boolean): FiniteAnimationSpec<T> = s(0.84f, 330f, reduce)

    /** Toggles and small state changes. */
    fun <T> small(reduce: Boolean): FiniteAnimationSpec<T> = s(0.7f, 520f, reduce)

    fun offsetSpring(reduce: Boolean): FiniteAnimationSpec<Offset> = s(0.5f, 420f, reduce)
    fun intOffsetSpring(reduce: Boolean): FiniteAnimationSpec<IntOffset> = s(0.86f, 420f, reduce)
}
