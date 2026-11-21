/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.data.Library
import com.convx.desktop.data.hasPlaylist
import com.convx.desktop.data.toSpeedDial
import com.convx.desktop.data.Song
import com.convx.desktop.data.toSaved
import com.convx.desktop.data.toSong
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.shell.Navigator
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint
import com.music.innertube.models.YTItem
import kotlinx.coroutines.launch

/** What's playing, handed down so every row can light itself up without its own collector. */
@Immutable
data class Playback(val currentId: String? = null, val isPlaying: Boolean = false, val isLoading: Boolean = false)

val LocalPlayback = compositionLocalOf { Playback() }

fun YTItem.subtitle(): String = when (this) {
    is SongItem -> artists.joinToString(", ") { it.name }
    is AlbumItem -> listOfNotNull(artists?.joinToString(", ") { it.name }?.ifBlank { null }, year?.toString()).joinToString(" • ")
    is PlaylistItem -> listOfNotNull(author?.name, songCountText).joinToString(" • ")
    is ArtistItem -> "Artist"
}

fun YTItem.link(): String = when (this) {
    is SongItem -> "https://music.youtube.com/watch?v=$id"
    is AlbumItem -> "https://music.youtube.com/browse/$browseId"
    is PlaylistItem -> "https://music.youtube.com/playlist?list=${id.removePrefix("VL")}"
    is ArtistItem -> "https://music.youtube.com/channel/$id"
}

/** Opening and playing things, the phone's openYtItem and its menus' play actions. */
object ItemActions {
    fun open(item: YTItem, navigator: Navigator) {
        when (item) {
            is SongItem -> PlayerController.startRadio(item.toSong(), item.endpoint)
            is AlbumItem -> navigator.navigate(Screen.Album(item.browseId))
            is PlaylistItem -> navigator.navigate(Screen.Playlist(item.id))
            is ArtistItem -> navigator.navigate(Screen.Artist(item.id))
        }
    }

    /** The hover play button on a card: play the whole thing without opening it. */
    fun play(item: YTItem, shuffle: Boolean = false) {
        when (item) {
            is SongItem -> PlayerController.startRadio(item.toSong(), item.endpoint)
            is ArtistItem -> {
                val endpoint = if (shuffle) item.shuffleEndpoint ?: item.radioEndpoint else item.playEndpoint ?: item.shuffleEndpoint ?: item.radioEndpoint
                if (endpoint != null) PlayerController.playEndpoint(endpoint, item.title)
                else withSongs(item) { PlayerController.playSongs(it, title = item.title, shuffle = shuffle) }
            }
            is PlaylistItem -> {
                val endpoint = if (shuffle) item.shuffleEndpoint else null
                if (endpoint != null) PlayerController.playEndpoint(endpoint, item.title)
                else withSongs(item) { PlayerController.playSongs(it, title = item.title, shuffle = shuffle) }
            }
            is AlbumItem -> withSongs(item) { PlayerController.playSongs(it, title = item.title, shuffle = shuffle) }
        }
    }

    fun radio(item: YTItem) {
        when (item) {
            is SongItem -> PlayerController.startRadio(item.toSong(), null)
            is ArtistItem -> item.radioEndpoint?.let { PlayerController.playEndpoint(it, "${item.title} Radio") }
            is PlaylistItem -> PlayerController.playEndpoint(
                item.radioEndpoint ?: WatchEndpoint(playlistId = "RDAMPL${item.id.removePrefix("VL")}"),
                "${item.title} Radio",
            )
            is AlbumItem -> PlayerController.playEndpoint(WatchEndpoint(playlistId = "RDAMPL${item.playlistId}"), "${item.title} Radio")
        }
    }

    /** Loads a collection's songs off the UI thread, then hands them to [action]. */
    fun withSongs(item: YTItem, action: (List<Song>) -> Unit) {
        AppScope.launch {
            val songs = runCatching { songsOf(item) }
                .onFailure { Log.w("ItemActions", "Couldn't load ${item.id}", it) }
                .getOrNull()
                .orEmpty()
            if (songs.isEmpty()) Overlays.toast("Couldn't load songs", "error") else action(songs)
        }
    }

    suspend fun songsOf(item: YTItem): List<Song> = when (item) {
        is SongItem -> listOf(item.toSong())
        is AlbumItem -> {
            val page = YouTube.album(item.browseId).getOrNull()
            val songs = page?.songs?.takeIf { it.isNotEmpty() }
                ?: YouTube.albumSongs(item.playlistId, item).getOrThrow()
            songs.map { it.toSong() }
        }
        is PlaylistItem -> {
            val page = YouTube.playlist(item.id.removePrefix("VL")).getOrThrow()
            val songs = page.songs.toMutableList()
            var continuation = page.songsContinuation
            var guard = 0
            while (continuation != null && songs.size < 1000 && guard++ < 20) {
                val more = YouTube.playlistContinuation(continuation).getOrNull() ?: break
                songs += more.songs
                continuation = more.continuation
            }
            songs.map { it.toSong() }
        }
        is ArtistItem -> {
            val page = YouTube.artist(item.id).getOrThrow()
            page.sections.firstOrNull { section -> section.items.any { it is SongItem } }
                ?.items?.filterIsInstance<SongItem>()?.map { it.toSong() }
                .orEmpty()
        }
    }
}

/** The phone's Pin to Speed dial: puts the item on Home's Speed dial, newest first. */
@Composable
fun SpeedDialMenuItem(item: YTItem, pinned: Boolean, dismiss: () -> Unit) {
    MenuItem(if (pinned) "Unpin from Speed dial" else "Pin to Speed dial", if (pinned) "remove" else "add") {
        Library.togglePin(item.toSpeedDial())
        Overlays.toast(if (pinned) "Unpinned from Speed dial" else "Pinned to Speed dial", if (pinned) "remove" else "ic_push_pin")
        dismiss()
    }
}

/** Right-click menu for any YouTube item on a card or shelf. */
@Composable
fun ItemMenuContent(item: YTItem, dismiss: () -> Unit) {
    if (item is SongItem) {
        SongMenuContent(item.toSong(), dismiss)
        return
    }
    val navigator = LocalNavigator.current
    val clipboard = LocalClipboardManager.current
    val library by Library.state.collectAsState()

    MenuItem("Play", "play_applemusic") {
        ItemActions.play(item)
        dismiss()
    }
    MenuItem("Shuffle", "shuffle") {
        ItemActions.play(item, shuffle = true)
        dismiss()
    }
    MenuItem("Start radio", "radio") {
        ItemActions.radio(item)
        dismiss()
    }
    if (item !is ArtistItem) {
        MenuItem("Play next", "playlist_play") {
            ItemActions.withSongs(item) {
                PlayerController.playNext(it)
                Overlays.toast("Playing next")
            }
            dismiss()
        }
        MenuItem("Add to queue", "queue_music") {
            ItemActions.withSongs(item) {
                PlayerController.addToQueue(it)
                Overlays.toast("Added to queue")
            }
            dismiss()
        }
    }
    MenuDivider()
    when (item) {
        is AlbumItem -> {
            val saved = library.albums.any { it.browseId == item.browseId }
            MenuItem(if (saved) "Remove from Library" else "Save to Library", if (saved) "library_add_check" else "library_add") {
                Library.toggleAlbum(item.toSaved())
                Overlays.toast(if (saved) "Removed from Library" else "Saved to Library")
                dismiss()
            }
            item.artists?.firstOrNull { it.id != null }?.let { artist ->
                MenuItem("Go to artist", "artist") {
                    navigator.navigate(Screen.Artist(artist.id!!))
                    dismiss()
                }
            }
        }
        is PlaylistItem -> {
            val saved = library.hasPlaylist(item.id)
            MenuItem(if (saved) "Remove from Library" else "Save to Library", if (saved) "library_add_check" else "library_add") {
                Library.togglePlaylist(item.toSaved())
                Overlays.toast(if (saved) "Removed from Library" else "Saved to Library")
                dismiss()
            }
        }
        is ArtistItem -> {
            val followed = library.artists.any { it.id == item.id }
            MenuItem(if (followed) "Unfollow" else "Follow", if (followed) "check" else "add") {
                Library.toggleArtist(item.toSaved())
                Overlays.toast(if (followed) "Unfollowed ${item.title}" else "Following ${item.title}")
                dismiss()
            }
        }
        is SongItem -> Unit
    }
    SpeedDialMenuItem(item, library.speedDial.any { it.id == item.toSpeedDial().id }, dismiss)
    MenuItem("Copy link", "link") {
        clipboard.setText(AnnotatedString(item.link()))
        Overlays.toast("Link copied", "link")
        dismiss()
    }
}
