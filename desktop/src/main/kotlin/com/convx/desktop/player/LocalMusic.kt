/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.player

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.ArtistRef
import com.convx.desktop.data.JsonFile
import com.convx.desktop.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.File
import java.security.MessageDigest

/**
 * Music files on this PC, the phone's local songs: the folders you choose are scanned for audio,
 * each file's tags and cover are read with FFmpeg (the same decoder that plays it), and the
 * songs show under Local files and play from disk. Network shares work as folders too, since
 * Windows opens \\server\share paths itself.
 */
object LocalMusic {
    private const val Tag = "LocalMusic"
    const val Prefix = "file-"

    val Extensions = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "webm", "aiff", "aif", "ape", "wv", "mka", "alac")
    private val CoverNames = listOf("cover", "folder", "front", "album", "albumart")

    @Serializable
    data class Track(
        val path: String,
        val modified: Long,
        val size: Long,
        val title: String,
        val artists: List<String> = emptyList(),
        val album: String? = null,
        val durationMs: Long = 0,
        val cover: String? = null,
        val codec: String? = null,
        val bitrate: Int? = null,
        val sampleRate: Int? = null,
        val track: Int? = null,
        val year: Int? = null,
        /** The file has no artist tag, and [artists] came from its name ("Artist - Title"). */
        val named: Boolean = false,
    ) {
        /** No artist tag was found when the file was last read. */
        val untagged: Boolean get() = artists.isEmpty() || named
    }

    @Serializable
    data class Index(val tracks: Map<String, Track> = emptyMap())

    sealed interface Status {
        data object Idle : Status
        data class Scanning(val folder: String, val found: Int, val read: Int) : Status
        data class Done(val at: Long, val total: Int, val added: Int, val removed: Int) : Status
        data class Failed(val message: String) : Status
    }

    private val store = JsonFile(File(AppDirs.data, "local-music.json"), Index.serializer()) { Index() }
    val index: StateFlow<Index> = store.state
    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    private var job: Job? = null
    private val covers by lazy { File(AppDirs.cache, "local-covers").apply { mkdirs() } }

    fun isLocal(id: String) = id.startsWith(Prefix)

    fun track(id: String): Track? = store.value.tracks[id]

    fun flush() = store.flush()

    /** Every local song, by artist, then album, then track number. */
    fun songs(index: Index = store.value): List<Song> =
        index.tracks.entries
            .sortedWith(
                compareBy<Map.Entry<String, Track>>(
                    { it.value.artists.firstOrNull()?.lowercase() ?: "\uffff" },
                    { it.value.album?.lowercase() ?: "" },
                    { it.value.track ?: Int.MAX_VALUE },
                    { it.value.title.lowercase() },
                ),
            )
            .map { (id, track) -> toSong(id, track) }

    fun toSong(id: String, track: Track) = Song(
        id = id,
        title = track.title,
        artists = track.artists.map { ArtistRef(null, it) },
        duration = (track.durationMs / 1000).toInt().takeIf { it > 0 },
        thumbnail = track.cover,
    )

    /** What the player's quality badge and engine need, as a stream would describe it. */
    fun stream(id: String, track: Track) = ResolvedStream(
        videoId = id,
        url = File(track.path).toURI().toString(),
        mimeType = "audio/${File(track.path).extension.lowercase()}; codecs=\"${track.codec ?: File(track.path).extension.lowercase()}\"",
        bitrate = track.bitrate ?: 0,
        sampleRate = track.sampleRate,
        contentLength = track.size,
        loudnessDb = null,
        durationMs = track.durationMs.takeIf { it > 0 },
        client = "Local file",
        expiresAt = Long.MAX_VALUE,
    )

    /** The default the phone's "scan device" has here: your Windows Music folder. */
    fun defaultFolders(): List<String> =
        listOf(File(System.getProperty("user.home"), "Music")).filter { it.isDirectory }.map { it.absolutePath }

    /**
     * Looks through the chosen folders again. A file is read again when its size or date changed,
     * so tags edited since are picked up by themselves. [untagged] also reads again the files that
     * gave no artist last time, as Scan now does: a tag editor can keep the file's date. [everything]
     * reads every file again ("Read tags again"). Folder playlists follow what was found.
     */
    fun scan(untagged: Boolean = false, everything: Boolean = false) {
        val running = job
        if (running?.isActive == true) {
            if (!untagged && !everything) return
            running.cancel()
        }
        job = AppScope.launch(Dispatchers.IO) {
            running?.join()
            val settings = AppSettings.value
            val folders = settings.localFolders.map(::File).filter { it.isDirectory }
            val excluded = settings.excludedFolders.map { File(it).absolutePath.lowercase().trimEnd('\\', '/') }
            val before = store.value.tracks
            val found = HashMap<String, Track>()
            val downloadCopy = Downloads.exportCopies()
            var read = 0
            try {
                for (folder in folders) {
                    folder.walkTopDown()
                        .onEnter { dir ->
                            !dir.isHidden && !Downloads.isExportFolder(dir) &&
                                excluded.none { dir.absolutePath.lowercase().trimEnd('\\', '/') == it }
                        }
                        .filter { it.isFile && it.extension.lowercase() in Extensions && !downloadCopy(it) }
                        .forEach { file ->
                            ensureActive()
                            val id = idOf(file)
                            if (id in found) return@forEach
                            val old = before[id]
                            val same = old != null && old.modified == file.lastModified() && old.size == file.length()
                            found[id] = if (same && !everything && !(untagged && old!!.untagged)) {
                                old!!
                            } else {
                                read++
                                readTrack(id, file) ?: return@forEach
                            }
                            if (found.size % 25 == 0) _status.value = Status.Scanning(folder.name, found.size, read)
                        }
                }
                store.update { Index(found) }
                runCatching { FolderPlaylists.sync(store.value) }.onFailure { Log.w(Tag, "Couldn't update the folder playlists", it) }
                val added = found.keys.count { it !in before }
                val removed = before.keys.count { it !in found }
                _status.value = Status.Done(System.currentTimeMillis(), found.size, added, removed)
                Log.i(Tag, "Scanned ${folders.size} folders: ${found.size} songs, $added new, $removed gone, $read read")
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.w(Tag, "Scan failed", error)
                _status.value = Status.Failed(error.message ?: "Couldn't scan your folders")
            }
        }
    }

    /** One id per file, stable across scans; the path itself never goes into file names or links. */
    private fun idOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(file.absolutePath.lowercase().toByteArray(Charsets.UTF_8))
        return Prefix + digest.take(10).joinToString("") { "%02x".format(it) }
    }

    /** Reads one file's tags the way a scan does; for the playback check. */
    internal fun read(file: File): Track? = readTrack(idOf(file), file)

    private fun readTrack(id: String, file: File): Track? {
        val grabber = FFmpegFrameGrabber(file.absolutePath)
        return try {
            grabber.start()
            val tags = (grabber.metadata.orEmpty() + grabber.audioMetadata.orEmpty())
                .mapKeys { it.key.lowercase() }
                .filterValues { it.isNotBlank() }
            val tagged = (tags["artist"] ?: tags["album_artist"] ?: tags["albumartist"] ?: tags["author"] ?: tags["performer"])
                ?.split(";", " / ", "\u0000")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            // No artist tag: a name like "Artist - Title" or "01 - Artist - Title" still says who it is.
            val name = if (tagged.isEmpty()) fromName(file.nameWithoutExtension) else null
            val title = tags["title"] ?: name?.second ?: file.nameWithoutExtension
            val named = name?.first?.takeIf { tags["title"] == null || tags["title"].equals(name.second, ignoreCase = true) }
            Track(
                path = file.absolutePath,
                modified = file.lastModified(),
                size = file.length(),
                title = title,
                artists = tagged.ifEmpty { listOfNotNull(named) },
                named = tagged.isEmpty() && named != null,
                album = tags["album"],
                durationMs = grabber.lengthInTime / 1000,
                cover = embeddedCover(id, grabber) ?: folderCover(file.parentFile),
                codec = audioCodec(grabber),
                bitrate = grabber.audioBitrate.takeIf { it > 0 },
                sampleRate = grabber.sampleRate.takeIf { it > 0 },
                track = tags["track"]?.substringBefore('/')?.trim()?.toIntOrNull(),
                year = (tags["date"] ?: tags["year"])?.take(4)?.toIntOrNull(),
            )
        } catch (error: Throwable) {
            Log.w(Tag, "Couldn't read ${file.name}: ${error.message}")
            null
        } finally {
            runCatching { grabber.release() }
        }
    }

    private val LeadingNumber = Regex("^\\d{1,3}\\s*[-._)]\\s*")
    private val NameSplit = Regex("\\s+[-\u2013\u2014]\\s+")

    /**
     * What a file's name says when it has no tags: "Artist - Title" gives both, and a track number
     * in front ("01 - ", "01. ") is dropped. Null artist when the name doesn't name one.
     */
    internal fun fromName(name: String): Pair<String?, String> {
        val bare = name.replace(LeadingNumber, "").trim().ifEmpty { name }
        val parts = bare.split(NameSplit, limit = 2).map { it.trim() }
        return if (parts.size == 2 && parts.all { it.isNotEmpty() }) parts[0] to parts[1] else null to bare
    }

    /** The picture inside the file (ID3 APIC, FLAC PICTURE, MP4 covr), saved once next to the cache. */
    private fun embeddedCover(id: String, grabber: FFmpegFrameGrabber): String? = runCatching {
        val context = grabber.formatContext ?: return null
        for (i in 0 until context.nb_streams()) {
            val stream = context.streams(i)
            if ((stream.disposition() and avformat.AV_DISPOSITION_ATTACHED_PIC) == 0) continue
            val packet = stream.attached_pic()
            val size = packet.size()
            if (size <= 0) continue
            val bytes = ByteArray(size)
            packet.data().get(bytes)
            val out = File(covers, "$id.img")
            out.writeBytes(bytes)
            return out.toURI().toString()
        }
        null
    }.getOrNull()

    /** The audio stream's codec name, e.g. "flac" or "mp3", from FFmpeg's own table. */
    private fun audioCodec(grabber: FFmpegFrameGrabber): String? = runCatching {
        val context = grabber.formatContext ?: return null
        (0 until context.nb_streams())
            .map { context.streams(it).codecpar() }
            .firstOrNull { it.codec_type() == avutil.AVMEDIA_TYPE_AUDIO }
            ?.let { avcodec.avcodec_get_name(it.codec_id()).string }
    }.getOrNull()

    private fun folderCover(dir: File?): String? {
        val files = dir?.listFiles() ?: return null
        return files.firstOrNull { f ->
            f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png") && f.nameWithoutExtension.lowercase() in CoverNames
        }?.toURI()?.toString()
    }
}
