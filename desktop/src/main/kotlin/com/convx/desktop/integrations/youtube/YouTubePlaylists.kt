/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations.youtube

import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.data.LibraryData
import com.convx.desktop.data.SavedPlaylist
import com.convx.desktop.data.Song
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.screens.PageCache
import com.music.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Your own playlists on YouTube Music as places to put songs, as the phone's Add to playlist
 * offers them: the song goes into the playlist on the account, so it's there on every device.
 */
object YouTubePlaylists {
    private const val Tag = "YouTubePlaylists"

    /** YouTube's video ids; local files and other sources have ids of their own. */
    private val VideoId = Regex("[A-Za-z0-9_-]{11}")

    /** The account's playlists you can add to, while signed in, by name. */
    fun editable(library: LibraryData): List<SavedPlaylist> =
        if (!YouTubeAccount.signedIn) emptyList()
        else library.playlists.filter { it.editable }.sortedBy { it.title.lowercase() }

    /** Adds [songs] to [playlist] on YouTube Music, then says how it went. */
    fun add(playlist: SavedPlaylist, songs: List<Song>) {
        val ids = songs.map { it.id }.filter { VideoId.matches(it) }.distinct()
        if (ids.isEmpty()) {
            Overlays.toast("Only songs from YouTube Music can go in a YouTube Music playlist", "error")
            return
        }
        AppScope.launch(Dispatchers.IO) {
            var added = 0
            var failure: Throwable? = null
            for (id in ids) {
                YouTube.addToPlaylist(playlist.id, id)
                    .onSuccess { added++ }
                    .onFailure {
                        failure = it
                        Log.w(Tag, "Couldn't add $id to ${playlist.id}", it)
                    }
            }
            // The playlist's page shows the new songs next time it opens.
            PageCache.remove("playlist:${playlist.id.removePrefix("VL")}")
            Log.i(Tag, "Added $added of ${ids.size} to ${playlist.id}")
            withContext(Dispatchers.Main) {
                when {
                    added == ids.size -> Overlays.toast("Added to ${playlist.title}")
                    added > 0 -> Overlays.toast("Added $added of ${ids.size} songs to ${playlist.title}", "error")
                    else -> Overlays.toast(
                        "Couldn't add to ${playlist.title}: " + (failure?.message?.take(80) ?: "YouTube Music refused it"),
                        "error",
                    )
                }
            }
        }
    }
}
