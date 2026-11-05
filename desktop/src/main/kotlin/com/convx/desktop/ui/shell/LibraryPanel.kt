/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.shell

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.convx.desktop.ui.glass.glassControl
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.core.AppScope
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.LibraryData
import com.convx.desktop.data.Song
import com.convx.desktop.data.toSong
import com.convx.desktop.integrations.spotify.SpotifyAccount
import com.convx.desktop.integrations.spotify.SpotifyApi
import com.convx.desktop.integrations.spotify.SpotifyMatcher
import com.convx.desktop.integrations.spotify.SpotifyPlaylist
import com.convx.desktop.ui.screens.SpotifyGreen
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.GradientTile
import com.convx.desktop.ui.components.MenuDivider
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.components.MosaicArt
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.Tooltip
import com.convx.desktop.ui.components.onSecondaryClick
import com.convx.desktop.ui.components.openAtProvider
import com.convx.desktop.ui.components.rememberListReorder
import com.convx.desktop.ui.components.reorderGestures
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.glass.GlassConfig
import com.convx.desktop.ui.glass.GlassShapes
import com.convx.desktop.ui.glass.GlassTokens
import com.convx.desktop.ui.glass.liquidGlass
import com.convx.desktop.ui.glass.rememberInteractiveHighlight
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent
import com.music.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Spotify's "Your Library" in the phone's glass: the panel on the left holds what you've kept
 * (playlists, albums, artists) with its filters, search and sort, pins on top, and folds to a
 * rail of covers. The phone's side panel material (black at 75%, its own lighter lens) and
 * corner, at the phone's tablet margins.
 */

val SideBarWidth: Dp = 320.dp
val SideBarCollapsedWidth: Dp = 72.dp

/** Gap between the window edge and the panel; on the phone it is 30dp of a tablet's width. */
val SideBarMargin: Dp = 12.dp

/** Where rows start beside the panel; lists still run under it. */
val SideBarContentInset: Dp = SideBarMargin + SideBarWidth + 8.dp
val SideBarCollapsedContentInset: Dp = SideBarMargin + SideBarCollapsedWidth + 8.dp

/** Local files' tile, in the phone's orange for music folders. */
val LocalFilesGradient = listOf(Color(0xFFFF9F0A), Color(0xFFFF375F))

/** Downloaded songs' tile. */
val DownloadsGradient = listOf(Color(0xFF30D158), Color(0xFF0A84FF))

private val RowHeight = 60.dp
private val ArtSize = 46.dp

private enum class Kind(val label: String) {
    Liked("Playlist"),
    LocalFiles("Playlist"),
    Downloads("Playlist"),
    Top("Playlist"),
    Cached("Playlist"),
    Uploaded("Playlist"),
    LocalPlaylist("Playlist"),
    Playlist("Playlist"),
    Album("Album"),
    Artist("Artist"),
    SpotifyLiked("Spotify"),
    SpotifyPlaylist("Spotify"),
    /** One music file on this PC, listed under the Local files chip. */
    LocalSong("Song"),
    ;

    val spotify: Boolean get() = this == SpotifyLiked || this == SpotifyPlaylist
    val playlist: Boolean get() = this == Liked || this == LocalFiles || this == Downloads || this == Top || this == Cached || this == Uploaded ||
        this == LocalPlaylist || this == Playlist || spotify
}

private enum class LibraryFilter(val label: String) { Playlists("Playlists"), Albums("Albums"), Artists("Artists"), Local("Local files") }

private enum class LibrarySort(val key: String, val label: String) {
    Recents("recents", "Recents"),
    Added("added", "Recently Added"),
    Alphabetical("alphabetical", "Alphabetical"),
    Creator("creator", "Creator"),
    /** Spotify's "Custom order": the order you dragged things into, what's new on top. */
    Custom("custom", "Custom order"),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: Recents
    }
}

/** [entries] in the order of [sort]; [custom] is Custom order's keys, what isn't in it yet on top, newest first. */
private fun sortEntries(entries: List<LibraryEntry>, sort: LibrarySort, custom: List<String>): List<LibraryEntry> = when (sort) {
    LibrarySort.Recents -> entries.sortedByDescending { it.recent }
    LibrarySort.Added -> entries.sortedByDescending { it.added }
    LibrarySort.Alphabetical -> entries.sortedBy { it.title.lowercase() }
    LibrarySort.Creator -> entries.sortedWith(compareBy({ it.owner.lowercase() }, { it.title.lowercase() }))
    LibrarySort.Custom -> {
        val place = custom.withIndex().associate { it.value to it.index }
        entries.filter { it.key !in place }.sortedByDescending { it.added } + entries.filter { it.key in place }.sortedBy { place.getValue(it.key) }
    }
}

/** [whole] with the keys of [part] in [part]'s order, each in a place one of them had; ones it lacks go after. */
private fun inPlaces(whole: List<String>, part: List<String>): List<String> {
    val had = whole.toHashSet()
    val moving = part.filter { it in had }
    val set = moving.toHashSet()
    val next = moving.iterator()
    return whole.map { if (it in set) next.next() else it } + part.filterNot { it in had }
}

@Immutable
private data class LibraryEntry(
    val key: String,
    val kind: Kind,
    val id: String,
    val title: String,
    val owner: String,
    val thumbnail: String?,
    val mosaic: List<String>,
    val screen: Screen,
    val added: Long,
    val recent: Long,
) {
    val subtitle: String get() = when {
        kind == Kind.Artist -> "Artist"
        // Spotify's lists carry its mark in front instead of a word.
        kind.spotify -> listOf("Playlist", owner).filter { it.isNotBlank() }.joinToString(" • ")
        else -> listOf(kind.label, owner).filter { it.isNotBlank() }.joinToString(" • ")
    }
}

/** Your Spotify Liked Songs and playlists, listed after the app's own, as Spotify lists them. */
private fun spotifyEntries(playlists: List<SpotifyPlaylist>, likedCount: Int?): List<LibraryEntry> = buildList {
    add(
        LibraryEntry(
            key = "spotify-liked", kind = Kind.SpotifyLiked, id = "spotify-liked", title = "Liked Songs",
            owner = likedCount?.let { if (it == 1) "1 song" else "$it songs" }.orEmpty(),
            thumbnail = null, mosaic = emptyList(), screen = Screen.SpotifyLiked, added = 0L, recent = 0L,
        ),
    )
    playlists.forEachIndexed { index, p ->
        // Spotify's own order is most recently played first; keep it under "Recents".
        add(
            LibraryEntry(
                "spotify:${p.id}", Kind.SpotifyPlaylist, p.id, p.name, p.owner, p.image, emptyList(),
                Screen.SpotifyPlaylist(p.id), added = -index.toLong(), recent = -index.toLong(),
            ),
        )
    }
}

/** The music files on this PC, the phone's "Local" list; shown once a scan has found any. */
private fun localEntry(count: Int, library: LibraryData): List<LibraryEntry> {
    if (count == 0) return emptyList()
    val recent = library.history.firstOrNull { com.convx.desktop.player.LocalMusic.isLocal(it.song.id) }?.at ?: 0L
    return listOf(
        LibraryEntry(
            key = "local-files", kind = Kind.LocalFiles, id = "local-files", title = "Local files",
            owner = if (count == 1) "1 song" else "$count songs",
            thumbnail = null, mosaic = emptyList(), screen = Screen.LocalFiles, added = 1L, recent = recent,
        ),
    )
}

/** The phone's "Downloaded" list; shown once anything is downloaded. */
private fun downloadsEntry(done: Map<String, com.convx.desktop.player.DownloadedSong>, library: LibraryData): List<LibraryEntry> {
    if (done.isEmpty()) return emptyList()
    val recent = library.history.firstOrNull { it.song.id in done }?.at ?: 0L
    return listOf(
        LibraryEntry(
            key = "downloads", kind = Kind.Downloads, id = "downloads", title = "Downloaded",
            owner = if (done.size == 1) "1 song" else "${done.size} songs",
            thumbnail = null, mosaic = emptyList(), screen = Screen.Downloads,
            added = done.values.maxOf { it.at }, recent = recent,
        ),
    )
}

/** Every music file on this PC, one row each, for the Local files chip; [recent] is when each last played. */
private fun localSongEntries(index: com.convx.desktop.player.LocalMusic.Index, library: LibraryData): List<LibraryEntry> {
    if (index.tracks.isEmpty()) return emptyList()
    val lastPlayed = HashMap<String, Long>()
    library.history.forEach { lastPlayed.merge(it.song.id, it.at) { a, b -> maxOf(a, b) } }
    return com.convx.desktop.player.LocalMusic.songs(index).map { song ->
        LibraryEntry(
            key = "file:${song.id}", kind = Kind.LocalSong, id = song.id, title = song.title, owner = song.artistText,
            thumbnail = song.thumbnail, mosaic = emptyList(), screen = Screen.LocalFiles,
            added = index.tracks[song.id]?.modified ?: 0L, recent = lastPlayed[song.id] ?: 0L,
        )
    }
}

/** The phone's "My top", "Cached" and "Uploaded" auto playlists, when switched on and there's something in them. */
private fun autoEntries(library: LibraryData, settings: com.convx.desktop.data.Settings, cached: List<Song>): List<LibraryEntry> = buildList {
    if (settings.showTopPlaylist && library.history.isNotEmpty()) {
        val count = library.history.distinctBy { it.song.id }.size.coerceAtMost(settings.topSize)
        add(
            LibraryEntry(
                key = "my-top", kind = Kind.Top, id = "my-top", title = "My top ${settings.topSize}",
                owner = if (count == 1) "1 song" else "$count songs",
                thumbnail = null, mosaic = emptyList(), screen = Screen.MyTop, added = 0L, recent = library.history.first().at,
            ),
        )
    }
    if (settings.showCachedPlaylist && cached.isNotEmpty()) {
        val ids = cached.mapTo(HashSet()) { it.id }
        add(
            LibraryEntry(
                key = "cached", kind = Kind.Cached, id = "cached", title = "Cached",
                owner = if (cached.size == 1) "1 song" else "${cached.size} songs",
                thumbnail = null, mosaic = emptyList(), screen = Screen.Cached,
                added = 0L, recent = library.history.firstOrNull { it.song.id in ids }?.at ?: 0L,
            ),
        )
    }
    if (settings.showUploadedPlaylist && library.uploaded.isNotEmpty()) {
        val ids = library.uploaded.mapTo(HashSet()) { it.id }
        add(
            LibraryEntry(
                key = "uploaded", kind = Kind.Uploaded, id = "uploaded", title = "Uploaded",
                owner = if (library.uploaded.size == 1) "1 song" else "${library.uploaded.size} songs",
                thumbnail = null, mosaic = emptyList(), screen = Screen.Uploaded,
                added = 0L, recent = library.history.firstOrNull { it.song.id in ids }?.at ?: 0L,
            ),
        )
    }
}

/** Everything in the library as one list; [recent] is the last time anything in it played. */
private fun LibraryData.entries(): List<LibraryEntry> {
    val bySong = HashMap<String, Long>()
    val byAlbum = HashMap<String, Long>()
    val byArtist = HashMap<String, Long>()
    history.forEach { event ->
        bySong.merge(event.song.id, event.at) { a, b -> maxOf(a, b) }
        event.song.album?.id?.let { byAlbum.merge(it, event.at) { a, b -> maxOf(a, b) } }
        event.song.artists.forEach { artist -> artist.id?.let { byArtist.merge(it, event.at) { a, b -> maxOf(a, b) } } }
    }
    fun lastOf(songs: List<Song>) = songs.maxOfOrNull { bySong[it.id] ?: 0L } ?: 0L
    return buildList {
        val likedSongs = liked.map { it.song }
        add(
            LibraryEntry(
                key = "liked", kind = Kind.Liked, id = "liked", title = "Liked Songs",
                owner = if (liked.size == 1) "1 song" else "${liked.size} songs",
                thumbnail = null, mosaic = emptyList(), screen = Screen.Liked,
                added = 0L, recent = maxOf(liked.maxOfOrNull { it.likedAt } ?: 0L, lastOf(likedSongs)),
            ),
        )
        localPlaylists.forEach { p ->
            add(
                LibraryEntry(
                    "local:${p.id}", Kind.LocalPlaylist, p.id, p.name, "You", com.convx.desktop.data.PlaylistCovers.url(p.cover),
                    p.songs.mapNotNull { it.thumbnail }.distinct().take(4), Screen.LocalPlaylist(p.id),
                    p.createdAt, maxOf(p.updatedAt, lastOf(p.songs)),
                ),
            )
        }
        playlists.forEach { p ->
            add(LibraryEntry("playlist:${p.id}", Kind.Playlist, p.id, p.title, p.author.orEmpty(), p.thumbnail, emptyList(), Screen.Playlist(p.id), p.savedAt, p.savedAt))
        }
        albums.forEach { a ->
            add(
                LibraryEntry(
                    "album:${a.browseId}", Kind.Album, a.browseId, a.title, a.artists.joinToString(", ") { it.name }, a.thumbnail,
                    emptyList(), Screen.Album(a.browseId), a.savedAt, maxOf(a.savedAt, byAlbum[a.browseId] ?: 0L),
                ),
            )
        }
        artists.forEach { a ->
            add(LibraryEntry("artist:${a.id}", Kind.Artist, a.id, a.name, "", a.thumbnail, emptyList(), Screen.Artist(a.id), a.savedAt, maxOf(a.savedAt, byArtist[a.id] ?: 0L)))
        }
    }
}

@Composable
fun LibraryPanel(collapsed: Boolean, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    val settings by AppSettings.state.collectAsState()
    val library by Library.state.collectAsState()
    val player by PlayerController.state.collectAsState()
    val panelWidth by animateDpAsState(if (collapsed) SideBarCollapsedWidth else SideBarWidth, spring(0.9f, 400f))
    val shape = if (collapsed) GlassShapes.Capsule else GlassShapes.Panel
    // Measured against the whole panel, so a small radius keeps the glow to about one row.
    val glow = rememberInteractiveHighlight(radiusScale = 0.3f)

    var filter by rememberSaveable { mutableStateOf<LibraryFilter?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val sort = LibrarySort.of(settings.librarySort)
    val pins = settings.pinnedLibrary
    val spotify by SpotifyAccount.state.collectAsState()
    var spotifyLists by remember { mutableStateOf<List<SpotifyPlaylist>?>(null) }
    var spotifyLiked by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(spotify is SpotifyAccount.State.SignedIn) {
        if (spotify !is SpotifyAccount.State.SignedIn) {
            spotifyLists = null
            spotifyLiked = null
            return@LaunchedEffect
        }
        // A failed read (Spotify busy, the network down) is tried again a little later, three times.
        for (wait in listOf(0L, 15_000L, 60_000L, 180_000L)) {
            kotlinx.coroutines.delay(wait)
            val lists = SpotifyApi.playlists()
            spotifyLiked = SpotifyApi.likedCount().getOrNull() ?: spotifyLiked
            lists.onSuccess { spotifyLists = it }.onFailure { com.convx.desktop.core.Log.w("Spotify", "Couldn't read your playlists: ${it.message}") }
            if (lists.isSuccess) break
        }
    }
    val localIndex by com.convx.desktop.player.LocalMusic.index.collectAsState()
    val downloaded by com.convx.desktop.player.Downloads.done.collectAsState()
    val autoSettings = listOf(settings.showLikedPlaylist, settings.showDownloadedPlaylist, settings.showTopPlaylist, settings.showCachedPlaylist, settings.showUploadedPlaylist, settings.topSize)
    val all = remember(library, spotifyLists, spotifyLiked, localIndex, downloaded, autoSettings) {
        val cached = if (settings.showCachedPlaylist) com.convx.desktop.ui.screens.cachedSongs(library, downloaded.values) else emptyList()
        (
            library.entries() + localEntry(localIndex.tracks.size, library) + downloadsEntry(downloaded, library) +
                autoEntries(library, settings, cached) +
                (spotifyLists?.let { spotifyEntries(it, spotifyLiked) } ?: emptyList())
            ).filterNot { (it.kind == Kind.Liked && !settings.showLikedPlaylist) || (it.kind == Kind.Downloads && !settings.showDownloadedPlaylist) }
    }
    // The files themselves only under their chip, so a big music folder doesn't flood the rest.
    val localSongs = remember(filter == LibraryFilter.Local, localIndex, library) {
        if (filter == LibraryFilter.Local) localSongEntries(localIndex, library) else emptyList()
    }
    val shown = remember(all, localSongs, filter, query, sort, pins, settings.libraryOrder) {
        val matching = (all + localSongs).filter { entry ->
            val kindOk = when (filter) {
                null -> entry.kind != Kind.LocalSong
                LibraryFilter.Playlists -> entry.kind.playlist
                LibraryFilter.Albums -> entry.kind == Kind.Album
                LibraryFilter.Artists -> entry.kind == Kind.Artist
                LibraryFilter.Local -> entry.kind == Kind.LocalFiles || entry.kind == Kind.LocalSong
            }
            kindOk && (query.isBlank() || entry.title.contains(query.trim(), ignoreCase = true) || entry.owner.contains(query.trim(), ignoreCase = true))
        }
        val sorted = sortEntries(matching, sort, settings.libraryOrder)
        // Pins stay on top in the order they were pinned, as Spotify keeps them; under Local files, the whole list leads.
        val ordered = if (filter == LibraryFilter.Local) sorted.sortedBy { it.kind != Kind.LocalFiles } else sorted
        ordered.filter { it.key in pins }.sortedBy { pins.indexOf(it.key) } + ordered.filter { it.key !in pins }
    }

    // Drag a row to put Your Library in your own order, as on Spotify: pins move among the pins,
    // the rest among the rest, and the sort turns to Custom order.
    val listState = rememberLazyListState()
    val reorder = rememberListReorder(listState, edgeTop = 4.dp, edgeBottom = 16.dp)
    val canReorder = filter != LibraryFilter.Local
    SideEffect {
        reorder.bind(
            keys = { shown.map { it.key } },
            allowed = { key, keys ->
                val entry = shown.firstOrNull { it.key == key }
                if (!canReorder || entry == null || entry.kind == Kind.LocalSong) {
                    null
                } else {
                    val pinnedCount = keys.count { it in pins }
                    if (key in pins) 0 until pinnedCount else pinnedCount..keys.lastIndex
                }
            },
            onDrop = { dropped ->
                val keys = dropped.map { it as String }
                val movedPins = keys.filter { it in pins }
                if (movedPins != shown.map { it.key }.filter { it in pins }) {
                    AppSettings.update { it.copy(pinnedLibrary = inPlaces(it.pinnedLibrary, movedPins)) }
                } else {
                    // The whole library in the order it's shown now, hidden rows too, with the shown ones moved.
                    val whole = sortEntries(all, sort, settings.libraryOrder).map { it.key }.filterNot { it in pins }
                    val order = inPlaces(whole, keys.filterNot { it in pins })
                    AppSettings.update { it.copy(libraryOrder = order, librarySort = LibrarySort.Custom.key) }
                }
            },
        )
    }
    val rows = reorder.arrange(shown) { it.key }
    LaunchedEffect(shown) { reorder.settled(shown.map { it.key }) }

    Column(
        modifier
            .width(panelWidth)
            .fillMaxHeight()
            .clip(shape)
            .liquidGlass(shape, GlassConfig.SidePanel, highlightAlpha = 0.3f)
            .then(glow.modifier)
            .then(glow.gestureModifier),
    ) {
        PanelHeader(collapsed)
        if (!collapsed) {
            FilterRow(filter) { filter = it }
            ToolsRow(query, { query = it }, sort) { option ->
                // Custom order starts from the order shown now, so nothing jumps when it's picked.
                AppSettings.update {
                    if (option == LibrarySort.Custom && it.libraryOrder.isEmpty()) {
                        it.copy(librarySort = option.key, libraryOrder = sortEntries(all, sort, it.libraryOrder).map { e -> e.key })
                    } else {
                        it.copy(librarySort = option.key)
                    }
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().fadingEdge(14.dp).reorderGestures(reorder),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 16.dp),
        ) {
            items(rows, key = { it.key }) { entry ->
                val song = entry.kind == Kind.LocalSong
                val held = reorder.held == entry.key
                Box(
                    Modifier
                        .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = if (reorder.active && !held) spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold) else null)
                        .zIndex(if (held) 1f else 0f)
                        .graphicsLayer {
                            translationY = reorder.shift(entry.key)
                            if (held) {
                                shadowElevation = 12.dp.toPx()
                                shape = RoundedCornerShape(10.dp)
                                clip = true
                            }
                        }
                        .background(if (held) Color(0xFF2A2A2D) else Color.Transparent, RoundedCornerShape(10.dp)),
                ) {
                    LibraryRow(
                        entry = entry,
                        collapsed = collapsed,
                        pinned = entry.key in pins,
                        selected = !song && navigator.current.screen == entry.screen,
                        playing = if (song) player.current?.id == entry.id else player.queueTitle != null && player.queueTitle == entry.title && player.current != null,
                        isPlaying = player.isPlaying,
                        // A file plays from where it is in the list, the rest of the shown files after it.
                        onClick = { if (song) playLocalFrom(shown, entry) else navigator.navigate(entry.screen) },
                    )
                }
            }
            if (shown.isEmpty() && !collapsed) {
                item(key = "empty") {
                    val noFiles = filter == LibraryFilter.Local && query.isBlank()
                    Text(
                        when {
                            query.isNotBlank() -> "Nothing in your library matches \"${query.trim()}\""
                            noFiles -> "No music files found on this PC yet. Choose your music folders in Settings."
                            else -> "Nothing here yet"
                        },
                        style = TextStyle(fontFamily = ConvxType.family, fontSize = 13.sp, lineHeight = 18.sp),
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                    )
                    if (noFiles) {
                        com.convx.desktop.ui.components.PillButton(
                            "Music folders",
                            {
                                navigator.navigate(Screen.Settings)
                                com.convx.desktop.ui.screens.settings.SettingsNav.push(com.convx.desktop.ui.screens.settings.SettingsPage.LocalFolders)
                            },
                            modifier = Modifier.padding(start = 12.dp),
                            icon = "storage",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(collapsed: Boolean) {
    val navigator = LocalNavigator.current
    val accent = LocalAccent.current
    val createMenu = rememberMenuAnchor()
    fun create() {
        Overlays.prompt("New playlist", confirm = "Create") { name ->
            navigator.navigate(Screen.LocalPlaylist(Library.createPlaylist(name)))
        }
    }
    if (collapsed) {
        Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            HeaderIcon("sf_music_note_square_stack", "Expand Your Library", accent) { UiState.toggleSideBar() }
            Spacer(Modifier.height(4.dp))
            HeaderIcon("add", "Create playlist", GlassTokens.Icon) { create() }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        Tooltip("Collapse Your Library") {
            Row(
                Modifier
                    .clip(GlassShapes.Capsule)
                    .hoverable(interaction)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = interaction, indication = null) { UiState.toggleSideBar() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConvxIcon("sf_music_note_square_stack", tint = accent, size = 22.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Your Library",
                    style = TextStyle(fontFamily = ConvxType.family, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.43).sp),
                    color = if (hovered) Color.White else Color.White.copy(alpha = 0.92f),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Box {
            val createInteraction = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .height(32.dp)
                    .glassControl(GlassShapes.Capsule, backdrop = null)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = createInteraction, indication = null) {
                        createMenu.offset = DpOffset(0.dp, 4.dp)
                        createMenu.expanded = true
                    }
                    .padding(start = 10.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConvxIcon("add", tint = Color.White, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("Create", style = TextStyle(fontFamily = ConvxType.family, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold), color = Color.White)
            }
            AppMenu(createMenu) {
                MenuItem("Playlist", "playlist_add") {
                    createMenu.expanded = false
                    create()
                }
            }
        }
    }
}

@Composable
private fun HeaderIcon(icon: String, tooltip: String, tint: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Tooltip(tooltip) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (hovered) 0.10f else 0f))
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            ConvxIcon(icon, tint = tint, size = 22.dp)
        }
    }
}

/** Spotify's filter chips; with one picked, a close chip in front clears it. */
@Composable
private fun FilterRow(filter: LibraryFilter?, onFilter: (LibraryFilter?) -> Unit) {
    Row(
        // Four chips are wider than the panel, so the row scrolls sideways, as Spotify's does.
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, bottom = 6.dp, top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(filter != null, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
            FilterChip(null, selected = false, icon = "close") { onFilter(null) }
        }
        LibraryFilter.entries.forEach { f ->
            if (filter == null || filter == f) {
                FilterChip(f.label, selected = filter == f) { onFilter(if (filter == f) null else f) }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String?, selected: Boolean, icon: String? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val accent = LocalAccent.current
    Row(
        Modifier
            .height(32.dp)
            // Glass on the panel's glass: drawn flat, since refracting the pages here would punch through the panel.
            .glassControl(GlassShapes.Capsule, fill = if (selected) accent else null, backdrop = null)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (label == null) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) ConvxIcon(icon, tint = Color.White, size = 16.dp)
        if (label != null) {
            Text(
                label,
                style = TextStyle(fontFamily = ConvxType.family, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                color = Color.White,
            )
        }
    }
}

/** Search in your library on the left, the sort on the right, as Spotify lays them out. */
@Composable
private fun ToolsRow(query: String, onQuery: (String) -> Unit, sort: LibrarySort, onSort: (LibrarySort) -> Unit) {
    var searching by remember { mutableStateOf(query.isNotEmpty()) }
    val focus = remember { FocusRequester() }
    val sortMenu = rememberMenuAnchor()
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searching) {
            Row(
                Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConvxIcon("search", tint = GlassTokens.Icon, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("Search in Your Library", style = TextStyle(fontFamily = ConvxType.family, fontSize = 13.sp), color = Color.White.copy(alpha = 0.5f), maxLines = 1)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = ConvxType.family, fontSize = 13.sp, color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onFocusChanged {
                                UiState.typingFocusChanged("library-search", it.isFocused)
                                if (!it.isFocused && query.isEmpty()) searching = false
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                    onQuery("")
                                    searching = false
                                    true
                                } else {
                                    false
                                }
                            },
                    )
                }
                if (query.isNotEmpty()) {
                    Box(
                        Modifier.size(20.dp).clip(CircleShape).pointerHoverIcon(PointerIcon.Hand).clickable { onQuery("") },
                        contentAlignment = Alignment.Center,
                    ) { ConvxIcon("close", tint = GlassTokens.Icon, size = 14.dp) }
                }
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        } else {
            HeaderIcon("search", "Search in Your Library", GlassTokens.Icon) { searching = true }
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.width(6.dp))
        Box {
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Row(
                Modifier
                    .height(32.dp)
                    .clip(GlassShapes.Capsule)
                    .hoverable(interaction)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = interaction, indication = null) {
                        sortMenu.offset = DpOffset(0.dp, 4.dp)
                        sortMenu.expanded = true
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    sort.label,
                    style = TextStyle(fontFamily = ConvxType.family, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                    color = if (hovered) Color.White else Color.White.copy(alpha = 0.7f),
                )
                Spacer(Modifier.width(6.dp))
                ConvxIcon("list", tint = if (hovered) Color.White else Color.White.copy(alpha = 0.7f), size = 16.dp)
            }
            AppMenu(sortMenu) {
                LibrarySort.entries.forEach { option ->
                    MenuItem(option.label, if (option == sort) "check" else null) {
                        sortMenu.expanded = false
                        onSort(option)
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(
    entry: LibraryEntry,
    collapsed: Boolean,
    pinned: Boolean,
    selected: Boolean,
    playing: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val menu = rememberMenuAnchor()
    val row = @Composable {
        Box(Modifier.onSecondaryClick(menu.openAtProvider())) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(RowHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            selected -> Color.White.copy(alpha = 0.12f)
                            hovered -> Color.White.copy(alpha = 0.06f)
                            else -> Color.Transparent
                        },
                    )
                    .hoverable(interaction)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                    .padding(horizontal = if (collapsed) 0.dp else 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
            ) {
                EntryArt(entry, ArtSize)
                if (!collapsed) {
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.title,
                            style = TextStyle(fontFamily = ConvxType.family, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.23).sp),
                            color = if (playing) accent else Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (pinned) {
                                ConvxIcon("ic_push_pin", tint = accent, size = 13.dp, modifier = Modifier.graphicsLayer { rotationZ = 45f })
                                Spacer(Modifier.width(4.dp))
                            }
                            if (entry.kind.spotify) {
                                ConvxIcon("spotify", tint = SpotifyGreen, size = 13.dp)
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(
                                entry.subtitle,
                                style = TextStyle(fontFamily = ConvxType.family, fontSize = 13.sp, lineHeight = 17.sp),
                                color = Color.White.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (playing) {
                        Spacer(Modifier.width(8.dp))
                        ConvxIcon(if (isPlaying) "volume_up" else "volume_mute", tint = accent, size = 18.dp)
                    }
                }
            }
            AppMenu(menu) { EntryMenu(entry, pinned) { menu.expanded = false } }
        }
    }
    if (collapsed) Tooltip(entry.title) { row() } else row()
}

@Composable
private fun EntryArt(entry: LibraryEntry, size: Dp) {
    val shape = if (entry.kind == Kind.Artist) CircleShape else RoundedCornerShape(6.dp)
    // The phone's "Library icons only": a plain glyph for each saved item, the auto playlists keep their tiles.
    val iconsOnly = AppSettings.state.collectAsState().value.libraryIconsOnly
    val glyph = when (entry.kind) {
        Kind.Album -> "sf_music_note_square_stack"
        Kind.Artist -> "mic"
        Kind.LocalPlaylist, Kind.Playlist, Kind.SpotifyPlaylist -> "queue_music"
        else -> null
    }
    if (iconsOnly && glyph != null) {
        Box(Modifier.size(size).clip(shape).background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            ConvxIcon(glyph, tint = Color.White.copy(alpha = 0.6f), size = size * 0.45f)
        }
        return
    }
    when (entry.kind) {
        Kind.Liked -> com.convx.desktop.ui.components.LikedSongsArt(size, shape)
        Kind.LocalFiles -> GradientTile("storage", size, LocalFilesGradient, shape)
        Kind.Downloads -> GradientTile("download", size, DownloadsGradient, shape)
        Kind.Top -> GradientTile("trending_up", size, com.convx.desktop.ui.screens.TopGradient, shape)
        Kind.Cached -> GradientTile("cached", size, com.convx.desktop.ui.screens.CachedGradient, shape)
        Kind.Uploaded -> GradientTile("cloud", size, com.convx.desktop.ui.screens.UploadedGradient, shape)
        Kind.LocalPlaylist -> if (entry.thumbnail != null) {
            Artwork(entry.thumbnail, Modifier.size(size), size = 160, shape = shape, iconSize = size * 0.4f)
        } else {
            // Its CD cover, or the mosaic with those off.
            val playlist = Library.playlist(entry.id)
            if (playlist != null) com.convx.desktop.ui.components.PlaylistCoverArt(playlist, size, shape) else MosaicArt(entry.mosaic, size, shape)
        }
        // Spotify's own Liked Songs tile.
        Kind.SpotifyLiked -> GradientTile("favorite", size, listOf(Color(0xFF450AF5), Color(0xFF8E8EE5)), shape)
        else -> Artwork(entry.thumbnail, Modifier.size(size), size = 160, shape = shape, iconSize = size * 0.4f)
    }
}

@Composable
private fun EntryMenu(entry: LibraryEntry, pinned: Boolean, dismiss: () -> Unit) {
    val navigator = LocalNavigator.current
    fun play(shuffle: Boolean) {
        dismiss()
        when (entry.kind) {
            Kind.Liked -> Library.state.value.liked.map { it.song }.takeIf { it.isNotEmpty() }
                ?.let { PlayerController.playSongs(it, 0, entry.title, shuffle = shuffle) }
            Kind.LocalFiles -> com.convx.desktop.player.LocalMusic.songs().takeIf { it.isNotEmpty() }
                ?.let { PlayerController.playSongs(it, 0, entry.title, shuffle = shuffle) }
            Kind.Downloads -> com.convx.desktop.player.Downloads.done.value.values.sortedByDescending { it.at }.map { it.song }
                .takeIf { it.isNotEmpty() }
                ?.let { PlayerController.playSongs(it, 0, entry.title, shuffle = shuffle) }
            Kind.Top -> com.convx.desktop.ui.screens.myTopSongs(com.convx.desktop.ui.screens.TopFilter.AllTime).map { it.value }
                .takeIf { it.isNotEmpty() }
                ?.let { PlayerController.playSongs(it, 0, entry.title, shuffle = shuffle) }
            Kind.Cached -> com.convx.desktop.ui.screens.cachedSongs().takeIf { it.isNotEmpty() }
                ?.let { PlayerController.playSongs(it, 0, entry.title, shuffle = shuffle) }
            Kind.Uploaded -> Library.state.value.uploaded.takeIf { it.isNotEmpty() }
                ?.let { PlayerController.playSongs(it, 0, entry.title, shuffle = shuffle) }
            Kind.LocalPlaylist -> Library.playlist(entry.id)?.songs?.takeIf { it.isNotEmpty() }
                ?.let { PlayerController.playSongs(it, 0, entry.title, shuffle = shuffle) }
            Kind.Playlist, Kind.Album -> AppScope.launch {
                val songs = withContext(Dispatchers.IO) {
                    runCatching {
                        if (entry.kind == Kind.Album) YouTube.album(entry.id).getOrThrow().songs.map { it.toSong() }
                        else YouTube.playlist(entry.id.removePrefix("VL")).getOrThrow().songs.map { it.toSong() }
                    }.getOrNull().orEmpty()
                }
                if (songs.isEmpty()) Overlays.toast("Couldn't load songs", "error")
                else PlayerController.playSongs(songs, 0, entry.title, shuffle = shuffle)
            }
            Kind.Artist -> navigator.navigate(entry.screen)
            Kind.LocalSong -> com.convx.desktop.player.LocalMusic.songs().takeIf { it.isNotEmpty() }?.let { files ->
                val start = files.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
                PlayerController.playSongs(files, start, "Local files", shuffle = shuffle)
            }
            Kind.SpotifyLiked, Kind.SpotifyPlaylist -> AppScope.launch {
                val tracks = if (entry.kind == Kind.SpotifyLiked) SpotifyApi.likedSongs() else SpotifyApi.playlistTracks(entry.id)
                val songs = tracks.getOrNull().orEmpty().map(SpotifyMatcher::toSong)
                if (songs.isEmpty()) Overlays.toast("Couldn't load songs from Spotify", "error")
                else PlayerController.playSongs(songs, 0, entry.title, shuffle = shuffle)
            }
        }
    }
    if (entry.kind != Kind.Artist) {
        MenuItem("Play", "play_applemusic") { play(false) }
        MenuItem("Shuffle", "shuffle") { play(true) }
        MenuDivider()
    }
    MenuItem(if (pinned) "Unpin" else "Pin", "ic_push_pin") {
        dismiss()
        AppSettings.update { s ->
            s.copy(pinnedLibrary = if (pinned) s.pinnedLibrary - entry.key else s.pinnedLibrary + entry.key)
        }
    }
    when (entry.kind) {
        Kind.LocalPlaylist -> {
            MenuItem("Rename", "edit") {
                dismiss()
                Overlays.prompt("Rename playlist", initial = entry.title) { Library.renamePlaylist(entry.id, it) }
            }
            val hasCover = Library.playlist(entry.id)?.cover != null
            MenuItem(if (hasCover) "Change picture" else "Choose picture", "image") {
                dismiss()
                com.convx.desktop.ui.components.choosePlaylistCover(entry.id)
            }
            if (hasCover) {
                MenuItem("Remove picture", "hide_image") {
                    dismiss()
                    Library.setPlaylistCover(entry.id, null)
                }
            }
            if (!hasCover && AppSettings.value.discCovers) {
                MenuItem("New cover design", "shuffle") {
                    dismiss()
                    Library.newCoverDesign(entry.id)
                }
            }
            MenuItem("Delete", "delete", destructive = true) {
                dismiss()
                Overlays.confirm("Delete playlist?", "\"${entry.title}\" will be removed from your library.", "Delete") {
                    if (navigator.current.screen == entry.screen) navigator.back()
                    Library.deletePlaylist(entry.id)
                }
            }
        }
        Kind.Playlist -> MenuItem("Remove from Your Library", "delete", destructive = true) {
            dismiss()
            Library.state.value.playlists.firstOrNull { it.id == entry.id }?.let { Library.togglePlaylist(it) }
        }
        Kind.Album -> MenuItem("Remove from Your Library", "delete", destructive = true) {
            dismiss()
            Library.state.value.albums.firstOrNull { it.browseId == entry.id }?.let { Library.toggleAlbum(it) }
        }
        Kind.Artist -> MenuItem("Unfollow", "delete", destructive = true) {
            dismiss()
            Library.state.value.artists.firstOrNull { it.id == entry.id }?.let { Library.toggleArtist(it) }
        }
        Kind.Liked -> {
            val hasCover = AppSettings.value.likedSongsCover != null
            MenuItem(if (hasCover) "Change picture" else "Choose picture", "image") {
                dismiss()
                com.convx.desktop.ui.components.chooseLikedSongsCover()
            }
            if (hasCover) {
                MenuItem("Remove picture", "hide_image") {
                    dismiss()
                    com.convx.desktop.ui.components.setLikedSongsCover(null)
                }
            }
        }
        Kind.LocalFiles, Kind.Downloads, Kind.Top, Kind.Cached, Kind.Uploaded, Kind.SpotifyLiked, Kind.SpotifyPlaylist, Kind.LocalSong -> Unit
    }
}

/** Fades content out over [edge] at the top and bottom, the phone's fadingEdge(vertical). */
fun Modifier.fadingEdge(edge: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val f = (edge.toPx() / size.height).coerceIn(0f, 0.5f)
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, f to Color.Black, 1f - f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

/** Plays the local files as they're listed, from [entry] on. */
private fun playLocalFrom(shown: List<LibraryEntry>, entry: LibraryEntry) {
    val files = shown.filter { it.kind == Kind.LocalSong }
    val songs = com.convx.desktop.player.LocalMusic.index.value.tracks.let { tracks ->
        files.mapNotNull { e -> tracks[e.id]?.let { com.convx.desktop.player.LocalMusic.toSong(e.id, it) } }
    }
    val start = songs.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
    if (songs.isNotEmpty()) PlayerController.playSongs(songs, start, "Local files")
}
