/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.core.AppScope
import com.convx.desktop.ui.components.Gutter
import com.convx.desktop.ui.components.LocalPageInsets
import com.convx.desktop.ui.components.SectionHeader
import com.convx.desktop.ui.components.artworkUrl
import com.convx.desktop.ui.components.bleedStart
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.rememberImage
import com.convx.desktop.ui.shapes.ContinuousRoundedRectangle
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.theme.ConvxType
import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.pages.MoodAndGenres
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** The phone's tile: 181 by 105. */
private const val TileAspect = 181f / 105f

/** The narrowest a tile gets before a grid takes a column away. */
val MoodTileMinWidth: Dp = 190.dp
private val TileGap = 10.dp

private val MoodAndGenres.Item.key: String get() = endpoint.browseId + "|" + endpoint.params

/**
 * Pictures for the mood and genre tiles. YouTube Music gives each mood a colour but no picture,
 * so a tile borrows the lead artwork of its own page, as the phone's search does: fetched when
 * the tile first shows, four at a time, and kept for the session.
 */
object MoodArt {
    private val _art = MutableStateFlow<Map<String, String?>>(emptyMap())
    val art: StateFlow<Map<String, String?>> = _art.asStateFlow()

    private val gate = Semaphore(4)
    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    fun load(item: MoodAndGenres.Item) {
        val key = item.key
        if (_art.value.containsKey(key) || !pending.add(key)) return
        AppScope.launch(Dispatchers.IO) {
            try {
                gate.withPermit {
                    val url = YouTube.browse(item.endpoint.browseId, item.endpoint.params).getOrNull()
                        ?.items?.asSequence()?.flatMap { it.items.asSequence() }
                        // People read best in the duotone, then covers.
                        ?.sortedBy { if (it is ArtistItem) 0 else if (it is PlaylistItem || it is AlbumItem) 1 else 2 }
                        ?.firstOrNull()?.thumbnail
                    _art.update { it + (key to url) }
                }
            } finally {
                pending.remove(key)
            }
        }
    }
}

private val Desaturate = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * A mood or genre as the phone's search shows it, after Apple Music: the mood's colour, a
 * grey photo multiplied into it over the right three quarters, so the colour stays solid down
 * the left, and the name in white at the bottom left.
 */
@Composable
fun MoodTile(mood: MoodAndGenres.Item, modifier: Modifier = Modifier, onClick: () -> Unit) {
    LaunchedEffect(mood.key) { MoodArt.load(mood) }
    val art by MoodArt.art.collectAsState()
    val image by rememberImage(artworkUrl(art[mood.key], 360))
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val ground = Color(mood.stripeColor).copy(alpha = 1f)
    val shape = ContinuousRoundedRectangle(15.dp)
    Box(
        modifier
            .aspectRatio(TileAspect)
            .clip(shape)
            .background(ground)
            .border(0.25.dp, Color.Black.copy(alpha = 0.1f), shape)
            .hoverable(interaction)
            .pressable(onClick, interaction = interaction, pressScale = 0.97f),
    ) {
        val bitmap = image
        if (bitmap != null) {
            Canvas(Modifier.fillMaxSize()) {
                // The photo fills the right 72%, cropped from its top centre, multiplied into the colour.
                val left = size.width * 0.28f
                val width = size.width - left
                val scale = maxOf(width / bitmap.width, size.height / bitmap.height)
                val srcWidth = (width / scale).toInt().coerceIn(1, bitmap.width)
                val srcHeight = (size.height / scale).toInt().coerceIn(1, bitmap.height)
                drawImage(
                    image = bitmap,
                    srcOffset = IntOffset((bitmap.width - srcWidth) / 2, 0),
                    srcSize = IntSize(srcWidth, srcHeight),
                    dstOffset = IntOffset(left.toInt(), 0),
                    dstSize = IntSize(width.toInt() + 1, size.height.toInt() + 1),
                    colorFilter = Desaturate,
                    blendMode = BlendMode.Multiply,
                )
                // Lifts the duotone's highlights back toward the tile colour.
                drawRect(ground.copy(alpha = 0.25f), blendMode = BlendMode.Screen)
            }
        }
        // Keeps the name legible where the photo runs dark behind it.
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = if (hovered) 0.4f else 0.28f))),
        )
        Text(
            mood.title,
            style = TextStyle(fontFamily = ConvxType.family, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.23).sp),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}

/** How many tiles fit across [width] of page (gutters included). */
fun moodColumns(width: Dp): Int = (((width - Gutter * 2) + TileGap) / (MoodTileMinWidth + TileGap)).toInt().coerceAtLeast(2)

/**
 * The tiles as a grid down the page, Apple Music's Browse Categories: one list item per row, so
 * only the rows in view fetch their pictures.
 */
fun LazyListScope.moodTileRows(keyPrefix: String, moods: List<MoodAndGenres.Item>, columns: Int, onClick: (MoodAndGenres.Item) -> Unit) {
    moods.chunked(columns).forEachIndexed { index, row ->
        item(key = "$keyPrefix-row-$index-${row.first().key}") {
            Row(
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, bottom = TileGap),
                horizontalArrangement = Arrangement.spacedBy(TileGap),
            ) {
                row.forEach { mood -> MoodTile(mood, Modifier.weight(1f)) { onClick(mood) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Mood and genre tiles in two rows that scroll sideways, for a shelf among others (Home). */
@Composable
fun MoodGrid(title: String, moods: List<MoodAndGenres.Item>, onTitleClick: (() -> Unit)? = null) {
    val navigator = LocalNavigator.current
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        SectionHeader(title, Modifier.padding(horizontal = Gutter), onAction = onTitleClick)
        Spacer(Modifier.height(10.dp))
        val bleed = LocalPageInsets.current.start
        val tileWidth = 210.dp
        LazyHorizontalGrid(
            rows = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = bleed + Gutter, end = Gutter),
            horizontalArrangement = Arrangement.spacedBy(TileGap),
            verticalArrangement = Arrangement.spacedBy(TileGap),
            modifier = Modifier.bleedStart(bleed).fillMaxWidth().height(tileWidth / TileAspect * 2 + TileGap),
        ) {
            items(moods, key = { it.key }) { mood ->
                MoodTile(mood, Modifier.width(tileWidth)) { navigator.navigate(Screen.Browse(mood.endpoint.browseId, mood.endpoint.params, mood.title)) }
            }
        }
    }
}
