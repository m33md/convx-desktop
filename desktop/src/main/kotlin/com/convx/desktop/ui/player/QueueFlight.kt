/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.theme.ConvxMotion
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A queued song's cover hopping into the bar's queue button; the button bumps as it lands and
 * shows how many went in. It only flies where there is a bar to land in.
 */
object QueueFlight {
    /** The bar's queue button, in window px; null while the bar is away. */
    internal var target by mutableStateOf<Rect?>(null)
    internal var flight by mutableStateOf<Flight?>(null)
    internal var landing by mutableStateOf<Landing?>(null)
    private var next = 0L

    internal class Flight(val id: Long, val thumbnail: String?, val from: Offset, val count: Int)
    internal class Landing(val id: Long, val count: Int)

    /**
     * Sends [thumbnail] from [from] (window px) into the queue button. False when there is no bar
     * to land in, so the caller says it in words instead.
     */
    fun launch(thumbnail: String?, from: Offset?, count: Int): Boolean {
        if (from == null || target == null || UiState.fullPlayer || count <= 0) return false
        flight = Flight(next++, thumbnail, from, count)
        return true
    }

    internal fun land(arrived: Flight) {
        if (flight?.id == arrived.id) flight = null
        landing = Landing(arrived.id, arrived.count)
    }
}

private const val FlightMillis = 620
private val FlightSize = 40.dp

// Across at an even pace; tossed up a little, then falling into the button.
private val AcrossEasing = CubicBezierEasing(0.35f, 0f, 0.25f, 1f)
private val Hop = 60.dp

/** The cover in the air, over the pages and the bar. Sits at the window's top-left. */
@Composable
fun QueueFlightLayer() {
    val flight = QueueFlight.flight ?: return
    val target = QueueFlight.target ?: return
    val progress = remember(flight.id) { Animatable(0f) }
    LaunchedEffect(flight.id) {
        progress.animateTo(1f, tween(FlightMillis, easing = LinearEasing))
        QueueFlight.land(flight)
    }
    Artwork(
        flight.thumbnail,
        Modifier
            .size(FlightSize)
            .graphicsLayer {
                val t = progress.value
                val half = FlightSize.toPx() / 2f
                translationX = lerp(flight.from.x, target.center.x, AcrossEasing.transform(t)) - half
                translationY = lerp(flight.from.y, target.center.y, t * t) - Hop.toPx() * 4f * t * (1f - t) - half
                // It swells as it lifts off, then shrinks into the button and fades as it goes in.
                val scale = when {
                    t < 0.2f -> lerp(1f, 1.18f, t / 0.2f)
                    t < 0.85f -> lerp(1.18f, 0.75f, (t - 0.2f) / 0.65f)
                    else -> lerp(0.75f, 0.3f, (t - 0.85f) / 0.15f)
                }
                scaleX = scale
                scaleY = scale
                alpha = if (t < 0.9f) 1f else 1f - (t - 0.9f) / 0.1f
                shadowElevation = 12.dp.toPx()
                shape = RoundedCornerShape(7.dp)
                clip = true
            },
        size = 120,
        shape = RoundedCornerShape(7.dp),
        iconSize = 18.dp,
    )
}

/** Marks the bar's queue button as where covers land, while it's on screen. */
@Composable
internal fun Modifier.queueTarget(): Modifier {
    DisposableEffect(Unit) { onDispose { QueueFlight.target = null } }
    return onGloballyPositioned { QueueFlight.target = it.boundsInWindow() }
}

/** The queue button's bump as a cover lands in it. */
@Composable
internal fun Modifier.queueBump(): Modifier {
    val landing = QueueFlight.landing
    val scale = remember { Animatable(1f) }
    LaunchedEffect(landing?.id) {
        if (landing == null) return@LaunchedEffect
        scale.snapTo(1.35f)
        scale.animateTo(1f, ConvxMotion.bump())
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/** "+1" on the queue button, popping in as a cover lands and fading a moment later. */
@Composable
internal fun QueueBadge(modifier: Modifier = Modifier) {
    val landing = QueueFlight.landing ?: return
    val scale = remember(landing.id) { Animatable(0f) }
    val fade = remember(landing.id) { Animatable(1f) }
    LaunchedEffect(landing.id) {
        launch { scale.animateTo(1f, ConvxMotion.likePop()) }
        delay(1100)
        fade.animateTo(0f, tween(220))
        if (QueueFlight.landing?.id == landing.id) QueueFlight.landing = null
    }
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = fade.value
            }
            .defaultMinSize(minWidth = 20.dp)
            .height(18.dp)
            .shadow(4.dp, RoundedCornerShape(9.dp))
            .clip(RoundedCornerShape(9.dp))
            .background(LocalAccent.current)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "+${landing.count}",
            style = ConvxType.Footnote.copy(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.ExtraBold),
            color = Color.White,
            maxLines = 1,
        )
    }
}
