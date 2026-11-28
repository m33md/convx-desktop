/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations.spotify

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.Http
import com.convx.desktop.core.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Spotify's web player API ("pathfinder"), which the phone reads your library through: the same
 * saved queries Spotify's own player sends, with the web player's token ([SpotifyWebPlayer]). It
 * needs no developer app and no Premium, and isn't rationed the way the shared Web API client is.
 *
 * Each query is known by a hash of its text. Spotify changes them now and then; Convx starts from
 * the ones the phone uses, learns the ones Spotify's player is seen sending, and when Spotify says
 * a hash is unknown, reads the current ones out of the web player's code and tries again.
 */
internal object SpotifyPathfinder {
    private const val Tag = "Spotify"

    /** Where the queries go; the check points it at a stand-in. */
    @Volatile internal var queryUrl = "https://api-partner.spotify.com/pathfinder/v2/query"

    /** A token to use instead of the account's; only the build's checks set it (a guest's, for public playlists). */
    @Volatile internal var tokenForChecks: SpotifyWebPlayer.Token? = null

    private val Defaults = mapOf(
        "profileAttributes" to "08ffb4730af3746e04a8301396f20875dbbce10c75243803091a9274eacc8ac0",
        "libraryV3" to "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455",
        "fetchPlaylist" to "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf",
        "fetchLibraryTracks" to "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240",
        "searchDesktop" to "4801118d4a100f756e833d33984436a3899cff359c532f8fd3aaf174b60b3b49",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val hashFile by lazy { File(AppDirs.cache, "spotify-queries.json") }
    private val hashSerializer = MapSerializer(String.serializer(), String.serializer())

    /** The hashes in use, by query name: the defaults, then any learnt since. */
    private val hashes: ConcurrentHashMap<String, String> by lazy {
        val saved = runCatching { json.decodeFromString(hashSerializer, hashFile.readText()) }.getOrNull().orEmpty()
        ConcurrentHashMap(Defaults + saved.filterKeys { it in Defaults })
    }

    /** Takes in hashes seen in use; only queries Convx asks are kept. */
    fun learn(found: Map<String, String>) {
        val useful = found.filter { (name, hash) -> name in Defaults && HashPattern.matches(hash) && hashes[name] != hash }
        if (useful.isEmpty()) return
        hashes.putAll(useful)
        Log.i(Tag, "Spotify's player uses new queries for ${useful.keys.joinToString()}")
        runCatching { hashFile.writeText(json.encodeToString(hashSerializer, hashes.filter { (k, v) -> Defaults[k] != v })) }
    }

    private val HashPattern = Regex("[a-f0-9]{64}")

    /** The web player's code names each query beside its hash: "fetchPlaylist","query","21ea…". */
    private val BundleHash = Regex("""["']([A-Za-z][A-Za-z0-9]*)["']\s*,\s*["'](?:query|mutation)["']\s*,\s*["']([a-f0-9]{64})["']""")

    private val refreshLock = Mutex()
    @Volatile private var refreshedAt = 0L

    /** Reads the current hashes out of the web player's own scripts. True when any changed. */
    private suspend fun refreshFromPlayer(): Boolean = refreshLock.withLock {
        if (System.currentTimeMillis() - refreshedAt < 10 * 60_000L) return@withLock false
        refreshedAt = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            runCatching {
                val before = HashMap(hashes)
                SpotifyWebPlayer.seenQueries.let(::learn)
                val page = fetchText(SpotifyWebPlayer.origin + "/")
                val scripts = Regex("""https://open\.spotifycdn\.com/cdn/build/web-player/[A-Za-z0-9._-]+\.js""").findAll(page)
                    .map { it.value }.distinct()
                    .sortedByDescending { it.contains("/web-player.") }
                    .take(8).toList()
                val found = HashMap<String, String>()
                for (script in scripts) {
                    BundleHash.findAll(fetchText(script)).forEach { found[it.groupValues[1]] = it.groupValues[2] }
                    if (Defaults.keys.all { it in found }) break
                }
                learn(found)
                hashes != before
            }.onFailure { Log.w(Tag, "Couldn't read Spotify's web player for its queries: ${it.message}") }.getOrDefault(false)
        }
    }

    private fun fetchText(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("$url answered ${response.code}")
            return response.body.string()
        }
    }

    /** Queries go one at a time, as the player sends them. */
    private val requestLock = Mutex()

    /** While Spotify has asked Convx to wait, what it said. */
    var onWaiting: (String?) -> Unit = {}

    /** Longest wait Convx sits out by itself before saying so. */
    private const val MaxPatientWait = 15L

    /**
     * Sends [operation] with [variables] and gives back its "data". A token that has run out is
     * replaced once, as is a query hash Spotify no longer knows.
     */
    suspend fun query(operation: String, variables: JsonObject = JsonObject(emptyMap())): JsonObject {
        var newToken = false
        var newHashes = false
        var waits = 0
        while (true) {
            val token = tokenForChecks ?: SpotifyAccount.webToken(forceRefresh = newToken)
            val (code, body, retryAfter) = requestLock.withLock { send(operation, variables, token) }
            val unknownQuery = body.contains("PersistedQueryNotFound", ignoreCase = true)
            when {
                code == 401 && !newToken -> {
                    newToken = true
                    continue
                }
                code == 429 -> {
                    val wait = (retryAfter ?: (2L shl waits)).coerceAtLeast(1L)
                    Log.w(Tag, "$operation answered 429 (retry after ${retryAfter}s)")
                    if (wait > MaxPatientWait || waits >= 3) {
                        throw SpotifyException(429, "Spotify asked Convx to slow down. Try again in ${if (wait >= 120) "${wait / 60} minutes" else "$wait seconds"}.")
                    }
                    waits++
                    onWaiting("Spotify asked Convx to wait, so it's waiting $wait seconds…")
                    try {
                        delay(wait * 1000)
                    } finally {
                        onWaiting(null)
                    }
                    continue
                }
                unknownQuery && !newHashes -> {
                    newHashes = true
                    Log.w(Tag, "Spotify no longer knows the $operation query; reading the current one")
                    if (refreshFromPlayer()) continue
                    // The player itself shows the queries it sends; a fresh visit may have seen this one.
                    SpotifyAccount.webToken(forceRefresh = true)
                    learn(SpotifyWebPlayer.seenQueries)
                    continue
                }
                unknownQuery -> throw SpotifyException(400, "Spotify changed how its web player reads your library, so Convx needs an update to read it")
                code !in 200..299 -> {
                    Log.w(Tag, "$operation answered $code: ${body.take(300)}")
                    throw SpotifyException(code, "Spotify answered $code")
                }
            }
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: throw SpotifyException(500, "Spotify sent something Convx couldn't read")
            val errors = root.arr("errors").orEmpty()
            val data = root.obj("data")
            if (data == null || (errors.isNotEmpty() && data.isEmpty())) {
                val message = (errors.firstOrNull() as? JsonObject)?.str("message") ?: "no data"
                Log.w(Tag, "$operation: $message")
                throw SpotifyException(400, "Spotify: $message")
            }
            return data
        }
    }

    private suspend fun send(operation: String, variables: JsonObject, token: SpotifyWebPlayer.Token): Triple<Int, String, Long?> =
        withContext(Dispatchers.IO) {
            val hash = hashes[operation] ?: error("No query for $operation")
            val body = buildJsonObject {
                put("variables", variables)
                put("operationName", operation)
                putJsonObject("extensions") {
                    putJsonObject("persistedQuery") {
                        put("version", 1)
                        put("sha256Hash", hash)
                    }
                }
            }.toString()
            val request = Request.Builder()
                .url(queryUrl)
                .header("Authorization", "Bearer ${token.accessToken}")
                .header("App-Platform", "WebPlayer")
                .header("User-Agent", token.userAgent ?: Http.USER_AGENT)
                .header("Origin", SpotifyWebPlayer.origin)
                .header("Referer", SpotifyWebPlayer.origin + "/")
                .header("Accept", "application/json")
                .header("Accept-Language", java.util.Locale.getDefault().toLanguageTag().ifBlank { "en" })
                .apply {
                    token.clientToken?.takeIf { token.clientTokenExpiresAt == 0L || token.clientTokenExpiresAt > System.currentTimeMillis() }
                        ?.let { header("Client-Token", it) }
                    token.clientVersion?.let { header("Spotify-App-Version", it) }
                }
                .post(body.toRequestBody("application/json;charset=UTF-8".toMediaType()))
                .build()
            Http.client.newCall(request).execute().use { response ->
                Triple(response.code, response.body.string(), response.header("Retry-After")?.toLongOrNull())
            }
        }

    // The library, as SpotifyApi hands it out.

    suspend fun profile(): SpotifyProfile {
        val profile = query("profileAttributes").obj("me")?.obj("profile") ?: throw SpotifyException(500, "Spotify sent no profile")
        val id = profile.str("uri")?.substringAfterLast(':') ?: profile.str("username").orEmpty()
        return SpotifyProfile(
            id = id,
            name = profile.str("name")?.takeIf { it.isNotBlank() } ?: id,
            image = profile.obj("avatar")?.arr("sources")?.let(::bestImage),
            premium = false,
        )
    }

    /** Your playlists, folders opened up, in Spotify's own order (most recently played first). */
    suspend fun playlists(): List<SpotifyPlaylist> {
        val all = ArrayList<SpotifyPlaylist>()
        var offset = 0
        while (true) {
            val library = query(
                "libraryV3",
                buildJsonObject {
                    putJsonArray("filters") { add("Playlists") }
                    put("order", JsonNull)
                    put("textFilter", "")
                    putJsonArray("features") {
                        listOf("LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "EVENTS").forEach { add(it) }
                    }
                    put("limit", 50)
                    put("offset", offset)
                    put("flatten", true)
                    putJsonArray("expandedFolders") {}
                    put("folderUri", JsonNull)
                    put("includeFoldersWhenFlattening", false)
                },
            ).obj("me")?.obj("libraryV3") ?: throw SpotifyException(500, "Spotify sent no library")
            val items = library.arr("items").orEmpty()
            items.mapNotNullTo(all) { (it as? JsonObject)?.obj("item")?.let(::playlist) }
            offset += items.size
            val total = library.int("totalCount") ?: 0
            if (items.isEmpty() || offset >= total || offset >= 5_000) break
        }
        return all.distinctBy { it.id }
    }

    internal fun playlist(wrapper: JsonObject): SpotifyPlaylist? {
        if (wrapper.str("__typename")?.let { it != "PlaylistResponseWrapper" } == true) return null
        val data = wrapper.obj("data") ?: return null
        if (data.str("__typename")?.let { it != "Playlist" } == true) return null
        val uri = wrapper.str("_uri") ?: data.str("uri") ?: return null
        if (!uri.startsWith("spotify:playlist:")) return null
        val owner = data.obj("ownerV2")?.obj("data")
        val images = data.obj("images")?.arr("items")?.flatMap { (it as? JsonObject)?.arr("sources").orEmpty() }
        val count = listOf("content", "contents", "tracks", "tracksV2").firstNotNullOfOrNull { data.obj(it)?.int("totalCount") }
            ?: data.int("totalCount") ?: data.int("trackCount") ?: -1
        return SpotifyPlaylist(
            id = uri.substringAfterLast(':'),
            name = data.str("name").orEmpty(),
            owner = owner?.str("name").orEmpty(),
            image = images?.let { bestImage(JsonArray(it)) },
            trackCount = count,
            description = data.str("description").orEmpty(),
        )
    }

    /** Every song of playlist [id], in its order. */
    suspend fun playlistTracks(id: String): List<SpotifyTrack> {
        val all = ArrayList<SpotifyTrack>()
        var offset = 0
        while (true) {
            val content = query(
                "fetchPlaylist",
                buildJsonObject {
                    put("uri", "spotify:playlist:$id")
                    put("offset", offset)
                    put("limit", 100)
                    put("enableWatchFeedEntrypoint", false)
                },
            ).obj("playlistV2")?.let { playlist ->
                // A playlist that's gone or private says so in its type.
                playlist.str("__typename")?.takeIf { it != "Playlist" }?.let { throw SpotifyException(404, "Spotify can't open this playlist ($it)") }
                playlist.obj("content")
            } ?: throw SpotifyException(500, "Spotify sent no songs for this playlist")
            val items = content.arr("items").orEmpty()
            items.mapNotNullTo(all) { row ->
                val wrapper = (row as? JsonObject)?.obj("itemV2") ?: return@mapNotNullTo null
                track(wrapper.obj("data"), wrapper.str("_uri"))
            }
            offset += items.size
            val total = content.int("totalCount") ?: 0
            if (items.isEmpty() || offset >= total || offset >= 10_000) break
        }
        return all
    }

    /** Your Liked Songs, newest first, as Spotify lists them. */
    suspend fun likedSongs(): List<SpotifyTrack> {
        val all = ArrayList<SpotifyTrack>()
        var offset = 0
        while (true) {
            val tracks = likedPage(offset, 50)
            val items = tracks.arr("items").orEmpty()
            items.mapNotNullTo(all) { row ->
                val wrapper = (row as? JsonObject)?.obj("track") ?: return@mapNotNullTo null
                track(wrapper.obj("data"), wrapper.str("_uri"))
            }
            offset += items.size
            val total = tracks.int("totalCount") ?: 0
            if (items.isEmpty() || offset >= total || offset >= 10_000) break
        }
        return all
    }

    suspend fun likedCount(): Int = likedPage(0, 1).int("totalCount") ?: 0

    private suspend fun likedPage(offset: Int, limit: Int): JsonObject =
        query("fetchLibraryTracks", buildJsonObject { put("offset", offset); put("limit", limit) })
            .obj("me")?.obj("library")?.obj("tracks") ?: throw SpotifyException(500, "Spotify sent no Liked Songs")

    /** Spotify's songs for a search, best first. */
    suspend fun searchTracks(text: String, limit: Int): List<SpotifyTrack> {
        val search = query(
            "searchDesktop",
            buildJsonObject {
                put("searchTerm", text)
                put("offset", 0)
                put("limit", limit.coerceIn(1, 10))
                put("numberOfTopResults", 5)
                put("includeAudiobooks", false)
                put("includeArtistHasConcertsField", false)
                put("includePreReleases", false)
                put("includeLocalConcertsField", false)
                put("includeAuthors", false)
            },
        ).obj("searchV2")
        return search?.obj("tracksV2")?.arr("items").orEmpty().mapNotNull { row ->
            val item = (row as? JsonObject)?.obj("item") ?: return@mapNotNull null
            track(item.obj("data"), item.str("_uri"))
        }
    }

    /**
     * A song as Spotify's player lists it. Podcast episodes, files of your own and songs Spotify
     * can't offer have other types, and can't be matched to YouTube Music.
     */
    internal fun track(data: JsonObject?, wrapperUri: String?): SpotifyTrack? {
        data ?: return null
        if (data.str("__typename")?.let { it != "Track" } == true) return null
        val uri = wrapperUri ?: data.str("uri") ?: data.str("_uri") ?: return null
        if (!uri.startsWith("spotify:track:")) return null
        val album = data.obj("albumOfTrack")
        val duration = data.obj("trackDuration")?.long("totalMilliseconds")
            ?: data.obj("duration")?.long("totalMilliseconds")
            ?: data.long("durationMs")
            ?: 0L
        return SpotifyTrack(
            id = uri.substringAfterLast(':'),
            title = data.str("name").orEmpty(),
            artists = data.obj("artists")?.arr("items")?.mapNotNull { (it as? JsonObject)?.obj("profile")?.str("name") }.orEmpty(),
            album = album?.str("name"),
            image = album?.obj("coverArt")?.arr("sources")?.let(::bestImage),
            durationMs = duration,
            explicit = data.obj("contentRating")?.str("label") == "EXPLICIT",
        )
    }

    /** The largest picture up to 640 px, else the first. */
    private fun bestImage(sources: JsonArray): String? {
        val pictures = sources.mapNotNull { it as? JsonObject }.filter { it.str("url") != null }
        return pictures.filter { (it.int("width") ?: 0) in 1..640 }.maxByOrNull { it.int("width") ?: 0 }?.str("url")
            ?: pictures.firstOrNull()?.str("url")
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.prim(key: String) = this[key]?.takeIf { it !is JsonNull && it !is JsonObject && it !is JsonArray }?.jsonPrimitive
    private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = prim(key)?.intOrNull
    private fun JsonObject.long(key: String): Long? = prim(key)?.longOrNull
}
