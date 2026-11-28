/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations.spotify

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.AppScope
import com.convx.desktop.data.AppSettings
import com.convx.desktop.core.Http
import com.convx.desktop.core.Log
import com.convx.desktop.core.Secrets
import com.convx.desktop.integrations.youtube.BrowserSignIn
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Your Spotify account, linked one of two ways:
 *
 * - Logged in as the phone does ([logIn]): Spotify's login page opens in a browser window of its
 *   own, you log in there, and Convx keeps the web player's login cookie, encrypted for your
 *   Windows account. Spotify's web player then signs Convx in ([SpotifyWebPlayer]) and your
 *   library is read the way the player reads it ([SpotifyPathfinder]). No Premium or developer
 *   app needed, and not rationed like the shared Web API client.
 * - Connected through your own Spotify developer app ([signIn]): Spotify's authorisation page,
 *   then the Web API. Spotify hands the app a token through a one-off page on this PC.
 *
 * Either way Convx never sees your password. Spotify's audio stays on Spotify: songs from it play
 * through YouTube Music, matched by title, artist and length, as on the phone.
 */
object SpotifyAccount {
    private const val Tag = "Spotify"

    /** Spotify's desktop client, which accepts a loopback redirect; librespot uses it too. */
    private const val ClientId = "65b708073fc0480ea92a077233ca87bd"
    private val Ports = listOf(8898, 8989, 4381, 5588)
    private val Scopes = listOf(
        "user-read-private",
        "playlist-read-private",
        "playlist-read-collaborative",
        "user-library-read",
        "user-follow-read",
        "user-top-read",
        "user-read-recently-played",
    )

    sealed interface State {
        data object SignedOut : State
        data object SigningIn : State
        /** Spotify's login page is open in a browser window; closing it (or Done) finishes the login. */
        data object WaitingForBrowser : State
        /** Logged in in the browser; Spotify's web player is opening out of sight to sign Convx in. */
        data object Checking : State
        /** [problem] is what Spotify said while it won't hand over the profile. */
        data class SignedIn(val profile: SpotifyProfile?, val problem: String? = null) : State
        data class Failed(val message: String) : State
    }

    @Serializable
    private data class Tokens(val access: String, val refresh: String?, val expiresAt: Long, val clientId: String? = null)

    /** Your own Spotify app's ID when one is set in Settings, else Spotify's desktop client. */
    private fun chosenClientId(): String = AppSettings.value.spotifyClientId.trim().takeIf { it.length == 32 } ?: ClientId

    /** Whether linking uses your own Spotify app. */
    val usesOwnApp: Boolean get() = chosenClientId() != ClientId

    /** The address your own Spotify app must list under Redirect URIs. */
    const val OwnAppRedirect = "http://127.0.0.1:8898/login"

    /** The phone's way in: the web player's login, and what was last read with it. */
    @Serializable
    private data class WebLogin(
        val cookies: SpotifyWebPlayer.Cookies,
        val profile: SpotifyProfile? = null,
        val token: SpotifyWebPlayer.Token? = null,
    ) {
        override fun toString() = "WebLogin(profile=${profile?.name})"
    }

    private const val KeyWeb = "spotify.web"

    /** Spotify's login page, which goes on to the web player once you're in. */
    private val SpotifySite = BrowserSignIn.Site(
        name = "Spotify",
        startUrl = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F",
        host = "open.spotify.com",
        accountCookies = setOf("sp_dc"),
        profileName = "sign-in-spotify",
    )

    @Volatile private var web: WebLogin? = null
    private val webLock = Mutex()

    /** Whether Convx reads Spotify as the web player does, rather than through a developer app. */
    val usesWebPlayer: Boolean get() = web != null

    private val _state = MutableStateFlow<State>(State.SignedOut)
    val state: StateFlow<State> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val file: File by lazy { File(AppDirs.data, "spotify.dat") }
    private val mutex = Mutex()
    private var tokens: Tokens? = null
    private var pending: CompletableDeferred<String?>? = null

    /** Reads the saved sign-in, if any. */
    fun restore() {
        AppScope.launch(Dispatchers.IO) {
            val login = runCatching { Secrets[KeyWeb]?.let { json.decodeFromString(WebLogin.serializer(), it) } }
                .onFailure { Log.w(Tag, "Couldn't read the saved Spotify login", it) }.getOrNull()
            if (login != null) {
                web = login
                _state.value = State.SignedIn(login.profile)
                if (login.profile == null) refreshProfile()
                return@launch
            }
            val saved = runCatching { load() }.onFailure { Log.w(Tag, "Couldn't read the saved Spotify sign-in", it) }.getOrNull()
                ?: return@launch
            tokens = saved
            _state.value = State.SignedIn(null)
            refreshProfile()
        }
    }

    /** Opens Spotify's sign-in in the browser and waits for it to come back. */
    fun signIn(openBrowser: (URI) -> Unit) {
        if (_state.value == State.SigningIn) {
            // A second click cancels the first attempt and starts again.
            pending?.complete(null)
        }
        _state.value = State.SigningIn
        AppScope.launch(Dispatchers.IO) {
            val result = runCatching { authorize(openBrowser) }
            result.onSuccess { got ->
                if (got == null) {
                    if (_state.value == State.SigningIn) _state.value = State.SignedOut
                    return@onSuccess
                }
                mutex.withLock {
                    tokens = got
                    save(got)
                }
                // Your own app's link takes the place of a web player login.
                web = null
                Secrets[KeyWeb] = null
                SpotifyApi.clearCache()
                _state.value = State.SignedIn(null)
                refreshProfile()
            }.onFailure {
                Log.w(Tag, "Spotify sign-in failed", it)
                _state.value = State.Failed(it.message ?: "Spotify sign-in didn't finish")
            }
        }
    }

    fun cancelSignIn() {
        // Before the sign-in page is up there's nothing to cancel yet; just stop waiting for it.
        if (pending?.complete(null) != true && _state.value == State.SigningIn) _state.value = State.SignedOut
    }

    fun signOut() {
        pending?.complete(null)
        browserJob?.cancel()
        tokens = null
        runCatching { file.delete() }
        web = null
        Secrets[KeyWeb] = null
        _state.value = State.SignedOut
        SpotifyApi.clearCache()
    }

    private var browserJob: kotlinx.coroutines.Job? = null

    /**
     * The phone's login: Spotify's own login page in a browser window. When you've logged in and
     * close the window (or press Done), Convx keeps the login cookie and lets Spotify's web player
     * sign it in.
     */
    fun logIn() {
        browserJob?.cancel()
        if (SpotifyWebPlayer.browser() == null) {
            _state.value = State.Failed(SpotifyWebPlayer.NoBrowserException().message!!)
            return
        }
        _state.value = State.WaitingForBrowser
        browserJob = AppScope.launch(Dispatchers.IO) {
            try {
                val session = BrowserSignIn.run(SpotifySite)
                val cookies = SpotifyWebPlayer.cookiesFrom(session) ?: throw BrowserSignIn.ClosedException()
                finishLogin(cookies)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: BrowserSignIn.ClosedException) {
                _state.value = State.SignedOut
            } catch (e: Exception) {
                Log.w(Tag, "Spotify login failed", e)
                _state.value = State.Failed(e.message ?: "The Spotify login didn't finish")
            }
        }
    }

    /** Finishes the login in the browser window, as closing it does. */
    fun finishBrowser() = BrowserSignIn.finish()

    /** Stops a login that's waiting on the browser window. */
    fun cancelLogIn() {
        browserJob?.cancel()
        browserJob = null
        BrowserSignIn.finish()
        if (_state.value == State.WaitingForBrowser || _state.value == State.Checking) _state.value = State.SignedOut
    }

    /**
     * Logs in with the login cookie copied from a browser where you're logged in to Spotify (the
     * value of sp_dc on open.spotify.com). False when [text] isn't one.
     */
    fun logInWithCookie(text: String): Boolean {
        val cookies = SpotifyWebPlayer.cookieFromText(text) ?: return false
        browserJob?.cancel()
        browserJob = AppScope.launch(Dispatchers.IO) {
            try {
                finishLogin(cookies)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(Tag, "Spotify cookie login failed", e)
                _state.value = State.Failed(e.message ?: "Spotify didn't take that cookie")
            }
        }
        return true
    }

    /** Signs the web player in with [cookies], keeps them, and reads who you are. */
    private suspend fun finishLogin(cookies: SpotifyWebPlayer.Cookies) {
        _state.value = State.Checking
        val token = try {
            SpotifyWebPlayer.token(cookies)
        } catch (e: SpotifyWebPlayer.LoggedOutException) {
            throw java.io.IOException("Spotify didn't see you logged in. Log in again, and close the window once Spotify's web player shows your library.")
        }
        SpotifyPathfinder.learn(SpotifyWebPlayer.seenQueries)
        // A developer-app link gives way to this one.
        tokens = null
        runCatching { file.delete() }
        SpotifyApi.clearCache()
        val login = WebLogin(cookies, token = token)
        saveWeb(login)
        _state.value = State.SignedIn(null)
        val profile = SpotifyApi.me()
        profile.onSuccess { me ->
            web?.let { saveWeb(it.copy(profile = me)) }
            _state.value = State.SignedIn(me)
        }.onFailure { error ->
            Log.w(Tag, "Couldn't read the Spotify profile", error)
            _state.value = State.SignedIn(null, error.message ?: "Spotify didn't answer")
        }
    }

    private fun saveWeb(login: WebLogin) {
        web = login
        Secrets[KeyWeb] = json.encodeToString(WebLogin.serializer(), login)
    }

    /**
     * A web player token good for at least another minute. Spotify's player is opened again for a
     * new one when it has run out (about hourly while Convx is reading Spotify), or when
     * [forceRefresh] says Spotify turned the current one down.
     */
    internal suspend fun webToken(forceRefresh: Boolean = false): SpotifyWebPlayer.Token = webLock.withLock {
        val login = web ?: throw SpotifyException(401, "Log in to Spotify first")
        val current = login.token
        if (!forceRefresh && current != null && current.isFresh()) return@withLock current
        val fresh = try {
            SpotifyWebPlayer.token(login.cookies)
        } catch (e: SpotifyWebPlayer.LoggedOutException) {
            web = null
            Secrets[KeyWeb] = null
            _state.value = State.Failed("Your Spotify login ran out. Log in again.")
            throw SpotifyException(401, "Your Spotify login ran out. Log in again.")
        }
        SpotifyPathfinder.learn(SpotifyWebPlayer.seenQueries)
        // Logged out meanwhile: don't bring the login back.
        val still = web ?: throw SpotifyException(401, "Log in to Spotify first")
        saveWeb(still.copy(token = fresh))
        fresh
    }

    /** A token that is good for at least another minute, refreshed when it isn't. */
    suspend fun accessToken(forceRefresh: Boolean = false): String = mutex.withLock {
        val current = tokens ?: throw SpotifyException(401, "Connect your Spotify account first")
        if (!forceRefresh && current.expiresAt - System.currentTimeMillis() > 60_000) return@withLock current.access
        val refresh = current.refresh ?: run {
            expire()
            throw SpotifyException(401, "Your Spotify sign-in ran out. Connect again.")
        }
        val updated = withContext(Dispatchers.IO) {
            tokenRequest(
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refresh)
                    .add("client_id", current.clientId ?: ClientId)
                    .build(),
            )
        }.let { it.copy(refresh = it.refresh ?: refresh, clientId = current.clientId) }
        tokens = updated
        save(updated)
        updated.access
    }

    private fun expire() {
        tokens = null
        runCatching { file.delete() }
        _state.value = State.Failed("Your Spotify sign-in ran out. Connect again.")
    }

    private var profileJob: kotlinx.coroutines.Job? = null

    /**
     * Reads your name and picture; while Spotify won't answer, tries again a little later (four
     * times), and keeps what it said so the profile and Settings can show it.
     */
    fun refreshProfile() {
        profileJob?.cancel()
        profileJob = AppScope.launch(Dispatchers.IO) {
            for (wait in listOf(0L, 20_000L, 90_000L, 300_000L)) {
                kotlinx.coroutines.delay(wait)
                if (_state.value !is State.SignedIn) return@launch
                val result = SpotifyApi.me()
                result.onSuccess {
                    web?.let { login -> saveWeb(login.copy(profile = it)) }
                    if (_state.value is State.SignedIn) _state.value = State.SignedIn(it)
                    return@launch
                }
                val error = result.exceptionOrNull()
                Log.w(Tag, "Couldn't load the Spotify profile", error)
                if (error is SpotifyException && error.status == 401) {
                    if (web == null) expire()
                    return@launch
                }
                val current = _state.value as? State.SignedIn ?: return@launch
                _state.value = current.copy(problem = error?.message ?: "Spotify didn't answer")
            }
        }
    }

    private suspend fun authorize(openBrowser: (URI) -> Unit): Tokens? {
        val random = SecureRandom()
        val verifier = base64Url(ByteArray(64).also(random::nextBytes))
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = base64Url(ByteArray(24).also(random::nextBytes))

        val clientId = chosenClientId()
        // Your own app lists one address; Spotify's desktop client takes any of these.
        val ports = if (clientId == ClientId) Ports else Ports.take(1)
        val (server, port) = ports.firstNotNullOfOrNull { port ->
            runCatching { HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0) to port }.getOrNull()
        } ?: throw IOException("Every port Spotify can send you back to is busy. Close other Spotify tools and try again.")
        val redirect = "http://127.0.0.1:$port/login"
        val answer = CompletableDeferred<String?>()
        pending = answer

        server.createContext("/login") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                val key = URLDecoder.decode(it.substringBefore('='), "UTF-8")
                key to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val ok = query["state"] == state && query["code"] != null
            val page = if (ok) donePage("You're connected", "Convx can see your Spotify playlists and liked songs now. You can close this tab.")
            else donePage("Spotify didn't connect", query["error"]?.let { "Spotify said: $it. Go back to Convx and try again." } ?: "Go back to Convx and try again.")
            val bytes = page.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            if (query["state"] == state) answer.complete(query["code"])
        }
        server.start()
        try {
            val url = "https://accounts.spotify.com/authorize" +
                "?response_type=code" +
                "&client_id=$clientId" +
                "&redirect_uri=${encode(redirect)}" +
                "&code_challenge_method=S256" +
                "&code_challenge=$challenge" +
                "&state=$state" +
                "&scope=${encode(Scopes.joinToString(" "))}"
            openBrowser(URI(url))
            // Long enough to type a password and pass a captcha; the button also cancels it.
            val code = withTimeoutOrNull(5 * 60_000L) { answer.await() } ?: return null
            return tokenRequest(
                FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", redirect)
                    .add("client_id", clientId)
                    .add("code_verifier", verifier)
                    .build(),
            ).copy(clientId = clientId.takeIf { it != ClientId })
        } finally {
            pending = null
            server.stop(1)
        }
    }

    private fun tokenRequest(body: FormBody): Tokens {
        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .header("User-Agent", Http.USER_AGENT)
            .post(body)
            .build()
        Http.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!response.isSuccessful || obj == null) {
                val error = obj?.get("error_description")?.jsonPrimitive?.contentOrNull ?: obj?.get("error")?.jsonPrimitive?.contentOrNull
                if (obj?.get("error")?.jsonPrimitive?.contentOrNull == "invalid_grant") throw SpotifyException(401, "Your Spotify sign-in ran out. Connect again.")
                throw SpotifyException(response.code, "Spotify turned the sign-in down${error?.let { ": $it" } ?: " (${response.code})"}")
            }
            val access = obj["access_token"]?.jsonPrimitive?.contentOrNull ?: throw SpotifyException(500, "Spotify sent no token")
            val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
            return Tokens(access, obj["refresh_token"]?.jsonPrimitive?.contentOrNull, System.currentTimeMillis() + expiresIn * 1000)
        }
    }

    private val windows = System.getProperty("os.name").lowercase().contains("win")

    private fun save(tokens: Tokens) {
        val plain = json.encodeToString(Tokens.serializer(), tokens).toByteArray(Charsets.UTF_8)
        // Windows encrypts it for this user account (DPAPI), as browsers keep their cookies.
        val bytes = if (windows) Crypt32Util.cryptProtectData(plain) else plain
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(bytes)
        if (!windows) {
            temp.setReadable(false, false)
            temp.setReadable(true, true)
        }
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    private fun load(): Tokens? {
        if (!file.isFile) return null
        val bytes = file.readBytes()
        val plain = if (windows) Crypt32Util.cryptUnprotectData(bytes) else bytes
        return json.decodeFromString(Tokens.serializer(), String(plain, Charsets.UTF_8))
    }

    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun donePage(title: String, body: String) = """
        <!doctype html>
        <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Convx</title>
        <style>
          html,body{margin:0;height:100%;background:#000;color:#fff;font-family:-apple-system,"Segoe UI",system-ui,sans-serif}
          main{height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:0 24px}
          .dot{width:56px;height:56px;border-radius:50%;background:#FA2D48;margin-bottom:24px}
          h1{font-size:28px;margin:0 0 10px}
          p{font-size:16px;color:rgba(255,255,255,.65);max-width:420px;line-height:1.45;margin:0}
        </style></head>
        <body><main><div class="dot"></div><h1>$title</h1><p>$body</p></main></body></html>
    """.trimIndent()
}

class SpotifyException(val status: Int, override val message: String) : Exception(message)
