/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * The phone's motion: its Motion, MotionTokens and AppleTokens.Motion, with the same numbers.
 * Springs keep their speed when interrupted, so they move things; tweens fade them.
 */
object ConvxMotion {
    /** SwiftUI's `.smooth`, a half-second spring without bounce: the default for anything that moves. */
    const val Stiffness = 158f

    fun <T> standard(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Stiffness)

    /** A little bounce, for selection, toggles and emphasis. */
    fun <T> emphasis(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = Stiffness)

    /** Cross-fades, where a spring has nothing to overshoot. */
    fun <T> fade(): FiniteAnimationSpec<T> = tween(durationMillis = 200)

    /** A selection moving between two places: a tab's puck, a segmented control's thumb. */
    fun <T> select(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.76f, stiffness = 750f)

    /** Something arriving in place: a sheet, a panel, a chip row. */
    fun <T> appear(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 450f)

    /** The same thing leaving. */
    fun <T> appearExit(): FiniteAnimationSpec<T> = tween(400, easing = PushEasing)

    /** A menu's glass growing out of its button: quick, settling with a little give. */
    fun <T> menuOpen(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.72f, stiffness = 420f)

    /** The menu pulled back into its button: quicker, no give. */
    fun <T> menuClose(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f)

    /** A heart filling in on a like: a quick pop with a lively settle. */
    fun <T> likePop(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.38f, stiffness = 640f)

    /** A button taking something in, such as the queue button when a song lands in it. */
    fun <T> bump(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.42f, stiffness = 760f)

    /** A highlight moving between rows, with a little give at the end. */
    fun <T> glide(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.74f, stiffness = 520f)

    /** A page's covers rising into place one after another. */
    fun <T> cascade(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 450f)

    /** A dialog springing up from a little smaller. */
    fun <T> dialog(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.74f, stiffness = 430f)

    /** A notice rising out from behind the bottom bar. */
    fun <T> notice(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.66f, stiffness = 480f)

    /** Leaving quickly: material's accelerate curve, for anything that goes away. */
    val ExitEasing: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /** An icon changing in place, play to pause: quick, with a pop from 30% size. */
    const val SwapInitialScale = 0.3f

    fun <T> swapEnter(): FiniteAnimationSpec<T> = tween(200, easing = FastOutSlowInEasing)

    fun <T> swapExit(): FiniteAnimationSpec<T> = tween(160, easing = FastOutSlowInEasing)

    /** UIKit's navigation curve: it leaves rest gently and settles, where Material's throws the page. */
    val PushEasing: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /** A page pushed over another, and how long a page takes to fade in and out. */
    const val PushMillis = 260
    const val PageEnterMillis = 300
    const val PageExitMillis = 220

    fun <T> push(): FiniteAnimationSpec<T> = tween(PushMillis, easing = PushEasing)

    /** An album or playlist sliding in over the page it was opened from. */
    fun collectionPush(): FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = IntOffset(1, 1))

    /** The player opening over the app and closing back into its bar. */
    fun <T> playerSheet(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 360f)

    /** Artwork growing or shrinking: fast out of rest, a long soft landing. */
    fun <T> artwork(): FiniteAnimationSpec<T> = tween(350, easing = ArtworkEasing)

    private const val ArtworkEndpoint = 9f
    private val ArtworkNormalization = 1f / (1f - (1f + ArtworkEndpoint) * kotlin.math.exp(-ArtworkEndpoint))
    private val ArtworkEasing = Easing { fraction ->
        when {
            !fraction.isFinite() || fraction >= 1f -> 1f
            fraction <= 0f -> 0f
            else -> {
                val position = ArtworkEndpoint * fraction
                (1f - (1f + position) * kotlin.math.exp(-position)) * ArtworkNormalization
            }
        }
    }
}
