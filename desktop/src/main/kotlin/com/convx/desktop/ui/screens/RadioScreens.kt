/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.Song
import com.convx.desktop.data.toSong
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.Gutter
import com.convx.desktop.ui.components.LoadingState
import com.convx.desktop.ui.components.MessageState
import com.convx.desktop.ui.components.SectionHeader
import com.convx.desktop.ui.components.SongMenuContent
import com.convx.desktop.ui.components.SongRow
import com.convx.desktop.ui.components.onSecondaryClick
import com.convx.desktop.ui.components.openAtProvider
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.glass.GlassCircleButton
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.music.innertube.YouTube
import com.music.innertube.models.WatchEndpoint

/** Cards stay near the phone's width, so a wide window gets more columns, not bigger tiles. */
private val RadioCardMinWidth = 168.dp
private val RadioCardGapX = 12.dp
private val RadioCardGapY = 14.dp

/** The phone's radio seeds: YouTube songs (11-character ids), so local and Spotify-only songs are left out. */
private val RadioSeedId = Regex("[A-Za-z0-9_-]{11}")

/**
 * The phone's RadioViewModel: the last 40 plays, one station per first artist, at most 12, without
 * explicit songs when they're hidden.
 */
internal fun recentStations(history: List<com.convx.desktop.data.PlayEvent>, hideExplicit: Boolean): List<Song> =
    history.asSequence()
        .take(40)
        .map { it.song }
        .filter { RadioSeedId.matches(it.id) && (!hideExplicit || !it.explicit) }
        .distinctBy { it.artists.firstOrNull()?.id ?: it.id }
        .take(12)
        .toList()

/** The phone's Radio tab: a station for each artist you played lately, from the song you played. */
@Composable
fun RadioScreen() {
    val navigator = LocalNavigator.current
    val library by Library.state.collectAsState()
    val settings by AppSettings.state.collectAsState()
    val stations = remember(library.history, settings.hideExplicit) { recentStations(library.history, settings.hideExplicit) }

    PageList(state = rememberLazyListState(), tint = Color.Black) {
        item(key = "title") {
            Text("Radio", style = ConvxType.LargeTitle, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp))
        }
        if (stations.isEmpty()) {
            item(key = "empty") {
                MessageState(
                    title = "No stations yet",
                    message = "Play a few songs and your recent tracks will appear here.",
                    icon = "sf_dot_radiowaves",
                    actionText = "Search",
                    onAction = { navigator.navigate(Screen.Search) },
                )
            }
        } else {
            item(key = "recent") {
                SectionHeader("Recently played", Modifier.padding(start = Gutter, end = Gutter, top = 12.dp, bottom = 14.dp))
            }
            item(key = "stations") {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Gutter)) {
                    // The phone's adaptive grid: as many columns as fit at the minimum, sharing the width.
                    val columns = ((maxWidth + RadioCardGapX) / (RadioCardMinWidth + RadioCardGapX)).toInt().coerceAtLeast(1)
                    val width = (maxWidth - RadioCardGapX * (columns - 1)) / columns - 0.5.dp
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(RadioCardGapX),
                        verticalArrangement = Arrangement.spacedBy(RadioCardGapY),
                    ) {
                        stations.forEach { song ->
                            RadioStationCard(
                                song,
                                Modifier.width(width),
                                onClick = { navigator.navigate(Screen.RadioStation(song)) },
                                onPlay = { PlayerController.startRadio(song) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioStationCard(song: Song, modifier: Modifier, onClick: () -> Unit, onPlay: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val artist = song.artists.joinToString { it.name }.ifBlank { song.title }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val menu = rememberMenuAnchor()
    Box(
        modifier
            .aspectRatio(1f)
            .hoverable(interaction)
            .onSecondaryClick(menu.openAtProvider())
            .pressable(onClick, interaction = interaction, pressScale = 0.98f)
            .clip(shape)
            .background(ConvxColors.Card),
    ) {
        Artwork(song.thumbnail, Modifier.fillMaxSize(), size = 544, shape = shape)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = if (hovered) 0.16f else 0.08f),
                    0.45f to Color.Black.copy(alpha = 0.28f),
                    1f to Color.Black.copy(alpha = 0.82f),
                ),
            ),
        )
        Box(
            Modifier.align(Alignment.TopStart).padding(10.dp).size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            ConvxIcon("sf_dot_radiowaves", tint = Color.White, size = 17.dp)
        }
        // The phone's flat glass play button, which plays the station without opening it.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(38.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (hovered) 0.26f else 0.18f))
                .border(0.5.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                .pressable(onPlay),
            contentAlignment = Alignment.Center,
        ) {
            ConvxIcon("play_applemusic", tint = Color.White, size = 16.dp, contentDescription = "Play station")
        }
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(
                "Radio for $artist",
                style = ConvxType.Headline.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.title,
                style = ConvxType.Subhead.copy(fontSize = 12.sp),
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AppMenu(menu) { SongMenuContent(song, { menu.expanded = false }) }
    }
}

/**
 * The phone's RadioStationViewModel: what YouTube Music plays after the seed, or its related songs
 * when that comes back empty, at most 24.
 */
internal suspend fun stationTracks(seedId: String): List<Song> {
    val next = YouTube.next(WatchEndpoint(videoId = seedId)).getOrThrow()
    var tracks = next.items.filterNot { it.id == seedId }
    val related = next.relatedEndpoint
    if (tracks.isEmpty() && related != null) tracks = YouTube.related(related).getOrThrow().songs
    return tracks.map { it.toSong() }.distinctBy { it.id }
}

/** One station: the song it's built on, and what plays after it. Each track starts its own radio. */
@Composable
fun RadioStationScreen(seed: Song) {
    val settings by AppSettings.state.collectAsState()
    val page = rememberPage("radio:${seed.id}") { stationTracks(seed.id) }
    val tracks = remember(page.value, settings.hideExplicit) {
        page.value?.filter { !settings.hideExplicit || !it.explicit }?.take(24)
    }
    val title = seed.title.ifBlank { "Radio" }

    PageList(state = rememberLazyListState(), tint = Color.Black) {
        item(key = "title") {
            Text(
                "Radio for $title",
                style = ConvxType.LargeTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
            )
        }
        item(key = "header") {
            RadioStationHeader(seed, title) { PlayerController.startRadio(seed) }
        }
        item(key = "up-next") {
            SectionHeader("Up next", Modifier.padding(start = Gutter, end = Gutter, top = 20.dp, bottom = 8.dp))
        }
        when {
            tracks == null && page.error != null -> item(key = "error") {
                MessageState(
                    title = "Couldn't load this station",
                    message = "Could not load this station. Check your connection and try again.",
                    actionText = "Retry",
                    onAction = { page.retry() },
                )
            }
            tracks == null -> item(key = "loading") { LoadingState(Modifier.height(160.dp)) }
            tracks.isEmpty() -> item(key = "none") {
                Text(
                    "Could not load this station. Check your connection and try again.",
                    style = ConvxType.Body,
                    color = ConvxColors.SecondaryLabel,
                    modifier = Modifier.padding(horizontal = Gutter, vertical = 16.dp),
                )
            }
            else -> itemsIndexed(tracks, key = { _, song -> "track:${song.id}" }) { index, song ->
                SongRow(
                    song,
                    onPlay = { PlayerController.startRadio(song) },
                    modifier = Modifier.padding(horizontal = Gutter - 12.dp),
                    index = index + 1,
                )
            }
        }
    }
}

@Composable
private fun RadioStationHeader(seed: Song, title: String, onPlay: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .padding(horizontal = Gutter, vertical = 10.dp)
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .hoverable(interaction)
            .pressable(onPlay, interaction = interaction, pressScale = 0.99f)
            .clip(shape)
            .background(if (hovered) ConvxColors.CardSecondary else ConvxColors.Card)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Artwork(seed.thumbnail, Modifier.size(88.dp), size = 240, shape = RoundedCornerShape(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Song radio", fontSize = 13.sp, color = ConvxColors.SecondaryLabel)
            Text(title, style = ConvxType.Headline, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(seed.artistText, style = ConvxType.Body, color = ConvxColors.SecondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GlassCircleButton(onClick = onPlay, size = 44.dp) {
            ConvxIcon("play_applemusic", tint = Color.White, size = 19.dp)
        }
        Spacer(Modifier.width(2.dp))
    }
}
