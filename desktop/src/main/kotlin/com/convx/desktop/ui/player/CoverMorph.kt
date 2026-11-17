/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.theme.ConvxMotion

/**
 * The bar's small cover growing into the player's big one as the player slides up, and shrinking
 * back into the bar as it closes. A stand-in cover flies between the two while both real ones
 * hide; where the player's cover isn't a plain square (a record's grooves, a clover, a full-bleed
 * cover) the stand-in hands over to it with a short cross-fade as it lands.
 */
object CoverMorph {
    /** The bar's cover, in window px; null while the bar is away. */
    internal var bar by mutableStateOf<Rect?>(null)
    internal var barCorner = 0f

    /** The player's cover, in window px, as it rides the sheet; null while there's none to grow into. */
    internal var stage by mutableStateOf<Stage?>(null)

    /** 0 at the bar, 1 in the player. */
    internal val progress = Animatable(0f)
    internal var flying by mutableStateOf(false)

    internal class Stage(val rect: Rect, val corner: Float, val soft: Boolean, val angle: () -> Float)

    /** How much of the player's own cover shows while the stand-in has its place. */
    fun stageAlpha(): Float {
        if (!flying) return 1f
        val s = stage ?: return 1f
        return if (s.soft) ((progress.value - (1f - SoftSpan)) / SoftSpan).coerceIn(0f, 1f) else 0f
    }

    /** The bar's cover hides while the stand-in is out of it. */
    fun barAlpha(): Float = if (flying && stage != null) 0f else 1f

    internal fun flyingAlpha(): Float =
        if (stage?.soft == true) ((1f - progress.value) / SoftSpan).coerceIn(0f, 1f) else 1f

    /** How far before the end a soft stand-in hands over to the real cover: close enough that the two don't show apart. */
    private const val SoftSpan = 0.06f
}

/** Marks the bar's cover as where the player's grows from, and hides it while the stand-in is out. */
@Composable
internal fun Modifier.coverBar(corner: Float): Modifier {
    DisposableEffect(Unit) { onDispose { CoverMorph.bar = null } }
    return this
        .onGloballyPositioned {
            CoverMorph.bar = it.windowRect()
            CoverMorph.barCorner = corner
        }
        .graphicsLayer { alpha = CoverMorph.barAlpha() }
}

/**
 * Marks the player's cover as where the bar's grows to: [corner] its corner radius in px,
 * [soft] when it isn't a plain square, [angle] how far it has turned. Put it after any scale
 * the cover has, so the place is where it's drawn.
 */
@Composable
internal fun Modifier.coverStage(corner: Float, soft: Boolean, angle: () -> Float = { 0f }): Modifier {
    DisposableEffect(Unit) { onDispose { CoverMorph.stage = null } }
    return onGloballyPositioned { CoverMorph.stage = CoverMorph.Stage(it.windowRect(), corner, soft, angle) }
}

/** The box as drawn, unclipped: the sheet starts off the bottom of the window. */
private fun LayoutCoordinates.windowRect(): Rect =
    Rect(localToWindow(Offset.Zero), localToWindow(Offset(size.width.toFloat(), size.height.toFloat())))

/** The stand-in cover, over the player. Sits at the window's top-left. */
@Composable
fun CoverMorphLayer() {
    val open = UiState.fullPlayer
    LaunchedEffect(open) {
        val target = if (open) 1f else 0f
        if (CoverMorph.progress.value == target || CoverMorph.bar == null) {
            CoverMorph.progress.snapTo(target)
            CoverMorph.flying = false
            return@LaunchedEffect
        }
        CoverMorph.flying = true
        // The sheet's own spring, so the cover lands as the sheet does.
        CoverMorph.progress.animateTo(target, ConvxMotion.playerSheet())
        CoverMorph.flying = false
    }
    if (!CoverMorph.flying) return
    val bar = CoverMorph.bar ?: return
    val stage = CoverMorph.stage ?: return
    val song = PlayerController.state.collectAsState().value.current ?: return
    val density = LocalDensity.current
    val width = with(density) { stage.rect.width.toDp() }
    val height = with(density) { stage.rect.height.toDp() }
    // Laid out at the player's size and scaled down, so the picture is made once, not every frame.
    Box(
        Modifier
            .size(width, height)
            .graphicsLayer {
                val p = CoverMorph.progress.value
                val to = CoverMorph.stage?.rect ?: stage.rect
                val w = lerp(bar.width, to.width, p)
                val h = lerp(bar.height, to.height, p)
                val scale = (w / size.width).coerceAtLeast(0.001f)
                transformOrigin = TransformOrigin.Center
                scaleX = scale
                scaleY = (h / size.height).coerceAtLeast(0.001f)
                translationX = lerp(bar.center.x, to.center.x, p) - size.width / 2f
                translationY = lerp(bar.center.y, to.center.y, p) - size.height / 2f
                rotationZ = stage.angle() * p
                shape = RoundedCornerShape(lerp(CoverMorph.barCorner, stage.corner, p) / scale)
                clip = true
                shadowElevation = 20.dp.toPx() * p
                alpha = CoverMorph.flyingAlpha()
            },
    ) {
        Artwork(song.thumbnail, Modifier.fillMaxSize(), size = 1000, shape = RectangleShape, iconSize = 80.dp)
    }
}
