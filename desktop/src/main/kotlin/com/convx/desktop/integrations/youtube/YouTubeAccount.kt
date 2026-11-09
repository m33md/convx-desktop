/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations.youtube

import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.core.Secrets
import com.convx.desktop.data.AppSettings
import com.music.innertube.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Your YouTube Music account, as the phone keeps it: the music.youtube.com cookie, the visitor
 * data and the DATASYNC_ID that picks one channel on the Google account. With it the player can
 * use the clients that need a sign-in, home and browse pages are yours ("More content"), and
 * your likes, albums, artists and playlists sync both ways ([YouTubeSync]).
 *
 * Everything is kept in [Secrets], encrypted for your Windows account, never in settings.json.
 */
object YouTubeAccount {
    private const val Tag = "YouTubeAccount"
    private const val KeyCookie = "youtube.cookie"
    private const val KeyVisitorData = "youtube.visitorData"
    private const val KeyDataSyncId = "youtube.dataSyncId"
    private const val KeyProfile = "youtube.profile"
    private const val KeyChannels = "youtube.channels"

    /** Who you are on YouTube Music; the phone's AccountName/Email/ChannelHandle keys. */
    @Serializable
    data class Profile(val name: String, val email: String? = null, val handle: String? = null, val thumbnail: String? = null)

    /** One channel on the signed-in Google account; the phone's SavedAccount. */
    @Serializable
    data class Channel(val dataSyncId: String, val name: String, val handle: String? = null, val thumbnail: String? = null)

    sealed interface State {
        data object SignedOut : State
        data object WaitingForBrowser : State
        data object Checking : State
        data class ChoosingChannel(val channels: List<Channel>) : State
        data class SignedIn(val profile: Profile, val channels: List<Channel>, val dataSyncId: String?) : State
        data class Failed(val message: String) : State
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<State>(State.SignedOut)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var pending: Pending? = null

    private class Pending(val cookie: String, val visitorData: String?)

    val signedIn: Boolean get() = Secrets[KeyCookie] != null

    /**
     * Puts the saved account into the YouTube client before the first request, and moves a
     * cookie from an older settings.json into [Secrets]. Returns whether you're signed in.
     */
    fun restore(): Boolean {
        AppSettings.value.cookie?.takeIf { it.isNotBlank() }?.let { old ->
            if (Secrets[KeyCookie] == null) Secrets[KeyCookie] = old
            AppSettings.update { it.copy(cookie = null) }
        }
        YouTube.useLoginForBrowse = AppSettings.value.youtubeMoreContent
        val cookie = Secrets[KeyCookie]
        if (cookie == null) {
            _state.value = State.SignedOut
            return false
        }
        YouTube.cookie = cookie
        YouTube.dataSyncId = Secrets[KeyDataSyncId]
        Secrets[KeyVisitorData]?.let { YouTube.visitorData = it }
        _state.value = State.SignedIn(profile(), channels(), Secrets[KeyDataSyncId])
        return true
    }

    /** True when the account brought its own visitor data, so the guest one mustn't replace it. */
    val hasVisitorData: Boolean get() = signedIn && Secrets[KeyVisitorData] != null

    fun setMoreContent(on: Boolean) {
        AppSettings.update { it.copy(youtubeMoreContent = on) }
        YouTube.useLoginForBrowse = on
        com.convx.desktop.ui.screens.PageCache.clear()
    }

    /** The phone's web login, in a real browser window (see [BrowserSignIn]). */
    fun signIn() {
        job?.cancel()
        _state.value = State.WaitingForBrowser
        job = AppScope.launch {
            runCatching { BrowserSignIn.run() }
                .onSuccess { finish(it.cookie, it.visitorData, it.dataSyncId, offerChannels = true) }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    Log.w(Tag, "Browser sign-in failed", error)
                    _state.value = if (error is BrowserSignIn.ClosedException) currentOrSignedOut() else State.Failed(error.message ?: "Sign-in didn't finish")
                }
        }
    }

    fun cancelSignIn() {
        job?.cancel()
        job = null
        if (pending != null) {
            // The unchosen sign-in is already in the client; put the saved account (or none) back.
            pending = null
            if (!restore()) {
                YouTube.cookie = null
                YouTube.dataSyncId = null
                AppSettings.value.visitorData?.let { YouTube.visitorData = it }
            }
        }
        _state.value = currentOrSignedOut()
    }

    /** The phone's token format, for "Login with token": copy it from the phone, paste it here. */
    fun token(): String = """
        ***INNERTUBE COOKIE*** =${Secrets[KeyCookie].orEmpty()}
        ***VISITOR DATA*** =${Secrets[KeyVisitorData].orEmpty()}
        ***DATASYNC ID*** =${Secrets[KeyDataSyncId].orEmpty()}
        ***ACCOUNT NAME*** =${profile().name.takeIf { signedIn }.orEmpty()}
        ***ACCOUNT EMAIL*** =${profile().email.takeIf { signedIn }.orEmpty()}
        ***ACCOUNT CHANNEL HANDLE*** =${profile().handle.takeIf { signedIn }.orEmpty()}
    """.trimIndent()

    /**
     * The cookie from a pasted token, or null when it has no SAPISID (the phone's own check). The
     * phone's token works, and so does a browser's own Cookie header for music.youtube.com, copied
     * from its developer tools (with or without "cookie:" in front, or among the other headers).
     */
    fun cookieOf(token: String): String? {
        val cookie = field(token, "***INNERTUBE COOKIE*** =")
            ?: token.lines().firstOrNull { it.trim().startsWith("cookie:", ignoreCase = true) }?.trim()?.substringAfter(':')?.trim()
            ?: token.trim().takeIf { '\n' !in it && '=' in it }
        return cookie?.takeIf { text -> text.split(';').any { it.trim().substringBefore('=') == "SAPISID" } }
    }

    fun signInWithToken(token: String) {
        val cookie = cookieOf(token) ?: return
        job?.cancel()
        job = AppScope.launch {
            finish(cookie, field(token, "***VISITOR DATA*** ="), field(token, "***DATASYNC ID*** ="), offerChannels = false)
        }
    }

    private fun field(token: String, prefix: String) =
        token.lines().firstOrNull { it.trim().startsWith(prefix) }?.trim()?.substringAfter(prefix)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The phone's LoginScreen validation: the channel list must load (an empty one is fine), and a
     * web sign-in on an account with several channels asks which one to use. A token names its own.
     */
    private suspend fun finish(cookie: String, visitorData: String?, dataSyncId: String?, offerChannels: Boolean) = withContext(Dispatchers.IO) {
        _state.value = State.Checking
        val before = Triple(YouTube.cookie, YouTube.visitorData, YouTube.dataSyncId)
        YouTube.cookie = cookie
        visitorData?.let { YouTube.visitorData = it }
        YouTube.dataSyncId = dataSyncId
        YouTube.getAccountChannels()
            .mapCatching { raw -> raw.mapNotNull { c -> c.dataSyncId?.let { Channel(it, c.name, c.channelHandle, c.thumbnailUrl) } } }
            .onSuccess { channels ->
                if (channels.size > 1 && (offerChannels || dataSyncId == null)) {
                    pending = Pending(cookie, visitorData)
                    _state.value = State.ChoosingChannel(channels)
                } else {
                    keep(cookie, visitorData, dataSyncId ?: channels.firstOrNull()?.dataSyncId, channels)
                }
            }
            .onFailure { error ->
                Log.w(Tag, "YouTube didn't accept the sign-in", error)
                YouTube.cookie = before.first
                before.second?.let { YouTube.visitorData = it }
                YouTube.dataSyncId = before.third
                _state.value = State.Failed("YouTube Music didn't accept that sign-in. Try again, or log in with a token from the phone.")
            }
    }

    /** Picks a channel after a sign-in that found several (the phone's ChannelPickerScreen). */
    fun chooseChannel(channel: Channel) {
        val p = pending ?: return
        val channels = (state.value as? State.ChoosingChannel)?.channels ?: listOf(channel)
        pending = null
        job = AppScope.launch(Dispatchers.IO) { keep(p.cookie, p.visitorData, channel.dataSyncId, channels) }
    }

    /** Switches to another channel on the same Google account; no new sign-in needed. */
    fun switchChannel(channel: Channel) {
        if (!signedIn) return
        job = AppScope.launch(Dispatchers.IO) {
            keep(Secrets[KeyCookie] ?: return@launch, Secrets[KeyVisitorData], channel.dataSyncId, channels())
        }
    }

    private suspend fun keep(cookie: String, visitorData: String?, dataSyncId: String?, channels: List<Channel>) {
        YouTube.cookie = cookie
        visitorData?.let { YouTube.visitorData = it }
        YouTube.dataSyncId = dataSyncId
        // The profile is read after the channel is set, as the phone does.
        val info = YouTube.accountInfo().getOrNull()
        val channelId = dataSyncId?.substringBefore("||")
        val chosen = channels.firstOrNull { it.dataSyncId.substringBefore("||") == channelId }
        val profile = Profile(
            name = info?.name ?: chosen?.name ?: "YouTube Music",
            email = info?.email,
            handle = info?.channelHandle ?: chosen?.handle,
            thumbnail = info?.thumbnailUrl ?: chosen?.thumbnail,
        )
        val switched = Secrets[KeyDataSyncId]?.substringBefore("||") != channelId || Secrets[KeyCookie] == null
        Secrets[KeyCookie] = cookie
        Secrets[KeyVisitorData] = visitorData ?: Secrets[KeyVisitorData]
        Secrets[KeyDataSyncId] = dataSyncId
        Secrets[KeyProfile] = json.encodeToString(Profile.serializer(), profile)
        Secrets[KeyChannels] = json.encodeToString(ListSerializer(Channel.serializer()), channels)
        _state.value = State.SignedIn(profile, channels, dataSyncId)
        Log.i(Tag, "Signed in to YouTube Music as ${profile.name} (${channels.size} channels)")
        com.convx.desktop.ui.screens.PageCache.clear()
        if (switched) YouTubeSync.forgetKnown()
        YouTubeSync.syncNow()
    }

    /** Refreshes the channel list and profile, as the phone does when its account page opens. */
    fun refresh() {
        if (!signedIn || job?.isActive == true) return
        AppScope.launch(Dispatchers.IO) {
            val channels = YouTube.getAccountChannels().getOrNull()
                ?.mapNotNull { c -> c.dataSyncId?.let { Channel(it, c.name, c.channelHandle, c.thumbnailUrl) } }
                ?.takeIf { it.isNotEmpty() }
            val info = YouTube.accountInfo().getOrNull()
            if (!signedIn) return@launch
            channels?.let { Secrets[KeyChannels] = json.encodeToString(ListSerializer(Channel.serializer()), it) }
            info?.let {
                Secrets[KeyProfile] = json.encodeToString(Profile.serializer(), Profile(it.name, it.email, it.channelHandle, it.thumbnailUrl))
            }
            _state.value = State.SignedIn(profile(), channels(), Secrets[KeyDataSyncId])
        }
    }

    /** Logs out; [clearData] also removes what the account sync brought in (the phone's "Clear Data"). */
    fun signOut(clearData: Boolean) {
        job?.cancel()
        pending = null
        Secrets[KeyCookie] = null
        Secrets[KeyVisitorData] = null
        Secrets[KeyDataSyncId] = null
        Secrets[KeyProfile] = null
        Secrets[KeyChannels] = null
        YouTube.cookie = null
        YouTube.dataSyncId = null
        AppSettings.value.visitorData?.let { YouTube.visitorData = it }
        if (clearData) YouTubeSync.clearSynced() else YouTubeSync.forgetKnown()
        com.convx.desktop.ui.screens.PageCache.clear()
        _state.value = State.SignedOut
    }

    private fun currentOrSignedOut(): State =
        if (signedIn) State.SignedIn(profile(), channels(), Secrets[KeyDataSyncId]) else State.SignedOut

    private fun profile(): Profile =
        Secrets[KeyProfile]?.let { runCatching { json.decodeFromString(Profile.serializer(), it) }.getOrNull() } ?: Profile("YouTube Music")

    private fun channels(): List<Channel> =
        Secrets[KeyChannels]?.let { runCatching { json.decodeFromString(ListSerializer(Channel.serializer()), it) }.getOrNull() }.orEmpty()
}
