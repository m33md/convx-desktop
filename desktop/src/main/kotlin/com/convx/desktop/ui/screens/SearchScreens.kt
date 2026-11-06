/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import com.convx.desktop.ui.components.LocalPageInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.toSong
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.CardGrid
import com.convx.desktop.ui.components.Chip
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.Gutter
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.ItemActions
import com.convx.desktop.ui.components.ItemCard
import com.convx.desktop.ui.components.ItemShelf
import com.convx.desktop.ui.components.LoadingState
import com.convx.desktop.ui.components.MessageState
import com.convx.desktop.ui.components.PlayButton
import com.convx.desktop.ui.components.SectionHeader
import com.convx.desktop.ui.components.SongRow
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.subtitle
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.music.innertube.SearchFilter
import com.music.innertube.YouTube
import com.music.innertube.models.Artist
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import com.music.innertube.models.SearchSuggestions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Search before you press Enter: recent searches and mood tiles, or live suggestions as you type. */
@Composable
fun SearchScreen() {
    val navigator = LocalNavigator.current
    val query = navigator.searchQuery.trim()
    val library by Library.state.collectAsState()
    val explore = rememberPage("explore") { YouTube.explore().getOrThrow() }
    val moods = rememberPage("moods") { YouTube.moodAndGenres().getOrThrow() }
    var suggestions by remember { mutableStateOf<SearchSuggestions?>(null) }

    LaunchedEffect(query) {
        if (query.isEmpty()) {
            suggestions = null
            return@LaunchedEffect
        }
        delay(220)
        suggestions = withContext(Dispatchers.IO) { YouTube.searchSuggestions(query).getOrNull() }
    }
    LaunchedEffect(Unit) { runCatching { UiState.searchFocus.requestFocus() } }

    fun submit(q: String) {
        navigator.searchQuery = q
        Library.addSearch(q)
        navigator.navigate(Screen.SearchResults(q))
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val insets = LocalPageInsets.current
        val columns = moodColumns(maxWidth - insets.start - insets.end)
        PageList {
            if (query.isNotEmpty()) {
                val s = suggestions
                if (s == null) {
                    item { LoadingState() }
                } else {
                    items(s.queries, key = { "q:$it" }) { text ->
                        SuggestionRow(text, onClick = { submit(text) }, onFill = { navigator.searchQuery = text })
                    }
                    if (s.recommendedItems.isNotEmpty()) {
                        item { Spacer(Modifier.height(12.dp)) }
                        items(s.recommendedItems, key = { "r:${it.id}" }) { item -> ResultRow(item) }
                    }
                }
                return@PageList
            }

            if (library.searchHistory.isNotEmpty()) {
                item(key = "recent_header") {
                    SectionHeader(
                        "Recent searches",
                        Modifier.padding(horizontal = Gutter),
                        trailing = { com.convx.desktop.ui.components.PillButton("Clear", { Library.clearSearches() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)) },
                    )
                }
                item(key = "recent") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Gutter, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(library.searchHistory.take(20), key = { it }) { text ->
                            Chip(text, false, { submit(text) })
                        }
                    }
                }
            }

            val newReleases = explore.value?.newReleaseAlbums.orEmpty()
            if (newReleases.isNotEmpty()) {
                item(key = "new") {
                    ItemShelf("New Releases", newReleases)
                    Spacer(Modifier.height(16.dp))
                }
            }
            item(key = "browse_header") {
                Text("Browse all", style = ConvxType.Title2, modifier = Modifier.padding(horizontal = Gutter).padding(top = 8.dp, bottom = 14.dp))
            }
            val groups = moods.value
            when {
                // One grid of every mood and genre, as the phone's search lays them out.
                groups != null -> moodTileRows(
                    "browse",
                    groups.flatMap { it.items }.distinctBy { it.endpoint.browseId + "|" + it.endpoint.params },
                    columns,
                ) { mood -> navigator.navigate(Screen.Browse(mood.endpoint.browseId, mood.endpoint.params, mood.title)) }
                moods.error != null -> item { MessageState("Couldn't load genres", friendlyError(moods.error), actionText = "Try again", onAction = { moods.retry() }) }
                else -> item { LoadingState() }
            }
        }
    }
}

@Composable
private fun SuggestionRow(text: String, onClick: () -> Unit, onFill: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gutter - 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .hoverable(interaction)
            .pressable(onClick, interaction = interaction, pressScale = 0.995f)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConvxIcon("cosmos_search", tint = ConvxColors.SecondaryLabel, size = 20.dp)
        Spacer(Modifier.width(14.dp))
        Text(text, style = ConvxType.Body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton("arrow_top_left", onFill, size = 30.dp, iconSize = 18.dp, tint = ConvxColors.SecondaryLabel, tooltip = "Fill in search")
    }
}

/** One result as a list row: cover, title, kind • subtitle. */
@Composable
fun ResultRow(item: YTItem) {
    val navigator = LocalNavigator.current
    if (item is SongItem) {
        val song = item.toSong()
        Box(Modifier.padding(horizontal = Gutter - 12.dp)) {
            SongRow(song, { PlayerController.startRadio(song, item.endpoint) }, showAlbum = false)
        }
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gutter - 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .hoverable(interaction)
            .pressable({ ItemActions.open(item, navigator) }, interaction = interaction, pressScale = 0.995f)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            item.thumbnail,
            Modifier.size(42.dp),
            size = 120,
            shape = if (item is ArtistItem) CircleShape else RoundedCornerShape(6.dp),
            placeholderIcon = if (item is ArtistItem) "person" else "music_note",
            iconSize = 18.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = ConvxType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(kindOf(item), item.subtitle()).filter { it.isNotBlank() }.distinct().joinToString(" • "),
                style = ConvxType.Subhead,
                color = ConvxColors.SecondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun kindOf(item: YTItem) = when (item) {
    is SongItem -> if (item.isVideoSong) "Video" else "Song"
    is com.music.innertube.models.AlbumItem -> "Album"
    is com.music.innertube.models.PlaylistItem -> "Playlist"
    is ArtistItem -> "Artist"
}

private enum class ResultFilter(val label: String, val filter: SearchFilter?) {
    All("All", null),
    Songs("Songs", SearchFilter.FILTER_SONG),
    Videos("Videos", SearchFilter.FILTER_VIDEO),
    Albums("Albums", SearchFilter.FILTER_ALBUM),
    Artists("Artists", SearchFilter.FILTER_ARTIST),
    Playlists("Playlists", SearchFilter.FILTER_FEATURED_PLAYLIST),
    Community("Community playlists", SearchFilter.FILTER_COMMUNITY_PLAYLIST),
}

@Composable
fun SearchResultsScreen(query: String) {
    val settings by AppSettings.state.collectAsState()
    var filter by rememberSaveable(query) { mutableStateOf(ResultFilter.All) }
    val scope = rememberCoroutineScope()

    val summary = rememberPage("search-summary:$query") { YouTube.searchSummary(query).getOrThrow() }
    val filtered = if (filter != ResultFilter.All) {
        val f = filter
        rememberPage("search:${f.name}:$query") { YouTube.search(query, f.filter!!).getOrThrow() }
    } else null
    var loadingMore by remember(filter) { mutableStateOf(false) }

    PageList(
        onNearEnd = {
            val page = filtered?.value ?: return@PageList
            val continuation = page.continuation ?: return@PageList
            if (loadingMore) return@PageList
            loadingMore = true
            scope.launch {
                val more = withContext(Dispatchers.IO) { YouTube.searchContinuation(continuation).getOrNull() }
                if (more != null) filtered.update { it.copy(items = it.items + more.items, continuation = more.continuation) }
                loadingMore = false
            }
        },
    ) {
        item(key = "filters") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Gutter, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ResultFilter.entries) { f -> Chip(f.label, filter == f, { filter = f }) }
            }
        }
        if (filtered == null) {
            val page = summary.value
            when {
                page != null -> {
                    val sections = page.summaries
                        .map { it.copy(items = it.items.filtered(settings)) }
                        .filter { it.items.isNotEmpty() }
                    if (sections.isEmpty()) item { MessageState("No results for \"$query\"", "Check the spelling, or try fewer words.", icon = "search") }
                    // Spotify's opening: the best match as a big card, songs beside it. The songs
                    // come from the rest of the top section and then the first all-songs section.
                    val top = sections.firstOrNull()
                    val songSection = sections.drop(1).firstOrNull { section -> section.items.all { it is SongItem } }
                    if (top != null) {
                        val beside = (top.items.drop(1) + songSection?.items.orEmpty())
                            .filterIsInstance<SongItem>()
                            .distinctBy { it.id }
                            .take(4)
                            .map { it.withRealArtists(top.items.first()) }
                        item(key = "top:${top.title}") { TopResult(top.title, top.items.first(), beside) }
                        val rest = top.items.drop(1).filter { it !is SongItem }
                        if (rest.isNotEmpty()) {
                            item(key = "top-rest") {
                                ItemShelf("More like this", rest)
                                Spacer(Modifier.height(20.dp))
                            }
                        }
                    }
                    sections.drop(1).filter { it !== songSection }.forEachIndexed { index, section ->
                        val songs = section.items.filterIsInstance<SongItem>()
                        if (songs.size == section.items.size) {
                            item(key = "h:$index:${section.title}") {
                                SectionHeader(section.title, Modifier.padding(horizontal = Gutter, vertical = 8.dp))
                            }
                            itemsIndexed(songs, key = { i, s -> "s:$index:$i:${s.id}" }) { _, s ->
                                val song = s.toSong()
                                Box(Modifier.padding(horizontal = Gutter - 12.dp)) {
                                    SongRow(song, { PlayerController.startRadio(song, s.endpoint) })
                                }
                            }
                            item(key = "sp:$index") { Spacer(Modifier.height(20.dp)) }
                        } else {
                            item(key = "sh:$index:${section.title}") {
                                ItemShelf(section.title, section.items)
                                Spacer(Modifier.height(20.dp))
                            }
                        }
                    }
                }
                summary.error != null -> item { MessageState("Search failed", friendlyError(summary.error), actionText = "Try again", onAction = { summary.retry() }) }
                else -> item { LoadingState() }
            }
        } else {
            val page = filtered.value
            when {
                page != null -> {
                    val items = page.items.filtered(settings).distinctBy { it.id }
                    if (items.isEmpty()) item { MessageState("No ${filter.label.lowercase()} for \"$query\"", icon = "search") }
                    val songs = items.filterIsInstance<SongItem>()
                    if (songs.size == items.size) {
                        itemsIndexed(songs, key = { i, s -> "f:$i:${s.id}" }) { i, s ->
                            val song = s.toSong()
                            Box(Modifier.padding(horizontal = Gutter - 12.dp)) {
                                SongRow(
                                    song,
                                    {
                                        if (filter == ResultFilter.Songs) PlayerController.playSongs(songs.map { it.toSong() }, i, query)
                                        else PlayerController.startRadio(song, s.endpoint)
                                    },
                                    index = i + 1,
                                )
                            }
                        }
                    } else {
                        item(key = "grid") {
                            CardGrid { items.forEach { ItemCard(it) } }
                        }
                    }
                    if (loadingMore) item { LoadingState() }
                }
                filtered.error != null -> item { MessageState("Search failed", friendlyError(filtered.error), actionText = "Try again", onAction = { filtered.retry() }) }
                else -> item { LoadingState() }
            }
        }
    }
}

/**
 * The top card's songs come back with their length parsed as the artist ("Acquainted, 5:49").
 * Drop those names, and when nothing is left, credit the artist the card is about.
 */
private fun SongItem.withRealArtists(top: YTItem): SongItem {
    val real = artists.filterNot { DurationText.matches(it.name.trim()) }
    if (real.size == artists.size) return this
    val card = (top as? ArtistItem)?.let { listOf(Artist(it.title, it.id)) }.orEmpty()
    return copy(artists = real.ifEmpty { card })
}

private val DurationText = Regex("""\d{1,2}(:\d{2}){1,2}""")

/** Spotify's "Top result" card beside the first few songs. */
@Composable
private fun TopResult(title: String, item: YTItem, songs: List<SongItem>) {
    val navigator = LocalNavigator.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp)) {
        val wide = maxWidth > 760.dp
        val card = @Composable { modifier: Modifier ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Column(modifier) {
                Text(title, style = ConvxType.Title2)
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = if (hovered) 0.12f else 0.07f))
                        .hoverable(interaction)
                        .pressable({ ItemActions.open(item, navigator) }, interaction = interaction, pressScale = 0.99f)
                        .padding(20.dp),
                ) {
                    Column {
                        Artwork(
                            item.thumbnail,
                            Modifier.size(96.dp).shadow(12.dp, if (item is ArtistItem) CircleShape else RoundedCornerShape(8.dp)),
                            size = 300,
                            shape = if (item is ArtistItem) CircleShape else RoundedCornerShape(8.dp),
                            placeholderIcon = if (item is ArtistItem) "person" else "music_note",
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(item.title, style = ConvxType.Title1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            listOf(kindOf(item), item.subtitle()).filter { it.isNotBlank() }.distinct().joinToString(" • "),
                            style = ConvxType.Subhead,
                            color = ConvxColors.SecondaryLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (hovered) {
                        PlayButton(false, { ItemActions.play(item) }, Modifier.align(Alignment.BottomEnd), size = 52.dp)
                    }
                }
            }
        }
        if (wide && songs.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                card(Modifier.weight(0.42f))
                Column(Modifier.weight(0.58f)) {
                    Text("Songs", style = ConvxType.Title2)
                    Spacer(Modifier.height(10.dp))
                    songs.forEach { item ->
                        val song = item.toSong()
                        SongRow(song, { PlayerController.startRadio(song, item.endpoint) }, showAlbum = false)
                    }
                }
            }
        } else {
            card(Modifier.fillMaxWidth())
        }
    }
}
