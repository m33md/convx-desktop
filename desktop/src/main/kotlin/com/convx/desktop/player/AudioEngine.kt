/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.player

import com.convx.desktop.audio.AudioEffects
import com.convx.desktop.audio.EffectsChain
import com.convx.desktop.audio.Sonic
import com.convx.desktop.automix.DjEffects
import com.convx.desktop.automix.DjFilter
import com.convx.desktop.audio.softClip
import com.convx.desktop.core.Log
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.nio.ShortBuffer
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Decodes with FFmpeg and plays through Java Sound. One [Session] (a thread) per loaded
 * track; play/pause/seek act on the live session. Everything YouTube serves (Opus in WebM,
 * AAC in MP4) decodes the same way, so there is no codec matrix to manage.
 *
 * Samples pass through [Sonic] for the [tempo] and [pitch], then the [effects] (equalizer,
 * spatial audio, skip silence), as floats. For a crossfade the outgoing session is [handOff]ed:
 * it keeps playing on its own line, fading out, while the next one fades in on another, and the
 * system mixes the two.
 */
class AudioEngine(private val listener: Listener) {
    /**
     * One song's controls while AutoMix plays two at once: its level, its filters and its tempo,
     * all ridden from the mix's clock and glided to in here so no step is heard.
     */
    interface Deck {
        val token: Long
        val positionMs: Long
        val durationMs: Long
        val isReady: Boolean

        /** 0..1 on top of the volume and normalisation. */
        var mixGain: Float

        /** Tempo matching, on top of Tempo and Pitch. */
        var rate: Float
        val filter: DjFilter

        /** The creative transitions' tape stop, delay and reverb; made the first time they're asked for. */
        val effects: DjEffects

        /** Whether [effects] has been made, so stopping them needn't make them. */
        val effectsMade: Boolean
    }

    interface Listener {
        fun onReady(token: Long, durationMs: Long)
        fun onEnded(token: Long)
        fun onError(token: Long, error: Throwable, positionMs: Long)
    }

    companion object {
        private const val Tag = "AudioEngine"
        private const val SampleRate = 48_000
        private const val Channels = 2
        private const val BufferMs = 250
        private const val TailChunkFrames = 4096
        private val Format = AudioFormat(SampleRate.toFloat(), 16, Channels, true, false)

        /** Loads the native libraries early so the first song doesn't pay for it. */
        fun warmUp() {
            thread(name = "convx-ffmpeg-warmup", isDaemon = true) {
                runCatching {
                    FFmpegFrameGrabber.tryLoad()
                    avutil.av_log_set_level(avutil.AV_LOG_ERROR)
                }.onFailure { Log.e(Tag, "FFmpeg failed to load", it) }
            }
        }

        /** The sound outputs Convx can play through, by the names Windows gives them. */
        fun outputDevices(): List<String> = runCatching {
            val wanted = DataLine.Info(SourceDataLine::class.java, Format)
            AudioSystem.getMixerInfo()
                .filter { info -> runCatching { AudioSystem.getMixer(info).isLineSupported(wanted) }.getOrDefault(false) }
                .map { it.name }
                // Java's own name for "whatever Windows is using", which is what no choice means.
                .filter { it != "Primary Sound Driver" && !it.startsWith("Default Audio Device") }
                .distinct()
        }.getOrElse {
            Log.e(Tag, "Couldn't list sound outputs", it)
            emptyList()
        }
    }

    private val tokens = AtomicLong()
    @Volatile private var session: Session? = null

    /** Sessions handed off to fade out under the next song. */
    private val fading = CopyOnWriteArraySet<Session>()

    /** The next song, opened and cued but silent, waiting for AutoMix to bring it in. */
    @Volatile private var standby: Session? = null

    /** Linear gain, 0..1, applied to every sample. */
    @Volatile var gain: Float = 1f

    /** Picked up by every session between buffers, so a change is heard within a few ms. */
    @Volatile var effects: AudioEffects = AudioEffects()

    /** The output to open new sessions on, by name; null for the system's default. */
    @Volatile var outputDevice: String? = null

    /** The phone's Tempo and Pitch: how fast songs play with the pitch kept, 0.25..2. */
    @Volatile var tempo: Float = 1f

    /** The pitch as a ratio (2 is an octave up), with the speed kept. */
    @Volatile var pitch: Float = 1f

    /** The song playing, as AutoMix rides it. */
    val current: Deck? get() = session

    val positionMs: Long get() = session?.positionMs() ?: 0L
    val durationMs: Long get() = session?.durationMs ?: 0L
    val isPlaying: Boolean get() = session?.let { it.playing && !it.finished } ?: false

    /**
     * Starts [source] at [startMs]. [trackGain] is the song's loudness normalisation; [fadeInMs]
     * eases it in, for a crossfade.
     */
    fun load(
        source: CachedAudio,
        startMs: Long,
        playWhenReady: Boolean,
        durationHintMs: Long?,
        trackGain: Float = 1f,
        fadeInMs: Int = 0,
    ): Long {
        stop(keepFading = fadeInMs > 0)
        val token = tokens.incrementAndGet()
        session = Session(token, source, startMs.coerceAtLeast(0), playWhenReady, durationHintMs ?: 0L, trackGain, fadeInMs)
            .also { it.start() }
        return token
    }

    /**
     * Opens [source] at [startMs] silent and paused beside the playing song, so AutoMix can bring
     * it in on the beat with [promote]. Replaces any song already waiting.
     */
    fun prepare(source: CachedAudio, startMs: Long, durationHintMs: Long?, trackGain: Float = 1f): Deck {
        standby?.close()
        val token = tokens.incrementAndGet()
        return Session(token, source, startMs.coerceAtLeast(0), false, durationHintMs ?: 0L, trackGain, 0, initialMixGain = 0f)
            .also {
                standby = it
                it.start()
            }
    }

    /**
     * Starts the waiting [deck] and makes it the playing song; the one that was playing carries on
     * under it, as it was, until [retire]d. Returns that outgoing song, or null if [deck] isn't the
     * one waiting any more.
     */
    fun promote(deck: Deck): Deck? {
        val next = standby?.takeIf { it === deck } ?: return null
        standby = null
        val outgoing = session
        if (outgoing != null) fading += outgoing
        session = next
        next.setPlayback(outgoing?.playing ?: true)
        return outgoing
    }

    /** Stops a song AutoMix is done with: the one mixed out, or one prepared and not used. */
    fun retire(deck: Deck) {
        val target = deck as? Session ?: return
        if (standby === target) standby = null
        if (session === target) return
        fading -= target
        target.close()
    }

    /** Lets the current song play on, fading out over [fadeMs], while the next one loads. */
    fun handOff(fadeMs: Int) {
        val current = session ?: return
        session = null
        fading += current
        current.fadeOut(fadeMs)
    }

    fun play() {
        session?.setPlayback(true)
        fading.forEach { it.setPlayback(true) }
    }

    fun pause() {
        session?.setPlayback(false)
        fading.forEach { it.setPlayback(false) }
    }

    fun seek(positionMs: Long) {
        session?.seek(positionMs.coerceAtLeast(0))
    }

    fun stop() = stop(keepFading = false)

    private fun stop(keepFading: Boolean) {
        session?.close()
        session = null
        standby?.close()
        standby = null
        if (!keepFading) {
            fading.forEach { it.close() }
            fading.clear()
        }
    }

    private inner class Session(
        override val token: Long,
        private val source: CachedAudio,
        startMs: Long,
        @Volatile var playing: Boolean,
        durationHintMs: Long,
        private val trackGain: Float,
        fadeInMs: Int,
        initialMixGain: Float = 1f,
    ) : Deck {
        @Volatile override var durationMs: Long = durationHintMs
        @Volatile override var isReady = false
        @Volatile override var mixGain: Float = initialMixGain
        @Volatile override var rate: Float = 1f
        override val filter = DjFilter(SampleRate)
        private val effectsLazy = lazy { DjEffects(SampleRate) }
        override val effects: DjEffects get() = effectsLazy.value
        override val effectsMade: Boolean get() = effectsLazy.isInitialized()
        override val positionMs: Long get() = positionMs()
        private var appliedMix = initialMixGain
        @Volatile var finished = false
        @Volatile private var closed = false
        @Volatile private var pendingSeekMs: Long = -1
        @Volatile private var baseMs: Long = startMs
        @Volatile private var framesAtBase: Long = 0
        @Volatile private var line: SourceDataLine? = null
        private val signal = Object()

        private val sonic = Sonic(SampleRate, Channels)
        private val tempo: Float get() = this@AudioEngine.tempo.coerceIn(0.25f, 2f)
        private val pitch: Float get() = this@AudioEngine.pitch.coerceIn(0.5f, 2f)

        private val fadeInFrames = SampleRate.toLong() * fadeInMs / 1000
        private var fadeInDone = 0L
        @Volatile private var fadeOutFrames = 0L
        private var fadeOutDone = 0L

        // Where the song is at each point of what's been written, so the playhead (and the lyrics)
        // stay with the music however it's stretched or how much silence was skipped: each mark
        // is the frames written so far and the song time they reach.
        private val marks = ArrayDeque<LongArray>().apply { addLast(longArrayOf(0L, startMs)) }
        private var writtenFrames = 0L
        private var decodedFrames = 0L

        fun start() {
            thread(name = "convx-audio-$token", isDaemon = true) { run() }
        }

        fun positionMs(): Long {
            val l = line ?: return baseMs
            if (pendingSeekMs >= 0) return pendingSeekMs
            val played = (l.longFramePosition - framesAtBase).coerceAtLeast(0)
            val pos = synchronized(marks) {
                while (marks.size >= 2 && marks[1][0] <= played) marks.removeFirst()
                val from = marks.firstOrNull() ?: return@synchronized baseMs
                val to = marks.getOrNull(1) ?: return@synchronized from[1]
                val span = (to[0] - from[0]).coerceAtLeast(1)
                from[1] + (played - from[0]).coerceIn(0, span) * (to[1] - from[1]) / span
            }
            return if (durationMs > 0) pos.coerceAtMost(durationMs) else pos
        }

        fun setPlayback(value: Boolean) {
            playing = value
            line?.let { if (value) it.start() else it.stop() }
            synchronized(signal) { signal.notifyAll() }
        }

        fun seek(ms: Long) {
            pendingSeekMs = ms
            line?.flush()
            synchronized(signal) { signal.notifyAll() }
        }

        fun fadeOut(ms: Int) {
            fadeOutFrames = (SampleRate.toLong() * ms / 1000).coerceAtLeast(1)
        }

        fun close() {
            closed = true
            // The line may already be closed by a song that ended on its own.
            line?.let {
                runCatching { it.stop() }
                runCatching { it.flush() }
            }
            synchronized(signal) { signal.notifyAll() }
        }

        private fun openLine(): SourceDataLine {
            val wanted = outputDevice
            if (wanted != null) {
                val info = AudioSystem.getMixerInfo().firstOrNull { it.name == wanted }
                if (info != null) {
                    runCatching { return AudioSystem.getSourceDataLine(Format, info) }
                        .onFailure { Log.w(Tag, "Couldn't open $wanted, using the default output: ${it.message}") }
                } else {
                    Log.w(Tag, "$wanted isn't connected, using the default output")
                }
            }
            return AudioSystem.getSourceDataLine(Format)
        }

        private fun run() {
            var decoder: Decoder? = null
            try {
                val reader = Decoder(source, SampleRate, Channels).also { decoder = it }
                reader.start()
                if (closed) return
                reader.grabber.lengthInTime.takeIf { it > 0 }?.let { durationMs = it / 1000 }
                if (baseMs > 0) reader.seek(baseMs * 1000)

                val l = openLine()
                l.open(Format, SampleRate * Channels * 2 * BufferMs / 1000)
                framesAtBase = l.longFramePosition
                line = l
                if (closed) return
                if (playing) l.start()
                isReady = true
                listener.onReady(token, durationMs)

                val chain = EffectsChain(SampleRate)
                var floats = FloatArray(0)
                var stretched = FloatArray(0)
                var bytes = ByteArray(0)
                while (!closed) {
                    val seekTo = pendingSeekMs
                    if (seekTo >= 0) {
                        l.flush()
                        reader.seek(seekTo * 1000)
                        baseMs = seekTo
                        framesAtBase = l.longFramePosition
                        synchronized(marks) {
                            marks.clear()
                            marks.addLast(longArrayOf(0L, seekTo))
                        }
                        writtenFrames = 0
                        decodedFrames = 0
                        sonic.flush()
                        chain.reset()
                        filter.reset()
                        if (effectsLazy.isInitialized()) effectsLazy.value.reset()
                        pendingSeekMs = -1
                        if (playing) l.start()
                    }
                    if (!playing) {
                        synchronized(signal) { signal.wait(100) }
                        continue
                    }
                    val frame = reader.grabSamples()
                    if (frame == null) {
                        // End of stream: let the buffer play out unless we were interrupted.
                        if (pendingSeekMs >= 0) continue
                        if (effectsLazy.isInitialized() && effectsLazy.value.isRinging) ringOut(l)
                        l.drain()
                        if (pendingSeekMs >= 0) continue
                        finished = true
                        break
                    }
                    val samples = frame.samples ?: continue
                    var frames = interleavedCount(samples) / Channels
                    if (floats.size < frames * Channels) floats = FloatArray(frames * Channels)
                    decode(samples, floats)
                    decodedFrames += frames

                    // Tempo and pitch: untouched (and without Sonic's delay) at the natural speed.
                    sonic.speed = tempo * rate.coerceIn(0.8f, 1.25f)
                    sonic.pitch = pitch
                    var input = floats
                    if (!sonic.isIdentity || sonic.pendingInputFrames > 0 || sonic.availableFrames > 0) {
                        sonic.queueInput(floats, frames)
                        frames = sonic.availableFrames
                        if (stretched.size < frames * Channels) stretched = FloatArray(frames * Channels)
                        sonic.readOutput(stretched, frames)
                        input = stretched
                    }
                    // The song time this buffer reaches, less what Sonic is still holding on to.
                    val reachedMs = baseMs + (decodedFrames - sonic.pendingInputFrames).coerceAtLeast(0) * 1000 / SampleRate

                    val effects = this@AudioEngine.effects
                    chain.configure(effects)
                    val count = if (frames == 0) 0 else chain.process(input, frames)
                    if (count == 0) {
                        // All of it was silence skipped (or held by Sonic): the song moves on regardless.
                        synchronized(marks) { marks.addLast(longArrayOf(writtenFrames, reachedMs)) }
                        continue
                    }
                    filter.process(chain.output, count)
                    if (effectsLazy.isInitialized()) effectsLazy.value.process(chain.output, count)
                    if (bytes.size < count * Channels * 2) bytes = ByteArray(count * Channels * 2)
                    val shaped = effects.shapesSound || trackGain > 1f
                    if (!encode(chain.output, count, bytes, gain * trackGain, shaped)) {
                        // The fade out is over: this session has handed over to the next song.
                        closed = true
                        break
                    }
                    writtenFrames += count
                    synchronized(marks) {
                        marks.addLast(longArrayOf(writtenFrames, reachedMs))
                        // Only the line's quarter second of marks is ever looked up.
                        if (marks.size > 512) marks.removeFirst()
                    }
                    var offset = 0
                    val length = count * Channels * 2
                    while (offset < length && !closed && pendingSeekMs < 0) {
                        val written = l.write(bytes, offset, length - offset)
                        if (written <= 0 && !playing) synchronized(signal) { signal.wait(50) }
                        offset += written
                    }
                }
            } catch (e: Throwable) {
                if (!closed) {
                    Log.e(Tag, "Playback failed", e)
                    listener.onError(token, e, positionMs())
                }
                return
            } finally {
                runCatching { line?.close() }
                decoder?.close()
                fading -= this
            }
            if (finished && !closed) listener.onEnded(token)
        }

        /**
         * The phone's processors' tail: past the song's last sample, silence is fed through its
         * echoes and reverb for a few seconds so they die away instead of stopping dead.
         */
        private fun ringOut(l: SourceDataLine) {
            val effects = effectsLazy.value
            val silence = FloatArray(TailChunkFrames * Channels)
            val bytes = ByteArray(TailChunkFrames * Channels * 2)
            var left = SampleRate.toLong() * DjEffects.TailSeconds
            while (left > 0 && !closed && pendingSeekMs < 0 && effects.isRinging) {
                val frames = minOf(left, TailChunkFrames.toLong()).toInt()
                silence.fill(0f)
                filter.process(silence, frames)
                effects.process(silence, frames)
                if (!encode(silence, frames, bytes, gain * trackGain, shaped = true)) return
                writtenFrames += frames
                var offset = 0
                val length = frames * Channels * 2
                while (offset < length && !closed && pendingSeekMs < 0) {
                    val written = l.write(bytes, offset, length - offset)
                    if (written <= 0 && !playing) synchronized(signal) { signal.wait(50) }
                    offset += written
                }
                left -= frames
            }
        }

        private fun interleavedCount(samples: Array<java.nio.Buffer>): Int =
            if (samples.size == 1) samples[0].limit() - samples[0].position()
            else (samples[0].limit() - samples[0].position()) * samples.size

        /** Packed or planar 16-bit frames as interleaved floats in -1..1. */
        private fun decode(samples: Array<java.nio.Buffer>, out: FloatArray) {
            if (samples.size == 1) {
                val buffer = samples[0] as ShortBuffer
                val start = buffer.position()
                val n = buffer.limit() - start
                for (i in 0 until n) out[i] = buffer.get(start + i) / 32768f
            } else {
                val planes = samples.map { it as ShortBuffer }
                val perPlane = planes[0].limit() - planes[0].position()
                var o = 0
                for (i in 0 until perPlane) {
                    for (plane in planes) out[o++] = plane.get(plane.position() + i) / 32768f
                }
            }
        }

        /**
         * Writes [frames] frames as 16-bit little-endian PCM with [g] and any fade, softening
         * peaks when the effects could have pushed them past full scale. False once a fade out
         * has run its course.
         */
        private fun encode(input: FloatArray, frames: Int, out: ByteArray, g: Float, shaped: Boolean): Boolean {
            val fadeOut = fadeOutFrames
            // AutoMix's level glides across the buffer from where the last one left it.
            val mixFrom = appliedMix
            val mixTo = mixGain.coerceIn(0f, 1f)
            appliedMix = mixTo
            var o = 0
            var i = 0
            for (frame in 0 until frames) {
                var f = g * (mixFrom + (mixTo - mixFrom) * frame / frames)
                if (fadeInDone < fadeInFrames) {
                    // Equal power: the two songs together stay as loud as one.
                    f *= sin(PI / 2 * fadeInDone / fadeInFrames).toFloat()
                    fadeInDone++
                }
                if (fadeOut > 0) {
                    if (fadeOutDone >= fadeOut) return false
                    f *= cos(PI / 2 * fadeOutDone / fadeOut).toFloat()
                    fadeOutDone++
                }
                repeat(Channels) {
                    var x = input[i++] * f
                    x = if (shaped) softClip(x) else x.coerceIn(-1f, 1f)
                    val v = (x * 32767f).toInt()
                    out[o++] = v.toByte()
                    out[o++] = (v shr 8).toByte()
                }
            }
            return true
        }
    }
}
