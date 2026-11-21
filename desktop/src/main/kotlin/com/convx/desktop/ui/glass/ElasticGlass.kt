/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/** Whether glass controls move under the pointer: Settings > Liquid Glass > Button motion. */
val LocalElasticGlass = compositionLocalOf { true }

/**
 * The phone's elastic glass (GlassCircleButton's elasticStretchLayerBlock) for a control the
 * pointer can press. It lifts a little as the pointer comes over it, swells and catches the light
 * under a press, leans and stretches after a drag on a tanh leash, and when let go it springs back
 * past where it started and jiggles wide and tall a couple of times before it settles, like a drop
 * of something thicker than water.
 *
 * It costs nothing until the pointer arrives: hover only starts and stops a short spring, the
 * drag is plain state written straight from the pointer (no coroutine for each move), and the
 * shape is read only in the control's own layer, so a frame of motion moves that layer and
 * nothing is composed again; only the light is drawn again, and only while it changes. Off in
 * Settings, a control has none of it: no layer, no pointer handler.
 */
@Stable
class ElasticGlass internal constructor(private val scope: CoroutineScope) {
    // A fiftieth of the swell is a tenth of a pixel: past that a spring stops asking for frames.
    private val press = Animatable(0f, Settled)
    private val hover = Animatable(0f, Settled)

    /** The jiggle after a let-go: above nothing it's wider and shorter, below it narrower and taller. */
    private val jiggle = Animatable(0f, Settled)

    /** How far the pointer has gone since the press, and then back to nothing as it springs home. */
    private var drag by mutableStateOf(Offset.Zero)
    private var home: Job? = null
    private var start = Offset.Zero
    private var held = false

    fun input(hovers: Boolean): Modifier = Modifier.pointerInput(this, hovers) {
        awaitPointerEventScope {
            while (true) {
                // Watched before the control's own click handling, and never consumed, so the click still lands.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                when (event.type) {
                    PointerEventType.Enter -> if (hovers) scope.launch { hover.animateTo(1f, HoverSpring) }
                    PointerEventType.Exit -> {
                        if (hovers) scope.launch { hover.animateTo(0f, HoverSpring) }
                        // A drag that leaves the glass keeps pulling it until the button comes up.
                        if (held && !event.buttons.isPrimaryPressed) letGo()
                    }
                    PointerEventType.Press -> {
                        held = true
                        home?.cancel()
                        start = change.position
                        drag = Offset.Zero
                        scope.launch { press.animateTo(1f, PressSpring) }
                    }
                    PointerEventType.Move -> if (held) drag = change.position - start
                    PointerEventType.Release -> if (held) letGo()
                    else -> Unit
                }
            }
        }
    }

    private fun letGo() {
        held = false
        val from = drag
        // Pulled up or down, it first stretches the way it was pulled; otherwise it first spreads wide.
        val kick = if (abs(from.y) > abs(from.x)) -JiggleKick else JiggleKick
        scope.launch { press.animateTo(0f, ReleaseSpring) }
        scope.launch { jiggle.animateTo(0f, JiggleSpring, initialVelocity = kick) }
        if (from != Offset.Zero) {
            home = scope.launch { animate(Offset.VectorConverter, from, Offset.Zero, animationSpec = DragSpring) { value, _ -> drag = value } }
        }
    }

    /**
     * The light the glass catches under a press, a pale wash over the material and under its glyph.
     * Goes after the material; drawn again only while the press moves.
     */
    val sheen: Modifier = Modifier.drawBehind {
        val light = SheenAlpha * press.value
        if (light > 0.004f) drawRect(Color.White.copy(alpha = light.coerceAtMost(SheenAlpha)))
    }

    /** The layer's part: [magnitude] 2 makes a long bar's swell read at its size; a bar never leans. */
    fun layer(magnitude: Float): GraphicsLayerScope.() -> Unit = {
        val width = size.width
        val height = size.height
        val pressed = press.value
        val hovered = hover.value
        val pull = drag
        val jiggled = jiggle.value
        if (width > 0f && height > 0f && (pressed != 0f || hovered != 0f || jiggled != 0f || pull != Offset.Zero)) {
            // A couple of dp on every side whatever the shape, so a wide pill grows by what a round button does.
            val grow = magnitude * (PressSwell.toPx() * pressed + HoverSwell.toPx() * hovered)
            val wobble = magnitude * Jiggle.toPx() * jiggled
            var sx = 1f + (grow + wobble) / width
            var sy = 1f + (grow - wobble) / height
            var tx = 0f
            var ty = 0f
            if (width <= height * CompactAspect && height <= width * CompactAspect && pull != Offset.Zero) {
                val reach = size.minDimension
                tx = magnitude * reach * tanh(Leash * pull.x / reach)
                ty = magnitude * reach * tanh(Leash * pull.y / reach)
                val stretch = magnitude * PressSwell.toPx() / height
                val angle = atan2(pull.y, pull.x)
                sx += stretch * abs(cos(angle) * pull.x / size.maxDimension) * (width / height).coerceAtMost(1f)
                sy += stretch * abs(sin(angle) * pull.y / size.maxDimension) * (height / width).coerceAtMost(1f)
            }
            translationX = tx
            translationY = ty
            scaleX = sx
            scaleY = sy
        } else {
            translationX = 0f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
        }
    }

    private companion object {
        const val Settled = 0.02f

        /** The phone's press spring, a touch bouncier on the way back so the glass dips past rest as it settles. */
        val PressSpring = spring(0.5f, 300f, Settled)
        val ReleaseSpring = spring(0.45f, 500f, Settled)
        val HoverSpring = spring(Spring.DampingRatioLowBouncy, 420f, Settled)
        val DragSpring = spring(0.5f, 300f, Offset.VisibilityThreshold)

        /** A loose spring kicked at let-go: two visible swings, done in under half a second. */
        val JiggleSpring = spring(0.35f, 700f, Settled)
        const val JiggleKick = 40f
        val Jiggle = 2.dp
        const val SheenAlpha = 0.1f
        val PressSwell = 4.dp
        val HoverSwell = 1.5.dp
        const val Leash = 0.05f

        /** Wider (or taller) than this many times the other side, glass is a bar: it swells but never leans. */
        const val CompactAspect = 3f
    }
}

/** This control's elastic glass, or null with Button motion off. */
@Composable
fun rememberElasticGlass(): ElasticGlass? {
    if (!LocalElasticGlass.current) return null
    val scope = rememberCoroutineScope()
    return remember(scope) { ElasticGlass(scope) }
}

/**
 * Puts [glass]'s motion on this control: the layer first, so the swell takes the whole control
 * with it, and the pointer watch inside. [hovers] false keeps it still until pressed, for the bar
 * the pointer crosses all the time.
 */
@Composable
fun Modifier.elastic(glass: ElasticGlass?, magnitude: Float = 1f, hovers: Boolean = true, enabled: Boolean = true): Modifier {
    if (glass == null || !enabled) return this
    val layer = remember(glass, magnitude) { glass.layer(magnitude) }
    val input = remember(glass, hovers) { glass.input(hovers) }
    return graphicsLayer(layer).then(input)
}

/**
 * A bare glyph on the glass (the bar's transport and the buttons beside it): it dips under a
 * press and wobbles back as it's let go, the phone's pressWobble, and a glyph that opens
 * something also plays the SF Symbols bounce (never the transport): a quick dip, then a loose
 * spring that overshoots and settles. One scale, read only in the glyph's own layer.
 */
@Stable
class GlyphPress internal constructor(private val scope: CoroutineScope) {
    private val scale = Animatable(1f, Settled)
    private var held = false

    /** Goes inside the click handling, so the dip never pulls the glyph out from under the pointer. */
    val layer: Modifier = Modifier.graphicsLayer {
        val s = scale.value
        scaleX = s
        scaleY = s
    }

    /** Watches the primary button, as the click does, without taking anything from it. */
    val input: Modifier = Modifier.pointerInput(this) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Press -> if (event.buttons.isPrimaryPressed && !held) {
                        held = true
                        scope.launch { scale.animateTo(Dip, DipSpring) }
                    }
                    PointerEventType.Release -> if (held && !event.buttons.isPrimaryPressed) {
                        held = false
                        scope.launch { scale.animateTo(1f, WobbleSpring) }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun bounce() {
        scope.launch {
            scale.animateTo(0.8f, tween(90, easing = FastOutLinearInEasing))
            scale.animateTo(1f, BounceSpring)
        }
    }

    private companion object {
        /** A quarter of a percent of a glyph's size: well under a pixel. */
        const val Settled = 0.004f
        const val Dip = 0.86f
        val DipSpring = spring(0.75f, 1220f, Settled)
        val WobbleSpring = spring(0.38f, Spring.StiffnessMedium, Settled)
        val BounceSpring = spring(0.38f, 520f, Settled)
    }
}

/** A glyph's press and bounce, or null with Button motion off. */
@Composable
fun rememberGlyphPress(): GlyphPress? {
    if (!LocalElasticGlass.current) return null
    val scope = rememberCoroutineScope()
    return remember(scope) { GlyphPress(scope) }
}
