/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.player

import org.bytedeco.ffmpeg.avformat.AVIndexEntry
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import java.io.IOException

/**
 * FFmpeg decoding one song to 16-bit PCM, from a finished file or from a download still in
 * flight. When the download can't be read, FFmpeg only sees a failed read: it then reports an
 * unrelated error (it retries the stream's name as a file path) or ends the song early. Here
 * the download's own error is thrown instead, so the player can retry and say what happened.
 */
class Decoder(source: CachedAudio, outputRate: Int, outputChannels: Int) : AutoCloseable {
    private val download = (source as? CachedAudio.Growing)?.download
    private val input = download?.let(::GrowingFileInputStream)

    val grabber: FFmpegFrameGrabber = when (source) {
        is CachedAudio.Complete -> FFmpegFrameGrabber(source.file.absolutePath)
        // The container is known, so FFmpeg doesn't have to guess it from the first bytes.
        is CachedAudio.Growing -> FFmpegFrameGrabber(checkNotNull(input)).apply { format = source.download.container }
    }.apply {
        sampleMode = FrameGrabber.SampleMode.SHORT
        sampleFormat = avutil.AV_SAMPLE_FMT_S16
        sampleRate = outputRate
        audioChannels = outputChannels
    }

    /** Why reading the download failed, if it did. */
    private val failure: IOException? get() = input?.failure ?: download?.error

    fun start() {
        try {
            grabber.start()
        } catch (e: FrameGrabber.Exception) {
            throw failure ?: e
        }
    }

    /**
     * Moves to [timestampUs]. An HLS song still arriving waits for its pieces up to just past that
     * point first; that wait ends early, returning false without moving, once [abandon] says so.
     */
    fun seek(timestampUs: Long, abandon: () -> Boolean = { false }): Boolean {
        val download = download
        val hls = download?.stream?.hls
        if (hls != null) {
            // Two seconds past the point, as FFmpeg reads a few frames ahead to find one, and the
            // start of the piece after it, so the point lies between two pieces it's told of.
            val sample = timestampUs * hls.info.sampleRate / 1_000_000
            if (!download.awaitSeek(timestampUs / 1_000_000.0 + 2.0, sample, abandon = abandon)) return false
            markPieces(download, hls.info.sampleRate)
        }
        try {
            grabber.timestamp = timestampUs
        } catch (e: FrameGrabber.Exception) {
            throw failure ?: e
        }
        return true
    }

    /**
     * Tells FFmpeg where an HLS song's pieces start, so a jump is looked for between two of them.
     * Without that FFmpeg first looks for the end of the file, which hasn't arrived, and waits
     * for more pieces to land: seconds on a slow line. Only real frame starts are given: a mark
     * at the end of the song would make FFmpeg's search fail.
     */
    private fun markPieces(download: AudioCache.Download, sampleRate: Int) {
        val context = grabber.formatContext ?: return
        val stream = (0 until context.nb_streams()).map { context.streams(it) }
            .firstOrNull { it.codecpar().codec_type() == avutil.AVMEDIA_TYPE_AUDIO } ?: return
        val base = stream.time_base()
        if (base.num() <= 0 || base.den() <= 0 || sampleRate <= 0) return
        for ((position, sample) in download.seekPoints()) {
            val timestamp = if (base.num() == 1 && base.den() == sampleRate) sample
            else sample * base.den() / (sampleRate.toLong() * base.num())
            avformat.av_add_index_entry(stream, position, timestamp, 0, 0, AVIndexEntry.AVINDEX_KEYFRAME)
        }
    }

    /** The next samples, or null at the end of the song. Throws when the download failed under it. */
    fun grabSamples(): Frame? {
        val frame = try {
            grabber.grabSamples()
        } catch (e: FrameGrabber.Exception) {
            throw failure ?: e
        }
        if (frame == null) input?.failure?.let { throw it }
        return frame
    }

    override fun close() {
        runCatching { grabber.stop() }
        runCatching { grabber.release() }
        runCatching { input?.close() }
    }
}

/** What to tell the listener when [error] stopped a song: plain words, never FFmpeg's. The details go to the log. */
fun describePlaybackError(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.toList()
    val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
    return when {
        AudioCache.isDiskFull(error) ->
            "There isn't enough free space on this PC to play songs. Free up some space and try again."
        isOutputProblem(error) ->
            "Convx couldn't open your speakers or headphones. Check the sound output in Windows and try again."
        chain.any { it is OutOfMemoryError } ->
            "Convx ran out of memory. Close it and open it again to keep listening."
        chain.any { it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.NoRouteToHostException } ->
            "You're offline, or YouTube can't be reached right now."
        chain.any { it is AudioCache.HttpStatusException } ->
            "YouTube won't send this song right now. Try again in a minute, or pick another one."
        chain.any { it is java.net.SocketTimeoutException } || "timed out" in text ->
            "The song is taking too long to arrive. Check your connection and try again."
        chain.any { it is java.io.FileNotFoundException || it is java.nio.file.AccessDeniedException } ->
            "Convx couldn't open this song's file."
        else -> "This song couldn't be played. Try it again, or pick another one."
    }
}

/** Whether [error] came from the sound output rather than from the song. */
fun isOutputProblem(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any {
        it is javax.sound.sampled.LineUnavailableException ||
            // Java's way of saying there's no sound output at all.
            (it is IllegalArgumentException && "no line matching" in it.message.orEmpty().lowercase())
    }
