/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.Song
import com.convx.desktop.data.toItem
import com.convx.desktop.player.Downloads
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.glass.InteractiveHighlight
import com.convx.desktop.ui.glass.LocalMenuBackdrop
import com.convx.desktop.ui.glass.MenuGlass
import com.convx.desktop.ui.glass.liquidGlass
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import kotlinx.coroutines.launch

/** Where a menu opens: under its button, or at the right-click point. */
class MenuAnchor {
    var expanded by mutableStateOf(false)
    var offset by mutableStateOf(DpOffset.Zero)
}

@Composable
fun rememberMenuAnchor() = remember { MenuAnchor() }

/** Opens [anchor] at a right-click [position] (px, relative to the clicked element). */
@Composable
fun MenuAnchor.openAtProvider(): (Offset) -> Unit {
    val density = LocalDensity.current
    return { position ->
        with(density) { offset = DpOffset(position.x.toDp(), position.y.toDp()) }
        expanded = true
    }
}

@Composable
fun AppMenu(anchor: MenuAnchor, content: @Composable () -> Unit) {
    // The phone's menus are liquid glass over the page, lit where the pointer is.
    val backdrop = LocalMenuBackdrop.current
    // The phone's sheet corner radius, which its menus are drawn on.
    val corner = AppSettings.state.collectAsState().value.menuCorner
    val shape = if (corner >= 0) RoundedCornerShape(corner.dp) else MenuShape
    val scope = rememberCoroutineScope()
    val glow = remember(scope) { InteractiveHighlight(scope, radiusScale = 0.6f, sheenAlpha = 0.06f, spotAlpha = 0.32f) }
    val glass = if (backdrop != null) {
        Modifier
            .liquidGlass(shape, MenuGlass, highlightAlpha = 0.20f, outerShadow = false, backdrop = backdrop)
            .then(glow.modifier)
            .then(glow.gestureModifier)
    } else {
        Modifier
    }
    // A zero-size box at the click point gives the popup something to hang from.
    Box(Modifier.offset(anchor.offset.x, anchor.offset.y).size(0.dp)) {
        DropdownMenu(
            expanded = anchor.expanded,
            onDismissRequest = { anchor.expanded = false },
            modifier = glass.widthIn(min = 220.dp).heightIn(max = 560.dp),
            shape = shape,
            containerColor = if (backdrop != null) Color.Transparent else Color(0xFF2C2C2E),
            tonalElevation = 0.dp,
            shadowElevation = 16.dp,
            border = if (backdrop != null) null else BorderStroke(0.5.dp, ConvxColors.Separator),
        ) {
            content()
        }
    }
}

/** The phone's menus round off at 32 on a 270-wide panel; a desktop menu is smaller. */
private val MenuShape = RoundedCornerShape(20.dp)

@Composable
fun MenuItem(text: String, icon: String? = null, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val color = when {
        !enabled -> ConvxColors.TertiaryLabel
        destructive -> Color(0xFFFF453A)
        else -> Color.White
    }
    DropdownMenuItem(
        text = { Text(text, style = ConvxType.Body, color = color) },
        leadingIcon = icon?.let { { ConvxIcon(it, tint = color, size = 18.dp) } },
        enabled = enabled,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp),
    )
}

@Composable
fun MenuDivider() = HorizontalDivider(color = ConvxColors.Separator, thickness = 0.5.dp)

/** Everything you can do with one song — the phone's song sheet, as a desktop menu. */
@Composable
fun SongMenuContent(
    song: Song,
    dismiss: () -> Unit,
    extra: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
) {
    val navigator = LocalNavigator.current
    val clipboard = LocalClipboardManager.current
    val library by Library.state.collectAsState()
    val liked = library.liked.any { it.song.id == song.id }
    var choosingPlaylist by remember { mutableStateOf(false) }

    if (choosingPlaylist) {
        MenuItem("New playlist", "add") {
            dismiss()
            Overlays.prompt("New playlist", confirm = "Create") { name ->
                Library.createPlaylist(name, listOf(song))
                Overlays.toast("Added to $name")
            }
        }
        MenuDivider()
        for (playlist in library.localPlaylists) {
            MenuItem(playlist.name, "queue_music") {
                Library.addToPlaylist(playlist.id, listOf(song))
                Overlays.toast("Added to ${playlist.name}")
                dismiss()
            }
        }
        return
    }

    // In a party where the host holds the controls these say why instead, not "Playing next".
    MenuItem("Play next", "playlist_play") {
        if (PlayerController.playNext(listOf(song)) != null) Overlays.toast("Playing next")
        dismiss()
    }
    MenuItem("Add to queue", "queue_music") {
        if (PlayerController.addToQueue(listOf(song)) != null) Overlays.toast("Added to queue")
        dismiss()
    }
    val local = com.convx.desktop.player.LocalMusic.isLocal(song.id)
    if (!local) {
        MenuItem("Start radio", "radio") {
            PlayerController.startRadio(song)
            dismiss()
        }
    }
    // The phone's two AI actions on a song, through the provider set up for translation.
    MenuItem("AI DJ Mix from this song", "sparks") {
        navigator.navigate(Screen.AiDj(seed = song))
        dismiss()
    }
    MenuItem("Track insights", "info") {
        dismiss()
        com.convx.desktop.ui.screens.trackInsights(song)
    }
    MenuDivider()
    MenuItem(if (liked) "Remove from Liked Songs" else "Add to Liked Songs", if (liked) "favorite" else "favorite_border") {
        Library.toggleLike(song)
        Overlays.toast(if (liked) "Removed from Liked Songs" else "Added to Liked Songs", if (liked) "favorite_border" else "favorite")
        dismiss()
    }
    MenuItem("Add to playlist", "playlist_add") { choosingPlaylist = true }
    if (!local) SpeedDialMenuItem(song.toItem(), library.speedDial.any { it.id == song.id }, dismiss)
    DownloadMenuItems(listOf(song), dismiss)
    MenuDivider()
    song.artists.firstOrNull { it.id != null }?.let { artist ->
        MenuItem("Go to artist", "artist") {
            navigator.navigate(Screen.Artist(artist.id!!))
            dismiss()
        }
    }
    song.album?.let { album ->
        MenuItem("Go to album", "album") {
            navigator.navigate(Screen.Album(album.id))
            dismiss()
        }
    }
    if (local) {
        MenuItem("Show in folder", "storage") {
            com.convx.desktop.player.LocalMusic.track(song.id)?.let { track ->
                runCatching { ProcessBuilder("explorer.exe", "/select,", track.path).start() }
                    .onFailure { Overlays.toast("Couldn't open the folder", "error") }
            }
            dismiss()
        }
    } else {
        MenuItem("Copy link", "link") {
            clipboard.setText(AnnotatedString("https://music.youtube.com/watch?v=${song.id}"))
            Overlays.toast("Link copied", "link")
            dismiss()
        }
    }
    if (extra != null) {
        MenuDivider()
        extra(dismiss)
    }
}

/** Menu for a whole collection: album, playlist, mix. */
@Composable
fun CollectionMenuContent(
    songs: () -> List<Song>,
    dismiss: () -> Unit,
    saved: Boolean? = null,
    onToggleSaved: (() -> Unit)? = null,
    link: String? = null,
    extra: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
    /** For a list only partly loaded: fetches the rest, so Download takes the whole of it. */
    loadAll: (suspend () -> List<Song>)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val library by Library.state.collectAsState()
    var choosingPlaylist by remember { mutableStateOf(false) }
    if (choosingPlaylist) {
        MenuItem("New playlist", "add") {
            dismiss()
            Overlays.prompt("New playlist", confirm = "Create") { name ->
                Library.createPlaylist(name, songs())
                Overlays.toast("Added to $name")
            }
        }
        MenuDivider()
        for (playlist in library.localPlaylists) {
            MenuItem(playlist.name, "queue_music") {
                Library.addToPlaylist(playlist.id, songs())
                Overlays.toast("Added to ${playlist.name}")
                dismiss()
            }
        }
        return
    }
    MenuItem("Shuffle", "shuffle") {
        PlayerController.playSongs(songs(), shuffle = true)
        dismiss()
    }
    MenuItem("Play next", "playlist_play") {
        if (PlayerController.playNext(songs()) != null) Overlays.toast("Playing next")
        dismiss()
    }
    MenuItem("Add to queue", "queue_music") {
        if (PlayerController.addToQueue(songs()) != null) Overlays.toast("Added to queue")
        dismiss()
    }
    MenuItem("Add to playlist", "playlist_add") { choosingPlaylist = true }
    DownloadMenuItems(songs(), dismiss, loadAll)
    if (saved != null && onToggleSaved != null) {
        MenuItem(if (saved) "Remove from Library" else "Save to Library", if (saved) "library_add_check" else "library_add") {
            onToggleSaved()
            dismiss()
        }
    }
    if (link != null) {
        MenuItem("Copy link", "link") {
            clipboard.setText(AnnotatedString(link))
            Overlays.toast("Link copied", "link")
            dismiss()
        }
    }
    if (extra != null) {
        MenuDivider()
        extra(dismiss)
    }
}

/**
 * The phone's Download, Downloading and Remove download entries, for one song or a whole list.
 * A list that's partly downloaded offers both: the rest, and removing what's there.
 */
@Composable
fun DownloadMenuItems(songs: List<Song>, dismiss: () -> Unit, loadAll: (suspend () -> List<Song>)? = null) {
    val done by Downloads.done.collectAsState()
    val active by Downloads.active.collectAsState()
    val wanted = remember(songs) { songs.filter(Downloads::canDownload) }
    if (wanted.isEmpty() && loadAll == null) return
    val single = songs.singleOrNull()
    val kept = wanted.filter { it.id in done }
    // The rest of a long playlist is fetched first, then every song not on this PC yet is downloaded.
    if (loadAll != null) {
        val running = Downloads.collective(wanted, done, active) as? Downloads.Collective.Running
        if (running == null) {
            MenuItem("Download", "download") {
                dismiss()
                Overlays.toast("Getting the whole playlist…", "download")
                com.convx.desktop.core.AppScope.launch {
                    val all = loadAll().filter(Downloads::canDownload)
                    val queued = Downloads.download(all)
                    Overlays.toast(
                        if (queued > 0) "Downloading $queued ${if (queued == 1) "song" else "songs"}" else "All ${all.size} songs are already downloaded",
                        "download",
                    )
                }
            }
        } else {
            MenuItem("Downloading ${running.done} of ${running.total}… Stop", "download") {
                Downloads.cancel(wanted.map { it.id })
                dismiss()
            }
        }
    } else when (val state = Downloads.collective(wanted, done, active)) {
        is Downloads.Collective.Running -> MenuItem(
            if (single != null) "Downloading… Stop" else "Downloading ${state.done} of ${state.total}… Stop",
            "download",
        ) {
            Downloads.cancel(wanted.map { it.id })
            dismiss()
        }
        Downloads.Collective.Done -> Unit
        Downloads.Collective.None -> {
            val failed = single != null && active[single.id]?.status is Downloads.Status.Failed
            MenuItem(if (failed) "Download failed. Try again" else "Download", "download") {
                val queued = Downloads.download(wanted)
                if (single == null && queued > 0) Overlays.toast("Downloading $queued ${if (queued == 1) "song" else "songs"}", "download")
                dismiss()
            }
        }
    }
    if (kept.isNotEmpty()) {
        MenuItem("Remove download", "delete") {
            dismiss()
            if (single != null) {
                Downloads.remove(listOf(single.id))
                Overlays.toast("Download removed", "delete")
            } else {
                Overlays.confirm(
                    "Remove download?",
                    "${kept.size} downloaded ${if (kept.size == 1) "song" else "songs"} will be removed from this PC. They stay in your library.",
                    "Remove",
                ) { Downloads.remove(kept.map { it.id }) }
            }
        }
    }
}
