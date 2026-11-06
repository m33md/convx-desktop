/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.shell

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.VerticalScrollbar
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.components.reorderGestures
import com.convx.desktop.ui.glass.GlassConfig
import com.convx.desktop.ui.glass.GlassShapes
import com.convx.desktop.ui.glass.liquidGlass
import com.convx.desktop.ui.glass.onSelected
import com.convx.desktop.ui.glass.rememberInteractiveHighlight
import com.convx.desktop.ui.glass.rememberSymbolBounce
import com.convx.desktop.ui.screens.SpotifyGreen
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent
import com.music.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The side bar, kept plain, in the spirit of Apple Music's, in Convx's own glass:
 * where to go at the top, your library under a small heading, your playlists under another, and
 * you at the foot. It folds to a rail of icons and covers on narrow windows.
 */

val SideBarWidth: Dp = 236.dp
val SideBarCollapsedWidth: Dp = 68.dp

/** Gap between the window edge and the side bar; on the phone it is 30dp of a tablet's width. */
val SideBarMargin: Dp = 12.dp

/** Where rows start beside the side bar; lists still run under it. */
val SideBarContentInset: Dp = SideBarMargin + SideBarWidth + 8.dp
val SideBarCollapsedContentInset: Dp = SideBarMargin + SideBarCollapsedWidth + 8.dp

/** Local files' tile, in the phone's orange for music folders. */
val LocalFilesGradient = listOf(Color(0xFFFF9F0A), Color(0xFFFF375F))

/** Downloaded songs' tile. */
val DownloadsGradient = listOf(Color(0xFF30D158), Color(0xFF0A84FF))

private val RowHeight = 36.dp
private val RailRowHeight = 44.dp
private val ArtSize = 24.dp
private val RailArtSize = 32.dp
private val RowShape = RoundedCornerShape(10.dp)

/** The kinds of list the Playlists heading holds. */
private enum class Kind {
    Top,
    Cached,
    Uploaded,
    LocalPlaylist,
    Playlist,
    SpotifyLiked,
    SpotifyPlaylist,
    ;

    val spotify: Boolean get() = this == SpotifyLiked || this == SpotifyPlaylist
}

private enum class LibrarySort(val key: String) {
    Recents("recents"),
    Added("added"),
    Alphabetical("alphabetical"),
    Creator("creator"),
    /** Spotify's "Custom order": the order you dragged things into, what's new on top. */
    Custom("custom"),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: Recents
    }
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
)

/** A place the side bar leads to. */
@Immutable
private data class NavItem(val key: String, val label: String, val icon: String, val selected: Boolean, val onClick: () -> Unit)

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

/** Your own playlists and the ones you saved; [LibraryEntry.recent] is the last time anything in one played. */
private fun LibraryData.playlistEntries(): List<LibraryEntry> {
    val bySong = HashMap<String, Long>()
    history.forEach { event -> bySong.merge(event.song.id, event.at) { a, b -> maxOf(a, b) } }
    fun lastOf(songs: List<Song>) = songs.maxOfOrNull { bySong[it.id] ?: 0L } ?: 0L
    return buildList {
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
    }
}

private fun rowText(weight: FontWeight = FontWeight.Medium) =
    TextStyle(fontFamily = ConvxType.family, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = weight, letterSpacing = (-0.15).sp)

@Composable
fun SideBar(collapsed: Boolean, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    val screen = navigator.current.screen
    val settings by AppSettings.state.collectAsState()
    val library by Library.state.collectAsState()
    val player by PlayerController.state.collectAsState()
    val panelWidth by animateDpAsState(if (collapsed) SideBarCollapsedWidth else SideBarWidth, spring(0.9f, 400f))
    val shape = if (collapsed) GlassShapes.Capsule else GlassShapes.Panel
    // Measured against the whole panel, so a small radius keeps the glow to about one row.
    val glow = rememberInteractiveHighlight(radiusScale = 0.3f)

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
    val autoSettings = listOf(settings.showTopPlaylist, settings.showCachedPlaylist, settings.showUploadedPlaylist, settings.topSize)
    val all = remember(library, spotifyLists, spotifyLiked, downloaded, autoSettings) {
        val cached = if (settings.showCachedPlaylist) com.convx.desktop.ui.screens.cachedSongs(library, downloaded.values) else emptyList()
        autoEntries(library, settings, cached) + library.playlistEntries() +
            (spotifyLists?.let { spotifyEntries(it, spotifyLiked) } ?: emptyList())
    }
    val sort = LibrarySort.of(settings.librarySort)
    val pins = settings.pinnedLibrary
    val shown = remember(all, sort, pins, settings.libraryOrder) {
        val sorted = sortEntries(all, sort, settings.libraryOrder)
        // Pins stay on top in the order they were pinned.
        sorted.filter { it.key in pins }.sortedBy { pins.indexOf(it.key) } + sorted.filter { it.key !in pins }
    }

    // The places: Apple Music's short list, each hidden by the phone's navigation switches.
    val hidden = settings.hiddenSections
    val searching = screen == Screen.Search || screen is Screen.SearchResults
    val places = buildList {
        add(
            NavItem("search", "Search", "search", searching) {
                if (searching) runCatching { UiState.searchFocus.requestFocus() } else navigator.navigate(Screen.Search)
            },
        )
        if (Section.Home.name !in hidden) add(NavItem("home", Section.Home.label, Section.Home.icon, screen == Screen.Home) { navigator.navigate(Screen.Home) })
        if (Section.Explore.name !in hidden) add(NavItem("explore", Section.Explore.label, Section.Explore.icon, screen == Screen.Explore) { navigator.navigate(Screen.Explore) })
        if (Section.Radio.name !in hidden) {
            add(NavItem("radio", Section.Radio.label, Section.Radio.icon, screen == Screen.Radio || screen is Screen.RadioStation) { navigator.navigate(Screen.Radio) })
        }
    }
    fun openTab(tab: LibraryTab) {
        UiState.libraryTab = tab
        navigator.navigate(Screen.Library)
    }
    fun onTab(tab: LibraryTab) = screen == Screen.Library && UiState.libraryTab == tab
    val libraryItems = if (Section.Library.name in hidden) emptyList() else buildList {
        add(NavItem("history", "Recently Played", "history", screen == Screen.History) { navigator.navigate(Screen.History) })
        add(NavItem("artists", "Artists", "artist", onTab(LibraryTab.Artists)) { openTab(LibraryTab.Artists) })
        add(NavItem("albums", "Albums", "album", onTab(LibraryTab.Albums)) { openTab(LibraryTab.Albums) })
        if (settings.showLikedPlaylist) add(NavItem("liked", "Liked Songs", "favorite", screen == Screen.Liked) { navigator.navigate(Screen.Liked) })
        if (settings.showDownloadedPlaylist && downloaded.isNotEmpty()) add(NavItem("downloads", "Downloaded", "download", screen == Screen.Downloads) { navigator.navigate(Screen.Downloads) })
        if (localIndex.tracks.isNotEmpty()) add(NavItem("local", "Local Files", "storage", screen == Screen.LocalFiles) { navigator.navigate(Screen.LocalFiles) })
        add(NavItem("stats", "Stats", "stats", screen == Screen.Stats) { navigator.navigate(Screen.Stats) })
    }
    val allPlaylists = NavItem("all-playlists", "All Playlists", "grid_view", onTab(LibraryTab.Playlists)) { openTab(LibraryTab.Playlists) }
    fun create() {
        Overlays.prompt("New playlist", confirm = "Create") { name ->
            navigator.navigate(Screen.LocalPlaylist(Library.createPlaylist(name)))
        }
    }

    // Drag a playlist to put them in your own order: pins move among the pins, the rest among the
    // rest, and the order becomes Custom order.
    val listState = rememberLazyListState()
    val reorder = rememberListReorder(listState, edgeTop = 4.dp, edgeBottom = 16.dp)
    SideEffect {
        reorder.bind(
            keys = { shown.map { it.key } },
            allowed = { key, keys ->
                if (shown.none { it.key == key }) {
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
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().fadingEdge(12.dp).reorderGestures(reorder),
                contentPadding = PaddingValues(start = 8.dp, end = if (collapsed) 8.dp else 10.dp, top = 10.dp, bottom = 12.dp),
            ) {
                items(places, key = { "nav:${it.key}" }) { NavRow(it, collapsed) }
                if (libraryItems.isNotEmpty()) {
                    item(key = "heading:library") { Heading("Library", collapsed) }
                    items(libraryItems, key = { "nav:${it.key}" }) { NavRow(it, collapsed) }
                }
                item(key = "heading:playlists") {
                    Heading("Playlists", collapsed) { HeadingButton("add", "New playlist") { create() } }
                }
                item(key = "nav:all-playlists") { NavRow(allPlaylists, collapsed) }
                items(rows, key = { it.key }) { entry ->
                    val held = reorder.held == entry.key
                    Box(
                        Modifier
                            .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = if (reorder.active && !held) spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold) else null)
                            .zIndex(if (held) 1f else 0f)
                            .graphicsLayer {
                                translationY = reorder.shift(entry.key)
                                if (held) {
                                    shadowElevation = 12.dp.toPx()
                                    this.shape = RowShape
                                    this.clip = true
                                }
                            }
                            .background(if (held) Color(0xFF2A2A2D) else Color.Transparent, RowShape),
                    ) {
                        PlaylistRow(
                            entry = entry,
                            collapsed = collapsed,
                            pinned = entry.key in pins,
                            selected = screen == entry.screen,
                            playing = player.queueTitle != null && player.queueTitle == entry.title && player.current != null,
                            isPlaying = player.isPlaying,
                            onClick = { navigator.navigate(entry.screen) },
                        )
                    }
                }
            }
            // The rail scrolls by wheel only: a bar would crowd its icons and run into its round ends.
            if (!collapsed) {
                VerticalScrollbar(
                    rememberScrollbarAdapter(listState),
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(top = 14.dp, bottom = 12.dp, end = 3.dp),
                )
            }
        }
        AccountRow(collapsed)
    }
}

/** A small grey heading over a group, Apple Music's "Library"; a hairline in the rail. */
@Composable
private fun Heading(title: String, collapsed: Boolean, action: (@Composable () -> Unit)? = null) {
    if (collapsed) {
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(28.dp).height(1.dp).background(Color.White.copy(alpha = 0.14f)))
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 16.dp, bottom = 4.dp).height(26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = TextStyle(fontFamily = ConvxType.family, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
            color = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

@Composable
private fun HeadingButton(icon: String, tooltip: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Tooltip(tooltip) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (hovered) 0.12f else 0f))
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            ConvxIcon(icon, tint = Color.White.copy(alpha = if (hovered) 0.9f else 0.6f), size = 16.dp)
        }
    }
}

/** A row's wash: lit where you are, faint under the pointer. */
private fun rowWash(selected: Boolean, hovered: Boolean): Color = when {
    selected -> Color.White.copy(alpha = 0.12f)
    hovered -> Color.White.copy(alpha = 0.06f)
    else -> Color.Transparent
}

@Composable
private fun NavRow(item: NavItem, collapsed: Boolean) {
    val accent = LocalAccent.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bounce = rememberSymbolBounce().onSelected(item.selected)
    val row = @Composable {
        Row(
            Modifier
                .fillMaxWidth()
                .height(if (collapsed) RailRowHeight else RowHeight)
                .clip(RowShape)
                .background(rowWash(item.selected, hovered))
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null, onClick = item.onClick)
                .padding(horizontal = if (collapsed) 0.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
        ) {
            ConvxIcon(
                item.icon,
                tint = if (item.selected) accent else Color.White.copy(alpha = 0.78f),
                size = if (collapsed) 22.dp else 19.dp,
                contentDescription = item.label,
                modifier = bounce.modifier,
            )
            if (!collapsed) {
                Spacer(Modifier.width(12.dp))
                Text(
                    item.label,
                    style = rowText(if (item.selected) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (item.selected) Color.White else Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (collapsed) Tooltip(item.label) { row() } else row()
}

@Composable
private fun PlaylistRow(
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
                    .height(if (collapsed) RailRowHeight else RowHeight)
                    .clip(RowShape)
                    .background(rowWash(selected, hovered))
                    .hoverable(interaction)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                    .padding(horizontal = if (collapsed) 0.dp else 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
            ) {
                EntryArt(entry, if (collapsed) RailArtSize else ArtSize)
                if (!collapsed) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        entry.title,
                        style = rowText(if (selected) FontWeight.SemiBold else FontWeight.Medium),
                        color = if (playing) accent else if (selected) Color.White else Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    when {
                        playing -> ConvxIcon(if (isPlaying) "volume_up" else "volume_mute", tint = accent, size = 15.dp)
                        entry.kind.spotify -> ConvxIcon("spotify", tint = SpotifyGreen, size = 12.dp)
                        pinned -> ConvxIcon("ic_push_pin", tint = Color.White.copy(alpha = 0.4f), size = 12.dp, modifier = Modifier.graphicsLayer { rotationZ = 45f })
                    }
                }
            }
            AppMenu(menu) { EntryMenu(entry, pinned) { menu.expanded = false } }
        }
    }
    if (collapsed) Tooltip(entry.title) { row() } else row()
}

/** You at the foot of the side bar, as Apple Music's account: your profile, and Settings beside it. */
@Composable
private fun AccountRow(collapsed: Boolean) {
    val navigator = LocalNavigator.current
    val screen = navigator.current.screen
    val settings by AppSettings.state.collectAsState()
    val identity = com.convx.desktop.ui.screens.rememberProfileIdentity()
    val accent = LocalAccent.current
    val popup = rememberMenuAnchor()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    fun open() {
        // The phone's "Enable settings popup" puts its quick popup first.
        if (settings.settingsPopup) {
            popup.offset = DpOffset(0.dp, 0.dp)
            popup.expanded = true
        } else {
            navigator.navigate(Screen.Profile)
        }
    }
    val title = if (identity.source != null) identity.name else "Your profile"
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
        Spacer(Modifier.height(6.dp))
        if (collapsed) {
            NavRow(NavItem("settings", "Settings", "settings", screen == Screen.Settings) { navigator.navigate(Screen.Settings) }, collapsed = true)
        }
        Box {
            val row = @Composable {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(if (collapsed) RailRowHeight + 4.dp else 52.dp)
                        .clip(RowShape)
                        .background(rowWash(screen == Screen.Profile, hovered))
                        .hoverable(interaction)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(interactionSource = interaction, indication = null) { open() }
                        .padding(horizontal = if (collapsed) 0.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
                ) {
                    Avatar(identity.picture, 32.dp)
                    if (!collapsed) {
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, style = rowText(FontWeight.SemiBold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                identity.source?.let { "Signed in with $it" } ?: "Accounts and settings",
                                style = TextStyle(fontFamily = ConvxType.family, fontSize = 12.sp, lineHeight = 16.sp),
                                color = Color.White.copy(alpha = 0.55f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        SettingsButton(screen == Screen.Settings, accent) { navigator.navigate(Screen.Settings) }
                    }
                }
            }
            if (collapsed) Tooltip(title) { row() } else row()
            AppMenu(popup) { SettingsPopup { popup.expanded = false } }
        }
    }
}

@Composable
private fun Avatar(picture: String?, size: Dp) {
    if (picture != null) {
        Artwork(picture, Modifier.size(size), size = 96, shape = CircleShape, placeholderIcon = "account", iconSize = size * 0.55f)
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            ConvxIcon("person", tint = Color.White.copy(alpha = 0.8f), size = size * 0.55f)
        }
    }
}

@Composable
private fun SettingsButton(lit: Boolean, accent: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Tooltip("Settings") {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (hovered) 0.12f else 0f))
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            ConvxIcon("settings", tint = if (lit) accent else Color.White.copy(alpha = if (hovered) 0.9f else 0.65f), size = 18.dp)
        }
    }
}

@Composable
private fun EntryArt(entry: LibraryEntry, size: Dp) {
    val shape = RoundedCornerShape(if (size < 28.dp) 5.dp else 6.dp)
    // The phone's "Library icons only": a plain glyph for each saved list, the auto playlists keep their tiles.
    val iconsOnly = AppSettings.state.collectAsState().value.libraryIconsOnly
    val glyph = when (entry.kind) {
        Kind.LocalPlaylist, Kind.Playlist, Kind.SpotifyPlaylist -> "queue_music"
        else -> null
    }
    if (iconsOnly && glyph != null) {
        Box(Modifier.size(size).clip(shape).background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            ConvxIcon(glyph, tint = Color.White.copy(alpha = 0.6f), size = size * 0.55f)
        }
        return
    }
    when (entry.kind) {
        Kind.Top -> GradientTile("trending_up", size, com.convx.desktop.ui.screens.TopGradient, shape)
        Kind.Cached -> GradientTile("cached", size, com.convx.desktop.ui.screens.CachedGradient, shape)
        Kind.Uploaded -> GradientTile("cloud", size, com.convx.desktop.ui.screens.UploadedGradient, shape)
        Kind.LocalPlaylist -> if (entry.thumbnail != null) {
            Artwork(entry.thumbnail, Modifier.size(size), size = 96, shape = shape, iconSize = size * 0.5f)
        } else {
            // Its CD cover, or the mosaic with those off.
            val playlist = Library.playlist(entry.id)
            if (playlist != null) com.convx.desktop.ui.components.PlaylistCoverArt(playlist, size, shape) else MosaicArt(entry.mosaic, size, shape)
        }
        // Spotify's own Liked Songs tile.
        Kind.SpotifyLiked -> GradientTile("favorite", size, listOf(Color(0xFF450AF5), Color(0xFF8E8EE5)), shape)
        Kind.Playlist, Kind.SpotifyPlaylist -> Artwork(entry.thumbnail, Modifier.size(size), size = 96, shape = shape, iconSize = size * 0.5f)
    }
}

@Composable
private fun EntryMenu(entry: LibraryEntry, pinned: Boolean, dismiss: () -> Unit) {
    val navigator = LocalNavigator.current
    fun play(shuffle: Boolean) {
        dismiss()
        when (entry.kind) {
            Kind.Top -> com.convx.desktop.ui.screens.myTopSongs(com.convx.desktop.ui.screens.TopFilter.AllTime).map { it.value }
                .takeIf { it.isNotEmpty() }
                ?.let { PlayerController.playSongs(it, 0, entry.title, shuffle = shuffle) }
            Kind.Cached -> com.convx.desktop.ui.screens.cachedSongs().takeIf { it.isNotEmpty() }
                ?.let { PlayerController.playSongs(it, 0, entry.title, shuffle = shuffle) }
            Kind.Uploaded -> Library.state.value.uploaded.takeIf { it.isNotEmpty() }
                ?.let { PlayerController.playSongs(it, 0, entry.title, shuffle = shuffle) }
            Kind.LocalPlaylist -> Library.playlist(entry.id)?.songs?.takeIf { it.isNotEmpty() }
                ?.let { PlayerController.playSongs(it, 0, entry.title, shuffle = shuffle) }
            Kind.Playlist -> AppScope.launch {
                val songs = withContext(Dispatchers.IO) {
                    runCatching { YouTube.playlist(entry.id.removePrefix("VL")).getOrThrow().songs.map { it.toSong() } }.getOrNull().orEmpty()
                }
                if (songs.isEmpty()) Overlays.toast("Couldn't load songs", "error")
                else PlayerController.playSongs(songs, 0, entry.title, shuffle = shuffle)
            }
            Kind.SpotifyLiked, Kind.SpotifyPlaylist -> AppScope.launch {
                val tracks = if (entry.kind == Kind.SpotifyLiked) SpotifyApi.likedSongs() else SpotifyApi.playlistTracks(entry.id)
                val songs = tracks.getOrNull().orEmpty().map(SpotifyMatcher::toSong)
                if (songs.isEmpty()) Overlays.toast("Couldn't load songs from Spotify", "error")
                else PlayerController.playSongs(songs, 0, entry.title, shuffle = shuffle)
            }
        }
    }
    MenuItem("Play", "play_applemusic") { play(false) }
    MenuItem("Shuffle", "shuffle") { play(true) }
    MenuDivider()
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
        Kind.Top, Kind.Cached, Kind.Uploaded, Kind.SpotifyLiked, Kind.SpotifyPlaylist -> Unit
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
