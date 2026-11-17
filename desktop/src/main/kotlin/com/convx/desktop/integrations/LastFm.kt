/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations

import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Http
import com.convx.desktop.core.Log
import com.convx.desktop.core.Secrets
import com.convx.desktop.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Last.fm scrobbling, as the phone's LastFM client does it, signed with an API key and secret.
 *
 * The phone's key is built into its release from the developer's private settings, so it isn't
 * in the source the PC app is made from. Last.fm gives anyone a key in a minute, so you paste
 * your own; then you approve Convx on Last.fm's own page in your browser and Convx gets a
 * session, never your password.
 */
object LastFm {
    private const val Tag = "LastFm"
    private const val Api = "https://ws.audioscrobbler.com/2.0/"
    const val CreateKeyUrl = "https://www.last.fm/api/account/create"

    private const val KeyApi = "lastfm.apiKey"
    private const val KeySecret = "lastfm.secret"
    private const val KeySession = "lastfm.session"
    private const val KeyUser = "lastfm.user"

    sealed interface State {
        data object NoKey : State
        data object SignedOut : State
        data object WaitingForBrowser : State
        data class SignedIn(val user: String) : State
        data class Failed(val message: String) : State
    }

    class LastFmException(val code: Int, message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val _state = MutableStateFlow<State>(State.NoKey)
    val state: StateFlow<State> = _state.asStateFlow()
    private var signInJob: Job? = null

    val apiKey: String? get() = Secrets[KeyApi]
    private val secret: String? get() = Secrets[KeySecret]
    private val session: String? get() = Secrets[KeySession]
    val user: String? get() = Secrets[KeyUser]

    val signedIn: Boolean get() = session != null && apiKey != null && secret != null

    fun restore() {
        _state.value = when {
            apiKey == null || secret == null -> State.NoKey
            session != null -> State.SignedIn(user ?: "Last.fm")
            else -> State.SignedOut
        }
    }

    /** Saves the key pair from last.fm/api; changing it signs out, as the session belongs to the old key. */
    fun setKey(key: String, sharedSecret: String) {
        val k = key.trim()
        val s = sharedSecret.trim()
        if (k != apiKey || s != secret) {
            Secrets[KeySession] = null
            Secrets[KeyUser] = null
        }
        Secrets[KeyApi] = k
        Secrets[KeySecret] = s
        restore()
    }

    /**
     * Last.fm's web sign-in: get a token, let you approve it on last.fm, then trade it for a
     * session. Waits up to five minutes for the approval.
     */
    fun signIn(openBrowser: (URI) -> Unit) {
        val key = apiKey ?: return
        signInJob?.cancel()
        _state.value = State.WaitingForBrowser
        signInJob = AppScope.launch(Dispatchers.IO) {
            runCatching {
                val token = call("auth.getToken", emptyMap(), withSession = false)["token"]?.jsonPrimitive?.contentOrNull
                    ?: error("Last.fm didn't give a sign-in token")
                openBrowser(URI("https://www.last.fm/api/auth/?api_key=${encode(key)}&token=${encode(token)}"))
                val deadline = System.currentTimeMillis() + 5 * 60_000
                while (System.currentTimeMillis() < deadline) {
                    delay(3_000)
                    val result = runCatching { call("auth.getSession", mapOf("token" to token), withSession = false) }
                    val error = result.exceptionOrNull()
                    // 14: not approved yet. Anything else is final.
                    if (error is LastFmException && error.code == 14) continue
                    val body = result.getOrThrow()
                    val s = body["session"]?.jsonObject ?: error("Last.fm sent no session")
                    Secrets[KeySession] = s["key"]?.jsonPrimitive?.contentOrNull ?: error("Last.fm sent no session key")
                    Secrets[KeyUser] = s["name"]?.jsonPrimitive?.contentOrNull
                    return@runCatching
                }
                error("Last.fm's approval page timed out. Try again.")
            }.onSuccess {
                Log.i(Tag, "Signed in as $user")
                restore()
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) return@onFailure
                Log.w(Tag, "Last.fm sign-in failed", it)
                _state.value = State.Failed(friendly(it))
            }
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null
        restore()
    }

    fun signOut() {
        Secrets[KeySession] = null
        Secrets[KeyUser] = null
        restore()
    }

    /** Forgets the key too. */
    fun forgetKey() {
        signOut()
        Secrets[KeyApi] = null
        Secrets[KeySecret] = null
        restore()
    }

    suspend fun nowPlaying(song: Song) {
        if (!signedIn) return
        runCatching { call("track.updateNowPlaying", trackParams(song, null)) }
            .onFailure { Log.w(Tag, "Now playing failed for ${song.id}: ${it.message}") }
    }

    suspend fun scrobble(song: Song, startedAtSeconds: Long): Boolean {
        if (!signedIn) return false
        return runCatching { call("track.scrobble", trackParams(song, startedAtSeconds)) }
            .onFailure { Log.w(Tag, "Scrobble failed for ${song.id}: ${it.message}") }
            .isSuccess
    }

    suspend fun love(song: Song, love: Boolean) {
        if (!signedIn) return
        runCatching {
            call(if (love) "track.love" else "track.unlove", mapOf("artist" to artistOf(song), "track" to song.title))
        }.onFailure { Log.w(Tag, "Love failed for ${song.id}: ${it.message}") }
    }

    /** The phone sends every artist joined with commas, so the PC's scrobbles line up with its. */
    private fun artistOf(song: Song) = song.artists.joinToString(", ") { it.name }.ifBlank { "Unknown artist" }

    private fun trackParams(song: Song, timestamp: Long?): Map<String, String> {
        val suffix = if (timestamp != null) "[0]" else ""
        return buildMap {
            put("artist$suffix", artistOf(song))
            put("track$suffix", song.title)
            timestamp?.let { put("timestamp$suffix", it.toString()) }
            song.album?.name?.takeIf { it.isNotBlank() }?.let { put("album$suffix", it) }
            song.duration?.takeIf { it > 0 }?.let { put("duration$suffix", it.toString()) }
        }
    }

    /** A signed POST to Last.fm; throws [LastFmException] with Last.fm's own message on an error. */
    private suspend fun call(method: String, params: Map<String, String>, withSession: Boolean = true): JsonObject =
        withContext(Dispatchers.IO) {
            val key = apiKey ?: throw LastFmException(10, "No Last.fm API key")
            val sharedSecret = secret ?: throw LastFmException(10, "No Last.fm API key")
            val signed = buildMap {
                put("method", method)
                put("api_key", key)
                if (withSession) put("sk", session ?: throw LastFmException(9, "Not signed in to Last.fm"))
                putAll(params)
            }
            val signature = md5(signed.toSortedMap().entries.joinToString("") { it.key + it.value } + sharedSecret)
            val body = FormBody.Builder().apply {
                signed.forEach { (k, v) -> add(k, v) }
                add("api_sig", signature)
                add("format", "json")
            }.build()
            val request = Request.Builder()
                .url(Api)
                .post(body)
                .header("User-Agent", "Convx for ${com.convx.desktop.core.AppInfo.PLATFORM} (https://github.com/cosmictaserdev-creator/Convx)")
                .build()
            Http.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: throw LastFmException(response.code, "Last.fm answered ${response.code}")
                val code = parsed["error"]?.jsonPrimitive?.intOrNull
                if (code != null) {
                    throw LastFmException(code, parsed["message"]?.jsonPrimitive?.contentOrNull ?: "Last.fm error $code")
                }
                parsed
            }
        }

    private fun friendly(error: Throwable): String = when ((error as? LastFmException)?.code) {
        10, 26 -> "Last.fm doesn't accept that API key. Check it on last.fm/api."
        13 -> "Last.fm says the shared secret doesn't match the key."
        15 -> "That approval expired. Try again."
        else -> error.message ?: "Last.fm sign-in didn't finish"
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")
}
