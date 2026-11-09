/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.data

import kotlinx.serialization.Serializable
import java.util.UUID

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
enum class FillKind(val label: String) { SOLID("Solid"), LINEAR("Linear"), RADIAL("Radial"), SWEEP("Sweep") }

@Serializable
enum class ImageLook(val label: String) {
    NONE("Original"),
    MONO("Mono"),
    DUOTONE("Duotone"),
    PIXEL("Pixel"),
    VINTAGE("Vintage"),
    GLOW("Glow"),
    INVERT("Invert"),
}

@Serializable
enum class ImageFrame(val label: String) { SQUARE("Square"), CIRCLE("Circle"), PHOTO("Photo print") }

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
    ;

    /** Drawn with lines whether or not the layer is outlined. */
    val lines: Boolean get() = this == LINE || this == CROSSHAIR || this == CORNERS || this == RULER

    /** Can be a filled shape or just its outline. */
    val outlinable: Boolean get() = this == BOX || this == CIRCLE || this == BADGE || this == BLOB

    /** Drawn from the layer's seed, so Shuffle gives a new one. */
    val shuffles: Boolean get() = this == BLOB || this == BARCODE || this == TAPE || this == SCRAP
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
    /** Sticker: a [DiscSticker] id, or [EmojiStickerPrefix] and an emoji. */
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
) {
    /** Whether the layer is placed somewhere (and can be picked and dragged) rather than covering the disc. */
    val placed: Boolean get() = kind == LayerKind.IMAGE || kind == LayerKind.STICKER || kind == LayerKind.SHAPE || (kind == LayerKind.TEXT && path != TextPath.REPEAT)

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
    val assets: Set<String> get() = layers.mapNotNullTo(LinkedHashSet()) { it.asset }

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

    val all: List<Preset> = listOf(
        Preset("blank-cd", "Blank CD") { DiscDesign(name = "My CD", format = DiscFormat.CD) },
        Preset("blank-vinyl", "Blank vinyl") {
            DiscDesign(
                name = "My record", format = DiscFormat.VINYL,
                layers = listOf(text("{name}", 0xFFFFFFFF, TextPath.ARC_TOP, scale = 0.28f, radius = 0.25f, area = LayerArea.MIDDLE, name = "Title")),
            )
        },
        Preset("afro-night", "Repeat type") {
            DiscDesign(
                name = "Afro Night", format = DiscFormat.CD,
                layers = listOf(
                    fill(0xFFF2C230, name = "Yellow"),
                    text("{name}", 0xFF3A2410, TextPath.REPEAT, scale = 0.62f, rotation = -8f, outline = true, name = "Repeated name"),
                    text("{name}", 0xFFFFFFFF, scale = 0.62f, y = 0.6f, rotation = -8f, shadow = true, name = "Name"),
                ),
            )
        },
        Preset("peach-stars", "Peach stars") {
            DiscDesign(
                name = "Peach stars", format = DiscFormat.CD,
                layers = listOf(
                    fill(0xFFFFC7A1, 0xFFFF8A65, 0xFFE0533D, kind = FillKind.RADIAL, name = "Peach glow"),
                    pattern(PatternKind.SPARKLES, 0xFFFFFFFF, density = 0.45f, amount = 0.6f, seed = 3),
                    pattern(PatternKind.STARS, 0xFFFFF4E8, density = 0.35f, amount = 0.35f, seed = 7, opacity = 0.9f),
                ),
            )
        },
        Preset("pixel-heat", "Pixel mosaic") {
            DiscDesign(
                name = "Pixel heat", format = DiscFormat.CD,
                layers = listOf(pattern(PatternKind.PIXELS, 0xFF2B0A0A, 0xFF8C1C13, 0xFFBC4B51, 0xFFF4A259, 0xFFFFF1C1, density = 0.35f, seed = 11)),
            )
        },
        Preset("prism", "Prism") {
            DiscDesign(
                name = "Prism", format = DiscFormat.CD, sheen = 1f,
                layers = listOf(
                    fill(0xFF8EC5FC, 0xFFE0C3FC, 0xFFFFC3A0, 0xFFA1FFCE, kind = FillKind.SWEEP, opacity = 0.85f, blend = LayerBlend.MULTIPLY, name = "Rainbow"),
                    pattern(PatternKind.RINGS, 0xFFFFFFFF, density = 0.7f, amount = 0.2f, opacity = 0.25f, blend = LayerBlend.SCREEN),
                ),
            )
        },
        Preset("sunburst", "Sunburst") {
            DiscDesign(
                name = "Sunburst", format = DiscFormat.CD,
                layers = listOf(
                    fill(0xFFFFC371),
                    pattern(PatternKind.RAYS, 0xFFFF5F6D, density = 0.45f),
                    sticker(DiscSticker.SMILE.id, 0xFFFFE14D, 0.52f, -0.42f, 0.3f, rotation = 12f),
                    sticker(DiscSticker.STAR.id, 0xFFFFFFFF, -0.58f, 0.4f, 0.22f, rotation = -10f),
                ),
            )
        },
        Preset("halftone-pop", "Halftone pop") {
            DiscDesign(
                name = "Pop", format = DiscFormat.CD,
                layers = listOf(
                    fill(0xFF1E90FF),
                    pattern(PatternKind.HALFTONE, 0xFFFF2E88, density = 0.6f, amount = 0.7f),
                    text("POP!", 0xFFFFE14D, scale = 0.9f, y = 0.55f, rotation = -10f, shadow = true, font = DiscFont.IMPACT, name = "Title"),
                ),
            )
        },
        Preset("love-letter", "Love letter") {
            DiscDesign(
                name = "Love letter", format = DiscFormat.CD,
                layers = listOf(
                    fill(0xFFFFD1DC, 0xFFFF8FAB, kind = FillKind.LINEAR, rotation = 60f),
                    pattern(PatternKind.HEARTS, 0xFFFF4D6D, 0xFFFFFFFF, density = 0.4f, amount = 0.45f, seed = 5),
                    text("{name}", 0xFF8A1538, TextPath.ARC_BOTTOM, scale = 0.3f, radius = 0.82f, font = DiscFont.SCRIPT, name = "Name"),
                ),
            )
        },
        Preset("y2k", "Chrome Y2K") {
            DiscDesign(
                name = "Y2K", format = DiscFormat.CD, sheen = 1f,
                layers = listOf(
                    fill(0xFFCFD9DF, 0xFFF5F7FA, 0xFFA1C4FD, 0xFFC2E9FB, 0xFFE2D1F9, kind = FillKind.SWEEP, name = "Chrome"),
                    pattern(PatternKind.SPARKLES, 0xFFFFFFFF, density = 0.3f, amount = 0.8f, seed = 2),
                    text("{name}", 0xFF26324D, TextPath.CIRCLE, scale = 0.22f, radius = 0.86f, spacing = 0.1f, font = DiscFont.SF_BOLD, name = "Around"),
                ),
            )
        },
        Preset("midnight-vinyl", "Midnight vinyl") {
            DiscDesign(
                name = "Midnight", format = DiscFormat.VINYL, labelColor = 0xFFFA2D48,
                layers = listOf(
                    text("{name}", 0xFFFFFFFF, TextPath.ARC_TOP, scale = 0.26f, radius = 0.25f, area = LayerArea.MIDDLE, name = "Title"),
                    text("SIDE A  ·  33⅓ RPM", 0xCCFFFFFF, TextPath.ARC_BOTTOM, scale = 0.12f, radius = 0.27f, spacing = 0.15f, font = DiscFont.SF, area = LayerArea.MIDDLE, name = "Side"),
                    sticker(DiscSticker.STAR.id, 0xFFFFFFFF, 0f, 0.11f, 0.045f, outline = false, shadow = false),
                ),
            )
        },
        Preset("splatter", "Splatter vinyl") {
            DiscDesign(
                name = "Splatter", format = DiscFormat.VINYL, base = 0xFFF2EBDD, labelColor = 0xFF151515, grooves = 0.55f,
                layers = listOf(
                    pattern(PatternKind.SPLATTER, 0xFFFA2D48, 0xFF151515, density = 0.55f, amount = 0.6f, seed = 9, area = LayerArea.RING),
                    text("{name}", 0xFFF2EBDD, scale = 0.22f, area = LayerArea.MIDDLE, y = -0.12f, name = "Title"),
                ),
            )
        },
        Preset("marble", "Marble vinyl") {
            DiscDesign(
                name = "Marble", format = DiscFormat.VINYL, base = 0xFF4B1D7A, labelColor = 0xFFF4EEFF, grooves = 0.5f,
                layers = listOf(
                    pattern(PatternKind.MARBLE, 0xFF4B1D7A, 0xFFE0B0FF, density = 0.5f, amount = 0.5f, seed = 4, area = LayerArea.RING),
                    text("{name}", 0xFF4B1D7A, TextPath.ARC_TOP, scale = 0.24f, radius = 0.24f, font = DiscFont.OUTFIT, area = LayerArea.MIDDLE, name = "Title"),
                ),
            )
        },
        Preset("checker-vinyl", "Checker picture disc") {
            DiscDesign(
                name = "Checker", format = DiscFormat.VINYL, labelColor = 0xFFFFE14D, grooves = 0.45f,
                layers = listOf(
                    pattern(PatternKind.CHECKER, 0xFF141414, 0xFFF1F1F1, density = 0.4f, area = LayerArea.RING),
                    text("{name}", 0xFF141414, scale = 0.2f, y = -0.12f, area = LayerArea.MIDDLE, name = "Title"),
                ),
            )
        },
        Preset("sticker-bomb", "Sticker bomb") {
            DiscDesign(
                name = "Sticker bomb", format = DiscFormat.CD,
                layers = listOf(
                    fill(0xFF101014),
                    pattern(PatternKind.CONFETTI, 0xFFFA2D48, 0xFF33D1FF, 0xFFFFE14D, 0xFFFFFFFF, density = 0.35f, amount = 0.4f, seed = 21),
                    sticker(DiscSticker.HEART.id, 0xFFFA2D48, -0.5f, -0.45f, 0.26f, rotation = -14f),
                    sticker(DiscSticker.BOLT.id, 0xFFFFE14D, 0.55f, -0.38f, 0.24f, rotation = 10f),
                    sticker(DiscSticker.CHERRY.id, 0xFFE0263A, 0.48f, 0.52f, 0.26f, rotation = -6f),
                    sticker(DiscSticker.FLOWER.id, 0xFF9B7BFF, -0.55f, 0.5f, 0.24f, rotation = 18f),
                    sticker(DiscSticker.CROWN.id, 0xFFFFC233, 0f, 0.74f, 0.2f),
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
        Preset("bloom-stars", "Bloom stars") {
            DiscDesign(
                name = "Bloom", format = DiscFormat.CD, sheen = 0.6f,
                layers = listOf(
                    fill(0xFF3A6DF0, 0xFF13296E, 0xFF070F2E, kind = FillKind.RADIAL, name = "Night blue"),
                    pattern(PatternKind.SPARKLES, 0xFFFFFFFF, density = 0.25f, amount = 0.35f, seed = 4, effects = listOf(fx(EffectKind.BLOOM, 0.6f))),
                    pattern(PatternKind.STARS, 0xFFFFF4C2, density = 0.18f, amount = 0.2f, seed = 9, effects = listOf(fx(EffectKind.BLOOM, 0.4f))),
                    text("{name}", 0xFFFFFFFF, scale = 0.42f, y = 0.56f, font = DiscFont.SF_BOLD, effects = listOf(fx(EffectKind.GLOW, 0.3f, 0xFF4F86FF)), name = "Title"),
                    pattern(PatternKind.GRAIN, 0xFFFFFFFF, amount = 0.25f, blend = LayerBlend.OVERLAY, seed = 5),
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

    /** The order presets are offered in: blanks, then collage and record looks, then the rest as listed. */
    private val Leading = listOf(
        "blank-cd", "blank-vinyl", "zine", "ink-brush", "bloom-stars", "night-drive", "manga",
        "moss-vinyl", "cream-vinyl", "marble-grey", "black-classic",
    )

    val ordered: List<Preset> by lazy { all.sortedBy { p -> Leading.indexOf(p.key).let { if (it < 0) Leading.size else it } } }

    private val SampleTracks = listOf("Intro", "Night drive", "Glass hearts", "Slow fade", "Outro")

    /** Songs numbered for a label, six at most, long titles cut short. */
    fun tracklist(titles: List<String>): String =
        titles.ifEmpty { SampleTracks }.take(6).mapIndexed { i, t -> "${i + 1}. ${if (t.length > 26) t.take(25) + "…" else t}" }.joinToString("\n")

    /**
     * A fresh copy of [preset] with new ids, its "{name}" filled in with [playlistName] (or the
     * preset's own name) and "{tracks}" with [tracks] numbered.
     */
    fun start(preset: Preset, playlistName: String? = null, tracks: List<String> = emptyList()): DiscDesign {
        val design = preset.make()
        val name = playlistName?.takeIf { it.isNotBlank() } ?: design.name
        val now = System.currentTimeMillis()
        return design.copy(
            id = UUID.randomUUID().toString(),
            name = if (playlistName != null) name else design.name,
            layers = design.layers.map { layer ->
                // A name longer than the preset's own is set smaller, so it still fits where the preset put it.
                val shrinks = "{name}" in layer.text && (layer.path == TextPath.STRAIGHT || layer.path == TextPath.ARC_TOP || layer.path == TextPath.ARC_BOTTOM)
                val ratio = (design.name.length.coerceAtLeast(6).toFloat() / name.length).coerceIn(0.35f, 1f)
                // Text on an arc has more room along the curve, so it gives up less.
                val fit = when {
                    !shrinks -> 1f
                    layer.path == TextPath.STRAIGHT -> ratio
                    else -> kotlin.math.sqrt(ratio)
                }
                layer.copy(id = newLayerId(), text = layer.text.replace("{name}", name).replace("{tracks}", tracklist(tracks)), scale = layer.scale * fit)
            },
            createdAt = now,
            updatedAt = now,
        )
    }
}
