/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.lyrics

import com.convx.desktop.core.AppInfo
import com.convx.desktop.core.Http
import com.convx.desktop.core.Log
import com.convx.desktop.core.Secrets
import com.convx.desktop.data.Song
import com.convx.desktop.integrations.spotify.SpotifyAccount
import com.convx.desktop.integrations.spotify.SpotifyApi
import com.convx.desktop.integrations.spotify.SpotifyMatcher
import com.convx.music.lyrics.bitchord.LyricAlignment
import com.convx.music.lyrics.bitchord.LyricLine
import com.convx.music.lyrics.bitchord.TimedRun
import com.convx.music.lyrics.bitchord.withInstrumentalGaps
import com.convx.music.lyrics.bitchord.wordsFromRuns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Spicy Lyrics' official API (developers.spicylyrics.org): its community's syllable syncs, then
 * Apple Music's, then Spotify's, for a Spotify track id.
 *
 * It works with no setup through Convx's own publishable key ([ConvxKey]); a key of your own,
 * pasted in Settings, is used instead when there is one.
 *
 * Its terms shape this: the answer's source, and a community sync's uploader and maker, are shown
 * wherever the lyrics are ([LyricsCredit]); nothing it returns is written to an .lrc file; a kept
 * copy is dropped after 30 days ([LyricsService]); and when it asks Convx to wait, Convx waits.
 *
 * Songs are found by their Spotify id: the one an imported Spotify song came with, else Spicy
 * Lyrics' own search, else a search through the linked Spotify account, else song.link's lookup of
 * the YouTube video.
 */
object SpicyLyrics {
    private const val Tag = "SpicyLyrics"
    private const val Api = "https://api.spicylyrics.org/v1"
    private const val SongLink = "https://api.song.link/v1-alpha.1/links"
    const val KeyName = "spicylyrics.key"

    /**
     * Convx's own publishable key. Spicy Lyrics makes these to ship inside apps, where anyone can
     * read them (developers.spicylyrics.org/docs/keys): every copy of Convx shares it, and Spicy
     * Lyrics limits each listener on their own. Its application allows "No origin header", the
     * setting Spicy Lyrics has for native clients.
     */
    const val ConvxKey = "sl_pk_3mv93SGbrLrW0W8c6UVNvJmfdQEakk-QKKHkRQ10sP4"

    /** A kept copy of its lyrics older than this is fetched again, as the terms ask. */
    const val KeepForMs = 30L * 24 * 60 * 60 * 1000

    /** The longest Convx waits for a busy lookup before trying once more; longer waits give up. */
    private const val LongestRetryWaitS = 4L

    private val json = Json { ignoreUnknownKeys = true }
    private val credits = ConcurrentHashMap<String, LyricsCredit>()
    private val spotifyIds = ConcurrentHashMap<String, String>()
    private val YouTubeId = Regex("^[A-Za-z0-9_-]{11}$")

    /** Until when Spicy Lyrics asked Convx to stop asking (a rate limit), as a clock time. */
    @Volatile private var quietUntil = 0L

    private val _problem = MutableStateFlow<String?>(null)

    /** What went wrong last, for Settings, or null while it answers. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** Whether this copy of Convx comes with its own key, so the source works with no setup. */
    val hasBuiltInKey: Boolean get() = ConvxKey.isNotBlank()

    private fun key(): String? = Secrets[KeyName]?.trim()?.takeIf { it.isNotEmpty() } ?: ConvxKey.trim().takeIf { it.isNotEmpty() }

    fun setKey(key: String?) {
        Secrets[KeyName] = key?.trim()?.takeIf { it.isNotEmpty() }
        _problem.value = null
        quietUntil = 0L
    }

    /** Who to credit for the lyrics last found for [song] asked as [title] by [artist], read from that answer. */
    fun credit(song: Song, title: String, artist: String): LyricsCredit? = credits[asked(song, title, artist)]

    /** A lookup's own key: a lyrics search with another title is another lookup of the same song. */
    private fun asked(song: Song, title: String, artist: String) = "${song.id}|$title|$artist"

    suspend fun lyrics(song: Song, title: String, artist: String, durationMs: Long): List<LyricLine>? = withContext(Dispatchers.IO) {
        val key = key() ?: return@withContext null
        if (System.currentTimeMillis() < quietUntil) return@withContext null
        val trackId = spotifyId(song, title, artist, durationMs, key) ?: run {
            Log.i(Tag, "No Spotify id for \"$title\" by $artist")
            return@withContext null
        }
        val body = ask("$Api/lyrics/$trackId", key) ?: return@withContext null
        val (lines, credit) = read(body) ?: return@withContext null
        credits[asked(song, title, artist)] = credit
        lines
    }

    /** The lines in a lyrics answer and who to credit for them, or null when it can't be read. */
    internal fun read(body: String): Pair<List<LyricLine>, LyricsCredit>? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val data = root.obj("Body") ?: root
        val lines = parse(data)
        if (lines.isNullOrEmpty()) {
            Log.w(Tag, "Couldn't read a ${data.str("Type")} answer; it has ${data.keys} and items like ${(data["Content"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.keys }}")
            return null
        }
        Log.i(Tag, "${data.str("Type")} lyrics from ${data.str("source")} for ${data.str("id")}")
        return lines to creditOf(data)
    }

    // ---- Asking ---------------------------------------------------------------------------

    /**
     * One GET to the API, the body when it answered 200. A lookup it couldn't do right now (503)
     * is tried once more after the wait it asks for, when that's short; a rate limit (429) keeps
     * every lookup quiet until the wait it gives is over.
     */
    private suspend fun ask(url: String, key: String): String? {
        repeat(2) { attempt ->
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $key")
                .header("Accept", "application/json")
                .header("User-Agent", "Convx/${AppInfo.VERSION} (desktop; ${AppInfo.PLATFORM})")
                .build()
            val (code, body, retryAfter) = Http.client.newCall(request).execute().use {
                Triple(it.code, it.body?.string().orEmpty(), it.header("Retry-After")?.trim()?.toLongOrNull())
            }
            when (code) {
                200 -> {
                    _problem.value = null
                    return body
                }
                400, 404 -> return null
                503 -> {
                    val wait = retryAfter ?: 2L
                    if (attempt == 0 && wait <= LongestRetryWaitS) {
                        delay(wait * 1000)
                        return@repeat
                    }
                    Log.i(Tag, "Busy (${errorOf(body)}), wait ${retryAfter}s")
                    return null
                }
                429 -> {
                    val wait = (retryAfter ?: 60L).coerceIn(5L, 3_600L)
                    quietUntil = System.currentTimeMillis() + wait * 1000
                    _problem.value = "Spicy Lyrics asked Convx to wait ${wait}s before asking again (${errorOf(body)})."
                    Log.w(Tag, "Rate limited for ${wait}s: ${errorOf(body)}")
                    return null
                }
                401, 403 -> {
                    _problem.value = if (Secrets[KeyName].isNullOrBlank()) {
                        "Spicy Lyrics refused Convx's key (${errorOf(body)}). A key of your own works in its place."
                    } else {
                        "Spicy Lyrics refused your key (${errorOf(body)}). Check that it's copied whole and that client access allows no origin header."
                    }
                    Log.w(Tag, "Key refused ($code): ${errorOf(body)}")
                    return null
                }
                else -> {
                    Log.w(Tag, "${url.substringAfter(Api)} answered $code: ${errorOf(body)}")
                    return null
                }
            }
        }
        return null
    }

    /** The error code and message of a refusal, as `{"Body": {"error", "message"}}` carries them. */
    private fun errorOf(body: String): String = runCatching {
        val error = json.parseToJsonElement(body).jsonObject.obj("Body")
        listOfNotNull(error?.str("error"), error?.str("message")).joinToString(": ").ifEmpty { null }
    }.getOrNull() ?: body.take(200)

    // ---- Reading the answer ----------------------------------------------------------------

    /** Times are seconds from the start of the track. */
    private fun toMs(seconds: Double): Long = (seconds * 1000).toLong()

    private fun parse(data: JsonObject): List<LyricLine>? {
        val content = data["Content"] as? JsonArray
        val static = data.str("Type").equals("Static", ignoreCase = true)
        if (content == null || static && content.none { (it as? JsonObject)?.obj("Lead") != null }) {
            val rows = (data["Lines"] as? JsonArray) ?: content ?: return null
            return rows.mapNotNull { (it as? JsonObject)?.str("Text")?.trim() }
                .map { LyricLine(0L, it) }
                .takeIf { it.any { line -> line.text.isNotBlank() } }
        }
        val lines = content.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val alignment = if (item.bool("OppositeAligned") == true) LyricAlignment.End else LyricAlignment.Start
            val lead = item.obj("Lead")
            when {
                lead != null -> vocal(lead)?.let { main ->
                    val backing = (item["Background"] as? JsonArray)?.mapNotNull { vocal(it as? JsonObject) }.orEmpty()
                    main.copy(alignment = alignment, background = merge(backing))
                }
                item["Syllables"] is JsonArray -> vocal(item)?.copy(alignment = alignment)
                else -> {
                    val text = item.str("Text")?.trim() ?: return@mapNotNull null
                    val start = item.num("StartTime") ?: return@mapNotNull null
                    LyricLine(toMs(start), text, sungUntilMs = item.num("EndTime")?.let { toMs(it) }, alignment = alignment)
                }
            }
        }
        return lines.sortedBy { it.timeMs }.withInstrumentalGaps().takeIf { it.any { line -> line.text.isNotBlank() } }
    }

    /** One voice's line from its syllables; [TimedRun]s glue "e" and "nough" back into a word. */
    private fun vocal(obj: JsonObject?): LyricLine? {
        obj ?: return null
        val syllables = obj["Syllables"] as? JsonArray ?: return null
        val runs = syllables.mapNotNull { element ->
            val s = element as? JsonObject ?: return@mapNotNull null
            val text = s.str("Text")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val start = s.num("StartTime") ?: return@mapNotNull null
            val end = s.num("EndTime") ?: start
            TimedRun(toMs(start), toMs(end), if (s.bool("IsPartOfWord") == true) text else "$text ")
        }
        if (runs.isEmpty()) return null
        val words = wordsFromRuns(runs, spacingIsExplicit = true)
        if (words.isEmpty()) return null
        return LyricLine(
            timeMs = obj.num("StartTime")?.let { toMs(it) }?.let { minOf(it, words.first().startMs) } ?: words.first().startMs,
            text = words.joinToString(" ") { it.text },
            words = words,
            sungUntilMs = obj.num("EndTime")?.let { toMs(it) } ?: words.last().endMs,
        )
    }

    /** Several backing voices under one line become one backing line, as the views draw one. */
    private fun merge(lines: List<LyricLine>): LyricLine? = when (lines.size) {
        0 -> null
        1 -> lines.first()
        else -> LyricLine(
            timeMs = lines.minOf { it.timeMs },
            text = lines.joinToString(" ") { it.text },
            words = lines.flatMap { it.words },
            sungUntilMs = lines.mapNotNull { it.sungUntilMs }.maxOrNull(),
        )
    }

    private fun creditOf(data: JsonObject): LyricsCredit {
        val provider = when (val source = data.str("source")) {
            "spicy_lyrics" -> "Spicy Lyrics"
            "apple_music" -> "Apple Music"
            "spotify" -> "Spotify"
            null, "", "unknown" -> "an unknown source"
            else -> source.replace('_', ' ')
        }
        val upload = data.obj("UploadAttribution")
        fun person(o: JsonObject?): CreditLink? = o?.str("username")?.takeIf { it.isNotBlank() }?.let { CreditLink(it, o?.str("url")) }
        return LyricsCredit(provider, person(upload?.obj("Uploader")), person(upload?.obj("Maker")))
    }

    // ---- Finding the Spotify id ----------------------------------------------------------------

    private suspend fun spotifyId(song: Song, title: String, artist: String, durationMs: Long, key: String): String? {
        val asked = asked(song, title, artist)
        spotifyIds[asked]?.let { return it }
        val id = when {
            SpotifyMatcher.isSpotify(song) -> song.id.removePrefix(SpotifyMatcher.Prefix)
            else -> SpotifyMatcher.spotifyIdOf(song.id)
                ?: fromSpicySearch(title, artist, durationMs, key)
                ?: fromSpotifySearch(title, artist, durationMs)
                ?: fromSongLink(song.id)
        }
        if (id != null) spotifyIds[asked] = id
        return id
    }

    /**
     * Spicy Lyrics' own search, by title, artist and length. Its first result is taken only when
     * it is this song ("exact", or "likely" at the same length): another version of a song has
     * other timings.
     */
    private suspend fun fromSpicySearch(title: String, artist: String, durationMs: Long, key: String): String? {
        if (title.isBlank()) return null
        val url = "$Api/tracks/search".toHttpUrl().newBuilder()
            .addQueryParameter("title", title.take(200))
            .apply { if (artist.isNotBlank()) addQueryParameter("artist", artist.take(200)) }
            .apply { if (durationMs > 0) addQueryParameter("duration", (durationMs / 1000.0).toString()) }
            .addQueryParameter("limit", "1")
            .build()
        val body = ask(url.toString(), key) ?: return null
        val first = (json.parseToJsonElement(body).jsonObject.obj("Body")?.get("results") as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return null
        val tier = first.str("tier")
        val off = first.num("duration")?.takeIf { durationMs > 0 }?.let { abs(toMs(it) - durationMs) }
        val sameLength = off == null || off <= 3_000
        val trusted = sameLength && (tier == "exact" || tier == "likely" && off != null)
        if (!trusted) Log.i(Tag, "Search for \"$title\" by $artist found ${first.str("title")} ($tier, ${off?.let { "${it / 1000}s off" } ?: "length unknown"}); not used")
        return first.str("id")?.takeIf { trusted && it.isNotBlank() }
    }

    /** The linked Spotify account's own search, scored the way imports are matched. */
    private suspend fun fromSpotifySearch(title: String, artist: String, durationMs: Long): String? {
        if (SpotifyAccount.state.value !is SpotifyAccount.State.SignedIn || title.isBlank()) return null
        val query = if (artist.isBlank()) title else "track:$title artist:$artist"
        val tracks = SpotifyApi.searchTracks(query, 5).getOrNull().orEmpty()
        val best = tracks.maxByOrNull {
            SpotifyMatcher.score(title, artist, durationMs, it.title, it.artists.joinToString(" "), (it.durationMs / 1000).toInt())
        } ?: return null
        val score = SpotifyMatcher.score(title, artist, durationMs, best.title, best.artists.joinToString(" "), (best.durationMs / 1000).toInt())
        return best.id.takeIf { score >= 0.6 }
    }

    /** song.link's free lookup of a YouTube video's Spotify track, for when Spotify isn't linked or answering. */
    private fun fromSongLink(videoId: String): String? {
        if (!YouTubeId.matches(videoId)) return null
        val target = URLEncoder.encode("https://music.youtube.com/watch?v=$videoId", Charsets.UTF_8)
        val request = Request.Builder()
            .url("$SongLink?url=$target&songIfSingle=true")
            .header("User-Agent", Http.USER_AGENT)
            .build()
        return runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.i(Tag, "song.link answered ${response.code} for $videoId")
                    return null
                }
                val spotify = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject.obj("linksByPlatform")?.obj("spotify")
                spotify?.str("entityUniqueId")?.substringAfter("::", "")?.takeIf { it.isNotEmpty() }
                    ?: spotify?.str("url")?.substringAfter("/track/", "")?.substringBefore('?')?.takeIf { it.isNotEmpty() }
            }
        }.onFailure { Log.w(Tag, "song.link lookup failed for $videoId: ${it.message}") }.getOrNull()
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? =
        this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    private fun JsonObject.num(key: String): Double? =
        this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
    private fun JsonObject.bool(key: String): Boolean? =
        this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
}
