/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.convx.desktop.ui.theme.ConvxMotion
import kotlinx.coroutines.delay

/**
 * The like's pop: when [liked] turns on, the filled heart grows out of nothing and settles with
 * a little give; turned off, the outline comes back from a touch smaller. Nothing moves the first
 * time it shows.
 */
@Composable
fun Modifier.likePop(liked: Boolean): Modifier {
    val scale = remember { Animatable(1f) }
    var shown by remember { mutableStateOf(liked) }
    LaunchedEffect(liked) {
        if (liked == shown) return@LaunchedEffect
        scale.snapTo(if (liked) 0f else 0.6f)
        shown = liked
        scale.animateTo(1f, if (liked) ConvxMotion.likePop() else ConvxMotion.appear())
    }
    return graphicsLayer {
        // The frame that swaps the glyph already starts small, before the effect above has run.
        val s = if (liked != shown) (if (liked) 0f else 0.6f) else scale.value
        scaleX = s
        scaleY = s
    }
}

/**
 * Paces a page's covers as it opens, so they rise in one after another rather than all at once.
 * The clock starts with the first cover, so a page that loads its shelves late still cascades;
 * covers that turn up once it has settled, scrolled into view, simply show.
 */
class PageReveal {
    private var first = 0L
    private var turn = 0

    /** How long a cover composed now waits before it rises, or null when it should just show. */
    fun waitFor(): Long? {
        val now = System.nanoTime() / 1_000_000
        if (first == 0L) first = now
        val elapsed = now - first
        if (elapsed > SettleMillis) return null
        return (FirstMillis + StepMillis * minOf(turn++, MaxSteps) - elapsed).coerceAtLeast(0)
    }

    private companion object {
        const val FirstMillis = 40L
        const val StepMillis = 38L
        const val MaxSteps = 14
        const val SettleMillis = 700L
    }
}

/** The page being opened, for [riseIn]; null outside the pages (the player, the side panels). */
val LocalPageReveal = staticCompositionLocalOf<PageReveal?> { null }

/** A cover rising into place, in its turn, as its page opens. */
@Composable
fun Modifier.riseIn(): Modifier {
    val reveal = LocalPageReveal.current ?: return this
    val wait = remember { reveal.waitFor() } ?: return this
    val progress = remember { Animatable(0f) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(wait)
        progress.animateTo(1f, ConvxMotion.cascade())
        settled = true
    }
    // Once in place it drops the layer, so a settled page costs nothing extra.
    if (settled) return this
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 18.dp.toPx()
        val s = 0.96f + 0.04f * p
        scaleX = s
        scaleY = s
    }
}
