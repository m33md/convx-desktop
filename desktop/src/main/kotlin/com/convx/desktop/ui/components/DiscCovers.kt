/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import androidx.compose.ui.graphics.asSkiaBitmap
import com.convx.desktop.core.Log
import com.convx.desktop.data.CoverArt
import com.convx.desktop.data.LocalPlaylist
import com.convx.desktop.data.PlaylistCovers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Image
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The CD covers Convx draws for playlists that have no picture of their own ([CoverArt]). Each is
 * drawn once from the playlist's name and its first songs' covers, kept as a file, and drawn again
 * only when those change or "New cover design" asks for another.
 */
object DiscCovers {
    private const val Tag = "DiscCovers"
    private const val Pictures = 9

    private val dir: File get() = PlaylistCovers.discDir
    private val drawing = Semaphore(2)
    private val made = ConcurrentHashMap<String, String>()

    private val fonts: CoverArt.Fonts by lazy {
        val bytes = runCatching {
            Thread.currentThread().contextClassLoader.getResourceAsStream("fonts/chillax_bold.otf")?.use { it.readBytes() }
        }.getOrNull()
        CoverArt.Fonts(bytes?.let { runCatching { FontMgr.default.makeFromData(Data.makeFromBytes(it)) }.getOrNull() })
    }

    private fun pictures(playlist: LocalPlaylist): List<String> =
        playlist.songs.mapNotNull { it.thumbnail }.distinct().take(Pictures)

    /** Names this playlist's cover as it is now: its name, its design and the covers it's made from. */
    fun key(playlist: LocalPlaylist): String {
        val text = "${CoverArt.Version}|${playlist.name}|${playlist.coverSeed}|" + pictures(playlist).joinToString("|")
        return MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun file(playlist: LocalPlaylist, key: String) = File(dir, "${PlaylistCovers.discName(playlist.id)}-$key.jpg")

    /** The cover's address when it's already drawn, without waiting. */
    fun peek(playlist: LocalPlaylist, key: String = key(playlist)): String? =
        made[key] ?: file(playlist, key).takeIf { it.isFile }?.toURI()?.toString()?.also { made[key] = it }

    /** The cover's address, drawing it first if need be; null while its songs' covers can't be fetched. */
    suspend fun cover(playlist: LocalPlaylist): String? {
        val key = key(playlist)
        peek(playlist, key)?.let { return it }
        return drawing.withPermit {
            peek(playlist, key) ?: draw(playlist, key)
        }
    }

    private suspend fun draw(playlist: LocalPlaylist, key: String): String? {
        val urls = pictures(playlist)
        val images = urls.mapNotNull { url ->
            runCatching {
                ImageCache.load(artworkUrl(url, 400) ?: url)?.let { Image.makeFromBitmap(it.asSkiaBitmap()) }
            }.getOrNull()
        }
        // Offline, or YouTube slow: try again next time rather than keep a cover missing its pictures.
        if (urls.isNotEmpty() && images.size < (urls.size + 1) / 2) return null
        return withContext(Dispatchers.Default) {
            runCatching {
                val words = playlist.songs.take(60).flatMap { song -> listOf(song.title) + song.artists.map { it.name } }
                val seed = playlist.coverSeed * 7919 + playlist.id.hashCode()
                val image = CoverArt.render(CoverArt.Source(playlist.name, images, words, seed, design = playlist.coverSeed, count = playlist.songs.size), fonts)
                val target = file(playlist, key)
                target.writeBytes(CoverArt.encode(image))
                // The playlist's earlier covers.
                val owner = PlaylistCovers.discName(playlist.id)
                dir.listFiles()?.filter { PlaylistCovers.discOwner(it) == owner && it.name != target.name }?.forEach { runCatching { it.delete() } }
                target.toURI().toString().also { made[key] = it }
            }.onFailure { Log.w(Tag, "Couldn't draw the cover for ${playlist.name}", it) }.getOrNull()
        }
    }
}
