/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.artworkUrl
import com.convx.desktop.ui.components.rememberMeshColors
import com.convx.desktop.ui.theme.ConvxType
import kotlinx.coroutines.delay
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * The phone's Ambient mode: the cover and its lyrics side by side over a slowly turning glow of
 * the cover's colours, the screen filled and kept awake. The window goes full screen for it (see
 * Main.kt) and comes back as it was.
 */
object Ambient {
    var on by mutableStateOf(false)
}

/** A cursor with nothing in it, so a mouse left still doesn't sit over the lyrics. */
private val HiddenCursor: PointerIcon by lazy {
    runCatching {
        PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "hidden"))
    }.getOrDefault(PointerIcon.Default)
}

@Composable
fun AmbientOverlay() {
    AnimatedVisibility(Ambient.on, enter = fadeIn(tween(500)), exit = fadeOut(tween(300))) {
        AmbientScreen()
    }
}

@Composable
private fun AmbientScreen() {
    val state by PlayerController.state.collectAsState()
    val song = state.current
    LaunchedEffect(song == null) { if (song == null) Ambient.on = false }
    // The back button and the pointer fade out when the mouse rests, as a video player's do.
    var moves by remember { mutableIntStateOf(0) }
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(moves) {
        idle = false
        delay(3_000)
        idle = true
    }
    var dragX by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Move, PointerEventType.Press -> moves++
                            // The phone's swipe up and down for volume, as the wheel.
                            PointerEventType.Scroll -> {
                                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                val volume = PlayerController.state.value.volume
                                PlayerController.setVolume((volume - delta * 0.05f).coerceIn(0f, 1f))
                                moves++
                            }
                        }
                    }
                }
            }
            // The phone's swipe sideways to skip, as a drag.
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(dragX) > 150f) {
                            if (dragX > 0) PlayerController.previous() else PlayerController.next()
                        }
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragX += amount
                    },
                )
            }
            .pointerHoverIcon(if (idle) HiddenCursor else PointerIcon.Default),
    ) {
        AmbientGlow(song?.thumbnail, Modifier.fillMaxSize())
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight().padding(32.dp), contentAlignment = Alignment.Center) {
                Artwork(
                    song?.thumbnail,
                    Modifier
                        .fillMaxHeight(0.85f)
                        .aspectRatio(1f)
                        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { PlayerController.togglePlay() }) },
                    size = 1200,
                    shape = RoundedCornerShape(16.dp),
                    iconSize = 96.dp,
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp, end = 32.dp, top = 32.dp, bottom = 32.dp), contentAlignment = Alignment.Center) {
                LyricsView(song, Modifier.fillMaxSize(), style = ConvxType.Lyrics)
            }
        }
        AnimatedVisibility(!idle, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            IconButton("arrow_back", { Ambient.on = false }, size = 44.dp, iconSize = 22.dp, filled = true)
        }
    }
}

/**
 * The phone's AmbientGlowBackground: six soft fields of the cover's colours drifting and swapping
 * colours over twenty seconds, on near black.
 */
@Composable
private fun AmbientGlow(thumbnail: String?, modifier: Modifier) {
    val mesh by rememberMeshColors(thumbnail?.let { artworkUrl(it, 226) })
    AnimatedContent(
        targetState = mesh.orEmpty(),
        transitionSpec = { fadeIn(tween(1200)) togetherWith fadeOut(tween(1200)) },
        label = "ambientGlow",
        modifier = modifier,
    ) { colors ->
        if (colors.isEmpty()) {
            Box(Modifier.fillMaxSize())
            return@AnimatedContent
        }
        val progress by rememberInfiniteTransition(label = "glow").animateFloat(
            0f, 1f, infiniteRepeatable(tween(20_000, easing = LinearEasing), RepeatMode.Restart), label = "glowProgress",
        )
        fun colorAt(index: Int): Color {
            val position = index + progress * colors.size
            val a = floor(position).toInt() % colors.size
            val b = (a + 1) % colors.size
            return lerp(colors[a], colors[b], position - floor(position))
        }
        fun oscillate(min: Float, max: Float, phase: Float): Float {
            val v = sin(2f * PI.toFloat() * (progress + phase))
            return min + (max - min) * ((v + 1f) * 0.5f)
        }
        // Each field: its colour, its strength, and where it wanders.
        val fields = listOf(
            Triple(colorAt(0), 0.85f, floatArrayOf(oscillate(0f, 1f, 0f), oscillate(0f, 0.5f, 0.07f), oscillate(0.8f, 1.6f, 0.12f))),
            Triple(colorAt(1), 0.8f, floatArrayOf(oscillate(1f, 0f, 0.2f), oscillate(0.5f, 1f, 0.25f), oscillate(0.7f, 1.5f, 0.18f))),
            Triple(colorAt(2), 0.75f, floatArrayOf(oscillate(0.2f, 0.8f, 0.33f), oscillate(0.8f, 0.2f, 0.36f), oscillate(0.6f, 1.4f, 0.29f))),
            Triple(colorAt(3), 0.7f, floatArrayOf(oscillate(0.3f, 0.7f, 0.44f), oscillate(0.2f, 0.8f, 0.41f), oscillate(0.9f, 1.7f, 0.47f))),
            Triple(colorAt(4), 0.65f, floatArrayOf(oscillate(0.4f, 0.6f, 0.55f), oscillate(0f, 1f, 0.51f), oscillate(0.7f, 1.5f, 0.58f))),
            Triple(colorAt(5), 0.6f, floatArrayOf(oscillate(0f, 1f, 0.66f), oscillate(0.5f, 0.7f, 0.62f), oscillate(0.8f, 1.8f, 0.69f))),
        )
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val brushes = fields.map { (color, alpha, at) ->
                    Brush.radialGradient(
                        listOf(color.copy(alpha = alpha), color.copy(alpha = alpha - 0.35f), Color.Transparent),
                        center = Offset(size.width * at[0], size.height * at[1]),
                        radius = size.width * at[2],
                    )
                }
                onDrawBehind {
                    drawRect(Color(0xFF050505))
                    brushes.forEach { drawRect(it) }
                }
            },
        )
    }
}
