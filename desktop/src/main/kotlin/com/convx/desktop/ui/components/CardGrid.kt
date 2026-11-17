/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Settings

// The phone's Grid & cards choices, as its sliders step through them. 0 is Auto.
val GridColumnChoices = listOf(0, 2, 3, 4, 5, 6)
val GridSpacingChoices = listOf(4, 8, 12, 16, 20, 24, 32)
val GridCardHeightChoices = listOf(0, 96, 112, 128, 144, 164, 184, 200, 220)
val HomeHeroHeightChoices = listOf(0, 200, 240, 280, 320, 360, 400, 440, 480)
val HomeCardCornerChoices = listOf(0, 8, 12, 16, 20, 24, 28, 36)

/** A card's 10dp frame either side of its cover, which lights up on hover. */
private val CardFrame = 20.dp

/**
 * How wide a card is: its cover, from the phone's card height or its Big and Small cell sizes
 * (164 and 128 there; 156 and 124 here, where Big was already 156), and the frame round it.
 */
fun Settings.cardWidth(): Dp = when {
    gridCardHeight > 0 -> gridCardHeight.dp
    gridSmall -> 124.dp
    else -> 156.dp
} + CardFrame

/** The width cards take where nothing sets one: the grid's, or the settings' size. */
val LocalCardWidth = compositionLocalOf { 176.dp }

/** The gap between cards. Each card's frame already keeps covers 20dp apart, so it is what's left of the chosen spacing. */
private fun Settings.gapAcross(): Dp = (gridSpacing - 12).coerceAtLeast(0).dp
private fun Settings.gapDown(): Dp = (gridSpacing - 8).coerceAtLeast(0).dp

/** How a page's cards sit: so many across, each so wide, with these gaps. */
@Immutable
data class CardGridLayout(val columns: Int, val cardWidth: Dp, val across: Dp, val down: Dp)

/**
 * The layout of a page's card grid for [cardGrid], from the page's width and the Grid & cards
 * settings: as many cards as fit at the chosen size, or the chosen number of columns sharing the
 * width.
 */
@Composable
fun rememberCardGrid(): CardGridLayout {
    val settings by AppSettings.state.collectAsState()
    val width = LocalPageWidth.current - (Gutter - 10.dp) * 2
    val across = settings.gapAcross()
    val chosen = settings.gridColumns
    return if (chosen > 0) {
        // Half a dp under the exact share, so rounding never pushes the last card off the row.
        CardGridLayout(chosen, ((width - across * (chosen - 1)) / chosen - 0.5.dp).coerceAtLeast(60.dp), across, settings.gapDown())
    } else {
        val card = LocalCardWidth.current
        CardGridLayout(((width + across) / (card + across)).toInt().coerceAtLeast(1), card, across, settings.gapDown())
    }
}

/**
 * A page of cards as rows of the page's list, so only the rows on screen are built, drawn and
 * hold their covers, however long the page is.
 */
fun <T> LazyListScope.cardGrid(
    layout: CardGridLayout,
    cards: List<T>,
    name: String = "cards",
    key: (T) -> Any,
    card: @Composable (T) -> Unit,
) {
    val columns = layout.columns
    val rows = (cards.size + columns - 1) / columns
    items(rows, key = { "$name-$it" }, contentType = { "cards" }) { row ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter - 10.dp).padding(bottom = if (row < rows - 1) layout.down else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(layout.across),
        ) {
            CompositionLocalProvider(LocalCardWidth provides layout.cardWidth) {
                for (index in row * columns until minOf(cards.size, (row + 1) * columns)) {
                    val item = cards[index]
                    key(key(item)) { card(item) }
                }
            }
        }
    }
}

/**
 * A short run of cards laid out at once, as the phone's grids lay them out: as many as fit at
 * the chosen size, or the chosen number of columns sharing the width, with the chosen gap
 * between covers. Whole pages of cards use [cardGrid].
 */
@Composable
fun CardGrid(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val settings by AppSettings.state.collectAsState()
    val across = settings.gapAcross()
    val down = settings.gapDown()
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = Gutter - 10.dp)) {
        val columns = settings.gridColumns
        // Half a dp under the exact share, so rounding never pushes the last card onto a new row.
        val width = if (columns > 0) ((maxWidth - across * (columns - 1)) / columns - 0.5.dp).coerceAtLeast(60.dp) else LocalCardWidth.current
        CompositionLocalProvider(LocalCardWidth provides width) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(across),
                verticalArrangement = Arrangement.spacedBy(down),
            ) {
                content()
            }
        }
    }
}
