/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import com.convx.desktop.ui.components.rememberLoopingPhase
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.AppScope
import com.convx.desktop.data.BrushStroke
import com.convx.desktop.data.BrushTip
import com.convx.desktop.data.DiscArt
import com.convx.desktop.data.DiscDesign
import com.convx.desktop.data.DiscDesigns
import com.convx.desktop.data.DiscLayer
import com.convx.desktop.data.DiscPresets
import com.convx.desktop.data.Library
import com.convx.desktop.data.LayerKind
import com.convx.desktop.data.TextPath
import com.convx.desktop.data.newLayerId
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.shell.WindowTitleBar
import com.convx.desktop.ui.shell.windowDragArea
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import com.convx.desktop.data.StoreJson
import com.convx.desktop.data.ImageFrame

/** The editor's own dark, a little lighter than the window's, so the disc stands off it. */
internal val EditorBg = Color(0xFF0E0E10)
internal val PanelBg = Color(0xFF161618)
internal val FieldBg = Color.White.copy(alpha = 0.07f)

/**
 * Disc Studio's editor, over the whole window: tools on the left, the disc in the middle, its
 * layers and the selected one's settings on the right. The mouse moves what's selected, the wheel
 * sizes it (Shift turns it), the round handle does both. Ctrl+Z / Ctrl+Y undo and redo, Ctrl+S
 * saves, Ctrl+C / Ctrl+X / Ctrl+V copy, cut and paste layers (and paste pictures copied in other
 * apps), Ctrl+D duplicates, Delete removes, the arrows nudge.
 */
object DiscEditor {
    private const val UndoDepth = 60

    var isOpen by mutableStateOf(false)
        private set
    var design by mutableStateOf(DiscDesign())
        private set
    var selectedId by mutableStateOf<String?>(null)
    var dirty by mutableStateOf(false)
        private set

    /** The playlist it was opened for: "Use on" goes there, and its songs' covers are offered as pictures. */
    var playlistId by mutableStateOf<String?>(null)
        private set
    var spinning by mutableStateOf(false)

    /** Show it as the playlist cover will look (in its case or sleeve) rather than the bare disc. */
    var asCover by mutableStateOf(false)

    /** Which "Add" picker the tools panel has open. */
    var picker by mutableStateOf<LayerKind?>(null)

    /** Drags on the disc paint with [brush] (into the selected brush layer, or a new one) rather than move things. */
    var painting by mutableStateOf(false)
    var brush by mutableStateOf(BrushStroke(tip = BrushTip.PEN, color = 0xFFFFFFFF, size = 0.025f))

    /** The stroke being painted, drawn over the disc until it's let go; the layer it goes into, if there is one yet. */
    var liveStroke by mutableStateOf<Pair<BrushStroke, DiscLayer?>?>(null)
    var saving by mutableStateOf(false)
        private set

    private val undo = mutableStateListOf<DiscDesign>()
    private val redo = mutableStateListOf<DiscDesign>()
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    val selected: DiscLayer? get() = design.layer(selectedId)

    fun open(start: DiscDesign, forPlaylist: String? = null) {
        design = start
        playlistId = forPlaylist
        undo.clear()
        redo.clear()
        selectedId = null
        picker = null
        painting = false
        liveStroke = null
        spinning = false
        asCover = false
        dirty = false
        isOpen = true
    }

    /** A new design from [preset], its text naming the playlist it's for. */
    fun openPreset(preset: DiscPresets.Preset, forPlaylist: String? = null) {
        val playlist = forPlaylist?.let { Library.playlist(it) }
        open(DiscPresets.start(preset, playlist?.name, playlist?.songs?.map { it.title }.orEmpty()), forPlaylist)
        dirty = true
    }

    /** The playlist's own design if it has one, or a new one with its name on it. */
    fun openFor(playlistId: String) {
        val linked = DiscDesigns.get(Library.playlist(playlistId)?.design)
        if (linked != null) {
            open(linked, playlistId)
        } else {
            openPreset(DiscPresets.of("afro-night") ?: DiscPresets.all.first(), playlistId)
        }
    }

    /** Leaves, asking first if there are changes that aren't saved. */
    fun close() {
        if (!dirty) return closeNow()
        Overlays.confirm("Leave without saving?", "Your changes to \"${design.name}\" will be lost.", "Leave") { closeNow() }
    }

    private fun closeNow() {
        isOpen = false
        spinning = false
        picker = null
        painting = false
        // Pictures added and then dropped before saving aren't kept.
        AppScope.launch(Dispatchers.IO) { runCatching { DiscDesigns.pruneAssets() } }
    }

    /** Saves the design (and redraws the playlists that wear it); [then] runs with the saved one. */
    fun save(then: ((DiscDesign) -> Unit)? = null) {
        if (saving) return
        saving = true
        val toSave = design
        AppScope.launch(Dispatchers.Main) {
            val saved = withContext(Dispatchers.IO) { runCatching { DiscDesigns.save(toSave) } }
            saving = false
            saved.onSuccess {
                if (design.id == it.id) {
                    design = it
                    dirty = false
                }
                if (then != null) then(it) else Overlays.toast("Saved \"${it.name}\"")
            }.onFailure { Overlays.toast("Couldn't save the design: ${it.message}", "error") }
        }
    }

    /** Saves, then puts the design on [playlist] as its cover. */
    fun useOn(playlist: String) {
        save { saved ->
            AppScope.launch(Dispatchers.Main) {
                val done = withContext(Dispatchers.IO) { DiscDesigns.applyTo(saved, playlist) }
                val name = Library.playlist(playlist)?.name ?: "the playlist"
                if (done) Overlays.toast("\"${saved.name}\" is now the cover of $name") else Overlays.toast("Couldn't put it on $name", "error")
            }
        }
    }

    /** Saves [toShare] as a .convxdisc file wherever the person picks. */
    fun export(toShare: DiscDesign = design) {
        val picker = FileDialog(null as Frame?, "Share disc design", FileDialog.SAVE).apply {
            file = DiscDesigns.fileName(toShare)
            isVisible = true
        }
        val name = picker.file ?: return
        val target = File(picker.directory, if (name.endsWith(".${DiscDesigns.Extension}", ignoreCase = true)) name else "$name.${DiscDesigns.Extension}")
        AppScope.launch {
            withContext(Dispatchers.IO) { runCatching { DiscDesigns.export(toShare, target) } }
                .onSuccess { Overlays.toast("Saved ${target.name}. Anyone with Convx can open it in Disc Studio.") }
                .onFailure { Overlays.toast("Couldn't save the file: ${it.message}", "error") }
        }
    }

    /** Saves [toDraw] as a picture for showing it off: the playlist cover if [cover], else the bare disc. */
    fun exportImage(toDraw: DiscDesign = design, cover: Boolean = asCover) {
        val picker = FileDialog(null as Frame?, "Save as picture", FileDialog.SAVE).apply {
            file = DiscDesigns.fileName(toDraw).removeSuffix(".${DiscDesigns.Extension}") + ".png"
            isVisible = true
        }
        val name = picker.file ?: return
        val target = File(picker.directory, if (name.endsWith(".png", ignoreCase = true)) name else "$name.png")
        AppScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val image = if (cover) DiscArt.renderCover(toDraw, DiscDesigns.Resources, 1400) else DiscArt.renderDisc(toDraw, DiscDesigns.Resources, 1400)
                    target.writeBytes(DiscArt.png(image))
                }
            }
                .onSuccess { Overlays.toast("Saved ${target.name}") }
                .onFailure { Overlays.toast("Couldn't save the picture: ${it.message}", "error") }
        }
    }

    // ---- Changes -----------------------------------------------------------------------------

    /** Keeps the design from before a change, so it can be undone. */
    fun keepUndo() {
        undo.add(design)
        if (undo.size > UndoDepth) undo.removeAt(0)
        redo.clear()
        dirty = true
    }

    /** A change with its own undo step. */
    fun commit(next: DiscDesign) {
        keepUndo()
        design = next
    }

    /** A live change (a drag, a slider): the gesture's start kept the undo step. */
    fun live(next: DiscDesign) {
        design = next
        dirty = true
    }

    fun change(transform: (DiscDesign) -> DiscDesign) = commit(transform(design))

    fun liveLayer(id: String, transform: (DiscLayer) -> DiscLayer) =
        live(design.copy(layers = design.layers.map { if (it.id == id) transform(it) else it }))

    fun commitLayer(id: String, transform: (DiscLayer) -> DiscLayer) =
        commit(design.copy(layers = design.layers.map { if (it.id == id) transform(it) else it }))

    fun undo() {
        if (undo.isEmpty()) return
        redo.add(design)
        design = undo.removeAt(undo.lastIndex)
        if (design.layer(selectedId) == null) selectedId = null
        dirty = true
    }

    fun redo() {
        if (redo.isEmpty()) return
        undo.add(design)
        design = redo.removeAt(redo.lastIndex)
        if (design.layer(selectedId) == null) selectedId = null
        dirty = true
    }

    fun add(layer: DiscLayer) {
        commit(design.copy(layers = design.layers + layer))
        selectedId = layer.id
        picker = null
        painting = false
    }

    /** The brush layer strokes go into: the selected one, if it's a brush layer that can be painted on. */
    fun paintTarget(): DiscLayer? = selected?.takeIf { it.kind == LayerKind.PAINT && !it.locked && it.visible }

    /** Adds a finished stroke to [target] (a brush layer), or to a new brush layer on top. */
    fun addStroke(stroke: BrushStroke, target: String?) {
        val layer = design.layer(target)
        if (layer == null) {
            if (stroke.tip == BrushTip.ERASER) return
            val fresh = recentre(DiscLayer(kind = LayerKind.PAINT, name = "Painting", strokes = listOf(stroke)))
            commit(design.copy(layers = design.layers + fresh))
            selectedId = fresh.id
        } else {
            commitLayer(layer.id) { recentre(it.copy(strokes = it.strokes + stroke)) }
        }
    }

    /** Ends a rub-out that was shown live, the undo step kept as it began. */
    fun finishErase(target: String) = liveLayer(target) { recentre(it) }

    /**
     * Moves a brush layer's middle to the middle of what's painted, without moving the paint, so
     * it turns and sizes round its own middle.
     */
    private fun recentre(layer: DiscLayer): DiscLayer {
        val box = DiscArt.paintBounds(layer) ?: return layer
        val mx = (box[0] + box[2]) / 2f
        val my = (box[1] + box[3]) / 2f
        if (abs(mx) < 1e-4f && abs(my) < 1e-4f) return layer
        val strokes = layer.strokes.map { stroke -> stroke.copy(points = stroke.points.mapIndexed { i, v -> roundPoint(v - if (i % 2 == 0) mx else my) }) }
        val a = Math.toRadians(layer.rotation.toDouble())
        val scale = layer.scale
        val dx = ((mx * kotlin.math.cos(a) - my * kotlin.math.sin(a)) * scale).toFloat()
        val dy = ((mx * kotlin.math.sin(a) + my * kotlin.math.cos(a)) * scale).toFloat()
        return layer.copy(x = layer.x + dx, y = layer.y + dy, strokes = strokes)
    }

    /** Four places is a ten-thousandth of the radius: finer than a pixel, and short in the saved file. */
    fun roundPoint(v: Float): Float = (v * 10_000f).roundToInt() / 10_000f

    fun delete(id: String? = selectedId) {
        id ?: return
        commit(design.copy(layers = design.layers.filterNot { it.id == id }))
        if (selectedId == id) selectedId = null
    }

    fun duplicate(id: String? = selectedId) {
        val layer = design.layer(id) ?: return
        val copy = layer.copy(id = newLayerId(), name = "${layer.name} copy", x = layer.x + 0.06f, y = layer.y + 0.06f)
        val at = design.layers.indexOf(layer) + 1
        commit(design.copy(layers = design.layers.toMutableList().apply { add(at, copy) }))
        selectedId = copy.id
    }

    /**
     * Copies a layer to the clipboard, as text other apps leave alone, so it pastes into another
     * design too, and after Convx restarts.
     */
    fun copy(id: String? = selectedId): Boolean {
        val layer = design.layer(id) ?: return false
        val text = LayerClipPrefix + StoreJson.encodeToString(DiscLayer.serializer(), layer)
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
            .onFailure { Overlays.toast("Couldn't copy that layer", "error"); return false }
        pastes = 0
        return true
    }

    fun cut(id: String? = selectedId) {
        if (copy(id)) delete(id)
    }

    /**
     * Pastes a copied layer above the selected one, a step down and right each time so copies
     * don't hide each other. A picture copied in another app (or a picture file copied in File
     * Explorer) comes in as a picture layer.
     */
    fun paste() {
        val clipboard = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull() ?: return
        val text = runCatching {
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) clipboard.getData(DataFlavor.stringFlavor) as? String else null
        }.getOrNull()
        val layer = text?.takeIf { it.startsWith(LayerClipPrefix) }
            ?.let { runCatching { StoreJson.decodeFromString(DiscLayer.serializer(), it.removePrefix(LayerClipPrefix)) }.getOrNull() }
        if (layer != null) {
            pastes++
            val shift = if (layer.placed) PasteStep * pastes else 0f
            insert(layer.copy(id = newLayerId(), x = layer.x + shift, y = layer.y + shift, locked = false))
            return
        }
        val picture = runCatching { clipboardPicture(clipboard) }.getOrNull() ?: return
        AppScope.launch(Dispatchers.Main) {
            withContext(Dispatchers.IO) { runCatching { picture().let { it to DiscArt.fitScale(it, DiscDesigns.Resources) } } }
                .onSuccess { (asset, fit) ->
                    insert(DiscLayer(kind = LayerKind.IMAGE, name = "Picture", asset = asset, frame = ImageFrame.WHOLE, scale = fit, amount = 1f))
                }
                .onFailure { Overlays.toast(it.message ?: "Couldn't paste that picture", "error") }
        }
    }

    /** Puts [layer] just above the selected layer (on top when none is) and selects it. */
    private fun insert(layer: DiscLayer) {
        val at = design.layer(selectedId)?.let { design.layers.indexOf(it) + 1 } ?: design.layers.size
        commit(design.copy(layers = design.layers.toMutableList().apply { add(at, layer) }))
        selectedId = layer.id
        picker = null
        painting = false
    }

    /** What the clipboard holds as a picture, read and stored as a design asset when called; null if none. */
    private fun clipboardPicture(clipboard: Clipboard): (() -> String)? {
        if (clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) {
            val files = (clipboard.getData(DataFlavor.javaFileListFlavor) as? List<*>).orEmpty().filterIsInstance<File>()
            val file = files.firstOrNull { it.extension.lowercase() in DiscDesigns.ImageExtensions } ?: return null
            return { DiscDesigns.importImage(file) }
        }
        if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
            val image = clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image ?: return null
            return {
                val buffered = image as? BufferedImage ?: BufferedImage(
                    image.getWidth(null).coerceAtLeast(1), image.getHeight(null).coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB,
                ).also { it.createGraphics().apply { drawImage(image, 0, 0, null); dispose() } }
                val bytes = ByteArrayOutputStream().also { ImageIO.write(buffered, "png", it) }.toByteArray()
                DiscDesigns.importImage(bytes, "png")
            }
        }
        return null
    }

    private var pastes = 0
    private const val PasteStep = 0.05f
    private const val LayerClipPrefix = "convx-disc-layer:"

    /** Moves a layer up (towards the front) or down by [by] places. */
    fun move(id: String, by: Int) {
        val layers = design.layers.toMutableList()
        val from = layers.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + by).coerceIn(0, layers.lastIndex)
        if (to == from) return
        layers.add(to, layers.removeAt(from))
        commit(design.copy(layers = layers))
    }

    /** Swaps the whole design for [preset]'s, keeping its name and the playlist's name in its text. */
    fun usePreset(preset: DiscPresets.Preset) {
        val playlist = playlistId?.let { Library.playlist(it) }
        val fresh = DiscPresets.start(preset, playlist?.name ?: design.name, playlist?.songs?.map { it.title }.orEmpty())
        commit(fresh.copy(id = design.id, name = design.name, createdAt = design.createdAt))
        selectedId = null
    }

    private fun nudge(dx: Float, dy: Float) {
        val layer = selected ?: return
        if (layer.locked) return
        commitLayer(layer.id) { it.copy(x = it.x + dx, y = it.y + dy) }
    }

    /** The editor's keys, ahead of the window's own; a text field being typed in keeps its keys. */
    fun key(event: KeyEvent): Boolean {
        if (!isOpen || event.type != KeyEventType.KeyDown) return false
        if (Overlays.dialog != null) return false
        val typing = UiState.typingIn != null
        val step = if (event.isShiftPressed) 0.05f else 0.01f
        return when {
            event.key == Key.Escape -> {
                when {
                    painting -> {
                        painting = false
                        if (picker == LayerKind.PAINT) picker = null
                    }
                    picker != null -> picker = null
                    selectedId != null && !typing -> selectedId = null
                    !typing -> close()
                }
                true
            }
            event.isCtrlPressed && event.key == Key.S -> { save(); true }
            typing -> false
            event.isCtrlPressed && event.key == Key.Z && event.isShiftPressed -> { redo(); true }
            event.isCtrlPressed && event.key == Key.Z -> { undo(); true }
            event.isCtrlPressed && event.key == Key.Y -> { redo(); true }
            event.isCtrlPressed && event.key == Key.D -> { duplicate(); true }
            event.isCtrlPressed && event.key == Key.C -> { copy(); true }
            event.isCtrlPressed && event.key == Key.X -> { cut(); true }
            event.isCtrlPressed && event.key == Key.V -> { paste(); true }
            event.key == Key.Delete || event.key == Key.Backspace -> { delete(); true }
            event.key == Key.DirectionLeft -> { nudge(-step, 0f); true }
            event.key == Key.DirectionRight -> { nudge(step, 0f); true }
            event.key == Key.DirectionUp -> { nudge(0f, -step); true }
            event.key == Key.DirectionDown -> { nudge(0f, step); true }
            event.key == Key.Spacebar -> { spinning = !spinning; true }
            event.key == Key.B && !event.isCtrlPressed -> {
                painting = !painting
                if (painting) picker = LayerKind.PAINT else if (picker == LayerKind.PAINT) picker = null
                true
            }
            // Brush size, as in other paint apps.
            painting && event.key == Key.LeftBracket -> { brush = brush.copy(size = (brush.size / 1.25f).coerceAtLeast(BrushSizes.start)); true }
            painting && event.key == Key.RightBracket -> { brush = brush.copy(size = (brush.size * 1.25f).coerceAtMost(BrushSizes.endInclusive)); true }
            else -> false
        }
    }
}

/** The editor over the whole window, while it's open. */
@Composable
fun DiscEditorOverlay() {
    if (!DiscEditor.isOpen) return
    Column(Modifier.fillMaxSize().background(EditorBg)) {
        EditorTopBar()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            ToolsPanel(Modifier.width(264.dp).fillMaxHeight())
            DiscCanvas(Modifier.weight(1f).fillMaxHeight())
            InspectorPanel(Modifier.width(328.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun EditorTopBar() {
    val accent = LocalAccent.current
    val library by Library.state.collectAsState()
    val playlistMenu = rememberMenuAnchor()
    // The bar is the window's title bar too: its empty parts drag the window.
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(PanelBg)
            .windowDragArea()
            .padding(start = 10.dp, end = WindowTitleBar.rightInset + 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton("arrow_back", DiscEditor::close, tooltip = "Back to Disc Studio (Esc)")
        Spacer(Modifier.width(4.dp))
        NameField(Modifier.widthIn(min = 120.dp, max = 320.dp))
        if (DiscEditor.dirty) {
            Text("Not saved", style = ConvxType.Caption, color = ConvxColors.SecondaryLabel)
        }
        Spacer(Modifier.weight(1f))
        IconButton("replay", DiscEditor::undo, enabled = DiscEditor.canUndo, tooltip = "Undo (Ctrl+Z)")
        IconButton("redo", DiscEditor::redo, enabled = DiscEditor.canRedo, tooltip = "Redo (Ctrl+Y)")
        Spacer(Modifier.width(6.dp))
        IconButton("refresh", { DiscEditor.spinning = !DiscEditor.spinning }, active = DiscEditor.spinning, activeTint = accent, tooltip = "Spin it (Space)")
        IconButton("disc_case", { DiscEditor.asCover = !DiscEditor.asCover }, active = DiscEditor.asCover, activeTint = accent, tooltip = "See it as the playlist cover")
        IconButton("image", { DiscEditor.exportImage() }, tooltip = "Save as picture")
        IconButton("ios_share", { DiscEditor.export() }, tooltip = "Share as a .convxdisc file")
        Spacer(Modifier.width(6.dp))
        Box {
            val target = DiscEditor.playlistId?.let { id -> library.localPlaylists.firstOrNull { it.id == id } }
            PillButton(
                if (target != null) "Use on ${target.name.take(18)}" else "Use on playlist",
                { if (target != null) DiscEditor.useOn(target.id) else playlistMenu.expanded = true },
                icon = "playlist_add",
            )
            AppMenu(playlistMenu) {
                if (library.localPlaylists.isEmpty()) {
                    MenuItem("No playlists yet", enabled = false) {}
                }
                library.localPlaylists.take(40).forEach { playlist ->
                    MenuItem(playlist.name, "queue_music") {
                        playlistMenu.expanded = false
                        DiscEditor.useOn(playlist.id)
                    }
                }
            }
        }
        if (DiscEditor.playlistId != null) {
            IconButton("more_horiz", { playlistMenu.expanded = true }, tooltip = "Use on another playlist")
        }
        PillButton(if (DiscEditor.saving) "Saving…" else "Save", { DiscEditor.save() }, accent = accent, icon = "check")
    }
}

@Composable
private fun NameField(modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { UiState.typingFocusChanged("disc-name", false) } }
    Box(
        modifier
            .clip(CircleShape)
            .background(if (focused) FieldBg else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        BasicTextField(
            value = DiscEditor.design.name,
            onValueChange = { name -> DiscEditor.live(DiscEditor.design.copy(name = name.take(60))) },
            singleLine = true,
            textStyle = ConvxType.Headline.copy(color = Color.White),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier.onFocusChanged {
                focused = it.isFocused
                UiState.typingFocusChanged("disc-name", it.isFocused)
            },
        )
    }
}

/** The disc as big as fits, drawn by [DiscArt] straight into the window, with the selection over it. */
@Composable
private fun DiscCanvas(modifier: Modifier) {
    val accent = LocalAccent.current
    // A turn every 1.8 s while the spin preview is on, paced like the app's other moving views.
    val phase by rememberLoopingPhase(1800, DiscEditor.spinning)
    val spin = if (DiscEditor.spinning) phase * 360f else 0f
    BoxWithConstraints(modifier.background(EditorBg)) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val radius = min(wPx, hPx) / 2f * 0.84f
        val cx = wPx / 2f
        val cy = hPx / 2f - min(wPx, hPx) * 0.02f
        val handleRadius = with(LocalDensity.current) { 9.dp.toPx() }
        val live by rememberUpdatedState(Triple(cx, cy, radius))
        var lastWheel by remember { mutableStateOf(0L) }
        // Where the brush is, for its outline, while painting.
        var hover by remember { mutableStateOf<Offset?>(null) }
        // While it spins, the print (with its effects) is drawn once and turned, not drawn again every frame.
        val design = DiscEditor.design
        val print by produceState<org.jetbrains.skia.Image?>(null, DiscEditor.spinning, design, radius) {
            value = if (!DiscEditor.spinning) null else withContext(Dispatchers.Default) {
                runCatching { DiscArt.renderPrint(design, DiscDesigns.Resources, (radius * 2f).roundToInt().coerceIn(64, 2400)) }.getOrNull()
            }
        }
        // Once it stops changing, the disc is drawn into a picture off the UI thread. Anything else
        // that redraws the window (a hover, a tooltip, a menu) then repaints that picture instead of
        // every layer and effect again; while something is dragged, the disc is drawn live.
        val asCover = DiscEditor.asCover
        val still by produceState<Still?>(null, design, asCover, radius) {
            value = null
            delay(160)
            value = withContext(Dispatchers.Default) { runCatching { Still.of(design, asCover, radius) }.getOrNull() }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerHoverIcon(if (DiscEditor.painting) PointerIcon.Crosshair else PointerIcon.Default)
                .onPointerEvent(PointerEventType.Move) { event -> hover = if (DiscEditor.painting) event.changes.firstOrNull()?.position else null }
                .onPointerEvent(PointerEventType.Exit) { hover = null }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        DiscEditor.spinning = false
                        val (ccx, ccy, r) = live
                        if (DiscEditor.painting) {
                            down.consume()
                            val target = DiscEditor.paintTarget()
                            val erasing = DiscEditor.brush.tip == BrushTip.ERASER
                            if (erasing && target == null) {
                                Overlays.toast("Select a brush layer first: the eraser rubs out paint", "info")
                                return@awaitEachGesture
                            }
                            // Points in the brush layer's own space, so they land where they're painted however it's moved.
                            fun local(p: Offset): Pair<Float, Float> {
                                val x = (p.x - ccx) / r
                                val y = (p.y - ccy) / r
                                return if (target != null) DiscArt.toLayer(target, x, y) else x to y
                            }
                            val scale = target?.scale?.coerceAtLeast(0.02f) ?: 1f
                            val brush = DiscEditor.brush.let { it.copy(size = it.size / scale, seed = Random.nextInt(100_000)) }
                            val points = ArrayList<Float>()
                            local(down.position).let { (x, y) -> points += DiscEditor.roundPoint(x); points += DiscEditor.roundPoint(y) }
                            val step = kotlin.math.max(0.0025f, DiscEditor.brush.size * 0.1f) / scale
                            fun show() {
                                val stroke = brush.copy(points = points.toList())
                                if (erasing) {
                                    if (target != null) DiscEditor.liveLayer(target.id) { it.copy(strokes = target.strokes + stroke) }
                                } else {
                                    DiscEditor.liveStroke = stroke to target
                                }
                            }
                            if (erasing && target != null) DiscEditor.keepUndo()
                            show()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                hover = change.position
                                val (x, y) = local(change.position)
                                if (hypot(x - points[points.size - 2], y - points[points.size - 1]) < step) continue
                                points += DiscEditor.roundPoint(x)
                                points += DiscEditor.roundPoint(y)
                                show()
                            }
                            if (erasing) {
                                target?.let { DiscEditor.finishErase(it.id) }
                            } else {
                                DiscEditor.addStroke(brush.copy(points = points.toList()), target?.id)
                            }
                            DiscEditor.liveStroke = null
                            return@awaitEachGesture
                        }
                        val current = DiscEditor.selected
                        val handle = current?.let { handleAt(it, ccx, ccy, r) }
                        val onHandle = handle != null && current?.locked == false && (down.position - handle).getDistance() <= handleRadius * 2.2f
                        val hit = if (onHandle) current?.id else DiscEditor.design.layers.asReversed().firstOrNull {
                            !it.locked && DiscArt.hit(it, DiscDesigns.Resources, ccx, ccy, r, down.position.x, down.position.y)
                        }?.id
                        if (hit != null) DiscEditor.selectedId = hit
                        // Grabbing empty space moves what's already selected.
                        val target = DiscEditor.design.layer(hit ?: DiscEditor.selectedId)?.takeIf { !it.locked }
                        val start = target
                        var moved = false
                        var last = down.position
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (target == null || start == null) continue
                            val pos = change.position
                            if (!moved && (pos - down.position).getDistance() < viewConfiguration.touchSlop) continue
                            if (!moved) {
                                DiscEditor.keepUndo()
                                moved = true
                            }
                            change.consume()
                            val centre = Offset(ccx + start.x * r, ccy + start.y * r)
                            val round = start.onCircle
                            when {
                                onHandle -> {
                                    // Distance from the middle sizes it, the angle turns it.
                                    val from = down.position - centre
                                    val to = pos - centre
                                    val ratio = to.getDistance() / from.getDistance().coerceAtLeast(1f)
                                    val turn = Math.toDegrees((atan2(to.y, to.x) - atan2(from.y, from.x)).toDouble()).toFloat()
                                    DiscEditor.liveLayer(target.id) {
                                        if (round) it.copy(radius = (start.radius * ratio).coerceIn(0.05f, 1.2f), rotation = start.rotation + turn)
                                        else it.copy(scale = (start.scale * ratio).coerceIn(0.02f, 4f), rotation = start.rotation + turn)
                                    }
                                }
                                round -> {
                                    // Text on a circle slides round it.
                                    val a = Math.toDegrees(atan2((last.y - centre.y).toDouble(), (last.x - centre.x).toDouble()))
                                    val b = Math.toDegrees(atan2((pos.y - centre.y).toDouble(), (pos.x - centre.x).toDouble()))
                                    DiscEditor.liveLayer(target.id) { it.copy(rotation = it.rotation + (b - a).toFloat()) }
                                }
                                else -> {
                                    val delta = pos - last
                                    DiscEditor.liveLayer(target.id) {
                                        it.copy(x = (it.x + delta.x / r).coerceIn(-1.6f, 1.6f), y = (it.y + delta.y / r).coerceIn(-1.6f, 1.6f))
                                    }
                                }
                            }
                            last = pos
                        }
                        if (!moved && hit == null && hypot(down.position.x - ccx, down.position.y - ccy) > r) DiscEditor.selectedId = null
                    }
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    val (ccx, ccy, r) = live
                    val id = DiscEditor.design.layers.asReversed().firstOrNull {
                        !it.locked && DiscArt.hit(it, DiscDesigns.Resources, ccx, ccy, r, change.position.x, change.position.y)
                    }?.id ?: DiscEditor.selected?.takeIf { !it.locked }?.id ?: return@onPointerEvent
                    DiscEditor.selectedId = id
                    val now = System.currentTimeMillis()
                    if (now - lastWheel > 600) DiscEditor.keepUndo()
                    lastWheel = now
                    val notches = change.scrollDelta.y + change.scrollDelta.x
                    val turning = event.keyboardModifiers.isShiftPressed
                    DiscEditor.liveLayer(id) {
                        val round = it.onCircle
                        when {
                            turning -> it.copy(rotation = it.rotation + notches * 5f)
                            round -> it.copy(radius = (it.radius * 0.97f.pow(notches)).coerceIn(0.05f, 1.2f))
                            else -> it.copy(scale = (it.scale * 0.92f.pow(notches)).coerceIn(0.02f, 4f))
                        }
                    }
                    change.consume()
                },
        ) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val picture = still?.takeIf { it.design == design && it.asCover == asCover && it.radius == radius && spin == 0f }
                if (picture != null) {
                    native.drawImage(picture.image, cx - picture.image.width / 2f, cy - picture.image.height / 2f)
                } else if (asCover) {
                    val side = radius * 2.3f
                    native.save()
                    native.clipRect(org.jetbrains.skia.Rect.makeXYWH(cx - side / 2f, cy - side / 2f, side, side))
                    native.translate(cx - side / 2f, cy - side / 2f)
                    DiscArt.drawCover(native, design, DiscDesigns.Resources, side)
                    native.restore()
                } else {
                    DiscArt.drawDisc(native, design, DiscDesigns.Resources, cx, cy, radius, spin, print.takeIf { spin != 0f })
                }
                // The stroke being painted, over the disc until it's let go and drawn into its layer.
                DiscEditor.liveStroke?.let { (stroke, layer) ->
                    native.save()
                    native.clipPath(org.jetbrains.skia.Path().addCircle(cx, cy, radius), org.jetbrains.skia.ClipMode.INTERSECT, true)
                    native.translate(cx + (layer?.x ?: 0f) * radius, cy + (layer?.y ?: 0f) * radius)
                    if (layer != null) {
                        native.rotate(layer.rotation)
                        native.scale(layer.scale, layer.scale)
                    }
                    DiscArt.drawStroke(native, stroke, radius)
                    native.restore()
                }
            }
            // The brush's size, where it would paint.
            val at = hover
            if (DiscEditor.painting && at != null) {
                val size = radius * DiscEditor.brush.size / 2f
                drawCircle(Color.Black.copy(alpha = 0.5f), size + 1.5f, at, style = Stroke(width = 1.5f))
                drawCircle(Color.White, size, at, style = Stroke(width = 1.5f))
            }
            // The selection: a dashed outline, and the round handle that sizes and turns it.
            val layer = DiscEditor.selected
            if (layer != null && layer.visible && !DiscEditor.asCover && spin == 0f && !DiscEditor.painting) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 7f), 0f)
                val round = layer.onCircle
                val corners = DiscArt.corners(layer, DiscDesigns.Resources, cx, cy, radius)
                when {
                    corners != null -> {
                        val path = Path().apply {
                            moveTo(corners[0].first, corners[0].second)
                            for (i in 1 until 4) lineTo(corners[i].first, corners[i].second)
                            close()
                        }
                        drawPath(path, Color.Black.copy(alpha = 0.5f), style = Stroke(width = 3.5f))
                        drawPath(path, accent, style = Stroke(width = 2f, pathEffect = dash))
                    }
                    round -> {
                        val centre = Offset(cx + layer.x * radius, cy + layer.y * radius)
                        drawCircle(accent, radius * layer.radius, centre, style = Stroke(width = 2f, pathEffect = dash))
                    }
                    else -> drawCircle(accent.copy(alpha = 0.6f), radius * 1.02f, Offset(cx, cy), style = Stroke(width = 2f, pathEffect = dash))
                }
                handleAt(layer, cx, cy, radius)?.let { at ->
                    drawCircle(Color.Black.copy(alpha = 0.45f), handleRadius + 3f, at + Offset(0f, 1.5f))
                    drawCircle(Color.White, handleRadius, at)
                    drawCircle(accent, handleRadius, at, style = Stroke(width = 3f))
                }
            }
        }
        Text(
            when {
                DiscEditor.asCover -> "This is how the playlist cover will look."
                DiscEditor.painting -> "Painting with the ${DiscEditor.brush.tip.label.lowercase()}. Ctrl+Z takes back a stroke, [ and ] size the brush, Esc stops."
                DiscEditor.selected?.locked == true -> "This layer is locked. Unlock it in Layers to move it."
                DiscEditor.selected?.kind == LayerKind.FILL -> "A fill covers the part of the disc it's printed on. Pick that part on the right."
                DiscEditor.selected != null -> "Drag to move. Scroll to resize, Shift+scroll to turn, or drag the round handle. Arrows nudge."
                else -> "Click something on the disc to select it, or add a layer from the left."
            },
            style = ConvxType.Footnote,
            color = Color.White.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp, start = 16.dp, end = 16.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/** The disc (or its cover) as a picture, for a design that isn't changing. */
private class Still(val design: DiscDesign, val asCover: Boolean, val radius: Float, val image: org.jetbrains.skia.Image) {
    companion object {
        fun of(design: DiscDesign, asCover: Boolean, radius: Float): Still {
            // Square and even, so its middle lands where the live disc's would.
            val side = if (asCover) ((radius * 2.3f).roundToInt() / 2) * 2 else ((radius * 2.24f).roundToInt() / 2 + 2) * 2
            val image = if (asCover) {
                DiscArt.renderCover(design, DiscDesigns.Resources, side)
            } else {
                val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(side, side)
                surface.canvas.clear(org.jetbrains.skia.Color.TRANSPARENT)
                DiscArt.drawDisc(surface.canvas, design, DiscDesigns.Resources, side / 2f, side / 2f, radius)
                surface.makeImageSnapshot()
            }
            return Still(design, asCover, radius, image)
        }
    }
}

/** Where the selected layer's handle sits: its outline's bottom-right corner, or on its circle for round text. */
private fun handleAt(layer: DiscLayer, cx: Float, cy: Float, r: Float): Offset? {
    val corners = DiscArt.corners(layer, DiscDesigns.Resources, cx, cy, r)
    if (corners != null) return Offset(corners[2].first, corners[2].second)
    if (layer.onCircle) {
        val a = Math.toRadians(layer.rotation.toDouble() + if (layer.path == TextPath.ARC_BOTTOM) 135.0 else 45.0 - 90.0)
        val rr = r * layer.radius
        return Offset(cx + layer.x * r + (rr * kotlin.math.cos(a)).toFloat(), cy + layer.y * r + (rr * kotlin.math.sin(a)).toFloat())
    }
    return null
}
