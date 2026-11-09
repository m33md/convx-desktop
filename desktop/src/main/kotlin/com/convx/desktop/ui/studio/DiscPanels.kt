/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.core.AppScope
import com.convx.desktop.data.DiscArt
import com.convx.desktop.data.DiscDesign
import com.convx.desktop.data.DiscDesigns
import com.convx.desktop.data.DiscFont
import com.convx.desktop.data.DiscFormat
import com.convx.desktop.data.DiscLayer
import com.convx.desktop.data.DiscPresets
import com.convx.desktop.data.DiscSticker
import com.convx.desktop.data.EffectKind
import com.convx.desktop.data.LayerEffect
import com.convx.desktop.data.ShapeKind
import com.convx.desktop.data.TextAlignment
import com.convx.desktop.data.EmojiStickerPrefix
import com.convx.desktop.data.FillKind
import com.convx.desktop.data.ImageFrame
import com.convx.desktop.data.ImageLook
import com.convx.desktop.data.LayerArea
import com.convx.desktop.data.LayerBlend
import com.convx.desktop.data.LayerKind
import com.convx.desktop.data.Library
import com.convx.desktop.data.PatternKind
import com.convx.desktop.data.TextPath
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.DialogCard
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.ImageCache
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.components.Tooltip
import com.convx.desktop.ui.components.artworkUrl
import com.convx.desktop.ui.glass.GlassSlider
import com.convx.desktop.ui.glass.GlassSwitch
import com.convx.desktop.ui.screens.settings.pickFile
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.random.Random
import org.jetbrains.skia.Image as SkiaImage

private val TileShape = RoundedCornerShape(12.dp)
private val SmallShape = RoundedCornerShape(8.dp)

/** Colours offered first in the colour picker. */
private val Swatches = listOf(
    0xFFFFFFFF, 0xFFE5E5EA, 0xFF8E8E93, 0xFF3A3A3C, 0xFF111113, 0xFFFA2D48,
    0xFFFF6B35, 0xFFFFC233, 0xFFFFE14D, 0xFF7ED957, 0xFF30D158, 0xFF33D1FF,
    0xFF0A84FF, 0xFF5E5CE6, 0xFF9B7BFF, 0xFFFF6FB5, 0xFFFFC7A1, 0xFFF2EBDD,
    0xFF8C1C13, 0xFF3A2410, 0xFF1B3B6F, 0xFF4B1D7A, 0xFF0F5132, 0xFFC9CACF,
)

private val Emojis = listOf("✨", "🔥", "💜", "🌙", "⭐", "🎧", "🎵", "🌸", "🦋", "🍒", "⚡", "🖤", "🌊", "🪩", "🎸", "💫", "👑", "🌈", "☁️", "🍄", "🐾", "🎀", "🪐", "💿")

internal fun Long.toColor(): Color = Color(this)

internal fun Color.toLong(): Long = toArgb().toLong() and 0xFFFFFFFFL

// ---- Thumbnails ------------------------------------------------------------------------------

/** Covers drawn once and kept: presets by key, designs by id and when they last changed. */
internal object DiscThumbs {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun peek(key: String): ImageBitmap? = cache[key]

    fun keep(key: String, image: ImageBitmap) {
        cache[key] = image
    }

    /** How big the covers on cards are drawn. */
    const val CardPixels = 420

    suspend fun cover(key: String, design: () -> DiscDesign, size: Int = CardPixels): ImageBitmap? = cache[key] ?: withContext(Dispatchers.Default) {
        runCatching { DiscArt.renderCover(design(), DiscDesigns.Resources, size).toComposeImageBitmap() }.getOrNull()
    }?.also { cache[key] = it }

    fun presetKey(preset: DiscPresets.Preset) = "preset:${preset.key}"

    fun designKey(design: DiscDesign) = "design:${design.id}:${design.updatedAt}"
}

/** A design's cover, drawn off the UI thread the first time. */
@Composable
internal fun DiscThumb(key: String, size: Dp, design: () -> DiscDesign) {
    val image = produceState(DiscThumbs.peek(key), key) { value = DiscThumbs.cover(key, design) }.value
    Box(Modifier.size(size).clip(SmallShape).background(Color(0xFF070708))) {
        if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
    }
}

/** One layer drawn by itself on a round tile: the pickers' previews. */
@Composable
internal fun LayerPreview(layer: DiscLayer, size: Dp, background: Color = Color(0xFF2A2A2E)) {
    Canvas(Modifier.size(size).clip(CircleShape).background(background)) {
        val s = this.size.minDimension
        drawIntoCanvas { canvas ->
            DiscArt.drawLayerAlone(canvas.nativeCanvas, layer, DiscDesigns.Resources, this.size.width / 2f, this.size.height / 2f, s / 2f)
        }
    }
}

// ---- Small controls --------------------------------------------------------------------------

@Composable
internal fun PanelHeading(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = ConvxType.Footnote, color = ConvxColors.SecondaryLabel, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .clip(CircleShape)
            .background(
                when {
                    selected -> accent
                    hovered -> Color.White.copy(alpha = 0.14f)
                    else -> FieldBg
                },
            )
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interaction, null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(text, style = ConvxType.CaptionStrong, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option -> ChoiceChip(label(option), option == selected) { if (option != selected) onPick(option) } }
    }
}

/** A slider whose whole slide is one undo step. */
@Composable
private fun PropSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    var sliding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = ConvxType.Subhead, color = Color.White, modifier = Modifier.weight(1f))
            Text(shown, style = ConvxType.Caption, color = ConvxColors.SecondaryLabel)
        }
        GlassSlider(
            value.coerceIn(range),
            { v ->
                if (!sliding) {
                    DiscEditor.keepUndo()
                    sliding = true
                }
                onChange(v)
            },
            valueRange = range,
            onValueChangeFinished = { sliding = false },
        )
    }
}

private fun percent(v: Float) = "${(v * 100).roundToInt()}%"

@Composable
private fun PropSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = ConvxType.Subhead, color = Color.White, modifier = Modifier.weight(1f))
        GlassSwitch(checked, onChange)
    }
}

@Composable
private fun Swatch(color: Long, size: Dp = 30.dp, selected: Boolean = false, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFF555555), Color(0xFF999999))))
            .background(color.toColor())
            .border(if (selected) 2.5.dp else 1.dp, if (selected) accent else Color.White.copy(alpha = 0.25f), CircleShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
    )
}

/** A row of colours: click one to change it, + to add one, − to take the last off. */
@Composable
private fun ColorRow(label: String, colors: List<Long>, min: Int = 1, max: Int = 5, onChange: (List<Long>) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = ConvxType.Subhead, color = Color.White)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            colors.forEachIndexed { index, color ->
                Swatch(color) {
                    pickColor(color) { picked -> onChange(colors.toMutableList().also { it[index] = picked }) }
                }
            }
            if (colors.size < max) {
                SmallRound("add", "Add a colour") {
                    DiscEditor.keepUndo()
                    onChange(colors + (colors.lastOrNull() ?: 0xFFFFFFFF))
                }
            }
            if (colors.size > min) {
                SmallRound("remove", "Take the last colour off") {
                    DiscEditor.keepUndo()
                    onChange(colors.dropLast(1))
                }
            }
        }
    }
}

@Composable
private fun SmallRound(icon: String, tip: String, onClick: () -> Unit) {
    Tooltip(tip) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(FieldBg).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { ConvxIcon(icon, tint = Color.White, size = 16.dp) }
    }
}

/**
 * The colour picker: swatches, hue, saturation, brightness and see-through sliders, and a hex
 * field. Changes show on the disc as they're made; the whole visit is one undo step.
 */
private fun pickColor(initial: Long, onPick: (Long) -> Unit) {
    DiscEditor.keepUndo()
    Overlays.showDialog {
        val start = remember { hsba(initial) }
        var h by remember { mutableStateOf(start[0]) }
        var s by remember { mutableStateOf(start[1]) }
        var b by remember { mutableStateOf(start[2]) }
        var a by remember { mutableStateOf(start[3]) }
        var hex by remember { mutableStateOf(hexOf(initial)) }
        DisposableEffect(Unit) { onDispose { UiState.typingFocusChanged("disc-hex", false) } }
        fun push() {
            val color = fromHsba(h, s, b, a)
            hex = hexOf(color)
            onPick(color)
        }
        DialogCard("Colour") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Swatches.forEach { swatch ->
                    Swatch(swatch, size = 34.dp, selected = hexOf(swatch) == hex.take(7)) {
                        val v = hsba(swatch)
                        h = v[0]
                        s = v[1]
                        b = v[2]
                        a = 1f
                        push()
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(Brush.horizontalGradient(List(7) { i -> fromHsba(i / 6f, 1f, 1f, 1f).toColor() })))
            GlassSlider(h, { h = it; push() }, valueRange = 0f..1f)
            Text("Saturation", style = ConvxType.Caption, color = ConvxColors.SecondaryLabel)
            GlassSlider(s, { s = it; push() }, valueRange = 0f..1f)
            Text("Brightness", style = ConvxType.Caption, color = ConvxColors.SecondaryLabel)
            GlassSlider(b, { b = it; push() }, valueRange = 0f..1f)
            Text("Opacity", style = ConvxType.Caption, color = ConvxColors.SecondaryLabel)
            GlassSlider(a, { a = it; push() }, valueRange = 0f..1f)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(fromHsba(h, s, b, a).toColor()).border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape))
                Box(Modifier.weight(1f).clip(SmallShape).background(FieldBg).padding(horizontal = 12.dp, vertical = 9.dp)) {
                    BasicTextField(
                        value = hex,
                        onValueChange = { text ->
                            hex = text.take(9)
                            parseHex(text)?.let { color ->
                                val v = hsba(color)
                                h = v[0]
                                s = v[1]
                                b = v[2]
                                a = v[3]
                                onPick(color)
                            }
                        },
                        singleLine = true,
                        textStyle = ConvxType.Body.copy(color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier.onFocusChanged { UiState.typingFocusChanged("disc-hex", it.isFocused) },
                    )
                }
                PillButton("Done", { Overlays.dismissDialog() }, filled = true)
            }
        }
    }
}

private fun hsba(color: Long): FloatArray {
    val c = DiscArt.argb(color)
    val hsb = java.awt.Color.RGBtoHSB((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, null)
    return floatArrayOf(hsb[0], hsb[1], hsb[2], ((c ushr 24) and 0xFF) / 255f)
}

private fun fromHsba(h: Float, s: Float, b: Float, a: Float): Long {
    val rgb = java.awt.Color.HSBtoRGB(h, s, b) and 0xFFFFFF
    return ((a.coerceIn(0f, 1f) * 255).roundToInt().toLong() shl 24) or rgb.toLong()
}

private fun hexOf(color: Long): String {
    val c = DiscArt.argb(color)
    val alpha = (c ushr 24) and 0xFF
    val rgb = "#%06X".format(c and 0xFFFFFF)
    return if (alpha == 0xFF) rgb else rgb + "%02X".format(alpha)
}

private fun parseHex(text: String): Long? {
    val t = text.trim().removePrefix("#")
    val v = t.toLongOrNull(16) ?: return null
    return when (t.length) {
        6 -> 0xFF000000L or v
        8 -> ((v and 0xFF) shl 24) or (v shr 8)
        else -> null
    }
}

// ---- Tools (left) ----------------------------------------------------------------------------

@Composable
internal fun ToolsPanel(modifier: Modifier) {
    Column(modifier.background(PanelBg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp)) {
        PanelHeading("Add a layer")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LayerKind.entries.forEach { kind ->
                ToolTile(kind.icon, kind.label, DiscEditor.picker == kind) {
                    DiscEditor.picker = if (DiscEditor.picker == kind) null else kind
                }
            }
        }
        when (DiscEditor.picker) {
            LayerKind.IMAGE -> ImagePicker()
            LayerKind.TEXT -> TextPicker()
            LayerKind.PATTERN -> PatternPicker { pattern -> DiscEditor.add(newPattern(pattern)) }
            LayerKind.STICKER -> StickerPicker { sticker -> DiscEditor.add(newSticker(sticker)) }
            LayerKind.FILL -> FillPicker()
            LayerKind.SHAPE -> ShapePicker()
            null -> Unit
        }
        PanelHeading("Start from a preset")
        Text("Replaces the whole design. Undo brings yours back.", style = ConvxType.Caption, color = ConvxColors.TertiaryLabel)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DiscPresets.ordered.forEach { preset ->
                Tooltip(preset.title) {
                    Box(Modifier.clip(SmallShape).pointerHoverIcon(PointerIcon.Hand).clickable { DiscEditor.usePreset(preset) }) {
                        // The same picture the Disc Studio page shows, so each preset is drawn and kept once.
                        DiscThumb("${DiscThumbs.presetKey(preset)}@${DiscThumbs.CardPixels}", 70.dp) { DiscPresets.start(preset) }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ToolTile(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier
            .width(70.dp)
            .clip(TileShape)
            .background(if (selected) accent.copy(alpha = 0.28f) else if (hovered) Color.White.copy(alpha = 0.11f) else FieldBg)
            .border(if (selected) 1.5.dp else 0.dp, if (selected) accent else Color.Transparent, TileShape)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interaction, null, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ConvxIcon(icon, tint = Color.White, size = 22.dp)
        Spacer(Modifier.height(6.dp))
        Text(label, style = ConvxType.CaptionStrong, color = Color.White)
    }
}

/** Where a new placed layer goes: near the middle, a little off each time so they don't pile up. */
private fun spot(): Pair<Float, Float> {
    val n = DiscEditor.design.layers.size
    val angle = n * 2.1
    return (kotlin.math.cos(angle) * 0.45).toFloat() to (kotlin.math.sin(angle) * 0.45).toFloat()
}

private fun newPattern(pattern: PatternKind): DiscLayer = DiscLayer(
    kind = LayerKind.PATTERN,
    name = pattern.label,
    pattern = pattern,
    colors = when (pattern) {
        PatternKind.CHECKER, PatternKind.MARBLE, PatternKind.SPLATTER -> listOf(0xFF151515, 0xFFF2F2F2)
        PatternKind.PIXELS -> listOf(0xFF2B0A0A, 0xFF8C1C13, 0xFFF4A259, 0xFFFFF1C1)
        PatternKind.CONFETTI -> listOf(0xFFFA2D48, 0xFF33D1FF, 0xFFFFE14D, 0xFFFFFFFF)
        PatternKind.PAPER -> listOf(0xFFF3EEE3)
        PatternKind.STREAKS -> listOf(0xFFFFE1B0, 0xFFFFFFFF, 0xFF8FD3FF)
        PatternKind.LIGHT_LEAK -> listOf(0xFFFF7A3D, 0xFFFF3D7F, 0xFFFFD25A)
        PatternKind.SCREEN_TONE, PatternKind.SCANLINES, PatternKind.HUD -> listOf(ink())
        else -> listOf(0xFFFFFFFF)
    },
    blend = when (pattern) {
        PatternKind.GRAIN -> LayerBlend.OVERLAY
        // Paper over a design prints the design on it.
        PatternKind.PAPER -> LayerBlend.MULTIPLY
        PatternKind.STREAKS, PatternKind.LIGHT_LEAK -> LayerBlend.SCREEN
        else -> LayerBlend.NORMAL
    },
    opacity = when (pattern) {
        PatternKind.SCANLINES -> 0.35f
        PatternKind.HUD -> 0.85f
        else -> 1f
    },
    seed = Random.nextInt(1000),
)

/** A colour that shows on the design as it stands: near-black on a light disc, white on a dark one. */
private fun ink(): Long {
    val design = DiscEditor.design
    val under = design.layers.lastOrNull {
        it.visible && (it.kind == LayerKind.FILL || it.kind == LayerKind.PATTERN) && it.area != LayerArea.MIDDLE && it.blend == LayerBlend.NORMAL
    }?.colors?.firstOrNull() ?: design.base
    return if (Color(under).luminance() > 0.45f) 0xFF15151A else 0xFFFFFFFF
}

private fun newShape(kind: ShapeKind): DiscLayer {
    val (x, y) = spot()
    val base = DiscLayer(kind = LayerKind.SHAPE, name = kind.label, shape = kind, colors = listOf(ink()), x = x, y = y, seed = Random.nextInt(1000))
    return when (kind) {
        ShapeKind.BOX -> base.copy(outline = true, scale = 0.2f, aspect = 1.4f, amount = 0.12f)
        ShapeKind.CIRCLE -> base.copy(outline = true, scale = 0.2f, amount = 0.12f)
        ShapeKind.BADGE -> base.copy(scale = 0.13f)
        ShapeKind.BLOB -> base.copy(outline = true, scale = 0.24f, aspect = 1.2f, amount = 0.1f, density = 0.5f)
        ShapeKind.LINE -> base.copy(scale = 0.06f, aspect = 8f, amount = 0.1f)
        ShapeKind.CROSSHAIR -> base.copy(scale = 0.2f, amount = 0.1f)
        ShapeKind.CORNERS -> base.copy(scale = 0.24f, aspect = 1.3f, amount = 0.12f)
        ShapeKind.RULER -> base.copy(scale = 0.035f, aspect = 9f, amount = 0.06f, density = 0.6f)
        ShapeKind.BARCODE -> base.copy(scale = 0.12f, aspect = 1.8f)
        ShapeKind.TAPE -> base.copy(scale = 0.07f, aspect = 3.4f, rotation = -18f, colors = listOf(0xCCF4D58D))
        ShapeKind.SCRAP -> base.copy(scale = 0.28f, aspect = 1.3f, rotation = Random.nextInt(-8, 8).toFloat(), colors = listOf(0xFFF3EEE3), shadow = true)
    }
}

@Composable
private fun ShapePicker() {
    PickerBox {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ShapeKind.entries.forEach { kind ->
                val sample = remember(kind) {
                    val shape = newShape(kind)
                    shape.copy(
                        x = 0f, y = 0f, shadow = false,
                        colors = listOf(if (kind == ShapeKind.TAPE) 0xFFF4D58D else if (kind == ShapeKind.SCRAP) 0xFFF3EEE3 else 0xFFFFFFFF),
                        scale = 0.6f / kotlin.math.max(1f, shape.aspect),
                        amount = if (kind.lines || shape.outline) 0.7f else shape.amount,
                        rotation = if (kind == ShapeKind.TAPE) -18f else 0f,
                    )
                }
                Tooltip(kind.label) {
                    Box(Modifier.clip(CircleShape).pointerHoverIcon(PointerIcon.Hand).clickable { DiscEditor.add(newShape(kind)) }) {
                        LayerPreview(sample, 46.dp, background = Color(0xFF3A3A40))
                    }
                }
            }
        }
    }
}

private fun newSticker(sticker: String): DiscLayer {
    val (x, y) = spot()
    val known = DiscSticker.of(sticker)
    return DiscLayer(
        kind = LayerKind.STICKER,
        name = known?.label ?: "Emoji",
        sticker = sticker,
        colors = listOf(
            when (known) {
                DiscSticker.HEART, DiscSticker.CHERRY -> 0xFFFA2D48
                DiscSticker.STAR, DiscSticker.BOLT, DiscSticker.SMILE, DiscSticker.CROWN -> 0xFFFFD43B
                DiscSticker.FLOWER -> 0xFF9B7BFF
                DiscSticker.MOON -> 0xFFFFF1C1
                DiscSticker.NOTE -> 0xFF111113
                else -> 0xFFFFFFFF
            },
        ),
        x = x,
        y = y,
        scale = 0.22f,
        outline = known != null,
        shadow = true,
    )
}

@Composable
private fun PatternPicker(onPick: (PatternKind) -> Unit) {
    PickerBox {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PatternKind.entries.forEach { pattern ->
                val sample = remember(pattern) { newPattern(pattern).copy(seed = 3) }
                Tooltip(pattern.label) {
                    Box(Modifier.clip(CircleShape).pointerHoverIcon(PointerIcon.Hand).clickable { onPick(pattern) }) {
                        LayerPreview(sample, 46.dp, background = Color(0xFF3A3A40))
                    }
                }
            }
        }
    }
}

@Composable
private fun StickerPicker(onPick: (String) -> Unit) {
    PickerBox {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DiscSticker.entries.forEach { sticker ->
                val sample = remember(sticker) { newSticker(sticker.id).copy(x = 0f, y = 0f, scale = 0.62f, shadow = false) }
                Tooltip(sticker.label) {
                    Box(Modifier.clip(CircleShape).pointerHoverIcon(PointerIcon.Hand).clickable { onPick(sticker.id) }) {
                        LayerPreview(sample, 46.dp, background = Color(0xFF3A3A40))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow {
            Emojis.forEach { emoji ->
                Box(
                    Modifier.size(38.dp).clip(SmallShape).pointerHoverIcon(PointerIcon.Hand).clickable { onPick(EmojiStickerPrefix + emoji) },
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 22.sp) }
            }
        }
    }
}

@Composable
private fun TextPicker() {
    PickerBox {
        val name = DiscEditor.playlistId?.let { Library.playlist(it)?.name } ?: DiscEditor.design.name
        val design = DiscEditor.design
        val cjk = name.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val vinyl = design.format == DiscFormat.VINYL
        val labelScale = design.label / 0.36f
        val styles = listOf(
            "Heading" to DiscLayer(kind = LayerKind.TEXT, name = "Heading", text = name, scale = 0.5f, shadow = true),
            "Arc over" to DiscLayer(kind = LayerKind.TEXT, name = "Arc", text = name, path = TextPath.ARC_TOP, scale = 0.3f, radius = 0.78f),
            "Arc under" to DiscLayer(kind = LayerKind.TEXT, name = "Arc under", text = name, path = TextPath.ARC_BOTTOM, scale = 0.3f, radius = 0.78f),
            "All around" to DiscLayer(kind = LayerKind.TEXT, name = "Around", text = name, path = TextPath.CIRCLE, scale = 0.2f, radius = 0.88f, font = DiscFont.SF_BOLD.id, spacing = 0.1f),
            "Repeat" to DiscLayer(kind = LayerKind.TEXT, name = "Repeat", text = name, path = TextPath.REPEAT, scale = 0.5f, rotation = -10f, outline = true, opacity = 0.9f),
            "Ink brush" to DiscLayer(
                kind = LayerKind.TEXT, name = "Ink brush", text = if (cjk) name else "音乐", font = DiscFont.INK_BRUSH.id, scale = 0.8f,
                colors = listOf(ink()), effects = listOf(LayerEffect(EffectKind.INK, 0.55f)),
            ),
            "Vertical" to DiscLayer(
                kind = LayerKind.TEXT, name = "Vertical", text = if (cjk) name else "夜间音乐", path = TextPath.VERTICAL, font = DiscFont.INK_BRUSH.id,
                scale = 0.26f, x = 0.62f, colors = listOf(ink()),
            ),
            "Neon" to DiscLayer(
                kind = LayerKind.TEXT, name = "Neon", text = name, font = DiscFont.MARKER.id, scale = 0.42f, rotation = -6f,
                effects = listOf(LayerEffect(EffectKind.GLOW, 0.55f, 0xFFFF3D9A), LayerEffect(EffectKind.BLOOM, 0.5f)),
            ),
            "Gothic" to DiscLayer(
                kind = LayerKind.TEXT, name = "Blackletter", text = name, font = DiscFont.BLACKLETTER.id, scale = 0.5f,
                colors = listOf(ink()), effects = listOf(LayerEffect(EffectKind.SMEAR, 0.4f)),
            ),
            "Caption" to DiscLayer(
                kind = LayerKind.TEXT, name = "Caption", text = "${name.uppercase()}\nSIDE A  /  33⅓ RPM\n${java.time.Year.now()}  CONVX",
                font = DiscFont.MONO.id, align = TextAlignment.LEFT, scale = 0.08f, y = 0.55f, colors = listOf(ink()),
            ),
            "Tracklist" to DiscLayer(
                kind = LayerKind.TEXT, name = "Tracklist", text = tracklist(), font = DiscFont.MONO.id, align = TextAlignment.LEFT,
                scale = if (vinyl) 0.05f * labelScale else 0.07f, y = if (vinyl) design.label * 0.45f else 0.55f,
                area = if (vinyl) LayerArea.MIDDLE else LayerArea.DISC, colors = listOf(if (vinyl) 0xFF15151A else ink()),
            ),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            styles.forEach { (label, layer) ->
                Column(
                    Modifier
                        .width(62.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { DiscEditor.add(layer.copy(id = com.convx.desktop.data.newLayerId())) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LayerPreview(textPreview(layer), 58.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(label, style = ConvxType.Caption, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
                }
            }
        }
    }
}

/** The playlist's first songs, numbered, for a record's label; made-up ones for a design on its own. */
private fun tracklist(): String =
    DiscPresets.tracklist(DiscEditor.playlistId?.let { Library.playlist(it)?.songs }.orEmpty().map { it.title })

/** [layer] made big enough to read on a 58dp tile: a short word, larger, the arcs pulled in. */
private fun textPreview(layer: DiscLayer): DiscLayer {
    val word = layer.text.split(' ').firstOrNull { it.isNotBlank() }?.take(6) ?: "Aa"
    val base = layer.copy(x = 0f, y = 0f, area = LayerArea.DISC, colors = listOf(0xFFFFFFFF))
    if (layer.path == TextPath.STRAIGHT && '\n' in layer.text) {
        return base.copy(text = layer.text.split('\n').take(3).joinToString("\n") { it.take(9) }, scale = 0.42f)
    }
    return when (layer.path) {
        TextPath.STRAIGHT -> base.copy(text = word, scale = 1.25f, rotation = -8f)
        TextPath.VERTICAL -> base.copy(text = layer.text.take(3), scale = 0.75f)
        TextPath.ARC_TOP, TextPath.ARC_BOTTOM -> base.copy(text = layer.text.take(12), scale = 0.95f, radius = 0.6f)
        TextPath.CIRCLE -> base.copy(scale = 0.7f, radius = 0.72f)
        TextPath.REPEAT -> base.copy(text = word, scale = 1f)
    }
}

@Composable
private fun FillPicker() {
    PickerBox {
        val fills = listOf(
            DiscLayer(kind = LayerKind.FILL, name = "Colour", colors = listOf(0xFFFA2D48)),
            DiscLayer(kind = LayerKind.FILL, name = "Sunset", fill = FillKind.LINEAR, rotation = 90f, colors = listOf(0xFFFFC371, 0xFFFF5F6D, 0xFF6A3093)),
            DiscLayer(kind = LayerKind.FILL, name = "Glow", fill = FillKind.RADIAL, colors = listOf(0xFFFFF1C1, 0xFFFF8A65, 0xFF7B1E3A)),
            DiscLayer(kind = LayerKind.FILL, name = "Rainbow", fill = FillKind.SWEEP, colors = listOf(0xFF8EC5FC, 0xFFE0C3FC, 0xFFFFC3A0, 0xFFA1FFCE), opacity = 0.85f, blend = LayerBlend.MULTIPLY),
            DiscLayer(kind = LayerKind.FILL, name = "Ocean", fill = FillKind.LINEAR, rotation = 45f, colors = listOf(0xFF0F2027, 0xFF2C5364, 0xFF33D1FF)),
            DiscLayer(kind = LayerKind.FILL, name = "Chrome", fill = FillKind.SWEEP, colors = listOf(0xFFCFD9DF, 0xFFF5F7FA, 0xFFA1C4FD, 0xFFE2D1F9)),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            fills.forEach { fill ->
                Tooltip(fill.name) {
                    Box(Modifier.clip(CircleShape).pointerHoverIcon(PointerIcon.Hand).clickable { DiscEditor.add(fill.copy(id = com.convx.desktop.data.newLayerId())) }) {
                        LayerPreview(fill, 46.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagePicker() {
    PickerBox {
        // A record's label can wear a picture of its own, cut round to fit it.
        val vinyl = DiscEditor.design.format == DiscFormat.VINYL
        var onLabel by remember { mutableStateOf(false) }
        if (vinyl) {
            Choices(listOf(false, true), onLabel, { if (it) "On the label" else "On the disc" }) { onLabel = it }
            Spacer(Modifier.height(6.dp))
        }
        val label = vinyl && onLabel
        PickerRow("From your PC…") { addImageFromPc(label) }
        val playlist = DiscEditor.playlistId?.let { id -> Library.state.collectAsState().value.localPlaylists.firstOrNull { it.id == id } }
        val covers = remember(playlist?.songs) { playlist?.songs?.mapNotNull { it.thumbnail }?.distinct()?.take(16).orEmpty() }
        if (covers.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("From this playlist's songs", style = ConvxType.Caption, color = ConvxColors.SecondaryLabel)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                covers.forEach { url ->
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).pointerHoverIcon(PointerIcon.Hand).clickable { addImageFromUrl(url, label) }) {
                        Artwork(url, Modifier.size(48.dp), size = 200, shape = RoundedCornerShape(6.dp))
                    }
                }
            }
        }
    }
}

private fun newImage(asset: String, frame: ImageFrame): DiscLayer {
    val (x, y) = spot()
    return DiscLayer(kind = LayerKind.IMAGE, name = "Picture", asset = asset, frame = frame, x = x, y = y, scale = 0.32f, rotation = Random.nextInt(-12, 12).toFloat(), shadow = true, amount = 1f)
}

/** A picture filling a record's label, under the label's text. */
private fun labelImage(asset: String): DiscLayer = DiscLayer(
    kind = LayerKind.IMAGE, name = "Label picture", asset = asset, frame = ImageFrame.CIRCLE, scale = DiscEditor.design.label,
    area = LayerArea.MIDDLE, amount = 1f,
)

private fun addImageFromPc(onLabel: Boolean = false) {
    val file = pickFile("Add a picture", DiscDesigns.ImageExtensions) ?: return
    AppScope.launch(Dispatchers.Main) {
        withContext(Dispatchers.IO) { runCatching { DiscDesigns.importImage(file) } }
            .onSuccess {
                DiscEditor.add(if (onLabel) labelImage(it) else newImage(it, ImageFrame.SQUARE).copy(x = 0f, y = 0f, scale = 1f, rotation = 0f, shadow = false))
            }
            .onFailure { Overlays.toast(it.message ?: "Couldn't add that picture", "error") }
    }
}

private fun addImageFromUrl(url: String, onLabel: Boolean = false) {
    AppScope.launch(Dispatchers.Main) {
        val asset = withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = ImageCache.load(artworkUrl(url, 600) ?: url) ?: error("Couldn't load that cover")
                val bytes = SkiaImage.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes ?: error("Couldn't read that cover")
                DiscDesigns.importImage(bytes, "png")
            }
        }
        asset.onSuccess { DiscEditor.add(if (onLabel) labelImage(it) else newImage(it, ImageFrame.PHOTO)) }
            .onFailure { Overlays.toast(it.message ?: "Couldn't add that cover", "error") }
    }
}

@Composable
private fun PickerBox(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(TileShape).background(Color.White.copy(alpha = 0.04f)).padding(10.dp)) { content() }
}

@Composable
private fun PickerRow(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(SmallShape)
            .background(if (hovered) Color.White.copy(alpha = 0.12f) else FieldBg)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interaction, null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = ConvxType.SubheadStrong, color = Color.White, modifier = Modifier.weight(1f))
        ConvxIcon("add", tint = Color.White.copy(alpha = 0.7f), size = 16.dp)
    }
}

// ---- Inspector (right) -----------------------------------------------------------------------

@Composable
internal fun InspectorPanel(modifier: Modifier) {
    Column(modifier.background(PanelBg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp)) {
        LayersList()
        val layer = DiscEditor.selected
        if (layer != null) LayerProperties(layer) else DiscProperties(DiscEditor.design)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LayersList() {
    val design = DiscEditor.design
    PanelHeading("Layers") {
        Text("${design.layers.size}", style = ConvxType.Caption, color = ConvxColors.TertiaryLabel)
    }
    if (design.layers.isEmpty()) {
        Text("Nothing on the disc yet. Add a layer from the left, or start from a preset.", style = ConvxType.Caption, color = ConvxColors.SecondaryLabel)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // The front layer first, as in an image editor.
        design.layers.asReversed().forEach { layer -> LayerRow(layer, layer.id == DiscEditor.selectedId) }
        LayerRow(null, DiscEditor.selectedId == null)
    }
    val selected = DiscEditor.selected
    if (selected != null) {
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton("expand_less", { DiscEditor.move(selected.id, 1) }, size = 32.dp, iconSize = 18.dp, tooltip = "Bring forward")
            IconButton("expand_more", { DiscEditor.move(selected.id, -1) }, size = 32.dp, iconSize = 18.dp, tooltip = "Send backward")
            IconButton("content_copy", { DiscEditor.duplicate() }, size = 32.dp, iconSize = 17.dp, tooltip = "Duplicate (Ctrl+D)")
            IconButton("delete", { DiscEditor.delete() }, size = 32.dp, iconSize = 17.dp, tint = Color(0xFFFF453A), tooltip = "Delete (Del)")
        }
    }
}

/** One row of the layers list; null is the disc itself, always at the bottom. */
@Composable
private fun LayerRow(layer: DiscLayer?, selected: Boolean) {
    val accent = LocalAccent.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(SmallShape)
            .background(if (selected) accent.copy(alpha = 0.25f) else if (hovered) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interaction, null) { DiscEditor.selectedId = layer?.id }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (layer == null) {
            ConvxIcon("disc_studio", tint = Color.White.copy(alpha = 0.8f), size = 18.dp)
            Spacer(Modifier.width(10.dp))
            Text(if (DiscEditor.design.format == DiscFormat.CD) "The CD" else "The record", style = ConvxType.Subhead, color = Color.White, modifier = Modifier.weight(1f))
            return@Row
        }
        ConvxIcon(layer.kind.icon, tint = Color.White.copy(alpha = if (layer.visible) 0.8f else 0.35f), size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            layer.name.ifBlank { layer.kind.label },
            style = ConvxType.Subhead,
            color = Color.White.copy(alpha = if (layer.visible) 1f else 0.45f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (layer.effects.isNotEmpty()) {
            Tooltip(layer.effects.joinToString(", ") { it.kind.label }) {
                ConvxIcon("sparks", tint = Color.White.copy(alpha = 0.45f), size = 14.dp, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
        if (hovered || layer.locked) {
            RowIcon("lock", if (layer.locked) "Unlock" else "Lock", if (layer.locked) 0.9f else 0.4f) {
                DiscEditor.commitLayer(layer.id) { it.copy(locked = !it.locked) }
            }
        }
        RowIcon(if (layer.visible) "eye" else "hide_image", if (layer.visible) "Hide" else "Show") {
            DiscEditor.commitLayer(layer.id) { it.copy(visible = !it.visible) }
        }
    }
}

@Composable
private fun RowIcon(icon: String, tip: String, alpha: Float = 0.75f, onClick: () -> Unit) {
    Tooltip(tip) {
        Box(Modifier.size(28.dp).clip(CircleShape).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            ConvxIcon(icon, tint = Color.White.copy(alpha = alpha), size = 15.dp)
        }
    }
}

@Composable
private fun DiscProperties(design: DiscDesign) {
    PanelHeading("The disc")
    Choices(DiscFormat.entries, design.format, { it.label }) { format ->
        DiscEditor.change { d ->
            d.copy(
                format = format,
                base = if (format == DiscFormat.CD) DiscDesign.CdSilver else DiscDesign.VinylBlack,
            )
        }
    }
    ColorRow(if (design.format == DiscFormat.CD) "Disc colour" else "Vinyl colour", listOf(design.base), max = 1) { colors ->
        DiscEditor.live(DiscEditor.design.copy(base = colors.first()))
    }
    QuickColors(if (design.format == DiscFormat.CD) CdColors else VinylColors, design.base) { color -> DiscEditor.change { it.copy(base = color) } }
    if (design.format == DiscFormat.VINYL) {
        ColorRow("Label colour", listOf(design.labelColor), max = 1) { colors ->
            DiscEditor.live(DiscEditor.design.copy(labelColor = colors.first()))
        }
        QuickColors(LabelColors, design.labelColor) { color -> DiscEditor.change { it.copy(labelColor = color) } }
        PropSlider("Grooves", design.grooves, 0f..1f, percent(design.grooves)) { DiscEditor.live(DiscEditor.design.copy(grooves = it)) }
    } else {
        PropSwitch("Clear middle", design.clearHub) { on -> DiscEditor.change { it.copy(clearHub = on) } }
    }
    PropSlider(if (design.format == DiscFormat.VINYL) "Label size" else "Middle size", design.label, 0.2f..0.7f, percent(design.label)) {
        DiscEditor.live(DiscEditor.design.copy(label = it))
    }
    PropSlider(if (design.format == DiscFormat.CD) "Rainbow shine" else "Shine", design.sheen, 0f..1f, percent(design.sheen)) {
        DiscEditor.live(DiscEditor.design.copy(sheen = it))
    }
    PropSlider("Where the light hits", design.lightAngle.mod(360f), 0f..360f, "${design.lightAngle.mod(360f).roundToInt()}°") {
        DiscEditor.live(DiscEditor.design.copy(lightAngle = it))
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "\"Middle\" and \"Outer ring\" layers are split at the middle size, so a record's label and its vinyl can have their own designs.",
        style = ConvxType.Caption,
        color = ConvxColors.TertiaryLabel,
    )
}

/** Pressed vinyl as it comes: classic black, the pale and coloured pressings, and a see-through one. */
private val VinylColors = listOf(
    0xFF131315, 0xFFEDE6D6, 0xFFF4F1EA, 0xFF9A9CA3, 0xFF4E8F2A, 0xFF6E1420, 0xFF1F3F9E, 0xFFE09A2B, 0xFFE7A3B8, 0x99505058,
)
private val CdColors = listOf(DiscDesign.CdSilver, 0xFFD8B65C, 0xFF1A1A1E, 0xFFF2F2F2, 0xFFB7A3D9)
private val LabelColors = listOf(0xFF111113, 0xFFF4F1EA, 0xFFE9DFC8, 0xFFD9E3A8, 0xFFFA2D48, 0xFF1F3F9E, 0x00000000)

/** One-click colours under a colour row. */
@Composable
private fun QuickColors(colors: List<Long>, current: Long, onPick: (Long) -> Unit) {
    FlowRow(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        colors.forEach { color -> Swatch(color, size = 22.dp, selected = color == current) { onPick(color) } }
    }
}

@Composable
private fun LayerProperties(layer: DiscLayer) {
    val id = layer.id
    fun live(transform: (DiscLayer) -> DiscLayer) = DiscEditor.liveLayer(id, transform)
    fun commit(transform: (DiscLayer) -> DiscLayer) = DiscEditor.commitLayer(id, transform)

    PanelHeading(layer.kind.label)
    TextInput("Name", layer.name, singleLine = true, key = "disc-layer-name") { text -> live { it.copy(name = text.take(40)) } }

    when (layer.kind) {
        LayerKind.FILL -> {
            Choices(FillKind.entries, layer.fill, { it.label }) { kind -> commit { it.copy(fill = kind, colors = if (kind != FillKind.SOLID && it.colors.size < 2) it.colors + 0xFF111113 else it.colors) } }
            ColorRow("Colours", layer.colors, max = if (layer.fill == FillKind.SOLID) 1 else 6) { colors -> live { it.copy(colors = colors) } }
            if (layer.fill != FillKind.SOLID) {
                PropSlider("Angle", ((layer.rotation % 360f) + 360f) % 360f, 0f..360f, "${layer.rotation.roundToInt()}°") { v -> live { it.copy(rotation = v) } }
                PropSlider("Spread", layer.scale, 0.2f..3f, percent(layer.scale)) { v -> live { it.copy(scale = v) } }
            }
        }
        LayerKind.IMAGE -> {
            PickerRow("Replace picture…") {
                val file = pickFile("Choose a picture", DiscDesigns.ImageExtensions) ?: return@PickerRow
                AppScope.launch(Dispatchers.Main) {
                    withContext(Dispatchers.IO) { runCatching { DiscDesigns.importImage(file) } }
                        .onSuccess { asset -> commit { it.copy(asset = asset) } }
                        .onFailure { Overlays.toast(it.message ?: "Couldn't use that picture", "error") }
                }
            }
            Text("Frame", style = ConvxType.Subhead, color = Color.White, modifier = Modifier.padding(top = 8.dp))
            Choices(ImageFrame.entries, layer.frame, { it.label }) { frame -> commit { it.copy(frame = frame) } }
            Text("Look", style = ConvxType.Subhead, color = Color.White, modifier = Modifier.padding(top = 4.dp))
            Choices(ImageLook.entries, layer.look, { it.label }) { look ->
                commit { it.copy(look = look, colors = if (look == ImageLook.DUOTONE && it.colors.size < 2) listOf(0xFF1B1035, 0xFFFF6FB5) else it.colors) }
            }
            if (layer.look != ImageLook.NONE) {
                PropSlider(if (layer.look == ImageLook.PIXEL) "Block size" else "Strength", layer.amount, 0f..1f, percent(layer.amount)) { v -> live { it.copy(amount = v) } }
            }
            if (layer.look == ImageLook.DUOTONE) {
                ColorRow("Shadows and lights", layer.colors.take(2), min = 2, max = 2) { colors -> live { it.copy(colors = colors) } }
            }
        }
        LayerKind.TEXT -> {
            TextInput("Text", layer.text, singleLine = false, key = "disc-layer-text") { text -> live { it.copy(text = text.take(200)) } }
            Text("Shape", style = ConvxType.Subhead, color = Color.White, modifier = Modifier.padding(top = 8.dp))
            Choices(TextPath.entries, layer.path, { it.label }) { path -> commit { it.copy(path = path) } }
            if (layer.boxed && (layer.text.contains('\n') || layer.path == TextPath.VERTICAL)) {
                Text(if (layer.path == TextPath.VERTICAL) "Columns start" else "Lines line up", style = ConvxType.Subhead, color = Color.White, modifier = Modifier.padding(top = 4.dp))
                Choices(TextAlignment.entries, layer.align, { align ->
                    if (layer.path != TextPath.VERTICAL) align.label else when (align) {
                        TextAlignment.LEFT -> "Top"
                        TextAlignment.CENTER -> "Middle"
                        TextAlignment.RIGHT -> "Bottom"
                    }
                }) { align -> commit { it.copy(align = align) } }
            }
            Text("Font", style = ConvxType.Subhead, color = Color.White, modifier = Modifier.padding(top = 4.dp))
            Choices(DiscFont.entries, DiscFont.of(layer.font), { it.label }) { font -> commit { it.copy(font = font.id) } }
            ColorRow("Colour", layer.colors.take(1), max = 1) { colors -> live { it.copy(colors = colors) } }
            if (layer.onCircle) {
                PropSlider("Circle size", layer.radius, 0.1f..1.1f, percent(layer.radius)) { v -> live { it.copy(radius = v) } }
            }
            PropSlider("Letter spacing", layer.spacing, -0.1f..0.6f, percent(layer.spacing)) { v -> live { it.copy(spacing = v) } }
            PropSwitch(if (layer.path == TextPath.REPEAT) "Outline every other row" else "Outline only", layer.outline) { on -> commit { it.copy(outline = on) } }
        }
        LayerKind.PATTERN -> {
            Choices(PatternKind.entries, layer.pattern, { it.label }) { pattern -> commit { it.copy(pattern = pattern, name = pattern.label) } }
            ColorRow("Colours", layer.colors, max = 6) { colors -> live { it.copy(colors = colors) } }
            PropSlider("How many", layer.density, 0f..1f, percent(layer.density)) { v -> live { it.copy(density = v) } }
            PropSlider("Size", layer.amount, 0f..1f, percent(layer.amount)) { v -> live { it.copy(amount = v) } }
            PickerRow("Shuffle") { commit { it.copy(seed = Random.nextInt(100_000)) } }
        }
        LayerKind.STICKER -> {
            if (!layer.sticker.startsWith(EmojiStickerPrefix)) {
                ColorRow("Colour", layer.colors.take(1), max = 1) { colors -> live { it.copy(colors = colors) } }
                PropSwitch("White sticker border", layer.outline) { on -> commit { it.copy(outline = on) } }
                if (layer.outline) PropSlider("Border", layer.amount, 0f..1f, percent(layer.amount)) { v -> live { it.copy(amount = v) } }
            }
            Text("Change sticker", style = ConvxType.Subhead, color = Color.White, modifier = Modifier.padding(top = 6.dp))
            StickerPicker { sticker -> commit { it.copy(sticker = sticker, name = DiscSticker.of(sticker)?.label ?: "Emoji") } }
        }
        LayerKind.SHAPE -> {
            Choices(ShapeKind.entries, layer.shape, { it.label }) { kind -> commit { it.copy(shape = kind, name = kind.label) } }
            ColorRow("Colour", layer.colors.take(1), max = 1) { colors -> live { it.copy(colors = colors) } }
            if (layer.shape.outlinable) PropSwitch("Outline only", layer.outline) { on -> commit { it.copy(outline = on) } }
            val outlined = layer.outline && layer.shape.outlinable
            if (layer.shape.lines || outlined) {
                PropSlider("Line width", layer.amount, 0f..1f, percent(layer.amount)) { v -> live { it.copy(amount = v) } }
            }
            if (outlined) {
                PropSlider("Echoes", layer.density, 0f..1f, "${1 + (layer.density * 4).roundToInt()}") { v -> live { it.copy(density = v) } }
            }
            if (layer.shape == ShapeKind.RULER) {
                PropSlider("Ticks", layer.density, 0f..1f, "${10 + (layer.density * 50).roundToInt()}") { v -> live { it.copy(density = v) } }
            }
            PropSlider("Width", layer.aspect, 0.2f..12f, "×${"%.1f".format(layer.aspect)}") { v -> live { it.copy(aspect = v) } }
            if (layer.shape.shuffles) PickerRow("Shuffle") { commit { it.copy(seed = Random.nextInt(100_000)) } }
        }
    }

    PanelHeading("Placement")
    if (layer.kind != LayerKind.FILL) {
        PropSlider(if (layer.kind == LayerKind.TEXT) "Text size" else "Size", layer.scale, 0.01f..2f, percent(layer.scale)) { v -> live { it.copy(scale = v) } }
        PropSlider("Turn", ((layer.rotation + 180f).mod(360f)) - 180f, -180f..180f, "${(((layer.rotation + 180f).mod(360f)) - 180f).roundToInt()}°") { v -> live { it.copy(rotation = v) } }
    }
    if (layer.x != 0f || layer.y != 0f) PickerRow("Move to the middle") { commit { it.copy(x = 0f, y = 0f) } }
    Text("Printed on", style = ConvxType.Subhead, color = Color.White, modifier = Modifier.padding(top = 8.dp))
    Choices(LayerArea.entries, layer.area, { it.label }) { area -> commit { it.copy(area = area) } }

    PanelHeading("Look")
    PropSlider("Opacity", layer.opacity, 0f..1f, percent(layer.opacity)) { v -> live { it.copy(opacity = v) } }
    Text("Blend", style = ConvxType.Subhead, color = Color.White, modifier = Modifier.padding(top = 4.dp))
    Choices(LayerBlend.entries, layer.blend, { it.label }) { blend -> commit { it.copy(blend = blend) } }
    PropSwitch("Drop shadow", layer.shadow) { on -> commit { it.copy(shadow = on) } }

    EffectsSection(layer)
}

/** The layer's effects, each with its own strength (and colour), in the order they're applied, and the ones it could add. */
@Composable
private fun EffectsSection(layer: DiscLayer) {
    val id = layer.id
    fun change(index: Int, live: Boolean, transform: (LayerEffect) -> LayerEffect) {
        val update: (DiscLayer) -> DiscLayer = { l -> l.copy(effects = l.effects.mapIndexed { i, e -> if (i == index) transform(e) else e }) }
        if (live) DiscEditor.liveLayer(id, update) else DiscEditor.commitLayer(id, update)
    }
    PanelHeading("Effects")
    if (layer.effects.isEmpty()) {
        Text("Add as many as you like. They're drawn with the layer, in this order.", style = ConvxType.Caption, color = ConvxColors.TertiaryLabel)
    }
    layer.effects.forEachIndexed { index, effect ->
        Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(SmallShape).background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(effect.kind.label, style = ConvxType.SubheadStrong, color = Color.White)
                    Text(effect.kind.tip, style = ConvxType.Caption, color = ConvxColors.TertiaryLabel, maxLines = 2)
                }
                if (index > 0) {
                    SmallRound("expand_less", "Apply earlier") {
                        DiscEditor.commitLayer(id) { l ->
                            l.copy(effects = l.effects.toMutableList().also { it.add(index - 1, it.removeAt(index)) })
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }
                SmallRound("close", "Remove") {
                    DiscEditor.commitLayer(id) { l -> l.copy(effects = l.effects.filterIndexed { i, _ -> i != index }) }
                }
            }
            PropSlider(strengthLabel(effect.kind), effect.amount, 0f..1f, percent(effect.amount)) { v -> change(index, live = true) { it.copy(amount = v) } }
            if (effect.kind.colored) {
                ColorRow(colorLabel(effect.kind), listOf(effect.color), max = 1) { colors -> change(index, live = true) { it.copy(color = colors.first()) } }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        EffectKind.entries.filter { kind -> layer.effects.none { it.kind == kind } }.forEach { kind ->
            Tooltip(kind.tip) {
                ChoiceChip("+ ${kind.label}", false) { DiscEditor.commitLayer(id) { it.copy(effects = it.effects + LayerEffect(kind)) } }
            }
        }
    }
}

private fun strengthLabel(kind: EffectKind) = when (kind) {
    EffectKind.BLOOM -> "Glow"
    EffectKind.GLOW -> "Size"
    EffectKind.SMEAR -> "Length"
    EffectKind.HALFTONE -> "Dot size"
    EffectKind.INK -> "Roughness"
    EffectKind.SKETCH -> "Detail"
    else -> "Strength"
}

private fun colorLabel(kind: EffectKind) = when (kind) {
    EffectKind.GLOW -> "Halo colour"
    EffectKind.HALFTONE -> "Ink"
    else -> "Line colour"
}

/** A text box whose typing, from when it takes the keyboard to when it lets go, is one undo step. */
@Composable
private fun TextInput(label: String, value: String, singleLine: Boolean, key: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var kept by remember { mutableStateOf(false) }
    DisposableEffect(key) { onDispose { UiState.typingFocusChanged(key, false) } }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = ConvxType.Subhead, color = Color.White)
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(SmallShape)
                .background(if (focused) Color.White.copy(alpha = 0.11f) else FieldBg)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = { text ->
                    if (!kept) {
                        DiscEditor.keepUndo()
                        kept = true
                    }
                    onChange(text)
                },
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 4,
                textStyle = ConvxType.Body.copy(color = Color.White),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth().onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) kept = false
                    UiState.typingFocusChanged(key, it.isFocused)
                },
            )
        }
    }
}
