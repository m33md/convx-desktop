/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.player

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.JsonFile
import com.convx.desktop.data.Song
import com.convx.desktop.integrations.spotify.SpotifyMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Plays your own copy of a song you stream. When your Local files folders or the download folder
 * hold a file with the song's title and artist and about its length, the song plays from that file
 * instead of from YouTube. It stays the YouTube Music song everywhere else (likes, lyrics, history,
 * Discord), so pointing the download folder at a music collection you already have makes Convx play
 * from it.
 */
object LocalCopies {
    private const val Tag = "LocalCopies"

    /** The stream's client name, which the quality card shows. */
    const val Client = "Your file"

    /** A file this much longer or shorter is another recording (a live take, an edit, a video). */
    private const val ToleranceMs = 3_000L

    /** The download folder's music, read like Local files are; the files there may be no song Convx lists. */
    private val store = JsonFile(File(AppDirs.data, "download-folder-music.json"), LocalMusic.Index.serializer()) { LocalMusic.Index() }
    private var job: Job? = null
    private val _found = MutableStateFlow<Int?>(null)

    /** How many songs the download folder's last read found; null before the first. */
    val found: StateFlow<Int?> = _found.asStateFlow()

    internal class Candidate(val id: String, val track: LocalMusic.Track, val artists: List<String>)

    private class Catalog(val local: LocalMusic.Index, val folder: LocalMusic.Index, val byTitle: Map<String, List<Candidate>>)

    @Volatile private var catalog: Catalog? = null

    /** Songs that matched a file, for AutoMix's measuring, which only knows a song by its id. */
    private val matched = ConcurrentHashMap<String, String>()

    val enabled: Boolean get() = AppSettings.value.playOwnCopies

    /** [song]'s copy on this PC as a stream and its file, or null when there is none or the setting is off. */
    fun find(song: Song): Pair<ResolvedStream, File>? {
        if (!enabled || LocalMusic.isLocal(song.id) || SpotifyMatcher.isSpotify(song)) return null
        val candidate = best(song)
        val file = candidate?.track?.path?.let(::File)?.takeIf { it.isFile }
        if (candidate == null || file == null) {
            matched.remove(song.id)
            return null
        }
        if (matched.put(song.id, file.path) != file.path) Log.i(Tag, "${song.title} plays from your file (${file.extension})")
        return LocalMusic.stream(candidate.id, candidate.track).copy(videoId = song.id, client = Client) to file
    }

    /** The file [id] matched when it last played, if it is still there. */
    fun knownFile(id: String): File? =
        if (!enabled) null else matched[id]?.let(::File)?.takeIf { it.isFile }

    internal fun best(song: Song): Candidate? {
        val title = titleKey(song.title).ifEmpty { return null }
        val artists = song.artists.map { artistKey(it.name) }.filter { it.isNotEmpty() }
        if (artists.isEmpty()) return null
        val durationMs = song.duration?.takeIf { it > 0 }?.times(1000L)
        fun off(c: Candidate) = if (durationMs == null || c.track.durationMs <= 0) 0L else abs(c.track.durationMs - durationMs)
        return catalog().byTitle[title].orEmpty()
            .filter { c -> c.artists.any { local -> artists.any { sameArtist(it, local) } } }
            .filter { c -> off(c) <= ToleranceMs }
            // The best-sounding file first; files of the same quality by how close their length is.
            .sortedWith(compareByDescending<Candidate> { lossless(it.track) }.thenByDescending { it.track.bitrate ?: 0 }.thenBy(::off))
            .firstOrNull()
    }

    private fun lossless(track: LocalMusic.Track) =
        (track.codec ?: File(track.path).extension).lowercase().let { c -> listOf("flac", "alac", "wav", "pcm", "ape", "wavpack", "aiff").any { it in c } }

    private fun catalog(): Catalog {
        val local = LocalMusic.index.value
        val folder = store.value
        catalog?.let { if (it.local === local && it.folder === folder) return it }
        val byTitle = HashMap<String, MutableList<Candidate>>()
        // A file in both is the same file (ids come from the path), so it is in once.
        (local.tracks + folder.tracks).forEach { (id, track) ->
            val artists = track.artists.map(::artistKey).filter { it.isNotEmpty() }
            val key = titleKey(track.title)
            if (artists.isEmpty() || key.isEmpty()) return@forEach
            byTitle.getOrPut(key) { mutableListOf() } += Candidate(id, track, artists)
        }
        return Catalog(local, folder, byTitle).also { catalog = it }
    }

    /**
     * Reads the download folder's music again, in the background. A file is read only when it is new
     * or changed, or when Local files hasn't read it already.
     */
    fun refresh() {
        job?.cancel()
        if (!enabled) return
        job = AppScope.launch(Dispatchers.IO) {
            val folder = Downloads.exportDir
            val before = store.value.tracks
            val known = LocalMusic.index.value.tracks
            val found = HashMap<String, LocalMusic.Track>()
            var read = 0
            if (folder.isDirectory) {
                folder.walkTopDown()
                    .onEnter { !it.isHidden && !it.name.startsWith(".") }
                    .filter { it.isFile && it.extension.lowercase() in LocalMusic.Extensions }
                    .forEach { file ->
                        ensureActive()
                        val id = LocalMusic.idOf(file)
                        val old = (before[id] ?: known[id])?.takeIf { it.modified == file.lastModified() && it.size == file.length() }
                        found[id] = old ?: LocalMusic.readTags(id, file)?.also { read++ } ?: return@forEach
                    }
            }
            store.update { LocalMusic.Index(found) }
            _found.value = found.size
            Log.i(Tag, "Download folder: ${found.size} songs, $read read")
        }
    }

    private val Bracketed = Regex("\\s*[(\\[][^)\\]]*[)\\]]")
    private val FeatTail = Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s.*$", RegexOption.IGNORE_CASE)
    private val DashNoise = Regex("\\s+[-\u2013\u2014]\\s+[^-\u2013\u2014]*\\b(remaster|remastered|explicit|clean)\\b.*$", RegexOption.IGNORE_CASE)

    /** Words in brackets that don't make another recording: "(feat. X)", "(Official Audio)", "(2011 Remaster)". */
    private val Noise = setOf(
        "feat", "ft", "featuring", "with", "remaster", "remastered", "explicit", "clean", "official", "audio",
        "video", "lyric", "lyrics", "visualizer", "visualiser", "hq", "hd", "prod", "from",
    )
    private val Marks = Regex("\\p{M}+")
    private val NonWord = Regex("[^\\p{L}\\p{N}]+")

    private fun words(text: String): List<String> =
        Normalizer.normalize(text, Normalizer.Form.NFKD).replace(Marks, "").lowercase().replace("&", " and ")
            .split(NonWord).filter { it.isNotEmpty() }

    /** A title as it is compared: no featured artists, remaster or "official" notes, accents, case or punctuation. */
    internal fun titleKey(text: String): String {
        val bare = Bracketed.replace(text) { m -> if (words(m.value).any { it in Noise }) "" else m.value }
            .replace(FeatTail, "")
            .replace(DashNoise, "")
        return words(bare).joinToString(" ").ifEmpty { words(text).joinToString(" ") }
    }

    internal fun artistKey(text: String): String = words(text).let { if (it.firstOrNull() == "the" && it.size > 1) it.drop(1) else it }.joinToString(" ")

    /** "The Weeknd" is the artist of a file tagged "The Weeknd, Daft Punk", but "Ye" isn't in "Yeat". */
    internal fun sameArtist(a: String, b: String): Boolean =
        a == b || (minOf(a.length, b.length) >= 3 && (" $b ".contains(" $a ") || " $a ".contains(" $b ")))
}
