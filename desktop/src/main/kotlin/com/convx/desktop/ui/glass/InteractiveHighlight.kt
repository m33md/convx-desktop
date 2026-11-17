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
import androidx.compose.ui.geometry.Offset
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
 * A press on floating glass, tracked for [elasticStretch]: the press itself, [pressProgress], and
 * the drag since the press, [offset]. The glass swells a little under the press, leans and
 * stretches toward a drag, then springs back when let go. Nothing lights up under the pointer.
 */
class InteractiveHighlight(private val scope: CoroutineScope) {
    private val progressSpec = spring(0.5f, 300f, 0.001f)
    private val positionSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)
    private val press = Animatable(0f, 0.001f)
    private val drag = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var start = Offset.Zero
    private var pressed = false

    /** 0 at rest, 1 held down; springs past both ends, as the phone's does. */
    val pressProgress: Float get() = press.value

    /** How far the pointer has moved since the press, springing back to zero after it. */
    val offset: Offset get() = drag.value

    val gestureModifier: Modifier = Modifier.pointerInput(this) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                when (event.type) {
                    PointerEventType.Exit -> {
                        // A drag that leaves the glass keeps stretching it until the button is let go.
                        if (pressed && !event.buttons.isPrimaryPressed) {
                            pressed = false
                            letGo()
                        }
                    }
                    PointerEventType.Move -> {
                        if (pressed) {
                            val at = change.position
                            scope.launch { drag.snapTo(at - start) }
                        }
                    }
                    PointerEventType.Press -> {
                        pressed = true
                        start = change.position
                        scope.launch { drag.snapTo(Offset.Zero) }
                        scope.launch { press.animateTo(1f, progressSpec) }
                    }
                    PointerEventType.Release -> {
                        pressed = false
                        letGo()
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
}

@Composable
fun rememberInteractiveHighlight(): InteractiveHighlight {
    val scope = rememberCoroutineScope()
    return remember(scope) { InteractiveHighlight(scope) }
}

/**
 * The phone's elastic stretch (GlassCircleButton.kt, elasticStretchLayerBlock), shared by every
 * floating glass control: pressing swells it by 4dp, dragging pulls it after the pointer on a
 * tanh leash and stretches it along the drag. Bar pills pass [magnitude] 2 so the same feel
 * travels far enough to read at their size.
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
        val grow = magnitude * 4.dp.toPx() * progress
        var sx = 1f + grow / width
        var sy = 1f + grow / height
        var tx = 0f
        var ty = 0f

        if (width <= height * CompactAspect && height <= width * CompactAspect) {
            val maxOffset = size.minDimension
            val initialDerivative = 0.05f
            val offset = highlight.offset
            tx = magnitude * maxOffset * tanh(initialDerivative * offset.x / maxOffset)
            ty = magnitude * maxOffset * tanh(initialDerivative * offset.y / maxOffset)

            val maxDragScale = magnitude * 4.dp.toPx() / height
            val offsetAngle = atan2(offset.y, offset.x)
            sx += maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) * (width / height).coerceAtMost(1f)
            sy += maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) * (height / width).coerceAtMost(1f)
        }
        translationX = tx
        translationY = ty
        scaleX = sx
        scaleY = sy
    }
}

/** Wider (or taller) than this many times the other side, glass is a bar: it swells but never leans. */
private const val CompactAspect = 3f
