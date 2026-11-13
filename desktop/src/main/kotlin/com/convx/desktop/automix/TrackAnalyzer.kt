/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.automix

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.Log
import com.convx.desktop.player.AudioCache
import com.convx.desktop.player.Downloads
import com.convx.desktop.player.LocalMusic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The phone's TrackAnalyzer for AutoMix: measures a song once (tempo, beats, key, where its
 * intro ends and its outro starts) from the copy on this PC, and keeps the result on disk so it
 * stays measured. Songs not on the PC yet are asked about again once they are.
 */
object TrackAnalyzer {
    private const val Tag = "AutoMix"

    /** Bumped when the analysis changes, so older results are measured again. */
    private const val Version = 1
    private const val RetryMs = 60_000L

    /** Below this much of the stated length, the copy is short (still downloading or broken). */
    private const val MinDecodedFraction = 0.95

    /**
     * Longer songs (hour-long mixes) aren't measured: the whole song is held in memory while it
     * is, which for a two-hour mix is several hundred MB. Their transitions are plain crossfades.
     */
    private const val MaxSeconds = 20 * 60

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val dir by lazy { File(AppDirs.data, "analysis-v$Version").apply { mkdirs() } }
    private val results = ConcurrentHashMap<String, TrackAnalysis>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val tooLong = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "convx-automix-analysis").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    private val _measured = MutableStateFlow(0)

    /** Counts up as songs are measured, for pages that show it. */
    val measured: StateFlow<Int> = _measured.asStateFlow()

    fun analysisFor(id: String): TrackAnalysis =
        results[id] ?: load(id)?.also { results[id] = it } ?: TrackAnalysis(trackId = id)

    fun isAnalysed(id: String): Boolean = analysisFor(id).status == TrackAnalysis.STATUS_READY

    fun isAnalysing(id: String): Boolean = id in running

    /** How many songs have a measurement on disk. */
    fun storedCount(): Int = dir.list()?.size ?: 0

    /** The whole copy of a song on this PC: a local file, a download, or a finished cache file. */
    fun sourceFile(id: String): File? {
        if (LocalMusic.isLocal(id)) return LocalMusic.track(id)?.path?.let(::File)?.takeIf { it.isFile }
        return Downloads.offline(id)?.second ?: AudioCache.completeFile(id)
    }

    /**
     * Measures [id] in the background if it isn't yet and its audio is on this PC. Cheap to call
     * on every tick: a song already measured, being measured, or recently refused is skipped.
     */
    fun request(id: String, durationSeconds: Double) {
        if (id.isBlank() || id in tooLong || isAnalysed(id)) return
        if (durationSeconds > MaxSeconds) {
            tooLong += id
            return
        }
        if (System.currentTimeMillis() < (retryAfter[id] ?: 0L)) return
        val file = sourceFile(id) ?: run {
            // Not on this PC yet: look again in a moment rather than on every tick.
            retryAfter[id] = System.currentTimeMillis() + 2_000L
            return
        }
        if (!running.add(id)) return
        executor.execute {
            try {
                analyze(id, file, durationSeconds)
            } catch (e: Throwable) {
                Log.w(Tag, "Couldn't measure $id: ${e.message}")
                retryAfter[id] = System.currentTimeMillis() + RetryMs
            } finally {
                running.remove(id)
            }
        }
    }

    /** Measures [id] now, on the calling thread; for the library pass and the build's check. */
    fun analyzeNow(id: String, file: File, durationSeconds: Double = 0.0): TrackAnalysis? {
        analyze(id, file, durationSeconds)
        return results[id]
    }

    private fun analyze(id: String, file: File, durationSeconds: Double) {
        if (id in tooLong || durationSeconds > MaxSeconds) {
            tooLong += id
            return
        }
        val started = System.currentTimeMillis()
        val samples = try {
            decodeMono(file, MaxSeconds)
        } catch (e: TooLong) {
            Log.i(Tag, "Not measuring $id: longer than ${MaxSeconds / 60} minutes")
            tooLong += id
            return
        } ?: run {
            retryAfter[id] = System.currentTimeMillis() + RetryMs
            return
        }
        val decodedSeconds = samples.size / AudioAnalysis.SampleRate
        if (durationSeconds > 0 && decodedSeconds < durationSeconds * MinDecodedFraction) {
            Log.w(Tag, "Measuring $id refused: decoded ${"%.1f".format(decodedSeconds)}s of ${"%.1f".format(durationSeconds)}s")
            retryAfter[id] = System.currentTimeMillis() + RetryMs
            return
        }
        val features = AudioAnalysis.analyze(samples, AudioAnalysis.SampleRate, decodedSeconds)
        val analysis = features.toTrackAnalysis(id)
        results[id] = analysis
        missing.remove(id)
        save(id, analysis)
        _measured.value++
        Log.i(
            Tag,
            "Measured $id in ${System.currentTimeMillis() - started}ms: ${"%.1f".format(analysis.bpm)} BPM " +
                "(${"%.2f".format(analysis.beatConfidence)}), ${analysis.key.ifBlank { "no key" }}, " +
                "intro ends ${"%.1f".format(analysis.introEndTime)}s, outro ${"%.1f".format(analysis.outroStartTime)}s",
        )
    }

    private class TooLong : Exception()

    /**
     * The whole file as mono floats at the analyzer's rate; FFmpeg does the downmix and resampling.
     * Throws [TooLong] once it passes [maxSeconds].
     */
    internal fun decodeMono(file: File, maxSeconds: Int): FloatArray? {
        val limit = maxSeconds.toLong() * AudioAnalysis.SampleRate.toLong()
        val grabber = FFmpegFrameGrabber(file.absolutePath)
        return try {
            grabber.sampleMode = FrameGrabber.SampleMode.FLOAT
            grabber.sampleFormat = avutil.AV_SAMPLE_FMT_FLT
            grabber.sampleRate = AudioAnalysis.SampleRate.toInt()
            grabber.audioChannels = 1
            grabber.start()
            var out = FloatArray(AudioAnalysis.SampleRate.toInt() * 240)
            var size = 0
            while (true) {
                val frame = grabber.grabSamples() ?: break
                val buffer = frame.samples?.firstOrNull() as? FloatBuffer ?: continue
                val count = buffer.limit() - buffer.position()
                if (size + count > limit) throw TooLong()
                if (size + count > out.size) out = out.copyOf(minOf(maxOf(out.size.toLong() * 2, (size + count).toLong()), limit).toInt())
                buffer.get(out, size, count)
                size += count
            }
            out.copyOf(size).takeIf { size > 0 }
        } catch (e: TooLong) {
            throw e
        } catch (e: Throwable) {
            Log.w(Tag, "Couldn't decode ${file.name} for measuring: ${e.message}")
            null
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    private fun fileOf(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    private fun load(id: String): TrackAnalysis? {
        if (id in missing) return null
        val file = fileOf(id)
        if (!file.isFile) {
            missing += id
            return null
        }
        return runCatching { json.decodeFromString(TrackAnalysis.serializer(), file.readText()) }
            .onFailure { Log.w(Tag, "Couldn't read the measurement of $id: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.trackId == id }
    }

    private fun save(id: String, analysis: TrackAnalysis) {
        runCatching {
            val file = fileOf(id)
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(TrackAnalysis.serializer(), analysis))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }.onFailure { Log.w(Tag, "Couldn't save the measurement of $id: ${it.message}") }
    }

    /** Forgets every measurement, for Settings' clear button. */
    fun clear() {
        results.clear()
        missing.clear()
        tooLong.clear()
        dir.listFiles()?.forEach { it.delete() }
        _measured.value++
    }

    private fun AudioAnalysis.Features.toTrackAnalysis(id: String) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = id,
        duration = duration,
        bpm = bpm,
        beatInterval = beatInterval,
        beatConfidence = beatConfidence,
        downbeats = downbeats,
        phraseBoundaries = phraseBoundaries,
        firstBeat = firstBeat,
        key = key,
        keyConfidence = keyConfidence,
        audibleStartTime = audibleStartTime,
        pickupTime = pickupTime,
        introEndTime = introEndTime,
        contentEndTime = contentEndTime.takeIf { it > 0 } ?: duration,
        outroStartTime = outroStartTime,
        mixInTime = mixInTime,
        mixOutTime = mixOutTime,
        mixInCandidates = mixInCandidates,
        mixOutCandidates = mixOutCandidates,
        energyCurve = energyCurve.map { EnergySample(it.time, it.energy) },
        lowEnergyCurve = lowEnergyCurve.map { EnergySample(it.time, it.energy) },
        vocalActivityMask = vocalActivityMask,
        vocalProbability = vocalProbability,
    )
}
