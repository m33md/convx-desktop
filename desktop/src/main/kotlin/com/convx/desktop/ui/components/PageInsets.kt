/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Room the floating glass chrome takes from a page: Your Library on the left, the Now Playing
 * panel on the right, the top bar (and a section's strip under it) at the top, and the now
 * playing pill at the bottom. Pages spend it as list padding, as the phone's tab view does, so
 * rows start clear of the glass while the list still spans the whole window and scrolls
 * underneath it.
 */
@Immutable
data class PageInsets(val start: Dp = 0.dp, val top: Dp = 64.dp, val bottom: Dp = 0.dp, val end: Dp = 0.dp)

val LocalPageInsets = compositionLocalOf { PageInsets() }

/** The width a page's list has between the side bar and the side panel. */
val LocalPageWidth = compositionLocalOf { 1000.dp }

/**
 * Widens a list item [start] to the left, out to the window's edge, so a sideways shelf or a
 * hero photo runs under the side bar's glass instead of stopping at its inset.
 */
fun Modifier.bleedStart(start: Dp): Modifier = if (start <= 0.dp) this else layout { measurable, constraints ->
    val extra = start.roundToPx()
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}
