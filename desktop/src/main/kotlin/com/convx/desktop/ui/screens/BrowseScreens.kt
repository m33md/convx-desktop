/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.pluralize
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.toSong
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.CardGrid
import com.convx.desktop.ui.components.LocalCardWidth
import com.convx.desktop.ui.components.Gutter
import com.convx.desktop.ui.components.SectionHeader
import com.convx.desktop.ui.components.LocalPageInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import com.convx.desktop.ui.components.ItemCard
import com.convx.desktop.ui.components.ItemShelf
import com.convx.desktop.ui.components.LoadingState
import com.convx.desktop.ui.components.MessageState
import com.convx.desktop.ui.components.PlayButton
import com.convx.desktop.ui.components.SongShelf
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.GradientTile
import com.convx.desktop.ui.shell.LibraryTab
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.components.MosaicArt
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxShapes
import com.convx.desktop.ui.theme.ConvxType
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem

/** A titled run of shelves: what charts, moods and "see all" links open. */
private data class BrowsePage(val title: String?, val shelves: List<Pair<String?, List<YTItem>>>)

@Composable
fun BrowseScreen(browseId: String, params: String?, title: String?) {
    // Some "see all" links are really an album, playlist or artist.
    when {
        browseId.startsWith("VL") -> return PlaylistScreen(browseId)
        browseId.startsWith("MPREb") -> return AlbumScreen(browseId)
        browseId.startsWith("UC") && params == null -> return ArtistScreen(browseId)
    }
    val settings by AppSettings.state.collectAsState()
    val navigator = LocalNavigator.current

    if (browseId == "FEmusic_moods_and_genres") {
        val moods = rememberPage("moods") { YouTube.moodAndGenres().getOrThrow() }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val insets = LocalPageInsets.current
            val columns = moodColumns(maxWidth - insets.start - insets.end)
            PageList {
                item { Text(title ?: "Moods & Genres", style = ConvxType.LargeTitle, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp)) }
                val groups = moods.value
                when {
                    groups != null -> groups.forEach { group ->
                        item(key = group.title) {
                            Text(group.title, style = ConvxType.Title3, modifier = Modifier.padding(horizontal = Gutter).padding(top = 8.dp, bottom = 12.dp))
                        }
                        moodTileRows("g:${group.title}", group.items, columns) { mood ->
                            navigator.navigate(Screen.Browse(mood.endpoint.browseId, mood.endpoint.params, mood.title))
                        }
                    }
                    moods.error != null -> item { MessageState("Couldn't load genres", friendlyError(moods.error), actionText = "Try again", onAction = { moods.retry() }) }
                    else -> item { LoadingState() }
                }
            }
        }
        return
    }

    val page = rememberPage("browse:$browseId:$params") {
        when (browseId) {
            "FEmusic_charts" -> {
                val charts = YouTube.getChartsPage().getOrThrow()
                BrowsePage("Charts", charts.sections.map { it.title to it.items })
            }
            "FEmusic_new_releases_albums" -> BrowsePage("New Releases", listOf(null to YouTube.newReleaseAlbums().getOrThrow()))
            else -> {
                val result = YouTube.browse(browseId, params).getOrThrow()
                BrowsePage(result.title, result.items.map { it.title to it.items })
            }
        }
    }
    PageList {
        item(key = "title") {
            Text(page.value?.title ?: title ?: "", style = ConvxType.LargeTitle, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp))
        }
        val data = page.value
        when {
            data != null -> {
                val shelves = data.shelves
                    .map { (t, items) -> t to items.filtered(settings).distinctBy { it.id } }
                    .filter { it.second.isNotEmpty() }
                if (shelves.isEmpty()) item { MessageState("Nothing here right now", icon = "music_note") }
                if (shelves.size == 1) {
                    // A single list reads better as a grid than a lone sideways shelf.
                    val (t, items) = shelves.first()
                    if (t != null) item { Text(t, style = ConvxType.Title2, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp)) }
                    item(key = "grid") {
                        CardGrid { items.forEach { ItemCard(it) } }
                    }
                } else {
                    shelves.forEachIndexed { index, (t, items) ->
                        item(key = "b-$index-$t") {
                            if (items.isSongShelf()) {
                                val songs = items.filterIsInstance<SongItem>().map { it.toSong() }
                                SongShelf(t ?: "Songs", songs) { i -> PlayerController.playSongs(songs, i, t ?: data.title) }
                            } else {
                                ItemShelf(t ?: "", items)
                            }
                            Spacer(Modifier.height(20.dp))
                        }
                    }
                }
            }
            page.error != null -> item { MessageState("Couldn't load this page", friendlyError(page.error), actionText = "Try again", onAction = { page.retry() }) }
            else -> item { LoadingState() }
        }
    }
}

/** Your Library as a full page of cards, like the phone's library grid. */
@Composable
fun LibraryScreen() {
    val navigator = LocalNavigator.current
    val library by Library.state.collectAsState()
    val settings by com.convx.desktop.data.AppSettings.state.collectAsState()
    val downloaded by com.convx.desktop.player.Downloads.done.collectAsState()
    val tab = UiState.libraryTab
    PageList {
        // The filters live in Library's strip under the top bar.
        item(key = "title") {
            Text(tab.title, style = ConvxType.LargeTitle, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp))
        }
        item(key = "grid") {
            CardGrid(Modifier.padding(vertical = 8.dp)) {
                if (tab == LibraryTab.All || tab == LibraryTab.Playlists) {
                    // The phone's auto playlists first, each as its Settings switch says.
                    if (settings.showLikedPlaylist) {
                        val liked = library.liked.map { it.song }
                        SavedCard(
                            "Liked Songs",
                            pluralize(liked.size, "song"),
                            art = { size -> com.convx.desktop.ui.components.LikedSongsArt(size, RoundedCornerShape(8.dp)) },
                            onClick = { navigator.navigate(Screen.Liked) },
                            onPlay = if (liked.isEmpty()) null else ({ PlayerController.playSongs(liked, 0, "Liked Songs") }),
                        )
                    }
                    if (settings.showDownloadedPlaylist && downloaded.isNotEmpty()) {
                        val songs = downloaded.values.sortedByDescending { it.at }.map { it.song }
                        SavedCard(
                            "Downloaded",
                            pluralize(songs.size, "song"),
                            art = { size -> GradientTile("download", size, com.convx.desktop.ui.shell.DownloadsGradient, RoundedCornerShape(8.dp)) },
                            onClick = { navigator.navigate(Screen.Downloads) },
                            onPlay = { PlayerController.playSongs(songs, 0, "Downloaded") },
                        )
                    }
                    if (settings.showTopPlaylist && library.history.isNotEmpty()) {
                        val title = "My top ${settings.topSize}"
                        SavedCard(
                            title,
                            "Playlist",
                            art = { size -> GradientTile("trending_up", size, TopGradient, RoundedCornerShape(8.dp)) },
                            onClick = { navigator.navigate(Screen.MyTop) },
                            onPlay = { PlayerController.playSongs(myTopSongs(TopFilter.AllTime, settings).map { it.value }, 0, title) },
                        )
                    }
                    if (settings.showCachedPlaylist) {
                        val cached = remember(library, downloaded) { cachedSongs(library, downloaded.values) }
                        if (cached.isNotEmpty()) {
                            SavedCard(
                                "Cached",
                                pluralize(cached.size, "song"),
                                art = { size -> GradientTile("cached", size, CachedGradient, RoundedCornerShape(8.dp)) },
                                onClick = { navigator.navigate(Screen.Cached) },
                                onPlay = { PlayerController.playSongs(cached, 0, "Cached") },
                            )
                        }
                    }
                    if (settings.showUploadedPlaylist && library.uploaded.isNotEmpty()) {
                        SavedCard(
                            "Uploaded",
                            pluralize(library.uploaded.size, "song"),
                            art = { size -> GradientTile("cloud", size, UploadedGradient, RoundedCornerShape(8.dp)) },
                            onClick = { navigator.navigate(Screen.Uploaded) },
                            onPlay = { PlayerController.playSongs(library.uploaded, 0, "Uploaded") },
                        )
                    }
                    // The phone's AI DJ Mix sits with the auto playlists, though it opens a builder, not songs.
                    SavedCard(
                        "AI DJ Mix",
                        "Build a set from a vibe or a song",
                        art = { size -> GradientTile("sparks", size, AiDjGradient, RoundedCornerShape(8.dp)) },
                        onClick = { navigator.navigate(Screen.AiDj()) },
                    )
                    library.localPlaylists.forEach { p ->
                        SavedCard(
                            p.name,
                            "Playlist • ${pluralize(p.songs.size, "song")}",
                            art = { size -> com.convx.desktop.ui.components.PlaylistCoverArt(p, size, RoundedCornerShape(8.dp)) },
                            onClick = { navigator.navigate(Screen.LocalPlaylist(p.id)) },
                            onPlay = if (p.songs.isEmpty()) null else ({ PlayerController.playSongs(p.songs, 0, p.name) }),
                        )
                    }
                    library.playlists.forEach { p ->
                        SavedCard(
                            p.title,
                            listOfNotNull("Playlist", p.author).joinToString(" • "),
                            thumbnail = p.thumbnail,
                            onClick = { navigator.navigate(Screen.Playlist(p.id)) },
                        )
                    }
                }
                if (tab == LibraryTab.All || tab == LibraryTab.Albums) {
                    library.albums.forEach { a ->
                        SavedCard(
                            a.title,
                            a.artists.joinToString(", ") { it.name }.ifBlank { "Album" },
                            thumbnail = a.thumbnail,
                            onClick = { navigator.navigate(Screen.Album(a.browseId)) },
                        )
                    }
                }
                if (tab == LibraryTab.All || tab == LibraryTab.Artists) {
                    library.artists.forEach { a ->
                        SavedCard(a.name, "Artist", thumbnail = a.thumbnail, round = true, onClick = { navigator.navigate(Screen.Artist(a.id)) })
                    }
                }
            }
        }
        val empty = when (tab) {
            LibraryTab.All -> false
            LibraryTab.Playlists -> false
            LibraryTab.Albums -> library.albums.isEmpty()
            LibraryTab.Artists -> library.artists.isEmpty()
        }
        if (empty) {
            item {
                MessageState(
                    if (tab == LibraryTab.Albums) "No saved albums yet" else "You don't follow any artists yet",
                    if (tab == LibraryTab.Albums) "Save an album with the + button on its page." else "Follow artists from their pages.",
                    icon = if (tab == LibraryTab.Albums) "album" else "artist",
                )
            }
        }
    }
}

@Composable
internal fun SavedCard(
    title: String,
    subtitle: String,
    thumbnail: String? = null,
    round: Boolean = false,
    art: (@Composable (Dp) -> Unit)? = null,
    onClick: () -> Unit,
    onPlay: (() -> Unit)? = null,
    width: Dp = LocalCardWidth.current,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape: Shape = if (round) CircleShape else ConvxShapes.Artwork
    Box(
        Modifier
            .width(width)
            .clip(ConvxShapes.Card)
            .background(if (hovered) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .hoverable(interaction)
            .pressable(onClick, interaction = interaction, pressScale = 0.98f),
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).shadow(if (hovered) 14.dp else 6.dp, shape)) {
                if (art != null) {
                    Box(Modifier.fillMaxSize().clip(shape), contentAlignment = Alignment.Center) { art(width - 20.dp) }
                } else {
                    Artwork(thumbnail, Modifier.fillMaxSize(), size = 400, shape = shape, placeholderIcon = if (round) "person" else "music_note", iconSize = 40.dp)
                }
                if (hovered && onPlay != null) {
                    PlayButton(false, onPlay, Modifier.align(Alignment.BottomEnd).padding(8.dp).shadow(10.dp, CircleShape), size = 46.dp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                style = ConvxType.Body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (round) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                subtitle,
                style = ConvxType.Subhead,
                color = ConvxColors.SecondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (round) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The phone's Explore tab: new releases, the charts and the mood and genre tiles. Reads
 * the same cached pages as Home, so switching between them doesn't refetch.
 */
@Composable
fun ExploreScreen() {
    val navigator = LocalNavigator.current
    val settings by AppSettings.state.collectAsState()
    val explore = rememberPage("explore") { YouTube.explore().getOrThrow() }
    val charts = rememberPage("charts") { YouTube.getChartsPage().getOrThrow() }
    val releases = explore.value?.newReleaseAlbums.orEmpty().filtered(settings)
    val chartShelves = charts.value?.sections.orEmpty()
        .map { it to it.items.filtered(settings).distinctBy { item -> item.id } }
        .filter { it.second.isNotEmpty() }
    val moods = explore.value?.moodAndGenres.orEmpty().distinctBy { it.title }
    val listState = rememberLazyListState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val insets = LocalPageInsets.current
        val columns = moodColumns(maxWidth - insets.start - insets.end)
        PageList(state = listState) {
            item(key = "title") {
                Text("Explore", style = ConvxType.LargeTitle, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp))
            }
            if (releases.isNotEmpty()) {
                item(key = "new_releases") {
                    ItemShelf("New Releases", releases, onTitleClick = { navigator.navigate(Screen.Browse("FEmusic_new_releases_albums", null, "New Releases")) })
                    Spacer(Modifier.height(20.dp))
                }
            }
            chartShelves.forEachIndexed { index, (section, items) ->
                item(key = "chart-$index-${section.title}") {
                    if (items.isSongShelf()) {
                        val songs = items.filterIsInstance<SongItem>().map { it.toSong() }
                        SongShelf(section.title, songs) { i -> PlayerController.playSongs(songs, i, section.title) }
                    } else {
                        ItemShelf(section.title, items, onTitleClick = { navigator.navigate(Screen.Browse("FEmusic_charts", null, "Charts")) })
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
            if (moods.isNotEmpty()) {
                item(key = "moods") {
                    SectionHeader("Moods & Genres", Modifier.padding(horizontal = Gutter).padding(bottom = 10.dp))
                }
                moodTileRows("moods", moods, columns) { mood -> navigator.navigate(Screen.Browse(mood.endpoint.browseId, mood.endpoint.params, mood.title)) }
            }
            when {
                explore.value != null || charts.value != null -> Unit
                explore.error != null -> item {
                    MessageState(
                        "Couldn't reach YouTube Music",
                        friendlyError(explore.error),
                        icon = "offline",
                        actionText = "Try again",
                        onAction = {
                            explore.retry()
                            charts.retry()
                        },
                    )
                }
                else -> item { LoadingState() }
            }
        }
    }
}
