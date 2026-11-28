/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.convx.desktop.core.AppInfo
import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Log
import com.convx.desktop.core.Network
import com.convx.desktop.core.initYouTube
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.integrations.party.Party
import com.convx.desktop.integrations.DiscordPresence
import com.convx.desktop.integrations.LastFm
import com.convx.desktop.integrations.PlaylistImport
import com.convx.desktop.integrations.Scrobbler
import com.convx.desktop.integrations.youtube.YouTubeSync
import com.convx.desktop.integrations.spotify.SpotifyAccount
import com.convx.desktop.integrations.spotify.SpotifyMatcher
import com.convx.desktop.player.AudioCache
import com.convx.desktop.player.LocalCopies
import com.convx.desktop.player.LocalMusic
import com.convx.desktop.player.AudioEngine
import com.convx.desktop.player.Downloads
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.ImageCache
import com.convx.desktop.ui.player.Ambient
import com.convx.desktop.ui.screens.replay.Replay
import com.convx.desktop.ui.shell.AppShell
import com.convx.desktop.ui.shell.Navigator
import com.convx.desktop.ui.shell.TopBarHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.shell.FullPlayerMode
import com.convx.desktop.ui.shell.WindowTitleBar
import com.sun.jna.Library as JnaLibrary
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.Window as AwtWindow

fun main() {
    // Anything that stops Convx from starting is explained, as a crash in a window is (CrashExit).
    try {
        start()
    } catch (error: Throwable) {
        CrashExit.close(error)
    }
}

@OptIn(FlowPreview::class)
private fun start() {
    Log.i(
        "Startup",
        "Convx ${AppInfo.VERSION} on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, " +
            "Java ${System.getProperty("java.version")}, ${AudioCache.freeBytes()?.let { "${it / 1_073_741_824} GB" } ?: "unknown space"} free",
    )
    Thread.setDefaultUncaughtExceptionHandler { thread, error -> Log.crash(thread, error) }
    // A second Convx would write its own copy of the library over this one's; bring this one up instead.
    if (!com.convx.desktop.core.SingleInstance.claim()) {
        Log.i("Startup", "Convx is already open; brought its window up instead")
        kotlin.system.exitProcess(0)
    }

    // Each part starts on its own: one that fails (something it saved it can't read, say) is
    // logged and left out, and Convx opens without it rather than not at all.
    fun part(name: String, block: () -> Unit) = runCatching(block).onFailure { Log.e("Startup", "$name didn't start", it) }
    // Before any HTTP client exists, so they all go through the proxy if one is set.
    part("Network") { Network.apply(AppSettings.value) }
    part("Module streams") { com.convx.desktop.player.ModuleStreams.init() }
    AppScope.launch(Dispatchers.IO) { initYouTube() }
    AppScope.launch(Dispatchers.IO) {
        AudioCache.cleanupPartials()
        AudioCache.trim()
        ImageCache.trimDisk()
        AudioEngine.warmUp()
    }
    part("Fonts") { com.convx.desktop.ui.theme.AppFonts.start() }
    part("Queue") { PlayerController.restore() }
    part("Discord") { DiscordPresence.start() }
    part("Spotify") { SpotifyAccount.restore() }
    AppScope.launch(Dispatchers.IO) { LastFm.restore() }
    part("Scrobbling") { Scrobbler.start() }
    part("YouTube Music sync") { YouTubeSync.start() }
    part("Playlist import") { PlaylistImport.init() }
    part("Local files") {
        if (AppSettings.value.scanLocalOnStart) LocalMusic.scan() else LocalMusic.repairCovers()
        LocalCopies.refresh()
    }
    part("Listen together") { Party.start() }
    part("Downloads") { Downloads.start() }

    val icon = Thread.currentThread().contextClassLoader.getResourceAsStream("convx.png")
        ?.use { BitmapPainter(Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap()) }

    application {
        val saved = remember { AppSettings.value }
        val windowState = rememberWindowState(
            placement = if (saved.windowMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = WindowPosition(Alignment.Center),
            size = minimumWindowSize().let { min ->
                val screen = screenWorkArea()
                DpSize(
                    saved.windowWidth.coerceAtLeast(min.width).let { if (screen != null) it.coerceAtMost(screen.width) else it }.dp,
                    saved.windowHeight.coerceAtLeast(min.height).let { if (screen != null) it.coerceAtMost(screen.height) else it }.dp,
                )
            },
        )
        val navigator = remember { Navigator(com.convx.desktop.ui.shell.startScreen(saved.defaultTab)) }

        fun quit() {
            stopAndSave()
            exitApplication()
        }
        com.convx.desktop.core.Updater.quit = ::quit

        // A window that fails to draw closes Convx with one plain message instead of Compose's
        // error box followed by the Windows launcher's "Failed to launch JVM".
        CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides CrashExit) {
            Window(
                onCloseRequest = ::quit,
                state = windowState,
                title = "Convx",
                icon = icon,
                // On a Mac the window is made by macOS (see MacWindow): transparent here, titled there.
                undecorated = com.convx.desktop.integrations.MacWindow.isMac,
                transparent = com.convx.desktop.integrations.MacWindow.isMac,
                onPreviewKeyEvent = { shortcut(it, navigator) },
                onKeyEvent = { event ->
                    // A text field takes the typed space but lets the key press through, so check.
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Spacebar && !event.isCtrlPressed && UiState.typingIn == null) {
                        PlayerController.togglePlay()
                        true
                    } else {
                        false
                    }
                },
            ) {
                LaunchedEffect(Unit) {
                    com.convx.desktop.core.SingleInstance.window = window
                    window.minimumSize = minimumWindowSize()
                    if (!com.convx.desktop.integrations.MacWindow.isMac) window.background = java.awt.Color.BLACK
                    // Every size of the icon, so the title bar and taskbar get a sharp one (the small
                    // ones drop the sound bars, which blur away) instead of the big one shrunk.
                    runCatching {
                        val loader = Thread.currentThread().contextClassLoader
                        val sizes = listOf(16, 20, 24, 32, 40, 48, 64, 256).mapNotNull { size ->
                            loader.getResourceAsStream("app-icon/$size.png")?.use { javax.imageio.ImageIO.read(it) }
                        }
                        if (sizes.isNotEmpty()) window.iconImages = sizes
                    }
                    WindowsChrome.darken(window)
                    MacChrome.style(window)
                    if (com.convx.desktop.integrations.MacWindow.isMac) {
                        delay(300)
                        com.convx.desktop.integrations.MacWindow.install("Convx", window.width, window.height)
                    }
                    // Spotify's window: the top bar is the title bar. Falls back to the dark Windows one.
                    WindowTitleBar.install(window, TopBarHeight)
                    window.addComponentListener(object : ComponentAdapter() {
                        override fun componentResized(e: ComponentEvent?) = WindowTitleBar.updateInsets()
                        override fun componentShown(e: ComponentEvent?) = WindowTitleBar.updateInsets()
                    })
                    // The buttons' width settles once the frame is on screen.
                    repeat(10) {
                        delay(100)
                        WindowTitleBar.updateInsets()
                    }
                    com.convx.desktop.core.Perf.watch(window)
                    com.convx.desktop.core.Memory.start()
                    // A finger on a touch screen, as touch rather than as the runtime's wheel steps.
                    com.convx.desktop.integrations.TouchInput.install(window)
                    // Media keys, the Windows media box and the taskbar preview's buttons, once the frame is settled.
                    withContext(Dispatchers.IO) { com.convx.desktop.integrations.MediaControls.install(window) }
                }
                // Views that move on their own stop while minimised and slow down behind other apps (see Motion.kt).
                val focused = LocalWindowInfo.current.isWindowFocused
                LaunchedEffect(focused) {
                    UiState.windowFocused = focused
                    if (focused) YouTubeSync.refreshOnReturn()
                }
                LaunchedEffect(windowState) { snapshotFlow { windowState.isMinimized }.collect { UiState.windowShown = !it } }
                // Ambient mode and F11 fill the screen (ambient also keeps it on, as on the phone), then put the window back.
                val beforeFullscreen = remember { arrayOfNulls<WindowPlacement>(1) }
                val borderless = remember { booleanArrayOf(false) }
                val wantFullscreen = Ambient.on || UiState.windowFullscreen
                LaunchedEffect(wantFullscreen) {
                    // Windows fills the monitor edge to edge itself (see WindowsChrome.fullscreen), off
                    // the event thread, and finishes even if F11 is pressed again meanwhile.
                    val handled = withContext(NonCancellable) {
                        if (wantFullscreen && !borderless[0]) {
                            borderless[0] = withContext(Dispatchers.IO) { WindowsChrome.fullscreen(window, true) }
                            if (borderless[0]) WindowTitleBar.showFullscreen(true)
                            borderless[0]
                        } else if (!wantFullscreen && borderless[0]) {
                            WindowTitleBar.showFullscreen(false)
                            withContext(Dispatchers.IO) { WindowsChrome.fullscreen(window, false) }
                            borderless[0] = false
                            true
                        } else {
                            borderless[0]
                        }
                    }
                    if (handled) return@LaunchedEffect
                    if (wantFullscreen) {
                        if (windowState.placement != WindowPlacement.Fullscreen) beforeFullscreen[0] = windowState.placement
                        windowState.placement = WindowPlacement.Fullscreen
                    } else {
                        beforeFullscreen[0]?.let { windowState.placement = it }
                        beforeFullscreen[0] = null
                    }
                }
                LaunchedEffect(Ambient.on) { WindowsChrome.keepAwake(Ambient.on) }
                // The phone's automatic update check: once a launch, a little after the window opens.
                LaunchedEffect(Unit) {
                    if (!AppSettings.value.checkForUpdates || AppInfo.COMMIT == "dev") return@LaunchedEffect
                    delay(20_000)
                    val result = com.convx.desktop.core.Updater.check()
                    if (result is com.convx.desktop.core.Updater.State.Available) {
                        com.convx.desktop.ui.components.Overlays.toast("Convx ${result.release.version} is available", "download", action = "See what's new") {
                            navigator.navigate(com.convx.desktop.ui.shell.Screen.Settings)
                            com.convx.desktop.ui.screens.settings.SettingsNav.push(com.convx.desktop.ui.screens.settings.SettingsPage.Updates)
                        }
                    }
                }
                // The phone's "Disable screenshot": the window drops out of screenshots, recordings and screen shares.
                LaunchedEffect(Unit) {
                    AppSettings.state.map { it.disableScreenshot }.distinctUntilChanged().collect { WindowsChrome.hideFromCapture(window, it) }
                }
                LaunchedEffect(windowState) {
                    snapshotFlow { Triple(windowState.size, windowState.placement, Unit) }
                        .debounce(600)
                        .collect { (size, placement) ->
                            // Ambient mode's full screen is a visit, not where the window lives.
                            if (placement == WindowPlacement.Fullscreen || Ambient.on || UiState.windowFullscreen || borderless[0]) return@collect
                            AppSettings.update {
                                if (placement == WindowPlacement.Maximized) {
                                    it.copy(windowMaximized = true)
                                } else {
                                    it.copy(
                                        windowMaximized = false,
                                        windowWidth = size.width.value.toInt(),
                                        windowHeight = size.height.value.toInt(),
                                    )
                                }
                            }
                        }
                }
                // The phone's display density: everything drawn smaller than Windows' own scale, down to 55%.
                val scale = AppSettings.state.collectAsState().value.displayDensity
                val base = LocalDensity.current
                LaunchedEffect(scale) { WindowTitleBar.rescale(scale, TopBarHeight) }
                CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
                    AppShell(navigator)
                }
            }
        }
    }
}

/**
 * Stops playback, the integrations and media keys, and saves everything, before Convx closes.
 * Each step goes ahead even if one before it fails, so a stuck part never costs the library.
 */
private fun stopAndSave() {
    fun step(name: String, block: () -> Unit) = runCatching(block).onFailure { Log.e("Quit", "Couldn't finish $name while closing", it) }
    step("media controls") { com.convx.desktop.integrations.MediaControls.shutdown() }
    step("listen together") { Party.shutdown() }
    step("Discord") { DiscordPresence.shutdown() }
    step("the player") { PlayerController.shutdown() }
    step("the library") { Library.flush() }
    step("Spotify matches") { SpotifyMatcher.flush() }
    step("YouTube Music sync") { YouTubeSync.flush() }
    step("local files") { LocalMusic.flush() }
    step("local song covers") { com.convx.desktop.integrations.LocalSongCovers.flush() }
    step("downloads") { Downloads.flush() }
    step("settings") { AppSettings.flush() }
    step("lyrics tweaks") { com.convx.desktop.lyrics.LyricsTweaks.flush() }
}

/**
 * What happens when drawing a window fails: the error and the log before it go into the crash
 * report, one message says what happened and where that report is, and Convx saves what it can
 * and closes. Exiting from here, rather than letting the error reach main(), is what keeps the
 * Windows launcher's own "Failed to launch JVM" box away.
 */
private object CrashExit : WindowExceptionHandlerFactory {
    private val handled = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun exceptionHandler(window: AwtWindow): WindowExceptionHandler = object : WindowExceptionHandler {
        // After the events already queued for the window, some of which may fail too.
        override fun onException(throwable: Throwable) = javax.swing.SwingUtilities.invokeLater { close(throwable) }
    }

    /** Reports [error], says so, saves what it can and ends Convx. */
    fun close(error: Throwable) {
        // Frames keep failing while the message is up; the first error is the one that counts.
        if (!handled.compareAndSet(false, true)) return
        Log.crash(Thread.currentThread(), error)
        val detail = error.message?.takeIf { it.isNotBlank() }?.take(300) ?: error.javaClass.simpleName
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            runCatching {
                javax.swing.JOptionPane.showMessageDialog(
                    null,
                    "Convx ran into a problem and has to close.\n\n$detail\n\n" +
                        "A crash report was saved to:\n${Log.crashFile.absolutePath}",
                    "Convx",
                    javax.swing.JOptionPane.ERROR_MESSAGE,
                )
            }
        }
        stopAndSave()
        kotlin.system.exitProcess(1)
    }
}

/** The screen's room for windows (without the taskbar), in the same units as the window's size. */
private fun screenWorkArea(): java.awt.Rectangle? =
    runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()
        ?.takeIf { it.width > 0 && it.height > 0 }

/**
 * 940 by 600, or less on a screen that hasn't that much room (1366 by 768 at 125% has 582 high
 * above the taskbar): a minimum bigger than the screen leaves a maximised window running off it.
 */
private fun minimumWindowSize(): Dimension {
    val screen = screenWorkArea() ?: return Dimension(940, 600)
    return Dimension(minOf(940, screen.width), minOf(600, screen.height))
}

/** Window-wide shortcuts, Spotify's set where it has one. */
private fun shortcut(event: KeyEvent, navigator: Navigator): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    // The DIY editor's own keys (Esc, undo, redo, save, delete) come first while it's open.
    if (com.convx.desktop.ui.player.DiyEditor.key(event)) return true
    // So do Disc Studio's (Esc, save, undo, redo, delete, nudge, spin).
    if (com.convx.desktop.ui.studio.DiscEditor.key(event)) return true
    val ctrl = event.isCtrlPressed
    // Replay takes the keys a story needs while it's open; the rest still reach the app.
    if (Replay.open && !ctrl) {
        val handled = when (event.key) {
            Key.Escape -> { Replay.close(); true }
            Key.DirectionLeft -> { Replay.go(Replay.page - 1); true }
            Key.DirectionRight -> { Replay.go(Replay.page + 1); true }
            Key.Spacebar -> { Replay.stopped = !Replay.stopped; true }
            Key.M -> { Replay.toggleMute(); true }
            else -> false
        }
        if (handled) return true
    }
    return when {
        // Esc steps back out of full-screen lyrics first: to the lyrics beside the cover, or with
        // one-tap on (where that middle step isn't used) to the cover.
        event.key == Key.Escape && Ambient.on -> {
            Ambient.on = false
            true
        }
        // Then out of F11's full screen, before the player closes.
        event.key == Key.Escape && UiState.windowFullscreen -> {
            UiState.windowFullscreen = false
            true
        }
        event.key == Key.Escape && UiState.fullPlayer && UiState.lyricsFullscreen && UiState.fullPlayerMode == FullPlayerMode.Lyrics -> {
            UiState.lyricsFullscreen = false
            if (AppSettings.value.oneTapFullscreenLyrics) UiState.fullPlayerMode = FullPlayerMode.Artwork
            true
        }
        event.key == Key.Escape && UiState.fullPlayer -> {
            UiState.fullPlayer = false
            true
        }
        // The whole screen, taskbar hidden, as F11 does in a browser; the player keeps whatever it shows.
        event.key == Key.F11 -> {
            UiState.windowFullscreen = !UiState.windowFullscreen
            true
        }
        // Settings, now that the picture at the top opens your profile.
        ctrl && event.key == Key.Comma -> {
            UiState.fullPlayer = false
            navigator.navigate(com.convx.desktop.ui.shell.Screen.Settings)
            true
        }
        ctrl && (event.key == Key.L || event.key == Key.K) -> {
            UiState.fullPlayer = false
            // The top bar's field opens Search as it takes the keyboard.
            runCatching { UiState.searchFocus.requestFocus() }
            true
        }
        ctrl && event.key == Key.DirectionRight -> {
            if (event.isShiftPressed) PlayerController.seekBy(10_000) else PlayerController.next()
            true
        }
        ctrl && event.key == Key.DirectionLeft -> {
            if (event.isShiftPressed) PlayerController.seekBy(-10_000) else PlayerController.previous()
            true
        }
        ctrl && event.key == Key.DirectionUp -> {
            PlayerController.setVolume((PlayerController.state.value.volume + 0.1f).coerceAtMost(1f))
            true
        }
        ctrl && event.key == Key.DirectionDown -> {
            PlayerController.setVolume((PlayerController.state.value.volume - 0.1f).coerceAtLeast(0f))
            true
        }
        ctrl && event.key == Key.S -> {
            PlayerController.toggleShuffle()
            true
        }
        ctrl && event.key == Key.R -> {
            PlayerController.cycleRepeat()
            true
        }
        event.isAltPressed && event.key == Key.DirectionLeft -> {
            navigator.back()
            true
        }
        event.isAltPressed && event.key == Key.DirectionRight -> {
            navigator.forward()
            true
        }
        else -> false
    }
}

/** macOS: a dark title bar, and the screen kept on while ambient mode is. */
private object MacChrome {
    private val mac = System.getProperty("os.name").lowercase().contains("mac")

    fun style(window: java.awt.Window) {
        if (!mac) return
        runCatching {
            (window as? javax.swing.RootPaneContainer)?.rootPane?.apply {
                putClientProperty("apple.awt.windowAppearance", "NSAppearanceNameDarkAqua")
            }
        }.onFailure { Log.w("Window", "Couldn't darken the title bar", it) }
    }

    private var assertion: Int = 0

    /** IOPMAssertion: the display stays on and the Mac stays awake while [on]. */
    fun keepAwake(on: Boolean) {
        if (!mac) return
        runCatching {
            val iokit = NativeLibrary.getInstance("IOKit")
            val cf = NativeLibrary.getInstance("CoreFoundation")
            if (on && assertion == 0) {
                val text = "Convx ambient mode".toByteArray()
                val buffer = Memory(text.size + 1L).apply { write(0, text, 0, text.size); setByte(text.size.toLong(), 0) }
                val name = cf.getFunction("CFStringCreateWithCString").invokePointer(arrayOf(null, buffer, 0x08000100))
                val type = cf.getFunction("CFStringCreateWithCString").invokePointer(arrayOf(null, Memory(32).apply { setString(0, "PreventUserIdleDisplaySleep") }, 0x08000100))
                val id = IntByReference()
                // kIOPMAssertionLevelOn = 255
                val rc = iokit.getFunction("IOPMAssertionCreateWithName").invokeInt(arrayOf(type, 255, name, id))
                if (rc == 0) assertion = id.value
            } else if (!on && assertion != 0) {
                iokit.getFunction("IOPMAssertionRelease").invokeInt(arrayOf(assertion))
                assertion = 0
            }
        }.onFailure { Log.w("Window", "Couldn't keep the screen on", it) }
    }
}

/** Windows 10/11: a dark title bar that matches the app instead of a white strip. */
private object WindowsChrome {
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36

    private interface Dwm : JnaLibrary {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: Pointer, size: Int): Int
    }

    private val dwm: Dwm? by lazy {
        if (!System.getProperty("os.name").lowercase().contains("win")) null
        else runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull()
    }

    private interface User32 : JnaLibrary {
        fun SetWindowDisplayAffinity(hwnd: WinDef.HWND, affinity: Int): Boolean
    }

    private val user32: User32? by lazy {
        if (!System.getProperty("os.name").lowercase().contains("win")) null
        else runCatching { Native.load("user32", User32::class.java) }.getOrNull()
    }

    private interface Kernel32 : JnaLibrary {
        fun SetThreadExecutionState(flags: Int): Int
    }

    private val kernel32: Kernel32? by lazy {
        if (!System.getProperty("os.name").lowercase().contains("win")) null
        else runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull()
    }

    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x1
    private const val ES_DISPLAY_REQUIRED = 0x2

    /**
     * Keeps the display on (and the PC awake) while [on]. Windows ties the request to the thread
     * that made it, so it's made on the window's event thread, which lasts as long as the app.
     */
    fun keepAwake(on: Boolean) {
        MacChrome.keepAwake(on)
        val api = kernel32 ?: return
        java.awt.EventQueue.invokeLater {
            runCatching { api.SetThreadExecutionState(if (on) ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED else ES_CONTINUOUS) }
                .onFailure { Log.w("Window", "Couldn't keep the screen on", it) }
        }
    }

    private const val WDA_NONE = 0
    private const val WDA_MONITOR = 1
    private const val WDA_EXCLUDEFROMCAPTURE = 0x11

    /**
     * Leaves the window out of captures. Windows 10 2004 and later skip it altogether; older
     * versions only know how to black it out, which still keeps what's on it private.
     */
    fun hideFromCapture(window: AwtWindow, hidden: Boolean) {
        val api = user32 ?: return
        runCatching {
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            val ok = api.SetWindowDisplayAffinity(hwnd, if (hidden) WDA_EXCLUDEFROMCAPTURE else WDA_NONE)
            if (!ok && hidden && !api.SetWindowDisplayAffinity(hwnd, WDA_MONITOR)) Log.w("Window", "Windows refused to hide the window from captures")
        }.onFailure { Log.w("Window", "Couldn't change screenshot protection", it) }
    }

    fun darken(window: AwtWindow) {
        val api = dwm ?: return
        runCatching {
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            api.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, IntByReference(1).pointer, 4)
            // COLORREF is 0x00BBGGRR. Windows 10 ignores these two; 11 paints the caption to match.
            api.DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, IntByReference(0x000000).pointer, 4)
            api.DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, IntByReference(0xFFFFFF).pointer, 4)
        }.onFailure { Log.w("Window", "Couldn't darken the title bar", it) }
    }

    private const val GWL_STYLE = -16
    private const val WS_OVERLAPPEDWINDOW = 0x00CF0000
    private const val WS_MAXIMIZE = 0x01000000
    private const val MONITOR_DEFAULTTONEAREST = 2
    private const val SWP_NOSIZE = 0x1
    private const val SWP_NOMOVE = 0x2
    private const val SWP_NOZORDER = 0x4
    private const val SWP_FRAMECHANGED = 0x20
    private const val SWP_NOOWNERZORDER = 0x200
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWA_BORDER_COLOR = 34
    private const val DWMWCP_DEFAULT = 0
    private const val DWMWCP_DONOTROUND = 1
    private const val DWMWA_COLOR_NONE = 0xFFFFFFFE.toInt()
    private const val DWMWA_COLOR_DEFAULT = 0xFFFFFFFF.toInt()

    private const val SW_SHOWMINIMIZED = 2
    private const val SW_SHOWMAXIMIZED = 3

    /** The window before full screen, to put back after. */
    private class Windowed(val style: Int, val placement: com.sun.jna.platform.win32.WinUser.WINDOWPLACEMENT, val rect: WinDef.RECT)

    private var windowed: Windowed? = null

    /**
     * Windows full screen, as browsers and video players do it: the frame's border and title bar
     * styles come off and the window covers its whole monitor, square and without the thin
     * border. Java's own full screen keeps the frame, so the title bar showed and its invisible
     * resize border left strips of the desktop along the edges. False when this isn't Windows (or
     * Windows refused), and the caller falls back to Compose's full screen.
     */
    @Synchronized
    fun fullscreen(window: AwtWindow, on: Boolean): Boolean {
        if (dwm == null) return false
        val api = com.sun.jna.platform.win32.User32.INSTANCE
        return runCatching {
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            if (on) {
                if (windowed != null) return@runCatching true
                val style = api.GetWindowLong(hwnd, GWL_STYLE)
                val placement = com.sun.jna.platform.win32.WinUser.WINDOWPLACEMENT()
                val rect = WinDef.RECT()
                val monitor = com.sun.jna.platform.win32.WinUser.MONITORINFO()
                if (!api.GetWindowPlacement(hwnd, placement).booleanValue() || !api.GetWindowRect(hwnd, rect)) return@runCatching false
                if (!api.GetMonitorInfo(api.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST), monitor).booleanValue()) return@runCatching false
                // A window down in the taskbar is left to Compose's way, as before.
                if (placement.showCmd == SW_SHOWMINIMIZED) return@runCatching false
                windowed = Windowed(style, placement, rect)
                // Not maximised either: a maximised window keeps room for the frame at the top.
                api.SetWindowLong(hwnd, GWL_STYLE, style and WS_OVERLAPPEDWINDOW.inv() and WS_MAXIMIZE.inv())
                corners(hwnd, square = true)
                val r = monitor.rcMonitor
                api.SetWindowPos(hwnd, null, r.left, r.top, r.right - r.left, r.bottom - r.top, SWP_NOOWNERZORDER or SWP_FRAMECHANGED)
            } else {
                val before = windowed ?: return@runCatching true
                windowed = null
                // Maximised again by the placement, which does it properly only from a window that isn't.
                api.SetWindowLong(hwnd, GWL_STYLE, before.style and WS_MAXIMIZE.inv())
                api.SetWindowPlacement(hwnd, before.placement)
                corners(hwnd, square = false)
                val r = before.rect
                if (before.placement.showCmd == SW_SHOWMAXIMIZED) {
                    api.SetWindowPos(hwnd, null, 0, 0, 0, 0, SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOOWNERZORDER or SWP_FRAMECHANGED)
                } else {
                    // Back where it was. The placement alone would undo a snap to half the screen, as it
                    // holds where the window was before it was snapped.
                    api.SetWindowPos(hwnd, null, r.left, r.top, r.right - r.left, r.bottom - r.top, SWP_NOZORDER or SWP_NOOWNERZORDER or SWP_FRAMECHANGED)
                }
            }
            true
        }.onFailure { Log.w("Window", "Couldn't ${if (on) "fill" else "leave"} the screen", it) }.getOrDefault(false)
    }

    /** Windows 11 rounds a window's corners and draws a thin border; neither belongs on a full screen. Windows 10 ignores both. */
    private fun corners(hwnd: WinDef.HWND, square: Boolean) {
        val api = dwm ?: return
        api.DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, IntByReference(if (square) DWMWCP_DONOTROUND else DWMWCP_DEFAULT).pointer, 4)
        api.DwmSetWindowAttribute(hwnd, DWMWA_BORDER_COLOR, IntByReference(if (square) DWMWA_COLOR_NONE else DWMWA_COLOR_DEFAULT).pointer, 4)
    }
}
