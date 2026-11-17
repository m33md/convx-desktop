/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.data

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.random.Random

/*
 * A disc someone designed in Disc Studio: a CD or a vinyl record, its print built from layers
 * (fills, pictures, text, patterns, stickers) from the bottom up. Colours are ARGB longs, sizes and
 * places are fractions of the disc's radius, so a design looks the same at any size. The same JSON
 * is what a .convxdisc file carries, so a design can be shared and opened on another PC.
 */

@Serializable
enum class DiscFormat(val label: String) { CD("CD"), VINYL("Vinyl") }

@Serializable
enum class LayerKind(val label: String, val icon: String) {
    FILL("Fill", "palette"),
    IMAGE("Image", "image"),
    TEXT("Text", "text_format"),
    PATTERN("Pattern", "grid_view"),
    STICKER("Sticker", "star_fill"),
    SHAPE("Shape", "shapes"),
    PAINT("Brush", "brush"),
}

/** How a layer mixes with what's under it, as in an image editor. */
@Serializable
enum class LayerBlend(val label: String) {
    NORMAL("Normal"),
    MULTIPLY("Multiply"),
    SCREEN("Screen"),
    OVERLAY("Overlay"),
    SOFT_LIGHT("Soft light"),
    ADD("Add"),
    DIFFERENCE("Difference"),
    COLOR("Colour"),
}

/** The part of the disc a layer is printed on: all of it, the middle (a record's label) or the ring around it. */
@Serializable
enum class LayerArea(val label: String) { DISC("Whole disc"), MIDDLE("Middle"), RING("Outer ring") }

@Serializable
enum class FillKind(val label: String) {
    SOLID("Solid"),
    LINEAR("Linear"),
    RADIAL("Radial"),
    SWEEP("Sweep"),
    /** Rainbow ribbons over metal: the first colour is the metal, any others tint the ribbons. */
    HOLO("Holographic"),
    /** Soap-bubble pastels; two or more colours steer them. */
    PEARL("Pearl"),
    /** A bent mirror: the first colour is the sky it reflects, the second the ground. */
    CHROME("Liquid chrome"),
    /** Soft pools of every colour melting together. */
    MESH("Mesh gradient"),
    ;

    /** Drawn by a finish shader, which reads the layer's waves and shuffle too. */
    val finish: Boolean get() = this == HOLO || this == PEARL || this == CHROME || this == MESH
}

@Serializable
enum class ImageLook(val label: String) {
    NONE("Original"),
    MONO("Mono"),
    DUOTONE("Duotone"),
    PIXEL("Pixel"),
    VINTAGE("Vintage"),
    GLOW("Glow"),
    INVERT("Invert"),
    /** The picture in rainbow foil, its darks kept. */
    HOLO("Holo"),
}

@Serializable
enum class ImageFrame(val label: String) {
    /** The picture as it is, its own shape kept. */
    WHOLE("Whole picture"),
    SQUARE("Square"),
    CIRCLE("Circle"),
    /** A thin white border all round, as a print from a photo lab. */
    PRINT("Photo print"),
    PHOTO("Polaroid"),
    ROUNDED("Rounded"),
    /** Cut round with a white border and rounded corners, like a sticker. */
    STICKER("Sticker"),
}

@Serializable
enum class TextPath(val label: String) {
    STRAIGHT("Straight"),
    ARC_TOP("Arc over"),
    ARC_BOTTOM("Arc under"),
    CIRCLE("All around"),
    REPEAT("Repeat"),
    /** Top to bottom, the lines right to left, as Chinese and Japanese are set on a spine. */
    VERTICAL("Vertical"),
}

@Serializable
enum class TextAlignment(val label: String) { LEFT("Left"), CENTER("Centre"), RIGHT("Right") }

@Serializable
enum class PatternKind(val label: String) {
    STARS("Stars"),
    SPARKLES("Sparkles"),
    DOTS("Dots"),
    HALFTONE("Halftone"),
    STRIPES("Stripes"),
    CHECKER("Checker"),
    RAYS("Sunburst"),
    RINGS("Rings"),
    WAVES("Waves"),
    CONFETTI("Confetti"),
    HEARTS("Hearts"),
    PIXELS("Pixels"),
    MARBLE("Marble"),
    SPLATTER("Splatter"),
    GRAIN("Grain"),
    PAPER("Paper"),
    SCREEN_TONE("Screen tone"),
    SCANLINES("Scan lines"),
    STREAKS("Light streaks"),
    LIGHT_LEAK("Light leak"),
    HUD("HUD marks"),
    CHEVRON("Chevron"),
    HONEYCOMB("Honeycomb"),
    PLAID("Plaid"),
    SCALES("Scales"),
    ARGYLE("Argyle"),
    CONTOURS("Contour lines"),
    TERRAZZO("Terrazzo"),
    LEOPARD("Leopard"),
    /** The layer's sticker, over and over. */
    STICKERS("Sticker pattern"),
    GLITTER("Glitter"),
    PRISM("Prism foil"),
    SWIRL("Paint swirl"),
    WATERCOLOR("Watercolour"),
    SMOKE("Smoke"),
    NEBULA("Nebula"),
    CANVAS("Canvas"),
    DENIM("Denim"),
}

/** Lines and cut-outs for collages, sized by the layer's scale and stretched by its aspect. */
@Serializable
enum class ShapeKind(val label: String) {
    BOX("Box"),
    CIRCLE("Circle"),
    BADGE("Badge"),
    BLOB("Blob"),
    LINE("Line"),
    CROSSHAIR("Crosshair"),
    CORNERS("Viewfinder"),
    RULER("Ruler"),
    BARCODE("Barcode"),
    TAPE("Tape"),
    SCRAP("Paper scrap"),
    TRIANGLE("Triangle"),
    STAR("Star"),
    HEART("Heart"),
    HEXAGON("Hexagon"),
    CROSS("Plus"),
    ARROW("Arrow"),
    SPEECH("Speech bubble"),
    BURST("Burst"),
    WAVE("Wavy line"),
    ZIGZAG("Zigzag"),
    SPIRAL("Spiral"),
    FLOWER("Flower"),
    DAISY("Daisy"),
    CLOUD("Cloud"),
    BOLT("Lightning"),
    MOON("Moon"),
    SUN("Sun"),
    SPARKLE("Sparkle"),
    DIAMOND("Diamond"),
    RING("Ring"),
    ASTERISK("Asterisk"),
    DROP("Drop"),
    ARCH("Arch"),
    HALF_CIRCLE("Half circle"),
    QUARTER("Quarter circle"),
    PILL("Pill"),
    TICKET("Ticket"),
    SCALLOP("Scalloped"),
    SQUIGGLE("Squiggle"),
    MOUNTAINS("Mountains"),
    PINE("Pine tree"),
    BIRD("Bird"),
    CASSETTE("Cassette"),
    TONEARM("Tonearm"),
    ;

    /** Drawn with lines whether or not the layer is outlined. */
    val lines: Boolean get() = this == LINE || this == CROSSHAIR || this == CORNERS || this == RULER || this == WAVE || this == ZIGZAG || this == SPIRAL ||
        this == ASTERISK || this == SQUIGGLE || this == BIRD || this == RING

    /** Can be a filled shape or just its outline. */
    val outlinable: Boolean get() = this == BOX || this == CIRCLE || this == BADGE || this == BLOB || this == TRIANGLE || this == STAR ||
        this == HEART || this == HEXAGON || this == CROSS || this == ARROW || this == SPEECH || this == BURST || this == FLOWER || this == CLOUD ||
        this == BOLT || this == MOON || this == SUN || this == SPARKLE || this == DIAMOND || this == DROP || this == ARCH || this == HALF_CIRCLE ||
        this == QUARTER || this == PILL || this == TICKET || this == SCALLOP

    /** Drawn from the layer's seed, so Shuffle gives a new one. */
    val shuffles: Boolean get() = this == BLOB || this == BARCODE || this == TAPE || this == SCRAP || this == SQUIGGLE || this == MOUNTAINS || this == CLOUD

    /** Takes a second colour: the snow on mountains, a daisy's middle, a cassette's label, a tonearm's metal. */
    val twoColors: Boolean get() = this == MOUNTAINS || this == DAISY || this == CASSETTE || this == TONEARM || this == PINE
}

/**
 * Effects on one layer, applied in order by Skia as the layer is drawn. Each one is a filter on the
 * layer's own pixels, so nothing is kept per frame and a design costs the same at rest as without them.
 */
@Serializable
enum class EffectKind(val label: String, val tip: String, val colored: Boolean = false) {
    BLOOM("Bloom", "Bright parts bleed soft light"),
    GLOW("Glow", "A coloured halo round it", colored = true),
    BLUR("Blur", "Soft focus"),
    SMEAR("Motion smear", "Streaks along the layer, like a moving camera"),
    GLITCH("Glitch", "Sliced and split into red and blue"),
    HALFTONE("Halftone", "Printed in dots: dark parts big, light parts small", colored = true),
    GRAIN("Grain", "Film grain on the layer"),
    INK("Ink texture", "Rough brush edges, dry streaks and ink specks"),
    SKETCH("Sketch lines", "Only its outlines, drawn as lines", colored = true),
    CUT_WHITE("Cut out white", "White and light parts turn see-through, so the disc shows there"),
    CUT_BLACK("Cut out black", "Black and dark parts turn see-through"),
    SOFT_EDGES("Soft edges", "Its edges fade into the disc"),
    FOIL("Holo foil", "Printed in rainbow foil that shifts across it"),
    CHROME("Chrome", "Printed in polished liquid metal"),
    OUTLINE("Outline", "A border round its shape, like a die-cut sticker", colored = true),
    RECOLOR("Recolour", "All one colour, its light and dark kept", colored = true),
}

@Serializable
data class LayerEffect(
    val kind: EffectKind,
    val amount: Float = 0.5f,
    /** Glow's halo, halftone's ink, sketch's lines. */
    val color: Long = defaultColor(kind),
) {
    companion object {
        fun defaultColor(kind: EffectKind): Long = when (kind) {
            EffectKind.GLOW -> 0xFFFF6FB5
            EffectKind.HALFTONE -> 0xFF15151A
            EffectKind.SKETCH -> 0xFFFFFFFF
            EffectKind.OUTLINE -> 0xFFFFFFFF
            EffectKind.RECOLOR -> 0xFF2E4A8C
            else -> 0xFFFFFFFF
        }
    }
}

/** The fonts text can use: the ones Convx ships, then Windows' own families. */
enum class DiscFont(val id: String, val label: String) {
    CHILLAX_BOLD("chillax-bold", "Chillax Bold"),
    CHILLAX("chillax", "Chillax"),
    CHILLAX_LIGHT("chillax-light", "Chillax Light"),
    OUTFIT("outfit", "Outfit"),
    JAKARTA("jakarta", "Plus Jakarta"),
    SF_BOLD("sf-bold", "SF Pro Bold"),
    SF("sf", "SF Pro"),
    SERIF("serif", "Serif"),
    MONO("mono", "Mono"),
    SCRIPT("script", "Script"),
    IMPACT("impact", "Impact"),
    INK_BRUSH("ink-brush", "Ink brush 毛笔"),
    BLACKLETTER("blackletter", "Blackletter"),
    MARKER("marker", "Marker"),
    ;

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: CHILLAX_BOLD
    }
}

/** The stickers Convx draws itself; an emoji sticker is "emoji:" and the emoji. */
enum class DiscSticker(val id: String, val label: String) {
    STAR("star", "Star"),
    SPARKLE("sparkle", "Sparkle"),
    HEART("heart", "Heart"),
    BOLT("bolt", "Bolt"),
    FLOWER("flower", "Flower"),
    SMILE("smile", "Smile"),
    MOON("moon", "Moon"),
    CROWN("crown", "Crown"),
    BURST("burst", "Badge"),
    NOTE("note", "Note"),
    CHERRY("cherry", "Cherries"),
    ARROW("arrow", "Arrow"),
    ;

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
    }
}

const val EmojiStickerPrefix = "emoji:"

/**
 * A picture Convx paints itself (an "album cover" of shapes and colour, numbered), for designs that
 * show how photos sit before any are added. A preset started for a playlist swaps them for its songs' covers.
 */
const val SampleAssetPrefix = "sample:"

/** A sticker from the bundled library ([com.convx.desktop.data.StickerLibrary]): "icon:", its set and its name. */
const val LibraryStickerPrefix = "icon:"

/** What a brush stroke is painted with. */
@Serializable
enum class BrushTip(val label: String) {
    PEN("Pen"),
    MARKER("Marker"),
    INK("Ink brush"),
    SPRAY("Spray"),
    CHALK("Chalk"),
    NEON("Neon"),
    ERASER("Eraser"),
}

/** One stroke of a brush layer, in the layer's own space: points are x, y pairs in fractions of the disc's radius. */
@Serializable
data class BrushStroke(
    val tip: BrushTip = BrushTip.PEN,
    val color: Long = 0xFFFFFFFF,
    /** Width, as a fraction of the disc's radius. */
    val size: Float = 0.03f,
    val opacity: Float = 1f,
    val points: List<Float> = emptyList(),
    /** Where the spray's dots and the chalk's grain fall. */
    val seed: Int = 0,
)

@Serializable
data class DiscLayer(
    val id: String = newLayerId(),
    val kind: LayerKind,
    val name: String = kind.label,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val opacity: Float = 1f,
    val blend: LayerBlend = LayerBlend.NORMAL,
    val area: LayerArea = LayerArea.DISC,
    /** The layer's centre, -1..1 across the disc. */
    val x: Float = 0f,
    val y: Float = 0f,
    /** 1 is the size of the disc (for text, a large heading). */
    val scale: Float = 1f,
    /** Degrees, clockwise. For text on an arc, where on the circle it is centred. */
    val rotation: Float = 0f,
    /** Fill stops, text ink, pattern and sticker colours; most kinds read the first one or two. */
    val colors: List<Long> = listOf(0xFFFFFFFF),
    val shadow: Boolean = false,
    /** Fill */
    val fill: FillKind = FillKind.SOLID,
    /** Image: a file in the designs' asset folder. */
    val asset: String? = null,
    val look: ImageLook = ImageLook.NONE,
    val frame: ImageFrame = ImageFrame.SQUARE,
    /** Text */
    val text: String = "",
    val font: String = DiscFont.CHILLAX_BOLD.id,
    val path: TextPath = TextPath.STRAIGHT,
    /** Text on an arc or all around: the circle's radius, 0..1 of the disc's. */
    val radius: Float = 0.8f,
    val spacing: Float = 0f,
    val outline: Boolean = false,
    /** Pattern */
    val pattern: PatternKind = PatternKind.STARS,
    val density: Float = 0.5f,
    val seed: Int = 0,
    /** Sticker (and the sticker pattern): a [DiscSticker] id, [EmojiStickerPrefix] and an emoji, or a [LibraryStickerPrefix] id. */
    val sticker: String = DiscSticker.STAR.id,
    /** One more knob each kind reads its own way: pattern size, picture effect strength, sticker border, line width. */
    val amount: Float = 0.5f,
    /** Straight text: how its lines line up. */
    val align: TextAlignment = TextAlignment.CENTER,
    /** Shape */
    val shape: ShapeKind = ShapeKind.BOX,
    /** A shape's width over its height. */
    val aspect: Float = 1f,
    val effects: List<LayerEffect> = emptyList(),
    /** Brush: what was painted, oldest first. */
    val strokes: List<BrushStroke> = emptyList(),
    /** Shows only where the layer under it is, as a clipping mask: a pattern inside a shape, a picture inside text. */
    val clip: Boolean = false,
) {
    /** Whether the layer is placed somewhere (and can be picked and dragged) rather than covering the disc. */
    val placed: Boolean get() = kind == LayerKind.IMAGE || kind == LayerKind.STICKER || kind == LayerKind.SHAPE || kind == LayerKind.PAINT ||
        (kind == LayerKind.TEXT && path != TextPath.REPEAT)

    /** Text set along a circle, which is moved round it and sized by its circle. */
    val onCircle: Boolean get() = kind == LayerKind.TEXT && (path == TextPath.ARC_TOP || path == TextPath.ARC_BOTTOM || path == TextPath.CIRCLE)

    /** Text in a box: straight lines, or a vertical column. */
    val boxed: Boolean get() = kind == LayerKind.TEXT && (path == TextPath.STRAIGHT || path == TextPath.VERTICAL)
}

@Serializable
data class DiscDesign(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Untitled disc",
    val format: DiscFormat = DiscFormat.CD,
    /** Bottom first. */
    val layers: List<DiscLayer> = emptyList(),
    /** The disc itself under the print: a CD's silver, a record's vinyl. */
    val base: Long = if (format == DiscFormat.CD) CdSilver else VinylBlack,
    /** A record's paper label; transparent shows the vinyl. */
    val labelColor: Long = 0xFFFA2D48,
    /** Where the middle stops, 0..1 of the radius: a record's label, the split for "Middle" and "Outer ring" layers. */
    val label: Float = 0.36f,
    /** How much the light catches it: a CD's rainbow, a record's shine. */
    val sheen: Float = 0.8f,
    /** A record's grooves. */
    val grooves: Float = 0.7f,
    /** Where the light catches it, in degrees round the disc. */
    val lightAngle: Float = 0f,
    /** A CD's clear middle; off prints all the way in to the hole. */
    val clearHub: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** The file format's version, for designs made by a newer Convx. */
    val version: Int = 1,
) {
    fun layer(id: String?): DiscLayer? = layers.firstOrNull { it.id == id }

    /** The images it uses, which travel with it when it's shared. */
    val assets: Set<String> get() = layers.mapNotNullTo(LinkedHashSet()) { it.asset?.takeUnless { a -> a.startsWith(SampleAssetPrefix) } }

    companion object {
        const val CdSilver = 0xFFC9CACF
        const val VinylBlack = 0xFF131315
    }
}

fun newLayerId(): String = UUID.randomUUID().toString().take(12)

/**
 * Ready-made designs to start from. "{name}" in a text layer becomes the playlist's name when a
 * design is started for a playlist.
 */
object DiscPresets {
    class Preset(val key: String, val title: String, val make: () -> DiscDesign)

    private fun text(
        text: String,
        color: Long,
        path: TextPath = TextPath.STRAIGHT,
        scale: Float = 0.5f,
        font: DiscFont = DiscFont.CHILLAX_BOLD,
        x: Float = 0f,
        y: Float = 0f,
        rotation: Float = 0f,
        radius: Float = 0.8f,
        spacing: Float = 0f,
        outline: Boolean = false,
        shadow: Boolean = false,
        area: LayerArea = LayerArea.DISC,
        name: String = "Text",
        align: TextAlignment = TextAlignment.CENTER,
        opacity: Float = 1f,
        effects: List<LayerEffect> = emptyList(),
    ) = DiscLayer(
        kind = LayerKind.TEXT, name = name, text = text, colors = listOf(color), path = path, scale = scale, font = font.id,
        x = x, y = y, rotation = rotation, radius = radius, spacing = spacing, outline = outline, shadow = shadow, area = area,
        align = align, opacity = opacity, effects = effects,
    )

    private fun shape(
        kind: ShapeKind,
        color: Long,
        x: Float,
        y: Float,
        scale: Float,
        aspect: Float = 1f,
        rotation: Float = 0f,
        outline: Boolean = false,
        line: Float = 0.12f,
        echoes: Float = 0f,
        shadow: Boolean = false,
        seed: Int = 0,
        area: LayerArea = LayerArea.DISC,
        effects: List<LayerEffect> = emptyList(),
    ) = DiscLayer(
        kind = LayerKind.SHAPE, name = kind.label, shape = kind, colors = listOf(color), x = x, y = y, scale = scale, aspect = aspect,
        rotation = rotation, outline = outline, amount = line, density = echoes, shadow = shadow, seed = seed, area = area, effects = effects,
    )

    private fun fx(kind: EffectKind, amount: Float = 0.5f, color: Long = LayerEffect.defaultColor(kind)) = LayerEffect(kind, amount, color)

    private fun fill(vararg colors: Long, kind: FillKind = FillKind.SOLID, rotation: Float = 0f, opacity: Float = 1f, blend: LayerBlend = LayerBlend.NORMAL, area: LayerArea = LayerArea.DISC, name: String = "Fill") =
        DiscLayer(kind = LayerKind.FILL, name = name, colors = colors.toList(), fill = kind, rotation = rotation, opacity = opacity, blend = blend, area = area)

    private const val Ink = 0xFF15151A

    private fun pattern(
        kind: PatternKind,
        vararg colors: Long,
        density: Float = 0.5f,
        amount: Float = 0.5f,
        seed: Int = 0,
        opacity: Float = 1f,
        blend: LayerBlend = LayerBlend.NORMAL,
        area: LayerArea = LayerArea.DISC,
        rotation: Float = 0f,
        effects: List<LayerEffect> = emptyList(),
    ) = DiscLayer(
        kind = LayerKind.PATTERN, name = kind.label, pattern = kind, colors = colors.toList(), density = density, amount = amount,
        seed = seed, opacity = opacity, blend = blend, area = area, rotation = rotation, effects = effects,
    )

    private fun sticker(id: String, color: Long, x: Float, y: Float, scale: Float, rotation: Float = 0f, outline: Boolean = true, shadow: Boolean = true) =
        DiscLayer(kind = LayerKind.STICKER, name = DiscSticker.of(id)?.label ?: "Sticker", sticker = id, colors = listOf(color), x = x, y = y, scale = scale, rotation = rotation, outline = outline, shadow = shadow)

    private fun image(asset: String, x: Float, y: Float, scale: Float, frame: ImageFrame = ImageFrame.PRINT, rotation: Float = 0f, shadow: Boolean = true) =
        DiscLayer(kind = LayerKind.IMAGE, name = "Photo", asset = asset, frame = frame, x = x, y = y, scale = scale, rotation = rotation, shadow = shadow, amount = 1f)

    private fun library(name: String, x: Float, y: Float, scale: Float, rotation: Float = 0f, outline: Boolean = false, shadow: Boolean = false, effects: List<LayerEffect> = emptyList()) =
        DiscLayer(
            kind = LayerKind.STICKER, name = name.replace('-', ' ').replaceFirstChar { it.uppercase() }, sticker = "${LibraryStickerPrefix}emoji:$name",
            x = x, y = y, scale = scale, rotation = rotation, outline = outline, shadow = shadow, effects = effects,
        )

    /**
     * Daisies painted with the ink brush: each flower a ring of tapering white petals round a yellow
     * middle, seen a little from the side, on curving stems with a leaf or two. ([x], [y], size) each.
     */
    private fun daisies(flowers: List<Triple<Float, Float, Float>>, seed: Int): DiscLayer {
        val random = Random(seed)
        val strokes = ArrayList<BrushStroke>()
        fun line(vararg xy: Float) = xy.toList()
        fun curve(x0: Float, y0: Float, x1: Float, y1: Float, bend: Float, n: Int = 8): List<Float> {
            val out = ArrayList<Float>()
            val nx = -(y1 - y0)
            val ny = x1 - x0
            for (i in 0..n) {
                val t = i / n.toFloat()
                val b = kotlin.math.sin(t * Math.PI).toFloat() * bend
                out += x0 + (x1 - x0) * t + nx * b
                out += y0 + (y1 - y0) * t + ny * b
            }
            return out
        }
        // Stems and leaves first, so the flowers sit over them.
        for ((x, y, s) in flowers) {
            val ex = x + s * (0.3f + random.nextFloat() * 0.6f)
            val ey = y + s * 3.2f
            strokes += BrushStroke(BrushTip.INK, 0xFF55633F, s * 0.1f, 1f, curve(x, y + s * 0.2f, ex, ey, 0.12f), random.nextInt(999))
            val lx = x + (ex - x) * 0.5f
            val ly = y + s * 1.6f
            strokes += BrushStroke(BrushTip.INK, 0xFF6B7A4C, s * 0.2f, 0.95f, curve(lx, ly, lx - s * 0.8f, ly - s * 0.25f, -0.25f, 6), random.nextInt(999))
        }
        for ((x, y, s) in flowers) {
            val tilt = (random.nextFloat() - 0.5f) * 0.9f
            val squash = 0.62f + random.nextFloat() * 0.25f
            val petals = 13 + random.nextInt(4)
            val ct = kotlin.math.cos(tilt)
            val st = kotlin.math.sin(tilt)
            fun at(a: Float, d: Float): Pair<Float, Float> {
                val px = kotlin.math.cos(a) * d
                val py = kotlin.math.sin(a) * d * squash
                return (x + px * ct - py * st) to (y + px * st + py * ct)
            }
            // The back petals first, a little greyer, then the front ones over them.
            for (pass in 0..1) for (k in 0 until petals) {
                val a = (k * 2 * Math.PI / petals).toFloat() + random.nextFloat() * 0.18f
                val front = kotlin.math.sin(a) > 0
                if ((pass == 1) != front) continue
                val len = s * (0.82f + random.nextFloat() * 0.22f)
                val (x0, y0) = at(a, s * 0.16f)
                val (xm, ym) = at(a + (random.nextFloat() - 0.5f) * 0.12f, len * 0.55f)
                val (x1, y1) = at(a + (random.nextFloat() - 0.5f) * 0.18f, len)
                val shade = if (front) 0xFFF6F3EA else 0xFFD9D6CC
                strokes += BrushStroke(BrushTip.INK, shade, s * 0.24f, 1f, line(x0, y0, (x0 + xm) / 2, (y0 + ym) / 2, xm, ym, (xm + x1) / 2, (ym + y1) / 2, x1, y1), random.nextInt(999))
                // A thin stroke of shadow down one side of the petal.
                if (random.nextFloat() < 0.6f) strokes += BrushStroke(BrushTip.PEN, 0x55A9A397, s * 0.025f, 1f, line(x0, y0, xm, ym), 0)
            }
            // The middle: a dome of yellow, darker underneath, a dab of light on top.
            val (cx0, cy0) = at(Math.PI.toFloat(), s * 0.14f)
            val (cx1, cy1) = at(0f, s * 0.14f)
            strokes += BrushStroke(BrushTip.INK, 0xFFE6B229, s * 0.34f, 1f, line(cx0, cy0, x, y + s * 0.02f, cx1, cy1), random.nextInt(999))
            strokes += BrushStroke(BrushTip.INK, 0xFFC0661C, s * 0.12f, 0.85f, curve(cx0 + s * 0.04f, cy0 + s * 0.08f, cx1, cy1 + s * 0.06f, 0.25f, 5), random.nextInt(999))
            strokes += BrushStroke(BrushTip.PEN, 0xCCFFF2B0, s * 0.06f, 1f, line(x - s * 0.06f, y - s * 0.06f, x + s * 0.02f, y - s * 0.08f), 0)
        }
        return DiscLayer(kind = LayerKind.PAINT, name = "Painted daisies", strokes = strokes)
    }

    private val Cream = 0xFFF1EADB

    val all: List<Preset> = listOf(
        Preset("blank-cd", "Blank CD") { DiscDesign(name = "My CD", format = DiscFormat.CD) },
        Preset("blank-vinyl", "Blank vinyl") {
            DiscDesign(
                name = "My record", format = DiscFormat.VINYL,
                layers = listOf(text("{name}", 0xFFFFFFFF, TextPath.ARC_TOP, scale = 0.28f, radius = 0.25f, area = LayerArea.MIDDLE, name = "Title")),
            )
        },
        Preset("holographic", "Holographic") {
            DiscDesign(
                name = "Holographic", format = DiscFormat.CD, sheen = 0.5f,
                layers = listOf(fill(0xFFC4C7D2, kind = FillKind.HOLO, name = "Holographic").copy(density = 0.35f, seed = 4, amount = 0.6f)),
            )
        },
        Preset("cover-collage", "Cover collage") {
            DiscDesign(
                name = "Collage", format = DiscFormat.CD, sheen = 0.4f,
                layers = listOf(fill(0xFFF1EFEA, name = "White")) +
                    DiscCollage.layers(List(9) { DiscSamples.id(it) }, DiscCollage.Layout.RING, ImageFrame.PRINT, seed = 3) +
                    listOf(
                        shape(ShapeKind.PILL, 0xFFF2C230, -0.6f, 0.62f, 0.035f, aspect = 2.2f, rotation = 24f),
                        shape(ShapeKind.CIRCLE, 0xFF161618, -0.77f, 0.42f, 0.03f),
                    ),
            )
        },
        Preset("big-number", "Big number") {
            DiscDesign(
                name = "Big number", format = DiscFormat.VINYL, labelColor = 0x00000000, grooves = 0.85f, sheen = 0.9f, lightAngle = -30f,
                layers = listOf(
                    text("{count}", Cream, scale = 2.9f, x = -0.02f, y = 0.16f, spacing = -0.06f, font = DiscFont.SF_BOLD, name = "Number"),
                    text("{NAME}\n{count} SONGS\nMADE IN DISC STUDIO\nSIDE A  ·  33⅓ RPM", Cream, scale = 0.11f, x = 0.3f, y = -0.6f, font = DiscFont.SF_BOLD, align = TextAlignment.LEFT, name = "Credits"),
                    shape(ShapeKind.BARCODE, Cream, -0.42f, -0.62f, 0.075f, aspect = 2.6f, seed = 7),
                    text("SIDE A", Cream, TextPath.ARC_BOTTOM, scale = 0.07f, radius = 0.86f, spacing = 0.5f, font = DiscFont.SF_BOLD, name = "Side"),
                ),
            )
        },
        Preset("big-label", "Big label") {
            val ink = 0xFF221E1A
            DiscDesign(
                name = "Big label", format = DiscFormat.VINYL, labelColor = 0xFFEFE8D8, label = 0.93f, grooves = 0.8f, sheen = 0.8f, lightAngle = -30f,
                layers = listOf(
                    library("black-cat", 0.3f, 0.3f, 0.3f, rotation = -10f, effects = listOf(fx(EffectKind.RECOLOR, 0.95f, 0xFF3A2E24))),
                    text("A", ink, scale = 0.36f, y = -0.7f, font = DiscFont.SF_BOLD, area = LayerArea.MIDDLE, name = "Side"),
                    text("{name}", ink, scale = 0.3f, y = -0.47f, font = DiscFont.SERIF, area = LayerArea.MIDDLE, name = "Title"),
                    text("{tracks}", ink, scale = 0.085f, x = -0.4f, y = -0.08f, font = DiscFont.JAKARTA, align = TextAlignment.LEFT, area = LayerArea.MIDDLE, name = "Tracklist"),
                    text("STEREO\n33⅓ R.P.M.\n℗ {year}", ink, scale = 0.075f, x = 0.42f, y = -0.2f, font = DiscFont.JAKARTA, align = TextAlignment.LEFT, area = LayerArea.MIDDLE, name = "Details"),
                    text("MADE IN DISC STUDIO  ·  ALL SONGS CHOSEN BY YOU  ·  PLAY IT LOUD, PLAY IT OFTEN  ·  ", ink, TextPath.CIRCLE, scale = 0.075f, radius = 0.86f, spacing = 0.12f, font = DiscFont.SF, area = LayerArea.MIDDLE, name = "Round the edge"),
                    text("convx", 0xFFD4363F, scale = 0.42f, x = -0.32f, y = 0.5f, rotation = -8f, font = DiscFont.SCRIPT, area = LayerArea.MIDDLE, name = "Label name"),
                    text("DISC STUDIO", 0xFFD4363F, scale = 0.06f, x = -0.3f, y = 0.64f, spacing = 0.3f, font = DiscFont.SF_BOLD, area = LayerArea.MIDDLE, name = "Label line"),
                ),
            )
        },
        Preset("paint-swirl", "Paint swirl") {
            val gold = 0xFFE7C35A
            DiscDesign(
                name = "Paint swirl", format = DiscFormat.VINYL, labelColor = 0xFF8EA9D6, label = 0.31f, grooves = 0.6f, sheen = 1f, lightAngle = -30f,
                layers = listOf(
                    pattern(PatternKind.SWIRL, 0xFF2B1A0C, 0xFFE0A33A, 0xFFF4E3B0, 0xFF5E7A3A, 0xFF8C2F1C, density = 0.55f, amount = 0.45f, seed = 5, area = LayerArea.RING),
                    shape(ShapeKind.RING, gold, 0f, 0f, 0.29f, line = 0.06f, area = LayerArea.MIDDLE),
                    text("{NAME}", gold, TextPath.ARC_TOP, scale = 0.11f, radius = 0.215f, spacing = 0.12f, font = DiscFont.SF_BOLD, area = LayerArea.MIDDLE, name = "Title"),
                    text("SIDE A  ·  STEREO", gold, TextPath.ARC_BOTTOM, scale = 0.05f, radius = 0.235f, spacing = 0.2f, font = DiscFont.SF_BOLD, area = LayerArea.MIDDLE, name = "Side"),
                    text("{count} songs", 0xFF1D2B4A, scale = 0.05f, y = 0.09f, font = DiscFont.SF_BOLD, area = LayerArea.MIDDLE, name = "Count"),
                ),
            )
        },
        Preset("painted-daisies", "Painted daisies") {
            val ink = 0xFF2A1408
            DiscDesign(
                name = "Daisies", format = DiscFormat.VINYL, labelColor = 0xFFF07A1F, label = 0.34f, grooves = 0.85f, sheen = 1f, lightAngle = -35f,
                layers = listOf(
                    daisies(listOf(Triple(0.38f, -0.68f, 0.17f), Triple(0.7f, -0.42f, 0.16f), Triple(0.5f, -0.12f, 0.15f), Triple(0.74f, 0.2f, 0.17f), Triple(0.42f, 0.56f, 0.16f)), seed = 11),
                    pattern(PatternKind.RAYS, 0xFFC4520C, density = 0.55f, amount = 0.25f, opacity = 0.5f, area = LayerArea.MIDDLE).copy(y = -0.03f),
                    text("{NAME}", ink, TextPath.ARC_TOP, scale = 0.15f, radius = 0.2f, spacing = 0.04f, font = DiscFont.CHILLAX_BOLD, area = LayerArea.MIDDLE, name = "Title"),
                    shape(ShapeKind.LINE, ink, 0f, 0.0f, 0.03f, aspect = 11.5f, line = 0.06f, area = LayerArea.MIDDLE),
                    text("{count} SONGS\nSIDE A  ·  33⅓", ink, scale = 0.045f, y = 0.13f, font = DiscFont.SF_BOLD, area = LayerArea.MIDDLE, name = "Details"),
                ),
            )
        },
        Preset("mountain-grooves", "Mountains") {
            val dark = 0xFF1E2023
            val rust = 0xFFB4513A
            DiscDesign(
                name = "Mountains", format = DiscFormat.VINYL, base = 0xFFEDE6D6, labelColor = rust, label = 0.3f, grooves = 0.9f, sheen = 0.7f, lightAngle = -40f,
                layers = listOf(
                    shape(ShapeKind.MOUNTAINS, 0xFF5C5F65, 0.22f, 0.22f, 0.4f, aspect = 2.1f, seed = 7).copy(colors = listOf(0xFF5C5F65, 0xFFEDE6D6)),
                    shape(ShapeKind.MOUNTAINS, dark, -0.12f, 0.44f, 0.44f, aspect = 2.3f, seed = 3).copy(colors = listOf(dark, 0xFFEDE6D6)),
                    shape(ShapeKind.BOX, dark, 0f, 0.93f, 0.1f, aspect = 10f),
                ) + listOf(
                    Triple(-0.62f, 0.66f, 0.1f), Triple(-0.48f, 0.74f, 0.13f), Triple(-0.3f, 0.7f, 0.09f), Triple(-0.12f, 0.78f, 0.12f),
                    Triple(0.08f, 0.72f, 0.1f), Triple(0.26f, 0.8f, 0.13f), Triple(0.44f, 0.7f, 0.11f), Triple(0.6f, 0.62f, 0.12f),
                ).mapIndexed { i, (x, y, size) ->
                    shape(ShapeKind.PINE, if (i % 3 == 1) 0xFF6A6D72 else 0xFFEDE6D6, x, y, size, aspect = 0.55f).copy(colors = listOf(if (i % 3 == 1) 0xFF6A6D72 else 0xFFEDE6D6, dark))
                } + listOf(
                    shape(ShapeKind.CLOUD, dark, -0.48f, -0.52f, 0.13f, aspect = 1.9f, outline = true, line = 0.18f, seed = 2),
                    shape(ShapeKind.CLOUD, dark, -0.7f, -0.25f, 0.09f, aspect = 2.2f, outline = true, line = 0.15f, seed = 5),
                    shape(ShapeKind.BIRD, rust, -0.15f, -0.62f, 0.018f, aspect = 1.8f, line = 0.06f),
                    shape(ShapeKind.BIRD, rust, 0.05f, -0.7f, 0.014f, aspect = 1.8f, line = 0.05f),
                    shape(ShapeKind.BIRD, rust, 0.55f, -0.55f, 0.02f, aspect = 1.8f, line = 0.06f),
                    shape(ShapeKind.BIRD, rust, -0.3f, -0.15f, 0.014f, aspect = 1.8f, line = 0.05f),
                    pattern(PatternKind.GRAIN, 0xFFFFFFFF, amount = 0.3f, blend = LayerBlend.OVERLAY, seed = 4),
                ),
            )
        },
        Preset("patch-collage", "Patch collage") {
            val navy = 0xFF2E4A8C
            DiscDesign(
                name = "Patches", format = DiscFormat.VINYL, labelColor = 0xFF3E6ED6, label = 0.3f, grooves = 0.8f, sheen = 1f, lightAngle = -30f,
                layers = listOf(
                    shape(ShapeKind.STAR, 0xFFFFFFFF, 0.42f, 0.42f, 0.4f, rotation = -14f, shadow = true).copy(name = "Patch"),
                    pattern(PatternKind.DENIM, 0xFF1F3560, 0xFFD6DEEB, density = 0.5f, amount = 0.5f, seed = 2).copy(clip = true),
                    pattern(PatternKind.STARS, 0xFFDDE3EE, density = 0.2f, amount = 0.45f, seed = 6, opacity = 0.8f).copy(clip = true),
                    shape(ShapeKind.STAR, 0xCCE9DFC6, 0.42f, 0.42f, 0.355f, rotation = -14f, outline = true, line = 0.025f).copy(name = "Stitches"),
                    library("kiss-mark", -0.5f, -0.42f, 0.24f, rotation = -14f, effects = listOf(fx(EffectKind.RECOLOR, 1f, navy), fx(EffectKind.OUTLINE, 0.25f))),
                    library("ribbon", -0.02f, -0.72f, 0.15f, rotation = 8f, effects = listOf(fx(EffectKind.RECOLOR, 1f, navy), fx(EffectKind.OUTLINE, 0.25f))),
                    shape(ShapeKind.STAR, 0xFFC3CBDD, 0.52f, -0.42f, 0.24f, rotation = 12f, shadow = true),
                    shape(ShapeKind.SPIRAL, navy, 0.52f, -0.4f, 0.23f, line = 0.3f, echoes = 0.7f).copy(clip = true),
                    library("pool-8-ball", -0.74f, 0.1f, 0.13f, effects = listOf(fx(EffectKind.RECOLOR, 1f, navy), fx(EffectKind.OUTLINE, 0.25f))),
                    shape(ShapeKind.CASSETTE, 0xFF1C2A5C, -0.4f, 0.55f, 0.2f, aspect = 1.55f, rotation = -10f, shadow = true).copy(colors = listOf(0xFF1C2A5C, 0xFFF4F1EA)),
                    shape(ShapeKind.SPARKLE, 0xFFDDE3EE, 0.06f, 0.78f, 0.07f, rotation = -8f),
                    shape(ShapeKind.STAR, navy, 0.84f, -0.06f, 0.07f, rotation = 10f),
                ),
            )
        },
        Preset("blue-watercolour", "Blue watercolour") {
            val ink = 0xFF2A4C8F
            DiscDesign(
                name = "Blue", format = DiscFormat.VINYL, base = 0xFF1E3F7A, labelColor = 0xFFE7DFCB, label = 0.33f, grooves = 0.85f, sheen = 0.8f, lightAngle = -30f,
                layers = listOf(
                    pattern(PatternKind.WATERCOLOR, 0xFF173A78, 0xFF8AA6D6, density = 0.55f, amount = 0.45f, seed = 8, area = LayerArea.RING),
                    pattern(PatternKind.WATERCOLOR, 0xFF5E7DB8, 0xFFE7DFCB, density = 0.2f, amount = 0.7f, seed = 3, opacity = 0.35f, blend = LayerBlend.MULTIPLY, area = LayerArea.MIDDLE),
                    text("{NAME}", ink, scale = 0.075f, y = -0.17f, spacing = 0.1f, font = DiscFont.SF_BOLD, area = LayerArea.MIDDLE, name = "Title"),
                    shape(ShapeKind.CROSSHAIR, ink, 0f, 0.0f, 0.13f, aspect = 1.6f, line = 0.05f, area = LayerArea.MIDDLE),
                    text("SIDE A  ·  {count} SONGS", ink, scale = 0.04f, y = 0.17f, spacing = 0.15f, font = DiscFont.MONO, area = LayerArea.MIDDLE, name = "Details"),
                    shape(ShapeKind.TONEARM, 0xFFE3E7EF, 0.44f, -0.2f, 0.42f, aspect = 1.45f, rotation = -14f, shadow = true).copy(colors = listOf(0xFFE3E7EF, 0xFF1A2A4E)),
                    pattern(PatternKind.GRAIN, 0xFFFFFFFF, amount = 0.25f, blend = LayerBlend.OVERLAY, seed = 3),
                ),
            )
        },
        Preset("big-letter", "Big letter") {
            val ink = 0xFF2A1B10
            DiscDesign(
                name = "Big letter", format = DiscFormat.CD, sheen = 0.3f,
                layers = listOf(
                    pattern(PatternKind.CANVAS, 0xFF8C7656, density = 0.75f, amount = 0.45f, seed = 4),
                    text("{initial}", ink, scale = 1.8f, y = -0.6f, font = DiscFont.BLACKLETTER, effects = listOf(fx(EffectKind.INK, 0.35f)), name = "Letter"),
                    shape(ShapeKind.DIAMOND, ink, -0.66f, 0f, 0.045f),
                    shape(ShapeKind.DIAMOND, ink, 0.66f, 0f, 0.045f),
                    text("{name}", ink, scale = 0.34f, y = 0.55f, font = DiscFont.SCRIPT, name = "Name"),
                    shape(ShapeKind.LINE, ink, 0f, 0.68f, 0.02f, aspect = 9f, line = 0.06f),
                    text("{count} songs", ink, scale = 0.09f, y = 0.78f, spacing = 0.2f, font = DiscFont.SERIF, name = "Count"),
                ),
            )
        },
        Preset("liquid-chrome", "Liquid chrome") {
            DiscDesign(
                name = "Chrome", format = DiscFormat.CD, sheen = 0.4f,
                layers = listOf(
                    fill(0xFFCFE0FF, 0xFF1C1D22, kind = FillKind.CHROME, name = "Chrome").copy(density = 0.6f, seed = 6),
                    shape(ShapeKind.PILL, 0xFFF4F6FA, 0f, 0.66f, 0.085f, aspect = 4.2f, shadow = true).copy(name = "Tag"),
                    text("{name}", 0xFF111216, scale = 0.32f, y = 0.66f, font = DiscFont.SF_BOLD, name = "Title"),
                ),
            )
        },
        Preset("aura", "Aura") {
            DiscDesign(
                name = "Aura", format = DiscFormat.CD, sheen = 0.4f,
                layers = listOf(
                    fill(0xFFFF8FB8, 0xFFFFD08A, 0xFF8BB8FF, 0xFFC4A6FF, 0xFFFFF4E6, kind = FillKind.MESH, name = "Aura").copy(density = 0.45f, seed = 2),
                    pattern(PatternKind.GRAIN, 0xFFFFFFFF, amount = 0.35f, blend = LayerBlend.OVERLAY, seed = 3),
                    text("{NAME}", 0xFF2A2433, scale = 0.09f, y = 0.62f, spacing = 0.3f, font = DiscFont.MONO, name = "Title"),
                    text("SIDE A", 0xFF2A2433, TextPath.ARC_TOP, scale = 0.06f, radius = 0.86f, spacing = 0.5f, font = DiscFont.MONO, name = "Side"),
                ),
            )
        },
        Preset("y2k-foil", "Y2K foil") {
            val foil = listOf(fx(EffectKind.FOIL, 1f))
            DiscDesign(
                name = "Y2K", format = DiscFormat.CD, sheen = 0.4f,
                layers = listOf(
                    fill(0xFF0D0D10, name = "Black"),
                    pattern(PatternKind.HUD, 0xFFFFFFFF, density = 0.35f, amount = 0.15f, seed = 2, opacity = 0.45f),
                    shape(ShapeKind.SPARKLE, 0xFFFFFFFF, -0.5f, -0.45f, 0.22f, effects = foil),
                    shape(ShapeKind.SPARKLE, 0xFFFFFFFF, 0.56f, -0.36f, 0.12f, effects = foil),
                    shape(ShapeKind.SPARKLE, 0xFFFFFFFF, 0.44f, 0.5f, 0.16f, effects = foil),
                    shape(ShapeKind.STAR, 0xFFFFFFFF, -0.62f, 0.32f, 0.09f, rotation = -12f, effects = foil),
                    shape(ShapeKind.SPARKLE, 0xFFFFFFFF, 0.1f, -0.74f, 0.07f, effects = foil),
                    text("{name}", 0xFFFFFFFF, scale = 0.44f, y = 0.66f, font = DiscFont.SF_BOLD, effects = listOf(fx(EffectKind.CHROME, 1f)), name = "Title"),
                ),
            )
        },
        Preset("pearl", "Pearl") {
            DiscDesign(
                name = "Pearl", format = DiscFormat.CD, sheen = 0.4f,
                layers = listOf(
                    fill(0xFFF7E1EC, 0xFFE4DDF7, 0xFFDCEBF7, 0xFFF6F0E2, kind = FillKind.PEARL, name = "Pearl").copy(density = 0.35f, seed = 7),
                    shape(ShapeKind.SPARKLE, 0xFFFFFFFF, 0.55f, -0.5f, 0.12f, shadow = true),
                    shape(ShapeKind.SPARKLE, 0xFFFFFFFF, 0.7f, -0.28f, 0.06f),
                    text("{NAME}  ✦  {NAME}  ✦  ", 0xFF6D6385, TextPath.CIRCLE, scale = 0.075f, radius = 0.86f, spacing = 0.25f, font = DiscFont.SF_BOLD, name = "Round the edge"),
                ),
            )
        },
        Preset("prism-foil", "Prism foil") {
            DiscDesign(
                name = "Prism", format = DiscFormat.CD, sheen = 0.4f,
                layers = listOf(
                    pattern(PatternKind.PRISM, 0xFFFFFFFF, density = 0.7f, amount = 0.45f, seed = 5),
                    text("{name}", 0xFF111114, scale = 0.42f, y = 0.6f, font = DiscFont.SF_BOLD, effects = listOf(fx(EffectKind.OUTLINE, 0.25f, 0xFFFFFFFF)), name = "Title"),
                ),
            )
        },
        Preset("galaxy", "Galaxy") {
            DiscDesign(
                name = "Galaxy", format = DiscFormat.CD, sheen = 0.4f,
                layers = listOf(
                    pattern(PatternKind.NEBULA, 0xFF07061A, 0xFF4B1E7A, 0xFF1E5FAE, 0xFFFF9AD5, amount = 0.45f, seed = 2),
                    pattern(PatternKind.SPARKLES, 0xFFFFFFFF, density = 0.12f, amount = 0.25f, seed = 9),
                    text("{NAME}", 0xFFFFFFFF, TextPath.ARC_BOTTOM, scale = 0.12f, radius = 0.8f, spacing = 0.3f, font = DiscFont.SF_BOLD, name = "Title"),
                ),
            )
        },
        Preset("glitter", "Glitter") {
            DiscDesign(
                name = "Glitter", format = DiscFormat.CD, sheen = 0.4f,
                layers = listOf(
                    pattern(PatternKind.GLITTER, 0xFFFFB6D9, 0xFFE6E6F0, 0xFFC9B6FF, density = 0.6f, amount = 0.4f, seed = 3),
                    text("{name}", 0xFFFFFFFF, scale = 0.42f, y = 0.6f, rotation = -6f, font = DiscFont.MARKER, effects = listOf(fx(EffectKind.OUTLINE, 0.3f, 0xFFFF5FA2)), name = "Title"),
                ),
            )
        },
        Preset("holo-vinyl", "Holo vinyl") {
            DiscDesign(
                name = "Holo vinyl", format = DiscFormat.VINYL, base = 0xFF1B1B20, labelColor = 0xFF0F0F12, label = 0.3f, grooves = 0.7f, sheen = 0.9f, lightAngle = -30f,
                layers = listOf(
                    fill(0xFF2A2A30, kind = FillKind.HOLO, area = LayerArea.RING, name = "Holographic").copy(density = 0.55f, seed = 9, amount = 0.5f),
                    text("{NAME}", 0xFFFFFFFF, TextPath.ARC_TOP, scale = 0.11f, radius = 0.21f, spacing = 0.15f, font = DiscFont.SF_BOLD, area = LayerArea.MIDDLE, name = "Title"),
                    text("{count} SONGS", 0x99FFFFFF, scale = 0.045f, y = 0.12f, spacing = 0.2f, font = DiscFont.SF_BOLD, area = LayerArea.MIDDLE, name = "Count"),
                ),
            )
        },
        Preset("smoke-vinyl", "Smoke vinyl") {
            DiscDesign(
                name = "Smoke", format = DiscFormat.VINYL, base = 0xCC2E2E36, labelColor = 0xFFF4F1EA, label = 0.3f, grooves = 0.6f, sheen = 1f, lightAngle = -30f,
                layers = listOf(
                    pattern(PatternKind.SMOKE, 0x00000000, 0xDDECECF2, amount = 0.45f, seed = 4, area = LayerArea.RING),
                    text("{name}", 0xFF15151A, scale = 0.12f, y = -0.08f, font = DiscFont.CHILLAX_BOLD, area = LayerArea.MIDDLE, name = "Title"),
                    text("SIDE A  ·  33⅓", 0xFF15151A, scale = 0.04f, y = 0.1f, spacing = 0.2f, font = DiscFont.SF_BOLD, area = LayerArea.MIDDLE, name = "Side"),
                ),
            )
        },
        Preset("zine", "Zine collage") {
            DiscDesign(
                name = "Zine", format = DiscFormat.CD, sheen = 0.35f, clearHub = false,
                layers = listOf(
                    fill(0xFFF1EBDD, name = "Paper"),
                    shape(ShapeKind.BOX, 0xFF808080, -0.24f, -0.3f, 0.4f, aspect = 1.3f, rotation = -3f, effects = listOf(fx(EffectKind.HALFTONE, 0.35f, 0xFF3E7B39))).copy(name = "Green dots"),
                    shape(ShapeKind.SCRAP, 0xFFFBF9F4, 0.36f, 0.3f, 0.27f, aspect = 1.25f, rotation = 6f, shadow = true, seed = 4),
                    text("{name}", Ink, scale = 0.5f, x = -0.12f, y = 0.5f, rotation = -4f, font = DiscFont.BLACKLETTER, effects = listOf(fx(EffectKind.SMEAR, 0.45f)), name = "Title"),
                    text("SIDE A  /  33⅓\nVOL. 01\nPRINTED ON PAPER", Ink, scale = 0.06f, x = 0.36f, y = 0.3f, rotation = 6f, font = DiscFont.MONO, align = TextAlignment.LEFT, name = "Caption"),
                    shape(ShapeKind.BOX, Ink, 0.4f, -0.46f, 0.12f, aspect = 1.7f, outline = true, line = 0.05f, echoes = 0.25f),
                    shape(ShapeKind.BADGE, Ink, -0.66f, 0.12f, 0.09f, rotation = 20f),
                    shape(ShapeKind.BARCODE, Ink, 0.3f, 0.74f, 0.06f, aspect = 2f, rotation = -8f, seed = 8),
                    shape(ShapeKind.TAPE, 0xCCF4D58D, 0.5f, 0.08f, 0.05f, aspect = 3f, rotation = -24f),
                    pattern(PatternKind.PAPER, 0xFFFFFFFF, density = 0.5f, amount = 0.7f, blend = LayerBlend.MULTIPLY),
                ),
            )
        },
        Preset("ink-brush", "Ink brush") {
            DiscDesign(
                name = "Ink", format = DiscFormat.CD, sheen = 0.3f, clearHub = false,
                layers = listOf(
                    pattern(PatternKind.PAPER, 0xFFEFE7D6, density = 0.6f, amount = 0.75f),
                    text("音乐", 0xFF141414, scale = 0.95f, x = -0.06f, y = 0.5f, rotation = -6f, font = DiscFont.INK_BRUSH, effects = listOf(fx(EffectKind.INK, 0.6f)), name = "Brush"),
                    shape(ShapeKind.BOX, 0xFFC8261E, 0.5f, -0.42f, 0.1f, effects = listOf(fx(EffectKind.INK, 0.3f))).copy(name = "Seal"),
                    text("夜", 0xFFF4EDE0, scale = 0.42f, x = 0.5f, y = -0.42f, font = DiscFont.INK_BRUSH, name = "Seal mark"),
                    text("夜间音乐", 0xFF3A3A3A, TextPath.VERTICAL, scale = 0.15f, x = -0.6f, y = -0.2f, font = DiscFont.INK_BRUSH, name = "Vertical"),
                    text("{name}", 0xFF3A3A3A, TextPath.ARC_TOP, scale = 0.12f, radius = 0.84f, spacing = 0.2f, font = DiscFont.MONO, name = "Name"),
                    pattern(PatternKind.GRAIN, 0xFFFFFFFF, amount = 0.25f, blend = LayerBlend.OVERLAY, seed = 3),
                ),
            )
        },
        Preset("night-drive", "Night drive") {
            DiscDesign(
                name = "Night drive", format = DiscFormat.CD, sheen = 0.5f,
                layers = listOf(
                    fill(0xFF0D1426, 0xFF1C2B4A, kind = FillKind.LINEAR, rotation = 90f, name = "Night"),
                    pattern(PatternKind.STREAKS, 0xFFFFC27A, 0xFFFFFFFF, 0xFF7FC8FF, density = 0.4f, amount = 0.45f, seed = 12, blend = LayerBlend.SCREEN, effects = listOf(fx(EffectKind.BLOOM, 0.5f))),
                    pattern(PatternKind.HUD, 0xFFFFFFFF, density = 0.4f, amount = 0.15f, seed = 2, opacity = 0.7f),
                    text("{name}", 0xFFFFFFFF, scale = 0.3f, y = 0.62f, spacing = 0.12f, font = DiscFont.MONO, effects = listOf(fx(EffectKind.GLITCH, 0.3f)), name = "Title"),
                    pattern(PatternKind.GRAIN, 0xFFFFFFFF, amount = 0.4f, blend = LayerBlend.OVERLAY, seed = 6),
                ),
            )
        },
        Preset("manga", "Manga print") {
            DiscDesign(
                name = "Manga", format = DiscFormat.CD, sheen = 0.3f, clearHub = false,
                layers = listOf(
                    fill(0xFFF7F5F0, name = "Paper"),
                    pattern(PatternKind.SCREEN_TONE, 0xFF1B3B8F, density = 0.78f, amount = 0.3f, opacity = 0.6f, area = LayerArea.RING),
                    shape(ShapeKind.LINE, 0xFFE0262E, 0f, 0f, 0.05f, aspect = 19f, rotation = 62f, line = 0.08f),
                    text("夜间音乐", Ink, TextPath.VERTICAL, scale = 0.2f, x = 0.6f, y = -0.05f, font = DiscFont.INK_BRUSH, name = "Vertical"),
                    text("{name}", 0xFFE0262E, scale = 0.32f, y = -0.56f, font = DiscFont.IMPACT, name = "Title"),
                    text("1. SIDE A\n2. SIDE B\n3. BONUS", Ink, scale = 0.065f, x = -0.42f, y = 0.48f, font = DiscFont.MONO, align = TextAlignment.LEFT, name = "Caption"),
                    shape(ShapeKind.BARCODE, Ink, -0.52f, -0.08f, 0.06f, aspect = 1.9f, rotation = 90f, seed = 5),
                    shape(ShapeKind.CORNERS, Ink, 0.12f, 0.42f, 0.16f, aspect = 1.4f, line = 0.06f),
                ),
            )
        },
        Preset("moss-vinyl", "Moss green vinyl") {
            DiscDesign(
                name = "Moss", format = DiscFormat.VINYL, base = 0xFF4E8F2A, labelColor = 0xFFD9E3A8, grooves = 0.75f, sheen = 1f, lightAngle = -40f,
                layers = listOf(
                    text("{name}", 0xFF1D2A12, scale = 0.42f, y = -0.1f, font = DiscFont.CHILLAX_BOLD, area = LayerArea.MIDDLE, name = "Title"),
                    text("{tracks}", 0xFF1D2A12, scale = 0.05f, y = 0.16f, font = DiscFont.MONO, align = TextAlignment.LEFT, area = LayerArea.MIDDLE, name = "Tracklist"),
                    text("SIDE A", 0xFF1D2A12, TextPath.ARC_TOP, scale = 0.06f, radius = 0.3f, spacing = 0.3f, font = DiscFont.SF_BOLD, area = LayerArea.MIDDLE, name = "Side"),
                ),
            )
        },
        Preset("cream-vinyl", "Cream vinyl") {
            DiscDesign(
                name = "Cream", format = DiscFormat.VINYL, base = 0xFFEDE6D6, labelColor = 0xFF151515, grooves = 0.3f, sheen = 1f, lightAngle = -40f,
                layers = listOf(
                    text("{name}", 0xFFF4F1EA, scale = 0.3f, y = -0.1f, rotation = -5f, font = DiscFont.MARKER, area = LayerArea.MIDDLE, effects = listOf(fx(EffectKind.INK, 0.35f)), name = "Title"),
                    text("{tracks}", 0xFFF4F1EA, scale = 0.045f, y = 0.16f, font = DiscFont.MONO, align = TextAlignment.LEFT, area = LayerArea.MIDDLE, name = "Tracklist"),
                ),
            )
        },
        Preset("marble-grey", "Grey marble vinyl") {
            DiscDesign(
                name = "Grey marble", format = DiscFormat.VINYL, base = 0xFF8F939C, labelColor = 0xFFE9DFC8, grooves = 0.45f, sheen = 1f, lightAngle = -40f,
                layers = listOf(
                    pattern(PatternKind.MARBLE, 0xFF8F939C, 0xFFDADDE3, 0xFF41444C, density = 0.45f, amount = 0.4f, seed = 6, area = LayerArea.RING),
                    pattern(PatternKind.RAYS, 0xFF2A2A2E, density = 0.7f, amount = 0.15f, opacity = 0.18f, area = LayerArea.MIDDLE),
                    text("{name}  ✦  {name}", 0xFF2A2A2E, TextPath.CIRCLE, scale = 0.08f, radius = 0.31f, spacing = 0.15f, font = DiscFont.SERIF, area = LayerArea.MIDDLE, name = "Around"),
                    text("{name}", 0xFF2A2A2E, scale = 0.16f, y = -0.09f, font = DiscFont.BLACKLETTER, area = LayerArea.MIDDLE, name = "Title"),
                ),
            )
        },
        Preset("black-classic", "Black classic") {
            DiscDesign(
                name = "Classic", format = DiscFormat.VINYL, labelColor = 0xFF111113, grooves = 0.8f, sheen = 0.9f, lightAngle = -40f,
                layers = listOf(
                    text("{name}", 0xFFEDEDED, TextPath.ARC_TOP, scale = 0.14f, radius = 0.27f, spacing = 0.06f, font = DiscFont.SF_BOLD, area = LayerArea.MIDDLE, name = "Title"),
                    text("{tracks}", 0xFFCFCFCF, scale = 0.045f, y = 0.14f, font = DiscFont.MONO, align = TextAlignment.LEFT, area = LayerArea.MIDDLE, name = "Tracklist"),
                    text("33⅓", 0xFFCFCFCF, scale = 0.07f, x = -0.2f, y = -0.03f, font = DiscFont.SF, area = LayerArea.MIDDLE, name = "Speed"),
                    text("A", 0xFFCFCFCF, scale = 0.07f, x = 0.2f, y = -0.03f, font = DiscFont.SF_BOLD, area = LayerArea.MIDDLE, name = "Side"),
                ),
            )
        },
    )

    fun of(key: String) = all.firstOrNull { it.key == key }

    /** The order presets are offered in: blanks, then the newest looks, then the rest as listed. */
    private val Leading = listOf(
        "blank-cd", "blank-vinyl", "holographic", "cover-collage", "big-number", "big-label", "paint-swirl", "painted-daisies",
        "mountain-grooves", "patch-collage", "blue-watercolour", "big-letter", "liquid-chrome", "aura", "y2k-foil",
    )

    val ordered: List<Preset> by lazy { all.sortedBy { p -> Leading.indexOf(p.key).let { if (it < 0) Leading.size else it } } }

    private val SampleTracks = listOf("Intro", "Night drive", "Glass hearts", "Slow fade", "Outro")

    /** Songs numbered for a label, six at most, long titles cut short. */
    fun tracklist(titles: List<String>): String =
        titles.ifEmpty { SampleTracks }.take(6).mapIndexed { i, t -> "${i + 1}. ${if (t.length > 26) t.take(25) + "…" else t}" }.joinToString("\n")

    /** How many songs a design started on its own says it has. */
    private const val SampleCount = 24

    /**
     * A fresh copy of [preset] with new ids and its placeholders filled in: "{name}" (and "{NAME}"
     * in capitals) with [playlistName] or the preset's own name, "{tracks}" with [tracks] numbered,
     * "{count}" with how many there are, "{initial}" with the name's first letter and "{year}". Its
     * sample pictures become [covers], in turn, when there are any.
     */
    fun start(preset: Preset, playlistName: String? = null, tracks: List<String> = emptyList(), covers: List<String> = emptyList()): DiscDesign {
        val design = preset.make()
        val name = playlistName?.takeIf { it.isNotBlank() } ?: design.name
        val now = System.currentTimeMillis()
        val count = if (tracks.isEmpty()) SampleCount.toString() else tracks.size.toString()
        val initial = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "C"
        fun fill(text: String) = text
            .replace("{name}", name).replace("{NAME}", name.uppercase())
            .replace("{tracks}", tracklist(tracks)).replace("{count}", count)
            .replace("{initial}", initial).replace("{year}", java.time.Year.now().toString())
        return design.copy(
            id = UUID.randomUUID().toString(),
            name = if (playlistName != null) name else design.name,
            layers = design.layers.map { layer ->
                // A name longer than the preset's own is set smaller, so it still fits where the preset put it.
                val named = "{name}" in layer.text || "{NAME}" in layer.text
                val shrinks = named && (layer.path == TextPath.STRAIGHT || layer.path == TextPath.ARC_TOP || layer.path == TextPath.ARC_BOTTOM)
                val ratio = (design.name.length.coerceAtLeast(6).toFloat() / name.length).coerceIn(0.35f, 1f)
                // Text on an arc has more room along the curve, so it gives up less.
                val fit = when {
                    !shrinks -> 1f
                    layer.path == TextPath.STRAIGHT -> ratio
                    else -> kotlin.math.sqrt(ratio)
                }
                // A number of songs longer than the preset's set smaller too.
                val digits = if ("{count}" in layer.text && layer.text.trim() == "{count}") (2f / count.length.coerceAtLeast(2)) else 1f
                val asset = layer.asset?.let { a ->
                    val n = a.removePrefix(SampleAssetPrefix).toIntOrNull()
                    if (a.startsWith(SampleAssetPrefix) && n != null && covers.isNotEmpty()) covers[n % covers.size] else a
                }
                layer.copy(id = newLayerId(), text = fill(layer.text), scale = layer.scale * fit * digits, asset = asset)
            },
            createdAt = now,
            updatedAt = now,
        )
    }

    /** Whether [preset] has sample pictures that a playlist's covers can stand in for. */
    fun usesCovers(preset: Preset): Boolean = preset.make().layers.any { it.asset?.startsWith(SampleAssetPrefix) == true }
}
