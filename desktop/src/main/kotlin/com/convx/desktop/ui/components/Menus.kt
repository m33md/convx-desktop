/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import com.convx.desktop.integrations.youtube.YouTubePlaylists
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.Song
import com.convx.desktop.data.toItem
import com.convx.desktop.player.Downloads
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.glass.LocalMenuBackdrop
import com.convx.desktop.ui.glass.MenuGlass
import com.convx.desktop.ui.glass.liquidGlass
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.convx.desktop.ui.theme.ConvxMotion
import com.kyant.backdrop.Backdrop
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where a menu opens: under its button, or at the right-click point. */
class MenuAnchor {
    var expanded by mutableStateOf(false)
    var offset by mutableStateOf(DpOffset.Zero)
}

@Composable
fun rememberMenuAnchor() = remember { MenuAnchor() }

/** Opens [anchor] at a right-click [position] (px, relative to the clicked element). */
@Composable
fun MenuAnchor.openAtProvider(): (Offset) -> Unit {
    val density = LocalDensity.current
    return { position ->
        with(density) { offset = DpOffset(position.x.toDp(), position.y.toDp()) }
        expanded = true
    }
}

/**
 * A menu of liquid glass over the page. Its glass grows out of the button that opened it (or the
 * spot that was right-clicked) and settles with a little give while its rows come in one after
 * another; closing pulls it back into the button.
 */
@Composable
fun AppMenu(anchor: MenuAnchor, content: @Composable () -> Unit) {
    val backdrop = LocalMenuBackdrop.current
    // The phone's sheet corner radius, which its menus are drawn on.
    val corner = AppSettings.state.collectAsState().value.menuCorner
    val shape = if (corner >= 0) RoundedCornerShape(corner.dp) else MenuShape
    val density = LocalDensity.current
    val morph = remember { MenuMorph() }
    val fromButton = anchor.offset == DpOffset.Zero
    LaunchedEffect(anchor.expanded) { morph.run(anchor.expanded) }
    // A zero-size box at the click point gives the popup something to hang from; the box it sits
    // in is the button the menu grows out of.
    Box(
        Modifier.offset(anchor.offset.x, anchor.offset.y).size(0.dp).onGloballyPositioned { c ->
            morph.point = c.positionInWindow()
            // A button that has just hidden (its row no longer hovered as the menu closes) keeps
            // the place it had, so the menu still goes back into it.
            c.parentLayoutCoordinates?.takeIf { it.size.width > 0 && it.size.height > 0 }?.let {
                morph.host = Rect(it.positionInWindow(), it.size.toSize())
            }
        },
    ) {
        if (morph.showing) {
            val placement = remember(fromButton, density) { MenuPlacement(morph, fromButton, density) }
            Popup(
                popupPositionProvider = placement,
                onDismissRequest = { anchor.expanded = false },
                properties = PopupProperties(focusable = true),
                onKeyEvent = { event -> morph.moveFocus(event) },
            ) {
                morph.focus = LocalFocusManager.current
                MorphingMenu(morph, placement, shape, backdrop, content)
            }
        }
    }
}

/** The phone's menus round off at 32 on a 270-wide panel; a desktop menu is smaller. */
private val MenuShape = RoundedCornerShape(20.dp)

/** One open (or closing) menu: how far its glass has grown, and what it grows from. */
private class MenuMorph {
    var showing by mutableStateOf(false)
    var closing by mutableStateOf(false)
    val progress = Animatable(0f)
    val contentAlpha = Animatable(0f)

    /** The click point and the box around it, in window px. */
    var point = Offset.Zero
    var host: Rect? = null
    var focus: FocusManager? = null

    suspend fun run(open: Boolean) = coroutineScope {
        if (open) {
            closing = false
            showing = true
            launch { contentAlpha.animateTo(1f, tween(150, delayMillis = 60, easing = LinearOutSlowInEasing)) }
            progress.animateTo(1f, ConvxMotion.menuOpen())
        } else if (showing) {
            closing = true
            launch { contentAlpha.animateTo(0f, tween(80)) }
            progress.animateTo(0f, ConvxMotion.menuClose())
            showing = false
            closing = false
        }
    }

    /** Fades the shrinking glass out as it reaches the button. */
    fun glassAlpha() = if (closing) (progress.value / 0.3f).coerceIn(0f, 1f) else 1f

    /** Arrow keys walk the rows, as in any menu. */
    fun moveFocus(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val direction = when (event.key) {
            Key.DirectionDown -> FocusDirection.Next
            Key.DirectionUp -> FocusDirection.Previous
            else -> return false
        }
        return focus?.moveFocus(direction) ?: false
    }
}

/**
 * Where a menu opens. From a button it covers the button and runs away from the nearer window
 * edge; from a right-click it hangs from the point, flipping where it would not fit.
 */
private class MenuPlacement(private val morph: MenuMorph, private val fromButton: Boolean, density: Density) : PopupPositionProvider {
    private val margin = with(density) { 8.dp.roundToPx() }
    private val buttonLimit = with(density) { 96.dp.toPx() }
    private val spot = with(density) { 28.dp.toPx() }

    private fun button(): Rect? {
        val host = morph.host ?: return null
        if (!fromButton || host.width > buttonLimit || host.height > buttonLimit) return null
        val side = minOf(host.width, host.height)
        return Rect(host.center - Offset(side / 2, side / 2), Size(side, side))
    }

    /** The menu's top left in the window, for a menu of [size]. */
    fun place(window: IntSize, size: IntSize): IntOffset {
        val point = morph.point
        val button = button()
        val x: Float
        val y: Float
        if (button != null) {
            x = if (button.center.x > window.width / 2f) button.right - size.width else button.left
            y = if (button.top + size.height <= window.height - margin) button.top else button.bottom - size.height
        } else {
            x = if (point.x + size.width <= window.width - margin) point.x else point.x - size.width
            y = if (point.y + size.height <= window.height - margin) point.y else point.y - size.height
        }
        val maxX = (window.width - size.width - margin).coerceAtLeast(margin)
        val maxY = (window.height - size.height - margin).coerceAtLeast(margin)
        return IntOffset(x.roundToInt().coerceIn(margin, maxX), y.roundToInt().coerceIn(margin, maxY))
    }

    /** What the glass grows out of, inside a menu of [size]: the button, or a spot round the click. */
    fun origin(window: IntSize, size: IntSize): Rect {
        val from = button() ?: Rect(morph.point - Offset(spot / 2, spot / 2), Size(spot, spot))
        val at = place(window, size)
        return from.translate(-at.x.toFloat(), -at.y.toFloat())
    }

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        place(windowSize, popupContentSize)
}

/** The rows, clipped to the glass as it grows. */
private data class RevealShape(val rect: Rect, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

@Composable
private fun MorphingMenu(
    morph: MenuMorph,
    placement: MenuPlacement,
    shape: CornerBasedShape,
    backdrop: Backdrop?,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val reveal = remember { MenuReveal() }
    val cornerPx = shape.topStart.toPx(Size(1000f, 1000f), density)
    val maxHeight = with(density) { (window.height.toDp() - 16.dp).coerceIn(120.dp, 560.dp) }
    val glass = if (backdrop != null) {
        Modifier.liquidGlass(shape, MenuGlass, highlightAlpha = 0.20f, backdrop = backdrop)
    } else {
        Modifier.background(Color(0xFF2C2C2E), shape).border(0.5.dp, ConvxColors.Separator, shape)
    }
    // The glass at this point of its growth, inside a menu of [size].
    fun grown(size: IntSize): Rect {
        val from = placement.origin(window, size)
        val p = morph.progress.value
        return Rect(
            lerp(from.left, 0f, p),
            lerp(from.top, 0f, p),
            lerp(from.right, size.width.toFloat(), p),
            lerp(from.bottom, size.height.toFloat(), p),
        )
    }
    Box {
        Box(
            Modifier
                .matchParentSize()
                .layout { measurable, constraints ->
                    val w = constraints.maxWidth
                    val h = constraints.maxHeight
                    val r = grown(IntSize(w, h))
                    val placeable = measurable.measure(
                        Constraints.fixed(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)),
                    )
                    layout(w, h) { placeable.placeWithLayer(r.left.roundToInt(), r.top.roundToInt()) { alpha = morph.glassAlpha() } }
                }
                .then(glass),
        )
        CompositionLocalProvider(LocalMenuReveal provides reveal) {
            Column(
                Modifier
                    .graphicsLayer {
                        val r = grown(IntSize(size.width.roundToInt(), size.height.roundToInt()))
                        clip = true
                        this.shape = RevealShape(r, minOf(cornerPx, r.minDimension / 2))
                        alpha = morph.contentAlpha.value
                    }
                    .widthIn(min = 220.dp)
                    .heightIn(max = maxHeight)
                    .padding(vertical = 8.dp)
                    .width(IntrinsicSize.Max)
                    .verticalScroll(rememberScrollState()),
            ) {
                content()
            }
        }
    }
}

/** Hands each row of an opening menu its turn, so the rows come in one after another. */
private class MenuReveal {
    private val openedAt = System.nanoTime()
    private var count = 0

    /** How long the next row waits, in ms: none once the menu has finished opening. */
    fun nextDelay(): Long {
        val turn = count++.coerceAtMost(10)
        val elapsed = (System.nanoTime() - openedAt) / 1_000_000
        return (80L + 18L * turn - elapsed).coerceAtLeast(0L)
    }
}

private val LocalMenuReveal = staticCompositionLocalOf<MenuReveal?> { null }

@Composable
fun MenuItem(text: String, icon: String? = null, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val color = when {
        !enabled -> ConvxColors.TertiaryLabel
        destructive -> Color(0xFFFF453A)
        else -> Color.White
    }
    val reveal = LocalMenuReveal.current
    val shown = remember { Animatable(if (reveal == null) 1f else 0f) }
    if (reveal != null) {
        val wait = remember { reveal.nextDelay() }
        LaunchedEffect(Unit) {
            delay(wait)
            shown.animateTo(1f, ConvxMotion.appear())
        }
    }
    DropdownMenuItem(
        text = { Text(text, style = ConvxType.Body, color = color) },
        leadingIcon = icon?.let { { ConvxIcon(it, tint = color, size = 18.dp) } },
        enabled = enabled,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp),
        modifier = Modifier.graphicsLayer {
            alpha = shown.value
            translationY = (1f - shown.value) * 8.dp.toPx()
        },
    )
}

@Composable
fun MenuDivider() = HorizontalDivider(color = ConvxColors.Separator, thickness = 0.5.dp)

/** Everything you can do with one song — the phone's song sheet, as a desktop menu. */
@Composable
fun SongMenuContent(
    song: Song,
    dismiss: () -> Unit,
    extra: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
) {
    val navigator = LocalNavigator.current
    val clipboard = LocalClipboardManager.current
    val library by Library.state.collectAsState()
    val liked = library.liked.any { it.song.id == song.id }
    var choosingPlaylist by remember { mutableStateOf(false) }

    if (choosingPlaylist) {
        MenuItem("New playlist", "add") {
            dismiss()
            Overlays.prompt("New playlist", confirm = "Create") { name ->
                Library.createPlaylist(name, listOf(song))
                Overlays.toast("Added to $name")
            }
        }
        MenuDivider()
        for (playlist in library.localPlaylists) {
            MenuItem(playlist.name, "queue_music") {
                Library.addToPlaylist(playlist.id, listOf(song))
                Overlays.toast("Added to ${playlist.name}")
                dismiss()
            }
        }
        YouTubePlaylistItems(library) { playlist ->
            YouTubePlaylists.add(playlist, listOf(song))
            dismiss()
        }
        return
    }

    // In a party where the host holds the controls these say why instead, not "Playing next".
    MenuItem("Play next", "playlist_play") {
        if (PlayerController.playNext(listOf(song)) != null) Overlays.toast("Playing next")
        dismiss()
    }
    MenuItem("Add to queue", "queue_music") {
        if (PlayerController.addToQueue(listOf(song)) != null) Overlays.toast("Added to queue")
        dismiss()
    }
    val local = com.convx.desktop.player.LocalMusic.isLocal(song.id)
    if (!local) {
        MenuItem("Start radio", "radio") {
            PlayerController.startRadio(song)
            dismiss()
        }
    }
    // The phone's two AI actions on a song, through the provider set up for translation.
    MenuItem("AI DJ Mix from this song", "sparks") {
        navigator.navigate(Screen.AiDj(seed = song))
        dismiss()
    }
    MenuItem("Track insights", "info") {
        dismiss()
        com.convx.desktop.ui.screens.trackInsights(song)
    }
    MenuDivider()
    MenuItem(if (liked) "Remove from Liked Songs" else "Add to Liked Songs", if (liked) "favorite" else "favorite_border") {
        Library.toggleLike(song)
        Overlays.toast(if (liked) "Removed from Liked Songs" else "Added to Liked Songs", if (liked) "favorite_border" else "favorite")
        dismiss()
    }
    MenuItem("Add to playlist", "playlist_add") { choosingPlaylist = true }
    if (!local) SpeedDialMenuItem(song.toItem(), library.speedDial.any { it.id == song.id }, dismiss)
    DownloadMenuItems(listOf(song), dismiss)
    MenuDivider()
    song.artists.firstOrNull { it.id != null }?.let { artist ->
        MenuItem("Go to artist", "artist") {
            navigator.navigate(Screen.Artist(artist.id!!))
            dismiss()
        }
    }
    song.album?.let { album ->
        MenuItem("Go to album", "album") {
            navigator.navigate(Screen.Album(album.id))
            dismiss()
        }
    }
    if (local) {
        MenuItem("Show in folder", "storage") {
            com.convx.desktop.player.LocalMusic.track(song.id)?.let { track ->
                runCatching { ProcessBuilder("explorer.exe", "/select,", track.path).start() }
                    .onFailure { Overlays.toast("Couldn't open the folder", "error") }
            }
            dismiss()
        }
    } else {
        MenuItem("Copy link", "link") {
            clipboard.setText(AnnotatedString("https://music.youtube.com/watch?v=${song.id}"))
            Overlays.toast("Link copied", "link")
            dismiss()
        }
    }
    if (extra != null) {
        MenuDivider()
        extra(dismiss)
    }
}

/** Your own YouTube Music playlists, under the Convx ones, while signed in. */
@Composable
private fun YouTubePlaylistItems(library: com.convx.desktop.data.LibraryData, onPick: (com.convx.desktop.data.SavedPlaylist) -> Unit) {
    val playlists = remember(library.playlists) { YouTubePlaylists.editable(library) }
    if (playlists.isEmpty()) return
    if (library.localPlaylists.isNotEmpty()) MenuDivider()
    for (playlist in playlists) {
        MenuItem(playlist.title, "cloud") { onPick(playlist) }
    }
}

/** Menu for a whole collection: album, playlist, mix. */
@Composable
fun CollectionMenuContent(
    songs: () -> List<Song>,
    dismiss: () -> Unit,
    saved: Boolean? = null,
    onToggleSaved: (() -> Unit)? = null,
    link: String? = null,
    extra: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
    /** For a list only partly loaded: fetches the rest, so Download takes the whole of it. */
    loadAll: (suspend () -> List<Song>)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val library by Library.state.collectAsState()
    var choosingPlaylist by remember { mutableStateOf(false) }
    if (choosingPlaylist) {
        MenuItem("New playlist", "add") {
            dismiss()
            Overlays.prompt("New playlist", confirm = "Create") { name ->
                Library.createPlaylist(name, songs())
                Overlays.toast("Added to $name")
            }
        }
        MenuDivider()
        for (playlist in library.localPlaylists) {
            MenuItem(playlist.name, "queue_music") {
                Library.addToPlaylist(playlist.id, songs())
                Overlays.toast("Added to ${playlist.name}")
                dismiss()
            }
        }
        YouTubePlaylistItems(library) { playlist ->
            YouTubePlaylists.add(playlist, songs())
            dismiss()
        }
        return
    }
    MenuItem("Shuffle", "shuffle") {
        PlayerController.playSongs(songs(), shuffle = true)
        dismiss()
    }
    MenuItem("Play next", "playlist_play") {
        if (PlayerController.playNext(songs()) != null) Overlays.toast("Playing next")
        dismiss()
    }
    MenuItem("Add to queue", "queue_music") {
        if (PlayerController.addToQueue(songs()) != null) Overlays.toast("Added to queue")
        dismiss()
    }
    MenuItem("Add to playlist", "playlist_add") { choosingPlaylist = true }
    DownloadMenuItems(songs(), dismiss, loadAll)
    if (saved != null && onToggleSaved != null) {
        MenuItem(if (saved) "Remove from Library" else "Save to Library", if (saved) "library_add_check" else "library_add") {
            onToggleSaved()
            dismiss()
        }
    }
    if (link != null) {
        MenuItem("Copy link", "link") {
            clipboard.setText(AnnotatedString(link))
            Overlays.toast("Link copied", "link")
            dismiss()
        }
    }
    if (extra != null) {
        MenuDivider()
        extra(dismiss)
    }
}

/**
 * The phone's Download, Downloading and Remove download entries, for one song or a whole list.
 * A list that's partly downloaded offers both: the rest, and removing what's there.
 */
@Composable
fun DownloadMenuItems(songs: List<Song>, dismiss: () -> Unit, loadAll: (suspend () -> List<Song>)? = null) {
    val done by Downloads.done.collectAsState()
    val active by Downloads.active.collectAsState()
    val wanted = remember(songs) { songs.filter(Downloads::canDownload) }
    if (wanted.isEmpty() && loadAll == null) return
    val single = songs.singleOrNull()
    val kept = wanted.filter { it.id in done }
    // The rest of a long playlist is fetched first, then every song not on this PC yet is downloaded.
    if (loadAll != null) {
        val running = Downloads.collective(wanted, done, active) as? Downloads.Collective.Running
        if (running == null) {
            MenuItem("Download", "download") {
                dismiss()
                Overlays.toast("Getting the whole playlist…", "download")
                com.convx.desktop.core.AppScope.launch {
                    val all = loadAll().filter(Downloads::canDownload)
                    val queued = Downloads.download(all)
                    Overlays.toast(
                        if (queued > 0) "Downloading $queued ${if (queued == 1) "song" else "songs"}" else "All ${all.size} songs are already downloaded",
                        "download",
                    )
                }
            }
        } else {
            MenuItem("Downloading ${running.done} of ${running.total}… Stop", "download") {
                Downloads.cancel(wanted.map { it.id })
                dismiss()
            }
        }
    } else when (val state = Downloads.collective(wanted, done, active)) {
        is Downloads.Collective.Running -> MenuItem(
            if (single != null) "Downloading… Stop" else "Downloading ${state.done} of ${state.total}… Stop",
            "download",
        ) {
            Downloads.cancel(wanted.map { it.id })
            dismiss()
        }
        Downloads.Collective.Done -> Unit
        Downloads.Collective.None -> {
            val failed = single != null && active[single.id]?.status is Downloads.Status.Failed
            MenuItem(if (failed) "Download failed. Try again" else "Download", "download") {
                val queued = Downloads.download(wanted)
                if (single == null && queued > 0) Overlays.toast("Downloading $queued ${if (queued == 1) "song" else "songs"}", "download")
                dismiss()
            }
        }
    }
    if (kept.isNotEmpty()) {
        MenuItem("Remove download", "delete") {
            dismiss()
            if (single != null) {
                Downloads.remove(listOf(single.id))
                Overlays.toast("Download removed", "delete")
            } else {
                Overlays.confirm(
                    "Remove download?",
                    "${kept.size} downloaded ${if (kept.size == 1) "song" else "songs"} will be removed from this PC. They stay in your library.",
                    "Remove",
                ) { Downloads.remove(kept.map { it.id }) }
            }
        }
    }
}
