/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.player

import com.convx.desktop.core.Http
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.util.Locale

/**
 * A module's song sent as an HLS playlist: one recording cut into short pieces, each at its own
 * address. Tidal sends every quality this way, as FLAC in fragmented MP4 pieces. That is the form
 * taken here: the FLAC frames inside the pieces are written one after another behind the
 * stream's own FLAC header, so what lands on disk is a plain .flac file that plays, seeks and is
 * kept like any other song. Encrypted, live, or non-FLAC playlists are turned away.
 */
object HlsAudio {
    /** Why a playlist can't be played here, in a few words for the log. */
    class Unsupported(reason: String) : IOException(reason)

    /** One piece: its address, which bytes of it when the playlist names a range, and how many seconds it plays. */
    data class Part(val url: String, val offset: Long? = null, val length: Long? = null, val seconds: Double = 0.0)

    /** What the stream's FLAC header says: the numbers the quality badge shows. */
    /** [blockSize] is the most samples a frame holds: every frame's but the last in most files. */
    data class FlacInfo(val sampleRate: Int, val channels: Int, val bitDepth: Int, val totalSamples: Long, val blockSize: Int = 0)

    /** A playlist ready to download: the FLAC header to start the file with, then the pieces in order. */
    class Media(
        val playlistUrl: String,
        val segments: List<Part>,
        /** "fLaC" and the stream's metadata blocks: the start of the .flac file. */
        val header: ByteArray,
        /** The MP4 track the FLAC frames belong to. */
        val trackId: Long,
        val info: FlacInfo,
        /** The playlist's own figure for the stream, in bits per second, when it gives one. */
        val bandwidth: Long?,
    ) {
        val durationMs: Long?
            get() = if (info.totalSamples > 0 && info.sampleRate > 0) info.totalSamples * 1000 / info.sampleRate else null

        /** Roughly how big the file will be, to make room for it: FLAC is usually well under raw PCM. */
        val estimatedBytes: Long
            get() {
                val seconds = (durationMs ?: (segments.size * 4_000L)) / 1000.0
                val bits = bandwidth?.takeIf { it > 0 } ?: (info.sampleRate.toLong() * info.channels * info.bitDepth)
                return (seconds * bits / 8).toLong() + header.size
            }
    }

    private const val MaxPlaylistBytes = 4L * 1024 * 1024
    private const val MaxInitBytes = 1L * 1024 * 1024
    private const val MaxSegmentBytes = 64L * 1024 * 1024
    private const val MaxSegments = 20_000

    /** Whether a module's answer is an HLS playlist rather than one audio file. */
    fun isPlaylist(url: String, mimeType: String?, streamType: String?): Boolean =
        streamType.equals("hls", ignoreCase = true) ||
            mimeType?.contains("mpegurl", ignoreCase = true) == true ||
            runCatching { URI(url).path.orEmpty().endsWith(".m3u8", ignoreCase = true) }.getOrDefault(false)

    /**
     * Reads the playlist at [url] (a master playlist's FLAC version is followed) and its setup
     * piece, and checks that its pieces carry FLAC that plays here. Throws [Unsupported] with the
     * reason when they don't, or an IOException when the playlist can't be fetched.
     */
    fun load(url: String, client: OkHttpClient = Http.client): Media {
        var playlistUrl = url
        var text = fetchText(playlistUrl, client)
        var bandwidth: Long? = null
        if (isMaster(text)) {
            val variant = pickVariant(text, playlistUrl) ?: throw Unsupported("the playlist has no FLAC version")
            playlistUrl = variant.url
            bandwidth = variant.averageBandwidth ?: variant.bandwidth
            text = fetchText(playlistUrl, client)
            if (isMaster(text)) throw Unsupported("the playlist only names other playlists")
        }
        val playlist = parseMedia(text, playlistUrl)
        val init = fetchPart(playlist.init, client, MaxInitBytes)
        val flac = readInit(init)
        return Media(playlistUrl, playlist.segments, flac.header, flac.trackId, flac.info, bandwidth)
    }

    /** One piece's bytes. A 4xx answer is thrown as [AudioCache.HttpStatusException]. */
    fun fetchPart(part: Part, client: OkHttpClient = Http.client, limit: Long = MaxSegmentBytes): ByteArray {
        val request = Request.Builder().url(part.url).header("User-Agent", Http.USER_AGENT).apply {
            if (part.length != null) header("Range", "bytes=${part.offset ?: 0}-${(part.offset ?: 0) + part.length - 1}")
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AudioCache.HttpStatusException(response.code, ModuleStreams.Client)
            val declared = response.body.contentLength()
            if (declared > limit) throw IOException("A piece of $declared bytes is too big")
            val bytes = readLimited(response.body.byteStream(), limit)
            // A server that ignores the range sends the whole file: keep the part that was asked for.
            if (part.length != null && response.code == 200 && bytes.size.toLong() > part.length) {
                val from = (part.offset ?: 0).toInt()
                if (from + part.length > bytes.size) throw IOException("A piece is shorter than its playlist says")
                return bytes.copyOfRange(from, from + part.length.toInt())
            }
            return bytes
        }
    }

    /** What one piece added to the .flac file: its length in bytes, and the sample its first frame starts at (null when unknown). */
    class Written(val bytes: Long, val firstSample: Long?)

    /**
     * Writes the FLAC frames of one piece to [out] at [position]. The frames are found from the
     * piece's own index (moof/traf/trun), so padding or other tracks in the piece are left out.
     * [blockSize] is the stream's, from its header, to count where the frames sit in the song.
     */
    fun writeFrames(piece: ByteArray, trackId: Long, out: RandomAccessFile, position: Long, blockSize: Int = 0): Written {
        val frames = flacFrames(piece, trackId)
        out.seek(position)
        var written = 0L
        for (range in frames) {
            out.write(piece, range.first, range.last - range.first + 1)
            written += range.last - range.first + 1
        }
        return Written(written, frames.firstOrNull()?.let { frameSamples(piece, it, blockSize) }?.first)
    }

    /**
     * The FLAC frame header at the start of [frame]: the sample the frame starts at and how many
     * samples it holds. Null when it isn't a frame header. A frame of a fixed block size stream
     * carries its number, so it starts at that times the stream's [blockSize] (a short last frame
     * would give the wrong answer from its own size); otherwise it carries its first sample.
     */
    internal fun frameSamples(data: ByteArray, frame: IntRange, blockSize: Int = 0): Pair<Long, Int>? {
        fun at(i: Int) = if (i <= frame.last && i < data.size) data[i].toInt() and 0xFF else -1
        val start = frame.first
        if (at(start) != 0xFF || (at(start + 1) and 0xFE) != 0xF8) return null
        val variable = (at(start + 1) and 1) == 1
        val sizeCode = at(start + 2) shr 4
        var p = start + 4
        val lead = at(p++)
        if (lead < 0) return null
        val (bits, more) = when {
            lead and 0x80 == 0 -> lead to 0
            lead and 0xE0 == 0xC0 -> (lead and 0x1F) to 1
            lead and 0xF0 == 0xE0 -> (lead and 0x0F) to 2
            lead and 0xF8 == 0xF0 -> (lead and 0x07) to 3
            lead and 0xFC == 0xF8 -> (lead and 0x03) to 4
            lead and 0xFE == 0xFC -> (lead and 0x01) to 5
            lead == 0xFE -> 0 to 6
            else -> return null
        }
        var number = bits.toLong()
        repeat(more) {
            val c = at(p++)
            if (c < 0 || c and 0xC0 != 0x80) return null
            number = (number shl 6) or (c and 0x3F).toLong()
        }
        val samples = when (sizeCode) {
            1 -> 192
            in 2..5 -> 576 shl (sizeCode - 2)
            6 -> at(p).takeIf { it >= 0 }?.plus(1) ?: return null
            7 -> if (at(p) < 0 || at(p + 1) < 0) return null else ((at(p) shl 8) or at(p + 1)) + 1
            in 8..15 -> 256 shl (sizeCode - 8)
            else -> return null
        }
        val step = if (blockSize >= 16) blockSize else samples
        return (if (variable) number else number * step) to samples
    }

    /** The FLAC header of a finished .flac file, for the badge of a song played from disk. */
    fun flacInfo(file: File): FlacInfo? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(42)
            raf.readFully(head)
            if (String(head, 0, 4, Charsets.ISO_8859_1) != "fLaC" || (head[4].toInt() and 0x7F) != 0) return null
            streamInfo(head, 8)
        }
    }.getOrNull()

    // Playlists

    private fun isMaster(text: String) = text.lineSequence().any { it.trim().startsWith("#EXT-X-STREAM-INF") }

    internal data class Variant(val url: String, val bandwidth: Long?, val averageBandwidth: Long?)

    /** The FLAC version with the most bits, or the richest one when the playlist names no codecs. */
    internal fun pickVariant(text: String, base: String): Variant? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        requireHeader(lines)
        val renditions = lines.filter { it.startsWith("#EXT-X-MEDIA:") }.map { attributes(it.substringAfter(':')) }
            .filter { it["TYPE"].equals("AUDIO", ignoreCase = true) && it["URI"] != null }
        data class Entry(val attrs: Map<String, String>, val uri: String)
        val entries = lines.mapIndexedNotNull { i, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF:")) return@mapIndexedNotNull null
            val uri = lines.drop(i + 1).firstOrNull { !it.startsWith("#") } ?: return@mapIndexedNotNull null
            Entry(attributes(line.substringAfter(':')), uri)
        }
        val named = entries.filter { it.attrs["CODECS"] != null }
        val candidates = if (named.isEmpty()) entries else named.filter { isFlacCodec(it.attrs["CODECS"].orEmpty()) }
        val best = candidates.maxByOrNull { it.attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L } ?: return null
        // Audio kept as its own rendition: follow that instead of the main playlist.
        val group = best.attrs["AUDIO"]
        val rendition = group?.let { g ->
            renditions.filter { it["GROUP-ID"] == g }.let { list -> list.firstOrNull { it["DEFAULT"].equals("YES", true) } ?: list.firstOrNull() }
        }
        val uri = rendition?.get("URI") ?: best.uri
        return Variant(
            resolve(base, uri),
            best.attrs["BANDWIDTH"]?.toLongOrNull(),
            best.attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull(),
        )
    }

    private fun isFlacCodec(codecs: String) = codecs.split(',').any { it.trim().lowercase(Locale.ROOT) == "flac" }

    internal data class MediaPlaylist(val init: Part, val segments: List<Part>)

    internal fun parseMedia(text: String, base: String): MediaPlaylist {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        requireHeader(lines)
        var init: Part? = null
        var ended = false
        var range: Pair<Long, Long?>? = null
        var seconds = 0.0
        val ends = HashMap<String, Long>()
        val segments = ArrayList<Part>()
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:") -> {
                    val method = attributes(line.substringAfter(':'))["METHOD"].orEmpty()
                    if (!method.equals("NONE", ignoreCase = true)) throw Unsupported("the pieces are encrypted ($method)")
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = attributes(line.substringAfter(':'))
                    val url = resolve(base, attrs["URI"] ?: throw Unsupported("the setup piece has no address"))
                    val byteRange = attrs["BYTERANGE"]?.let(::byteRange)
                    val part = Part(url, byteRange?.let { it.second ?: 0L }, byteRange?.first)
                    if (init != null && init != part) throw Unsupported("the pieces change format part way")
                    init = part
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> range = byteRange(line.substringAfter(':'))
                line.startsWith("#EXTINF:") -> seconds = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line == "#EXT-X-ENDLIST" -> ended = true
                line.startsWith("#") -> Unit
                else -> {
                    val url = resolve(base, line)
                    val part = range?.let { (length, offset) ->
                        val start = offset ?: ends[url] ?: 0L
                        ends[url] = start + length
                        Part(url, start, length, seconds)
                    } ?: Part(url, seconds = seconds)
                    range = null
                    seconds = 0.0
                    segments += part
                    if (segments.size > MaxSegments) throw Unsupported("the playlist has too many pieces")
                }
            }
        }
        if (!ended) throw Unsupported("it's a live playlist")
        if (segments.isEmpty()) throw Unsupported("the playlist has no pieces")
        // Without a setup piece, the pieces are MPEG-TS or bare audio, which never carry FLAC.
        return MediaPlaylist(init ?: throw Unsupported("the pieces aren't MP4"), segments)
    }

    private fun requireHeader(lines: List<String>) {
        if (lines.firstOrNull()?.removePrefix("﻿") != "#EXTM3U") throw Unsupported("it isn't an HLS playlist")
    }

    /** "1000@200": a length, and where it starts when given. */
    private fun byteRange(value: String): Pair<Long, Long?> {
        val length = value.substringBefore('@').trim().toLongOrNull() ?: throw Unsupported("a byte range can't be read")
        val offset = if ('@' in value) value.substringAfter('@').trim().toLongOrNull() ?: throw Unsupported("a byte range can't be read") else null
        return length to offset
    }

    /** An attribute list: KEY=value pairs, where a quoted value may hold commas. */
    internal fun attributes(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var i = 0
        while (i < text.length) {
            val eq = text.indexOf('=', i)
            if (eq < 0) break
            val key = text.substring(i, eq).trim().trimStart(',').trim()
            var j = eq + 1
            val value: String
            if (j < text.length && text[j] == '"') {
                val close = text.indexOf('"', j + 1).takeIf { it >= 0 } ?: text.length
                value = text.substring(j + 1, close)
                j = close + 1
            } else {
                val comma = text.indexOf(',', j).takeIf { it >= 0 } ?: text.length
                value = text.substring(j, comma).trim()
                j = comma
            }
            if (key.isNotEmpty()) result[key.uppercase(Locale.ROOT)] = value
            i = text.indexOf(',', j).let { if (it < 0) text.length else it + 1 }
        }
        return result
    }

    private fun resolve(base: String, uri: String): String {
        if (uri.startsWith("https://", true) || uri.startsWith("http://", true)) return uri
        return base.toHttpUrlOrNull()?.resolve(uri)?.toString() ?: throw Unsupported("a piece's address can't be read")
    }

    private fun fetchText(url: String, client: OkHttpClient): String {
        val request = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AudioCache.HttpStatusException(response.code, ModuleStreams.Client)
            return String(readLimited(response.body.byteStream(), MaxPlaylistBytes), Charsets.UTF_8)
        }
    }

    private fun readLimited(input: java.io.InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("A piece is bigger than ${limit / 1_048_576} MB")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    // MP4

    private class Box(val type: String, val start: Int, val body: Int, val end: Int)

    /** The boxes between [from] and [to]; a box that runs past [to] ends the list. */
    private fun boxes(data: ByteArray, from: Int, to: Int): List<Box> {
        val list = ArrayList<Box>()
        var at = from
        while (at + 8 <= to) {
            var size = u32(data, at)
            val type = String(data, at + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                if (at + 16 > to) break
                size = u64(data, at + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - at).toLong()
            }
            if (size < header || at + size > to) break
            list += Box(type, at, at + header, (at + size).toInt())
            at = (at + size).toInt()
        }
        return list
    }

    private fun child(data: ByteArray, box: Box, type: String, skip: Int = 0): Box? =
        boxes(data, box.body + skip, box.end).firstOrNull { it.type == type }

    private class Init(val header: ByteArray, val trackId: Long, val info: FlacInfo)

    /** The setup piece (ftyp + moov): finds the FLAC track and its STREAMINFO. */
    private fun readInit(data: ByteArray): Init {
        val moov = boxes(data, 0, data.size).firstOrNull { it.type == "moov" } ?: throw Unsupported("the setup piece has no moov")
        var found: String? = null
        for (trak in boxes(data, moov.body, moov.end).filter { it.type == "trak" }) {
            val tkhd = child(data, trak, "tkhd") ?: continue
            val trackId = if (data[tkhd.body].toInt() == 1) u32(data, tkhd.body + 20) else u32(data, tkhd.body + 12)
            val mdia = child(data, trak, "mdia") ?: continue
            val stsd = child(data, mdia, "minf")?.let { child(data, it, "stbl") }?.let { child(data, it, "stsd") } ?: continue
            // stsd: version/flags and an entry count, then the sample entries.
            val entry = boxes(data, stsd.body + 8, stsd.end).firstOrNull() ?: continue
            found = entry.type
            when (entry.type) {
                "enca", "encv" -> throw Unsupported("the pieces are encrypted")
                "fLaC" -> {
                    // AudioSampleEntry: 28 bytes of fields before its own boxes.
                    if (boxes(data, entry.body + 28, entry.end).any { it.type == "sinf" }) throw Unsupported("the pieces are encrypted")
                    val dfla = boxes(data, entry.body + 28, entry.end).firstOrNull { it.type == "dfLa" }
                        ?: throw Unsupported("the FLAC track has no header")
                    val blocks = data.copyOfRange(dfla.body + 4, dfla.end)
                    return Init(flacHeader(blocks), trackId, readBlocks(blocks))
                }
            }
        }
        throw Unsupported(found?.let { "the pieces hold ${it.trim()}, not FLAC" } ?: "the setup piece has no audio track")
    }

    /** "fLaC" and the metadata blocks, with the last block marked as last as a .flac file needs. */
    private fun flacHeader(blocks: ByteArray): ByteArray {
        val copy = blocks.copyOf()
        var at = 0
        var last = -1
        while (at + 4 <= copy.size) {
            last = at
            val length = ((copy[at + 1].toInt() and 0xFF) shl 16) or ((copy[at + 2].toInt() and 0xFF) shl 8) or (copy[at + 3].toInt() and 0xFF)
            if (copy[at].toInt() and 0x80 != 0) break
            at += 4 + length
        }
        if (last >= 0) copy[last] = (copy[last].toInt() or 0x80).toByte()
        val end = if (last >= 0) {
            val length = ((copy[last + 1].toInt() and 0xFF) shl 16) or ((copy[last + 2].toInt() and 0xFF) shl 8) or (copy[last + 3].toInt() and 0xFF)
            minOf(copy.size, last + 4 + length)
        } else copy.size
        return "fLaC".toByteArray(Charsets.ISO_8859_1) + copy.copyOfRange(0, end)
    }

    private fun readBlocks(blocks: ByteArray): FlacInfo {
        if (blocks.size < 38 || (blocks[0].toInt() and 0x7F) != 0) throw Unsupported("the FLAC header has no STREAMINFO")
        return streamInfo(blocks, 4)
    }

    /** STREAMINFO at [at]: rate (20 bits), channels (3), bits per sample (5), total samples (36). */
    private fun streamInfo(data: ByteArray, at: Int): FlacInfo {
        val b = IntArray(8) { data[at + 10 + it].toInt() and 0xFF }
        val rate = (b[0] shl 12) or (b[1] shl 4) or (b[2] shr 4)
        val channels = ((b[2] shr 1) and 0x7) + 1
        val depth = (((b[2] and 1) shl 4) or (b[3] shr 4)) + 1
        val total = ((b[3] and 0xF).toLong() shl 32) or (b[4].toLong() shl 24) or (b[5].toLong() shl 16) or (b[6].toLong() shl 8) or b[7].toLong()
        val blockSize = ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)
        if (rate <= 0) throw Unsupported("the FLAC header can't be read")
        return FlacInfo(rate, channels, depth, total, blockSize)
    }

    /**
     * Where each FLAC frame of [trackId] sits in one piece (moof + mdat), in playing order. A
     * piece with no fragment in it (a last piece can be empty) gives none.
     */
    internal fun flacFrames(data: ByteArray, trackId: Long): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        val top = boxes(data, 0, data.size)
        if (top.firstOrNull()?.type !in PieceStarts) throw Unsupported("a piece isn't MP4")
        for ((index, moof) in top.withIndex()) {
            if (moof.type != "moof") continue
            var previousEnd = moof.start.toLong()
            for (traf in boxes(data, moof.body, moof.end).filter { it.type == "traf" }) {
                val parts = boxes(data, traf.body, traf.end)
                if (parts.any { it.type == "senc" }) throw Unsupported("the pieces are encrypted")
                val tfhd = parts.firstOrNull { it.type == "tfhd" } ?: continue
                val flags = u24(data, tfhd.body + 1)
                if (u32(data, tfhd.body + 4) != trackId) continue
                var at = tfhd.body + 8
                val baseOffset = if (flags and 0x1 != 0) u64(data, at).also { at += 8 } else null
                if (flags and 0x2 != 0) at += 4
                if (flags and 0x8 != 0) at += 4
                val defaultSize = if (flags and 0x10 != 0) u32(data, at).also { at += 4 } else null
                // Pieces are fetched on their own, so offsets count from the moof (CMAF's default-base-is-moof).
                val base = baseOffset ?: if (flags and 0x20000 != 0) moof.start.toLong() else previousEnd
                val runs = parts.filter { it.type == "trun" }
                // An index that gives no sizes at all: the mdat after this moof is taken whole.
                if (defaultSize == null && runs.any { u24(data, it.body + 1) and 0x200 == 0 }) {
                    val mdat = top.drop(index + 1).firstOrNull { it.type == "mdat" } ?: throw Unsupported("a piece holds no audio")
                    ranges += mdat.body until mdat.end
                    break
                }
                var next = base
                for (trun in runs) {
                    val runFlags = u24(data, trun.body + 1)
                    val count = u32(data, trun.body + 4)
                    var p = trun.body + 8
                    if (runFlags and 0x1 != 0) {
                        next = base + data.int32(p)
                        p += 4
                    }
                    if (runFlags and 0x4 != 0) p += 4
                    val perSample = listOf(0x100, 0x200, 0x400, 0x800).count { runFlags and it != 0 } * 4
                    if (p + count * perSample > trun.end) throw Unsupported("a piece's index can't be read")
                    for (i in 0 until count.toInt()) {
                        var q = p + i * perSample
                        if (runFlags and 0x100 != 0) q += 4
                        val size = if (runFlags and 0x200 != 0) u32(data, q) else defaultSize!!
                        if (next < 0 || next + size > data.size) throw Unsupported("a piece's index points past its end")
                        if (size > 0) ranges += next.toInt() until (next + size).toInt()
                        next += size
                    }
                }
                previousEnd = next
            }
        }
        if (ranges.isEmpty()) return ranges
        val first = ranges.first().first
        if (first + 1 >= data.size || data[first] != 0xFF.toByte() || (data[first + 1].toInt() and 0xFE) != 0xF8) {
            throw Unsupported("the pieces don't hold FLAC frames")
        }
        return ranges
    }

    /** The boxes a fragmented MP4 piece can open with. */
    private val PieceStarts = setOf("styp", "sidx", "moof", "prft", "emsg", "free", "skip")

    private fun u24(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 16) or ((data[at + 1].toInt() and 0xFF) shl 8) or (data[at + 2].toInt() and 0xFF)

    private fun u32(data: ByteArray, at: Int): Long =
        ((data[at].toLong() and 0xFF) shl 24) or ((data[at + 1].toLong() and 0xFF) shl 16) or
            ((data[at + 2].toLong() and 0xFF) shl 8) or (data[at + 3].toLong() and 0xFF)

    private fun ByteArray.int32(at: Int): Int = u32(this, at).toInt()

    private fun u64(data: ByteArray, at: Int): Long = (u32(data, at) shl 32) or u32(data, at + 4)
}
