/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.PlayerIconSlot
import com.convx.desktop.ui.components.rememberPlayerIcon
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.convx.desktop.ui.components.awaitPacedFrameMillis
import com.convx.desktop.ui.components.rememberLoopingPhase
import com.convx.desktop.ui.shell.UiState
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * The playback position for a view to draw. The controller reports every 100 ms while playing;
 * with [stepMs] 0 this runs the clock forward between reports at frame rate, so the lyric sweep
 * and the big bar glide. With a step it changes only when the position crosses a multiple of it
 * (the small bar's pixel, the clock's second), so the window draws a few times a second instead
 * of every frame. Neither moves while paused or minimised.
 */
@Composable
fun rememberSmoothPosition(stepMs: Long = 0): State<Long> {
    fun snap(ms: Long, step: Long) = if (step > 0) ms - ms % step else ms
    val smooth = remember { mutableLongStateOf(snap(PlayerController.position.value, stepMs)) }
    val step by rememberUpdatedState(stepMs)
    LaunchedEffect(Unit) {
        val playing = PlayerController.state.map { it.isPlaying && !it.isLoading }.distinctUntilChanged()
        val shown = snapshotFlow { UiState.windowShown }
        combine(PlayerController.position, playing, shown) { base, isPlaying, isShown -> Triple(base, isPlaying, isShown) }
            .collectLatest { (base, isPlaying, isShown) ->
                if (!isShown) return@collectLatest
                val s = step
                val at = snap(base, s)
                if (smooth.longValue != at) smooth.longValue = at
                // A step is coarser than the controller's reports already; only the frame clock runs ahead.
                if (!isPlaying || s > 0) return@collectLatest
                val start = awaitPacedFrameMillis()
                val tempo = PlayerController.state.value.tempo
                while (true) {
                    val now = awaitPacedFrameMillis()
                    // Never run more than a little ahead of the last report.
                    smooth.longValue = base + ((now - start) * tempo).toLong().coerceAtMost(250)
                }
            }
    }
    return smooth
}

/**
 * The progress bar. With [wavy] on it is the phone's squiggle: the played part
 * ripples while music plays and lies flat when paused.
 */
@Composable
fun SeekBar(
    positionMs: () -> Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    wavy: Boolean = true,
    playing: Boolean = false,
    activeColor: Color = Color.White,
    hoverColor: Color = activeColor,
    trackColor: Color = Color.White.copy(alpha = 0.22f),
    height: Dp = 18.dp,
    strokeWidth: Dp = 4.dp,
    /** The phone's Default style keeps its thumb showing; the others show one only under the pointer. */
    thumb: Boolean = false,
    /** The player's own bar: a seek handle image from Player icons takes the thumb's place, always shown. */
    customThumb: Boolean = false,
) {
    val handle = if (customThumb) rememberPlayerIcon(PlayerIconSlot.SEEK_THUMB) else null
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val currentDuration by rememberUpdatedState(durationMs)
    val currentOnSeek by rememberUpdatedState(onSeek)

    val amplitude by animateFloatAsState(if (wavy && playing && dragFraction == null) 1f else 0f, tween(400))
    // The squiggle travels only while it shows; a flat bar asks for no frames.
    val phase by rememberLoopingPhase(1300, running = amplitude > 0f)

    val engaged = hovered || dragFraction != null

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    dragFraction = (down.position.x / width).coerceIn(0f, 1f)
                    down.consume()
                    drag(down.id) { change ->
                        dragFraction = (change.position.x / width).coerceIn(0f, 1f)
                        change.consume()
                    }
                    val target = dragFraction
                    if (target != null && currentDuration > 0) currentOnSeek((target * currentDuration).toLong())
                    dragFraction = null
                }
            },
    ) {
        val fraction = dragFraction ?: if (durationMs > 0) (positionMs().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val stroke = strokeWidth.toPx()
        val cy = size.height / 2
        val inset = stroke / 2
        val usable = size.width - stroke
        val x = inset + usable * fraction
        val color = if (engaged) hoverColor else activeColor

        // Remaining track
        drawLine(trackColor, Offset(x, cy), Offset(size.width - inset, cy), stroke, StrokeCap.Round)

        // Played part
        val amp = 2.6.dp.toPx() * amplitude
        if (amp > 0.3f && x - inset > 2f) {
            val wavelength = 26.dp.toPx()
            val path = Path()
            path.moveTo(inset, cy)
            var px = inset
            val step = 2f
            while (px < x) {
                val t = (px - inset) / wavelength - phase
                // Fade the wave in from the left edge and out at the head, so it meets the thumb flat.
                val headFade = ((x - px) / (wavelength * 0.5f)).coerceIn(0f, 1f)
                path.lineTo(px, cy + amp * headFade * sin(2 * PI * t).toFloat())
                px += step
            }
            path.lineTo(x, cy)
            drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
        } else {
            drawLine(color, Offset(inset, cy), Offset(x, cy), stroke, StrokeCap.Round)
        }

        if (handle != null) {
            // The phone draws a picked handle at 18dp, centred on the playhead.
            val r = 9.dp.toPx()
            translate(x - r, cy - r) {
                with(handle.painter) { draw(Size(r * 2, r * 2), colorFilter = handle.colorFilter(color)) }
            }
        } else if (engaged || thumb) {
            drawCircle(Color.White, radius = (if (thumb) 8.dp else 6.5.dp).toPx(), center = Offset(x, cy))
        }
    }
}

/**
 * Apple Music's progress capsule, which both Apple designs draw: thin and quiet, thicker under
 * the pointer or a drag, as Apple's swells under a finger.
 */
@Composable
fun AppleSeekBar(
    positionMs: () -> Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val currentDuration by rememberUpdatedState(durationMs)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val thickness by animateFloatAsState(if (hovered || dragFraction != null) 11f else 7f, tween(180))
    Canvas(
        modifier
            .fillMaxWidth()
            .height(18.dp)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    dragFraction = (down.position.x / width).coerceIn(0f, 1f)
                    down.consume()
                    drag(down.id) { change ->
                        dragFraction = (change.position.x / width).coerceIn(0f, 1f)
                        change.consume()
                    }
                    val target = dragFraction
                    if (target != null && currentDuration > 0) currentOnSeek((target * currentDuration).toLong())
                    dragFraction = null
                }
            },
    ) {
        val fraction = dragFraction ?: if (durationMs > 0) (positionMs().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val h = thickness.dp.toPx()
        val top = (size.height - h) / 2f
        val corner = CornerRadius(h / 2f)
        drawRoundRect(color.copy(alpha = 0.22f), Offset(0f, top), Size(size.width, h), corner)
        clipRect(right = size.width * fraction) {
            drawRoundRect(color.copy(alpha = 0.85f), Offset(0f, top), Size(size.width, h), corner)
        }
    }
}

/** A random walk, so neighbouring bars relate and it reads like audio rather than noise (the phone's waveformBars). */
private fun waveformBars(seed: Int, count: Int): FloatArray {
    val random = Random(seed)
    var previous = 0.5f
    return FloatArray(count) {
        previous = (previous + (random.nextFloat() - 0.5f) * 0.6f).coerceIn(0.12f, 1f)
        previous
    }
}

/**
 * The phone's Waveform seek bar: a window of rounded bars that glides with the song, centred on
 * the playhead and fading out at both ends. Drag it sideways to scrub, or click a bar.
 */
@Composable
fun WaveformSeekBar(
    positionMs: () -> Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    seed: Int,
    modifier: Modifier = Modifier,
    playedColor: Color = Color.White,
    trackColor: Color = Color.White.copy(alpha = 0.22f),
    totalBars: Int = 160,
    visibleBars: Int = 44,
) {
    val heights = remember(seed, totalBars) { waveformBars(seed, totalBars) }
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    val currentDuration by rememberUpdatedState(durationMs)
    val currentOnSeek by rememberUpdatedState(onSeek)
    fun progress(): Float = if (durationMs > 0) (positionMs().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Canvas(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, 0.22f to Color.Black, 0.78f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val start = dragProgress ?: (if (currentDuration > 0) (positionMs().toFloat() / currentDuration).coerceIn(0f, 1f) else 0f)
                    // A click lands on the bar under the pointer; a drag scrubs from where the song is.
                    dragProgress = (start + (down.position.x / width - 0.5f) * visibleBars / totalBars).coerceIn(0f, 1f)
                    down.consume()
                    drag(down.id) { change ->
                        val delta = change.position.x - change.previousPosition.x
                        dragProgress = ((dragProgress ?: start) - delta / width * visibleBars / totalBars).coerceIn(0f, 1f)
                        change.consume()
                    }
                    dragProgress?.let { if (currentDuration > 0) currentOnSeek((it * currentDuration).toLong()) }
                    dragProgress = null
                }
            },
    ) {
        val n = heights.size
        if (n == 0 || size.width <= 0f) return@Canvas
        val current = (dragProgress ?: progress()).coerceIn(0f, 1f)
        val mid = size.height / 2f
        val slot = size.width / visibleBars
        val barWidth = slot * 0.55f
        val centerBar = current * (n - 1)
        val firstBar = centerBar - visibleBars / 2f
        val startIndex = floor(firstBar).toInt()
        for (i in startIndex..(startIndex + visibleBars + 1)) {
            if (i !in 0 until n) continue
            val x = (i - firstBar) * slot + slot / 2f
            val h = (heights[i] * size.height * 0.9f).coerceAtLeast(size.height * 0.15f)
            drawRoundRect(
                if (i <= centerBar) playedColor else trackColor,
                Offset(x - barWidth / 2f, mid - h / 2f),
                Size(barWidth, h),
                CornerRadius(barWidth / 2f),
            )
        }
    }
}

/** Plain slider for volume. */
@Composable
fun ValueSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = Color.White,
    hoverColor: Color = activeColor,
    trackColor: Color = Color.White.copy(alpha = 0.22f),
    height: Dp = 18.dp,
    strokeWidth: Dp = 4.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val currentOnChange by rememberUpdatedState(onValueChange)
    val engaged = hovered || dragging
    Canvas(
        modifier
            .height(height)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    dragging = true
                    currentOnChange((down.position.x / width).coerceIn(0f, 1f))
                    down.consume()
                    drag(down.id) { change ->
                        currentOnChange((change.position.x / width).coerceIn(0f, 1f))
                        change.consume()
                    }
                    dragging = false
                }
            },
    ) {
        val stroke = strokeWidth.toPx()
        val cy = size.height / 2
        val inset = stroke / 2
        val x = inset + (size.width - stroke) * value.coerceIn(0f, 1f)
        drawLine(trackColor, Offset(inset, cy), Offset(size.width - inset, cy), stroke, StrokeCap.Round)
        drawLine(if (engaged) hoverColor else activeColor, Offset(inset, cy), Offset(x, cy), stroke, StrokeCap.Round)
        if (engaged) drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(x, cy))
    }
}
