/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.data

import com.convx.desktop.core.AppDirs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.util.UUID

/** The user's own music: likes, saved albums/playlists/artists, local playlists, history. */
object Library {
    /** Plays kept: about a year of daily listening, so Stats' years and months have something to show. */
    const val HistoryLimit = 10_000
    private const val SearchHistoryLimit = 30

    private val store = JsonFile(File(AppDirs.data, "library.json"), LibraryData.serializer()) { LibraryData() }

    val state: StateFlow<LibraryData> = store.state
    val value: LibraryData get() = store.value

    fun flush() = store.flush()

    // Likes

    fun isLiked(id: String): Boolean = value.liked.any { it.song.id == id }

    /** What you like, save and follow, as you do it, for Last.fm loves and YouTube Music sync. */
    sealed interface Change {
        data class Like(val song: Song, val liked: Boolean) : Change
        data class Album(val album: SavedAlbum, val saved: Boolean) : Change
        data class Playlist(val playlist: SavedPlaylist, val saved: Boolean) : Change
        data class Artist(val artist: SavedArtist, val followed: Boolean) : Change
    }

    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 64)
    val changes: SharedFlow<Change> = _changes.asSharedFlow()

    fun toggleLike(song: Song) {
        val liked = !isLiked(song.id)
        store.update { data ->
            if (data.liked.any { it.song.id == song.id }) data.copy(liked = data.liked.filterNot { it.song.id == song.id })
            else data.copy(liked = listOf(LikedSong(song)) + data.liked)
        }
        _changes.tryEmit(Change.Like(song, liked))
    }

    fun setLiked(song: Song, liked: Boolean) {
        if (isLiked(song.id) != liked) toggleLike(song)
    }

    // Saved collections

    fun isAlbumSaved(browseId: String) = value.albums.any { it.browseId == browseId }

    fun toggleAlbum(album: SavedAlbum) {
        val saved = !isAlbumSaved(album.browseId)
        store.update { data ->
            if (data.albums.any { it.browseId == album.browseId }) data.copy(albums = data.albums.filterNot { it.browseId == album.browseId })
            else data.copy(albums = listOf(album) + data.albums)
        }
        _changes.tryEmit(Change.Album(album, saved))
    }

    fun isPlaylistSaved(id: String) = value.playlists.any { it.id == id }

    fun togglePlaylist(playlist: SavedPlaylist) {
        val saved = !isPlaylistSaved(playlist.id)
        store.update { data ->
            if (data.playlists.any { it.id == playlist.id }) data.copy(playlists = data.playlists.filterNot { it.id == playlist.id })
            else data.copy(playlists = listOf(playlist) + data.playlists)
        }
        _changes.tryEmit(Change.Playlist(playlist, saved))
    }

    fun isArtistFollowed(id: String) = value.artists.any { it.id == id }

    fun toggleArtist(artist: SavedArtist) {
        val followed = !isArtistFollowed(artist.id)
        store.update { data ->
            if (data.artists.any { it.id == artist.id }) data.copy(artists = data.artists.filterNot { it.id == artist.id })
            else data.copy(artists = listOf(artist) + data.artists)
        }
        _changes.tryEmit(Change.Artist(artist, followed))
    }

    /** Applies what an account sync found, without echoing it back as changes of yours. */
    internal fun applySync(transform: (LibraryData) -> LibraryData) = store.update(transform)

    // Local playlists

    fun createPlaylist(name: String, songs: List<Song> = emptyList()): String {
        val id = "local-" + UUID.randomUUID().toString()
        store.update { it.copy(localPlaylists = it.localPlaylists + LocalPlaylist(id, name.ifBlank { "New playlist" }, songs)) }
        return id
    }

    /**
     * Makes the playlist [id] hold exactly [songs], creating it if needed; imports use stable ids.
     * [cover] is the source's picture; a picture you chose yourself stays.
     */
    fun upsertPlaylist(id: String, name: String, songs: List<Song>, cover: String? = null) = store.update { data ->
        if (data.localPlaylists.any { it.id == id }) {
            data.copy(localPlaylists = data.localPlaylists.map {
                if (it.id == id) {
                    val kept = it.cover?.takeIf(PlaylistCovers::isOwn) ?: cover ?: it.cover
                    it.copy(name = name, songs = songs, cover = kept, updatedAt = System.currentTimeMillis())
                } else {
                    it
                }
            })
        } else {
            data.copy(localPlaylists = data.localPlaylists + LocalPlaylist(id, name, songs, cover = cover))
        }
    }

    /** Sets the playlist's picture, or takes it off with null; a picture file nothing uses any more is deleted. */
    fun setPlaylistCover(id: String, cover: String?) {
        updatePlaylist(id) { it.copy(cover = cover, design = null) }
        PlaylistCovers.prune(value.localPlaylists)
    }

    /** Puts a Disc Studio design's picture on the playlist, remembering the design so it can be redrawn. */
    fun setPlaylistDesign(id: String, cover: String, design: String) {
        updatePlaylist(id) { it.copy(cover = cover, design = design) }
        PlaylistCovers.prune(value.localPlaylists)
    }

    /** A deleted design's playlists keep its picture, no longer tied to it. */
    fun forgetDesign(design: String) = store.update { data ->
        data.copy(localPlaylists = data.localPlaylists.map { if (it.design == design) it.copy(design = null) else it })
    }

    fun renamePlaylist(id: String, name: String) = updatePlaylist(id) { it.copy(name = name) }

    /** Draws the playlist's CD cover again, differently. Leaves "Recently updated" as it was. */
    fun newCoverDesign(id: String) = store.update { data ->
        data.copy(localPlaylists = data.localPlaylists.map { if (it.id == id) it.copy(coverSeed = it.coverSeed + 1) else it })
    }

    fun deletePlaylist(id: String) {
        store.update { data -> data.copy(localPlaylists = data.localPlaylists.filterNot { it.id == id }) }
        PlaylistCovers.prune(value.localPlaylists)
    }

    fun addToPlaylist(id: String, songs: List<Song>) = updatePlaylist(id) { playlist ->
        val existing = playlist.songs.mapTo(HashSet()) { it.id }
        playlist.copy(songs = playlist.songs + songs.filterNot { it.id in existing }.distinctBy { it.id })
    }

    fun removeFromPlaylist(id: String, songId: String) = updatePlaylist(id) { playlist ->
        playlist.copy(songs = playlist.songs.filterNot { it.id == songId })
    }

    /** Puts the playlist's songs in the order of [songIds]; any it doesn't name keep their order after them. */
    fun reorderPlaylist(id: String, songIds: List<String>) = updatePlaylist(id) { playlist ->
        val byId = playlist.songs.associateBy { it.id }
        val placed = songIds.distinct().mapNotNull { byId[it] }
        val named = placed.mapTo(HashSet()) { it.id }
        playlist.copy(songs = placed + playlist.songs.filterNot { it.id in named })
    }

    fun moveInPlaylist(id: String, from: Int, to: Int) = updatePlaylist(id) { playlist ->
        val songs = playlist.songs.toMutableList()
        if (from !in songs.indices || to !in songs.indices) return@updatePlaylist playlist
        songs.add(to, songs.removeAt(from))
        playlist.copy(songs = songs)
    }

    fun playlist(id: String): LocalPlaylist? = value.localPlaylists.firstOrNull { it.id == id }

    private fun updatePlaylist(id: String, transform: (LocalPlaylist) -> LocalPlaylist) = store.update { data ->
        data.copy(localPlaylists = data.localPlaylists.map {
            if (it.id == id) transform(it).copy(updatedAt = System.currentTimeMillis()) else it
        })
    }

    // History

    /** Adds a play to the history and returns when it was, so its time played can follow; null while history is paused. */
    fun recordPlay(song: Song, playedMs: Long = 0L): Long? {
        if (AppSettings.value.pauseListenHistory) return null
        val event = PlayEvent(song, playedMs = playedMs)
        store.update { data -> data.copy(history = (listOf(event) + data.history).take(HistoryLimit)) }
        return event.at
    }

    /** Sets how long the play at [at] was listened to, as it grows. */
    fun setPlayTime(at: Long, playedMs: Long) = store.update { data ->
        val index = data.history.indexOfFirst { it.at == at }
        if (index < 0 || data.history[index].playedMs == playedMs) {
            data
        } else {
            data.copy(history = data.history.toMutableList().apply { this[index] = this[index].copy(playedMs = playedMs) })
        }
    }

    fun clearHistory() = store.update { it.copy(history = emptyList()) }

    // Recognize Music's history

    fun addRecognition(recognition: Recognition) = store.update { it.copy(recognitions = listOf(recognition) + it.recognitions) }

    fun removeRecognition(at: Long) = store.update { data -> data.copy(recognitions = data.recognitions.filterNot { it.at == at }) }

    fun clearRecognitions() = store.update { it.copy(recognitions = emptyList()) }

    fun removeFromHistory(at: Long) = store.update { data -> data.copy(history = data.history.filterNot { it.at == at }) }

    /** Recently played, one entry per song. */
    fun recentSongs(limit: Int = 30): List<Song> =
        value.history.asSequence().map { it.song }.distinctBy { it.id }.take(limit).toList()

    /** The phone's "most played" over plays since [since], skipping the first [offset]. */
    fun mostPlayedSongs(since: Long, limit: Int, offset: Int = 0): List<Song> =
        listeningStats(since).songs.drop(offset).take(limit).map { it.value }

    /** Artists of the songs played since [since], most played first, as id and name. */
    fun mostPlayedArtists(since: Long, limit: Int): List<ArtistRef> =
        listeningStats(since).artists.take(limit).map { it.value }

    /** Albums of the songs played since [since], most played first, with a cover from one of them. */
    fun mostPlayedAlbums(since: Long, limit: Int): List<Pair<AlbumRef, Song>> =
        listeningStats(since).albums.take(limit).map { it.value to it.song }

    /** One song, artist or album in [ListeningStats], with a song of it for its cover. */
    data class Ranked<T>(val value: T, val song: Song, val plays: Int, val playedMs: Long)

    class ListeningStats(val songs: List<Ranked<Song>>, val artists: List<Ranked<ArtistRef>>, val albums: List<Ranked<AlbumRef>>)

    /**
     * The phone's Stats over plays after [from] and up to [to]: songs, YouTube artists and albums,
     * ranked by time listened as there, then by plays for history from before time was kept.
     */
    fun listeningStats(from: Long, to: Long = Long.MAX_VALUE, limit: Int = 1000): ListeningStats {
        val events = value.history.filter { it.at > from && it.at <= to }
        fun <K, T> rank(keys: (PlayEvent) -> List<Pair<K, T>>): List<Ranked<T>> {
            val groups = LinkedHashMap<K, MutableList<Pair<T, PlayEvent>>>()
            for (event in events) for ((key, item) in keys(event)) groups.getOrPut(key) { mutableListOf() } += item to event
            return groups.values
                .map { plays -> Ranked(plays.first().first, plays.first().second.song, plays.size, plays.sumOf { it.second.playedMs }) }
                .sortedWith(compareByDescending<Ranked<T>> { it.playedMs }.thenByDescending { it.plays })
                .take(limit)
        }
        return ListeningStats(
            songs = rank { listOf(it.song.id to it.song) },
            artists = rank { event -> event.song.artists.filter { it.id?.startsWith("UC") == true }.map { it.id!! to it } },
            albums = rank { event -> listOfNotNull(event.song.album?.let { it.id to it }) },
        )
    }

    // Artist pictures

    /** A played artist's picture: from following them, or kept from an earlier look-up. */
    fun artistPicture(id: String): String? =
        value.artists.firstOrNull { it.id == id }?.thumbnail ?: value.artistPictures[id]

    fun rememberArtistPictures(pictures: Map<String, String>) {
        if (pictures.isEmpty()) return
        store.update { data -> data.copy(artistPictures = data.artistPictures + pictures) }
    }

    /**
     * The phone's forgotten favourites, by its query: songs heard again this month, but less than
     * a fifth as much as before it. Counted in plays where the phone sums time played.
     */
    fun forgottenFavorites(now: Long = System.currentTimeMillis()): List<Song> {
        val monthAgo = now - 30L * 86_400_000
        return value.history.groupBy { it.song.id }.values
            .mapNotNull { events ->
                val old = events.count { it.at < monthAgo }
                val new = events.size - old
                if (new > 0 && 0.2 * old > new) events.first().song else null
            }
            .take(100)
    }

    // Speed dial

    fun isPinned(id: String): Boolean = value.speedDial.any { it.id == id }

    fun togglePin(entry: SpeedDialEntry) = store.update { data ->
        if (data.speedDial.any { it.id == entry.id }) {
            data.copy(speedDial = data.speedDial.filterNot { it.id == entry.id })
        } else {
            data.copy(speedDial = listOf(entry) + data.speedDial)
        }
    }

    // Search history

    fun addSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty() || AppSettings.value.pauseSearchHistory) return
        store.update { data ->
            data.copy(searchHistory = (listOf(q) + data.searchHistory.filterNot { it.equals(q, ignoreCase = true) }).take(SearchHistoryLimit))
        }
    }

    fun removeSearch(query: String) = store.update { data -> data.copy(searchHistory = data.searchHistory - query) }

    fun clearSearches() = store.update { it.copy(searchHistory = emptyList()) }
}

object AppSettings {
    private val store = JsonFile(File(AppDirs.data, "settings.json"), Settings.serializer()) { Settings() }

    val state: StateFlow<Settings> = store.state
    val value: Settings get() = store.value

    fun update(transform: (Settings) -> Settings) = store.update(transform)

    fun flush() = store.flush()
}
