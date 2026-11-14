/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations

import com.convx.desktop.core.Http
import com.convx.desktop.core.Log
import com.convx.desktop.data.Song
import com.convx.desktop.player.PlayerController
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.Executors

/**
 * macOS' own controls for the music: the media keys (play/pause, next, previous) and the song,
 * cover and timeline in Control Center's Now Playing, the lock screen and AirPods controls.
 *
 * It is MediaPlayer's MPNowPlayingInfoCenter and MPRemoteCommandCenter, reached through the
 * Objective-C runtime with JNA. The remote commands take Objective-C blocks, which are built by
 * hand here (a global block whose function pointer is a JNA callback). Best effort, like the
 * Windows side: anything the system refuses is logged and the rest carries on.
 */
internal object MacMediaControls {
    private const val Tag = "MacMedia"

    private const val PlaybackPlaying = 1L
    private const val PlaybackPaused = 2L
    private const val PlaybackStopped = 3L

    private const val StatusSuccess = 0L
    private const val StatusNoActionableNowPlayingItem = 112L

    @Volatile var status: String? = null
        private set

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "mac-media-controls").apply { isDaemon = true } }

    // ---- The Objective-C runtime ---------------------------------------------------------------

    private val objc by lazy { NativeLibrary.getInstance("objc") }
    private val msgSend: Function by lazy { objc.getFunction("objc_msgSend") }
    private val getClass: Function by lazy { objc.getFunction("objc_getClass") }
    private val selector: Function by lazy { objc.getFunction("sel_registerName") }
    private val selectors = HashMap<String, Pointer>()
    private val classes = HashMap<String, Pointer>()

    private fun cls(name: String): Pointer = classes.getOrPut(name) { getClass.invokePointer(arrayOf(name)) ?: error("No class $name") }
    private fun sel(name: String): Pointer = selectors.getOrPut(name) { selector.invokePointer(arrayOf(name)) ?: error("No selector $name") }

    private fun send(target: Pointer?, name: String, vararg args: Any?): Pointer? =
        msgSend.invokePointer(arrayOf(target, sel(name), *args))

    private fun sendLong(target: Pointer?, name: String, vararg args: Any?): Long =
        msgSend.invokeLong(arrayOf(target, sel(name), *args))

    private fun sendDouble(target: Pointer?, name: String): Double =
        msgSend.invokeDouble(arrayOf(target, sel(name)))

    private fun nsString(text: String): Pointer {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val buffer = Memory(bytes.size + 1L).apply { write(0, bytes, 0, bytes.size); setByte(bytes.size.toLong(), 0) }
        return send(cls("NSString"), "stringWithUTF8String:", buffer) ?: error("NSString")
    }

    private fun nsNumber(value: Double): Pointer = send(cls("NSNumber"), "numberWithDouble:", value) ?: error("NSNumber")

    /** Runs [body] inside an autorelease pool, as this thread has none of its own. */
    private fun <T> pooled(body: () -> T): T {
        val pool = send(send(cls("NSAutoreleasePool"), "alloc"), "init")
        try {
            return body()
        } finally {
            send(pool, "drain")
        }
    }

    // ---- Blocks --------------------------------------------------------------------------------

    private fun interface CommandHandler : Callback {
        fun invoke(block: Pointer?, event: Pointer?): Long
    }

    private fun interface ArtworkHandler : Callback {
        fun invoke(block: Pointer?, width: Double, height: Double): Pointer?
    }

    /** A global (never copied or freed) Objective-C block that calls [callback]. */
    private fun block(callback: Callback): Pointer {
        val isa = NativeLibrary.getInstance("System").getGlobalVariableAddress("_NSConcreteGlobalBlock")
        val descriptor = Memory(16).apply { setLong(0, 0); setLong(8, 32) }
        return Memory(32).apply {
            setPointer(0, isa)
            setInt(8, 1 shl 28) // BLOCK_IS_GLOBAL
            setInt(12, 0)
            setPointer(16, CallbackReference.getFunctionPointer(callback))
            setPointer(24, descriptor)
            keep += this
            keep += descriptor
        }
    }

    // Everything native that must outlive its Kotlin owner.
    private val keep = ArrayList<Any>()

    // ---- State ---------------------------------------------------------------------------------

    private var started = false
    private var center: Pointer? = null
    private var shownSongId: String? = null
    private var artwork: Pointer? = null
    private var artworkFor: String? = null

    /** Registers the remote commands. Safe to call twice. */
    fun start(): Boolean {
        if (started) return true
        return runCatching {
            // The frameworks are in the system cache; loading them makes their classes known to the runtime.
            NativeLibrary.getInstance("/System/Library/Frameworks/MediaPlayer.framework/MediaPlayer")
            NativeLibrary.getInstance("/System/Library/Frameworks/AppKit.framework/AppKit")
            pooled {
                center = send(cls("MPNowPlayingInfoCenter"), "defaultCenter")
                val commands = send(cls("MPRemoteCommandCenter"), "sharedCommandCenter")
                fun on(property: String, command: MediaControls.Command) {
                    val c = send(commands, property) ?: return
                    send(c, "setEnabled:", 1)
                    val handler = CommandHandler { _, _ ->
                        MediaControls.run(command, "macOS media keys")
                        StatusSuccess
                    }
                    send(c, "addTargetWithHandler:", block(handler))
                }
                on("playCommand", MediaControls.Command.PLAY)
                on("pauseCommand", MediaControls.Command.PAUSE)
                on("togglePlayPauseCommand", MediaControls.Command.TOGGLE)
                on("nextTrackCommand", MediaControls.Command.NEXT)
                on("previousTrackCommand", MediaControls.Command.PREVIOUS)
                on("stopCommand", MediaControls.Command.STOP)
                // The timeline in Control Center is draggable.
                send(commands, "changePlaybackPositionCommand")?.let { c ->
                    send(c, "setEnabled:", 1)
                    val handler = CommandHandler { _, event ->
                        val seconds = runCatching { sendDouble(event, "positionTime") }.getOrDefault(-1.0)
                        if (seconds < 0) {
                            StatusNoActionableNowPlayingItem
                        } else {
                            MediaControls.lastCommand = "SEEK from macOS Now Playing"
                            PlayerController.seekTo((seconds * 1000).toLong())
                            StatusSuccess
                        }
                    }
                    send(c, "addTargetWithHandler:", block(handler))
                }
            }
            started = true
            status = "on"
            Log.i(Tag, "macOS media controls on")
            true
        }.onFailure {
            status = "failed: ${it.message}"
            Log.w(Tag, "macOS media controls didn't start", it)
        }.getOrDefault(false)
    }

    fun show(snap: MediaControls.Snapshot) {
        if (!started) return
        thread.execute {
            runCatching { publish(snap) }.onFailure { Log.w(Tag, "Couldn't update Now Playing", it) }
        }
    }

    /** Position or duration changed without the song changing. */
    fun timeline() {
        if (!started) return
        val s = PlayerController.state.value
        show(MediaControls.Snapshot(s.current, s.isPlaying, s.isLoading, s.queue.isNotEmpty()))
    }

    private fun publish(snap: MediaControls.Snapshot) = pooled {
        val song = snap.song
        val c = center ?: return@pooled
        if (song == null) {
            send(c, "setNowPlayingInfo:", null as Pointer?)
            send(c, "setPlaybackState:", PlaybackStopped)
            shownSongId = null
            return@pooled
        }
        if (song.id != shownSongId) {
            shownSongId = song.id
            artwork = null
            artworkFor = null
            fetchArtwork(song)
        }
        val s = PlayerController.state.value
        val durationMs = s.durationMs.takeIf { it > 0 } ?: song.duration?.times(1000L) ?: 0L
        val info = send(cls("NSMutableDictionary"), "dictionary")
        fun put(key: String, value: Pointer?) {
            if (value != null) send(info, "setObject:forKey:", value, nsString(key))
        }
        put("title", nsString(song.title))
        put("artist", nsString(song.artistText))
        song.album?.name?.takeIf { it.isNotBlank() }?.let { put("albumTitle", nsString(it)) }
        if (durationMs > 0) put("playbackDuration", nsNumber(durationMs / 1000.0))
        put("MPNowPlayingInfoPropertyElapsedPlaybackTime", nsNumber(PlayerController.livePositionMs().coerceIn(0, durationMs.coerceAtLeast(0)) / 1000.0))
        put("MPNowPlayingInfoPropertyPlaybackRate", nsNumber(if (snap.playing && !snap.loading) 1.0 else 0.0))
        put("MPNowPlayingInfoPropertyDefaultPlaybackRate", nsNumber(1.0))
        if (artworkFor == song.id) put("artwork", artwork)
        send(c, "setNowPlayingInfo:", info)
        send(c, "setPlaybackState:", if (snap.playing) PlaybackPlaying else PlaybackPaused)
    }

    /** Downloads the cover and wraps it for the system, which asks for it at whatever size it likes. */
    private fun fetchArtwork(song: Song) {
        val url = MediaControls.coverUrl(song) ?: return
        val id = song.id
        com.convx.desktop.core.AppScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    Http.client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() }
                }.getOrNull()
            } ?: return@launch
            thread.execute {
                runCatching {
                    if (shownSongId != id) return@execute
                    pooled {
                        val data = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
                        val nsData = send(cls("NSData"), "dataWithBytes:length:", data, bytes.size.toLong())
                        val image = send(send(cls("NSImage"), "alloc"), "initWithData:", nsData) ?: return@pooled
                        val handler = ArtworkHandler { _, _, _ -> image }
                        // NSSize {width, height}: a struct of two doubles, passed in the floating-point registers.
                        val item = send(send(cls("MPMediaItemArtwork"), "alloc"), "initWithBoundsSize:requestHandler:", Size(640.0, 640.0), block(handler))
                        keep += image
                        artwork = item
                        artworkFor = id
                    }
                    val s = PlayerController.state.value
                    publish(MediaControls.Snapshot(s.current, s.isPlaying, s.isLoading, s.queue.isNotEmpty()))
                }.onFailure { Log.w(Tag, "No cover for Now Playing", it) }
            }
        }
    }

    /** CGSize / NSSize by value. */
    class Size(width: Double, height: Double) : com.sun.jna.Structure(), com.sun.jna.Structure.ByValue {
        @JvmField var width: Double = width
        @JvmField var height: Double = height
        constructor() : this(0.0, 0.0)
        override fun getFieldOrder() = listOf("width", "height")
    }
}
