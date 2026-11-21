/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.data.JsonFile
import com.convx.desktop.data.Song
import com.convx.desktop.integrations.spotify.SpotifyMatcher
import com.music.innertube.SearchFilter
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Covers from the web for music files on this PC. Discord only shows a picture it can fetch from
 * an https address, never a file on your PC, so a local song is looked up once on YouTube Music by
 * its title, artist and length, and a close match lends its cover. What was found, or that nothing
 * was, is remembered until the file's tags change.
 */
object LocalSongCovers {
    private const val Tag = "LocalSongCovers"
    /** Title, artist and length together; a title alone can't reach it unless it's nearly exact and the length agrees. */
    private const val MinScore = 0.6
    private const val NoArtistMinScore = 0.55
    private const val RetryMs = 60_000L

    @Serializable
    data class Found(val key: String, val url: String? = null)

    private val store = JsonFile(
        File(AppDirs.data, "local-song-covers.json"),
        MapSerializer(String.serializer(), Found.serializer()),
    ) { emptyMap() }
    private val asking = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    fun flush() = store.flush()

    /** The web cover known for [song] now; null starts a lookup, and a later call has the answer. */
    fun cover(song: Song): String? {
        val key = keyOf(song)
        val found = store.value[song.id]
        if (found != null && found.key == key) return found.url
        lookUp(song, key)
        return null
    }

    private fun lookUp(song: Song, key: String) {
        if (song.title.isBlank()) return
        if (System.currentTimeMillis() - (failedAt[song.id] ?: 0L) < RetryMs) return
        if (!asking.add(song.id)) return
        AppScope.launch(Dispatchers.IO) {
            try {
                val artists = song.artists.map { it.name }.filter { it.isNotBlank() }
                val artist = artists.joinToString(" ")
                val query = listOf(artists.firstOrNull().orEmpty(), song.title).filter { it.isNotBlank() }.joinToString(" ")
                val result = withTimeoutOrNull(10_000) { YouTube.search(query, SearchFilter.FILTER_SONG) }
                val items = result?.getOrNull()
                if (items == null) {
                    // Offline or YouTube didn't answer: asked again in a minute, not remembered as "none".
                    failedAt[song.id] = System.currentTimeMillis()
                    return@launch
                }
                val durationMs = (song.duration ?: 0) * 1000L
                val best = items.items.filterIsInstance<SongItem>()
                    .map { it to SpotifyMatcher.score(song.title, artist, durationMs, it.title, it.artists.joinToString(" ") { a -> a.name }, it.duration) }
                    .maxByOrNull { it.second }
                val needed = if (artists.isEmpty()) NoArtistMinScore else MinScore
                val url = best?.takeIf { it.second >= needed }?.first?.thumbnail
                store.update { it + (song.id to Found(key, url)) }
                Log.i(Tag, if (url != null) "Found a cover for a local song (score ${"%.2f".format(best.second)})" else "No cover found for a local song")
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                failedAt[song.id] = System.currentTimeMillis()
                Log.w(Tag, "Cover lookup failed", error)
            } finally {
                asking.remove(song.id)
            }
        }
    }

    /** What the lookup went by; tags read again differently mean a new lookup. */
    private fun keyOf(song: Song) =
        listOf(song.title, song.artists.joinToString(",") { it.name }, (song.duration ?: 0).toString()).joinToString("|").lowercase()
}
