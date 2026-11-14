/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.glass.GlassSlider
import com.convx.desktop.ui.glass.GlassSwitch
import com.convx.desktop.ui.shell.FullPlayerMode
import com.convx.desktop.ui.shell.WindowTitleBar
import com.convx.desktop.ui.shell.windowDragArea
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val EmojiPalette = listOf(
    "✨", "🔥", "💜", "🌙", "⭐", "🎧", "🎵", "🌸", "🦋", "🍀",
    "☁️", "⚡", "🖤", "🌊", "🪩", "🎸", "💫", "🫧", "🌺", "🍄",
    "👑", "🍒", "🐾", "🎀", "🧊", "🌈", "☀️", "🪐", "🎐", "🕯️",
)

/**
 * The phone's DIY editor: the real player at its real size with the stickers over it, everything
 * else floating in pills. A mouse stands in for fingers: drag moves, the wheel resizes (Shift turns),
 * and the selected sticker's corner handle does both. Ctrl+Z and Ctrl+Y undo and redo, Delete removes.
 */
object DiyEditor {
    private const val UndoDepth = 30

    var isOpen by mutableStateOf(false)
        private set
    var layout by mutableStateOf(DiyLayout())
        private set
    var selectedId by mutableStateOf<String?>(null)
    var emojiOpen by mutableStateOf(false)
    var editOpen by mutableStateOf(false)
    private val undo = mutableStateListOf<DiyLayout>()
    private val redo = mutableStateListOf<DiyLayout>()
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    val selected: DiySticker? get() = layout.stickers.firstOrNull { it.id == selectedId }

    fun open() {
        layout = AppSettings.value.diyLayout
        undo.clear()
        redo.clear()
        selectedId = null
        emojiOpen = false
        editOpen = false
        isOpen = true
    }

    /** Leaves without saving, as the phone's back arrow does. */
    fun close() {
        isOpen = false
        emojiOpen = false
        editOpen = false
    }

    fun save() {
        val saved = layout
        AppSettings.update { it.copy(diyLayout = saved) }
        DiyStore.prune(saved)
        close()
        Overlays.toast("Layout saved")
    }

    /** Keeps the layout from before a change, so it can be undone. */
    fun keepUndo() {
        undo.add(layout)
        if (undo.size > UndoDepth) undo.removeAt(0)
        redo.clear()
    }

    fun commit(next: DiyLayout) {
        keepUndo()
        layout = next
    }

    /** A live change (a drag, a slider): no undo step per frame; the gesture's start keeps one. */
    fun replace(id: String, transform: (DiySticker) -> DiySticker) {
        layout = layout.copy(stickers = layout.stickers.map { if (it.id == id) transform(it) else it })
    }

    fun undo() {
        if (undo.isEmpty()) return
        redo.add(layout)
        layout = undo.removeAt(undo.lastIndex)
        deselect()
    }

    fun redo() {
        if (redo.isEmpty()) return
        undo.add(layout)
        layout = redo.removeAt(redo.lastIndex)
        deselect()
    }

    fun deselect() {
        selectedId = null
        emojiOpen = false
        editOpen = false
    }

    fun delete() {
        val id = selectedId ?: return
        commit(layout.copy(stickers = layout.stickers.filterNot { it.id == id }))
        deselect()
    }

    private fun nextZ() = (layout.stickers.maxOfOrNull { it.z } ?: 0) + 1

    private fun add(sticker: DiySticker) {
        commit(layout.copy(stickers = layout.stickers + sticker))
        selectedId = sticker.id
    }

    private fun full(): Boolean {
        if (layout.stickers.size < DiyMaxStickers) return false
        Overlays.toast("You can place up to $DiyMaxStickers stickers.", icon = "warning")
        return true
    }

    fun addEmoji(emoji: String) {
        emojiOpen = false
        if (full()) return
        add(DiySticker(UUID.randomUUID().toString(), DiyStickerKind.EMOJI, emoji, z = nextZ()))
    }

    fun addImage() {
        if (full()) return
        val file = com.convx.desktop.ui.screens.settings.pickFile("Add an image", DiyStore.Extensions) ?: return
        runCatching { DiyStore.import(file) }
            .onSuccess { add(DiySticker(UUID.randomUUID().toString(), DiyStickerKind.IMAGE, it, z = nextZ())) }
            .onFailure { Overlays.toast(it.message ?: "Cannot add that", icon = "warning") }
    }

    /** The editor's keys, ahead of the window's own. */
    fun key(event: KeyEvent): Boolean {
        if (!isOpen || event.type != KeyEventType.KeyDown) return false
        return when {
            event.key == Key.Escape -> {
                if (emojiOpen || editOpen) {
                    emojiOpen = false
                    editOpen = false
                } else {
                    close()
                }
                true
            }
            event.isCtrlPressed && event.key == Key.Z && event.isShiftPressed -> { redo(); true }
            event.isCtrlPressed && event.key == Key.Z -> { undo(); true }
            event.isCtrlPressed && event.key == Key.Y -> { redo(); true }
            event.isCtrlPressed && event.key == Key.S -> { save(); true }
            event.key == Key.Delete || event.key == Key.Backspace -> { delete(); true }
            else -> false
        }
    }
}

/** The editor over the whole window, while it's open. */
@Composable
fun DiyEditorOverlay() {
    if (!DiyEditor.isOpen) return
    val state by PlayerController.state.collectAsState()
    val settings by AppSettings.state.collectAsState()
    // Its top edge is the window's title bar, as the full player's is: what isn't a control drags the window.
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).windowDragArea()) {
        val orientation = orientationOf(maxWidth, maxHeight)
        // The real player in the chosen design, at the window's size, with nothing in it reacting.
        PlayerStage(state, settings, FullPlayerMode.Artwork, preview = true, showStickers = false)
        StickerCanvas(orientation)
        // Clear of Windows' own minimise, maximise and close in the corner.
        TopControls(Modifier.align(Alignment.TopCenter).padding(end = WindowTitleBar.rightInset))
        BottomControls(Modifier.align(Alignment.BottomCenter))
        Panels(orientation)
    }
}

/** Where a press landed on the selected sticker's corner handle, in the canvas' pixels. */
private fun handleCenter(sticker: DiySticker, orientation: DiyOrientation, width: Float, height: Float): Offset {
    val t = sticker.transformFor(orientation)
    val half = min(width, height) * t.scale / 2f
    val radians = Math.toRadians(t.rotation.toDouble())
    val cx = width * t.x
    val cy = height * t.y
    return Offset(
        (cx + half * cos(radians) - half * sin(radians)).toFloat(),
        (cy + half * sin(radians) + half * cos(radians)).toFloat(),
    )
}

/** The topmost sticker under [at], with a minimum grab area so a tiny one can still be caught. */
private fun hitTest(layout: DiyLayout, orientation: DiyOrientation, at: Offset, width: Float, height: Float, minHalf: Float): String? =
    layout.stickers.sortedByDescending { it.z }.firstOrNull { sticker ->
        val t = sticker.transformFor(orientation)
        val half = maxOf(min(width, height) * t.scale / 2f, minHalf)
        val cx = width * t.x
        val cy = height * t.y
        at.x in (cx - half)..(cx + half) && at.y in (cy - half)..(cy + half)
    }?.id

@Composable
private fun StickerCanvas(orientation: DiyOrientation) {
    val accent = LocalAccent.current
    val layout = DiyEditor.layout
    val selectedId = DiyEditor.selectedId
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        val widthPx = with(density) { width.toPx() }
        val heightPx = with(density) { height.toPx() }
        val minHalf = with(density) { 24.dp.toPx() }
        val handleRadius = with(density) { 14.dp.toPx() }
        val liveOrientation by rememberUpdatedState(orientation)
        // The wheel adds an undo step once per burst of turning, not per notch.
        var lastWheel by remember { mutableStateOf(0L) }

        layout.stickers.sortedBy { it.z }.forEach { sticker ->
            DiyStickerContent(
                sticker,
                orientation,
                width,
                height,
                modifier = if (sticker.id == selectedId) Modifier.border(2.dp, accent, RoundedCornerShape(4.dp)) else Modifier,
            )
        }
        DiyEditor.selected?.let { sticker ->
            val at = handleCenter(sticker, orientation, widthPx, heightPx)
            Box(
                Modifier
                    .offset { IntOffset((at.x - handleRadius).roundToInt(), (at.y - handleRadius).roundToInt()) }
                    .size(28.dp)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ConvxIcon("refresh", tint = accent, size = 14.dp)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(widthPx, heightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val o = liveOrientation
                        val current = DiyEditor.selected
                        val onHandle = current != null &&
                            (down.position - handleCenter(current, o, widthPx, heightPx)).getDistance() <= handleRadius * 1.4f
                        val hit = if (onHandle) current?.id else hitTest(DiyEditor.layout, o, down.position, widthPx, heightPx, minHalf)
                        if (hit != null) DiyEditor.selectedId = hit
                        // Grabbing empty space moves what's already selected, as on the phone.
                        val target = hit ?: DiyEditor.selectedId
                        val start = target?.let { id -> DiyEditor.layout.stickers.firstOrNull { it.id == id }?.transformFor(o) }
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
                                DiyEditor.keepUndo()
                                moved = true
                            }
                            change.consume()
                            if (onHandle) {
                                // The corner handle: distance from the centre sizes it, the angle turns it.
                                val c = Offset(widthPx * start.x, heightPx * start.y)
                                val from = down.position - c
                                val to = pos - c
                                val ratio = (to.getDistance() / from.getDistance().coerceAtLeast(1f))
                                val turn = Math.toDegrees((atan2(to.y, to.x) - atan2(from.y, from.x)).toDouble()).toFloat()
                                DiyEditor.replace(target) {
                                    it.withTransform(o, start.copy(scale = start.scale * ratio, rotation = start.rotation + turn).clamped())
                                }
                            } else {
                                val delta = pos - last
                                DiyEditor.replace(target) {
                                    val t = it.transformFor(o)
                                    it.withTransform(o, t.copy(x = (t.x + delta.x / widthPx).coerceIn(-0.2f, 1.2f), y = (t.y + delta.y / heightPx).coerceIn(-0.2f, 1.2f)))
                                }
                            }
                            last = pos
                        }
                        // A click on nothing clears the selection.
                        if (!moved && hit == null) DiyEditor.deselect()
                    }
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    val o = liveOrientation
                    val id = hitTest(DiyEditor.layout, o, change.position, widthPx, heightPx, minHalf) ?: DiyEditor.selectedId ?: return@onPointerEvent
                    DiyEditor.selectedId = id
                    val now = System.currentTimeMillis()
                    if (now - lastWheel > 600) DiyEditor.keepUndo()
                    lastWheel = now
                    val notches = change.scrollDelta.y + change.scrollDelta.x
                    val turning = event.keyboardModifiers.isShiftPressed
                    DiyEditor.replace(id) {
                        val t = it.transformFor(o)
                        val next = if (turning) t.copy(rotation = t.rotation + notches * 5f) else t.copy(scale = t.scale * Math.pow(0.92, notches.toDouble()).toFloat())
                        it.withTransform(o, next.clamped())
                    }
                    change.consume()
                },
        )
    }
}

/** A floating capsule, the phone's Pill. */
@Composable
private fun Pill(content: @Composable () -> Unit) {
    Row(
        Modifier
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(Color(0xFF2C2C2E).copy(alpha = 0.94f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
}

@Composable
private fun PillIcon(icon: String, enabled: Boolean = true, tint: Color = Color.White, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (hovered && enabled) Color.White.copy(alpha = 0.1f) else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(interaction, null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ConvxIcon(icon, tint = if (enabled) tint else tint.copy(alpha = 0.35f), size = 20.dp)
    }
}

@Composable
private fun PillText(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit = {}) {
    val accent = LocalAccent.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .clip(CircleShape)
            .background(
                when {
                    selected -> accent
                    hovered && enabled -> Color.White.copy(alpha = 0.1f)
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(interaction, null, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            style = ConvxType.SubheadStrong,
            color = when {
                selected -> Color.White
                enabled -> Color.White
                else -> ConvxColors.SecondaryLabel
            },
        )
    }
}

@Composable
private fun TopControls(modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill { PillIcon("arrow_back", onClick = DiyEditor::close) }
        Spacer(Modifier.weight(1f))
        Pill {
            PillIcon("replay", enabled = DiyEditor.canUndo, onClick = DiyEditor::undo)
            PillIcon("fast_forward", enabled = DiyEditor.canRedo, onClick = DiyEditor::redo)
            PillIcon("check", onClick = DiyEditor::save)
        }
    }
}

@Composable
private fun BottomControls(modifier: Modifier) {
    Column(modifier.padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Drag a sticker to move it. Scroll over it to resize, Shift+scroll to turn, or drag its corner.",
            style = ConvxType.Footnote,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill {
                PillText("Add image", onClick = DiyEditor::addImage)
                PillText("Add emoji", selected = DiyEditor.emojiOpen) {
                    DiyEditor.emojiOpen = !DiyEditor.emojiOpen
                    DiyEditor.editOpen = false
                }
                PillText("${DiyEditor.layout.stickers.size} / $DiyMaxStickers", enabled = false)
            }
            if (DiyEditor.selected != null) {
                Pill {
                    PillText("Edit", selected = DiyEditor.editOpen) {
                        DiyEditor.editOpen = !DiyEditor.editOpen
                        DiyEditor.emojiOpen = false
                    }
                    PillIcon("delete", tint = Color(0xFFFF453A), onClick = DiyEditor::delete)
                }
            }
        }
    }
}

/** The emoji and sticker panels, floating above the pills; each can be dragged by its body and stays where it was left. */
@Composable
private fun BoxScope.Panels(orientation: DiyOrientation) {
    val density = LocalDensity.current
    val lift = with(density) { (-150).dp.toPx() }
    var emojiOffset by remember { mutableStateOf(Offset(0f, lift)) }
    var editOffset by remember { mutableStateOf(Offset(0f, lift)) }
    if (DiyEditor.emojiOpen) {
        DraggablePanel(emojiOffset, { emojiOffset = it }) { EmojiPanel() }
    }
    val selected = DiyEditor.selected
    if (DiyEditor.editOpen && selected != null) {
        DraggablePanel(editOffset, { editOffset = it }) { StickerPanel(selected, orientation) }
    }
}

@Composable
private fun BoxScope.DraggablePanel(offset: Offset, onOffset: (Offset) -> Unit, content: @Composable () -> Unit) {
    val live by rememberUpdatedState(offset)
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onOffset(Offset(live.x + drag.x, (live.y + drag.y).coerceAtMost(0f)))
                }
            }
            .shadow(12.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF2C2C2E).copy(alpha = 0.97f)),
    ) {
        // The column is as wide as its content (the 300dp panels); a full-width grab bar used to stretch it across the window.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // The grab bar that says the panel can be moved.
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).width(36.dp).height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f)))
            content()
        }
    }
}

@Composable
private fun EmojiPanel() {
    FlowRow(Modifier.width(300.dp).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
        EmojiPalette.forEach { emoji ->
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).pressable({ DiyEditor.addEmoji(emoji) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, fontSize = 26.sp)
            }
        }
    }
}

@Composable
private fun StickerPanel(sticker: DiySticker, orientation: DiyOrientation) {
    val transform = sticker.transformFor(orientation)
    // A slider keeps one undo step for the whole slide.
    var sliding by remember { mutableStateOf(false) }
    fun change(next: (DiySticker) -> DiySticker) {
        if (!sliding) {
            DiyEditor.keepUndo()
            sliding = true
        }
        DiyEditor.replace(sticker.id, next)
    }
    fun toggle(next: (DiySticker) -> DiySticker) {
        DiyEditor.keepUndo()
        DiyEditor.replace(sticker.id, next)
    }
    Column(Modifier.width(300.dp).padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        LowResolutionHint(sticker, transform)
        PanelSlider("Size", transform.scale, DiyTransform.MinScale..DiyTransform.MaxScale, { sliding = false }) { v ->
            change { it.withTransform(orientation, transform.copy(scale = v)) }
        }
        PanelSlider("Rotation", ((transform.rotation + 180f).mod(360f)) - 180f, -180f..180f, { sliding = false }) { v ->
            change { it.withTransform(orientation, transform.copy(rotation = v)) }
        }
        PanelSlider("Opacity", sticker.opacity, 0.05f..1f, { sliding = false }) { v -> change { it.copy(opacity = v) } }
        if (sticker.kind == DiyStickerKind.IMAGE) {
            PanelSlider("Corner radius", sticker.cornerRadius, 0f..0.5f, { sliding = false }) { v -> change { it.copy(cornerRadius = v) } }
        }
        PanelSwitch("Flip horizontally", sticker.flipHorizontal) { v -> toggle { it.copy(flipHorizontal = v) } }
        PanelSwitch("Drop shadow", sticker.shadow) { v -> toggle { it.copy(shadow = v) } }
        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill {
                PillText("Backward") { toggle { it.copy(z = (it.z - 1).coerceAtLeast(-DiySticker.MaxZ)) } }
                PillText("Forward") { toggle { it.copy(z = (it.z + 1).coerceAtMost(DiySticker.MaxZ)) } }
            }
        }
        Text(
            if (sticker.z < 0) "Behind the artwork" else "In front of the artwork",
            style = ConvxType.Footnote,
            color = ConvxColors.SecondaryLabel,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun PanelSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onFinished: () -> Unit, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = ConvxType.SubheadStrong, color = Color.White)
        Spacer(Modifier.height(4.dp))
        GlassSlider(value.coerceIn(range), onChange, valueRange = range, onValueChangeFinished = onFinished)
    }
}

@Composable
private fun PanelSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = ConvxType.Body, color = Color.White, modifier = Modifier.weight(1f))
        GlassSwitch(checked, onChange)
    }
}

/** The phone's hint when a picture is blown up past its own pixels. A hint, not a limit. */
@Composable
private fun LowResolutionHint(sticker: DiySticker, transform: DiyTransform) {
    if (sticker.kind != DiyStickerKind.IMAGE) return
    val source = remember(sticker.source) { DiyStore.pixelSide(sticker.source) }
    if (source <= 0) return
    val density = LocalDensity.current
    val window = java.awt.Toolkit.getDefaultToolkit().screenSize.let { min(it.width, it.height) } * density.density
    if (window * transform.scale <= source * 1.15f) return
    Text(
        "This image is being scaled past its own resolution and may look soft.",
        style = ConvxType.Footnote,
        color = Color(0xFFFF453A),
        modifier = Modifier.padding(bottom = 4.dp),
    )
}
