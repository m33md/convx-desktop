/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations

import com.convx.desktop.data.LocalPlaylist
import com.convx.desktop.data.Song
import com.convx.desktop.player.FolderPlaylists
import com.convx.desktop.player.LocalMusic
import java.io.File

/**
 * Your playlists as files to take to another device: one M3U file each (see
 * [PlaylistFiles.writeM3u]). Convx on another PC imports them with the very songs and without
 * duplicates; the phone's "Import a m3u playlist" reads them too, finding each song by its name.
 */
object PlaylistExport {
    /** .m3u rather than .m3u8: Android files it as audio, so the phone's audio file picker offers it. Its text is UTF-8. */
    const val Extension = "m3u"

    /** [playlist] as the text of its file. */
    fun text(playlist: LocalPlaylist): String = PlaylistFiles.writeM3u(
        playlist.name,
        // A folder's playlist is that folder's on this PC only; elsewhere it is a playlist like any other.
        playlist.id.takeUnless(FolderPlaylists::isFolderPlaylist),
        playlist.cover,
        playlist.songs,
        ::location,
    )

    /** A song's YouTube Music link, or where its file is when it's one of your files. */
    private fun location(song: Song): String =
        if (LocalMusic.isLocal(song.id)) LocalMusic.index.value.tracks[song.id]?.path ?: song.id
        else "https://music.youtube.com/watch?v=${song.id}"

    /** A file name for [name] that Windows, macOS and Android all take. */
    fun fileName(name: String): String =
        name.replace(Regex("[\\x00-\\x1F<>:\"/\\\\|?*]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').take(80).trim()
            .ifEmpty { "Playlist" }

    /** Writes [playlist] to [file] as UTF-8 with no byte order mark, which the phone needs to see #EXTM3U first. */
    fun write(playlist: LocalPlaylist, file: File) {
        file.parentFile?.mkdirs()
        file.writeText(text(playlist), Charsets.UTF_8)
    }

    /**
     * Writes each of [playlists] into [folder] as its own file, named after it. A file there already
     * is replaced only when it is this playlist's earlier export; any other gets a numbered name
     * beside it. Returns the files written.
     */
    fun writeAll(playlists: List<LocalPlaylist>, folder: File): List<File> {
        folder.mkdirs()
        val used = HashSet<String>()
        return playlists.map { playlist ->
            val base = fileName(playlist.name)
            val file = (sequenceOf(base) + generateSequence(2) { it + 1 }.map { "$base ($it)" })
                .map { File(folder, "$it.$Extension") }
                .first { candidate -> used.add(candidate.name.lowercase()) && (!candidate.exists() || isExportOf(candidate, playlist)) }
            write(playlist, file)
            file
        }
    }

    private fun isExportOf(file: File, playlist: LocalPlaylist): Boolean = runCatching {
        val mark = if (FolderPlaylists.isFolderPlaylist(playlist.id)) "#PLAYLIST:${playlist.name.trim()}" else "#CONVX-ID:${playlist.id}"
        file.bufferedReader().useLines { lines -> lines.take(5).any { it.trim() == mark } }
    }.getOrDefault(false)
}
