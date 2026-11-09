/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.player

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.AppInfo
import com.convx.desktop.core.Log
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.AudioQuality
import com.convx.desktop.data.Settings
import com.convx.desktop.data.Song
import com.music.spine.ModuleManager
import com.music.spine.ModuleSearchResult
import com.music.spine.ModuleStreamResponse
import com.music.spine.ModuleStreamTrack
import com.music.spine.SpineModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's Modules at play time (ModulePlaybackResolver): the modules that are on are asked
 * for the song at once, a few at a time, and the highest one in the user's order that has it
 * plays. A song matches on its title, an artist, and a length within ten seconds.
 *
 * Playback here saves each song to disk as it plays, so only answers that are one audio file
 * are taken. A DASH or HLS answer moves on to the module's next quality, and Dolby Atmos is
 * left for stereo, which is what the PC plays.
 */
object ModuleStreams {
    private const val Tag = "Modules"

    /** What a module's stream carries as its client, so a retry after it fails goes to YouTube. */
    const val Client = "Module"

    /** How long the modules have to answer, as on the phone. */
    private const val BudgetMs = 15_000L

    /** How long "no module has this song" holds before they're asked again. */
    private const val MissMs = 30 * 60 * 1000L

    private val gate = Semaphore(3)
    private val misses = ConcurrentHashMap<String, Long>()

    /** Whether any module is on. */
    val active: Boolean get() = AppSettings.value.let { it.enabledModules.isNotEmpty() && it.moduleSources.isNotEmpty() }

    /** Eclipse addons want to know the app and its version; module scripts are kept between launches. */
    fun init() {
        ModuleManager.userAgent = "Convx/${AppInfo.VERSION}"
        ModuleManager.cacheDir = File(AppDirs.cache, "module-scripts")
    }

    /** Forgets every miss, after a module was switched on or its settings changed. */
    fun forget() {
        misses.clear()
        failed.clear()
    }

    /** Songs whose module stream wouldn't play: YouTube plays them for the rest of the session. */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    fun markFailed(songId: String) {
        failed += songId
    }

    /**
     * A copy of [song] saved from a module, if one is finished, described from its file alone.
     * Such a copy plays straight from disk with no module asked.
     */
    fun saved(song: Song): ResolvedStream? {
        val file = AudioCache.completeModuleFile(song.id) ?: return null
        val mime = when (file.extension.lowercase()) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "audio/mp4"
        }
        val durationMs = song.duration?.takeIf { it > 0 }?.times(1000L)
        return ResolvedStream(
            videoId = song.id,
            url = file.toURI().toString(),
            mimeType = mime,
            bitrate = durationMs?.let { (file.length() * 8_000 / it).toInt() } ?: 0,
            sampleRate = null,
            contentLength = file.length(),
            loudnessDb = null,
            durationMs = durationMs,
            client = Client,
            expiresAt = Long.MAX_VALUE,
            module = "Saved from a module",
        )
    }

    /** The song from the first module in the user's order that has it, or null when none does in time. */
    suspend fun resolve(song: Song, quality: AudioQuality): ResolvedStream? = withContext(Dispatchers.IO) {
        val settings = AppSettings.value
        if (song.id in failed) return@withContext null
        val missKey = song.id + "|" + fingerprint(settings, quality)
        misses[missKey]?.let { at -> if (System.currentTimeMillis() - at < MissMs) return@withContext null }
        val artists = song.artists.map { it.name }.filter { it.isNotBlank() }
        val title = cleanTitle(song.title, artists)
        if (title.isBlank()) return@withContext null
        val request = Request(
            enabledOrder = settings.enabledModules,
            settings = settings.moduleSettings,
            title = title,
            artists = artists,
            duration = song.duration,
            query = searchQuery("$title ${artists.joinToString(" ")}"),
            quality = quality,
        )
        val started = System.currentTimeMillis()
        val match = try {
            find(settings.moduleSources, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(Tag, "${song.id}: modules failed", e)
            null
        }
        if (match == null) {
            Log.i(Tag, "${song.id}: no module had \"$title\" (${System.currentTimeMillis() - started} ms)")
            misses[missKey] = System.currentTimeMillis()
            return@withContext null
        }
        val stream = match.toStream(song)
        Log.i(Tag, "${song.id}: playing via ${match.module.name}, ${stream.badge} (${System.currentTimeMillis() - started} ms)")
        stream
    }

    private data class Request(
        val enabledOrder: List<String>,
        val settings: Map<String, Map<String, String>>,
        val title: String,
        val artists: List<String>,
        val duration: Int?,
        val query: String,
        val quality: AudioQuality,
    )

    private data class Match(
        val module: SpineModule,
        val track: ModuleSearchResult,
        val stream: ModuleStreamResponse,
        val mimeType: String,
        val contentLength: Long,
        val priority: Int,
    )

    private suspend fun find(sourceUrls: List<String>, request: Request): Match? = withTimeoutOrNull(BudgetMs) {
        supervisorScope {
            val deadline = System.currentTimeMillis() + BudgetMs
            val answers = Channel<Match>(Channel.UNLIMITED)
            val jobs = sourceUrls.distinct().map { source ->
                launch(Dispatchers.IO) {
                    val modules = ModuleManager.shared.fetchIndex(source).getOrNull().orEmpty()
                        .filter { it.id in request.enabledOrder }
                        .sortedBy { request.enabledOrder.indexOf(it.id) }
                    supervisorScope {
                        modules.forEach { module ->
                            launch {
                                try {
                                    val match = gate.withPermit { findIn(module, source, request) }
                                    if (match != null) answers.send(match)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w(Tag, "${module.name} failed: ${e.message}")
                                }
                            }
                        }
                    }
                }
            }
            val completion = launch { jobs.joinAll(); answers.close() }
            try {
                val first = answers.receiveCatching().getOrNull() ?: return@supervisorScope null
                if (first.priority == 0) return@supervisorScope first
                // A module higher in the order gets a moment to answer too.
                val candidates = mutableListOf(first)
                val grace = minOf(400L, deadline - System.currentTimeMillis() - 20L)
                if (grace > 0) withTimeoutOrNull(grace) {
                    for (answer in answers) {
                        candidates.add(answer)
                        if (answer.priority == 0) break
                    }
                }
                candidates.minByOrNull { it.priority }
            } finally {
                jobs.forEach { it.cancel() }
                completion.cancel()
                answers.cancel()
            }
        }
    }

    private suspend fun findIn(module: SpineModule, source: String, request: Request): Match? {
        val loaded = ModuleManager.shared.loadModule(module) { source.substringBeforeLast('/') }.getOrThrow()
        val moduleSettings = request.settings[module.id].orEmpty()
        val configured = loaded.manifest.defaults + moduleSettings.filterValues { it.isNotBlank() }
        val wanted = stereo(loaded, compatibleQuality(loaded, selectedQuality(request.quality, moduleSettings)))
        fun settingsFor(tier: String) = configured + mapOf("quality" to tier, "dolbyAtmos" to "false")

        val candidates = candidates(loaded, request, settingsFor(wanted))
        // Hi-res often comes as DASH, which isn't one file: CD quality is next, then AAC.
        val tiers = buildList {
            add(wanted)
            if (isHiRes(wanted)) add(compatibleQuality(loaded, "LOSSLESS"))
            if (wanted != "LOW") add("HIGH")
        }.distinct()

        for (track in candidates) {
            // A URL on a search result is often only the add-on's default AAC; the stream endpoint
            // is asked for each tier first, so the quality setting can reach FLAC.
            for (tier in tiers) {
                val stream = ModuleManager.shared.getStreamUrl(loaded, track.id, settingsFor(tier), hostTier(tier))
                    .getOrNull() ?: continue
                val url = stream.streamUrl?.takeIf(::validUrl) ?: continue
                if (isManifest(stream, url)) continue
                val mime = mimeType(stream, track, url)
                if (isAtmos(stream, mime)) continue
                if (isLossless(tier) && !mime.isLosslessMime()) continue
                val length = StreamResolver.probe(url) ?: continue
                return Match(module, track, stream, mime, length, request.enabledOrder.indexOf(module.id))
            }
            // Older modules put a playable URL on the search result and have no stream endpoint.
            val direct = track.streamUrl?.takeIf(::validUrl) ?: continue
            val stream = ModuleStreamResponse(
                streamUrl = direct,
                track = ModuleStreamTrack(id = track.id, format = track.format, audioQuality = track.audioQuality),
            )
            if (isManifest(stream, direct)) continue
            val mime = mimeType(stream, track, direct)
            if (isAtmos(stream, mime)) continue
            val length = StreamResolver.probe(direct) ?: continue
            return Match(module, track, stream, mime, length, request.enabledOrder.indexOf(module.id))
        }
        return null
    }

    private suspend fun candidates(
        loaded: ModuleManager.LoadedModule,
        request: Request,
        settings: Map<String, String>,
    ): List<ModuleSearchResult> {
        var tracks = ModuleManager.shared.searchTracks(loaded, request.query, 8, settings).getOrThrow().tracks
        if (tracks.isEmpty() && request.artists.isNotEmpty()) {
            tracks = ModuleManager.shared.searchTracks(loaded, "${request.artists.first()} ${request.title}", 8, settings)
                .getOrThrow().tracks
        }
        return tracks
            .filter { matches(it, request.title, request.artists, request.duration) }
            .sortedBy { kotlin.math.abs(it.duration - (request.duration ?: it.duration)) }
            .take(2)
    }

    private fun Match.toStream(song: Song): ResolvedStream {
        val lossless = mimeType.isLosslessMime()
        val rate = stream.track?.sampleRate?.let { if (it < 1000) (it * 1000).toInt() else it.toInt() }
        val bitrate = stream.track?.bitrate?.let { if (it < 10_000) it * 1000 else it }
            ?: if (lossless) 1_411_000 else 320_000
        return ResolvedStream(
            videoId = song.id,
            url = requireNotNull(stream.streamUrl),
            mimeType = mimeType,
            bitrate = bitrate,
            sampleRate = rate,
            contentLength = contentLength,
            loudnessDb = null,
            durationMs = track.duration.takeIf { it > 0 }?.times(1000L) ?: song.duration?.times(1000L),
            client = Client,
            // Module links can be short-lived; the song is saved as it plays, so this is only for the next minute.
            expiresAt = System.currentTimeMillis() + 60_000L,
            module = module.name,
            bitDepth = stream.track?.bitDepth,
        )
    }

    /** What decides the answer, so a miss is forgotten when any of it changes. */
    private fun fingerprint(settings: Settings, quality: AudioQuality): Int =
        listOf(settings.moduleSources, settings.enabledModules, settings.moduleSettings, quality).hashCode()

    private fun selectedQuality(quality: AudioQuality, moduleSettings: Map<String, String>): String {
        moduleSettings["quality"]?.takeIf { it.isNotBlank() }?.let { return it }
        if (quality == AudioQuality.LOW) return "LOW"
        // A manifest's default is often its safe AAC tier; lossless is tried unless the user chose otherwise.
        return "LOSSLESS"
    }

    private fun compatibleQuality(loaded: ModuleManager.LoadedModule, quality: String): String {
        val options = loaded.manifest.settings["quality"]?.options?.map { it.value }.orEmpty()
        if (quality in options || options.isEmpty()) return quality
        return when (quality.uppercase(Locale.ROOT)) {
            "HIRES", "HI_RES" -> listOf("HI_RES", "HIRES")
            "LOSSLESS", "CD" -> listOf("CD", "LOSSLESS")
            "EAC3_JOC", "MAX", "DOLBY_ATMOS" -> listOf("HI_RES_DOLBY", "MAX", "EAC3_JOC")
            else -> listOf(quality)
        }.firstOrNull { it in options } ?: quality
    }

    /** Dolby Atmos asks for the best stereo tier instead. */
    private fun stereo(loaded: ModuleManager.LoadedModule, quality: String) =
        if (wantsAtmos(quality)) compatibleQuality(loaded, "HIRES") else quality

    private fun wantsAtmos(quality: String) = quality.uppercase(Locale.ROOT).let {
        it == "MAX" || it.contains("DOLBY") || it.contains("ATMOS") || it.contains("EAC3")
    }

    private fun isHiRes(quality: String) = quality.uppercase(Locale.ROOT).let { it.contains("HIRES") || it.contains("HI_RES") }

    private fun isLossless(quality: String): Boolean = quality.uppercase(Locale.ROOT).let {
        it.contains("LOSSLESS") || it.contains("HIRES") || it.contains("HI_RES") || it.contains("FLAC") || it == "CD"
    }

    private fun hostTier(quality: String) = when (quality.uppercase(Locale.ROOT)) {
        "LOW" -> "LOW"
        "HIGH" -> "HIGH"
        else -> "LOSSLESS"
    }

    private fun String.isLosslessMime() = contains("flac", true) || contains("alac", true) || contains("wav", true)

    private fun isManifest(stream: ModuleStreamResponse, url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        return stream.streamType.equals("dash", true) || stream.streamType.equals("hls", true) ||
            stream.mimeType.orEmpty().contains("mpegurl", true) || stream.mimeType.orEmpty().contains("dash", true) ||
            path.endsWith(".mpd", true) || path.endsWith(".m3u8", true)
    }

    private fun isAtmos(stream: ModuleStreamResponse, mime: String): Boolean =
        listOf(mime, stream.track?.codec, stream.track?.audioQuality).any {
            it?.let { value -> value.contains("EAC3", true) || value.contains("EC-3", true) || value.contains("ATMOS", true) } == true
        } || stream.track?.audioModes.orEmpty().any { it.contains("ATMOS", true) }

    private fun mimeType(stream: ModuleStreamResponse, track: ModuleSearchResult, url: String): String {
        stream.track?.mimeType?.takeIf { it.startsWith("audio/", true) }?.let { return it }
        stream.mimeType?.takeIf { it.startsWith("audio/", true) }?.let { return it }
        val resolved = listOfNotNull(stream.track?.format, stream.track?.codec, stream.track?.audioQuality)
        val description = (if (resolved.any { it.isNotBlank() }) resolved else listOf(track.audioQuality, track.format))
            .plus(runCatching { URI(url).path.orEmpty() }.getOrDefault(""))
            .joinToString(" ").uppercase(Locale.ROOT)
        return when {
            "EAC3" in description || "EC-3" in description || "ATMOS" in description -> "audio/mp4; codecs=\"ec-3\""
            "ALAC" in description -> "audio/mp4; codecs=\"alac\""
            "FLAC" in description || "LOSSLESS" in description || "HIRES" in description -> "audio/flac"
            "OPUS" in description -> "audio/ogg; codecs=\"opus\""
            "AAC" in description || ".M4A" in description || ".MP4" in description -> "audio/mp4"
            else -> "audio/mpeg"
        }
    }

    private fun validUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
            !uri.path.orEmpty().endsWith("/undefined") && !uri.path.orEmpty().endsWith("/null")
    }.getOrDefault(false)

    private fun matches(track: ModuleSearchResult, title: String, artists: List<String>, duration: Int?): Boolean {
        val requested = normalize(title)
        if (requested.isEmpty() || normalize(track.title) != requested) return false
        if (artists.isNotEmpty() && artists.none { artist ->
                val identity = normalize(artist)
                identity.isNotEmpty() && (" " + normalize(track.artist) + " ").contains(" $identity ")
            }
        ) return false
        return duration == null || duration <= 0 || track.duration <= 0 || kotlin.math.abs(track.duration - duration) <= 10
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s*[\\[(](?:feat\\.?|ft\\.?|featuring)\\s+[^\\])]*[\\])]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private val Whitespace = Regex("\\s+")
    private val OfficialTag = Regex("(?i)\\(\\s*official\\s*(video|music\\s*video|audio)?\\s*\\)")
    private val LyricsTag = Regex("(?i)\\(\\s*lyrics?\\s*\\)")
    private val BracketedTag = Regex("\\[.*?\\]")
    private val MusicNotes = Regex("[\\u266A\\u266B]")

    /** The phone's cleanYouTubeTitle: no "Artist - " in front, no "(Official Video)" or "[...]" after. */
    private fun cleanTitle(title: String, artists: List<String>): String {
        var cleaned = title
        for (artist in artists) cleaned = cleaned.replace(Regex("(?i)^\\s*${Regex.escape(artist)}\\s*[-–—]\\s*"), "")
        return cleaned.replace(OfficialTag, "").replace(LyricsTag, "").replace(BracketedTag, "")
            .replace(MusicNotes, "").replace(Whitespace, " ").trim()
    }

    private fun searchQuery(query: String): String =
        query.replace("&", " ").replace(",", " ").replace(Whitespace, " ").trim()
}
