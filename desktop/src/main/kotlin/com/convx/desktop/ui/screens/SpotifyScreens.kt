/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.AppScope
import com.convx.desktop.core.formatTotal
import com.convx.desktop.core.pluralize
import com.convx.desktop.data.Library
import com.convx.desktop.data.LocalPlaylist
import com.convx.desktop.data.Song
import com.convx.desktop.integrations.PlaylistImport
import com.convx.desktop.integrations.spotify.SpotifyApi
import com.convx.desktop.integrations.spotify.SpotifyMatcher
import com.convx.desktop.integrations.spotify.SpotifyPlaylist
import com.convx.desktop.integrations.spotify.SpotifyTrack
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.GradientTile
import com.convx.desktop.ui.components.Gutter
import com.convx.desktop.ui.components.LoadingState
import com.convx.desktop.ui.components.LocalPlayback
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.components.wornCover
import com.convx.desktop.ui.components.MessageState
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.studio.DiscCoverMenuItems
import com.convx.desktop.ui.studio.DiscTargets
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import kotlinx.coroutines.launch

/** Spotify's green, for the little marks that say where a list came from. */
val SpotifyGreen = Color(0xFF1ED760)

/** Liked Songs, where a Spotify playlist's id would go. */
private const val LikedKey = "liked"

/** The Spotify list (its id, or [LikedKey]) you asked to edit, so its page can say how that's going. */
private var editing by mutableStateOf<String?>(null)

/**
 * Makes [playlist] (null for Liked Songs) a Convx playlist you can change, as Settings' Spotify
 * import would: its songs matched on YouTube Music, into the copy an earlier import made if there
 * is one. Its Spotify page then opens as that copy. Spotify itself is left as it is.
 */
fun editSpotifyPlaylist(playlist: SpotifyPlaylist?) {
    if (PlaylistImport.busy) {
        Overlays.toast("Wait for the import that's running to finish", "error")
        return
    }
    editing = playlist?.id ?: LikedKey
    PlaylistImport.importSpotify(listOf(PlaylistImport.SpotifySource(playlist)))
}

/** [editSpotifyPlaylist] for the playlist with [id] (null for Liked Songs), looked up first. */
fun editSpotifyPlaylistById(id: String?) {
    if (id == null) return editSpotifyPlaylist(null)
    AppScope.launch {
        val playlist = SpotifyApi.playlists().getOrNull()?.firstOrNull { it.id == id }
        if (playlist == null) Overlays.toast("Couldn't load this playlist from Spotify", "error") else editSpotifyPlaylist(playlist)
    }
}

/**
 * "Add to playlist" on one of your Spotify playlists: [songs] go into its Convx copy, which is
 * made first (as Edit makes it) when there's none yet, and the list is then yours to edit here.
 * Spotify itself is left as it is.
 */
fun addToSpotifyPlaylist(playlist: SpotifyPlaylist, songs: List<Song>) {
    if (songs.isEmpty()) return
    PlaylistImport.copiesAmongYours(listOf(playlist)).first()?.let { copy ->
        Library.addToPlaylist(copy.id, songs)
        Overlays.toast("Added to ${copy.name}")
        return
    }
    val job = PlaylistImport.importSpotify(listOf(PlaylistImport.SpotifySource(playlist, add = songs)))
    if (job == null) {
        Overlays.toast("Wait for the import that's running to finish", "error")
        return
    }
    Overlays.toast("Adding to ${playlist.name}. Convx brings the playlist in from Spotify first, which takes a moment.", "spotify")
    AppScope.launch {
        job.join()
        when (val state = PlaylistImport.state.value) {
            is PlaylistImport.State.Done -> {
                PlaylistImport.dismiss()
                val name = state.playlistId?.let(Library::playlist)?.name ?: playlist.name
                val missing = if (state.skipped > 0) " ${pluralize(state.skipped, "song")} of it had no match on YouTube Music." else ""
                Overlays.toast("Added to $name. You can edit it in Convx now.$missing")
            }
            is PlaylistImport.State.Failed -> {
                PlaylistImport.dismiss()
                Overlays.toast(state.message, "error")
            }
            // Stopped: nothing was added.
            else -> Unit
        }
    }
}

/** One of your Spotify playlists, read from Spotify and played from YouTube Music, or the copy of it you can edit. */
@Composable
fun SpotifyPlaylistScreen(id: String) {
    val page = rememberPage("spotify-playlist-$id") {
        val all = SpotifyApi.playlists().getOrThrow()
        val tracks = SpotifyApi.playlistTracks(id).getOrThrow()
        all to tracks
    }
    val all = page.value?.first
    val playlist = all?.firstOrNull { it.id == id }
    val library by Library.state.collectAsState()
    // Found among all your lists, as the side bar finds it, so two with one name each keep their own.
    val copy = remember(all, library) {
        all?.let { lists -> PlaylistImport.copies(lists, library.localPlaylists).getOrNull(lists.indexOfFirst { it.id == id }) }
    }
    if (copy != null) return OpenCopy(Screen.SpotifyPlaylist(id), id, copy)
    val tracks = page.value?.second
    SpotifyTrackPage(
        title = playlist?.name ?: "Spotify playlist",
        subtitle = playlist?.owner,
        description = playlist?.description?.takeIf { it.isNotBlank() }?.let(::stripTags),
        art = { size ->
            // A disc design put on it shows instead of Spotify's picture.
            val picture = wornCover(DiscTargets.spotify(id)) ?: playlist?.image
            Artwork(picture, Modifier.size(size), size = 640, shape = RoundedCornerShape(12.dp), placeholderIcon = "spotify")
        },
        tracks = tracks,
        error = page.error,
        onRetry = {
            SpotifyApi.clearCache()
            page.retry()
        },
        keyPrefix = "spotify-$id",
        editKey = id,
        discKey = DiscTargets.spotify(id),
        onEdit = playlist?.let { { editSpotifyPlaylist(it) } },
    )
}

/** Your Liked Songs on Spotify, or the copy of them you can edit. */
@Composable
fun SpotifyLikedScreen() {
    val library by Library.state.collectAsState()
    val copy = remember(library) { PlaylistImport.copies(listOf(null), library.localPlaylists).first() }
    if (copy != null) return OpenCopy(Screen.SpotifyLiked, LikedKey, copy)
    val page = rememberPage("spotify-liked") { SpotifyApi.likedSongs().getOrThrow() }
    SpotifyTrackPage(
        title = "Liked Songs",
        subtitle = "Spotify",
        description = null,
        art = { size ->
            val disc = wornCover(DiscTargets.spotify(null))
            if (disc != null) {
                Artwork(disc, Modifier.size(size), size = 640, shape = RoundedCornerShape(12.dp))
            } else {
                GradientTile("favorite", size, listOf(Color(0xFF450AF5), Color(0xFF8E8EE5)), RoundedCornerShape(12.dp))
            }
        },
        tracks = page.value,
        error = page.error,
        onRetry = {
            SpotifyApi.clearCache()
            page.retry()
        },
        keyPrefix = "spotify-liked",
        editKey = LikedKey,
        discKey = DiscTargets.spotify(null),
        onEdit = { editSpotifyPlaylist(null) },
    )
}

/**
 * A Spotify list that's in Convx already opens as that playlist, which you can change, in place
 * of its read-only Spotify page, so it's never listed or opened twice.
 */
@Composable
private fun OpenCopy(from: Screen, key: String, copy: LocalPlaylist) {
    val navigator = LocalNavigator.current
    LaunchedEffect(copy.id) {
        if (editing == key) {
            editing = null
            val skipped = (PlaylistImport.state.value as? PlaylistImport.State.Done)?.skipped ?: 0
            PlaylistImport.dismiss()
            val missing = if (skipped > 0) " ${pluralize(skipped, "song")} had no match on YouTube Music." else ""
            Overlays.toast("You can edit ${copy.name} now.$missing", "edit")
        }
        navigator.replace(from, Screen.LocalPlaylist(copy.id))
    }
    LocalPlaylistScreen(copy.id)
}

@Composable
private fun SpotifyTrackPage(
    title: String,
    subtitle: String?,
    description: String?,
    art: @Composable (androidx.compose.ui.unit.Dp) -> Unit,
    tracks: List<SpotifyTrack>?,
    error: Throwable?,
    onRetry: () -> Unit,
    keyPrefix: String,
    editKey: String,
    discKey: String,
    onEdit: (() -> Unit)?,
) {
    val playback = LocalPlayback.current
    // Songs swap their Spotify ids for YouTube Music ones as they're matched, so rows follow the matches.
    val matched by SpotifyMatcher.matched.collectAsState()
    val songs = remember(tracks, matched) { tracks.orEmpty().map(SpotifyMatcher::toSong) }
    val playingHere = playback.isPlaying && songs.any { it.id == playback.currentId }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PageList(state = rememberLazyListState(), tint = Color.Black, topPadding = 0.dp) {
            val seconds = songs.sumOf { (it.duration ?: 0).toLong() }
            collectionHeader(
                title = title,
                art = art,
                subtitle = subtitle,
                metadata = listOfNotNull(
                    "Spotify",
                    tracks?.let { pluralize(it.size, "song") },
                    if (seconds > 0) formatTotal(seconds) else null,
                ).joinToString(" · "),
                description = description,
                playing = playingHere,
                enabled = songs.isNotEmpty(),
                onPlay = {
                    if (playingHere) PlayerController.pause() else if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, title, shuffle = false)
                },
                onShuffle = { if (songs.isNotEmpty()) PlayerController.playSongs(songs, 0, title, shuffle = true) },
                menu = { dismiss ->
                    if (onEdit != null) {
                        MenuItem("Edit in Convx", "edit") {
                            dismiss()
                            onEdit()
                        }
                    }
                    DiscCoverMenuItems(discKey, songs.takeIf { it.isNotEmpty() }, dismiss)
                    MenuItem("Reload from Spotify", "refresh") {
                        dismiss()
                        onRetry()
                    }
                },
            )
            if (onEdit != null && tracks != null) item(key = "edit") { EditBar(editKey, onEdit) }
            when {
                tracks != null && tracks.isEmpty() -> item {
                    MessageState("Nothing to play here", "This list has no songs Convx can match, like local files or podcasts.", icon = "spotify")
                }
                tracks != null -> songTable(songs, title, keyPrefix)
                error != null -> item {
                    MessageState("Couldn't load this from Spotify", friendlyError(error), icon = "spotify", actionText = "Try again", onAction = onRetry)
                }
                else -> item { LoadingState() }
            }
        }
    }
}

/**
 * Spotify's lists can't be changed from here, so the page offers to make one yours in Convx, then
 * says how far that's got until it opens as your playlist.
 */
@Composable
private fun EditBar(key: String, onEdit: () -> Unit) {
    val state by PlaylistImport.state.collectAsState()
    val mine = editing == key
    val now = state
    val stop: () -> Unit = { PlaylistImport.cancel() }
    val line: String
    val button: Pair<String, () -> Unit>?
    when {
        mine && now is PlaylistImport.State.Loading -> {
            line = "Getting the songs from Spotify…"
            button = "Stop" to stop
        }
        mine && now is PlaylistImport.State.Matching -> {
            line = "Matching songs on YouTube Music: ${now.done} of ${now.total}"
            button = "Stop" to stop
        }
        mine && now is PlaylistImport.State.Done -> {
            line = "Opening your playlist…"
            button = null
        }
        mine && now is PlaylistImport.State.Failed -> {
            line = now.message
            button = "Try again" to onEdit
        }
        else -> {
            line = "Spotify playlists can't be changed here. Edit makes this one a Convx playlist you can rename, " +
                "reorder and add to; your Spotify stays as it is."
            button = "Edit" to onEdit
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(line, style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel, modifier = Modifier.weight(1f))
        if (button != null) {
            Spacer(Modifier.width(16.dp))
            val (label, action) = button
            PillButton(label, action, icon = if (label == "Edit") "edit" else null, filled = label == "Edit")
        }
    }
}

/** Spotify writes playlist descriptions as HTML. */
private fun stripTags(html: String): String = html
    .replace(Regex("<[^>]+>"), "")
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&#x27;", "'")
    .replace("&#39;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
