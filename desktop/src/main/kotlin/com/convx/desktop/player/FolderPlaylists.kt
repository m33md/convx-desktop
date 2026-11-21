/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.player

import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.Song
import java.io.File
import java.security.MessageDigest

/**
 * Music folders as playlists of their own: each folder you pick in Settings > Local files becomes
 * a playlist that holds the songs in it and in the folders inside it. Every scan brings it up to
 * date, so a file added to the folder joins it and a file deleted leaves it. Your own order, name,
 * picture and disc design stay.
 */
object FolderPlaylists {
    const val Prefix = "local-folder-"

    /** One id per folder, stable across scans and restarts. */
    fun idOf(folder: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(key(folder).toByteArray(Charsets.UTF_8))
        return Prefix + digest.take(10).joinToString("") { "%02x".format(it) }
    }

    fun isFolderPlaylist(id: String) = id.startsWith(Prefix)

    /** The folder a folder playlist follows, while it's still one. */
    fun folderOf(id: String): String? = AppSettings.value.folderPlaylists.firstOrNull { idOf(it) == id }

    fun isOn(folder: String) = AppSettings.value.folderPlaylists.any { key(it) == key(folder) }

    /** Makes [folder] a playlist, or stops it being one, which deletes the playlist. */
    fun set(folder: String, on: Boolean) {
        AppSettings.update { s ->
            val rest = s.folderPlaylists.filterNot { key(it) == key(folder) }
            s.copy(folderPlaylists = if (on) rest + folder else rest)
        }
        sync()
    }

    /** Every music folder as a playlist; gives back how many were new. */
    fun all(): Int {
        val fresh = AppSettings.value.localFolders.filterNot(::isOn)
        if (fresh.isEmpty()) return 0
        AppSettings.update { it.copy(folderPlaylists = it.folderPlaylists + fresh) }
        sync()
        return fresh.size
    }

    /** A folder that's no longer a music folder stops being a playlist too. */
    fun forget(folder: String) {
        if (isOn(folder)) set(folder, false)
    }

    /** For deleting a folder playlist from the library: the folder stops being one, so the next scan doesn't bring it back. */
    fun deleted(id: String) {
        if (!isFolderPlaylist(id)) return
        AppSettings.update { s -> s.copy(folderPlaylists = s.folderPlaylists.filterNot { idOf(it) == id }) }
    }

    /** Brings the folder playlists in line with the folders and what the last scan found. */
    fun sync(index: LocalMusic.Index = LocalMusic.index.value) {
        val settings = AppSettings.value
        val folders = settings.folderPlaylists.filter { f -> settings.localFolders.any { key(it) == key(f) } }
        val wanted = folders.associate { folder -> idOf(folder) to (nameOf(folder) to songsIn(folder, index)) }
        Library.syncFolderPlaylists(Prefix, wanted)
    }

    /** "Music" for C:\Users\you\Music, "share" for \\server\share. */
    fun nameOf(folder: String): String =
        File(folder).name.ifEmpty { folder.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/') }.ifEmpty { folder }

    /** Whether [path] is in [folder] or a folder inside it; C:\Music doesn't hold C:\Music2. */
    fun within(path: String, folder: String): Boolean {
        val dir = key(folder).trimEnd('\\', '/')
        val p = key(path)
        return p.length > dir.length && p.startsWith(dir) && (p[dir.length] == '\\' || p[dir.length] == '/')
    }

    private fun key(path: String) = path.trim().lowercase()

    /** The songs in [folder] and the folders inside it, in the order File Explorer lists them by path. */
    fun songsIn(folder: String, index: LocalMusic.Index): List<Song> =
        index.tracks.entries.filter { within(it.value.path, folder) }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.value.path })
            .map { (id, track) -> LocalMusic.toSong(id, track) }
}
