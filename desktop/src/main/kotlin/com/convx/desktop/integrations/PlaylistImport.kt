/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.data.ArtistRef
import com.convx.desktop.data.Library
import com.convx.desktop.data.LocalPlaylist
import com.convx.desktop.data.PlaylistCovers
import com.convx.desktop.data.Song
import com.convx.desktop.data.toSong
import com.convx.desktop.integrations.spotify.SpotifyApi
import com.convx.desktop.integrations.spotify.SpotifyMatcher
import com.convx.desktop.integrations.spotify.SpotifyPlaylist
import com.convx.desktop.player.FolderPlaylists
import com.convx.desktop.player.LocalCopies
import com.convx.music.canvas.AppleMusicCatalog
import com.convx.music.canvas.AppleMusicPlaylist
import com.convx.music.canvas.ApplePlaylist
import com.convx.music.canvas.ApplePlaylistExport
import com.convx.music.canvas.ApplePlaylistLink
import com.convx.music.canvas.ApplePlaylistTrack
import com.music.innertube.SearchFilter
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone's playlist imports: Spotify playlists and Liked Songs mirrored into Convx playlists,
 * Apple Music playlists from a public link or a Music/iTunes XML export, and M3U and CSV files
 * (see [PlaylistFiles]). Every track is
 * matched to a YouTube Music song the way the phone matches it; songs with no close match are
 * skipped and counted rather than replaced by a guess.
 */
object PlaylistImport {
    private const val Tag = "PlaylistImport"
    /** The phone's Apple threshold: the same title, the same artist, and a close overall score. */
    private const val AppleMinScore = 0.78
    const val FileSource = "Playlist file"

    sealed interface State {
        data object Idle : State
        data class Loading(val source: String) : State
        data class Matching(val source: String, val title: String, val done: Int, val total: Int, val list: Int = 1, val lists: Int = 1) : State
        /** An Apple playlist is matched and waits for where to put it. */
        data class Ready(val title: String, val songs: List<Song>, val skipped: Int) : State
        /** [skipped] songs found no match on YouTube Music. */
        data class Done(val message: String, val playlistId: String? = null, val skipped: Int = 0) : State
        data class Failed(val message: String) : State
    }

    /**
     * One thing to import from Spotify; [playlist] null means Liked Songs. [add] are songs to put in
     * its Convx copy once it's there, as "Add to playlist" does with a list that's only on Spotify.
     */
    data class SpotifySource(val playlist: SpotifyPlaylist?, val add: List<Song> = emptyList())

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    val busy: Boolean get() = state.value is State.Loading || state.value is State.Matching

    fun init() {
        AppleMusicCatalog.cacheDir = File(AppDirs.cache, "apple_catalog")
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = State.Idle
    }

    fun dismiss() {
        if (!busy) _state.value = State.Idle
    }

    // Spotify

    /**
     * Brings each source into Convx, as the phone's SpotifyImportViewModel does. A playlist that is
     * already here, from an earlier Spotify import or under its name from Exportify, gets only the
     * songs it is missing; one that isn't (you deleted it, say) comes back. Nothing is in twice.
     */
    fun importSpotify(sources: List<SpotifySource>): Job? {
        if (busy || sources.isEmpty()) return null
        // Busy from now, so a second click can't start another before this one has begun.
        _state.value = State.Loading("Spotify")
        return AppScope.launch(Dispatchers.IO) {
            val tally = Tally()
            var lastId: String? = null
            try {
                val targets = copiesAmongYours(sources.map { it.playlist })
                sources.forEachIndexed { index, source ->
                    val title = source.playlist?.name ?: LikedTitle
                    _state.value = State.Loading("Spotify")
                    val tracks = (source.playlist?.let { SpotifyApi.playlistTracks(it.id) } ?: SpotifyApi.likedSongs()).getOrThrow()
                    val songs = mutableListOf<Song>()
                    tracks.forEachIndexed { i, track ->
                        ensureActive()
                        _state.value = State.Matching("Spotify", title, i, tracks.size, index + 1, sources.size)
                        val standIn = SpotifyMatcher.toSong(track)
                        val wasMatched = !SpotifyMatcher.isSpotify(standIn)
                        val song = if (wasMatched) standIn else SpotifyMatcher.resolve(standIn)
                        if (song != null && !SpotifyMatcher.isSpotify(song)) songs += song else tally.skipped++
                        if (!wasMatched) delay(150)
                    }
                    val there = targets[index]
                    val id = there?.id ?: (SpotifyPrefix + (source.playlist?.id ?: "liked"))
                    // Spotify's own picture and name; a name you changed here stays.
                    val added = Library.mergeImported(
                        id, title, songs, cover = source.playlist?.image,
                        spotifyId = source.playlist?.id?.takeIf { id != SpotifyPrefix + it },
                        rename = there == null || slugOf(there.name) == slugOf(title),
                        key = ::songKey,
                    )
                    tally.count(there != null, added)
                    if (source.add.isNotEmpty()) Library.addToPlaylist(id, source.add)
                    keepDisc(source.playlist?.id, id)
                    lastId = id
                }
                _state.value = State.Done(tally.message(), lastId.takeIf { sources.size == 1 }, tally.skipped)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w(Tag, "Spotify import failed", error)
                _state.value = State.Failed("Couldn't import from Spotify: ${error.message ?: "check your connection"}")
            }
        }.also { job = it }
    }

    /**
     * The key a disc design put on a Spotify list (its [spotifyId], or null for Liked Songs) is kept
     * by in Library.worn, while the list is only on Spotify.
     */
    fun discKey(spotifyId: String?) = "spotify:" + (spotifyId ?: "liked")

    /**
     * Moves a disc design worn by the Spotify list [spotifyId] onto [playlistId], the Convx playlist
     * it came into, unless that one has a picture of its own already; either way the list's own key
     * goes, as the copy now stands for it.
     */
    private fun keepDisc(spotifyId: String?, playlistId: String) {
        val key = discKey(spotifyId)
        val worn = Library.value.worn[key] ?: return
        val copy = Library.playlist(playlistId)
        if (copy != null && !PlaylistCovers.isOwn(copy.cover)) {
            if (worn.design != null) Library.setPlaylistDesign(playlistId, worn.cover, worn.design) else Library.setPlaylistCover(playlistId, worn.cover)
        }
        Library.removeWornCover(key)
    }

    /** What an import of several playlists did, for the line it ends with. */
    private class Tally {
        var created = 0
        var updated = 0
        var added = 0
        var skipped = 0

        fun count(wasThere: Boolean, songs: Int) {
            if (wasThere) {
                updated++
                added += songs
            } else {
                created++
            }
        }

        fun message(): String {
            fun lists(n: Int) = if (n == 1) "1 playlist" else "$n playlists"
            fun songs(n: Int) = if (n == 1) "1 song" else "$n songs"
            return listOfNotNull(
                "Imported ${lists(created)}.".takeIf { created > 0 },
                when {
                    updated == 0 -> null
                    added > 0 -> "${lists(updated)} you already had got ${songs(added)} they were missing."
                    else -> "${lists(updated)} you already had ${if (updated == 1) "was" else "were"} up to date."
                },
                "${songs(skipped)} had no match on YouTube Music.".takeIf { skipped > 0 },
            ).joinToString(" ").ifEmpty { "Nothing to import." }
        }
    }

    const val LikedId = "local-spotify-liked"
    private const val LikedTitle = "Spotify Liked Songs"
    private const val SpotifyPrefix = "local-spotify-"
    private const val ImportPrefix = "local-import-"

    /**
     * A playlist to import: its Spotify id when known (Exportify's files have none), its name, and
     * whether it's Liked Songs. A Convx export also has the [convxId] it had where it came from, and
     * may land in [anyName] playlist of yours with its name, not only one from Spotify.
     */
    internal data class Wanted(
        val spotifyId: String?,
        val name: String,
        val liked: Boolean = false,
        val convxId: String? = null,
        val anyName: Boolean = false,
    )

    /** The Spotify playlist a Convx playlist came from: in its id, or noted when a Spotify import found it by name. */
    private fun spotifyIdOf(playlist: LocalPlaylist): String? =
        playlist.spotifyId ?: playlist.id.takeIf { it.startsWith(SpotifyPrefix) && it != LikedId }?.removePrefix(SpotifyPrefix)

    /** The names a Convx playlist goes by: its own, and the one an Exportify import put in its id. */
    private fun nameKeys(playlist: LocalPlaylist): List<String> =
        listOfNotNull(slugOf(playlist.name), playlist.id.takeIf { it.startsWith(ImportPrefix) }?.removePrefix(ImportPrefix))

    /**
     * Where each of [wanted] already is among [library]'s playlists, or null where it isn't: Liked
     * Songs in its own playlist; a Spotify playlist in the one an earlier Spotify import made of it;
     * else one from Spotify or Exportify with its name (Exportify's files carry no Spotify ids, and
     * name their playlists in lowercase). A playlist found for one isn't found for another, so two
     * Spotify playlists with the same name keep two.
     */
    internal fun existing(library: List<LocalPlaylist>, wanted: List<Wanted>): List<LocalPlaylist?> {
        val found = arrayOfNulls<LocalPlaylist>(wanted.size)
        val taken = HashSet<String>()
        fun claim(i: Int, playlist: LocalPlaylist?) {
            if (playlist != null && taken.add(playlist.id)) found[i] = playlist
        }
        wanted.forEachIndexed { i, w ->
            when {
                w.liked -> claim(i, library.firstOrNull { it.id == LikedId })
                // The same playlist, brought over before, or back to the PC it came from.
                w.convxId != null && library.any { it.id == w.convxId && it.id !in taken } -> claim(i, library.first { it.id == w.convxId })
                w.spotifyId != null -> claim(i, library.firstOrNull { spotifyIdOf(it) == w.spotifyId && it.id !in taken })
            }
        }
        // By name: Exportify's playlists first by the name they were imported under, then any from Spotify with it.
        val named = library.filter { (it.id.startsWith(ImportPrefix) || it.id.startsWith(SpotifyPrefix)) && it.id != LikedId }
        val yours = library.filter { !FolderPlaylists.isFolderPlaylist(it.id) && it.id != LikedId }
        fun byName(i: Int, w: Wanted, match: (List<String>, String) -> Boolean) {
            if (found[i] != null || w.liked) return
            val slug = slugOf(w.name)
            claim(i, (if (w.anyName) yours else named).firstOrNull { p ->
                p.id !in taken && match(nameKeys(p), slug) &&
                    // One that came from another Spotify playlist is that one's, not this one's.
                    (w.spotifyId == null || spotifyIdOf(p).let { it == null || it == w.spotifyId })
            })
        }
        wanted.forEachIndexed { i, w -> byName(i, w) { keys, slug -> slug in keys } }
        // Two Exportify files with one name were imported as "name" and "name-2".
        wanted.forEachIndexed { i, w -> byName(i, w) { keys, slug -> keys.any { it.startsWith("$slug-") && it.removePrefix("$slug-").all(Char::isDigit) } } }
        return found.toList()
    }

    /** Which Spotify playlists ([playlists], with null for Liked Songs) are in Convx already, by their place in the list. */
    fun alreadyThere(playlists: List<SpotifyPlaylist?>): List<Boolean> = copies(playlists).map { it != null }

    /**
     * The Convx playlist each of [playlists] (null for Liked Songs) was imported into, or null where
     * there's none: the editable copy that stands for it, found as an import would find it.
     */
    fun copies(playlists: List<SpotifyPlaylist?>, library: List<LocalPlaylist> = Library.value.localPlaylists): List<LocalPlaylist?> =
        existing(library, playlists.map { Wanted(it?.id, it?.name ?: LikedTitle, it == null) })

    /**
     * [copies] of [playlists] found among all your Spotify lists as last read, as the side bar and a
     * Spotify list's page find them: of two lists with one name, only the first is the copy's, and
     * the other is a list of its own. Asked of a few on their own, both would get the one copy.
     */
    fun copiesAmongYours(playlists: List<SpotifyPlaylist?>, library: List<LocalPlaylist> = Library.value.localPlaylists): List<LocalPlaylist?> {
        val known = listOf<SpotifyPlaylist?>(null) + SpotifyApi.knownPlaylists.orEmpty()
        val all = known + playlists.filterNotNull().filter { p -> known.none { it?.id == p.id } }.distinctBy { it.id }
        val found = copies(all, library)
        return playlists.map { p -> found[all.indexOfFirst { it?.id == p?.id }] }
    }

    /** A song's title and main artist, as playlists compare songs that may be two uploads of one recording. */
    private fun songKey(song: Song): String? {
        val title = LocalCopies.titleKey(song.title)
        val artist = song.artists.firstOrNull()?.name?.let(LocalCopies::artistKey).orEmpty()
        return if (title.isEmpty() || artist.isEmpty()) null else "$title|$artist"
    }

    // Apple Music

    fun loadAppleLink(url: String) {
        val link = ApplePlaylistLink.parse(url)
        if (link == null) {
            _state.value = State.Failed("Enter a public music.apple.com playlist link.")
            return
        }
        loadApple("Could not import this playlist. Check that it is public and try again.") { AppleMusicPlaylist.fetch(link) }
    }

    fun loadAppleExport(file: File) {
        loadApple("Could not import this file. Export one playlist as XML, keep the file under 8 MB, and check your connection.") {
            require(file.length() <= ApplePlaylistExport.MAX_BYTES) { "Export too large" }
            ApplePlaylistExport.parse(file.readBytes())
        }
    }

    private fun loadApple(failure: String, fetch: suspend () -> ApplePlaylist) {
        if (busy) return
        _state.value = State.Loading("Apple Music")
        job = AppScope.launch(Dispatchers.IO) {
            try {
                val playlist = fetch()
                val matched = mutableListOf<Song>()
                var skipped = playlist.unsupportedTracks
                playlist.tracks.forEachIndexed { index, track ->
                    ensureActive()
                    _state.value = State.Matching("Apple Music", playlist.title, index, playlist.tracks.size)
                    val best = findAppleMatch(track)
                    if (best != null) matched += best.toSong() else skipped++
                    if (index < playlist.tracks.lastIndex) delay(150)
                }
                _state.value = State.Ready(playlist.title, matched.distinctBy { it.id }, skipped)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w(Tag, "Apple Music import failed", error)
                _state.value = State.Failed(failure)
            }
        }
    }

    // M3U and CSV files

    /**
     * Matches a playlist file's songs, as the phone's "Import a m3u/csv playlist" does, and waits
     * for where to put them like an Apple import. The phone takes YouTube's first search result for
     * each line; here each is scored on title, artist and length, and a poor match is skipped.
     */
    fun loadFile(parsed: PlaylistFiles.Parsed) {
        if (busy) return
        if (parsed.tracks.isEmpty()) {
            _state.value = State.Failed("No songs found in this file.")
            return
        }
        _state.value = State.Loading(FileSource)
        job = AppScope.launch(Dispatchers.IO) {
            try {
                val (matched, skipped) = matchFile(parsed, FileSource, 1, 1)
                _state.value = State.Ready(parsed.title, matched.distinctBy { it.id }, skipped)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w(Tag, "Playlist file import failed", error)
                _state.value = State.Failed("Couldn't import this file: ${error.message ?: "check your connection"}")
            }
        }
    }

    /**
     * The id part for an imported playlist's name, so importing it again updates it. A name of
     * only punctuation or emoji still gets one of its own, from its characters' codes.
     */
    internal fun slugOf(title: String): String =
        title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(60).ifEmpty {
            title.trim().codePoints().toArray().joinToString("") { Integer.toHexString(it) }.take(60).ifEmpty { "playlist" }
        }

    /** Where Exportify's files are imported from, as the progress names it. */
    const val ExportifySource = "Exportify"

    /** Playlists exported from Convx (PlaylistExport), here or on another device. */
    const val ConvxSource = "Convx playlists"

    /**
     * Several playlist files at once, such as Exportify's "Export All": each becomes its own Convx
     * playlist. One already here, from Exportify or from Spotify under the same name, gets only the
     * songs it is missing rather than another copy. Exportify's Liked Songs lands in the same
     * playlist a Spotify import of them does.
     */
    fun importFiles(lists: List<PlaylistFiles.Parsed>, source: String = ExportifySource) {
        val wanted = lists.filter { it.tracks.isNotEmpty() }
        if (busy || wanted.isEmpty()) return
        _state.value = State.Loading(source)
        job = AppScope.launch(Dispatchers.IO) {
            val tally = Tally()
            var lastId: String? = null
            val library = Library.value.localPlaylists
            val convx = source == ConvxSource
            // A Convx export says which playlist it is; Exportify's Liked Songs file only by its name.
            fun isLiked(parsed: PlaylistFiles.Parsed) =
                if (parsed.id != null) parsed.id == LikedId else slugOf(parsed.title).let { it == "liked-songs" || it == "liked" }
            val targets = existing(library, wanted.map {
                Wanted(it.id?.takeIf { id -> id.startsWith(SpotifyPrefix) && id != LikedId }?.removePrefix(SpotifyPrefix), it.title, isLiked(it), it.id, anyName = convx)
            })
            // A new playlist's id is one no playlist has, so two lists whose names make the same slug
            // ("......." and "!!!", say) each keep their own.
            val ids = library.mapTo(HashSet()) { it.id }
            targets.forEach { it?.let { ids += it.id } }
            // Export All repeats a song in Liked Songs and in every playlist that has it; each is looked up once.
            val found = HashMap<String, Song?>()
            try {
                wanted.forEachIndexed { index, parsed ->
                    val (songs, skipped) = matchFile(parsed, source, index + 1, wanted.size, found)
                    val there = targets[index]
                    val liked = isLiked(parsed)
                    // A Convx export keeps its id, so it's found by it next time; a folder's playlist can't
                    // (its folder isn't here), and one with no id gets a new one.
                    val kept = parsed.id?.takeIf { it.startsWith("local-") && !FolderPlaylists.isFolderPlaylist(it) && ids.add(it) }
                    val id = there?.id ?: if (liked) LikedId else kept ?: if (convx) "local-" + UUID.randomUUID() else {
                        val base = ImportPrefix + slugOf(parsed.title)
                        (sequenceOf(base) + generateSequence(2) { it + 1 }.map { "$base-$it" }).first { ids.add(it) }
                    }
                    // Each list goes into the library as soon as its songs are matched.
                    val added = Library.mergeImported(id, if (liked) LikedTitle else parsed.title, songs, cover = parsed.cover, key = ::songKey)
                    tally.count(there != null, added)
                    tally.skipped += skipped
                    lastId = id
                }
                _state.value = State.Done(tally.message(), lastId.takeIf { wanted.size == 1 })
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w(Tag, "Playlist files import failed", error)
                _state.value = State.Failed("Couldn't import these files: ${error.message ?: "check your connection"}")
            }
        }
    }

    /** How many songs are searched for at once. */
    private const val Searchers = 4

    /** The least time between two searches starting, so a long import doesn't hammer YouTube Music. */
    private const val SearchGapMs = 120L

    /** Matches go into the Spotify match file this many at a time, not after every song. */
    private const val KeepEvery = 250

    /**
     * One file's songs on YouTube Music, and how many had no match. The phone takes YouTube's first
     * search result for each line; here each is scored on title, artist and length, and a poor match
     * is skipped. A line with a Spotify id shares its match with Spotify imports.
     *
     * A song already matched (by an earlier import, or in [found] by another list of this one) isn't
     * searched again, and the rest are searched [Searchers] at a time, spaced out by [Pace].
     */
    private suspend fun matchFile(
        parsed: PlaylistFiles.Parsed,
        source: String,
        list: Int,
        lists: Int,
        found: MutableMap<String, Song?> = HashMap(),
    ): Pair<List<Song>, Int> = coroutineScope {
        val tracks = parsed.tracks
        val keys = tracks.map(::keyOf)
        // Lines that already name a YouTube song are looked up together, not searched.
        val linked = tracks.withIndex().filter { (i, track) -> track.videoId != null && keys[i] !in found }
            .mapNotNull { it.value.videoId }.distinct().chunked(50).flatMap { ids ->
                ensureActive()
                YouTube.queue(ids, null).onFailure { Log.w(Tag, "Couldn't look up ${ids.size} linked songs", it) }.getOrNull().orEmpty()
            }.associateBy { it.id }
        val todo = ArrayList<Int>()
        tracks.forEachIndexed { i, track ->
            val key = keys[i]
            if (key in found) return@forEachIndexed
            val id = track.videoId
            val known = track.spotifyId?.let(SpotifyMatcher::known)?.takeIf { !SpotifyMatcher.isSpotify(it) }
            when {
                id != null -> found[key] = linked[id]?.toSong() ?: Song(
                    id = id,
                    title = track.title.ifBlank { id },
                    artists = track.artists.map { ArtistRef(null, it) },
                    duration = (track.durationMs / 1000).toInt().takeIf { it > 0 },
                    thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                )
                known != null -> found[key] = known
                else -> {
                    // Claimed now, so a song listed twice in this file is searched once.
                    found[key] = null
                    todo += i
                }
            }
        }

        val total = tracks.size
        val done = AtomicInteger(total - todo.size)
        _state.value = State.Matching(source, parsed.title, done.get(), total, list, lists)
        val results = arrayOfNulls<Song>(todo.size)
        val next = AtomicInteger()
        val pending = ConcurrentLinkedQueue<Pair<String, Song>>()
        fun keep() {
            val batch = HashMap<String, Song>()
            while (true) pending.poll()?.let { batch[it.first] = it.second } ?: break
            SpotifyMatcher.remember(batch)
        }
        val pace = Pace(SearchGapMs)
        try {
            List(minOf(Searchers, todo.size)) {
                launch {
                    while (true) {
                        val n = next.getAndIncrement()
                        if (n >= todo.size) break
                        val track = tracks[todo[n]]
                        val song = search(track, pace)
                        results[n] = song
                        val spotifyId = track.spotifyId
                        if (song != null && spotifyId != null) pending += spotifyId to song
                        val count = done.incrementAndGet()
                        if (count % KeepEvery == 0) keep()
                        // Several searchers finish at once; the count only goes up.
                        _state.update { now ->
                            if (now is State.Matching && now.list == list && now.done >= count) now
                            else State.Matching(source, parsed.title, count, total, list, lists)
                        }
                    }
                }
            }.joinAll()
        } finally {
            // Stopped part way, the songs matched so far are still kept for next time.
            keep()
        }
        todo.forEachIndexed { n, i -> found[keys[i]] = results[n] }

        val matched = ArrayList<Song>(tracks.size)
        var skipped = 0
        keys.forEach { key -> found[key]?.let { matched += it } ?: skipped++ }
        matched to skipped
    }

    /** What makes two lines the same song: its Spotify or YouTube id, or else its words and length. */
    private fun keyOf(track: PlaylistFiles.FileTrack): String = when {
        track.videoId != null -> "yt:" + track.videoId
        track.spotifyId != null -> "sp:" + track.spotifyId
        else -> "text:" + track.artists.joinToString(",").lowercase() + "|" + track.title.lowercase() + "|" + track.durationMs / 1000
    }

    /** One line's song on YouTube Music: null when nothing is close, or YouTube Music kept failing. */
    private suspend fun search(track: PlaylistFiles.FileTrack, pace: Pace): Song? {
        repeat(3) {
            pace.await()
            val result = SpotifyMatcher.findMatch(track.title, track.artists, track.durationMs)
            if (result.isSuccess) {
                pace.ok()
                return result.getOrNull()?.toSong()
            }
            pace.failed()
        }
        return null
    }

    /**
     * Spaces searches out: one starts at most every [gapMs], and after YouTube Music fails one they
     * all wait and slow down for a while, so a long import isn't turned away.
     */
    private class Pace(private val gapMs: Long) {
        private var gap = gapMs
        private var next = 0L

        suspend fun await() {
            val wait = synchronized(this) {
                val now = System.currentTimeMillis()
                val at = maxOf(now, next)
                next = at + gap
                at - now
            }
            if (wait > 0) delay(wait)
        }

        fun ok() = synchronized(this) { gap = maxOf(gapMs, gap * 9 / 10) }

        fun failed() = synchronized(this) {
            gap = minOf(5_000L, gap * 3)
            next = maxOf(next, System.currentTimeMillis() + gap * 4)
        }
    }

    /** Puts a matched Apple playlist or playlist file into a new Convx playlist, or adds it to [playlistId]. */
    fun save(playlistId: String?) {
        val ready = state.value as? State.Ready ?: return
        val id = if (playlistId == null) {
            Library.createPlaylist(ready.title.trim().ifEmpty { "Imported playlist" }, ready.songs)
        } else {
            Library.addToPlaylist(playlistId, ready.songs)
            playlistId
        }
        val name = Library.playlist(id)?.name ?: ready.title
        _state.value = State.Done("Added ${ready.songs.size} songs to $name.", id)
    }

    private suspend fun findAppleMatch(track: ApplePlaylistTrack): SongItem? {
        repeat(2) { attempt ->
            try {
                val results = YouTube.search("${track.artist} ${track.title}", SearchFilter.FILTER_SONG).getOrThrow()
                return chooseAppleMatch(track, results.items.filterIsInstance<SongItem>())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(Tag, "Search for ${track.artist} - ${track.title} failed", failure)
                if (attempt == 0) delay(500)
            }
        }
        // One failed search shouldn't throw away the songs already matched.
        return null
    }

    /** The phone's chooseBestMatch: same normalised title, matching artist, then the best score. */
    private fun chooseAppleMatch(track: ApplePlaylistTrack, candidates: List<SongItem>): SongItem? {
        val title = AppleMusicCatalog.normalize(track.title)
        return candidates.asSequence()
            .distinctBy { it.id }
            .filter { AppleMusicCatalog.normalize(it.title) == title }
            .filter { AppleMusicCatalog.artistMatches(track.artist, it.artists.joinToString(", ") { a -> a.name }) }
            .map {
                it to SpotifyMatcher.score(
                    track.title, track.artist, track.durationMs.toLong(),
                    it.title, it.artists.joinToString(" ") { a -> a.name }, it.duration,
                )
            }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= AppleMinScore }
            ?.first
    }
}
