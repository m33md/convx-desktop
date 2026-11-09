/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.data

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.Log
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File

/**
 * Pictures chosen for playlists on this PC. Each is copied into Convx's own folder, so the
 * playlist keeps it if the original moves, and a backup carries it. A playlist stores the file's
 * name behind [Prefix] rather than a path, so a backup restored on another PC still finds it.
 */
object PlaylistCovers {
    private const val Tag = "PlaylistCovers"
    private const val Prefix = "convx-cover:"
    const val Folder = "playlist_covers"

    /** What the picker offers: the formats Skia reads. */
    val Extensions = listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    private const val MaxBytes = 20L * 1024 * 1024
    private const val MaxSide = 1200

    val dir: File by lazy { File(AppDirs.data, Folder).apply { mkdirs() } }

    /** A picture chosen here, as opposed to a web address from an import. */
    fun isOwn(cover: String?): Boolean = cover?.startsWith(Prefix) == true

    /** The address the artwork loader reads: the file for a chosen picture, the web address otherwise. */
    fun url(cover: String?): String? = when {
        cover.isNullOrBlank() -> null
        cover.startsWith(Prefix) -> File(dir, cover.removePrefix(Prefix)).takeIf { it.isFile }?.toURI()?.toString()
        else -> cover
    }

    sealed interface Result {
        data class Done(val cover: String) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Copies [source] in as a cover for [playlistId]. Big pictures are scaled down to 1200 pixels a
     * side, as nothing shows them larger. Every copy gets a new name, so no cache can show the old one.
     */
    fun import(playlistId: String, source: File): Result {
        if (!source.isFile) return Result.Failed("That file isn't there any more.")
        if (source.length() > MaxBytes) return Result.Failed("That picture is over 20 MB.")
        val bytes = runCatching { source.readBytes() }.getOrElse { return Result.Failed("Couldn't read that file.") }
        val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull()
            ?: return Result.Failed("That file isn't a picture Convx can read.")
        val safeId = playlistId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60)
        val stamp = System.currentTimeMillis()
        return runCatching {
            val (data, ext) = if (image.width > MaxSide || image.height > MaxSide) {
                val scale = MaxSide.toFloat() / maxOf(image.width, image.height)
                val w = (image.width * scale).toInt().coerceAtLeast(1)
                val h = (image.height * scale).toInt().coerceAtLeast(1)
                val surface = Surface.makeRasterN32Premul(w, h)
                surface.canvas.drawImageRect(image, Rect.makeWH(w.toFloat(), h.toFloat()))
                val scaled = surface.makeImageSnapshot()
                val encoded = scaled.encodeToData(EncodedImageFormat.JPEG, 90)?.bytes
                    ?: error("Couldn't shrink the picture")
                encoded to "jpg"
            } else {
                bytes to source.extension.lowercase().takeIf { it in Extensions }.orEmpty().ifEmpty { "png" }
            }
            val name = "$safeId-$stamp.$ext"
            File(dir, name).writeBytes(data)
            Result.Done(Prefix + name)
        }.getOrElse {
            Log.w(Tag, "Couldn't keep a playlist picture", it)
            Result.Failed("Couldn't save the picture.")
        }
    }

    /** Keeps [data], a picture Convx drew (a Disc Studio design), as a cover for [playlistId]. */
    fun save(playlistId: String, data: ByteArray, ext: String): Result = runCatching {
        val name = "${discName(playlistId)}-${System.currentTimeMillis()}.$ext"
        File(dir, name).writeBytes(data)
        Result.Done(Prefix + name)
    }.getOrElse {
        Log.w(Tag, "Couldn't keep a playlist picture", it)
        Result.Failed("Couldn't save the picture.")
    }

    /** Where the CD covers Convx draws are kept (ui.components.DiscCovers), one "<playlist>-<key>.jpg" each. */
    val discDir: File by lazy { File(dir, "disc").apply { mkdirs() } }

    /** A playlist's id as it starts its CD cover's file name. */
    fun discName(playlistId: String): String = playlistId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60)

    /** The playlist a CD cover file belongs to (its name without "-<key>.jpg"). */
    fun discOwner(file: File): String = file.name.substringBeforeLast('.').substringBeforeLast('-')

    /** Deletes picture files no playlist (nor Liked Songs) uses any more, and CD covers of playlists that are gone. */
    fun prune(playlists: List<LocalPlaylist>) {
        val used = playlists.mapNotNullTo(HashSet()) { p -> p.cover?.takeIf(::isOwn)?.removePrefix(Prefix) }
        AppSettings.value.likedSongsCover?.takeIf(::isOwn)?.let { used += it.removePrefix(Prefix) }
        dir.listFiles()?.filter { it.isFile && it.name !in used }?.forEach { runCatching { it.delete() } }
        val owners = playlists.mapTo(HashSet()) { discName(it.id) }
        discDir.listFiles()?.filter { it.isFile && discOwner(it) !in owners }?.forEach { runCatching { it.delete() } }
    }
}
