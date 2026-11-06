/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations.party

import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.ArtistRef
import com.convx.desktop.data.Song
import com.convx.desktop.integrations.spotify.SpotifyMatcher
import com.convx.desktop.player.LocalMusic
import com.convx.desktop.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import kotlin.math.abs

/**
 * The phone's Party (jam/JamManager.kt): a code on the phone's party server that everyone joins,
 * and every device hears the same song at the same moment, kept in step by the server's clock.
 * It talks to the same server the same way, so this PC and phones can share a party.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object Party {
    private const val Tag = "Party"
    const val DefaultServer = "https://bitchord-listen-together.onrender.com"
    const val HostOnlyNotice = "Only the host controls playback in this party"

    /** Position error that is corrected with a seek; smaller drift is left alone. */
    private const val DriftMs = 450L
    private const val PingMs = 4_000L
    private const val QueueSendDebounceMs = 600L
    private const val MaxQueue = 26
    private const val ReconnectGiveUpMs = 60_000L

    /** What this PC knows about the party it's in. */
    data class Session(
        val code: String,
        val you: PartyMember,
        val members: List<PartyMember> = emptyList(),
        val maxMembers: Int = 5,
        val hostOnlyControl: Boolean = false,
        val playback: PartyPlayback = PartyPlayback(),
        val queue: PartyQueue = PartyQueue(),
        val connected: Boolean = false,
    ) {
        /** The server refuses controls from everybody but the host when [hostOnlyControl] is on. */
        val canControl: Boolean get() = !hostOnlyControl || you.isHost
    }

    /** A party code, and the server it's on when it came from an invite link. */
    data class Invite(val code: String, val server: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val clock = PartyClock()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** The last thing worth saying on the party page: a refusal, a lost connection, a removal. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Things to say wherever the listener is: who joined, a removal, the party ending. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /**
     * The party server: Convx's own, built in and never shown. Only an
     * invite link that names another server moves this PC there, for that party, and nothing is saved.
     */
    var serverUrl: String = DefaultServer
        private set

    /**
     * The name others see. The phone offers its model name; a PC's own name is usually something
     * like DESKTOP-7H2K9QF, so this offers the Windows user name instead.
     */
    var displayName: String
        get() = AppSettings.value.partyName.ifBlank {
            System.getProperty("user.name")?.trim()?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
                ?: System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
                ?: "Convx PC"
        }
        set(value) {
            AppSettings.update { it.copy(partyName = value.trim()) }
        }

    /** This PC's id to the server, made once. */
    private val identity: String
        get() = AppSettings.value.partyId.ifBlank {
            UUID.randomUUID().toString().also { id -> AppSettings.update { it.copy(partyId = id) } }
        }

    private var token: String? = null
    private var socket: WebSocket? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var startJob: Job? = null
    private var leaving = false
    private var lastSeq = -1L
    private var started = false
    private var welcomed = false

    /** Ids of the queue as last sent or applied, so the party's own queue isn't sent back. */
    private var queueSignature: List<String> = emptyList()

    /** A song this PC started for the party; once it has loaded, the party is told where it really is. */
    private var ownTrackId: String? = null

    // ---- lifecycle ---------------------------------------------------------------------------

    /** Listens to the player for the whole session; a party only acts while there is one. */
    fun start() {
        if (started) return
        started = true
        PlayerController.controlLock = {
            _session.value?.takeIf { it.connected && !it.canControl }?.let { HostOnlyNotice }
        }
        scope.launch { PlayerController.changes.collect { onLocal(it) } }
        scope.launch {
            PlayerController.state.map { s -> s.queue.map { it.id } }.distinctUntilChanged().collectLatest { ids ->
                delay(QueueSendDebounceMs)
                sendQueue(ids)
            }
        }
        scope.launch {
            PlayerController.state.map { Triple(it.current?.id, it.isLoading, it.error != null) }.distinctUntilChanged().collect { (id, loading, failed) ->
                if (id != null && !loading && !failed) onLoaded(id)
            }
        }
    }

    /** Wakes a sleeping server while the listener types. */
    fun warm() {
        scope.launch { PartyClient.warm(serverUrl) }
    }

    fun create(name: String) {
        scope.launch {
            leaveCurrent()
            serverUrl = DefaultServer
            enter { PartyClient.create(serverUrl, request(name)) }
        }
    }

    /** Joins by a code, or by an invite link the phone shared (convx://party/CODE?server=…). */
    fun join(codeOrLink: String, name: String) {
        val invite = parseInvite(codeOrLink) ?: run {
            _message.value = "That isn't a party code"
            return
        }
        scope.launch {
            leaveCurrent()
            serverUrl = invite.server?.trim()?.trimEnd('/')?.ifBlank { null } ?: DefaultServer
            enter { PartyClient.join(serverUrl, invite.code, request(name)) }
        }
    }

    fun leave() {
        scope.launch { leaveCurrent() }
    }

    private fun leaveCurrent() {
        val current = _session.value ?: return
        val leavingToken = token
        val server = serverUrl
        leaving = true
        teardown()
        _session.value = null
        if (leavingToken != null) AppScope.launch { PartyClient.leave(server, current.code, leavingToken) }
    }

    /** Leaves on quit, so the party doesn't keep a seat for a PC that's gone. */
    fun shutdown() {
        val current = _session.value ?: return
        val leavingToken = token ?: return
        leaving = true
        socket?.close(1000, null)
        PartyClient.leaveNow(serverUrl, current.code, leavingToken)
    }

    fun setHostOnlyControl(enabled: Boolean) {
        scope.launch { control("setHostOnlyControl") { put("enabled", enabled) } }
    }

    fun clearMessage() {
        _message.value = null
    }

    /** The link that opens this party in Convx on a phone. */
    fun inviteLink(code: String): String =
        if (serverUrl == DefaultServer) "convx://party/$code" else "convx://party/$code?server=" + URLEncoder.encode(serverUrl, "UTF-8")

    /** A typed code, or the phone's invite link; null when it's neither. */
    fun parseInvite(text: String): Invite? {
        val trimmed = text.trim()
        if (trimmed.startsWith("convx://", ignoreCase = true)) {
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            if (!uri.host.equals("party", ignoreCase = true)) return null
            val code = normalise(uri.path.orEmpty().trim('/'))
            val server = uri.rawQuery.orEmpty().split('&')
                .firstOrNull { it.startsWith("server=") }
                ?.removePrefix("server=")
                ?.let { URLDecoder.decode(it, "UTF-8") }
                ?.takeIf { it.startsWith("http") }
            return code.takeIf { it.isNotEmpty() }?.let { Invite(it, server) }
        }
        return normalise(trimmed).takeIf { it.length in 4..12 && it.all(Char::isLetterOrDigit) }?.let { Invite(it, null) }
    }

    private suspend fun enter(call: suspend () -> PartyMembership) {
        if (_busy.value) return
        _busy.value = true
        _message.value = null
        val waking = scope.launch {
            delay(3_000)
            _message.value = "Waking the server, this can take up to 30 seconds…"
        }
        try {
            val membership = call()
            teardown()
            leaving = false
            lastSeq = -1
            welcomed = false
            ownTrackId = null
            queueSignature = emptyList()
            token = membership.token
            val party = membership.party
            _session.value = Session(
                code = membership.code,
                you = membership.you,
                members = party.members,
                maxMembers = party.maxMembers,
                hostOnlyControl = party.hostOnlyControl,
                playback = party.playback,
                queue = party.queue,
            )
            Log.i(Tag, "In party ${membership.code} as ${if (membership.you.isHost) "host" else "guest"}")
            connect(membership.code, membership.token)
            applyRemote(party.playback)
        } catch (e: PartyException) {
            _message.value = e.message
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(Tag, "Couldn't enter the party", e)
            _message.value = "The party server sent something Convx couldn't read"
        } finally {
            waking.cancel()
            if (_session.value != null) _message.value = null
            _busy.value = false
        }
    }

    private fun request(name: String): JoinRequest {
        val shown = name.trim().ifBlank { displayName }.take(80)
        displayName = shown
        return JoinRequest(userId = identity, deviceId = identity, displayName = shown)
    }

    /** Codes are typed by hand: the server normalises case and look-alikes, this only strips spaces. */
    private fun normalise(code: String) = code.filterNot { it.isWhitespace() || it == '-' }.uppercase()

    // ---- socket ------------------------------------------------------------------------------

    private fun connect(code: String, token: String) {
        socket = PartyClient.open(serverUrl, code, token, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    if (webSocket !== socket) return@launch
                    update { it.copy(connected = true) }
                    startPing(webSocket)
                    webSocket.send("""{"type":"sync"}""")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    if (webSocket !== socket) return@launch
                    runCatching { onFrame(text) }.onFailure { Log.w(Tag, "Bad frame: ${it.message}") }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    // A refused handshake means the seat is gone; retrying won't bring it back.
                    if (response?.code == 401 || response?.code == 403 || response?.code == 404) {
                        if (webSocket !== socket) return@launch
                        ended("The party has ended")
                    } else {
                        lost(webSocket)
                    }
                }
            }
        })
    }

    private fun lost(webSocket: WebSocket) {
        scope.launch {
            if (webSocket !== socket || leaving) return@launch
            pingJob?.cancel()
            update { it.copy(connected = false) }
            reconnect()
        }
    }

    private fun reconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val started = System.currentTimeMillis()
            var wait = 1_000L
            while (!leaving && _session.value != null) {
                if (System.currentTimeMillis() - started > ReconnectGiveUpMs) {
                    ended("Lost connection to the party")
                    return@launch
                }
                delay(wait)
                wait = (wait * 2).coerceAtMost(8_000L)
                val session = _session.value ?: return@launch
                val current = token ?: return@launch
                socket?.cancel()
                connect(session.code, current)
                delay(3_000)
                if (_session.value?.connected == true) return@launch
            }
        }
    }

    private fun startPing(webSocket: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                webSocket.send("""{"type":"ping","clientMs":${System.currentTimeMillis()}}""")
                delay(PingMs)
            }
        }
    }

    private fun teardown() {
        pingJob?.cancel()
        reconnectJob?.cancel()
        startJob?.cancel()
        socket?.close(1000, null)
        socket = null
        token = null
        clock.reset()
    }

    private fun ended(message: String) {
        teardown()
        _session.value = null
        _message.value = message
        _notices.tryEmit(message)
    }

    // ---- frames in ---------------------------------------------------------------------------

    private fun onFrame(text: String) {
        val json = PartyClient.json
        val frame = json.parseToJsonElement(text).jsonObject
        when (frame["type"]?.jsonPrimitive?.contentOrNull) {
            "welcome" -> frame["party"]?.let { party ->
                val snapshot = json.decodeFromJsonElement(PartySnapshot.serializer(), party)
                frame["you"]?.let { you -> update { it.copy(you = json.decodeFromJsonElement(PartyMember.serializer(), you)) } }
                update {
                    it.copy(
                        members = snapshot.members,
                        maxMembers = snapshot.maxMembers,
                        hostOnlyControl = snapshot.hostOnlyControl,
                        queue = snapshot.queue,
                    )
                }
                onState(snapshot.playback)
                if (!welcomed) {
                    welcomed = true
                    shareWhatsPlaying(snapshot.playback)
                }
            }
            "state" -> frame["playback"]?.let { onState(json.decodeFromJsonElement(PartyPlayback.serializer(), it)) }
            "queue" -> frame["queue"]?.let { queue ->
                val incoming = json.decodeFromJsonElement(PartyQueue.serializer(), queue)
                update { it.copy(queue = incoming) }
                applyQueue(incoming)
            }
            "members" -> {
                val before = _session.value?.members.orEmpty()
                update { current ->
                    current.copy(
                        members = frame["members"]?.let { json.decodeFromJsonElement(ListSerializer(PartyMember.serializer()), it) } ?: current.members,
                        maxMembers = frame["maxMembers"]?.jsonPrimitive?.intOrNull ?: current.maxMembers,
                        hostOnlyControl = frame["hostOnlyControl"]?.jsonPrimitive?.booleanOrNull ?: current.hostOnlyControl,
                    ).let { next -> next.copy(you = next.members.firstOrNull { it.memberId == current.you.memberId } ?: current.you) }
                }
                val session = _session.value ?: return
                val known = before.mapTo(HashSet()) { it.memberId }
                session.members.filter { it.memberId !in known && it.memberId != session.you.memberId }.forEach {
                    _notices.tryEmit("${it.displayName.ifBlank { "Someone" }} joined the party")
                }
            }
            "pong" -> {
                val sent = frame["clientMs"]?.jsonPrimitive?.longOrNull
                val server = frame["serverMs"]?.jsonPrimitive?.longOrNull
                if (sent != null && server != null) clock.observe(sent, server, System.currentTimeMillis())
            }
            "error" -> _message.value = frame["message"]?.jsonPrimitive?.contentOrNull ?: frame["error"]?.jsonPrimitive?.contentOrNull
            "bye" -> {
                val kicked = frame["reason"]?.jsonPrimitive?.contentOrNull == "kicked"
                ended(if (kicked) "You were removed from the party" else "The party has ended")
            }
        }
    }

    private fun update(change: (Session) -> Session) {
        _session.value = _session.value?.let(change)
    }

    private fun onState(playback: PartyPlayback) {
        // Every change bumps seq; an older or equal one is a duplicate or a straggler.
        if (playback.seq <= lastSeq) return
        lastSeq = playback.seq
        update { it.copy(playback = playback) }
        applyRemote(playback)
        val session = _session.value ?: return
        if (playback.queueSeq != session.queue.seq) socket?.send("""{"type":"syncQueue"}""")
    }

    // ---- party -> this PC --------------------------------------------------------------------

    private fun applyRemote(playback: PartyPlayback) {
        val track = playback.track ?: return
        val serverNow = clock.serverNow(System.currentTimeMillis())
        val target = PartySync.positionAt(playback, serverNow)
        val s = PlayerController.state.value
        startJob?.cancel()
        if (s.current?.id != track.videoId) {
            startTrack(track, target, playback.isPlaying)
            return
        }
        // Still loading: it's applied again once loaded (onLoaded), from the newest state.
        if (s.isLoading || s.error != null) return
        val seek = target.takeIf { abs(PlayerController.livePositionMs() - it) > DriftMs }
        if (playback.isPlaying) {
            if (seek != null) PlayerController.partyPlayback(seek, null)
            if (!s.isPlaying) {
                // A resume is anchored slightly ahead, so every device starts at the same moment.
                val wait = PartySync.startDelayMs(playback, serverNow)
                startJob = scope.launch {
                    delay(wait)
                    PlayerController.partyPlayback(null, true)
                }
            }
        } else {
            PlayerController.partyPlayback(seek, false)
        }
    }

    private fun startTrack(track: PartyTrack, position: Long, playing: Boolean) {
        val queue = _session.value?.queue
        val inQueue = queue != null && queue.index in queue.items.indices && queue.items[queue.index].videoId == track.videoId
        val items = if (inQueue) queue!!.items else listOf(track)
        val start = if (inQueue) queue!!.index else 0
        queueSignature = items.map { it.videoId }
        PlayerController.partyTrack(items.map(::toSong), start, position, playing, "Party")
    }

    /** A queue change that leaves the playing song alone only swaps what comes after it. */
    private fun applyQueue(queue: PartyQueue) {
        if (queue.items.isEmpty() || queue.index !in queue.items.indices) return
        val s = PlayerController.state.value
        val current = s.current ?: return
        if (current.id != queue.items[queue.index].videoId) return
        val upcoming = queue.items.drop(queue.index + 1)
        val upcomingIds = upcoming.map { it.videoId }
        val localUpcoming = s.queue.drop(s.index + 1).map { it.id }
        if (localUpcoming == upcomingIds) return
        // The party keeps 25 songs ahead; a longer queue here that starts the same is this PC's own.
        if (upcomingIds.size >= MaxQueue - 1 && localUpcoming.take(upcomingIds.size) == upcomingIds) return
        queueSignature = s.queue.take(s.index + 1).map { it.id } + upcomingIds
        PlayerController.partyUpcoming(upcoming.map(::toSong))
    }

    /** Called when a song has loaded: the party's position is applied again, now that it can be. */
    private fun onLoaded(id: String) {
        val session = _session.value ?: return
        if (id == ownTrackId) {
            // Started here: re-anchor the party to where this PC really is, instead of jumping ahead.
            ownTrackId = null
            if (canSend()) {
                val playing = PlayerController.state.value.isPlaying
                control(if (playing) "play" else "pause") { put("positionMs", PlayerController.livePositionMs()) }
            }
            return
        }
        if (session.playback.track?.videoId == id) applyRemote(session.playback)
    }

    // ---- this PC -> party --------------------------------------------------------------------

    private fun onLocal(change: PlayerController.Change) {
        val session = _session.value ?: return
        if (!canSend()) return
        when (change) {
            is PlayerController.Change.Playing ->
                control(if (change.playing) "play" else "pause") { put("positionMs", change.positionMs) }
            is PlayerController.Change.Seeked ->
                control("seek") { put("positionMs", change.positionMs) }
            is PlayerController.Change.Track -> {
                if (session.playback.track?.videoId == change.song.id) return
                val track = toTrack(change.song) ?: run {
                    _message.value = "Songs from files on this PC can't be shared with the party"
                    _notices.tryEmit("Files on this PC can't be shared with the party")
                    return
                }
                ownTrackId = change.song.id
                control("setTrack") {
                    put("track", PartyClient.json.encodeToJsonElement(PartyTrack.serializer(), track))
                    put("positionMs", 0L)
                    put("isPlaying", change.playing)
                }
            }
        }
    }

    /** The host of a new party shares the song already playing, so guests hear it straight away. */
    private fun shareWhatsPlaying(playback: PartyPlayback) {
        val session = _session.value ?: return
        if (!session.you.isHost || playback.track != null || !canSend()) return
        val s = PlayerController.state.value
        val song = s.current ?: return
        val track = toTrack(song) ?: return
        control("setTrack") {
            put("track", PartyClient.json.encodeToJsonElement(PartyTrack.serializer(), track))
            put("positionMs", PlayerController.livePositionMs())
            put("isPlaying", s.isPlaying)
        }
        queueSignature = emptyList()
        sendQueue(s.queue.map { it.id })
    }

    private fun sendQueue(ids: List<String>) {
        if (!canSend() || ids.isEmpty() || ids == queueSignature) return
        queueSignature = ids
        val s = PlayerController.state.value
        // The server keeps the playing song and up to 25 ahead; history stays on each device.
        val window = s.queue.drop(s.index.coerceAtLeast(0)).take(MaxQueue).mapNotNull(::toTrack)
        if (window.isEmpty()) return
        control("setQueue") {
            put("queue", PartyClient.json.encodeToJsonElement(ListSerializer(PartyTrack.serializer()), window))
            put("queueIndex", 0)
        }
    }

    private fun canSend(): Boolean = _session.value?.let { it.connected && it.canControl } == true

    private fun control(action: String, build: JsonObjectBuilder.() -> Unit) {
        val webSocket = socket ?: return
        val frame = buildJsonObject {
            put("type", "control")
            put("action", action)
            build()
        }
        PartyClient.send(webSocket, frame)
    }

    // ---- conversions -------------------------------------------------------------------------

    /** Null for a song the other devices can't play: a file on this PC, or a Spotify song not matched yet. */
    private fun toTrack(song: Song): PartyTrack? {
        if (LocalMusic.isLocal(song.id) || SpotifyMatcher.isSpotify(song)) return null
        return PartyTrack(
            videoId = song.id,
            title = song.title,
            artist = song.artistText,
            thumbnailUrl = song.thumbnail,
            durationMs = song.duration?.takeIf { it > 0 }?.times(1000L),
        )
    }

    private fun toSong(track: PartyTrack) = Song(
        id = track.videoId,
        title = track.title,
        artists = listOf(ArtistRef(null, track.artist)),
        duration = track.durationMs?.div(1000)?.toInt()?.takeIf { it > 0 },
        thumbnail = track.thumbnailUrl,
    )
}
