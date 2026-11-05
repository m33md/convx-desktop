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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
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
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.Dispatchers
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

@OptIn(FlowPreview::class)
fun main() {
    Log.i("Startup", "Convx ${AppInfo.VERSION} on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, Java ${System.getProperty("java.version")}")
    Thread.setDefaultUncaughtExceptionHandler { thread, error -> Log.crash(thread, error) }
    // A second Convx would write its own copy of the library over this one's; bring this one up instead.
    if (!com.convx.desktop.core.SingleInstance.claim()) {
        Log.i("Startup", "Convx is already open; brought its window up instead")
        kotlin.system.exitProcess(0)
    }

    // Before any HTTP client exists, so they all go through the proxy if one is set.
    Network.apply(AppSettings.value)
    AppScope.launch(Dispatchers.IO) { initYouTube() }
    AppScope.launch(Dispatchers.IO) {
        AudioCache.cleanupPartials()
        AudioCache.trim()
        ImageCache.trimDisk()
        AudioEngine.warmUp()
    }
    com.convx.desktop.ui.theme.AppFonts.start()
    PlayerController.restore()
    DiscordPresence.start()
    SpotifyAccount.restore()
    AppScope.launch(Dispatchers.IO) { LastFm.restore() }
    Scrobbler.start()
    YouTubeSync.start()
    PlaylistImport.init()
    if (AppSettings.value.scanLocalOnStart) LocalMusic.scan()
    Party.start()
    Downloads.start()

    val icon = Thread.currentThread().contextClassLoader.getResourceAsStream("convx.png")
        ?.use { BitmapPainter(Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap()) }

    application {
        val saved = remember { AppSettings.value }
        val windowState = rememberWindowState(
            placement = if (saved.windowMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = WindowPosition(Alignment.Center),
            size = DpSize(saved.windowWidth.coerceAtLeast(940).dp, saved.windowHeight.coerceAtLeast(600).dp),
        )
        val navigator = remember { Navigator(com.convx.desktop.ui.shell.startScreen(saved.defaultTab)) }

        fun quit() {
            com.convx.desktop.integrations.MediaControls.shutdown()
            Party.shutdown()
            DiscordPresence.shutdown()
            PlayerController.shutdown()
            Library.flush()
            SpotifyMatcher.flush()
            YouTubeSync.flush()
            LocalMusic.flush()
            Downloads.flush()
            AppSettings.flush()
            com.convx.desktop.lyrics.LyricsTweaks.flush()
            exitApplication()
        }
        com.convx.desktop.core.Updater.quit = ::quit

        Window(
            onCloseRequest = ::quit,
            state = windowState,
            title = "Convx",
            icon = icon,
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
                window.minimumSize = Dimension(940, 600)
                window.background = java.awt.Color.BLACK
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
                // Media keys, the Windows media box and the taskbar preview's buttons, once the frame is settled.
                withContext(Dispatchers.IO) { com.convx.desktop.integrations.MediaControls.install(window) }
            }
            // Ambient mode and F11 fill the screen (ambient also keeps it on, as on the phone), then put the window back.
            val beforeFullscreen = remember { arrayOfNulls<WindowPlacement>(1) }
            val wantFullscreen = Ambient.on || UiState.windowFullscreen
            LaunchedEffect(wantFullscreen) {
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
                        if (placement == WindowPlacement.Fullscreen) return@collect
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

/** Window-wide shortcuts, Spotify's set where it has one. */
private fun shortcut(event: KeyEvent, navigator: Navigator): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    // The DIY editor's own keys (Esc, undo, redo, save, delete) come first while it's open.
    if (com.convx.desktop.ui.player.DiyEditor.key(event)) return true
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
}
