/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drag to put rows of a lazy list in your own order, as Spotify's Your Library and playlists do:
 * press a row and move it, the rows it passes make way, and the order is kept when it's let go.
 * Holding it near the top or bottom scrolls the list.
 *
 * The gesture sits on the list itself (see [reorderGestures]), so a row that scrolls out of view
 * doesn't drop it. Rows are told apart by their item keys and must all be one height; only the
 * keys [bind] hands over take part, so headers stay put.
 */
@Stable
class ListReorder internal constructor(
    private val list: LazyListState,
    private val scope: CoroutineScope,
    private val edgeTop: Float,
    private val edgeBottom: Float,
) {
    /** The rows' order while one is held, and just after it's let go, until the list shows that order. */
    var order by mutableStateOf<List<Any>?>(null)
        private set

    /** The key of the row being moved. */
    var held by mutableStateOf<Any?>(null)
        private set

    val active: Boolean get() = held != null

    private var keys: () -> List<Any> = { emptyList() }
    private var allowed: (Any, List<Any>) -> IntRange? = { _, _ -> null }
    private var drop: (List<Any>) -> Unit = {}

    private var start: List<Any> = emptyList()
    private var range = 0..0
    private var rowSize = 1
    private var grabTop = 0
    private var moved by mutableFloatStateOf(0f)
    private var scroller: Job? = null
    private var release: Job? = null
    /** Frames since the order last changed: the list's layout shows a change a frame later. */
    private var frames = 0
    /** Where the list stood before a move took its first row elsewhere; put back on the next frame. */
    private var anchor: Pair<Int, Int>? = null

    /**
     * What the list shows now, which rows may move and where to ([allowed] gives the places a row
     * may take, or null when it can't move), and what to do with the new order.
     */
    fun bind(keys: () -> List<Any>, allowed: (key: Any, keys: List<Any>) -> IntRange?, onDrop: (List<Any>) -> Unit) {
        this.keys = keys
        this.allowed = allowed
        drop = onDrop
    }

    /** How far the held row is drawn from where the list puts it, so it stays under the pointer. */
    fun shift(key: Any): Float {
        if (key != held) return 0f
        val item = info(key) ?: return 0f
        return grabTop + moved - item.offset
    }

    /** [current] in the dragged order while there is one. */
    fun <T : Any> arrange(current: List<T>, keyOf: (T) -> Any): List<T> {
        val shown = order ?: return current
        val byKey = current.associateBy(keyOf)
        // A list that changed under the drag (a sync, say) shows as it is.
        if (shown.size != current.size || shown.any { it !in byKey }) return current
        return shown.map { byKey.getValue(it) }
    }

    /** Lets go of a dropped order once the list itself shows it. */
    fun settled(current: List<Any>) {
        if (!active && order != null && order == current) {
            release?.cancel()
            order = null
        }
    }

    private fun info(key: Any): LazyListItemInfo? = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    internal fun begin(y: Float): Boolean {
        val layout = list.layoutInfo
        val at = (y + layout.viewportStartOffset).toInt()
        val item = layout.visibleItemsInfo.firstOrNull { at >= it.offset && at < it.offset + it.size } ?: return false
        val current = keys()
        if (item.key !in current) return false
        val places = allowed(item.key, current) ?: return false
        release?.cancel()
        start = current
        range = places
        rowSize = item.size.coerceAtLeast(1)
        grabTop = item.offset
        moved = 0f
        frames = 2
        anchor = null
        order = current
        held = item.key
        scroller = scope.launch {
            while (isActive) {
                withFrameNanos { }
                frames++
                keepFirstRow()
                edgeScroll()
                place()
                keepFirstRow()
            }
        }
        return true
    }

    internal fun drag(dy: Float) {
        if (!active) return
        moved += dy
        place()
    }

    internal fun end() {
        if (!active) return
        val result = order
        stop()
        if (result != null && result != start) {
            drop(result)
            // Kept on show until the list catches up, so the rows don't flick back for a frame.
            release = scope.launch {
                delay(1_500)
                order = null
            }
        } else {
            order = null
        }
    }

    internal fun cancel() {
        stop()
        order = null
    }

    private fun stop() {
        scroller?.cancel()
        scroller = null
        held = null
        moved = 0f
        anchor = null
    }

    /** Puts the held row in the place of the row under its middle. */
    private fun place() {
        val key = held ?: return
        val current = order ?: return
        // Until the list has laid out the last move, its rows are where they were.
        if (frames < 2) return
        val layout = list.layoutInfo
        val middle = grabTop + moved + rowSize / 2f
        val target = layout.visibleItemsInfo.firstOrNull { it.key != key && middle >= it.offset && middle < it.offset + it.size } ?: return
        val to = current.indexOf(target.key)
        if (to !in range) return
        val first = layout.visibleItemsInfo.firstOrNull()
        val next = current.toMutableList().apply {
            remove(key)
            add(to, key)
        }
        // The list keeps its first row in view by key; keep it by place, so moving rows doesn't scroll it.
        if (first != null && anchor == null && current.indexOf(first.key) != next.indexOf(first.key)) {
            anchor = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        }
        order = next
        frames = 0
    }

    private suspend fun keepFirstRow() {
        val (index, offset) = anchor ?: return
        anchor = null
        scrolling { list.scrollToItem(index, offset) }
    }

    /** Scrolls while the held row is pushed against the top or bottom. */
    private suspend fun edgeScroll() {
        val layout = list.layoutInfo
        val top = grabTop + moved
        val bottom = top + rowSize
        val zone = rowSize * 0.8f
        val upper = layout.viewportStartOffset + edgeTop + zone
        val lower = layout.viewportEndOffset - edgeBottom - zone
        val speed = when {
            moved < 0 && top < upper -> -((upper - top) / zone).coerceAtMost(2f) * 14f
            moved > 0 && bottom > lower -> ((bottom - lower) / zone).coerceAtMost(2f) * 14f
            else -> return
        }
        scrolling { list.scrollBy(speed) }
    }

    /** A scroll of its own, so a wheel turn that takes over the list doesn't stop the drag. */
    private suspend fun scrolling(block: suspend () -> Unit) = coroutineScope { launch { block() } }
}

/** A [ListReorder] for [list]; [edgeTop] and [edgeBottom] are covered by glass, so scrolling starts before them. */
@Composable
fun rememberListReorder(list: LazyListState, edgeTop: Dp = 0.dp, edgeBottom: Dp = 0.dp): ListReorder {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return remember(list, edgeTop, edgeBottom, density) {
        with(density) { ListReorder(list, scope, edgeTop.toPx(), edgeBottom.toPx()) }
    }
}

/** The drag that moves rows; goes on the lazy list [reorder] was made for. */
fun Modifier.reorderGestures(reorder: ListReorder): Modifier = pointerInput(reorder) {
    detectDragGestures(
        onDragStart = { reorder.begin(it.y) },
        onDrag = { change, amount ->
            if (reorder.active) {
                change.consume()
                reorder.drag(amount.y)
            }
        },
        onDragEnd = { reorder.end() },
        onDragCancel = { reorder.cancel() },
    )
}
