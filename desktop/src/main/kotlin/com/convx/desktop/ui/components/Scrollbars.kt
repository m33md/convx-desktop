/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A column that scrolls, with a bar beside it to drag, so it scrolls without a mouse wheel too.
 * [contentPadding] sits inside the scrolling part; leave about 12dp on the right for the bar.
 */
@Composable
fun ScrollbarColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(end = 12.dp),
    state: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Box {
        Column(modifier.verticalScroll(state).padding(contentPadding), content = content)
        ScrollbarOver(this) { VerticalScrollbar(rememberScrollbarAdapter(state), it) }
    }
}

/** The bar to drag for a lazy list; put it in the Box that holds the list. */
@Composable
fun BoxScope.ListScrollbar(state: LazyListState, top: androidx.compose.ui.unit.Dp = 4.dp, bottom: androidx.compose.ui.unit.Dp = 4.dp) {
    ScrollbarOver(this, top, bottom) { VerticalScrollbar(rememberScrollbarAdapter(state), it) }
}

/** Lays the bar along the right of the box, as tall as the box and no taller. */
@Composable
private fun ScrollbarOver(
    scope: BoxScope,
    top: androidx.compose.ui.unit.Dp = 4.dp,
    bottom: androidx.compose.ui.unit.Dp = 4.dp,
    bar: @Composable (Modifier) -> Unit,
) = with(scope) {
    Box(Modifier.matchParentSize().padding(top = top, bottom = bottom, end = 2.dp), contentAlignment = Alignment.CenterEnd) {
        bar(Modifier.fillMaxHeight())
    }
}
