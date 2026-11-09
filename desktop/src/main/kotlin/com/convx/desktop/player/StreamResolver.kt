/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.player

import com.convx.desktop.core.Http
import com.convx.desktop.core.Log
import com.convx.desktop.data.AudioQuality
import com.music.innertube.NewPipeExtractor
import com.music.innertube.YouTube
import com.music.innertube.models.YouTubeClient
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_65_10
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.music.innertube.models.YouTubeClient.Companion.IOS
import com.music.innertube.models.YouTubeClient.Companion.MOBILE
import com.music.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.music.innertube.models.YouTubeClient.Companion.VISIONOS
import com.music.innertube.models.response.PlayerResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** A playable audio stream for one track, with what the quality badge needs to describe it. */
data class ResolvedStream(
    val videoId: String,
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val sampleRate: Int?,
    val contentLength: Long,
    val loudnessDb: Double?,
    val durationMs: Long?,
    /** The YouTube client that handed out [url], as "NAME version" (several share a name). */
    val client: String,
    val expiresAt: Long,
    /** YouTube's count of plays for this video, when the player response carries it. */
    val viewCount: Long? = null,
    /** Where to tell YouTube this video was played, so the account's history and Home learn from it. */
    val playbackTracking: String? = null,
) {
    val codec: String
        get() = mimeType.substringAfter("codecs=\"", "").substringBefore('"').substringBefore('.').ifBlank {
            mimeType.substringAfter('/').substringBefore(';')
        }

    val container: String get() = if (mimeType.contains("webm")) "webm" else "m4a"

    /** "Opus", "AAC": the codec as the phone's badge names it. */
    private val codecName: String
        get() = when {
            codec.contains("opus", ignoreCase = true) -> "Opus"
            codec.contains("mp4a", ignoreCase = true) || codec.contains("aac", ignoreCase = true) -> "AAC"
            codec.contains("vorbis", ignoreCase = true) -> "Vorbis"
            else -> codec.uppercase()
        }

    /** "Opus 160 kbps": the phone's badge between the elapsed and remaining times. */
    val badge: String
        get() = if (bitrate > 0) "$codecName ${bitrate / 1000} kbps" else codecName

    /** "Opus · 160 kbps · 48 kHz": the detail line of the phone's quality card. */
    val detail: String
        get() = buildList {
            add(codecName)
            if (bitrate > 0) add("${bitrate / 1000} kbps")
            sampleRate?.let { add(if (it % 1000 == 0) "${it / 1000} kHz" else "%.1f kHz".format(it / 1000f)) }
        }.joinToString(" · ")
}

/**
 * Turns a video id into a stream URL. This is the phone's YTPlayerUtils chain minus
 * what needs an Android WebView (PoTokens): clients that hand out direct URLs come
 * first, and the signature-cipher clients go through NewPipe's JS deobfuscation.
 */
object StreamResolver {
    private const val Tag = "StreamResolver"

    /** Same lead client as the phone: fast, no PoToken. Then the phone's fallbacks that work without one. */
    private val clients: List<YouTubeClient> = listOf(
        ANDROID_VR_1_43_32,
        VISIONOS,
        IOS,
        ANDROID_VR_1_61_48,
        ANDROID_VR_1_65_10,
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        ANDROID_VR_NO_AUTH,
        MOBILE,
    )

    private val cache = ConcurrentHashMap<String, ResolvedStream>()

    /**
     * Clients YouTube recently answered with "Sign in to confirm you're not a bot", and until
     * when they go after the others. YouTube checks the network, not the song, so asking such a
     * client first would only add a wasted call before every song.
     */
    private val botChecked = ConcurrentHashMap<String, Long>()
    private const val BotCheckPauseMs = 15 * 60 * 1000L

    private val YouTubeClient.key: String get() = "$clientName $clientVersion"

    private fun isBotChecked(client: YouTubeClient): Boolean =
        (botChecked[client.key] ?: 0L) > System.currentTimeMillis()

    /** The order to ask the clients in: healthy ones first, then bot-checked ones, then [avoid] (their streams just failed). */
    private fun order(avoid: Set<String>): List<YouTubeClient> =
        clients.sortedWith(compareBy<YouTubeClient>({ it.key in avoid }, { isBotChecked(it) }))

    private fun noteStatus(client: YouTubeClient, response: PlayerResponse) {
        if (response.playabilityStatus.status == "OK") {
            botChecked.remove(client.key)
        } else if ("bot" in response.playabilityStatus.reason.orEmpty().lowercase()) {
            botChecked[client.key] = System.currentTimeMillis() + BotCheckPauseMs
        }
    }

    /** Keep a little headroom so a URL never expires mid-download. */
    private const val ExpiryMarginMs = 10 * 60 * 1000L

    fun cached(videoId: String): ResolvedStream? =
        cache[videoId]?.takeIf { it.expiresAt - ExpiryMarginMs > System.currentTimeMillis() }

    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    /**
     * [preferAac] puts AAC first, as the phone does for downloads it also saves to the Music
     * folder, since every Windows player opens .m4a. Such a stream isn't kept for playback.
     * [avoidClients] are tried last: their streams just failed to play.
     */
    suspend fun resolve(
        videoId: String,
        quality: AudioQuality,
        forceRefresh: Boolean = false,
        preferAac: Boolean = false,
        avoidClients: Set<String> = emptySet(),
    ): Result<ResolvedStream> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh && !preferAac) cached(videoId)?.let { return@withContext Result.success(it) }
            // YouTube's own reason ("Video unavailable") when that was the last word: fine to show.
            // Anything else goes only to the log.
            var youTubeReason: String? = null
            var lastReason: String? = null
            var signatureTimestamp: Int? = null
            var signatureTimestampFetched = false
            for (client in order(avoidClients)) {
                currentCoroutineContext().ensureActive()
                if (client.loginRequired && YouTube.cookie == null) continue
                try {
                    val timestamp = if (client.useSignatureTimestamp) {
                        if (!signatureTimestampFetched) {
                            signatureTimestamp = NewPipeExtractor.getSignatureTimestamp(videoId).getOrNull()
                            signatureTimestampFetched = true
                        }
                        signatureTimestamp
                    } else null

                    val response = YouTube.player(videoId, null, client, timestamp, null).getOrNull()
                    if (response == null) {
                        lastReason = "No response from ${client.key}"
                        youTubeReason = null
                        continue
                    }
                    noteStatus(client, response)
                    if (response.playabilityStatus.status != "OK") {
                        lastReason = response.playabilityStatus.reason ?: response.playabilityStatus.status
                        youTubeReason = response.playabilityStatus.reason
                        Log.i(Tag, "$videoId: ${client.key} -> ${response.playabilityStatus.status} ($lastReason)")
                        continue
                    }
                    val formats = rankedAudioFormats(response.streamingData?.adaptiveFormats.orEmpty(), quality).let { ranked ->
                        if (preferAac) ranked.sortedByDescending { it.mimeType.contains("mp4a") } else ranked
                    }
                    for (format in formats.take(3)) {
                        currentCoroutineContext().ensureActive()
                        val url = format.url?.takeIf { it.isNotBlank() }
                            ?: NewPipeExtractor.getStreamUrl(format, videoId)
                            ?: continue
                        val length = probe(url) ?: continue
                        val stream = ResolvedStream(
                            videoId = videoId,
                            url = url,
                            mimeType = format.mimeType,
                            bitrate = format.averageBitrate ?: format.bitrate,
                            sampleRate = format.audioSampleRate,
                            contentLength = format.contentLength?.takeIf { it > 0 } ?: length,
                            loudnessDb = format.loudnessDb,
                            durationMs = format.approxDurationMs?.toLongOrNull()
                                ?: response.videoDetails?.lengthSeconds?.toLongOrNull()?.times(1000),
                            client = client.key,
                            expiresAt = System.currentTimeMillis() +
                                (response.streamingData?.expiresInSeconds ?: 21600) * 1000L,
                            viewCount = response.videoDetails?.viewCount?.toLongOrNull(),
                            playbackTracking = response.playbackTracking?.videostatsPlaybackUrl?.baseUrl,
                        )
                        Log.i(Tag, "$videoId: playing via ${client.key}, ${stream.badge}")
                        if (!preferAac) cache[videoId] = stream
                        return@withContext Result.success(stream)
                    }
                    lastReason = "${client.key} returned no reachable audio"
                    youTubeReason = null
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastReason = e.message
                    youTubeReason = null
                    Log.w(Tag, "$videoId: ${client.key} failed", e)
                }
            }
            Log.w(Tag, "$videoId: no client gave a stream (last: $lastReason)")
            Result.failure(
                IllegalStateException(
                    youTubeReason ?: "Couldn't get this song from YouTube. Check your connection and try again.",
                ),
            )
        }

    /**
     * A new address for the very file [stream] points at (same format, same length), for a
     * download YouTube stopped serving at byte [position]: the bytes already saved stay good and
     * the download carries on from there. Clients in [refused] are asked last. Null when no
     * client hands out that file within [timeoutMs].
     */
    suspend fun renew(
        stream: ResolvedStream,
        position: Long,
        refused: Set<String> = setOf(stream.client),
        timeoutMs: Long = 15_000,
    ): ResolvedStream? {
        if (stream.contentLength <= 0) return null
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                var signatureTimestamp: Int? = null
                var signatureTimestampFetched = false
                for (client in order(refused)) {
                    currentCoroutineContext().ensureActive()
                    if (client.loginRequired && YouTube.cookie == null) continue
                    try {
                        val timestamp = if (client.useSignatureTimestamp) {
                            if (!signatureTimestampFetched) {
                                signatureTimestamp = NewPipeExtractor.getSignatureTimestamp(stream.videoId).getOrNull()
                                signatureTimestampFetched = true
                            }
                            signatureTimestamp
                        } else null
                        val response = YouTube.player(stream.videoId, null, client, timestamp, null).getOrNull() ?: continue
                        noteStatus(client, response)
                        if (response.playabilityStatus.status != "OK") continue
                        val format = response.streamingData?.adaptiveFormats.orEmpty().firstOrNull {
                            it.isAudio && it.mimeType == stream.mimeType && it.contentLength == stream.contentLength
                        } ?: continue
                        val url = format.url?.takeIf { it.isNotBlank() }
                            ?: NewPipeExtractor.getStreamUrl(format, stream.videoId)
                            ?: continue
                        if (url == stream.url || probe(url, position) == null) continue
                        val fresh = stream.copy(
                            url = url,
                            client = client.key,
                            expiresAt = System.currentTimeMillis() + (response.streamingData?.expiresInSeconds ?: 21600) * 1000L,
                        )
                        // The player asks the cache next time (a seek, a replay): give it the address that works.
                        cache.computeIfPresent(stream.videoId) { _, kept -> if (kept.url == stream.url) fresh else kept }
                        return@withContext fresh
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(Tag, "${stream.videoId}: ${client.key} failed while renewing", e)
                    }
                }
                null
            }
        }
    }

    private fun rankedAudioFormats(
        formats: List<PlayerResponse.StreamingData.Format>,
        quality: AudioQuality,
    ): List<PlayerResponse.StreamingData.Format> {
        val audio = formats.filter { it.isAudio && it.isOriginal }.ifEmpty { formats.filter { it.isAudio } }
        return when (quality) {
            AudioQuality.LOW -> audio.sortedBy { it.bitrate }
            // Opus beats AAC at the same bitrate; prefer it, then the highest bitrate.
            AudioQuality.HIGH, AudioQuality.AUTO -> audio.sortedWith(
                compareByDescending<PlayerResponse.StreamingData.Format> { it.bitrate }
                    .thenByDescending { it.mimeType.contains("opus") },
            )
        }
    }

    /**
     * A one-byte ranged GET at [at]: YouTube can accept a HEAD for a URL it won't serve. Returns
     * the full length from Content-Range, or null when the URL doesn't serve bytes.
     */
    private fun probe(url: String, at: Long = 0): Long? = runCatching {
        val request = Request.Builder().url(url)
            .header("Range", "bytes=$at-$at")
            .header("User-Agent", YouTubeClient.USER_AGENT_WEB)
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.i(Tag, "probe -> HTTP ${response.code}")
                return null
            }
            response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { response.code == 200 }
                ?: -1L
        }
    }.getOrNull()
}
