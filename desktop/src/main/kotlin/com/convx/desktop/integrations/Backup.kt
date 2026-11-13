/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.data.AlbumRef
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.ArtistRef
import com.convx.desktop.data.Library
import com.convx.desktop.data.LibraryData
import com.convx.desktop.data.LikedSong
import com.convx.desktop.data.LocalPlaylist
import com.convx.desktop.data.PlayEvent
import com.convx.desktop.data.Recognition
import com.convx.desktop.data.SavedAlbum
import com.convx.desktop.data.SavedArtist
import com.convx.desktop.data.SavedPlaylist
import com.convx.desktop.data.Settings
import com.convx.desktop.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The phone's Backup & restore. A backup here is a zip of the library and settings; restoring one
 * replaces them. A backup made on the phone restores too, as an import: its liked songs,
 * playlists, albums, artists and history are added to this PC's library from its database.
 */
object Backup {
    private const val Tag = "Backup"
    private const val Manifest = "convx-desktop.json"
    private const val LibraryEntry = "library.json"
    private const val SettingsEntry = "settings.json"
    private const val MatchesEntry = "spotify-matches.json"
    /** Player icons' images, DIY's stickers and imported fonts, kept in these folders of the app's data. */
    private val AssetFolders = listOf(
        "player_icons", "diy_stickers", "lyrics-fonts", com.convx.desktop.data.PlaylistCovers.Folder,
        com.convx.desktop.data.DiscDesigns.Folder, com.convx.desktop.data.DiscDesigns.AssetFolder,
    )
    const val BeforeRestore = "before-restore.backup"

    /** The phone's entries (BackupRestoreViewModel). */
    private const val PhoneDatabase = "song.db"
    private const val PhoneSettings = "settings.preferences_pb"

    sealed interface State {
        data object Idle : State
        data class Working(val what: String) : State
        data class Done(val message: String) : State
        data class Failed(val message: String) : State
    }

    /** What a phone backup holds, read but not yet added. */
    data class PhoneLibrary(
        val liked: List<LikedSong>,
        val playlists: List<LocalPlaylist>,
        val savedPlaylists: List<SavedPlaylist>,
        val albums: List<SavedAlbum>,
        val artists: List<SavedArtist>,
        val history: List<PlayEvent>,
        /** Songs that were files on the phone, which this PC can't play. */
        val skippedLocal: Int,
        /** Every YouTube artist's picture the phone had, for Stats. */
        val artistPictures: Map<String, String> = emptyMap(),
        /** What the phone's Recognize Music found. */
        val recognitions: List<Recognition> = emptyList(),
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    fun dismiss() {
        if (_state.value !is State.Working) _state.value = State.Idle
    }

    /** The phone names its backups after the app and the time. */
    fun suggestedName(): String = "Convx_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))}.backup"

    fun create(target: File) {
        if (_state.value is State.Working) return
        _state.value = State.Working("Backing up…")
        AppScope.launch(Dispatchers.IO) {
            _state.value = runCatching {
                write(target)
                State.Done("Backed up to ${target.name}.")
            }.getOrElse { error ->
                Log.w(Tag, "Backup failed", error)
                target.delete()
                State.Failed("Couldn't back up: ${error.message ?: "the file couldn't be written"}")
            }
        }
    }

    /**
     * The library, the settings and the Spotify matches. Sign-ins live in Secrets and stay out,
     * so a backup file is safe to keep in a cloud folder.
     */
    private fun write(target: File) {
        Library.flush()
        AppSettings.flush()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(Manifest))
            zip.write("""{"app":"Convx for desktop","created":${System.currentTimeMillis()}}""".toByteArray())
            zip.putNextEntry(ZipEntry(LibraryEntry))
            zip.write(json.encodeToString(LibraryData.serializer(), Library.value).toByteArray())
            zip.putNextEntry(ZipEntry(SettingsEntry))
            zip.write(json.encodeToString(Settings.serializer(), AppSettings.value.copy(cookie = null)).toByteArray())
            File(AppDirs.data, MatchesEntry).takeIf { it.isFile }?.let { matches ->
                zip.putNextEntry(ZipEntry(MatchesEntry))
                matches.inputStream().use { it.copyTo(zip) }
            }
            // The images Player icons and DIY draw, and the Disc Studio designs with their pictures.
            AssetFolders.forEach { folder ->
                File(AppDirs.data, folder).listFiles()?.filter { it.isFile }?.forEach { image ->
                    zip.putNextEntry(ZipEntry("$folder/${image.name}"))
                    image.inputStream().use { it.copyTo(zip) }
                }
            }
        }
    }

    /** Restores a backup from this app, or adds a phone backup's library to this one. */
    fun restore(file: File) {
        if (_state.value is State.Working) return
        _state.value = State.Working("Reading the backup…")
        AppScope.launch(Dispatchers.IO) {
            _state.value = try {
                val names = entryNames(file)
                when {
                    LibraryEntry in names -> restoreDesktop(file)
                    PhoneDatabase in names -> {
                        _state.value = State.Working("Adding your phone's library…")
                        val phone = readPhone(file)
                        State.Done(addPhone(phone))
                    }
                    else -> State.Failed("This isn't a Convx backup.")
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w(Tag, "Restore failed", error)
                State.Failed("Couldn't restore this backup: ${error.message ?: "it may be damaged"}")
            }
        }
    }

    private fun entryNames(file: File): Set<String> =
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            generateSequence { runCatching { zip.nextEntry }.getOrNull() }.map { it.name }.toSet()
        }

    private fun restoreDesktop(file: File): State {
        var library: LibraryData? = null
        var settings: Settings? = null
        var matches: ByteArray? = null
        val assets = mutableMapOf<String, ByteArray>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == LibraryEntry -> library = json.decodeFromString(LibraryData.serializer(), zip.readBytes().decodeToString())
                    entry.name == SettingsEntry -> settings = json.decodeFromString(Settings.serializer(), zip.readBytes().decodeToString())
                    entry.name == MatchesEntry -> matches = zip.readBytes()
                    // Only the folder and the file's own name: an entry can't write anywhere else.
                    !entry.isDirectory && AssetFolders.any { entry.name.startsWith("$it/") } ->
                        assets[entry.name.substringBefore('/') + "/" + File(entry.name).name] = zip.readBytes()
                }
            }
        }
        val restoredLibrary = library ?: return State.Failed("This backup has no library in it.")
        // Restoring replaces everything, so what was here is kept beside the app's data first.
        val before = File(AppDirs.data, BeforeRestore)
        runCatching { write(before) }.onFailure { Log.w(Tag, "Couldn't keep the library from before the restore", it) }
        Library.applySync { restoredLibrary }
        // Before the settings that point at them.
        assets.forEach { (path, bytes) ->
            val folder = File(AppDirs.data, path.substringBefore('/')).apply { mkdirs() }
            runCatching { File(folder, path.substringAfter('/')).writeBytes(bytes) }
        }
        com.convx.desktop.data.DiscDesigns.refresh()
        settings?.let { restored ->
            // The window, the sound device, this PC's party id and YouTube's guest session stay
            // this PC's own, and music folders only come along if they exist here.
            AppSettings.update { current ->
                restored.copy(
                    windowWidth = current.windowWidth,
                    windowHeight = current.windowHeight,
                    windowMaximized = current.windowMaximized,
                    outputDevice = current.outputDevice,
                    partyId = current.partyId,
                    visitorData = current.visitorData,
                    recognitionInput = current.recognitionInput,
                    cookie = null,
                    localFolders = restored.localFolders.filter { File(it).isDirectory }.ifEmpty { current.localFolders },
                )
            }
        }
        matches?.let { runCatching { File(AppDirs.data, MatchesEntry).writeBytes(it) } }
        Library.flush()
        AppSettings.flush()
        return State.Done(
            "Restored ${restoredLibrary.liked.size} liked songs and ${restoredLibrary.localPlaylists.size} playlists" +
                (if (settings != null) ", and your settings." else ".") +
                " Sign-ins aren't in backups, so sign in again if this is a new PC." +
                (if (before.isFile) " What was here before is saved as $BeforeRestore in the app data folder." else ""),
        )
    }

    // ---- the phone's backup ------------------------------------------------------------------

    /** Reads a phone backup's database (Room's song.db) without changing anything. */
    fun readPhone(file: File): PhoneLibrary {
        val db = File.createTempFile("convx-phone", ".db")
        try {
            var found = false
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == PhoneDatabase) {
                        db.outputStream().use { zip.copyTo(it) }
                        found = true
                        break
                    }
                }
            }
            check(found) { "No library in this phone backup" }
            // Named once so the driver registers even if service loading is trimmed away.
            Class.forName("org.sqlite.JDBC")
            return DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { readPhone(it) }
        } finally {
            // SQLite may leave its journal beside the copy.
            listOf("", "-wal", "-shm", "-journal").forEach { File(db.path + it).delete() }
        }
    }

    private fun readPhone(connection: Connection): PhoneLibrary {
        // Artists of every song, in order.
        val songArtists = HashMap<String, MutableList<ArtistRef>>()
        connection.query(
            "SELECT m.songId AS songId, a.id AS id, a.name AS name FROM song_artist_map m JOIN artist a ON a.id = m.artistId ORDER BY m.songId, m.position",
        ) { row ->
            songArtists.getOrPut(row.string("songId") ?: return@query) { mutableListOf() } +=
                ArtistRef(row.string("id")?.takeIf { it.startsWith("UC") }, row.string("name").orEmpty())
        }
        val songs = HashMap<String, Song>()
        val likedRows = mutableListOf<Pair<String, Long>>()
        var skippedLocal = 0
        connection.query("SELECT * FROM song") { row ->
            val id = row.string("id") ?: return@query
            val local = row.bool("isLocal")
            if (local) {
                if (row.bool("liked")) skippedLocal++
                return@query
            }
            val albumId = row.string("albumId")
            songs[id] = Song(
                id = id,
                title = row.string("title").orEmpty(),
                artists = songArtists[id].orEmpty(),
                album = albumId?.takeIf { it.startsWith("MPRE") }?.let { AlbumRef(it, row.string("albumName").orEmpty()) },
                duration = row.int("duration")?.takeIf { it > 0 },
                thumbnail = row.string("thumbnailUrl"),
                explicit = row.bool("explicit"),
                isVideo = row.bool("isVideo"),
            )
            if (row.bool("liked")) likedRows += id to (row.long("likedDate") ?: 0L)
        }
        val liked = likedRows.sortedByDescending { it.second }.mapNotNull { (id, at) ->
            songs[id]?.let { LikedSong(it, at.takeIf { t -> t > 0 } ?: System.currentTimeMillis()) }
        }

        val playlistSongs = HashMap<String, MutableList<String>>()
        connection.query("SELECT playlistId, songId FROM playlist_song_map ORDER BY playlistId, position") { row ->
            val playlist = row.string("playlistId") ?: return@query
            playlistSongs.getOrPut(playlist) { mutableListOf() } += row.string("songId") ?: return@query
        }
        val playlists = mutableListOf<LocalPlaylist>()
        val savedPlaylists = mutableListOf<SavedPlaylist>()
        connection.query("SELECT * FROM playlist") { row ->
            val id = row.string("id") ?: return@query
            val name = row.string("name").orEmpty().ifBlank { "Phone playlist" }
            val browseId = row.string("browseId")?.removePrefix("VL")
            val created = row.long("createdAt") ?: System.currentTimeMillis()
            if (browseId == null) {
                // A playlist made on the phone: its songs, minus files only the phone has.
                val ids = playlistSongs[id].orEmpty()
                skippedLocal += ids.count { it !in songs }
                playlists += LocalPlaylist(
                    id = "local-phone-$id",
                    name = name,
                    songs = ids.mapNotNull { songs[it] }.distinctBy { it.id },
                    createdAt = created,
                    updatedAt = row.long("lastUpdateTime") ?: created,
                )
            } else {
                // A YouTube Music playlist, saved or your own from the account: kept as a link to it.
                savedPlaylists += SavedPlaylist(browseId, name, thumbnail = row.string("thumbnailUrl"), savedAt = row.long("bookmarkedAt") ?: created, editable = row.bool("isEditable"))
            }
        }

        val albumArtists = HashMap<String, MutableList<ArtistRef>>()
        runCatching {
            connection.query(
                "SELECT m.albumId AS albumId, a.id AS id, a.name AS name FROM album_artist_map m JOIN artist a ON a.id = m.artistId ORDER BY m.albumId, m.`order`",
            ) { row ->
                albumArtists.getOrPut(row.string("albumId") ?: return@query) { mutableListOf() } +=
                    ArtistRef(row.string("id")?.takeIf { it.startsWith("UC") }, row.string("name").orEmpty())
            }
        }
        val albums = mutableListOf<SavedAlbum>()
        connection.query("SELECT * FROM album") { row ->
            val id = row.string("id") ?: return@query
            if (row.bool("isLocal") || !id.startsWith("MPRE")) return@query
            val savedAt = row.long("bookmarkedAt") ?: row.long("inLibrary") ?: return@query
            albums += SavedAlbum(
                browseId = id,
                playlistId = row.string("playlistId"),
                title = row.string("title").orEmpty(),
                artists = albumArtists[id].orEmpty(),
                year = row.int("year"),
                thumbnail = row.string("thumbnailUrl"),
                savedAt = savedAt,
            )
        }

        val artists = mutableListOf<SavedArtist>()
        connection.query("SELECT * FROM artist") { row ->
            val id = row.string("id") ?: return@query
            val savedAt = row.long("bookmarkedAt") ?: return@query
            if (row.bool("isLocal") || !id.startsWith("UC")) return@query
            artists += SavedArtist(id, row.string("name").orEmpty(), row.string("thumbnailUrl"), savedAt)
        }

        val pictures = HashMap<String, String>()
        connection.query("SELECT * FROM artist") { row ->
            val id = row.string("id")?.takeIf { it.startsWith("UC") } ?: return@query
            pictures[id] = row.string("thumbnailUrl") ?: return@query
        }

        val history = mutableListOf<PlayEvent>()
        connection.query("SELECT * FROM event ORDER BY timestamp DESC LIMIT ${Library.HistoryLimit}") { row ->
            val song = songs[row.string("songId") ?: return@query] ?: return@query
            history += PlayEvent(song, row.long("timestamp") ?: return@query, playedMs = row.long("playTime") ?: 0L)
        }

        // Older phones have no such table.
        val recognitions = mutableListOf<Recognition>()
        runCatching {
            connection.query("SELECT * FROM recognition_history ORDER BY recognizedAt DESC") { row ->
                // The phone keeps its local clock time as if it were UTC.
                val stamp = row.long("recognizedAt") ?: return@query
                val at = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(stamp), java.time.ZoneOffset.UTC)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                recognitions += Recognition(
                    trackId = row.string("trackId").orEmpty(),
                    title = row.string("title") ?: return@query,
                    artist = row.string("artist").orEmpty(),
                    album = row.string("album"),
                    coverArt = row.string("coverArtUrl"),
                    coverArtHq = row.string("coverArtHqUrl"),
                    genre = row.string("genre"),
                    releaseDate = row.string("releaseDate"),
                    label = row.string("label"),
                    shazamUrl = row.string("shazamUrl"),
                    appleMusicUrl = row.string("appleMusicUrl"),
                    spotifyUrl = row.string("spotifyUrl"),
                    isrc = row.string("isrc"),
                    youtubeVideoId = row.string("youtubeVideoId"),
                    at = at,
                )
            }
        }

        return PhoneLibrary(liked, playlists, savedPlaylists, albums, artists, history, skippedLocal, pictures, recognitions)
    }

    /** Adds what the phone had without taking anything away from this PC's library. */
    private fun addPhone(phone: PhoneLibrary): String {
        var newLiked = 0
        var newAlbums = 0
        var newArtists = 0
        var newSaved = 0
        Library.applySync { data ->
            val likedIds = data.liked.mapTo(HashSet()) { it.song.id }
            val addLiked = phone.liked.filter { it.song.id !in likedIds }
            newLiked = addLiked.size
            val albumIds = data.albums.mapTo(HashSet()) { it.browseId }
            val addAlbums = phone.albums.filter { it.browseId !in albumIds }
            newAlbums = addAlbums.size
            val artistIds = data.artists.mapTo(HashSet()) { it.id }
            val addArtists = phone.artists.filter { it.id !in artistIds }
            newArtists = addArtists.size
            val savedIds = data.playlists.mapTo(HashSet()) { it.id }
            val addSaved = phone.savedPlaylists.filter { it.id !in savedIds }
            newSaved = addSaved.size
            // A phone playlist imported again replaces its earlier copy.
            val phoneIds = phone.playlists.mapTo(HashSet()) { it.id }
            // A play imported before time played came across gets the phone's time now.
            val phoneTimes = phone.history.associate { (it.song.id to it.at) to it.playedMs }
            val history = data.history.map { event ->
                if (event.playedMs == 0L) phoneTimes[event.song.id to event.at]?.let { event.copy(playedMs = it) } ?: event else event
            }
            val seen = history.mapTo(HashSet()) { it.song.id to it.at }
            data.copy(
                liked = (data.liked + addLiked).sortedByDescending { it.likedAt },
                albums = data.albums + addAlbums,
                artists = data.artists + addArtists,
                playlists = data.playlists + addSaved,
                localPlaylists = data.localPlaylists.filterNot { it.id in phoneIds } + phone.playlists,
                history = (history + phone.history.filter { (it.song.id to it.at) !in seen })
                    .sortedByDescending { it.at }
                    .take(Library.HistoryLimit),
                artistPictures = phone.artistPictures + data.artistPictures,
                recognitions = (data.recognitions + phone.recognitions.filter { r -> data.recognitions.none { it.trackId == r.trackId && it.at == r.at } })
                    .sortedByDescending { it.at },
            )
        }
        Library.flush()
        return buildString {
            append("Added from your phone: $newLiked liked songs, ${phone.playlists.size} playlists")
            if (newSaved > 0) append(", $newSaved saved playlists")
            append(", $newAlbums albums, $newArtists artists and ${phone.history.size} plays of history.")
            if (phone.recognitions.isNotEmpty()) append(" ${phone.recognitions.size} songs Recognize Music found came too.")
            if (phone.skippedLocal > 0) append(" ${phone.skippedLocal} songs were files on the phone, which this PC doesn't have.")
            append(" The phone's settings stay on the phone.")
        }
    }

    // ---- reading rows by column name, so an older phone database without a column still reads ----

    private class Row(private val rs: ResultSet, private val columns: Set<String>) {
        fun string(name: String): String? = if (name in columns) rs.getString(name) else null
        fun long(name: String): Long? = if (name in columns) rs.getLong(name).takeUnless { rs.wasNull() } else null
        fun int(name: String): Int? = if (name in columns) rs.getInt(name).takeUnless { rs.wasNull() } else null
        fun bool(name: String): Boolean = (long(name) ?: 0L) != 0L
    }

    private fun Connection.query(sql: String, each: (Row) -> Unit) {
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rs ->
                val meta = rs.metaData
                val columns = (1..meta.columnCount).mapTo(HashSet()) { meta.getColumnLabel(it) }
                val row = Row(rs, columns)
                while (rs.next()) each(row)
            }
        }
    }
}
