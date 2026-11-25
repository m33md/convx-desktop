/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations.spotify

import com.convx.desktop.core.Http
import com.convx.desktop.core.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class SpotifyProfile(val id: String, val name: String, val image: String?, val premium: Boolean)

@Serializable
data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val owner: String,
    val image: String?,
    /** How many songs, or -1 when Spotify didn't say (its web player lists playlists without a count). */
    val trackCount: Int,
    val description: String = "",
)

@Serializable
data class SpotifyTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val image: String?,
    val durationMs: Long,
    val explicit: Boolean,
)

/**
 * Your Spotify library, read-only: who you are, your playlists, what's in them, and your Liked
 * Songs. Logged in as the phone is, it comes through the web player's API ([SpotifyPathfinder]);
 * linked through a developer app, through Spotify's Web API. Pages are fetched whole and kept for
 * the session; [clearCache] drops them.
 */
object SpotifyApi {
    private const val Tag = "Spotify"
    private const val Base = "https://api.spotify.com/v1"
    private val json = Json { ignoreUnknownKeys = true }

    private val playlistTracks = ConcurrentHashMap<String, List<SpotifyTrack>>()
    @Volatile private var playlists: List<SpotifyPlaylist>? = null
    @Volatile private var liked: List<SpotifyTrack>? = null

    // One reading of each list at a time: the library, Settings and Profile all ask for the playlists
    // as they open, and each asking Spotify separately is what gets a shared client rationed.
    private val playlistsLock = Mutex()
    private val likedLock = Mutex()
    private val trackLocks = ConcurrentHashMap<String, Mutex>()

    private val _waiting = MutableStateFlow<String?>(null)

    init {
        SpotifyPathfinder.onWaiting = { _waiting.value = it }
    }

    /** Logged in as the phone is: the web player's API rather than the Web API. */
    private val web: Boolean get() = SpotifyAccount.usesWebPlayer

    /** While Spotify has asked Convx to slow down, what it said, so a page can show it instead of "…". */
    val waiting: StateFlow<String?> = _waiting.asStateFlow()

    /**
     * [runCatching] for these calls, except that a cancelled caller stays cancelled: a page that
     * closes mid-request mustn't come back with "the coroutine scope left the composition" as if
     * Spotify had said it.
     */
    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    fun clearCache() {
        playlistTracks.clear()
        playlists = null
        liked = null
    }

    suspend fun me(): Result<SpotifyProfile> = attempt {
        if (web) return@attempt SpotifyPathfinder.profile()
        val obj = get("$Base/me")
        SpotifyProfile(
            id = obj.str("id").orEmpty(),
            name = obj.str("display_name")?.takeIf { it.isNotBlank() } ?: obj.str("id").orEmpty(),
            image = obj.arr("images")?.let(::bestImage),
            premium = obj.str("product") == "premium",
        )
    }

    suspend fun playlists(refresh: Boolean = false): Result<List<SpotifyPlaylist>> = attempt {
        if (!refresh) playlists?.let { return@attempt it }
        playlistsLock.withLock { readPlaylists(refresh) }
    }

    private suspend fun readPlaylists(refresh: Boolean): List<SpotifyPlaylist> {
        // Whoever waited for the lock may find the list already read.
        if (!refresh) playlists?.let { return it }
        if (web) return SpotifyPathfinder.playlists().also { playlists = it }
        val items = pages("$Base/me/playlists?limit=50").mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            // Spotify renamed "tracks" to "items" on playlists; read whichever is there.
            val count = (obj.obj("items") ?: obj.obj("tracks"))?.int("total") ?: 0
            SpotifyPlaylist(
                id = id,
                name = obj.str("name").orEmpty(),
                owner = obj.obj("owner")?.str("display_name").orEmpty(),
                image = obj.arr("images")?.let(::bestImage),
                trackCount = count,
                description = obj.str("description").orEmpty(),
            )
        }.distinctBy { it.id }
        playlists = items
        return items
    }

    suspend fun playlistTracks(id: String, refresh: Boolean = false): Result<List<SpotifyTrack>> = attempt {
        if (!refresh) playlistTracks[id]?.let { return@attempt it }
        trackLocks.getOrPut(id) { Mutex() }.withLock { readPlaylistTracks(id, refresh) }
    }

    private suspend fun readPlaylistTracks(id: String, refresh: Boolean): List<SpotifyTrack> {
        if (!refresh) playlistTracks[id]?.let { return it }
        if (web) return SpotifyPathfinder.playlistTracks(id).also { playlistTracks[id] = it }
        val fields = "limit=100&additional_types=track&market=from_token"
        val rows = try {
            pages("$Base/playlists/$id/items?$fields")
        } catch (e: SpotifyException) {
            if (e.status != 404 && e.status != 410) throw e
            pages("$Base/playlists/$id/tracks?$fields")
        }
        val tracks = rows.mapNotNull { row -> track((row as? JsonObject)?.let { it.obj("item") ?: it.obj("track") }) }
        playlistTracks[id] = tracks
        return tracks
    }

    /** Spotify's tracks for a search, best first by Spotify's own ranking; a handful is plenty for one song. */
    suspend fun searchTracks(query: String, limit: Int = 5): Result<List<SpotifyTrack>> = attempt {
        if (web) return@attempt SpotifyPathfinder.searchTracks(query, limit)
        val q = java.net.URLEncoder.encode(query, Charsets.UTF_8)
        get("$Base/search?type=track&limit=${limit.coerceIn(1, 10)}&q=$q").obj("tracks")?.arr("items").orEmpty()
            .mapNotNull { track(it as? JsonObject) }
    }

    /** How many Liked Songs there are, in one request rather than reading them all. */
    suspend fun likedCount(): Result<Int> = attempt {
        liked?.let { return@attempt it.size }
        if (web) return@attempt SpotifyPathfinder.likedCount()
        get("$Base/me/tracks?limit=1").int("total") ?: 0
    }

    suspend fun likedSongs(refresh: Boolean = false): Result<List<SpotifyTrack>> = attempt {
        if (!refresh) liked?.let { return@attempt it }
        likedLock.withLock { readLiked(refresh) }
    }

    private suspend fun readLiked(refresh: Boolean): List<SpotifyTrack> {
        if (!refresh) liked?.let { return it }
        if (web) return SpotifyPathfinder.likedSongs().also { liked = it }
        val tracks = pages("$Base/me/tracks?limit=50&market=from_token").mapNotNull { row ->
            track((row as? JsonObject)?.let { it.obj("track") ?: it.obj("item") })
        }
        liked = tracks
        return tracks
    }

    private fun track(obj: JsonObject?): SpotifyTrack? {
        obj ?: return null
        // Local files and podcast episodes can't be matched to YouTube Music.
        if (obj.bool("is_local") == true || obj.str("type") == "episode") return null
        val id = obj.str("id") ?: return null
        val album = obj.obj("album")
        return SpotifyTrack(
            id = id,
            title = obj.str("name").orEmpty(),
            artists = obj.arr("artists")?.mapNotNull { (it as? JsonObject)?.str("name") }.orEmpty(),
            album = album?.str("name"),
            image = album?.arr("images")?.let(::bestImage),
            durationMs = obj.long("duration_ms") ?: 0L,
            explicit = obj.bool("explicit") == true,
        )
    }

    /** The largest picture up to 640 px; Spotify lists them largest first. */
    private fun bestImage(images: JsonArray): String? =
        images.mapNotNull { it as? JsonObject }
            .sortedByDescending { it.int("width") ?: 0 }
            .firstOrNull { (it.int("width") ?: 0) <= 640 }
            ?.str("url")
            ?: (images.firstOrNull() as? JsonObject)?.str("url")

    /** Every item of a paged list, following "next" until the end. */
    private suspend fun pages(first: String): List<JsonElement> {
        val all = ArrayList<JsonElement>()
        var url: String? = first
        while (url != null) {
            val page = get(url)
            page.arr("items")?.let(all::addAll)
            url = page.str("next")
            if (all.size > 10_000) break
        }
        return all
    }

    /** Spotify's "wait" is for the whole app, so every request honours it, and they go one at a time. */
    private val requestLock = Mutex()
    @Volatile private var quietUntil = 0L

    /**
     * Longest wait Convx sits out by itself. Spotify hands the shared desktop client a fresh
     * one-minute wait each time it asks again, so waiting longer only kept the countdown going:
     * past this, Convx stops asking until the wait is over and says so.
     */
    private const val MaxPatientWait = 10L

    private fun limited(wait: Long) = SpotifyException(
        429,
        "Spotify is turning away Convx's shared Spotify access right now (it says to wait ${waitText(wait)}). " +
            "Import with Exportify in Settings > Spotify brings your playlists in without it",
    )

    private suspend fun get(url: String, attempt: Int = 0, newToken: Boolean = false): JsonObject {
        val path = url.substringBefore('?').removePrefix(Base)
        val (code, body, retryAfter) = requestLock.withLock {
            val pause = quietUntil - System.currentTimeMillis()
            // Asking again inside a long wait only earns another one, so don't.
            if (pause > MaxPatientWait * 1000) throw limited((pause + 999) / 1000)
            if (pause > 0) {
                _waiting.value = "Spotify asked Convx to wait, so it's waiting ${waitText((pause + 999) / 1000)}…"
                try {
                    delay(pause)
                } finally {
                    _waiting.value = null
                }
            }
            withContext(Dispatchers.IO) {
                val token = SpotifyAccount.accessToken(forceRefresh = newToken)
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .header("User-Agent", Http.USER_AGENT)
                    .build()
                Http.client.newCall(request).execute().use { response ->
                    Triple(response.code, response.body?.string().orEmpty(), response.header("Retry-After")?.toLongOrNull())
                }
            }
        }
        if (code == 429) {
            val wait = (retryAfter ?: 2L).coerceAtLeast(1L)
            quietUntil = System.currentTimeMillis() + wait * 1000
            Log.w(Tag, "$path answered 429 (retry after ${retryAfter}s, try ${attempt + 1})")
            // A long wait is Spotify rationing the shared desktop client; say so rather than hang.
            if (wait > MaxPatientWait || attempt >= 2) throw limited(wait)
            return get(url, attempt + 1)
        }
        quietUntil = 0L
        return when {
            code in 200..299 -> json.parseToJsonElement(body.ifBlank { "{}" }).jsonObject
            code == 401 && !newToken -> get(url, attempt + 1, newToken = true)
            else -> {
                val message = runCatching {
                    json.parseToJsonElement(body).jsonObject.obj("error")?.str("message")
                }.getOrNull()
                // What Spotify said, so "Copy recent log" shows why a list didn't load.
                Log.w(Tag, "$path answered $code${retryAfter?.let { " (retry after ${it}s)" }.orEmpty()}: ${message ?: body.take(200)}")
                throw SpotifyException(code, message?.let { "Spotify: $it" } ?: "Spotify answered $code")
            }
        }
    }

    private fun waitText(seconds: Long): String = when {
        seconds >= 7200 -> "about ${seconds / 3600} hours"
        seconds >= 3600 -> "about an hour"
        seconds >= 120 -> "about ${seconds / 60} minutes"
        else -> "$seconds seconds"
    }

    private fun JsonObject.obj(key: String): JsonObject? = (this[key] as? JsonObject)
    private fun JsonObject.arr(key: String): JsonArray? = (this[key] as? JsonArray)
    private fun JsonObject.str(key: String): String? = this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    private fun JsonObject.int(key: String): Int? = this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
    private fun JsonObject.long(key: String): Long? = this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
    private fun JsonObject.bool(key: String): Boolean? = this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
}
