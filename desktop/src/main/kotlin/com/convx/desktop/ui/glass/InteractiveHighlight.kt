/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The phone's InteractiveHighlight: an additive sheen with a soft spot of light under the
 * pointer. On the phone it follows a finger while pressed; here the cursor also lights the
 * glass at half strength while it hovers, and a press brings it to full.
 *
 * A mouse can hover, which a finger can't, so the glass answers that too: it swells part of the
 * way a press takes it and leans a little toward the pointer, on the same springs as the press.
 *
 * [modifier] draws the light and [gestureModifier] listens; they are separate so the light can
 * sit on a recorded glass layer while the hit target is the moving element.
 *
 * It also tracks the press itself, [pressProgress] and the drag since the press, [offset], which
 * [elasticStretch] turns into the phone's stretch: the glass swells a little under the press and
 * leans and stretches toward a drag, then springs back when let go.
 */
class InteractiveHighlight(
    private val scope: CoroutineScope,
    private val radiusScale: Float = 1.5f,
    private val sheenAlpha: Float = 0.08f,
    private val spotAlpha: Float = 0.15f,
    private val hoverStrength: Float = 0.5f,
) {
    private val progressSpec = spring(0.5f, 300f, 0.001f)
    private val positionSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)
    private val progress = Animatable(0f, 0.001f)
    private val position = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private val press = Animatable(0f, 0.001f)
    private val drag = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private val hover = Animatable(0f, 0.001f)
    private val lean = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var start = Offset.Zero
    private var hovered = false
    private var pressed = false

    /** 0 at rest, 1 held down; springs past both ends, as the phone's does. */
    val pressProgress: Float get() = press.value

    /** How far the pointer has moved since the press, springing back to zero after it. */
    val offset: Offset get() = drag.value

    /** 0 away, 1 with the pointer over the glass; springs, so the swell wobbles in. */
    val hoverProgress: Float get() = hover.value

    /** Where the pointer is over the glass, -1..1 from the centre on each axis, while it hovers. */
    val hoverLean: Offset get() = lean.value

    private fun leanFor(position: Offset, width: Int, height: Int): Offset {
        if (width <= 0 || height <= 0) return Offset.Zero
        return Offset(
            ((position.x - width / 2f) / (width / 2f)).coerceIn(-1f, 1f),
            ((position.y - height / 2f) / (height / 2f)).coerceIn(-1f, 1f),
        )
    }

    val modifier: Modifier = Modifier.drawWithContent {
        val p = progress.value
        if (p > 0f) {
            // Plus lifts what is behind without replacing its detail.
            drawRect(Color.White.copy(alpha = sheenAlpha * p), blendMode = BlendMode.Plus)
            val radius = (size.minDimension * radiusScale).coerceAtLeast(1f)
            val pos = position.value
            val center = Offset(pos.x.coerceIn(0f, size.width), pos.y.coerceIn(0f, size.height))
            val spot = Color.White.copy(alpha = spotAlpha * p)
            drawRect(
                brush = Brush.radialGradient(
                    0f to spot,
                    0.5f to spot,
                    1f to Color.White.copy(alpha = 0f),
                    center = center,
                    radius = radius,
                ),
                blendMode = BlendMode.Plus,
            )
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(this) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                when (event.type) {
                    PointerEventType.Enter -> {
                        hovered = true
                        scope.launch { position.snapTo(change.position) }
                        scope.launch { lean.animateTo(leanFor(change.position, size.width, size.height), positionSpec) }
                        settle()
                    }
                    PointerEventType.Exit -> {
                        hovered = false
                        scope.launch { lean.animateTo(Offset.Zero, positionSpec) }
                        // A drag that leaves the glass keeps stretching it until the button is let go.
                        if (!event.buttons.isPrimaryPressed) {
                            if (pressed) letGo()
                            pressed = false
                        }
                        settle()
                    }
                    PointerEventType.Move -> {
                        val at = change.position
                        if (pressed) {
                            scope.launch {
                                position.snapTo(at)
                                drag.snapTo(at - start)
                            }
                        } else {
                            // One animation per value: a new target takes over the running spring, keeping its speed.
                            scope.launch { position.animateTo(at, positionSpec) }
                            if (hovered) scope.launch { lean.animateTo(leanFor(at, size.width, size.height), positionSpec) }
                        }
                    }
                    PointerEventType.Press -> {
                        pressed = true
                        start = change.position
                        scope.launch { position.snapTo(change.position) }
                        scope.launch { drag.snapTo(Offset.Zero) }
                        scope.launch { press.animateTo(1f, progressSpec) }
                        settle()
                    }
                    PointerEventType.Release -> {
                        pressed = false
                        letGo()
                        settle()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun letGo() {
        scope.launch { press.animateTo(0f, progressSpec) }
        scope.launch { drag.animateTo(Offset.Zero, positionSpec) }
    }

    private fun settle() {
        val target = when {
            pressed -> 1f
            hovered -> hoverStrength
            else -> 0f
        }
        scope.launch { progress.animateTo(target, progressSpec) }
        scope.launch { hover.animateTo(if (hovered || pressed) 1f else 0f, progressSpec) }
    }
}

@Composable
fun rememberInteractiveHighlight(radiusScale: Float = 1.5f): InteractiveHighlight {
    val scope = rememberCoroutineScope()
    return remember(scope, radiusScale) { InteractiveHighlight(scope, radiusScale = radiusScale) }
}

/**
 * The phone's elastic stretch (GlassCircleButton.kt, elasticStretchLayerBlock), shared by every
 * floating glass control: pressing swells it by 4dp, dragging pulls it after the pointer on a
 * tanh leash and stretches it along the drag. Bar pills pass [magnitude] 2 so the same feel
 * travels far enough to read at their size.
 *
 * Hovering, which the phone has no finger for, plays the same motion at half: the glass swells
 * 2dp and leans up to 1.5dp toward the pointer, stretching a touch that way. A press takes over
 * from it smoothly, since both feed the same scale.
 *
 * The swell is the same few dp on every side whatever the shape, so a long bar grows by what a
 * button does, not by its width. Only compact shapes lean and follow a drag: a bar holds still,
 * so the control under the pointer (a slider being dragged, a button about to be clicked) stays
 * where it is.
 */
fun elasticStretch(highlight: InteractiveHighlight, magnitude: Float = 1f): GraphicsLayerScope.() -> Unit = {
    val width = size.width
    val height = size.height
    if (width > 0f && height > 0f) {
        val progress = highlight.pressProgress
        val hover = highlight.hoverProgress
        val held = progress.coerceIn(0f, 1f)
        // The hover's share fades as the press comes in, so the two never add past a full press.
        val swell = progress + HoverSwell * hover * (1f - held)
        val grow = magnitude * 4.dp.toPx() * swell
        var sx = 1f + grow / width
        var sy = 1f + grow / height
        var tx = 0f
        var ty = 0f

        if (width <= height * CompactAspect && height <= width * CompactAspect) {
            val maxOffset = size.minDimension
            val initialDerivative = 0.05f
            val offset = highlight.offset
            val lean = highlight.hoverLean * (hover.coerceAtLeast(0f) * (1f - held))
            val leanPx = magnitude * HoverLean.toPx()
            tx = magnitude * maxOffset * tanh(initialDerivative * offset.x / maxOffset) + leanPx * lean.x
            ty = magnitude * maxOffset * tanh(initialDerivative * offset.y / maxOffset) + leanPx * lean.y

            val maxDragScale = magnitude * 4.dp.toPx() / height
            val offsetAngle = atan2(offset.y, offset.x)
            val leanScale = maxDragScale * HoverSwell
            sx += maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) * (width / height).coerceAtMost(1f) +
                leanScale * abs(lean.x) * (width / height).coerceAtMost(1f)
            sy += maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) * (height / width).coerceAtMost(1f) +
                leanScale * abs(lean.y) * (height / width).coerceAtMost(1f)
        }
        translationX = tx
        translationY = ty
        scaleX = sx
        scaleY = sy
    }
}

/** How much of a press's swell a hover plays, and how far the glass leans toward the pointer. */
private const val HoverSwell = 0.5f
private val HoverLean = 1.5.dp

/** Wider (or taller) than this many times the other side, glass is a bar: it swells but never leans. */
private const val CompactAspect = 3f
