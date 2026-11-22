/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.player

import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * Writes a song's tags and cover into a copy of its audio file, the way music players read them,
 * without decoding the sound again: FFmpeg copies the audio as it is into a container that holds
 * tags. YouTube's WebM becomes Ogg Opus (WebM can't carry a cover), its MP4 audio an .m4a, and a
 * module's FLAC stays FLAC.
 */
object TagWriter {
    class Tags(
        val title: String,
        val artist: String,
        val album: String? = null,
        val albumArtist: String? = null,
        val year: Int? = null,
        val lyrics: String? = null,
        /** The cover as a JPEG or PNG file's bytes; other pictures are turned into JPEG. */
        val cover: ByteArray? = null,
    )

    /** The extension the tagged copy of [input] gets. FLAC sent in MP4 by a module becomes a plain .flac. */
    fun extensionFor(input: File): String = when (val ext = input.extension.lowercase()) {
        "webm" -> if (audioCodec(input) == "vorbis") "ogg" else "opus"
        "mp4" -> if (audioCodec(input) == "flac") "flac" else "m4a"
        else -> ext
    }

    /** Whether a copy in this container can carry tags at all; WAV keeps only a bare INFO list, so it's copied as it is. */
    fun canTag(ext: String) = ext in setOf("m4a", "opus", "ogg", "mp3", "flac")

    /**
     * Copies [input] to [output] with [tags] in it. The copy is written beside [output] under a
     * plain name first and moved into place when it's whole, so a half-written file never stays
     * and a name with any letters in it is handled by Java, not FFmpeg.
     */
    fun write(input: File, output: File, tags: Tags) {
        val ext = output.extension.lowercase()
        require(canTag(ext)) { "Can't tag .$ext files" }
        val temp = File(output.parentFile, ".convx-tagging-${System.nanoTime()}.$ext")
        try {
            remux(input, temp, tags, ext)
            if (ext == "flac") fillFlacLength(temp)
            Files.move(temp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    private fun remux(input: File, output: File, tags: Tags, ext: String) {
        val inCtx = AVFormatContext(null)
        check(avformat.avformat_open_input(inCtx, utf8(input.absolutePath), null, null) >= 0) { "FFmpeg couldn't open ${input.name}" }
        var outCtx: AVFormatContext? = null
        try {
            check(avformat.avformat_find_stream_info(inCtx, null as PointerPointer<*>?) >= 0) { "FFmpeg couldn't read ${input.name}" }
            outCtx = AVFormatContext(null)
            check(avformat.avformat_alloc_output_context2(outCtx, null, null as BytePointer?, utf8(output.absolutePath)) >= 0) { "FFmpeg has no .$ext writer" }

            // The sound, copied as it is.
            val map = IntArray(inCtx.nb_streams()) { -1 }
            var audio: org.bytedeco.ffmpeg.avformat.AVStream? = null
            for (i in 0 until inCtx.nb_streams()) {
                val inStream = inCtx.streams(i)
                if (inStream.codecpar().codec_type() != avutil.AVMEDIA_TYPE_AUDIO || audio != null) continue
                val outStream = avformat.avformat_new_stream(outCtx, null)
                check(avcodec.avcodec_parameters_copy(outStream.codecpar(), inStream.codecpar()) >= 0)
                outStream.codecpar().codec_tag(0)
                outStream.time_base(inStream.time_base())
                map[i] = outStream.index()
                audio = outStream
            }
            checkNotNull(audio) { "${input.name} has no sound in it" }

            val text = linkedMapOf(
                "title" to tags.title,
                "artist" to tags.artist,
                "album" to tags.album,
                "album_artist" to tags.albumArtist,
                "date" to tags.year?.toString(),
                "lyrics" to tags.lyrics,
            ).filterValues { !it.isNullOrBlank() }.mapValues { it.value!! }
            val picture = tags.cover?.let(::picture)
            val format = AVDictionary(null)
            text.forEach { (key, value) -> avutil.av_dict_set(format, utf8(key), utf8(value), 0) }
            outCtx.metadata(format)
            if (ext == "opus" || ext == "ogg") {
                // Ogg keeps its tags on the stream, and its cover as a FLAC picture block in them.
                val stream = AVDictionary(null)
                text.forEach { (key, value) -> avutil.av_dict_set(stream, utf8(key), utf8(value), 0) }
                picture?.let { avutil.av_dict_set(stream, utf8("METADATA_BLOCK_PICTURE"), utf8(Base64.getEncoder().encodeToString(flacPicture(it))), 0) }
                audio.metadata(stream)
            }

            // MP4, MP3 and FLAC hold the cover as an attached picture: a one-frame video stream.
            val coverStream = if (picture != null && ext in setOf("m4a", "mp3", "flac")) {
                avformat.avformat_new_stream(outCtx, null).apply {
                    codecpar().codec_type(avutil.AVMEDIA_TYPE_VIDEO)
                    codecpar().codec_id(if (picture.png) avcodec.AV_CODEC_ID_PNG else avcodec.AV_CODEC_ID_MJPEG)
                    codecpar().width(picture.width)
                    codecpar().height(picture.height)
                    disposition(avformat.AV_DISPOSITION_ATTACHED_PIC)
                    time_base(avutil.av_make_q(1, 90_000))
                }
            } else {
                null
            }

            if ((outCtx.oformat().flags() and avformat.AVFMT_NOFILE) == 0) {
                val pb = AVIOContext(null)
                val opened = avformat.avio_open(pb, utf8(output.absolutePath), avformat.AVIO_FLAG_WRITE)
                check(opened >= 0) { "Couldn't write ${output.name} (${error(opened)})" }
                outCtx.pb(pb)
            }
            val options = AVDictionary(null)
            // The index up front, so players can start an .m4a without reading to its end.
            if (ext == "m4a") avutil.av_dict_set(options, "movflags", "+faststart", 0)
            check(avformat.avformat_write_header(outCtx, options) >= 0) { "FFmpeg couldn't start ${output.name}" }
            avutil.av_dict_free(options)

            val packet = avcodec.av_packet_alloc()
            try {
                if (coverStream != null && picture != null) {
                    check(avcodec.av_new_packet(packet, picture.bytes.size) >= 0)
                    packet.data().put(picture.bytes, 0, picture.bytes.size)
                    packet.stream_index(coverStream.index())
                    packet.flags(packet.flags() or avcodec.AV_PKT_FLAG_KEY)
                    packet.pts(0)
                    packet.dts(0)
                    check(avformat.av_interleaved_write_frame(outCtx, packet) >= 0) { "FFmpeg couldn't add the cover" }
                }
                while (avformat.av_read_frame(inCtx, packet) >= 0) {
                    val target = map.getOrElse(packet.stream_index()) { -1 }
                    if (target < 0) {
                        avcodec.av_packet_unref(packet)
                        continue
                    }
                    val inStream = inCtx.streams(packet.stream_index())
                    val outStream = outCtx.streams(target)
                    avcodec.av_packet_rescale_ts(packet, inStream.time_base(), outStream.time_base())
                    packet.stream_index(target)
                    packet.pos(-1)
                    check(avformat.av_interleaved_write_frame(outCtx, packet) >= 0) { "FFmpeg couldn't copy the sound" }
                }
                check(avformat.av_write_trailer(outCtx) >= 0) { "FFmpeg couldn't finish ${output.name}" }
            } finally {
                avcodec.av_packet_free(packet)
            }
        } finally {
            outCtx?.takeUnless { it.isNull }?.let { ctx ->
                val pb = ctx.pb()
                if (pb != null && !pb.isNull) avformat.avio_closep(pb)
                avformat.avformat_free_context(ctx)
            }
            avformat.avformat_close_input(inCtx)
        }
    }

    /**
     * Writes the song's length into a .flac file's header when it's missing, as it is in a file
     * joined from a module's pieces; without it File Explorer and other players show no length
     * and seek poorly. The length is counted from the frames, which aren't decoded for it.
     */
    fun fillFlacLength(file: File) {
        val info = HlsAudio.flacInfo(file) ?: return
        if (info.totalSamples > 0) return
        val ctx = AVFormatContext(null)
        if (avformat.avformat_open_input(ctx, utf8(file.absolutePath), null, null) < 0) return
        var total = 0L
        try {
            if (avformat.avformat_find_stream_info(ctx, null as PointerPointer<*>?) < 0) return
            val index = (0 until ctx.nb_streams()).firstOrNull { ctx.streams(it).codecpar().codec_type() == avutil.AVMEDIA_TYPE_AUDIO } ?: return
            val timeBase = ctx.streams(index).time_base()
            val samples = avutil.av_make_q(1, info.sampleRate)
            val packet = avcodec.av_packet_alloc()
            try {
                while (avformat.av_read_frame(ctx, packet) >= 0) {
                    if (packet.stream_index() == index && packet.pts() != avutil.AV_NOPTS_VALUE) {
                        total = maxOf(total, avutil.av_rescale_q(packet.pts() + packet.duration(), timeBase, samples))
                    }
                    avcodec.av_packet_unref(packet)
                }
            } finally {
                avcodec.av_packet_free(packet)
            }
        } finally {
            avformat.avformat_close_input(ctx)
        }
        if (total <= 0 || total >= 1L shl 36) return
        // STREAMINFO's sample count: the low 4 bits of byte 21 of the file and the 4 bytes after.
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(21)
            val high = raf.read() and 0xF0
            raf.seek(21)
            raf.write(high or (total shr 32).toInt())
            raf.writeInt(total.toInt())
        }
    }

    private fun error(code: Int): String {
        val text = ByteArray(256)
        avutil.av_strerror(code, text, text.size.toLong())
        return text.decodeToString().substringBefore('\u0000')
    }

    /** Text for FFmpeg as UTF-8, whatever Windows' own code page is, so any song or folder name works. */
    private fun utf8(text: String) = BytePointer(text, "UTF-8")

    private class Picture(val bytes: ByteArray, val png: Boolean, val width: Int, val height: Int)

    /** JPEG and PNG go in as they are; anything else (WebP thumbnails) is turned into a JPEG. */
    private fun picture(bytes: ByteArray): Picture? = runCatching {
        val image = Image.makeFromEncoded(bytes)
        val jpeg = bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val png = bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte()
        val data = if (jpeg || png) bytes else checkNotNull(image.encodeToData(EncodedImageFormat.JPEG, 92)).bytes
        Picture(data, png, image.width, image.height).also { image.close() }
    }.getOrNull()

    /** A FLAC PICTURE block: how Ogg Opus and Vorbis files keep a front cover. */
    private fun flacPicture(picture: Picture): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            val mime = (if (picture.png) "image/png" else "image/jpeg").toByteArray()
            out.writeInt(3) // front cover
            out.writeInt(mime.size)
            out.write(mime)
            out.writeInt(0) // no description
            out.writeInt(picture.width)
            out.writeInt(picture.height)
            out.writeInt(24)
            out.writeInt(0)
            out.writeInt(picture.bytes.size)
            out.write(picture.bytes)
        }
        return bytes.toByteArray()
    }

    /** The first audio stream's codec, e.g. "opus" or "vorbis". */
    fun audioCodec(file: File): String? {
        val ctx = AVFormatContext(null)
        if (avformat.avformat_open_input(ctx, utf8(file.absolutePath), null, null) < 0) return null
        return try {
            (0 until ctx.nb_streams()).map { ctx.streams(it).codecpar() }
                .firstOrNull { it.codec_type() == avutil.AVMEDIA_TYPE_AUDIO }
                ?.let { avcodec.avcodec_get_name(it.codec_id()).string }
        } finally {
            avformat.avformat_close_input(ctx)
        }
    }
}
