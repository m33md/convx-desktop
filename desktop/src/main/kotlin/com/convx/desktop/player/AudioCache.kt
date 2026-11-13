/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.player

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.Http
import com.convx.desktop.core.Log
import com.convx.desktop.data.AppSettings
import com.music.innertube.models.YouTubeClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Songs are downloaded to disk while they play, in ranged chunks (the phone uses the same
 * trick to avoid YouTube cutting long requests off). Playback reads the growing file, so it
 * starts after the first chunk, and a replay needs no network at all. A jump past what has
 * arrived fetches from there at once and fills in the rest afterwards, so a jump near the end
 * of an hour-long mix plays as soon as a jump in a short song does.
 */
object AudioCache {
    private const val Tag = "AudioCache"
    private const val ChunkBytes = 1024L * 1024L

    /** Room left free on the disk beside the song being saved, for Windows and the log. */
    private const val SpareBytes = 64L * 1024L * 1024L

    /** No song's audio comes near this; a download that passes it is going wrong. */
    private const val MaxSongBytes = 2L * 1024L * 1024L * 1024L

    /** The phone's "Max song cache size": 1 GB unless changed, -1 for no cap. */
    private val maxBytes: Long
        get() = AppSettings.value.maxSongCacheMb.let { if (it < 0) Long.MAX_VALUE else it * 1024L * 1024L }

    val dir: File by lazy { File(AppDirs.cache, "audio").apply { mkdirs() } }

    private val active = ConcurrentHashMap<String, Download>()

    /**
     * What a download that stopped part way (a refusal, a lost connection, a skip) had saved, by
     * file name, so the next try at the same file carries on instead of starting again.
     */
    private class Partial(val mimeType: String, val length: Long, val held: java.util.TreeMap<Long, Long>)

    private val partials = ConcurrentHashMap<String, Partial>()

    /** How many of an HLS song's pieces are fetched at once. */
    private const val PiecesAhead = 4

    /** How many times in a row one download may swap to a fresh address before it gives up. */
    private const val MaxRenewals = 3

    /** An address that served this much before failing counts as having worked: the count starts again. */
    private const val RenewalEarnedBytes = 8L * 1024L * 1024L

    /**
     * A fresh address for the same file when YouTube stops serving a download at a byte
     * position, or null when there isn't one. Only YouTube's own addresses can be renewed.
     */
    @Volatile var renewUrl: (stream: ResolvedStream, position: Long, refused: Set<String>) -> ResolvedStream? = { stream, position, refused ->
        if ("googlevideo.com" !in stream.url) null else runBlocking { StreamResolver.renew(stream, position, refused) }
    }

    /** A finished download: the data file plus its ".ok" marker (Windows can't rename a file a reader holds open). */
    fun completeFile(videoId: String): File? =
        dir.listFiles { f -> f.name.startsWith("$videoId.") && f.name.endsWith(".ok") }
            ?.firstOrNull()
            ?.let { File(dir, it.name.removeSuffix(".ok")) }
            ?.takeIf { it.isFile }

    fun isCached(videoId: String) = completeFile(videoId) != null

    /** What a module's copy of a song is saved as; YouTube's are always "webm" or "m4a". */
    private val ModuleContainers = setOf("flac", "mp3", "ogg", "wav", "mp4")

    /** A finished copy of [videoId] that came from a module. */
    fun completeModuleFile(videoId: String): File? =
        ModuleContainers.firstNotNullOfOrNull { completeFile(videoId, it) }

    /**
     * Whether the next play of [videoId] needs no network: with modules on, only a module's copy
     * counts, since a module is asked before a copy saved from YouTube is used.
     */
    fun isCachedForPlayback(videoId: String) =
        if (ModuleStreams.active) completeModuleFile(videoId) != null else isCached(videoId)

    /** A finished download of [videoId] in [container] ("webm" or "m4a") only. */
    private fun completeFile(videoId: String, container: String): File? =
        File(dir, "$videoId.$container").takeIf { it.isFile && File(dir, "$videoId.$container.ok").isFile }

    /**
     * The whole file for [stream], for a download to keep: joins the player's download when it
     * fetches the same URL, waits for one in another format rather than cut it off, and
     * otherwise fetches it the way playback does.
     */
    suspend fun fetchComplete(stream: ResolvedStream, onProgress: (done: Long, total: Long) -> Unit): File {
        val id = stream.videoId
        while (true) {
            currentCoroutineContext().ensureActive()
            completeFile(id, stream.container)?.let { return it }
            val existing = active[id]
            val download = when {
                existing != null && existing.running && existing.isSameFile(stream) -> existing
                // The player is fetching this song in another format; let it finish first.
                existing != null && existing.running -> {
                    delay(500)
                    continue
                }
                else -> {
                    val mine = Download(stream)
                    val placed = active.compute(id) { _, current -> if (current == null || !current.running) mine else current }
                    if (placed !== mine) continue
                    mine.start()
                    mine
                }
            }
            while (download.running) {
                onProgress(download.downloaded, download.totalBytes)
                delay(300)
            }
            if (download.finished) return download.file
            if (download.failed) throw download.error ?: IOException("Download failed")
            // Cancelled under us (the player skipped or retried): go round again.
        }
    }

    /** Starts (or joins) the download for [stream] and returns a reader positioned at 0. */
    fun open(stream: ResolvedStream): CachedAudio {
        // A module's stream is saved in its own format, so a copy from YouTube doesn't stand in for it.
        val saved = if (stream.module != null) completeFile(stream.videoId, stream.container) else completeFile(stream.videoId)
        saved?.let { file ->
            file.setLastModified(System.currentTimeMillis())
            return CachedAudio.Complete(file)
        }
        val download = active.compute(stream.videoId) { _, existing ->
            if (existing != null && !existing.failed && existing.isSameFile(stream)) existing
            else {
                existing?.cancel()
                Download(stream).also { it.start() }
            }
        }!!
        return CachedAudio.Growing(download)
    }

    /** Warms the next track so the switch is instant. */
    fun prefetch(stream: ResolvedStream) {
        val saved = if (stream.module != null) completeFile(stream.videoId, stream.container) else completeFile(stream.videoId)
        if (saved != null || active.containsKey(stream.videoId)) return
        active[stream.videoId] = Download(stream).also { it.start() }
    }

    fun cancel(videoId: String) {
        active.remove(videoId)?.cancel()
    }

    /** Cancels [videoId]'s download and drops any saved copy, so the next play fetches it afresh. */
    fun discard(videoId: String) {
        active.remove(videoId)?.cancel(keepSaved = false)
        partials.keys.removeIf { it.startsWith("$videoId.") }
        dir.listFiles { f -> f.name.startsWith("$videoId.") && f.name.endsWith(".ok") }?.forEach { marker ->
            runCatching {
                marker.delete()
                File(dir, marker.name.removeSuffix(".ok")).delete()
            }
        }
    }

    /** Free space on the cache's disk, or null when Windows won't say. */
    fun freeBytes(): Long? = runCatching { java.nio.file.Files.getFileStore(dir.toPath()).usableSpace }.getOrNull()

    /**
     * Makes sure [bytes] more fit on the disk, giving up the songs played longest ago if that's
     * what it takes. Throws [DiskFullException] when even an empty cache wouldn't make room.
     */
    private fun makeRoom(bytes: Long, keep: String) {
        val needed = bytes + SpareBytes
        val free = freeBytes() ?: return
        if (free >= needed) return
        var short = needed - free
        val songs = dir.listFiles { f -> f.name.endsWith(".ok") }
            ?.map { File(dir, it.name.removeSuffix(".ok")) }
            ?.sortedBy { it.lastModified() }.orEmpty()
        for (file in songs) {
            if (short <= 0) break
            val id = file.name.substringBefore('.')
            if (id == keep || active.containsKey(id)) continue
            val size = file.length()
            // A song open in the player can't be deleted on Windows; it keeps its marker then.
            if (file.delete()) {
                File(dir, file.name + ".ok").delete()
                short -= size
            }
        }
        if (short > 0) {
            Log.w(Tag, "Only ${free / 1_048_576} MB free on the disk; ${needed / 1_048_576} MB needed for $keep")
            throw DiskFullException()
        }
    }

    /** Drops data files a previous run left unfinished. */
    fun cleanupPartials() {
        dir.listFiles()?.forEach { f ->
            if (!f.name.endsWith(".ok") && !File(dir, f.name + ".ok").exists() &&
                !active.containsKey(f.name.substringBefore('.'))
            ) f.delete()
        }
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Every song kept whole in the cache, newest first: the phone's "Cached" playlist. */
    fun cachedIds(): List<String> =
        dir.listFiles { f -> f.name.endsWith(".ok") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { it.name.substringBefore('.') }
            .distinct()

    fun clear() {
        active.values.forEach { it.cancel(keepSaved = false) }
        active.clear()
        partials.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Removes the songs played longest ago until the cache fits its cap. [keep] is a song that
     * just finished downloading: something is about to read it, so even a cache that's off
     * holds it until the next one lands.
     */
    fun trim(keep: String? = null) {
        val files = dir.listFiles { f -> f.name.endsWith(".ok") }
            ?.map { File(dir, it.name.removeSuffix(".ok")) }
            ?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        val cap = maxBytes
        for (file in files) {
            if (total <= cap) break
            val id = file.name.substringBefore('.')
            if (id == keep || active.containsKey(id)) continue
            total -= file.length()
            File(dir, file.name + ".ok").delete()
            file.delete()
        }
    }

    class Download(stream: ResolvedStream) {
        val videoId = stream.videoId

        /** The stream being fetched; its address changes when YouTube stops serving the old one. */
        @Volatile var stream: ResolvedStream = stream
            private set
        val url: String get() = stream.url

        /** "webm" or "m4a": also the name FFmpeg knows the container by. */
        val container = stream.container
        val file = File(dir, "${stream.videoId}.${stream.container}")
        private val marker = File(dir, "${stream.videoId}.${stream.container}.ok")
        private val lock = ReentrantLock()
        private val progress = lock.newCondition()

        @Volatile var totalBytes: Long = stream.contentLength
            private set
        @Volatile var downloaded: Long = 0
            private set
        @Volatile var failed = false
            private set
        @Volatile var error: IOException? = null
            private set
        @Volatile var cancelled = false
            private set
        @Volatile var finished = false
            private set

        /**
         * What's on disk, as [start, end) byte ranges by start. A jump can leave gaps, which the
         * download fills in once it reaches the end. [downloaded] is their total.
         */
        private val held = java.util.TreeMap<Long, Long>()

        /** Where a reader wants bytes the download isn't about to reach, or -1. */
        @Volatile private var jumpTo = -1L

        /** The byte the download is fetching now. */
        @Volatile private var fetching = 0L

        /** For an HLS song: how many seconds of it are on disk so far, by its playlist. */
        @Volatile var secondsSaved = 0.0
            private set

        // For an HLS song: where each piece starts in the file and at which sample, so a jump can
        // be found between two of them. Not used at all if one piece's start couldn't be read.
        private val pieceStarts = ArrayList<LongArray>()
        private var marksBroken = false

        /** For an HLS song: a (byte, sample) pair for the start of each piece on disk. */
        fun seekPoints(): List<LongArray> = lock.withLock { if (marksBroken) emptyList() else pieceStarts.toList() }

        val running: Boolean get() = !finished && !failed && !cancelled

        /** Notes [from, to) as on disk, joining it to the ranges it touches. Under [lock]. */
        private fun addHeld(from: Long, to: Long) {
            if (to <= from) return
            var start = from
            var end = to
            val before = held.floorEntry(start)
            if (before != null && before.value >= start) {
                start = before.key
                end = maxOf(end, before.value)
            }
            val after = held.tailMap(start, true).entries.iterator()
            while (after.hasNext()) {
                val range = after.next()
                if (range.key > end) break
                end = maxOf(end, range.value)
                after.remove()
            }
            held[start] = end
            downloaded = held.entries.sumOf { it.value - it.key }
        }

        /** How many bytes from [position] on are on disk in one run. Under [lock]. */
        private fun heldFrom(position: Long): Long {
            val range = held.floorEntry(position) ?: return 0
            return (range.value - position).coerceAtLeast(0)
        }

        /** The first byte at or after [from] that isn't on disk yet, or null when none is missing. Under [lock]. */
        private fun firstGap(from: Long): Long? {
            var position = from
            while (true) {
                val run = heldFrom(position)
                if (run <= 0) return position.takeIf { it < totalBytes }
                position += run
            }
        }

        /** Where the next range on disk after [position] starts, if any. Under [lock]. */
        private fun nextHeld(position: Long): Long? = held.higherKey(position)

        /** Bytes readable from [position] now, without waiting. */
        fun readableFrom(position: Long): Long = lock.withLock { heldFrom(position) }

        /** Whether [other] is this download's file: the same address, or the same format and length under a renewed one. */
        fun isSameFile(other: ResolvedStream): Boolean =
            other.url == url || (other.mimeType == stream.mimeType && other.contentLength > 0 && other.contentLength == stream.contentLength)

        fun start() {
            thread(name = "convx-download-$videoId", isDaemon = true) { run() }
        }

        /** Stops the download; with [keepSaved] what it saved is picked up by the next try at the file. */
        fun cancel(keepSaved: Boolean = true) {
            if (!keepSaved) this.keepSaved = false
            cancelled = true
            lock.withLock { progress.signalAll() }
        }

        @Volatile private var keepSaved = true

        private fun run() {
            try {
                marker.delete()
                val hls = stream.hls
                makeRoom(totalBytes.takeIf { it > 0 } ?: hls?.estimatedBytes ?: (8 * ChunkBytes), keep = videoId)
                if (hls != null) RandomAccessFile(file, "rw").use { out ->
                    out.setLength(0)
                    joinPieces(hls, out)
                } else RandomAccessFile(file, "rw").use { out ->
                    if (!adoptPartial()) out.setLength(0)
                    var position = 0L
                    val refused = mutableSetOf<String>()
                    var renewals = 0
                    var sinceRenewal = 0L
                    while (!cancelled) {
                        val known = totalBytes > 0
                        if (known) {
                            // A reader jumped ahead: fetch from there, then come back for what was skipped.
                            val jump = jumpTo
                            if (jump >= 0) {
                                jumpTo = -1
                                position = jump
                            }
                            position = lock.withLock { firstGap(position) ?: firstGap(0) } ?: break
                        }
                        if (position > MaxSongBytes) throw IOException("Stopped a download that went past ${MaxSongBytes / 1_048_576} MB")
                        // Up to a chunk, stopping where bytes already on disk begin.
                        val end = if (known) minOf(position + ChunkBytes, lock.withLock { nextHeld(position) } ?: totalBytes, totalBytes) - 1
                        else position + ChunkBytes - 1
                        fetching = position
                        var attempt = 0
                        var read = -1L
                        while (read < 0 && !cancelled) {
                            val request = Request.Builder().url(url)
                                .header("Range", "bytes=$position-$end")
                                .header("User-Agent", YouTubeClient.USER_AGENT_WEB)
                                .build()
                            try {
                                read = fetch(request, out, position)
                            } catch (e: IOException) {
                                // YouTube refusing an address part way through: carry on from here under a fresh one.
                                // A long song can outlast several addresses; each one that served a good stretch earns another.
                                if (sinceRenewal >= RenewalEarnedBytes) renewals = 0
                                if (e is HttpStatusException && renewals < MaxRenewals && !cancelled) {
                                    renewals++
                                    sinceRenewal = 0
                                    refused += stream.client
                                    val fresh = runCatching { renewUrl(stream, position, refused.toSet()) }
                                        .onFailure { Log.w(Tag, "Couldn't renew $videoId's address", it) }
                                        .getOrNull()
                                    if (fresh != null && fresh.url != url) {
                                        Log.w(Tag, "$videoId: HTTP ${e.code} at $position/$totalBytes via ${stream.client}; carrying on via ${fresh.client}")
                                        stream = fresh
                                        continue
                                    }
                                }
                                if (++attempt >= 3 || e is HttpStatusException) throw e
                                Thread.sleep(500L * attempt)
                            }
                        }
                        if (read <= 0) {
                            if (known && jumpTo >= 0) continue
                            if (known && !cancelled && lock.withLock { firstGap(0) } != null) {
                                throw IOException("The server stopped at $position of $totalBytes bytes")
                            }
                            break
                        }
                        position += read
                        sinceRenewal += read
                        if (!known && read < ChunkBytes) {
                            totalBytes = position
                            break
                        }
                    }
                }
                // A replacement download may already own this file; leave it alone.
                if (cancelled) {
                    keepPartial()
                    return
                }
                if (totalBytes <= 0) totalBytes = downloaded
                partials.remove(file.name)
                marker.writeText(totalBytes.toString())
                lock.withLock {
                    finished = true
                    progress.signalAll()
                }
                active.remove(videoId, this)
                trim(keep = videoId)
            } catch (e: Exception) {
                val failure = when {
                    e is DiskFullException -> e
                    isDiskFull(e) -> DiskFullException(e)
                    else -> e as? IOException ?: IOException(e)
                }
                Log.w(Tag, "Download of $videoId via ${stream.client} failed at $downloaded/$totalBytes (${freeBytes()?.let { "${it / 1_048_576} MB free" } ?: "free space unknown"})", e)
                keepPartial()
                lock.withLock {
                    failed = true
                    error = failure
                    progress.signalAll()
                }
                active.remove(videoId, this)
            }
        }

        /** Takes over what an earlier, stopped download of this same file saved. */
        private fun adoptPartial(): Boolean {
            val kept = partials.remove(file.name) ?: return false
            val end = kept.held.lastEntry()?.value ?: return false
            if (kept.mimeType != stream.mimeType || kept.length != totalBytes || totalBytes <= 0 || file.length() < end) return false
            lock.withLock {
                kept.held.forEach { (from, to) -> addHeld(from, to) }
                progress.signalAll()
            }
            Log.i(Tag, "$videoId: carrying on with ${downloaded / 1024} KB already saved")
            return true
        }

        /** Remembers what this download saved, unless another download owns the file now. */
        private fun keepPartial() {
            if (!keepSaved || stream.hls != null || totalBytes <= 0) return
            val owner = active[videoId]
            if (owner != null && owner !== this) return
            val ranges = lock.withLock { java.util.TreeMap(held) }
            if (ranges.isNotEmpty()) partials[file.name] = Partial(stream.mimeType, totalBytes, ranges)
        }

        /**
         * An HLS song: the FLAC header, then each piece's FLAC frames in order, so the file is a
         * plain .flac from the first bytes on. A few pieces are fetched ahead while one is written.
         */
        private fun joinPieces(media: HlsAudio.Media, out: RandomAccessFile) {
            out.write(media.header)
            var position = media.header.size.toLong()
            lock.withLock {
                addHeld(0, position)
                progress.signalAll()
            }
            val fetcher = java.util.concurrent.Executors.newFixedThreadPool(PiecesAhead) { task ->
                Thread(task, "convx-pieces-$videoId").apply { isDaemon = true }
            }
            // A playlist without durations is taken as even pieces of the song's length.
            val even = if (media.segments.all { it.seconds <= 0 }) (media.durationMs ?: 0L) / 1000.0 / media.segments.size else null
            var seconds = 0.0
            try {
                val pending = ArrayDeque<java.util.concurrent.Future<ByteArray>>()
                var next = 0
                fun queue() {
                    val part = media.segments[next++]
                    pending.addLast(fetcher.submit<ByteArray> { fetchPiece(part) })
                }
                // The first piece comes alone, with the whole line to itself, so the song starts
                // sooner; then one more is fetched ahead for each piece in, up to PiecesAhead. A
                // slow line shared four ways would bring the next piece in late.
                var done = 0
                queue()
                while (pending.isNotEmpty() && !cancelled) {
                    val piece = try {
                        pending.removeFirst().get()
                    } catch (e: java.util.concurrent.ExecutionException) {
                        throw e.cause as? IOException ?: IOException(e.cause)
                    }
                    val part = media.segments[next - pending.size - 1]
                    done++
                    while (next < media.segments.size && pending.size < minOf(PiecesAhead, done + 1)) queue()
                    val written = HlsAudio.writeFrames(piece, media.trackId, out, position, media.info.blockSize)
                    if (position + written.bytes > MaxSongBytes) throw IOException("Stopped a download that went past ${MaxSongBytes / 1_048_576} MB")
                    seconds += even ?: part.seconds
                    lock.withLock {
                        if (written.bytes > 0) {
                            // Only marks that move forward: one out of order would send FFmpeg's search astray.
                            val last = pieceStarts.lastOrNull()
                            val first = written.firstSample?.takeIf { last == null || (it > last[1] && position > last[0]) }
                            if (first != null) pieceStarts += longArrayOf(position, first) else marksBroken = true
                        }
                        addHeld(position, position + written.bytes)
                        position += written.bytes
                        secondsSaved = seconds
                        progress.signalAll()
                    }
                }
            } finally {
                fetcher.shutdownNow()
            }
            if (cancelled) return
            if (position == media.header.size.toLong()) throw IOException("The song's pieces held no audio")
            totalBytes = position
        }

        /** One piece, tried three times; a refusal (HTTP 4xx) isn't tried again. */
        private fun fetchPiece(part: HlsAudio.Part): ByteArray {
            var attempt = 0
            while (true) {
                if (cancelled) throw IOException("Download cancelled")
                try {
                    return HlsAudio.fetchPart(part)
                } catch (e: IOException) {
                    if (e is HlsAudio.Unsupported || (e is HttpStatusException && e.code in 400..499) || ++attempt >= 3) throw e
                    Thread.sleep(500L * attempt)
                }
            }
        }

        private fun fetch(request: Request, out: RandomAccessFile, position: Long): Long {
            Http.client.newCall(request).execute().use { response ->
                if (response.code == 416) return 0
                if (!response.isSuccessful) throw HttpStatusException(response.code, stream.client)
                // A 200 is the whole file: the server ignored the range.
                val whole = response.code == 200
                if (totalBytes <= 0) {
                    val size = if (whole) response.body.contentLength()
                    else response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    size?.takeIf { it > 0 }?.let { totalBytes = it }
                }
                val body = response.body.byteStream()
                if (whole && position > 0) {
                    var left = position
                    while (left > 0 && !cancelled) {
                        val skipped = body.skip(left)
                        if (skipped <= 0) {
                            if (body.read() < 0) return 0
                            left--
                        } else {
                            left -= skipped
                        }
                    }
                }
                val buffer = ByteArray(64 * 1024)
                var written = 0L
                out.seek(position)
                try {
                    while (!cancelled) {
                        val n = body.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        fetching = position + written
                        // Let readers start before the whole chunk lands.
                        if (written % (256 * 1024) < n) lock.withLock {
                            addHeld(position, position + written)
                            progress.signalAll()
                        }
                        // A reader jumped somewhere else: go there now rather than finish this chunk.
                        val jump = jumpTo
                        if (jump >= 0 && (jump < position || jump > position + written + ChunkBytes)) break
                    }
                } finally {
                    lock.withLock {
                        addHeld(position, position + written)
                        progress.signalAll()
                    }
                }
                return written
            }
        }

        /**
         * For an HLS song, blocks until [seconds] of it are on disk and a piece starting after
         * [sample] has arrived, or the download ends. FFmpeg can only find a time in the part of
         * a FLAC file that has arrived, and finds it between two piece starts ([seekPoints]):
         * past the last one it looks for the end of the file, which isn't there yet. So a jump
         * waits here first, as a jump in a YouTube song waits for its bytes. Gives up when nothing
         * arrives for [stallMs], and returns false at once when [abandon] says the jump is no
         * longer wanted.
         */
        fun awaitSeek(seconds: Double, sample: Long, stallMs: Long = 30_000, abandon: () -> Boolean = { false }): Boolean {
            lock.withLock {
                fun ready() = finished || (secondsSaved >= seconds && (marksBroken || (pieceStarts.lastOrNull()?.let { it[1] > sample } ?: false)))
                var seen = secondsSaved
                var deadline = System.currentTimeMillis() + stallMs
                while (!ready()) {
                    error?.let { throw it }
                    if (cancelled) throw IOException("Download cancelled")
                    if (abandon()) return false
                    if (secondsSaved > seen) {
                        seen = secondsSaved
                        deadline = System.currentTimeMillis() + stallMs
                    }
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) throw IOException("Timed out waiting for audio data")
                    progress.await(minOf(left, 200), java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                return true
            }
        }

        /**
         * Blocks until [position] is readable or the download ends, and returns how many bytes are
         * readable from there. A position the download isn't about to reach is fetched next. Gives
         * up only when nothing arrives for [stallMs]: a big song far from its download edge just
         * takes longer.
         */
        fun awaitAvailable(position: Long, stallMs: Long = 30_000): Long {
            lock.withLock {
                var seen = downloaded
                var deadline = System.currentTimeMillis() + stallMs
                while (true) {
                    val ahead = heldFrom(position)
                    if (ahead > 0) return ahead
                    if (finished) return 0
                    error?.let { throw it }
                    if (cancelled) throw IOException("Download cancelled")
                    if (stream.hls == null && totalBytes > 0 && position < totalBytes) {
                        val edge = fetching
                        if (position !in edge until edge + 2 * ChunkBytes) jumpTo = position
                    }
                    if (downloaded > seen) {
                        seen = downloaded
                        deadline = System.currentTimeMillis() + stallMs
                    }
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) throw IOException("Timed out waiting for audio data")
                    progress.await(minOf(left, 200), java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    /** YouTube (or another server) answered [code]; [client] is whose address it refused. */
    class HttpStatusException(val code: Int, val client: String? = null) : IOException("HTTP $code")

    /** The disk has no room for the song. */
    class DiskFullException(cause: Throwable? = null) : IOException("Not enough free disk space", cause)

    /** Whether [e] is Windows (or Linux) saying the disk is full. */
    fun isDiskFull(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { cause ->
            val text = cause.message.orEmpty().lowercase()
            cause is DiskFullException || "not enough space" in text || "no space left" in text || "disk full" in text
        }
}

/** The source the decoder reads: a finished file, or a download still in flight. */
sealed interface CachedAudio {
    data class Complete(val file: File) : CachedAudio
    data class Growing(val download: AudioCache.Download) : CachedAudio
}

/**
 * An InputStream over a download in progress, with the mark/reset/skip semantics FFmpeg's
 * custom IO uses to seek: mark at 0, reset to 0, skip forward to any offset. Reads past
 * the downloaded edge wait for the bytes instead of reporting end of file.
 */
class GrowingFileInputStream(private val download: AudioCache.Download) : InputStream() {
    private var position = 0L
    private var file: RandomAccessFile? = null

    /**
     * Why the last read failed, or null after one that worked. FFmpeg is only told "-1" and then
     * reports something unrelated, so the player asks here for the real reason.
     */
    @Volatile var failure: IOException? = null
        private set

    private fun handle(): RandomAccessFile = file ?: RandomAccessFile(download.file, "r").also { file = it }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        try {
            val total = download.totalBytes
            if (total > 0 && position >= total) return -1
            val available = download.awaitAvailable(position)
            if (available <= 0) return -1
            val raf = handle()
            raf.seek(position)
            val n = raf.read(b, off, minOf(len.toLong(), available).toInt())
            if (n > 0) position += n
            failure = null
            return n
        } catch (e: IOException) {
            failure = e
            throw e
        }
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val total = download.totalBytes
        // Saturating: FFmpeg can ask to skip "to the end" with a huge n.
        val limit = if (total > 0) total else maxOf(download.downloaded, position)
        val target = if (n >= limit - position) limit else position + n
        val skipped = target - position
        position = target
        return skipped
    }

    override fun available(): Int = download.readableFrom(position).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    override fun markSupported() = true
    override fun mark(readlimit: Int) = Unit
    override fun reset() {
        position = 0
    }

    override fun close() {
        file?.close()
        file = null
    }
}
