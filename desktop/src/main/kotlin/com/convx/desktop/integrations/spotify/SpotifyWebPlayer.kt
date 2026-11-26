/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations.spotify

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.Log
import com.convx.desktop.integrations.youtube.BrowserSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.IOException
import java.net.URI

/**
 * Spotify's web player, the way the phone logs in to Spotify: you log in on Spotify's own page in
 * a browser window, and Convx keeps the web player's login cookie (`sp_dc`). Whenever Convx needs
 * to talk to Spotify, it opens Spotify's web player with that cookie in a browser out of sight,
 * lets the player sign itself in, and takes the access token the player was given, as the phone
 * does in a hidden WebView. Spotify's own code works the token out, so nothing here has to copy
 * how Spotify guards it. The token is good for about an hour.
 *
 * The page's own requests also say which saved queries ([SpotifyPathfinder]) Spotify's player is
 * using today, and the client token it sends with them.
 */
internal object SpotifyWebPlayer {
    private const val Tag = "Spotify"

    /** Where the web player lives; the token check points it at a stand-in. */
    @Volatile internal var origin = "https://open.spotify.com"

    /** The web player's login. [spKey] is a second cookie Spotify sets beside it. */
    @Serializable
    data class Cookies(val spDc: String, val spKey: String? = null) {
        override fun toString() = "Cookies"
    }

    /** What the web player was given: its access token, and the client token and version it sends with requests. */
    @Serializable
    data class Token(
        val accessToken: String,
        val expiresAt: Long,
        val clientId: String = "",
        val clientToken: String? = null,
        val clientTokenExpiresAt: Long = 0,
        val clientVersion: String? = null,
        val userAgent: String? = null,
    ) {
        fun isFresh(now: Long = System.currentTimeMillis()) = expiresAt - now > 60_000

        override fun toString() = "Token(expiresAt=$expiresAt)"
    }

    /** Spotify's player opened without signing in: the cookie has run out, or was signed out elsewhere. */
    class LoggedOutException : IOException("Spotify says you're not logged in any more. Log in to Spotify again.")

    class NoBrowserException : IOException(
        "Logging in to Spotify needs Microsoft Edge, Google Chrome, Brave or another Chromium browser on this computer " +
            "(Convx opens Spotify's web player in it, out of sight). You can still bring your playlists in with Exportify below.",
    )

    /** The browser the web player is opened in: Edge or Chrome first, as they run headless most reliably. */
    fun browser(): BrowserSignIn.Browser? {
        val chromium = BrowserSignIn.browsers().filter { it.engine == BrowserSignIn.Engine.Chromium }
        val preferred = listOf("msedge", "microsoft edge", "chrome", "google-chrome", "chromium", "brave")
        return chromium.sortedBy { browser ->
            val name = File(browser.path).nameWithoutExtension.lowercase()
            preferred.indexOfFirst { name.startsWith(it) }.let { if (it < 0) preferred.size else it }
        }.firstOrNull()
    }

    /** Only one web player at a time. */
    private val lock = Mutex()

    /** Queries the page was seen using, by name, for [SpotifyPathfinder]. */
    @Volatile var seenQueries: Map<String, String> = emptyMap()

    /** The guest token the player had the last time a cookie wasn't taken; only the build's checks use it. */
    @Volatile internal var lastGuest: Token? = null
        private set

    /**
     * Opens the web player with [cookies] out of sight and gives back the token it signed in with.
     * Takes a few seconds; throws [LoggedOutException] when Spotify doesn't take the cookie.
     */
    suspend fun token(cookies: Cookies, browser: BrowserSignIn.Browser? = browser()): Token = lock.withLock {
        val chosen = browser ?: throw NoBrowserException()
        withContext(Dispatchers.IO) {
            val profile = File(AppDirs.cache, "spotify-player")
            BrowserSignIn.discardProfile(profile)
            profile.mkdirs()
            val started = System.currentTimeMillis()
            try {
                withTimeout(75_000) {
                    BrowserSignIn.withHiddenPage(chosen, profile) { page -> read(page, cookies) }
                }.also { Log.i(Tag, "Spotify's web player signed in (${chosen.name}, ${System.currentTimeMillis() - started} ms)") }
            } finally {
                BrowserSignIn.discardProfile(profile)
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun read(page: BrowserSignIn.HiddenPage, cookies: Cookies): Token {
        val host = URI(origin).host
        val secure = origin.startsWith("https:")
        // Spotify's cookies are for the whole of spotify.com; a stand-in gets them for its own host.
        val domain = if (host.endsWith("spotify.com")) ".spotify.com" else host
        page.send("Network.enable")
        page.send(
            "Network.setCookies",
            buildJsonObject {
                putJsonArray("cookies") {
                    for ((name, value) in listOfNotNull("sp_dc" to cookies.spDc, cookies.spKey?.let { "sp_key" to it })) {
                        addJsonObject {
                            put("name", name)
                            put("value", value)
                            put("domain", domain)
                            put("path", "/")
                            put("secure", secure)
                            put("httpOnly", true)
                        }
                    }
                }
            },
        )
        page.send("Page.enable")
        page.send("Page.addScriptToEvaluateOnNewDocument", buildJsonObject { put("source", Hook) })
        page.send("Page.navigate", buildJsonObject { put("url", "$origin/") })

        var anonymousSince = 0L
        var token: Token? = null
        var tokenAt = 0L
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            delay(300)
            val seen = page.evaluate(Probe)?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: continue
            seen["hashes"]?.let { it as? JsonObject }?.let { found ->
                val queries = found.mapNotNull { (name, hash) -> hash.text()?.let { name to it } }.toMap()
                if (queries.isNotEmpty()) seenQueries = seenQueries + queries
            }
            if (token == null) {
                val signedIn = listOfNotNull(seen["token"].text(), seen["session"].text())
                    .firstNotNullOfOrNull { parseToken(it, page.userAgent) }
                if (signedIn != null) {
                    token = signedIn
                    tokenAt = System.currentTimeMillis()
                } else if (seen["anonymous"].bool() == true || listOfNotNull(seen["session"].text()).any { isAnonymous(it) }) {
                    if (anonymousSince == 0L) anonymousSince = System.currentTimeMillis()
                    // The player starts as a guest for a moment; still a guest well after, it isn't signed in.
                    if (System.currentTimeMillis() - anonymousSince > 12_000) {
                        lastGuest = listOfNotNull(seen["guest"].text(), seen["session"].text())
                            .firstNotNullOfOrNull { parseToken(it, page.userAgent, guest = true) }
                            ?.let { guest ->
                                val client = seen["clientToken"].text()?.let(::parseClientToken)
                                guest.copy(clientToken = client?.first, clientTokenExpiresAt = client?.second ?: 0)
                            }
                        throw LoggedOutException()
                    }
                }
            }
            val found = token ?: continue
            val version = seen["config"].text()?.let { config ->
                runCatching { json.parseToJsonElement(config).jsonObject["clientVersion"].text() }.getOrNull()
            }
            val client = seen["clientToken"].text()?.let(::parseClientToken)
            // The client token comes a moment after the access token; it's worth a short wait, not a long one.
            if (client != null || System.currentTimeMillis() - tokenAt > 5_000) {
                return found.copy(
                    clientToken = client?.first,
                    clientTokenExpiresAt = client?.second ?: 0,
                    clientVersion = version,
                )
            }
        }
        token?.let { return it }
        if (anonymousSince != 0L) throw LoggedOutException()
        throw IOException("Spotify's web player didn't open. Check your connection and try again.")
    }

    /** The web player's token answer, when it's for a signed-in account and not run out. */
    internal fun parseToken(text: String, userAgent: String?, now: Long = System.currentTimeMillis(), guest: Boolean = false): Token? = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        if (obj["isAnonymous"].bool() == true && !guest) return null
        val access = obj["accessToken"].text()?.takeIf { it.isNotBlank() } ?: return null
        val expires = obj["accessTokenExpirationTimestampMs"].long() ?: return null
        if (expires <= now) return null
        Token(access, expires, obj["clientId"].text().orEmpty(), userAgent = userAgent)
    }.getOrNull()

    private fun isAnonymous(text: String): Boolean =
        runCatching { json.parseToJsonElement(text).jsonObject["isAnonymous"].bool() == true }.getOrDefault(false)

    /** clienttoken.spotify.com's answer: the token and when it runs out. */
    internal fun parseClientToken(text: String, now: Long = System.currentTimeMillis()): Pair<String, Long>? = runCatching {
        val granted = json.parseToJsonElement(text).jsonObject["granted_token"] as? JsonObject ?: return null
        val value = granted["token"].text()?.takeIf { it.isNotBlank() } ?: return null
        val seconds = granted["expires_after_seconds"].long() ?: 0
        value to now + seconds * 1000
    }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement?.text(): String? =
        this?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun kotlinx.serialization.json.JsonElement?.bool(): Boolean? =
        this?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }

    private fun kotlinx.serialization.json.JsonElement?.long(): Long? =
        this?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }

    /**
     * Put in the page before Spotify's own code runs: notes the token the player gets from
     * /api/token, the client token, and the saved queries it asks Spotify for, where [Probe] can
     * read them. The phone's WebView bridge does the same.
     */
    private val Hook = """
        (function () {
          if (window.__convxSpotify) return;
          var out = window.__convxSpotify = { token: null, anonymous: false, clientToken: null, hashes: {} };
          function parse(url) { try { return new URL(String(url), location.href); } catch (_) { return null; } }
          function noteToken(text) {
            try {
              var t = JSON.parse(text);
              if (!t || !t.accessToken) return;
              if (t.isAnonymous) { out.anonymous = true; out.guest = text; } else out.token = text;
            } catch (_) {}
          }
          function noteClientToken(text) {
            try { var t = JSON.parse(text); if (t && t.granted_token && t.granted_token.token) out.clientToken = text; } catch (_) {}
          }
          function noteQuery(u, body) {
            try {
              var name = null, hash = null;
              if (typeof body === 'string' && body.charAt(0) === '{') {
                var b = JSON.parse(body);
                name = b.operationName;
                hash = b.extensions && b.extensions.persistedQuery && b.extensions.persistedQuery.sha256Hash;
              }
              if (!name) {
                name = u.searchParams.get('operationName');
                var ext = u.searchParams.get('extensions');
                if (ext) { var e = JSON.parse(ext); hash = e.persistedQuery && e.persistedQuery.sha256Hash; }
              }
              if (name && hash) out.hashes[name] = hash;
            } catch (_) {}
          }
          function watch(url, body, onText) {
            var u = parse(url);
            if (!u) return;
            if (u.pathname === '/api/token' && u.host === location.host) onText(noteToken);
            else if (u.hostname.indexOf('clienttoken.') === 0) onText(noteClientToken);
            else if (u.pathname.indexOf('/pathfinder/') >= 0) noteQuery(u, body);
          }
          var originalFetch = window.fetch;
          window.fetch = function (input, init) {
            var result = originalFetch.apply(this, arguments);
            try {
              watch(input && input.url ? input.url : input, init && init.body, function (note) {
                result.then(function (r) { return r.clone().text(); }).then(note).catch(function () {});
              });
            } catch (_) {}
            return result;
          };
          var open = XMLHttpRequest.prototype.open, send = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function (method, url) { this.__convxUrl = url; return open.apply(this, arguments); };
          XMLHttpRequest.prototype.send = function (body) {
            var xhr = this;
            try {
              watch(xhr.__convxUrl, body, function (note) {
                xhr.addEventListener('load', function () { try { note(xhr.responseText); } catch (_) {} });
              });
            } catch (_) {}
            return send.apply(this, arguments);
          };
        })();
    """.trimIndent()

    /** What the hook noted so far, plus the page's own session and config scripts. */
    private val Probe = """
        JSON.stringify((function () {
          var o = window.__convxSpotify || {};
          var session = null, config = null;
          try { var s = document.getElementById('session'); session = s ? s.textContent : null; } catch (_) {}
          try { var c = document.getElementById('appServerConfig'); config = c ? atob(c.textContent.trim()) : null; } catch (_) {}
          return { token: o.token || null, anonymous: !!o.anonymous, guest: o.guest || null, clientToken: o.clientToken || null, hashes: o.hashes || {}, session: session, config: config };
        })())
    """.trimIndent()

    /** The login cookie a person pasted: the bare value, or "sp_dc=…" from a cookie list. */
    fun cookieFromText(text: String): Cookies? {
        val cleaned = text.trim().trim('"')
        val pairs = cleaned.split(';').map { it.trim() }.filter { '=' in it }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val spDc = pairs["sp_dc"] ?: cleaned.takeIf { '=' !in it && ';' !in it }
        val valid = spDc != null && spDc.length >= 20 && spDc.none { it.isWhitespace() || it == ';' || it == ',' }
        return if (valid) Cookies(spDc!!, pairs["sp_key"]?.takeIf { it.isNotBlank() }) else null
    }

    /** The cookies a browser sign-in brought back, when they include the login. */
    fun cookiesFrom(session: BrowserSignIn.Session): Cookies? {
        val named = session.cookies.toMap()
        val spDc = named["sp_dc"]?.takeIf { it.isNotBlank() } ?: return null
        return Cookies(spDc, named["sp_key"]?.takeIf { it.isNotBlank() })
    }
}
