/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations

import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.Settings
import com.convx.desktop.data.Song
import com.convx.desktop.player.LocalMusic
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.artworkUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.util.UUID
import kotlin.math.abs

/**
 * "Listening to" on Discord, as the phone shows it: the song, its artists, the cover, a time
 * bar, and the phone's two buttons, under the phone's own Discord application so it reads
 * "Convx" there too.
 *
 * The phone logs into Discord with a user token because Android has no Discord client to talk
 * to. A PC does: this talks to the Discord app already running on it, through its local IPC
 * pipe, so there is nothing to log into and no token to keep. Like the phone, the status goes
 * away when the music pauses.
 */
object DiscordPresence {
    private const val Tag = "Discord"

    /**
     * Convx's own Discord application, registered under the name "Convx", which is the name Discord
     * shows: "Listening to Convx". Settings can point at another application instead.
     */
    private const val ApplicationId = "1572329076000297041"

    /** The application the status is shown under: one set in Settings, else Convx's. */
    fun applicationId(settings: Settings = AppSettings.value): String =
        settings.discordApplicationId.trim().takeIf { it.matches(Regex("\\d{17,20}")) } ?: ApplicationId

    /** Whether the status goes under an application set in Settings rather than Convx's own. */
    fun usesOwnApplication(settings: Settings = AppSettings.value) = applicationId(settings) != ApplicationId
    private const val ProjectUrl = "https://github.com/cosmictaserdev-creator/Convx"

    enum class Status { Off, Connecting, Connected, NotRunning }

    data class User(val id: String, val name: String, val avatar: String?)

    private val _status = MutableStateFlow(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(1)
    private var job: Job? = null
    private var pipe: Pipe? = null

    /** What Discord is showing now, so an unchanged state isn't sent again. */
    /** [link] is where clicking the song goes: its Spotify page, or Spotify's search for it until that's found. */
    private data class Shown(val songId: String?, val startMs: Long, val look: Look?, val link: String? = null, val cover: String? = null)

    /** The parts of the status Settings can change. */
    data class Look(
        val useDetails: Boolean,
        val type: String,
        val button1: String?,
        val button2: String?,
    ) {
        /** Discord's activity type: Listening shows the time bar; Playing, Watching and Competing change the verb. */
        val typeCode: Int get() = when (type) {
            "playing" -> 0
            "watching" -> 3
            "competing" -> 5
            else -> 2
        }

        companion object {
            // It opens the song in Spotify, as clicking the song's name does.
            const val DefaultButton1 = "Listen on Spotify"
            const val DefaultButton2 = "Visit Convx"

            fun of(settings: Settings): Look {
                val advanced = settings.discordAdvanced
                return Look(
                    useDetails = settings.discordUseDetails,
                    type = if (advanced) settings.discordActivityType else "listening",
                    button1 = if (!advanced) DefaultButton1 else settings.discordButton1Text.ifBlank { DefaultButton1 }.takeIf { settings.discordButton1 },
                    button2 = if (!advanced) DefaultButton2 else settings.discordButton2Text.ifBlank { DefaultButton2 }.takeIf { settings.discordButton2 },
                )
            }
        }
    }

    /** The phone's button variables (DiscordRPC.resolveVariables). */
    fun resolveVariables(text: String, song: Song): String = text
        .replace("{song_name}", song.title)
        .replace("{artist_name}", song.artistText)
        .replace("{album_name}", song.album?.name.orEmpty())

    private var shown: Shown? = null
    private var lastSendAt = 0L
    private var nextConnectAt = 0L

    /**
     * How much of the newer status this Discord build turned down: 1 leaves out the song's link,
     * 2 also which line the member list shows. Each refusal steps down once; a new connection tries all again.
     */
    private var trimLevel = 0
    private var connectedApplication: String? = null

    /** Starts following the player. The setting decides whether anything is shown. */
    fun start() {
        if (job != null) return
        job = AppScope.launch(io) {
            while (isActive) {
                runCatching { tick() }.onFailure {
                    Log.w(Tag, "Presence update failed", it)
                    disconnect()
                }
                delay(1000)
            }
        }
    }

    /** Ask now rather than at the next retry, for the Connect button. */
    fun reconnectNow() {
        nextConnectAt = 0L
    }

    fun shutdown() {
        job?.cancel()
        job = null
        runCatching { pipe?.let { setActivity(it, null) } }
        disconnect()
    }

    private fun tick() {
        val settings = AppSettings.value
        if (!settings.discordPresence) {
            if (pipe != null) {
                runCatching { pipe?.let { setActivity(it, null) } }
                disconnect()
            }
            _status.value = Status.Off
            return
        }

        val now = System.currentTimeMillis()
        // A different application in Settings needs a new handshake.
        if (pipe != null && connectedApplication != applicationId(settings)) {
            runCatching { pipe?.let { setActivity(it, null) } }
            disconnect()
            nextConnectAt = 0L
        }
        val connected = pipe ?: run {
            if (now < nextConnectAt) return
            _status.value = Status.Connecting
            val opened = connect()
            if (opened == null) {
                _status.value = Status.NotRunning
                // Discord may start later; look again now and then, not every second.
                nextConnectAt = now + 15_000
                return
            }
            opened
        }
        // Answers Discord sends on its own (none expected) must not pile up in the pipe.
        connected.drain()

        val player = PlayerController.state.value
        val song = player.current
        val want = if (song != null && player.isPlaying && !player.isLoading) {
            // At another tempo the song takes longer or shorter in real time, as the phone tells Discord.
            val position = (PlayerController.position.value / player.tempo).toLong()
            com.convx.desktop.integrations.spotify.SpotifyLinks.lookUp(song)
            Shown(song.id, now - position, Look.of(settings), com.convx.desktop.integrations.spotify.SpotifyLinks.now(song), cover(song))
        } else {
            null
        }
        val same = when {
            want == null -> shown?.songId == null && shown != null
            // A local file's cover found on the web a moment later is sent too.
            else -> shown?.let {
                it.songId == want.songId && it.look == want.look && it.link == want.link && it.cover == want.cover && abs(it.startMs - want.startMs) < 2_500
            } == true
        }
        if (same) return
        // Discord takes five updates in twenty seconds; space them so a burst of skips settles.
        if (now - lastSendAt < 4_000) return

        val activity = if (want?.look != null && song != null) {
            val durationMs = player.durationMs.takeIf { it > 0 } ?: ((song.duration ?: 0) * 1000L)
            activity(song, want.startMs, (durationMs / player.tempo).toLong(), want.look, want.link, want.cover)
        } else {
            null
        }
        lastSendAt = now
        // Turned down once, it is tried again plainly at the next tick; twice, it waits for a change.
        if (setActivity(connected, activity)) shown = want ?: Shown(null, 0, null)
    }

    private fun activity(song: Song, startMs: Long, durationMs: Long, look: Look, link: String?, cover: String?): JsonObject = buildJsonObject {
        // Listening unless advanced mode picked another verb; older Discord builds only know Listening and Playing.
        if (trimLevel < 2 || look.typeCode == 0) put("type", look.typeCode) else put("type", 2)
        put("details", fit(song.title))
        put("state", fit(song.artistText.ifBlank { "Unknown artist" }))
        // Clicking the song's name opens it in Spotify (the app, if it's installed), files on this PC included.
        if (trimLevel < 1 && link != null) put("details_url", link)
        // Which line the member list shows after "Listening to": the artists or the song, not the application's name.
        if (trimLevel < 2) put("status_display_type", if (look.useDetails) 2 else 1)
        putJsonObject("timestamps") {
            put("start", startMs)
            if (durationMs > 0) put("end", startMs + durationMs)
        }
        putJsonObject("assets") {
            cover?.let { put("large_image", it) }
            put("large_text", fit(song.album?.name ?: song.title))
            // The phone's small picture: the first artist's, with their name.
            val artist = song.artists.firstOrNull { it.id != null && Library.artistPicture(it.id!!) != null }
            val picture = artist?.id?.let { Library.artistPicture(it) }?.let(::webImage)
            if (artist != null && picture != null) {
                put("small_image", picture)
                put("small_text", fit(artist.name))
            }
        }
        if ((look.button1 != null && link != null) || look.button2 != null) {
            putJsonArray("buttons") {
                look.button1?.takeIf { link != null }?.let { label ->
                    addJsonObject {
                        put("label", button(resolveVariables(label, song)))
                        put("url", link)
                    }
                }
                look.button2?.let { label ->
                    addJsonObject {
                        put("label", button(resolveVariables(label, song)))
                        put("url", ProjectUrl)
                    }
                }
            }
        }
    }

    /**
     * The cover as a web address Discord fetches itself. Discord takes up to 256 characters there,
     * and some covers' addresses are longer, so those fall back to YouTube's picture of the song.
     * A file on this PC has its cover in the file, which Discord can't reach, so it borrows the
     * cover of the same song on YouTube Music when one is found (LocalSongCovers).
     */
    private fun cover(song: Song): String? =
        artworkUrl(song.thumbnail, 512)?.let(::webImage)
            ?: if (LocalMusic.isLocal(song.id)) {
                LocalSongCovers.cover(song)?.let { artworkUrl(it, 512) }?.let(::webImage)
            } else {
                song.id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            }

    private fun webImage(url: String): String? = url.takeIf { it.startsWith("https://") && it.length <= 256 }

    /** Discord cuts button labels at 32 characters and refuses empty ones. */
    private fun button(text: String): String = text.trim().ifEmpty { "Convx" }.take(32)

    /** Discord wants 2 to 128 characters in each line. */
    private fun fit(text: String): String = text.take(128).padEnd(2, '⠀')

    /** False when Discord turned it down and it is worth sending again without the newer fields. */
    private fun setActivity(pipe: Pipe, activity: JsonObject?): Boolean {
        val reply = pipe.request(command("SET_ACTIVITY", activity))
        if (reply?.get("evt")?.jsonPrimitive?.contentOrNull != "ERROR") return true
        val message = reply["data"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        Log.w(Tag, "Discord turned the status down (level $trimLevel): $message")
        if (activity == null || trimLevel >= 2) return true
        // A Discord that doesn't know a newer field: send again without it, one at a time.
        trimLevel++
        return false
    }

    private fun command(cmd: String, activity: JsonObject?) = buildJsonObject {
        put("cmd", cmd)
        putJsonObject("args") {
            put("pid", ProcessHandle.current().pid())
            if (activity != null) put("activity", activity)
        }
        put("nonce", UUID.randomUUID().toString())
    }

    private fun connect(): Pipe? {
        for (index in 0..9) {
            val opened = Pipe.open(index) ?: continue
            try {
                opened.write(OpHandshake, buildJsonObject {
                    put("v", 1)
                    put("client_id", applicationId())
                })
                val (op, body) = opened.readFrame(5_000) ?: throw IOException("No answer to the handshake")
                if (op == OpClose) throw IOException("Discord closed the connection: $body")
                val ready = json.parseToJsonElement(body).jsonObject
                val user = ready["data"]?.jsonObject?.get("user")?.jsonObject
                _user.value = user?.let {
                    val id = it["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val name = it["global_name"]?.jsonPrimitive?.contentOrNull
                        ?: it["username"]?.jsonPrimitive?.contentOrNull
                        ?: "Discord"
                    val avatar = it["avatar"]?.jsonPrimitive?.contentOrNull?.let { hash -> "https://cdn.discordapp.com/avatars/$id/$hash.png?size=128" }
                    User(id, name, avatar)
                }
                pipe = opened
                shown = null
                trimLevel = 0
                connectedApplication = applicationId()
                _status.value = Status.Connected
                Log.i(Tag, "Connected to Discord (pipe $index) as ${_user.value?.name}")
                return opened
            } catch (e: Exception) {
                Log.w(Tag, "Discord pipe $index didn't answer", e)
                opened.close()
            }
        }
        return null
    }

    private fun disconnect() {
        pipe?.close()
        pipe = null
        shown = null
        if (_status.value == Status.Connected) _status.value = Status.NotRunning
        nextConnectAt = System.currentTimeMillis() + 5_000
    }

    private const val OpHandshake = 0
    private const val OpFrame = 1
    private const val OpClose = 2
    private const val OpPing = 3
    private const val OpPong = 4

    /**
     * Discord's IPC socket: a named pipe on Windows, a Unix socket elsewhere. Frames are an
     * opcode and a length, both little-endian 32-bit, then that many bytes of JSON.
     */
    private abstract class Pipe {
        protected abstract fun writeBytes(bytes: ByteArray)

        /** Reads what is there without waiting; 0 when nothing has arrived. */
        protected abstract fun readSome(buffer: ByteBuffer): Int

        abstract fun close()

        fun write(op: Int, payload: JsonObject) {
            val body = payload.toString().toByteArray(Charsets.UTF_8)
            val frame = ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            frame.putInt(op).putInt(body.size).put(body)
            writeBytes(frame.array())
        }

        /** Sends a command and returns Discord's answer to it. */
        fun request(payload: JsonObject): JsonObject? {
            write(OpFrame, payload)
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val (op, body) = readFrame(deadline - System.currentTimeMillis()) ?: return null
                when (op) {
                    OpFrame -> return runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    OpPing -> write(OpPong, Json.parseToJsonElement(body).jsonObject)
                    OpClose -> throw IOException("Discord closed the connection: $body")
                }
            }
            return null
        }

        /** Answers pings and drops anything else that arrived unasked. */
        fun drain() {
            while (true) {
                val (op, body) = readFrame(0) ?: return
                if (op == OpPing) write(OpPong, Json.parseToJsonElement(body).jsonObject)
                if (op == OpClose) throw IOException("Discord closed the connection: $body")
            }
        }

        /** One frame, or null if none starts within [waitMs]. A frame that starts is read whole. */
        fun readFrame(waitMs: Long): Pair<Int, String>? {
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            if (!fill(header, waitMs)) return null
            header.flip()
            val op = header.int
            val length = header.int
            if (length < 0 || length > 1 shl 20) throw IOException("Bad frame length $length")
            val body = ByteBuffer.allocate(length)
            if (!fill(body, 3_000)) throw IOException("Frame cut short")
            return op to String(body.array(), Charsets.UTF_8)
        }

        /** False if nothing at all arrived in time; a buffer left half full is a broken pipe. */
        private fun fill(buffer: ByteBuffer, waitMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + waitMs
            while (buffer.hasRemaining()) {
                val read = readSome(buffer)
                if (read < 0) throw IOException("Discord closed the pipe")
                if (read == 0) {
                    if (System.currentTimeMillis() >= deadline) {
                        if (buffer.position() == 0) return false
                        throw IOException("Timed out mid-frame")
                    }
                    Thread.sleep(20)
                }
            }
            return true
        }

        companion object {
            private val windows = System.getProperty("os.name").lowercase().contains("win")

            fun open(index: Int): Pipe? = runCatching {
                if (windows) WindowsPipe(RandomAccessFile("\\\\.\\pipe\\discord-ipc-$index", "rw")) else unix(index)
            }.getOrNull()

            private fun unix(index: Int): Pipe? {
                val dirs = listOfNotNull(
                    System.getenv("XDG_RUNTIME_DIR"),
                    System.getenv("TMPDIR"),
                    System.getenv("TMP"),
                    System.getenv("TEMP"),
                    "/tmp",
                ).flatMap { base ->
                    // Flatpak and Snap Discord put their socket one folder down.
                    listOf(base, "$base/app/com.discordapp.Discord", "$base/snap.discord")
                }
                val path = dirs.map { File(it, "discord-ipc-$index") }.firstOrNull { it.exists() } ?: return null
                val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                channel.connect(UnixDomainSocketAddress.of(path.toPath()))
                channel.configureBlocking(false)
                return UnixPipe(channel)
            }
        }
    }

    /**
     * The pipe is opened for plain (blocking) reads, and a read that waits would also hold up
     * every write on it, so it only reads what [RandomAccessFile.length] says has arrived.
     */
    private class WindowsPipe(private val file: RandomAccessFile) : Pipe() {
        override fun writeBytes(bytes: ByteArray) = file.write(bytes)

        override fun readSome(buffer: ByteBuffer): Int {
            val available = file.length().toInt()
            if (available <= 0) return 0
            val count = minOf(available, buffer.remaining())
            val bytes = ByteArray(count)
            file.readFully(bytes)
            buffer.put(bytes)
            return count
        }

        override fun close() {
            runCatching { file.close() }
        }
    }

    private class UnixPipe(private val channel: SocketChannel) : Pipe() {
        override fun writeBytes(bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) == 0) Thread.sleep(5)
            }
        }

        override fun readSome(buffer: ByteBuffer): Int = channel.read(buffer)

        override fun close() {
            runCatching { channel.close() }
        }
    }
}
