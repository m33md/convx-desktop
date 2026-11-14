/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations

import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.data.Song
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.artworkUrl
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.awt.BasicStroke
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.awt.Window as AwtWindow

/**
 * Windows' own controls for the music, as Spotify has them:
 *  - the media keys (play/pause, next, previous) whether or not Convx has the focus,
 *  - the song and its buttons in the box Windows shows with the volume, in Quick Settings and on the lock screen,
 *  - three buttons under the window's preview when the pointer rests on Convx in the taskbar.
 *
 * The first two are Windows' System Media Transport Controls, reached through WinRT's raw COM
 * interfaces; the third is the taskbar's ITaskbarList3. Everything here is best effort: on a
 * Windows that refuses a piece, that piece logs why and the rest carries on.
 */
object MediaControls {
    private const val Tag = "MediaControls"
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /** What reached the player from Windows, for the CI check and the log. */
    @Volatile var lastCommand: String? = null
        internal set

    @Volatile private var installed = false

    /** Hooks the window up. Call once the window is on screen. */
    fun install(window: AwtWindow) {
        if (!isWindows && System.getProperty("os.name").lowercase().contains("mac") && !installed) {
            installed = true
            if (MacMediaControls.start()) {
                AppScope.launch {
                    PlayerController.state
                        .map { s -> Snapshot(s.current, s.isPlaying, s.isLoading, s.queue.isNotEmpty()) }
                        .distinctUntilChanged()
                        .collect { snap -> MacMediaControls.show(snap) }
                }
                AppScope.launch { PlayerController.changes.collect { MacMediaControls.timeline() } }
                AppScope.launch {
                    while (true) {
                        delay(5_000)
                        if (PlayerController.state.value.isPlaying) MacMediaControls.timeline()
                    }
                }
            }
            return
        }
        if (!isWindows || installed) return
        installed = true
        val hwnd = runCatching { WinDef.HWND(Native.getWindowPointer(window)) }.getOrElse {
            Log.w(Tag, "No window handle", it)
            return
        }
        WindowHook.install(hwnd)
        val smtc = Smtc.start(hwnd)
        Taskbar.start(hwnd, hotkeys = !smtc)
        AppScope.launch {
            PlayerController.state
                .map { s -> Snapshot(s.current, s.isPlaying, s.isLoading, s.queue.isNotEmpty()) }
                .distinctUntilChanged()
                .collect { snap ->
                    Smtc.show(snap)
                    Taskbar.show(snap)
                }
        }
        // The flyout's timeline: kept in step on every seek, and now and then while it plays.
        AppScope.launch {
            PlayerController.changes.collect { Smtc.timeline() }
        }
        AppScope.launch {
            while (true) {
                delay(5_000)
                if (PlayerController.state.value.isPlaying) Smtc.timeline()
            }
        }
    }

    fun shutdown() {
        if (!installed) return
        if (!isWindows) return
        // The window's procedure goes back first, so no message reaches Kotlin while the app closes.
        WindowHook.uninstall()
        Smtc.close()
        Taskbar.close()
    }

    internal data class Snapshot(val song: Song?, val playing: Boolean, val loading: Boolean, val hasQueue: Boolean)

    internal enum class Command { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, STOP }

    internal fun run(command: Command, from: String) {
        lastCommand = "$command from $from"
        Log.i(Tag, "$command from $from")
        when (command) {
            Command.PLAY -> PlayerController.play()
            Command.PAUSE -> PlayerController.pause()
            Command.TOGGLE -> PlayerController.togglePlay()
            Command.NEXT -> PlayerController.next()
            Command.PREVIOUS -> PlayerController.previous()
            Command.STOP -> PlayerController.pause()
        }
    }

    /** The cover as a web address Windows fetches itself; local covers aren't on the web, so those show none. */
    internal fun coverUrl(song: Song): String? =
        artworkUrl(song.thumbnail, 512)?.takeIf { it.startsWith("https://") }
            ?: song.id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

    /** A self-test for CI: runs each path once and says which ones reached the player. */
    fun selfTest(window: AwtWindow): List<String> {
        val out = mutableListOf<String>()
        out += "System media controls: " + (Smtc.status ?: "not started")
        out += "Taskbar buttons: " + (Taskbar.status ?: "not started")
        val hwnd = WinDef.HWND(Native.getWindowPointer(window))
        fun probe(name: String, action: () -> Unit) {
            lastCommand = null
            action()
            val deadline = System.currentTimeMillis() + 2_000
            while (lastCommand == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
            out += "$name: " + (lastCommand ?: "nothing reached the player")
        }
        // WM_APPCOMMAND, as a media key sends it to the window that has the focus.
        probe("Media key message") {
            User32.INSTANCE.SendMessage(hwnd, WM_APPCOMMAND, WinDef.WPARAM(0), WinDef.LPARAM((APPCOMMAND_MEDIA_NEXTTRACK.toLong() shl 16)))
        }
        // A click on the taskbar preview's middle button.
        probe("Taskbar button message") {
            User32.INSTANCE.SendMessage(hwnd, MediaControls.WM_COMMAND, WinDef.WPARAM(((THBN_CLICKED shl 16) or Taskbar.ID_PLAY).toLong()), WinDef.LPARAM(0))
        }
        // A real key press, as the keyboard sends it.
        probe("Next-track key press") { pressKey(VK_MEDIA_NEXT_TRACK) }
        return out
    }

    private fun pressKey(vk: Int) {
        @Suppress("UNCHECKED_CAST")
        val inputs = WinUser.INPUT().toArray(2) as Array<WinUser.INPUT>
        inputs.forEachIndexed { i, input ->
            input.type = WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
            input.input.setType("ki")
            input.input.ki.wVk = WinDef.WORD(vk.toLong())
            input.input.ki.dwFlags = WinDef.DWORD(if (i == 0) KEYEVENTF_EXTENDEDKEY.toLong() else (KEYEVENTF_EXTENDEDKEY or KEYEVENTF_KEYUP).toLong())
        }
        User32.INSTANCE.SendInput(WinDef.DWORD(2), inputs, inputs[0].size())
    }

    internal const val WM_APPCOMMAND = 0x0319
    internal const val WM_COMMAND = 0x0111
    internal const val THBN_CLICKED = 0x1800
    private const val APPCOMMAND_MEDIA_NEXTTRACK = 11
    private const val APPCOMMAND_MEDIA_PREVIOUSTRACK = 12
    private const val APPCOMMAND_MEDIA_STOP = 13
    private const val APPCOMMAND_MEDIA_PLAY_PAUSE = 14
    private const val APPCOMMAND_MEDIA_PLAY = 46
    private const val APPCOMMAND_MEDIA_PAUSE = 47
    internal const val VK_MEDIA_NEXT_TRACK = 0xB0
    internal const val VK_MEDIA_PREV_TRACK = 0xB1
    internal const val VK_MEDIA_STOP = 0xB2
    internal const val VK_MEDIA_PLAY_PAUSE = 0xB3
    private const val KEYEVENTF_EXTENDEDKEY = 0x1
    private const val KEYEVENTF_KEYUP = 0x2

    internal fun appCommand(lParam: Long): Command? = when (((lParam ushr 16).toInt() and 0x0FFF)) {
        APPCOMMAND_MEDIA_PLAY_PAUSE -> Command.TOGGLE
        APPCOMMAND_MEDIA_PLAY -> Command.PLAY
        APPCOMMAND_MEDIA_PAUSE -> Command.PAUSE
        APPCOMMAND_MEDIA_NEXTTRACK -> Command.NEXT
        APPCOMMAND_MEDIA_PREVIOUSTRACK -> Command.PREVIOUS
        APPCOMMAND_MEDIA_STOP -> Command.STOP
        else -> null
    }
}

// ---- COM plumbing -------------------------------------------------------------------------------

private const val S_OK = 0
private const val E_NOINTERFACE = 0x80004002.toInt()

private fun guid(text: String): Guid.GUID = Guid.GUID.fromString(text).also { it.write() }

private fun Guid.GUID.bytes(): ByteArray {
    write()
    return pointer.getByteArray(0, 16)
}

/** Calls method [index] of a COM interface pointer; arguments after `this`. */
private fun Pointer.vcall(index: Int, vararg args: Any?): Int {
    val vtable = getPointer(0)
    val fn = Function.getFunction(vtable.getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
    return fn.invokeInt(arrayOf(this, *args))
}

private fun Pointer.release() = runCatching { vcall(2) }

private fun Int.ok() = this >= 0

private fun Int.hex() = "0x%08X".format(this)

internal interface Combase : Library {
    fun RoInitialize(type: Int): Int
    fun RoGetActivationFactory(classId: Pointer?, iid: Guid.GUID, factory: PointerByReference): Int
    fun RoActivateInstance(classId: Pointer?, instance: PointerByReference): Int
    fun WindowsCreateString(source: WString?, length: Int, string: PointerByReference): Int
    fun WindowsDeleteString(string: Pointer?): Int
}

private val combase: Combase by lazy { Native.load("combase", Combase::class.java) }

private inline fun <T> withHString(text: String, block: (Pointer?) -> T): T {
    val ref = PointerByReference()
    val hr = combase.WindowsCreateString(WString(text), text.length, ref)
    check(hr.ok()) { "WindowsCreateString ${hr.hex()}" }
    try {
        return block(ref.value)
    } finally {
        combase.WindowsDeleteString(ref.value)
    }
}

private fun activationFactory(className: String, iid: Guid.GUID): Pointer {
    val out = PointerByReference()
    val hr = withHString(className) { combase.RoGetActivationFactory(it, iid, out) }
    check(hr.ok() && out.value != null) { "No factory for $className (${hr.hex()})" }
    return out.value
}

private fun Pointer.query(iid: Guid.GUID): Pointer? {
    val out = PointerByReference()
    return if (vcall(0, iid, out).ok()) out.value else null
}

internal interface QueryInterfaceFn : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer?, riid: Pointer?, out: Pointer?): Int
}

internal interface RefCountFn : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer?): Int
}

internal interface DelegateInvokeFn : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer?, sender: Pointer?, args: Pointer?): Int
}

/**
 * A WinRT event handler made by hand: an object whose first field points at a table of
 * QueryInterface, AddRef, Release and Invoke. It lives as long as the app, so the counts are a formality.
 */
private class EventHandler(delegateIid: String, private val onInvoke: (Pointer) -> Unit) {
    private val accepted = listOf(
        guid("{00000000-0000-0000-C000-000000000046}"), // IUnknown
        guid("{94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90}"), // IAgileObject: it may be called from any thread
        guid(delegateIid),
    ).map { it.bytes() }

    private val queryInterface = object : QueryInterfaceFn {
        override fun invoke(self: Pointer?, riid: Pointer?, out: Pointer?): Int {
            if (out == null) return E_NOINTERFACE
            val asked = riid?.getByteArray(0, 16)
            return if (asked != null && accepted.any { it.contentEquals(asked) }) {
                out.setPointer(0, self)
                S_OK
            } else {
                out.setPointer(0, null)
                E_NOINTERFACE
            }
        }
    }
    private val addRef = object : RefCountFn {
        override fun invoke(self: Pointer?): Int = 2
    }
    private val release = object : RefCountFn {
        override fun invoke(self: Pointer?): Int = 1
    }
    private val invoke = object : DelegateInvokeFn {
        override fun invoke(self: Pointer?, sender: Pointer?, args: Pointer?): Int {
            if (args != null) runCatching { onInvoke(args) }.onFailure { Log.w("MediaControls", "Event handler failed", it) }
            return S_OK
        }
    }

    private val vtable = Memory(4L * Native.POINTER_SIZE).apply {
        listOf(queryInterface, addRef, release, invoke).forEachIndexed { i, cb ->
            setPointer(i.toLong() * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(cb))
        }
    }

    val pointer: Pointer = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, vtable) }
}

// ---- System Media Transport Controls ------------------------------------------------------------

private object Smtc {
    private const val Tag = "MediaControls"

    // Interface ids, from the Windows SDK's metadata.
    private val IID_Interop = guid("{ddb0472d-c911-4a1f-86d9-dc3d71a95f5a}")
    private val IID_Controls = guid("{99fa3ff4-1742-42a6-902e-087d41f965ec}")
    private val IID_Controls2 = guid("{ea98d2f6-7f3c-4af2-a586-72889808efb1}")
    private val IID_MusicProperties2 = guid("{00368462-97d3-44b9-b00f-008afcefaf18}")
    private val IID_Timeline = guid("{5125316a-c3a2-475b-8507-93534dc88f15}")
    private val IID_UriFactory = guid("{44a9796f-723e-4fdf-a218-033e75b0c084}")
    private val IID_StreamReferenceStatics = guid("{857309dc-3fbf-4e7d-986f-ef3b1a07a964}")

    // TypedEventHandler<SystemMediaTransportControls, …EventArgs>, worked out the WinRT way from the two types.
    private const val IID_ButtonPressedHandler = "{0557e996-7b23-5bae-aa81-ea0d671143a4}"
    private const val IID_PositionRequestedHandler = "{44e34f15-bdc0-50a7-ace4-39e91fb753f1}"

    // ISystemMediaTransportControls, after IInspectable's six.
    private const val SetPlaybackStatus = 7
    private const val GetDisplayUpdater = 8
    private const val SetIsEnabled = 11
    private const val SetIsPlayEnabled = 13
    private const val SetIsStopEnabled = 15
    private const val SetIsPauseEnabled = 17
    private const val SetIsPreviousEnabled = 25
    private const val SetIsNextEnabled = 27
    private const val AddButtonPressed = 32

    // ISystemMediaTransportControls2
    private const val UpdateTimelineProperties = 12
    private const val AddPlaybackPositionChangeRequested = 13

    // ISystemMediaTransportControlsDisplayUpdater
    private const val SetType = 7
    private const val SetThumbnail = 11
    private const val GetMusicProperties = 12
    private const val ClearAll = 16
    private const val Update = 17

    // IMusicDisplayProperties (and 2)
    private const val SetTitle = 7
    private const val SetAlbumArtist = 9
    private const val SetArtist = 11
    private const val SetAlbumTitle = 7

    // ISystemMediaTransportControlsTimelineProperties: TimeSpans in 100 ns ticks.
    private const val SetStartTime = 7
    private const val SetEndTime = 9
    private const val SetMinSeekTime = 11
    private const val SetMaxSeekTime = 13
    private const val SetPosition = 15

    private const val StatusClosed = 0
    private const val StatusChanging = 1
    private const val StatusPlaying = 3
    private const val StatusPaused = 4

    private const val ButtonPlay = 0
    private const val ButtonPause = 1
    private const val ButtonStop = 2
    private const val ButtonNext = 6
    private const val ButtonPrevious = 7

    /** WinRT wants a thread that joined its multithreaded apartment; this one does and lasts. */
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "convx-media-controls").apply { isDaemon = true } }

    private var controls: Pointer? = null
    private var controls2: Pointer? = null
    private val handlers = mutableListOf<EventHandler>()
    private var shownSongId: String? = null

    @Volatile var status: String? = null
        private set

    fun start(hwnd: WinDef.HWND): Boolean = runCatching {
        thread.submit<Boolean> { open(hwnd) }.get(10, TimeUnit.SECONDS)
    }.getOrElse {
        status = "failed: ${it.message}"
        Log.w(Tag, "Windows media controls didn't start", it)
        false
    }

    private fun open(hwnd: WinDef.HWND): Boolean {
        combase.RoInitialize(1) // multithreaded; "already" answers are fine
        val interop = activationFactory("Windows.Media.SystemMediaTransportControls", IID_Interop)
        val out = PointerByReference()
        val hr = try {
            interop.vcall(6, hwnd.pointer, IID_Controls, out)
        } finally {
            interop.release()
        }
        check(hr.ok() && out.value != null) { "GetForWindow ${hr.hex()}" }
        val c = out.value
        controls = c
        c.vcall(SetIsEnabled, 1)
        c.vcall(SetIsPlayEnabled, 1)
        c.vcall(SetIsPauseEnabled, 1)
        c.vcall(SetIsStopEnabled, 1)
        c.vcall(SetIsPreviousEnabled, 1)
        c.vcall(SetIsNextEnabled, 1)
        val buttons = EventHandler(IID_ButtonPressedHandler) { args ->
            val button = IntByReference()
            if (args.vcall(6, button).ok()) {
                val command = when (button.value) {
                    ButtonPlay -> MediaControls.Command.PLAY
                    ButtonPause -> MediaControls.Command.PAUSE
                    ButtonStop -> MediaControls.Command.STOP
                    ButtonNext -> MediaControls.Command.NEXT
                    ButtonPrevious -> MediaControls.Command.PREVIOUS
                    else -> null
                }
                command?.let { MediaControls.run(it, "Windows media controls") }
            }
        }
        handlers += buttons
        val token = LongByReference()
        val added = c.vcall(AddButtonPressed, buttons.pointer, token)
        check(added.ok()) { "ButtonPressed ${added.hex()}" }
        // Seeking from the flyout's timeline, where Windows offers it.
        controls2 = c.query(IID_Controls2)
        controls2?.let { c2 ->
            val seek = EventHandler(IID_PositionRequestedHandler) { args ->
                val ticks = LongByReference()
                if (args.vcall(6, ticks).ok()) PlayerController.seekTo(ticks.value / 10_000)
            }
            handlers += seek
            c2.vcall(AddPlaybackPositionChangeRequested, seek.pointer, LongByReference())
        }
        status = "on"
        Log.i(Tag, "Windows media controls on")
        return true
    }

    fun show(snap: MediaControls.Snapshot) {
        val c = controls ?: return
        thread.execute {
            runCatching {
                val song = snap.song
                c.vcall(
                    SetPlaybackStatus,
                    when {
                        song == null -> StatusClosed
                        snap.loading && snap.playing -> StatusChanging
                        snap.playing -> StatusPlaying
                        else -> StatusPaused
                    },
                )
                if (song?.id != shownSongId) {
                    shownSongId = song?.id
                    describe(c, song)
                }
                timelineNow()
            }.onFailure { Log.w(Tag, "Couldn't update Windows media controls", it) }
        }
    }

    fun timeline() {
        if (controls2 == null) return
        thread.execute { runCatching { timelineNow() } }
    }

    private fun describe(c: Pointer, song: Song?) {
        val out = PointerByReference()
        check(c.vcall(GetDisplayUpdater, out).ok())
        val updater = out.value ?: return
        try {
            if (song == null) {
                updater.vcall(ClearAll)
                updater.vcall(Update)
                return
            }
            updater.vcall(SetType, 1) // music
            val musicOut = PointerByReference()
            if (updater.vcall(GetMusicProperties, musicOut).ok() && musicOut.value != null) {
                val music = musicOut.value
                withHString(song.title) { music.vcall(SetTitle, it) }
                withHString(song.artistText) { music.vcall(SetArtist, it) }
                withHString(song.artists.firstOrNull()?.name.orEmpty()) { music.vcall(SetAlbumArtist, it) }
                music.query(IID_MusicProperties2)?.let { m2 ->
                    withHString(song.album?.name.orEmpty()) { m2.vcall(SetAlbumTitle, it) }
                    m2.release()
                }
                music.release()
            }
            val thumbnail = MediaControls.coverUrl(song)?.let(::streamReference)
            updater.vcall(SetThumbnail, thumbnail)
            thumbnail?.release()
            updater.vcall(Update)
        } finally {
            updater.release()
        }
    }

    /** A RandomAccessStreamReference for a web picture; Windows downloads it when it shows it. */
    private fun streamReference(url: String): Pointer? = runCatching {
        val uriFactory = activationFactory("Windows.Foundation.Uri", IID_UriFactory)
        val uri = PointerByReference()
        try {
            withHString(url) { check(uriFactory.vcall(6, it, uri).ok()) }
        } finally {
            uriFactory.release()
        }
        val statics = activationFactory("Windows.Storage.Streams.RandomAccessStreamReference", IID_StreamReferenceStatics)
        val ref = PointerByReference()
        try {
            check(statics.vcall(7, uri.value, ref).ok())
        } finally {
            statics.release()
            uri.value?.release()
        }
        ref.value
    }.onFailure { Log.w(Tag, "No cover for Windows media controls", it) }.getOrNull()

    private fun timelineNow() {
        val c2 = controls2 ?: return
        val s = PlayerController.state.value
        val duration = s.durationMs.takeIf { it > 0 } ?: s.current?.duration?.times(1000L) ?: return
        val instance = PointerByReference()
        val hr = withHString("Windows.Media.SystemMediaTransportControlsTimelineProperties") { combase.RoActivateInstance(it, instance) }
        if (!hr.ok() || instance.value == null) return
        val timeline = instance.value.query(IID_Timeline)
        instance.value.release()
        timeline ?: return
        try {
            val ticks = 10_000L
            timeline.vcall(SetStartTime, 0L)
            timeline.vcall(SetEndTime, duration * ticks)
            timeline.vcall(SetMinSeekTime, 0L)
            timeline.vcall(SetMaxSeekTime, duration * ticks)
            timeline.vcall(SetPosition, PlayerController.livePositionMs().coerceIn(0, duration) * ticks)
            c2.vcall(UpdateTimelineProperties, timeline)
        } finally {
            timeline.release()
        }
    }

    fun close() {
        val c = controls ?: return
        runCatching {
            thread.submit {
                runCatching { c.vcall(SetPlaybackStatus, StatusClosed) }
                runCatching { c.vcall(SetIsEnabled, 0) }
            }.get(2, TimeUnit.SECONDS)
        }
    }
}

// ---- The window: taskbar-button clicks and media-key messages ------------------------------------

private object WindowHook {
    private const val GWLP_WNDPROC = -4
    private var previous: Pointer? = null
    private var window: WinDef.HWND? = null
    @Volatile var taskbarCreated = 0
        private set

    // Kept here so the collector never takes the window's procedure away.
    private val procedure = object : WinUser.WindowProc {
        override fun callback(hwnd: WinDef.HWND, msg: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): WinDef.LRESULT {
            try {
                when {
                    msg == MediaControls.WM_COMMAND && ((wParam.toLong() ushr 16).toInt() and 0xFFFF) == MediaControls.THBN_CLICKED -> {
                        Taskbar.clicked((wParam.toLong() and 0xFFFF).toInt())
                        return WinDef.LRESULT(0)
                    }
                    msg == MediaControls.WM_APPCOMMAND -> {
                        val command = MediaControls.appCommand(lParam.toLong())
                        if (command != null) {
                            // Handled here, so Windows doesn't pass the same key on to the media controls too.
                            MediaControls.run(command, "media key")
                            return WinDef.LRESULT(1)
                        }
                    }
                    msg != 0 && msg == taskbarCreated -> Taskbar.buttonCreated()
                }
            } catch (_: Throwable) {
                // Never let an exception unwind into Windows.
            }
            return User32.INSTANCE.CallWindowProc(previous, hwnd, msg, wParam, lParam)
        }
    }

    fun install(hwnd: WinDef.HWND) {
        runCatching {
            taskbarCreated = User32.INSTANCE.RegisterWindowMessage("TaskbarButtonCreated")
            previous = User32.INSTANCE.SetWindowLongPtr(hwnd, GWLP_WNDPROC, CallbackReference.getFunctionPointer(procedure))
            if (previous != null) window = hwnd
            if (previous == null) Log.w("MediaControls", "Couldn't listen to the window (${Kernel32.INSTANCE.GetLastError()})")
        }.onFailure { Log.w("MediaControls", "Couldn't listen to the window", it) }
    }

    fun uninstall() {
        val hwnd = window ?: return
        runCatching { User32.INSTANCE.SetWindowLongPtr(hwnd, GWLP_WNDPROC, previous) }
        window = null
    }
}

// ---- The taskbar preview's buttons, and the hotkey fallback ---------------------------------------

internal interface IconApi : StdCallLibrary {
    fun CreateIconFromResourceEx(bits: ByteArray, size: Int, icon: Boolean, version: Int, cx: Int, cy: Int, flags: Int): WinDef.HICON?
    fun DestroyIcon(icon: WinDef.HICON?): Boolean
}

/** Windows' THUMBBUTTON, field by field. */
@Structure.FieldOrder("dwMask", "iId", "iBitmap", "hIcon", "szTip", "dwFlags")
internal class ThumbButton : Structure() {
    @JvmField var dwMask: Int = 0
    @JvmField var iId: Int = 0
    @JvmField var iBitmap: Int = 0
    @JvmField var hIcon: WinDef.HICON? = null
    @JvmField var szTip: CharArray = CharArray(260)
    @JvmField var dwFlags: Int = 0
}

private object Taskbar {
    private const val Tag = "MediaControls"
    const val ID_PREVIOUS = 1
    const val ID_PLAY = 2
    const val ID_NEXT = 3

    private val CLSID_TaskbarList = Guid.GUID.fromString("{56fdf344-fd6d-11d0-958a-006097c9a090}")
    private val IID_ITaskbarList3 = Guid.GUID.fromString("{ea1afb91-9e28-4b86-90e9-9e9f8a5eefaf}")
    private const val HrInit = 3
    private const val ThumbBarAddButtons = 15
    private const val ThumbBarUpdateButtons = 16

    private const val THB_ICON = 0x2
    private const val THB_TOOLTIP = 0x4
    private const val THB_FLAGS = 0x8
    private const val THBF_ENABLED = 0x0
    private const val THBF_DISABLED = 0x1

    private const val WM_WORK = WinUser.WM_USER + 0x41
    private const val WM_HOTKEY = 0x0312

    private val icons: IconApi by lazy { Native.load("user32", IconApi::class.java, W32APIOptions.DEFAULT_OPTIONS) }

    private val work = ConcurrentLinkedQueue<() -> Unit>()
    @Volatile private var threadId = 0
    private var taskbar: Pointer? = null
    private var hwnd: WinDef.HWND? = null
    private var added = false
    private var last: MediaControls.Snapshot? = null
    private val glyphs = mutableMapOf<String, WinDef.HICON>()

    @Volatile var status: String? = null
        private set

    fun start(window: WinDef.HWND, hotkeys: Boolean) {
        hwnd = window
        val ready = CountDownLatch(1)
        Thread({
            // The taskbar's object lives in a single-threaded apartment, with this thread's message loop.
            Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED)
            threadId = Kernel32.INSTANCE.GetCurrentThreadId()
            ready.countDown()
            runCatching { create() }.onFailure {
                status = "failed: ${it.message}"
                Log.w(Tag, "No taskbar buttons", it)
            }
            if (hotkeys) registerHotkeys()
            val msg = WinUser.MSG()
            while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                when (msg.message) {
                    WM_WORK -> while (true) {
                        val job = work.poll() ?: break
                        runCatching(job).onFailure { Log.w(Tag, "Taskbar work failed", it) }
                    }
                    WM_HOTKEY -> hotkey(msg.wParam.toInt())
                    else -> {
                        User32.INSTANCE.TranslateMessage(msg)
                        User32.INSTANCE.DispatchMessage(msg)
                    }
                }
            }
        }, "convx-taskbar").apply { isDaemon = true }.start()
        ready.await(5, TimeUnit.SECONDS)
    }

    private fun post(job: () -> Unit) {
        work += job
        if (threadId != 0) User32.INSTANCE.PostThreadMessage(threadId, WM_WORK, WinDef.WPARAM(0), WinDef.LPARAM(0))
    }

    private fun create() {
        val out = PointerByReference()
        val hr = Ole32.INSTANCE.CoCreateInstance(CLSID_TaskbarList, null, 1 /* in-process */, IID_ITaskbarList3, out)
        check(hr.toInt().ok() && out.value != null) { "TaskbarList ${hr.toInt().hex()}" }
        taskbar = out.value
        check(out.value.vcall(HrInit).ok()) { "HrInit" }
        // The taskbar button is usually there by now; if not, TaskbarButtonCreated brings us back.
        addButtons()
    }

    fun buttonCreated() = post {
        added = false
        addButtons()
    }

    fun show(snap: MediaControls.Snapshot) = post {
        last = snap
        if (added) updateButtons() else addButtons()
    }

    fun clicked(id: Int) {
        when (id) {
            ID_PREVIOUS -> MediaControls.run(MediaControls.Command.PREVIOUS, "taskbar")
            ID_PLAY -> MediaControls.run(MediaControls.Command.TOGGLE, "taskbar")
            ID_NEXT -> MediaControls.run(MediaControls.Command.NEXT, "taskbar")
        }
    }

    /** Whether the taskbar is light; its previews follow it, and white glyphs vanish on a light one. */
    private var lightTaskbar: Boolean? = null

    private fun taskbarIsLight(): Boolean = runCatching {
        Advapi32Util.registryGetIntValue(
            WinReg.HKEY_CURRENT_USER,
            "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "SystemUsesLightTheme",
        ) == 1
    }.getOrDefault(false)

    private fun buttons(): Array<ThumbButton> {
        val light = taskbarIsLight()
        if (light != lightTaskbar) {
            // The theme changed (or this is the first time): draw the glyphs again in the right colour.
            glyphs.values.forEach { icons.DestroyIcon(it) }
            glyphs.clear()
            lightTaskbar = light
        }
        val snap = last
        val enabled = snap?.hasQueue == true
        @Suppress("UNCHECKED_CAST")
        val array = ThumbButton().toArray(3) as Array<ThumbButton>
        fun fill(b: ThumbButton, id: Int, glyph: String, tip: String) {
            b.dwMask = THB_ICON or THB_TOOLTIP or THB_FLAGS
            b.iId = id
            b.hIcon = icon(glyph)
            tip.toCharArray().copyInto(b.szTip, endIndex = minOf(tip.length, 259))
            b.dwFlags = if (enabled) THBF_ENABLED else THBF_DISABLED
            b.write()
        }
        fill(array[0], ID_PREVIOUS, "previous", "Previous")
        if (snap?.playing == true) fill(array[1], ID_PLAY, "pause", "Pause") else fill(array[1], ID_PLAY, "play", "Play")
        fill(array[2], ID_NEXT, "next", "Next")
        return array
    }

    private fun addButtons() {
        val tb = taskbar ?: return
        val window = hwnd ?: return
        val array = buttons()
        val hr = tb.vcall(ThumbBarAddButtons, window.pointer, 3, array[0].pointer)
        added = hr.ok()
        status = if (added) "on" else "waiting for the taskbar (${hr.hex()})"
        if (added) {
            val drawn = array.count { it.hIcon != null }
            Log.i(Tag, "Taskbar buttons on, $drawn of 3 pictures, ${if (lightTaskbar == true) "dark glyphs for a light" else "white glyphs for a dark"} taskbar")
        }
    }

    private fun updateButtons() {
        val tb = taskbar ?: return
        val window = hwnd ?: return
        val array = buttons()
        tb.vcall(ThumbBarUpdateButtons, window.pointer, 3, array[0].pointer)
    }

    /**
     * Glyphs like the app's own, drawn at the size Windows uses for small icons on this screen:
     * white on a dark taskbar, near black on a light one.
     */
    private fun icon(name: String): WinDef.HICON? = glyphs[name] ?: runCatching {
        val size = User32.INSTANCE.GetSystemMetrics(49 /* SM_CXSMICON */).takeIf { it in 12..96 } ?: 16
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = if (lightTaskbar == true) java.awt.Color(0x1C, 0x1C, 0x1E) else java.awt.Color.WHITE
        val s = size.toDouble()
        fun triangle(x0: Double, x1: Double, pointsRight: Boolean) = Path2D.Double().apply {
            val top = s * 0.2
            val bottom = s * 0.8
            if (pointsRight) {
                moveTo(x0, top); lineTo(x1, s / 2); lineTo(x0, bottom)
            } else {
                moveTo(x1, top); lineTo(x0, s / 2); lineTo(x1, bottom)
            }
            closePath()
        }
        g.stroke = BasicStroke((s / 16).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        when (name) {
            "play" -> triangle(s * 0.28, s * 0.82, true).also { g.fill(it); g.draw(it) }
            "pause" -> {
                g.fill(RoundRectangle2D.Double(s * 0.25, s * 0.2, s * 0.17, s * 0.6, s * 0.08, s * 0.08))
                g.fill(RoundRectangle2D.Double(s * 0.58, s * 0.2, s * 0.17, s * 0.6, s * 0.08, s * 0.08))
            }
            "next" -> listOf(triangle(s * 0.12, s * 0.5, true), triangle(s * 0.5, s * 0.88, true)).forEach { g.fill(it); g.draw(it) }
            else -> listOf(triangle(s * 0.12, s * 0.5, false), triangle(s * 0.5, s * 0.88, false)).forEach { g.fill(it); g.draw(it) }
        }
        g.dispose()
        toIcon(image)?.also { glyphs[name] = it }
    }.onFailure { Log.w(Tag, "Couldn't draw the $name button", it) }.getOrNull()

    /**
     * An icon made the way Windows reads one from an .ico file: a 32-bit image with its alpha, and a
     * real transparency mask under it. Built by hand with CreateIconIndirect, the mask was left
     * undefined, and the taskbar showed the buttons without their pictures.
     */
    private fun toIcon(image: BufferedImage): WinDef.HICON? {
        val w = image.width
        val h = image.height
        val argb = image.getRGB(0, 0, w, h, null, 0, w)
        val maskStride = ((w + 31) / 32) * 4
        val colorBytes = w * h * 4
        val maskBytes = maskStride * h
        val buffer = java.nio.ByteBuffer.allocate(40 + colorBytes + maskBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        // BITMAPINFOHEADER: the height counts the image and the mask together, as in an .ico.
        buffer.putInt(40).putInt(w).putInt(h * 2).putShort(1).putShort(32).putInt(0)
            .putInt(colorBytes + maskBytes).putInt(0).putInt(0).putInt(0).putInt(0)
        // Rows go bottom up; each pixel is blue, green, red, alpha.
        for (y in h - 1 downTo 0) for (x in 0 until w) {
            val p = argb[y * w + x]
            buffer.put((p and 0xFF).toByte()).put((p shr 8 and 0xFF).toByte())
                .put((p shr 16 and 0xFF).toByte()).put((p ushr 24).toByte())
        }
        // The mask: a set bit is see-through.
        for (y in h - 1 downTo 0) {
            val row = ByteArray(maskStride)
            for (x in 0 until w) if (argb[y * w + x] ushr 24 == 0) row[x / 8] = (row[x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
            buffer.put(row)
        }
        val bytes = buffer.array()
        val icon = icons.CreateIconFromResourceEx(bytes, bytes.size, true, 0x00030000, w, h, 0)
        if (icon == null) Log.w(Tag, "Windows refused a ${w}px taskbar button picture (${Kernel32.INSTANCE.GetLastError()})")
        return icon
    }

    /** Without Windows' media controls, the keys are taken as hotkeys instead, which works everywhere but can't be shared. */
    private fun registerHotkeys() {
        val keys = listOf(MediaControls.VK_MEDIA_PLAY_PAUSE, MediaControls.VK_MEDIA_NEXT_TRACK, MediaControls.VK_MEDIA_PREV_TRACK, MediaControls.VK_MEDIA_STOP)
        val taken = keys.filter { User32.INSTANCE.RegisterHotKey(null, it, 0, it) }
        Log.i(Tag, "Media keys as hotkeys: ${taken.size} of ${keys.size}")
    }

    private fun hotkey(id: Int) {
        val command = when (id) {
            MediaControls.VK_MEDIA_PLAY_PAUSE -> MediaControls.Command.TOGGLE
            MediaControls.VK_MEDIA_NEXT_TRACK -> MediaControls.Command.NEXT
            MediaControls.VK_MEDIA_PREV_TRACK -> MediaControls.Command.PREVIOUS
            MediaControls.VK_MEDIA_STOP -> MediaControls.Command.STOP
            else -> return
        }
        MediaControls.run(command, "media key (hotkey)")
    }

    fun close() {
        val tb = taskbar ?: return
        post {
            tb.release()
            taskbar = null
            glyphs.values.forEach { icons.DestroyIcon(it) }
            glyphs.clear()
        }
    }
}
