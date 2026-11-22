/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.player

import com.convx.desktop.data.DownloadFormat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.FrameGrabber
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Saves a download in the format picked for the download folder, with its tags and cover. A
 * file already in that format is only given its tags; anything else is decoded and encoded
 * again with the bundled FFmpeg. Lossless formats only take a lossless source.
 */
object FormatConverter {
    /** What a file becomes: its extension, and whether its sound has to be encoded again. */
    data class Plan(val ext: String, val convert: Boolean)

    private val LosslessCodecs = setOf("flac", "alac", "wavpack", "tta", "ape")

    private fun isLossless(codec: String) = codec in LosslessCodecs || codec.startsWith("pcm_")

    /** What [input] is saved as in [format]. */
    fun plan(input: File, format: DownloadFormat): Plan {
        val codec = TagWriter.audioCodec(input).orEmpty()
        val asItIs = Plan(TagWriter.extensionFor(input), false)
        val ext = format.ext ?: return asItIs
        val same = when (format) {
            DownloadFormat.FLAC -> codec == "flac"
            DownloadFormat.ALAC -> codec == "alac"
            DownloadFormat.WAV -> codec.startsWith("pcm_")
            DownloadFormat.MP3 -> codec == "mp3"
            DownloadFormat.AAC -> codec == "aac"
            DownloadFormat.OPUS -> codec == "opus"
            DownloadFormat.AUTO -> true
        }
        return when {
            same -> Plan(ext, false)
            // Lossy sound in a lossless file would only be bigger, not better.
            format.lossless && !isLossless(codec) -> asItIs
            else -> Plan(ext, true)
        }
    }

    /** Writes [input] to [output] as [plan] says, with [tags] where the format can hold them. */
    fun write(input: File, output: File, plan: Plan, format: DownloadFormat, tags: TagWriter.Tags) {
        val source = if (plan.convert) File(output.parentFile, ".convx-converting-${System.nanoTime()}.${plan.ext}") else input
        try {
            if (plan.convert) encode(input, source, format)
            if (TagWriter.canTag(plan.ext)) {
                TagWriter.write(source, output, tags)
            } else if (plan.convert) {
                Files.move(source.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                input.copyTo(output, overwrite = true)
            }
        } finally {
            if (plan.convert) source.delete()
        }
    }

    private class Encoder(val codec: String, val muxer: String, val sampleFormat: Int, val bitrate: Int = 0)

    private fun encoder(format: DownloadFormat, deep: Boolean): Encoder = when (format) {
        DownloadFormat.MP3 -> Encoder("libmp3lame", "mp3", avutil.AV_SAMPLE_FMT_FLTP, 320_000)
        DownloadFormat.AAC -> Encoder("aac", "ipod", avutil.AV_SAMPLE_FMT_FLTP, 256_000)
        DownloadFormat.OPUS -> Encoder("libopus", "opus", avutil.AV_SAMPLE_FMT_FLT, 192_000)
        DownloadFormat.FLAC -> Encoder("flac", "flac", if (deep) avutil.AV_SAMPLE_FMT_S32 else avutil.AV_SAMPLE_FMT_S16)
        DownloadFormat.ALAC -> Encoder("alac", "ipod", if (deep) avutil.AV_SAMPLE_FMT_S32P else avutil.AV_SAMPLE_FMT_S16P)
        DownloadFormat.WAV -> Encoder(if (deep) "pcm_s24le" else "pcm_s16le", "wav", if (deep) avutil.AV_SAMPLE_FMT_S32 else avutil.AV_SAMPLE_FMT_S16)
        DownloadFormat.AUTO -> error("Automatic isn't encoded")
    }

    /** The rate [format] is written at: the source's, where the format allows it. */
    private fun rateFor(format: DownloadFormat, source: Int): Int = when (format) {
        DownloadFormat.OPUS -> 48_000
        DownloadFormat.MP3, DownloadFormat.AAC -> when {
            source in setOf(32_000, 44_100, 48_000) -> source
            source > 0 && source % 11_025 == 0 -> 44_100
            else -> 48_000
        }
        else -> source.takeIf { it > 0 } ?: 44_100
    }

    /**
     * Decodes [input] and encodes it into [output] as [format]. Samples go through as 32-bit
     * floats, which carry 16- and 24-bit sound exactly, so lossless to lossless loses nothing.
     */
    private fun encode(input: File, output: File, format: DownloadFormat) {
        val grabber = FFmpegFrameGrabber(input.absolutePath).apply { sampleMode = FrameGrabber.SampleMode.FLOAT }
        grabber.start()
        try {
            val stream = grabber.formatContext.let { ctx ->
                (0 until ctx.nb_streams()).map { ctx.streams(it) }.firstOrNull { it.codecpar().codec_type() == avutil.AVMEDIA_TYPE_AUDIO }
            }
            val bits = stream?.codecpar()?.let { maxOf(it.bits_per_raw_sample(), it.bits_per_coded_sample()) } ?: 16
            val spec = encoder(format, deep = bits > 16)
            val channels = if (format.lossless) grabber.audioChannels.coerceAtLeast(1) else grabber.audioChannels.coerceIn(1, 2)
            val recorder = FFmpegFrameRecorder(output.absolutePath, channels).apply {
                this.format = spec.muxer
                audioCodecName = spec.codec
                sampleRate = rateFor(format, grabber.sampleRate)
                audioChannels = channels
                sampleFormat = spec.sampleFormat
                if (spec.bitrate > 0) audioBitrate = spec.bitrate
            }
            recorder.start()
            try {
                while (true) {
                    val frame = grabber.grabSamples() ?: break
                    val samples = frame.samples ?: continue
                    recorder.recordSamples(frame.sampleRate, frame.audioChannels, *samples)
                }
                recorder.stop()
            } finally {
                recorder.release()
            }
        } finally {
            grabber.stop()
            grabber.release()
        }
        check(output.isFile && output.length() > 0) { "FFmpeg wrote no ${format.label}" }
    }
}
