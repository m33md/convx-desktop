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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.core.formatTotal
import com.convx.desktop.core.pluralize
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.Song
import com.convx.desktop.data.toSaved
import com.convx.desktop.data.toSong
import com.convx.desktop.player.Downloads
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.CollectionMenuContent
import com.convx.desktop.ui.components.DownloadMenuItems
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.Gutter
import com.convx.desktop.ui.components.ItemShelf
import com.convx.desktop.ui.components.LinkText
import com.convx.desktop.ui.components.LoadingState
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.components.MessageState
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.SongRow
import com.convx.desktop.ui.components.SongTableHeader
import com.convx.desktop.ui.components.ListReorder
import com.convx.desktop.ui.components.rememberListReorder
import com.convx.desktop.ui.components.reorderGestures
import com.convx.desktop.ui.components.Tooltip
import com.convx.desktop.ui.components.onSecondaryClick
import com.convx.desktop.ui.components.openAtProvider
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.components.GradientTile
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.components.MosaicArt
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.components.LocalPageInsets
import com.convx.desktop.ui.theme.ConvxType
import com.music.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Every page is Convx black; nothing takes a cover's colour. */
private val PageBlack = Color.Black

/** The phone's detail-page text tiers: metadata and descriptions sit at 48% white. */
private val Metadata = Color.White.copy(alpha = 0.48f)

/**
 * The phone's album and playlist header (CollectionDetailHeader) across a desktop page:
 * the shadowed artwork with 12 corners on the left and, centred beside it as on the phone,
 * the title block, the metadata line, shuffle / Play / more, and a two-line description.
 *
 * Albums pass the artist as [wordmark], which the phone sets large above the album title.
 * Right-clicking anywhere on the header opens the same menu as the more button.
 */
fun LazyListScope.collectionHeader(
    title: String,
    art: @Composable (Dp) -> Unit,
    metadata: String,
    playing: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    enabled: Boolean = true,
    wordmark: String? = null,
    onWordmarkClick: (() -> Unit)? = null,
    subtitle: String? = null,
    onSubtitleClick: (() -> Unit)? = null,
    description: String? = null,
    menu: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
    download: List<Song>? = null,
) {
    item(key = "header") {
        val headerMenu = rememberMenuAnchor()
        val buttonMenu = rememberMenuAnchor()
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .then(if (menu != null) Modifier.onSecondaryClick(headerMenu.openAtProvider()) else Modifier),
        ) {
            val side = (maxWidth * 0.3f).coerceIn(200.dp, 300.dp)
            Row(
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = LocalPageInsets.current.top + 16.dp, bottom = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.shadow(
                        18.dp,
                        RoundedCornerShape(12.dp),
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.18f),
                        spotColor = Color.Black.copy(alpha = 0.18f),
                    ),
                ) { art(side) }
                Spacer(Modifier.width(32.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (wordmark != null) {
                        HeaderText(
                            wordmark,
                            ConvxType.LargeTitle.copy(lineHeight = 38.sp),
                            onClick = onWordmarkClick,
                        )
                        Spacer(Modifier.height(8.dp))
                        HeaderText(title, ConvxType.Body.copy(fontSize = 20.sp, lineHeight = 24.sp))
                    } else {
                        HeaderText(title, ConvxType.Title1)
                        if (!subtitle.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            HeaderText(subtitle, ConvxType.Body.copy(fontSize = 20.sp, lineHeight = 24.sp), onClick = onSubtitleClick)
                        }
                    }
                    if (metadata.isNotBlank()) {
                        Spacer(Modifier.height(7.dp))
                        Text(
                            metadata,
                            style = ConvxType.Body.copy(fontWeight = FontWeight.Medium, lineHeight = 19.sp),
                            color = Metadata,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(17.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        HeaderCircle("shuffle", "Shuffle", onShuffle, enabled)
                        PlayPill(playing, enabled, onPlay)
                        if (download != null) {
                            // The phone's album page: its right button downloads, and "more" sits up top.
                            DownloadCircle(download)
                        } else if (menu != null) {
                            Box {
                                HeaderCircle("more_horiz", "More options", { buttonMenu.expanded = true })
                                AppMenu(buttonMenu) { menu { buttonMenu.expanded = false } }
                            }
                        } else {
                            Spacer(Modifier.size(54.dp))
                        }
                    }
                    if (!description.isNullOrBlank()) {
                        Spacer(Modifier.height(20.dp))
                        ExpandableDescription(description, Modifier.widthIn(max = 620.dp))
                    }
                }
            }
            if (download != null && menu != null) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = LocalPageInsets.current.top + 16.dp, end = Gutter)) {
                    HeaderCircle("more_horiz", "More options", { buttonMenu.expanded = true }, size = 40.dp)
                    AppMenu(buttonMenu) { menu { buttonMenu.expanded = false } }
                }
            }
            if (menu != null) AppMenu(headerMenu) { menu { headerMenu.expanded = false } }
        }
    }
}

/**
 * Downloads a whole album or list: an arrow, a ring filling as the songs land, and a tick once
 * they're all on this PC (a click on the tick offers to remove them, as on the phone).
 */
@Composable
private fun DownloadCircle(songs: List<Song>) {
    val done by Downloads.done.collectAsState()
    val active by Downloads.active.collectAsState()
    val wanted = remember(songs) { songs.filter(Downloads::canDownload) }
    val ids = remember(wanted) { wanted.map { it.id } }
    when (val state = Downloads.collective(wanted, done, active)) {
        Downloads.Collective.Done -> HeaderCircle("check", "Downloaded", {
            Overlays.confirm(
                "Remove download?",
                "${ids.size} downloaded ${if (ids.size == 1) "song" else "songs"} will be removed from this PC. They stay in your library.",
                "Remove",
            ) { Downloads.remove(ids) }
        })
        is Downloads.Collective.Running -> {
            val partial = ids.sumOf { id -> ((active[id]?.status as? Downloads.Status.Running)?.fraction ?: 0f).toDouble() }
            val progress = ((state.done + partial) / state.total).toFloat().coerceIn(0f, 1f)
            Box(
                Modifier.drawWithContent {
                    drawContent()
                    val stroke = 3.dp.toPx()
                    drawArc(
                        Color.White,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                },
            ) {
                HeaderCircle("download", "Downloading ${state.done} of ${state.total}. Click to stop", { Downloads.cancel(ids) })
            }
        }
        Downloads.Collective.None -> HeaderCircle(
            "download",
            if (wanted.isEmpty()) "Nothing here can be downloaded" else "Download",
            {
                val queued = Downloads.download(wanted)
                if (queued > 1) Overlays.toast("Downloading $queued songs", "download")
            },
            enabled = wanted.isNotEmpty(),
        )
    }
}

@Composable
private fun HeaderText(text: String, style: TextStyle, onClick: (() -> Unit)? = null) {
    val modifier = Modifier.padding(horizontal = 12.dp)
    if (onClick != null) {
        LinkText(text, style.copy(color = Color.White, textAlign = TextAlign.Center), modifier, onClick = onClick)
    } else {
        Text(text, style = style, color = Color.White, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
    }
}

/** The phone's 54dp side buttons: the page's foreground at 16%. */
@Composable
private fun HeaderCircle(icon: String, tooltip: String, onClick: () -> Unit, enabled: Boolean = true, size: Dp = 54.dp) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Tooltip(tooltip) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (hovered && enabled) 0.24f else 0.16f))
                .hoverable(interaction)
                .pressable(onClick, enabled = enabled, interaction = interaction, pressScale = 0.92f),
            contentAlignment = Alignment.Center,
        ) {
            ConvxIcon(icon, tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f), size = size * 0.48f)
        }
    }
}

/** The phone's Play pill: 140 by 50, white at 82%, lettered in black. */
@Composable
private fun PlayPill(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill = when {
        !enabled -> 0.4f
        hovered -> 0.92f
        else -> 0.82f
    }
    Row(
        Modifier
            .width(140.dp)
            .height(50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(Color.White.copy(alpha = fill))
            .hoverable(interaction)
            .pressable(onClick, enabled = enabled, interaction = interaction, pressScale = 0.95f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        ConvxIcon(if (playing) "pause_applemusic" else "play_applemusic", tint = Color.Black, size = 19.dp)
        Spacer(Modifier.width(7.dp))
        Text(
            if (playing) "Pause" else "Play",
            style = ConvxType.Body.copy(fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold),
            color = Color.Black,
        )
    }
}

/** Two lines at 48% white; a click opens the rest, as the phone's ExpandableText does. */
@Composable
private fun ExpandableDescription(text: String, modifier: Modifier = Modifier) {
    var expanded by remember(text) { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text,
            style = ConvxType.Body.copy(lineHeight = 21.sp),
            color = Metadata,
            textAlign = TextAlign.Center,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.pressable({ expanded = !expanded }, pressScale = 1f),
        )
        Text(
            if (expanded) "LESS" else "MORE",
            style = ConvxType.CaptionStrong,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp).pressable({ expanded = !expanded }, pressScale = 1f),
        )
    }
}

/**
 * A numbered list of songs; clicking one plays the whole list from there. Rows are parted
 * by the phone's hairline rules, inset to the title. Column labels only appear when there
 * is a [trailingLabel] column to explain.
 */
fun LazyListScope.songTable(
    songs: List<Song>,
    queueTitle: String,
    keyPrefix: String,
    showAlbum: Boolean = true,
    showArtwork: Boolean = true,
    trailingLabel: String? = null,
    trailing: ((Int) -> String?)? = null,
    menuExtra: (@Composable (index: Int, song: Song, dismiss: () -> Unit) -> Unit)? = null,
    /** Lets the rows be dragged into another order; their keys are then [songKey]s, so each song must be in once. */
    reorder: ListReorder? = null,
) {
    // Playlists of uploads and videos often carry no albums at all; don't show an empty column.
    @Suppress("NAME_SHADOWING")
    val showAlbum = showAlbum && songs.any { it.album != null }
    if (trailingLabel != null) {
        item(key = "$keyPrefix-header") {
            SongTableHeader(showAlbum = showAlbum, trailingLabel = trailingLabel, modifier = Modifier.padding(horizontal = Gutter))
        }
    }
    // Row padding, then the number column, then the artwork if any: the rule starts under the title.
    val ruleInset = 12.dp + 48.dp + if (showArtwork) 54.dp else 0.dp
    itemsIndexed(songs, key = { i, s -> if (reorder != null) songKey(keyPrefix, s) else "$keyPrefix-$i-${s.id}" }) { i, song ->
        val held = reorder != null && reorder.held == songKey(keyPrefix, song)
        Box(
            Modifier
                .then(
                    if (reorder == null) {
                        Modifier
                    } else {
                        Modifier
                            .animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = null,
                                placementSpec = if (reorder.active && !held) spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold) else null,
                            )
                            .zIndex(if (held) 1f else 0f)
                            .graphicsLayer { translationY = reorder.shift(songKey(keyPrefix, song)) }
                    },
                )
                .padding(horizontal = Gutter - 12.dp)
                .then(if (held) Modifier.shadow(12.dp, RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2D), RoundedCornerShape(8.dp)) else Modifier)
                .drawWithContent {
                    drawContent()
                    val start = ruleInset.toPx()
                    val end = size.width - 12.dp.toPx()
                    val stroke = 0.5.dp.toPx()
                    // The column labels already rule off the top when they're shown.
                    if (i == 0 && trailingLabel == null) drawLine(Rule, Offset(start, stroke / 2), Offset(end, stroke / 2), stroke)
                    drawLine(Rule, Offset(start, size.height - stroke / 2), Offset(end, size.height - stroke / 2), stroke)
                },
        ) {
            SongRow(
                song,
                { PlayerController.playSongs(songs, i, queueTitle) },
                index = i + 1,
                showArtwork = showArtwork,
                showAlbum = showAlbum,
                trailingText = trailing?.invoke(i),
                menuExtra = if (menuExtra == null) null else ({ dismiss -> menuExtra(i, song, dismiss) }),
            )
        }
    }
}

/** A song row's key in a [songTable] that can be reordered. */
fun songKey(keyPrefix: String, song: Song) = "$keyPrefix-${song.id}"

/** The phone's list separator on a tinted page. */
private val Rule = Color.White.copy(alpha = 0.12f)

private fun List<Song>.totalLine(): List<String> {
    val seconds = sumOf { (it.duration ?: 0).toLong() }
    return listOfNotNull(pluralize(size, "song"), if (seconds > 0) formatTotal(seconds) else null)
}

@Composable
fun AlbumScreen(browseId: String) {
    val navigator = LocalNavigator.current
    val library by Library.state.collectAsState()
    val playback = com.convx.desktop.ui.components.LocalPlayback.current
    val page = rememberPage("album:$browseId") { YouTube.album(browseId).getOrThrow() }
    // Convx keeps its own black whatever the cover (the user's call); the art carries the colour.
    val pageColor = PageBlack
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize().background(pageColor)) {
        PageContent(page, Modifier.padding(top = LocalPageInsets.current.top)) { album ->
            val songs = remember(album) { album.songs.map { it.toSong() } }
            val saved = library.albums.any { it.browseId == browseId }
            val isThisPlaying = playback.isPlaying && songs.any { it.id == playback.currentId }
            val artists = album.album.artists.orEmpty()
            PageList(state = listState, tint = pageColor, topPadding = 0.dp) {
                collectionHeader(
                    title = album.album.title,
                    art = { size -> Artwork(album.album.thumbnail, Modifier.size(size), size = 600, shape = RoundedCornerShape(12.dp), iconSize = 64.dp) },
                    metadata = (listOfNotNull(album.album.year?.toString()) + songs.totalLine()).joinToString(" · "),
                    playing = isThisPlaying,
                    enabled = songs.isNotEmpty(),
                    onPlay = { if (isThisPlaying) PlayerController.pause() else PlayerController.playSongs(songs, 0, album.album.title, shuffle = false) },
                    onShuffle = { PlayerController.playSongs(songs, 0, album.album.title, shuffle = true) },
                    wordmark = artists.joinToString { it.name }.ifBlank { null },
                    onWordmarkClick = artists.firstOrNull { it.id != null }?.let { artist -> { navigator.navigate(Screen.Artist(artist.id!!)) } },
                    description = album.description,
                    download = songs,
                    menu = { dismiss ->
                        CollectionMenuContent(
                            songs = { songs },
                            dismiss = dismiss,
                            saved = saved,
                            onToggleSaved = {
                                Library.toggleAlbum(album.album.toSaved())
                                Overlays.toast(if (saved) "Removed from Your Library" else "Saved to Your Library")
                            },
                            link = "https://music.youtube.com/browse/$browseId",
                        )
                    },
                )
                songTable(songs, album.album.title, "album", showAlbum = false, showArtwork = false)
                if (album.otherVersions.isNotEmpty()) {
                    item(key = "versions") {
                        Spacer(Modifier.height(16.dp))
                        ItemShelf("Other Versions", album.otherVersions)
                    }
                }
                if (album.releasesForYou.isNotEmpty()) {
                    item(key = "foryou") {
                        Spacer(Modifier.height(16.dp))
                        ItemShelf("Releases for You", album.releasesForYou)
                    }
                }
            }
        }
    }
}

/**
 * The phone sets YouTube playlists over a slowly drifting mesh spun from the cover, held
 * still behind the scrolling list, rather than a flat tint.
 */
@Composable
fun PlaylistScreen(id: String) {
    val navigator = LocalNavigator.current
    val library by Library.state.collectAsState()
    val playback = com.convx.desktop.ui.components.LocalPlayback.current
    val playlistId = id.removePrefix("VL")
    val page = rememberPage("playlist:$playlistId") { YouTube.playlist(playlistId).getOrThrow() }
    val barTint = PageBlack
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var loadingMore by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(PageBlack).clipToBounds()) {
        PageContent(page, Modifier.padding(top = LocalPageInsets.current.top)) { data ->
            val songs = remember(data) { data.songs.map { it.toSong() } }
            val playlist = data.playlist
            val saved = library.playlists.any { it.id == playlist.id }
            val isThisPlaying = playback.isPlaying && songs.any { it.id == playback.currentId }
            val complete = data.songsContinuation == null
            PageList(
                state = listState,
                tint = barTint,
                topPadding = 0.dp,
                onNearEnd = {
                    val continuation = page.value?.songsContinuation ?: return@PageList
                    if (loadingMore) return@PageList
                    loadingMore = true
                    scope.launch {
                        val more = withContext(Dispatchers.IO) { YouTube.playlistContinuation(continuation).getOrNull() }
                        if (more != null) page.update { it.copy(songs = it.songs + more.songs, songsContinuation = more.continuation) }
                        loadingMore = false
                    }
                },
            ) {
                collectionHeader(
                    title = playlist.title,
                    art = { size -> Artwork(playlist.thumbnail, Modifier.size(size), size = 600, shape = RoundedCornerShape(12.dp), iconSize = 64.dp) },
                    metadata = listOfNotNull(
                        playlist.songCountText ?: pluralize(songs.size, "song"),
                        if (complete) songs.totalLine().getOrNull(1) else null,
                    ).joinToString(" · "),
                    playing = isThisPlaying,
                    enabled = songs.isNotEmpty() || playlist.playEndpoint != null,
                    onPlay = {
                        when {
                            isThisPlaying -> PlayerController.pause()
                            songs.isNotEmpty() -> PlayerController.playSongs(songs, 0, playlist.title, shuffle = false)
                            playlist.playEndpoint != null -> PlayerController.playEndpoint(playlist.playEndpoint!!, playlist.title)
                        }
                    },
                    onShuffle = {
                        val shuffle = playlist.shuffleEndpoint
                        if (!complete && shuffle != null) PlayerController.playEndpoint(shuffle, playlist.title)
                        else PlayerController.playSongs(songs, 0, playlist.title, shuffle = true)
                    },
                    subtitle = playlist.author?.name,
                    onSubtitleClick = playlist.author?.id?.let { authorId -> { navigator.navigate(Screen.Artist(authorId)) } },
                    menu = { dismiss ->
                        CollectionMenuContent(
                            songs = { songs },
                            dismiss = dismiss,
                            saved = saved,
                            onToggleSaved = {
                                Library.togglePlaylist(playlist.toSaved())
                                Overlays.toast(if (saved) "Removed from Your Library" else "Saved to Your Library")
                            },
                            link = "https://music.youtube.com/playlist?list=$playlistId",
                            // A long playlist loads as it scrolls; Download fetches the rest first.
                            loadAll = if (complete) null else suspend {
                                while (loadingMore) kotlinx.coroutines.delay(100)
                                loadingMore = true
                                try {
                                    while (true) {
                                        val continuation = page.value?.songsContinuation ?: break
                                        val more = withContext(Dispatchers.IO) { YouTube.playlistContinuation(continuation).getOrNull() } ?: break
                                        page.update { it.copy(songs = it.songs + more.songs, songsContinuation = more.continuation) }
                                    }
                                } finally {
                                    loadingMore = false
                                }
                                page.value?.songs.orEmpty().map { it.toSong() }
                            },
                            extra = { close ->
                                MenuItem("Start radio", "radio") {
                                    com.convx.desktop.ui.components.ItemActions.radio(playlist)
                                    close()
                                }
                            },
                        )
                    },
                )
                if (songs.isEmpty()) {
                    item { MessageState("This playlist is empty", icon = "queue_music") }
                } else {
                    songTable(songs, playlist.title, "pl")
                }
                if (loadingMore) item { LoadingState() }
                data.related?.takeIf { it.isNotEmpty() }?.let { related ->
                    item(key = "related") {
                        Spacer(Modifier.height(20.dp))
                        ItemShelf("Related Playlists", related)
                    }
                }
            }
        }
    }
}

@Composable
fun LocalPlaylistScreen(id: String) {
    val navigator = LocalNavigator.current
    val library by Library.state.collectAsState()
    val playback = com.convx.desktop.ui.components.LocalPlayback.current
    val playlist = library.localPlaylists.firstOrNull { it.id == id }
    if (playlist == null) {
        Box(Modifier.padding(top = LocalPageInsets.current.top)) { MessageState("This playlist was deleted", icon = "delete") }
        return
    }
    val thumbs = remember(playlist.songs) { playlist.songs.mapNotNull { it.thumbnail }.distinct().take(4) }
    val pageColor = PageBlack
    // Drag a song to put the playlist in your own order, as on Spotify.
    val listState = rememberLazyListState()
    val insets = LocalPageInsets.current
    val reorder = rememberListReorder(listState, edgeTop = insets.top, edgeBottom = insets.bottom)
    val movable = remember(playlist.songs) { playlist.songs.distinctBy { it.id }.size == playlist.songs.size }
    SideEffect {
        reorder.bind(
            keys = { if (movable) playlist.songs.map { songKey("local", it) } else emptyList() },
            allowed = { _, keys -> keys.indices },
            onDrop = { keys -> Library.reorderPlaylist(id, keys.map { (it as String).removePrefix("local-") }) },
        )
    }
    LaunchedEffect(playlist.songs) { reorder.settled(playlist.songs.map { songKey("local", it) }) }
    val songs = reorder.arrange(playlist.songs) { songKey("local", it) }
    val isThisPlaying = playback.isPlaying && songs.any { it.id == playback.currentId }
    Box(Modifier.fillMaxSize().background(pageColor)) {
        PageList(state = listState, tint = pageColor, topPadding = 0.dp, listModifier = Modifier.reorderGestures(reorder)) {
            collectionHeader(
                title = playlist.name,
                art = { size -> com.convx.desktop.ui.components.EditablePlaylistCover(playlist, size, RoundedCornerShape(12.dp)) },
                metadata = (songs.totalLine() + ("Updated " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(playlist.updatedAt)))).joinToString(" · "),
                playing = isThisPlaying,
                enabled = songs.isNotEmpty(),
                onPlay = { if (isThisPlaying) PlayerController.pause() else if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, playlist.name, shuffle = false) },
                onShuffle = { if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, playlist.name, shuffle = true) },
                    menu = { dismiss ->
                    MenuItem("Rename", "edit") {
                        dismiss()
                        Overlays.prompt("Rename playlist", initial = playlist.name) { Library.renamePlaylist(id, it) }
                    }
                    MenuItem(if (playlist.cover == null) "Choose picture" else "Change picture", "image") {
                        dismiss()
                        com.convx.desktop.ui.components.choosePlaylistCover(id)
                    }
                    if (playlist.cover != null) {
                        MenuItem("Remove picture", "hide_image") {
                            dismiss()
                            Library.setPlaylistCover(id, null)
                        }
                    }
                    if (playlist.cover == null && AppSettings.value.discCovers) {
                        MenuItem("New cover design", "shuffle") {
                            dismiss()
                            Library.newCoverDesign(id)
                        }
                    }
                    MenuItem("Add to queue", "queue_music", enabled = songs.isNotEmpty()) {
                        PlayerController.addToQueue(songs)
                        Overlays.toast("Added to queue")
                        dismiss()
                    }
                    // The whole playlist for listening offline; songs already on this PC are skipped.
                    DownloadMenuItems(songs, dismiss)
                    MenuItem("Modify with AI", "sparks") {
                        dismiss()
                        editPlaylistWithAi(id)
                    }
                    MenuItem("Delete playlist", "delete", destructive = true) {
                        dismiss()
                        Overlays.confirm("Delete playlist?", "\"${playlist.name}\" will be removed from your library.", "Delete") {
                            navigator.back()
                            Library.deletePlaylist(id)
                        }
                    }
                },
            )
            if (songs.isEmpty()) {
                item {
                    MessageState(
                        "Let's find something for your playlist",
                        "Right-click any song and choose Add to playlist.",
                        icon = "playlist_add",
                        actionText = "Search",
                        onAction = { navigator.navigate(Screen.Search) },
                    )
                }
            } else {
                songTable(
                    songs,
                    playlist.name,
                    "local",
                    reorder = if (movable) reorder else null,
                    menuExtra = { i, song, dismiss ->
                        if (i > 0) {
                            MenuItem("Move up", "expand_less") {
                                Library.moveInPlaylist(id, i, i - 1)
                                dismiss()
                            }
                        }
                        if (i < songs.lastIndex) {
                            MenuItem("Move down", "expand_more") {
                                Library.moveInPlaylist(id, i, i + 1)
                                dismiss()
                            }
                        }
                        MenuItem("Remove from this playlist", "remove", destructive = true) {
                            Library.removeFromPlaylist(id, song.id)
                            dismiss()
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun LikedScreen() {
    val navigator = LocalNavigator.current
    val library by Library.state.collectAsState()
    val playback = com.convx.desktop.ui.components.LocalPlayback.current
    val songs = remember(library.liked) { library.liked.map { it.song } }
    val tint = PageBlack
    val isThisPlaying = playback.isPlaying && songs.any { it.id == playback.currentId }
    val dates = remember(library.liked) { library.liked.map { it.likedAt } }
    Box(Modifier.fillMaxSize().background(tint)) {
        PageList(state = rememberLazyListState(), tint = tint, topPadding = 0.dp) {
            collectionHeader(
                title = "Liked Songs",
                // Click (or hover) the cover to give Liked Songs your own picture, as with a playlist.
                art = { size ->
                    val shape = RoundedCornerShape(12.dp)
                    com.convx.desktop.ui.components.EditableCover(size, shape, onChoose = { com.convx.desktop.ui.components.chooseLikedSongsCover() }) {
                        com.convx.desktop.ui.components.LikedSongsArt(size, shape)
                    }
                },
                metadata = songs.totalLine().joinToString(" · "),
                playing = isThisPlaying,
                enabled = songs.isNotEmpty(),
                onPlay = { if (isThisPlaying) PlayerController.pause() else if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, "Liked Songs", shuffle = false) },
                onShuffle = { if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, "Liked Songs", shuffle = true) },
                download = songs,
            )
            if (songs.isEmpty()) {
                item {
                    MessageState(
                        "Songs you like will appear here",
                        "Save songs by clicking the heart.",
                        icon = "favorite_border",
                        actionText = "Find songs",
                        onAction = { navigator.navigate(Screen.Search) },
                    )
                }
            } else {
                songTable(songs, "Liked Songs", "liked", trailingLabel = "Date added", trailing = { i -> dates.getOrNull(i)?.let { relativeDate(it) } })
            }
        }
    }
}

/** The phone's "Local" list: every music file the scan found on this PC. */
@Composable
fun LocalFilesScreen() {
    val navigator = LocalNavigator.current
    val index by com.convx.desktop.player.LocalMusic.index.collectAsState()
    val status by com.convx.desktop.player.LocalMusic.status.collectAsState()
    val playback = com.convx.desktop.ui.components.LocalPlayback.current
    val songs = remember(index) { com.convx.desktop.player.LocalMusic.songs(index) }
    val albums = remember(index) { songs.map { index.tracks[it.id]?.album } }
    val tint = PageBlack
    val isThisPlaying = playback.isPlaying && songs.any { it.id == playback.currentId }
    fun openFolders() {
        navigator.switchTo(Screen.Settings)
        com.convx.desktop.ui.screens.settings.SettingsNav.push(com.convx.desktop.ui.screens.settings.SettingsPage.LocalFolders)
    }
    Box(Modifier.fillMaxSize().background(tint)) {
        PageList(state = rememberLazyListState(), tint = tint, topPadding = 0.dp) {
            collectionHeader(
                title = "Local files",
                art = { size -> GradientTile("storage", size, com.convx.desktop.ui.shell.LocalFilesGradient, RoundedCornerShape(12.dp)) },
                metadata = (songs.totalLine() + listOfNotNull((status as? com.convx.desktop.player.LocalMusic.Status.Scanning)?.let { "Scanning…" }))
                    .joinToString(" · "),
                playing = isThisPlaying,
                enabled = songs.isNotEmpty(),
                onPlay = { if (isThisPlaying) PlayerController.pause() else if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, "Local files", shuffle = false) },
                onShuffle = { if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, "Local files", shuffle = true) },
            )
            if (songs.isEmpty()) {
                item {
                    MessageState(
                        "No music files yet",
                        "Choose the folders with your music, and Convx plays the files from there.",
                        icon = "storage",
                        actionText = "Choose folders",
                        onAction = { openFolders() },
                    )
                }
            } else {
                songTable(
                    songs,
                    "Local files",
                    "local",
                    trailingLabel = "Album",
                    trailing = { i -> albums.getOrNull(i) },
                )
            }
        }
    }
}

/** The phone's "Downloaded" list: songs kept on this PC, which play with no connection. */
@Composable
fun DownloadsScreen() {
    val done by Downloads.done.collectAsState()
    val active by Downloads.active.collectAsState()
    val playback = com.convx.desktop.ui.components.LocalPlayback.current
    val entries = remember(done) { done.values.sortedByDescending { it.at } }
    val songs = remember(entries) { entries.map { it.song } }
    val pending = remember(active) { active.values.toList() }
    val bytes = remember(done) { done.values.sumOf { it.bytes } }
    val tint = PageBlack
    val isThisPlaying = playback.isPlaying && songs.any { it.id == playback.currentId }
    Box(Modifier.fillMaxSize().background(tint)) {
        PageList(state = rememberLazyListState(), tint = tint, topPadding = 0.dp) {
            collectionHeader(
                title = "Downloaded",
                art = { size -> GradientTile("download", size, com.convx.desktop.ui.shell.DownloadsGradient, RoundedCornerShape(12.dp)) },
                metadata = (songs.totalLine() + listOfNotNull(bytes.takeIf { it > 0 }?.let { com.convx.desktop.ui.screens.settings.formatBytes(it) }))
                    .joinToString(" · "),
                playing = isThisPlaying,
                enabled = songs.isNotEmpty(),
                onPlay = { if (isThisPlaying) PlayerController.pause() else if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, "Downloaded", shuffle = false) },
                onShuffle = { if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, "Downloaded", shuffle = true) },
                menu = { dismiss -> CollectionMenuContent(songs = { songs }, dismiss = dismiss) },
            )
            if (pending.isNotEmpty()) {
                item(key = "pending-title") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Downloading", style = ConvxType.Title3, color = Color.White, modifier = Modifier.weight(1f))
                        com.convx.desktop.ui.components.PillButton("Stop all", { Downloads.cancel(active.keys.toList()) })
                    }
                }
                songTable(
                    pending.map { it.song },
                    "Downloading",
                    "pending",
                    showAlbum = false,
                    trailingLabel = "Status",
                    trailing = { i ->
                        when (val status = pending.getOrNull(i)?.status) {
                            Downloads.Status.Queued -> "Waiting"
                            is Downloads.Status.Running -> "${(status.fraction * 100).toInt()}%"
                            is Downloads.Status.Failed -> "Failed"
                            null -> null
                        }
                    },
                )
                item(key = "pending-gap") { Spacer(Modifier.height(24.dp)) }
            }
            if (songs.isEmpty()) {
                if (pending.isEmpty()) {
                    item {
                        MessageState(
                            "No downloads yet",
                            "Download songs, albums and playlists from their menus, and they play here without a connection.",
                            icon = "download",
                        )
                    }
                }
            } else {
                songTable(songs, "Downloaded", "downloaded", trailingLabel = "Downloaded", trailing = { i -> entries.getOrNull(i)?.let { relativeDate(it.at) } })
            }
        }
    }
}

/** My top's colours, the phone's trending orange. */
val TopGradient = listOf(Color(0xFFFF9F0A), Color(0xFFB2361B))

/** Cached's colours. */
val CachedGradient = listOf(Color(0xFF64D2FF), Color(0xFF5E5CE6))

/** Uploaded's colours, around the phone's purple for it. */
val UploadedGradient = listOf(Color(0xFFBF5AF2), Color(0xFF7D2AE8))

/** The AI DJ Mix card's colours. */
val AiDjGradient = listOf(Color(0xFFBF5AF2), Color(0xFF5E5CE6))

/** The periods the phone's My top can be sorted by, under its names for them. */
enum class TopFilter(val label: String) {
    AllTime("All time"),
    Day("Past 24 hours"),
    Week("Past week"),
    Month("Past month"),
    Year("Past year"),
    ;

    fun since(): Long {
        val now = java.time.ZonedDateTime.now()
        val start = when (this) {
            AllTime -> return 0L
            Day -> now.minusDays(1)
            Week -> now.minusWeeks(1)
            Month -> now.minusMonths(1)
            Year -> now.minusYears(1)
        }
        return start.toInstant().toEpochMilli()
    }
}

/** My top's songs: the most listened to over [filter], as many as Settings asks for. */
fun myTopSongs(filter: TopFilter, settings: com.convx.desktop.data.Settings = AppSettings.value): List<Library.Ranked<Song>> =
    Library.listeningStats(filter.since()).songs
        .filter { !settings.hideVideos || !it.value.isVideo }
        .take(settings.topSize)

/** Songs kept whole in the audio cache, newest first, as the library knows them; ones it doesn't are left out. */
fun cachedSongs(
    library: com.convx.desktop.data.LibraryData = Library.value,
    downloaded: Collection<com.convx.desktop.player.DownloadedSong> = Downloads.done.value.values,
): List<Song> {
    val known = HashMap<String, Song>()
    library.localPlaylists.forEach { playlist -> playlist.songs.forEach { known[it.id] = it } }
    library.liked.forEach { known[it.song.id] = it.song }
    downloaded.forEach { known[it.song.id] = it.song }
    // Oldest first, so the newest copy of a song's details wins.
    library.history.asReversed().forEach { known[it.song.id] = it.song }
    PlayerController.state.value.queue.forEach { known.putIfAbsent(it.id, it) }
    return com.convx.desktop.player.AudioCache.cachedIds().mapNotNull { known[it] }
}

/**
 * The phone's "My top" auto playlist: your most played songs, as many as its length setting
 * says, over all time or a recent period picked from its sort menu.
 */
@Composable
fun MyTopScreen() {
    val library by Library.state.collectAsState()
    val settings by AppSettings.state.collectAsState()
    var filter by remember { mutableStateOf(TopFilter.AllTime) }
    val ranked = remember(library.history, filter, settings.topSize, settings.hideVideos) { myTopSongs(filter, settings) }
    val songs = remember(ranked) { ranked.map { it.value } }
    val title = "My top ${settings.topSize}"
    val playback = com.convx.desktop.ui.components.LocalPlayback.current
    val isThisPlaying = playback.isPlaying && songs.any { it.id == playback.currentId }
    Box(Modifier.fillMaxSize().background(PageBlack)) {
        PageList(state = rememberLazyListState(), tint = PageBlack, topPadding = 0.dp) {
            collectionHeader(
                title = title,
                art = { size -> GradientTile("trending_up", size, TopGradient, RoundedCornerShape(12.dp)) },
                metadata = (listOf(filter.label) + songs.totalLine()).joinToString(" · "),
                playing = isThisPlaying,
                enabled = songs.isNotEmpty(),
                onPlay = { if (isThisPlaying) PlayerController.pause() else if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, title, shuffle = false) },
                onShuffle = { if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, title, shuffle = true) },
                menu = { dismiss -> CollectionMenuContent(songs = { songs }, dismiss = dismiss) },
            )
            item(key = "filter") {
                // The phone's sort header, with its periods in place of sort orders.
                val menu = rememberMenuAnchor()
                Box(Modifier.padding(start = Gutter, bottom = 8.dp)) {
                    com.convx.desktop.ui.components.PillButton(
                        filter.label,
                        {
                            menu.offset = androidx.compose.ui.unit.DpOffset(0.dp, 38.dp)
                            menu.expanded = true
                        },
                        icon = "expand_more",
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    )
                    AppMenu(menu) {
                        TopFilter.entries.forEach { entry ->
                            MenuItem(entry.label, if (entry == filter) "check" else null) {
                                menu.expanded = false
                                filter = entry
                            }
                        }
                    }
                }
            }
            if (songs.isEmpty()) {
                item(key = "empty") { MessageState("This playlist is empty", "Songs you play rank here by how long you listen.", icon = "trending_up") }
            } else {
                songTable(songs, title, "top", trailingLabel = "Played", trailing = { i -> ranked.getOrNull(i)?.let { statsLine(it.plays, it.playedMs) } })
            }
        }
    }
}

/** The phone's "Cached" auto playlist: songs kept on this PC from playing them, which start at once. */
@Composable
fun CachedScreen() {
    val library by Library.state.collectAsState()
    val done by Downloads.done.collectAsState()
    val songs = remember(library, done) { cachedSongs(library, done.values) }
    val bytes = remember(songs) { com.convx.desktop.player.AudioCache.sizeBytes() }
    val playback = com.convx.desktop.ui.components.LocalPlayback.current
    val isThisPlaying = playback.isPlaying && songs.any { it.id == playback.currentId }
    Box(Modifier.fillMaxSize().background(PageBlack)) {
        PageList(state = rememberLazyListState(), tint = PageBlack, topPadding = 0.dp) {
            collectionHeader(
                title = "Cached",
                art = { size -> GradientTile("cached", size, CachedGradient, RoundedCornerShape(12.dp)) },
                metadata = (songs.totalLine() + listOfNotNull(bytes.takeIf { it > 0 }?.let { com.convx.desktop.ui.screens.settings.formatBytes(it) }))
                    .joinToString(" · "),
                playing = isThisPlaying,
                enabled = songs.isNotEmpty(),
                onPlay = { if (isThisPlaying) PlayerController.pause() else if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, "Cached", shuffle = false) },
                onShuffle = { if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, "Cached", shuffle = true) },
                menu = { dismiss -> CollectionMenuContent(songs = { songs }, dismiss = dismiss) },
            )
            if (songs.isEmpty()) {
                item(key = "empty") { MessageState("Nothing cached yet", "Songs you play are kept here, so they start at once next time.", icon = "cached") }
            } else {
                songTable(songs, "Cached", "cached")
            }
        }
    }
}

/**
 * The phone's "Uploaded" auto playlist: songs you uploaded to YouTube Music, refreshed from the
 * account each time it's opened, as the phone does.
 */
@Composable
fun UploadedScreen() {
    val library by Library.state.collectAsState()
    val account by com.convx.desktop.integrations.youtube.YouTubeAccount.state.collectAsState()
    val signedIn = account is com.convx.desktop.integrations.youtube.YouTubeAccount.State.SignedIn
    val songs = library.uploaded
    var refreshing by remember { mutableStateOf(signedIn) }
    androidx.compose.runtime.LaunchedEffect(signedIn) {
        if (signedIn) {
            refreshing = true
            com.convx.desktop.integrations.youtube.YouTubeSync.refreshUploads()
        }
        refreshing = false
    }
    val navigator = LocalNavigator.current
    val playback = com.convx.desktop.ui.components.LocalPlayback.current
    val isThisPlaying = playback.isPlaying && songs.any { it.id == playback.currentId }
    Box(Modifier.fillMaxSize().background(PageBlack)) {
        PageList(state = rememberLazyListState(), tint = PageBlack, topPadding = 0.dp) {
            collectionHeader(
                title = "Uploaded",
                art = { size -> GradientTile("cloud", size, UploadedGradient, RoundedCornerShape(12.dp)) },
                metadata = (listOf("YouTube Music") + songs.totalLine()).joinToString(" · "),
                playing = isThisPlaying,
                enabled = songs.isNotEmpty(),
                onPlay = { if (isThisPlaying) PlayerController.pause() else if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, "Uploaded", shuffle = false) },
                onShuffle = { if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, "Uploaded", shuffle = true) },
                menu = { dismiss -> CollectionMenuContent(songs = { songs }, dismiss = dismiss) },
            )
            when {
                songs.isNotEmpty() -> songTable(songs, "Uploaded", "uploaded")
                !signedIn -> item(key = "signed-out") {
                    MessageState(
                        "Sign in to see your uploads",
                        "Songs you upload to YouTube Music show up here once you sign in.",
                        icon = "cloud",
                        actionText = "Sign in",
                        onAction = {
                            navigator.navigate(Screen.Settings)
                            com.convx.desktop.ui.screens.settings.SettingsNav.push(com.convx.desktop.ui.screens.settings.SettingsPage.YouTubeMusic)
                        },
                    )
                }
                refreshing -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f), strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
                    }
                }
                else -> item(key = "empty") {
                    MessageState("No uploads", "Songs you upload at music.youtube.com show up here.", icon = "cloud")
                }
            }
        }
    }
}

@Composable
fun HistoryScreen() {
    val library by Library.state.collectAsState()
    val events = library.history
    val songs = remember(events) { events.map { it.song } }
    PageList(state = rememberLazyListState()) {
        item(key = "title") {
            Row(Modifier.padding(horizontal = Gutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recently played", style = ConvxType.LargeTitle, modifier = Modifier.weight(1f))
                if (events.isNotEmpty()) {
                    com.convx.desktop.ui.components.PillButton("Clear history", {
                        Overlays.confirm("Clear listening history?", "This also resets Stats.", "Clear") { Library.clearHistory() }
                    })
                }
            }
        }
        if (events.isEmpty()) {
            item { MessageState("Nothing played yet", "Songs you play show up here.", icon = "history") }
        } else {
            songTable(
                songs.take(500),
                "Recently played",
                "history",
                trailingLabel = "Played",
                trailing = { i -> events.getOrNull(i)?.let { relativeDate(it.at) } },
                menuExtra = { i, _, dismiss ->
                    MenuItem("Remove from history", "remove") {
                        events.getOrNull(i)?.let { Library.removeFromHistory(it.at) }
                        dismiss()
                    }
                },
            )
        }
    }
}

/** "Today", "Yesterday", "3 days ago", or a date. */
fun relativeDate(millis: Long): String {
    val days = ((System.currentTimeMillis() - millis) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "Today"
        days == 1 -> "Yesterday"
        days < 7 -> "$days days ago"
        days < 28 -> "${days / 7} week${if (days / 7 == 1) "" else "s"} ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
    }
}
