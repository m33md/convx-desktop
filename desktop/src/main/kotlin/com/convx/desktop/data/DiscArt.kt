/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.data

import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ClipMode
import org.jetbrains.skia.Color
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ColorMatrix
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.Path
import org.jetbrains.skia.PathFillMode
import org.jetbrains.skia.PathOp
import org.jetbrains.skia.RRect
import org.jetbrains.skia.RSXform
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import org.jetbrains.skia.TextBlob
import org.jetbrains.skia.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws a [DiscDesign]: the disc on its own (the editor's canvas, a spinning preview) or as a square
 * playlist cover, a CD in its clear case or a record coming out of its sleeve. Only Skia, so it
 * draws off the UI thread and straight into Compose's canvas alike.
 */
object DiscArt {
    /** Where pictures and fonts come from; the app reads them from disk, a preview can pass [None]. */
    interface Resources {
        fun image(asset: String): Image?
        fun typeface(font: String): Typeface?
    }

    object None : Resources {
        override fun image(asset: String): Image? = null
        override fun typeface(font: String): Typeface? = null
    }

    /** A CD's hole, as on a real 120 mm disc, and a record's spindle hole. */
    private const val CdHole = 0.125f
    private const val CdClamp = 0.2f
    private const val CdHub = 0.36f
    private const val VinylHole = 0.026f

    fun renderCover(design: DiscDesign, res: Resources, size: Int = 900): Image {
        val surface = Surface.makeRasterN32Premul(size, size)
        drawCover(surface.canvas, design, res, size.toFloat())
        return surface.makeImageSnapshot()
    }

    /**
     * The print alone, [size] pixels across: the disc's base, its label and every layer with its
     * effects, without the light, the holes or the edge. Drawn once and turned while the disc spins,
     * so a spinning disc costs one picture a frame however much is on it.
     */
    fun renderPrint(design: DiscDesign, res: Resources, size: Int): Image {
        val surface = Surface.makeRasterN32Premul(size, size)
        val c = size / 2f
        surface.canvas.clear(Color.TRANSPARENT)
        surface.canvas.clipPath(Path().addCircle(c, c, c), ClipMode.INTERSECT, true)
        printBase(surface.canvas, design, c, c, c)
        for (layer in design.layers) if (layer.visible) drawLayer(surface.canvas, design, layer, res, c, c, c)
        return surface.makeImageSnapshot()
    }

    /** The disc alone on a clear background, [size] pixels across. */
    fun renderDisc(design: DiscDesign, res: Resources, size: Int, spin: Float = 0f): Image {
        val surface = Surface.makeRasterN32Premul(size, size)
        surface.canvas.clear(Color.TRANSPARENT)
        drawDisc(surface.canvas, design, res, size / 2f, size / 2f, size / 2f * 0.97f, spin)
        return surface.makeImageSnapshot()
    }

    fun jpeg(image: Image, quality: Int = 92): ByteArray =
        image.encodeToData(EncodedImageFormat.JPEG, quality)?.bytes ?: error("Couldn't encode the disc")

    fun png(image: Image): ByteArray =
        image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("Couldn't encode the disc")

    /**
     * The disc at ([cx], [cy]) with radius [r]. [spin] turns the print (the light on it stays where
     * it is, as on a turntable). The holes are cut through to whatever is under the disc. [print],
     * from [renderPrint], stands in for the base and layers.
     */
    fun drawDisc(canvas: Canvas, design: DiscDesign, res: Resources, cx: Float, cy: Float, r: Float, spin: Float = 0f, print: Image? = null) {
        val bounds = Rect.makeLTRB(cx - r * 1.1f, cy - r * 1.1f, cx + r * 1.1f, cy + r * 1.1f)
        // A soft shadow under the disc: a ring of gradient, much cheaper than blurring the disc.
        val sy = cy + r * 0.02f
        canvas.drawPath(Path().apply {
            fillMode = PathFillMode.EVEN_ODD
            addCircle(cx, sy, r * 1.09f)
            addCircle(cx, sy, r * 0.9f)
        }, Paint().apply {
            isAntiAlias = true
            shader = Shader.makeRadialGradient(cx, sy, r * 1.09f, intArrayOf(withAlpha(Color.BLACK, 0.55f), withAlpha(Color.BLACK, 0.4f), 0), floatArrayOf(0.88f, 0.94f, 1f))
        })
        canvas.saveLayer(bounds, null)
        canvas.save()
        canvas.clipPath(Path().addCircle(cx, cy, r), ClipMode.INTERSECT, true)
        face(canvas, design, res, cx, cy, r, spin, print)
        canvas.restore()
        when (design.format) {
            DiscFormat.CD -> cdMiddle(canvas, design, cx, cy, r)
            DiscFormat.VINYL -> vinylMiddle(canvas, design, cx, cy, r)
        }
        canvas.restore()
        // The edge catching the light.
        canvas.drawCircle(cx, cy, r, stroke(withAlpha(Color.WHITE, if (design.format == DiscFormat.CD) 0.45f else 0.14f), r * 0.006f))
        canvas.drawCircle(cx, cy, r * 0.992f, stroke(withAlpha(Color.BLACK, 0.25f), r * 0.008f))
    }

    /** The square cover: a CD in a clear jewel case, or a record half out of its sleeve. */
    fun drawCover(canvas: Canvas, design: DiscDesign, res: Resources, s: Float) {
        when (design.format) {
            DiscFormat.CD -> {
                val case = CoverArt.Case(s)
                case.frame(canvas) {
                    val cx = case.cx
                    val cy = case.cy
                    val r = case.r
                    canvas.drawCircle(cx, cy + s * 0.008f, r, Paint().apply {
                        isAntiAlias = true
                        color = withAlpha(Color.BLACK, 0.7f)
                        maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, s * 0.014f, true)
                    })
                    canvas.save()
                    canvas.clipPath(Path().addCircle(cx, cy, r), ClipMode.INTERSECT, true)
                    face(canvas, design, res, cx, cy, r, 0f)
                    canvas.restore()
                    if (design.clearHub) case.hub(canvas) else case.spindle(canvas)
                    canvas.drawCircle(cx, cy, r, stroke(withAlpha(Color.WHITE, 0.45f), s * 0.003f))
                    canvas.drawCircle(cx, cy, r * 0.988f, stroke(withAlpha(Color.BLACK, 0.25f), s * 0.004f))
                }
            }
            DiscFormat.VINYL -> sleeve(canvas, design, res, s)
        }
    }

    // ---- The disc ----------------------------------------------------------------------------

    /** Base, layers, then the light; the caller has clipped to the disc. */
    private fun face(canvas: Canvas, design: DiscDesign, res: Resources, cx: Float, cy: Float, r: Float, spin: Float, print: Image? = null) {
        canvas.save()
        if (spin != 0f) canvas.rotate(spin, cx, cy)
        if (print != null) {
            canvas.drawImageRect(print, Rect.makeWH(print.width.toFloat(), print.height.toFloat()), Rect.makeLTRB(cx - r, cy - r, cx + r, cy + r), SamplingMode.LINEAR, null, true)
        } else {
            printBase(canvas, design, cx, cy, r)
            for (layer in design.layers) if (layer.visible) drawLayer(canvas, design, layer, res, cx, cy, r)
        }
        canvas.restore()
        when (design.format) {
            DiscFormat.CD -> cdSheen(canvas, design, cx, cy, r)
            DiscFormat.VINYL -> {
                grooves(canvas, design, cx, cy, r)
                vinylLight(canvas, design, cx, cy, r)
            }
        }
    }

    private fun printBase(canvas: Canvas, design: DiscDesign, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx, cy, r, fill(argb(design.base)))
        if (design.format == DiscFormat.CD) brushedSilver(canvas, cx, cy, r)
        if (design.format == DiscFormat.VINYL && Color.getA(argb(design.labelColor)) > 0) {
            canvas.drawCircle(cx, cy, r * design.label, fill(argb(design.labelColor)))
        }
    }

    /** A bare CD's silver: faint bands of light running round it. */
    private fun brushedSilver(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx, cy, r, Paint().apply {
            isAntiAlias = true
            shader = Shader.makeSweepGradient(cx, cy, intArrayOf(
                withAlpha(Color.WHITE, 0.18f), withAlpha(Color.BLACK, 0.08f), withAlpha(Color.WHITE, 0.14f),
                withAlpha(Color.BLACK, 0.1f), withAlpha(Color.WHITE, 0.18f),
            ), floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f))
        })
    }

    /** A CD's rainbow and the light across it, as strong as the design's sheen. */
    private fun cdSheen(canvas: Canvas, design: DiscDesign, cx: Float, cy: Float, r: Float) {
        val k = design.sheen.coerceIn(0f, 1f) * 1.4f
        if (k <= 0f) return
        canvas.save()
        canvas.rotate(design.lightAngle, cx, cy)
        canvas.drawCircle(cx, cy, r, Paint().apply {
            isAntiAlias = true
            blendMode = BlendMode.SCREEN
            shader = Shader.makeSweepGradient(cx, cy, intArrayOf(
                0, withAlpha(0xFF66E0FF.toInt(), 0.12f * k), 0, withAlpha(0xFFFF66CC.toInt(), 0.11f * k), 0, withAlpha(0xFFFFE066.toInt(), 0.1f * k), 0,
            ), floatArrayOf(0f, 0.12f, 0.25f, 0.45f, 0.6f, 0.78f, 1f))
        })
        canvas.drawCircle(cx, cy, r, Paint().apply {
            isAntiAlias = true
            shader = Shader.makeLinearGradient(cx - r, cy - r, cx + r, cy + r,
                intArrayOf(withAlpha(Color.WHITE, 0.14f * k), withAlpha(Color.WHITE, 0f), withAlpha(Color.BLACK, 0.1f * k)), floatArrayOf(0f, 0.5f, 1f))
        })
        canvas.restore()
    }

    /** A bare CD's clear middle and its hole, cut through to what's under the disc. */
    private fun cdMiddle(canvas: Canvas, design: DiscDesign, cx: Float, cy: Float, r: Float) {
        val clear = if (design.clearHub) CdHub else CdClamp
        val cut = Paint().apply { isAntiAlias = true; blendMode = BlendMode.CLEAR }
        canvas.drawCircle(cx, cy, r * clear, cut)
        // Clear plastic: a frosted ring, the stacking ring, the hole's edge.
        canvas.drawCircle(cx, cy, r * clear, Paint().apply {
            isAntiAlias = true
            shader = Shader.makeRadialGradient(cx, cy, r * clear,
                intArrayOf(withAlpha(Color.WHITE, 0.1f), withAlpha(Color.WHITE, 0.22f), withAlpha(Color.WHITE, 0.08f)), floatArrayOf(0.4f, 0.86f, 1f))
        })
        canvas.drawCircle(cx, cy, r * clear, stroke(withAlpha(Color.WHITE, 0.55f), r * 0.006f))
        if (design.clearHub) {
            canvas.drawCircle(cx, cy, r * 0.315f, stroke(withAlpha(Color.WHITE, 0.14f), r * 0.012f))
            canvas.drawCircle(cx, cy, r * 0.255f, stroke(withAlpha(Color.WHITE, 0.3f), r * 0.008f))
        }
        canvas.drawCircle(cx, cy, r * 0.17f, stroke(withAlpha(Color.WHITE, 0.25f), r * 0.01f))
        canvas.drawCircle(cx, cy, r * CdHole, cut)
        canvas.drawCircle(cx, cy, r * CdHole, stroke(withAlpha(Color.WHITE, 0.5f), r * 0.006f))
    }

    /** A record's grooves round the label: fine rings, and the smooth gaps between its songs. */
    private fun grooves(canvas: Canvas, design: DiscDesign, cx: Float, cy: Float, r: Float) {
        val k = design.grooves.coerceIn(0f, 1f)
        if (k <= 0f) return
        val inner = r * (design.label + 0.03f)
        val outer = r * 0.975f
        val random = Random(design.id.hashCode())
        val gap = r * 0.008f
        val gaps = List(4) { inner + (outer - inner) * (0.12f + random.nextFloat() * 0.8f) }.sortedDescending()
        // The grooved band with the gaps left out: nested circles, even-odd, alternate in and out.
        val band = Path().apply {
            fillMode = PathFillMode.EVEN_ODD
            addCircle(cx, cy, outer)
            for (g in gaps) {
                addCircle(cx, cy, g + gap)
                addCircle(cx, cy, g - gap)
            }
            addCircle(cx, cy, inner)
        }
        // One ring shader repeated out from the middle draws every groove in a single pass. Never
        // finer than a few pixels, or small covers shimmer with moiré; fainter when they're small.
        val pitch = max(r * 0.0065f, 3f)
        val g = k * min(1f, r / 260f)
        canvas.drawPath(band, Paint().apply {
            isAntiAlias = true
            shader = Shader.makeRadialGradient(
                cx, cy, pitch,
                intArrayOf(withAlpha(Color.BLACK, 0.26f * g), withAlpha(Color.WHITE, 0.05f * g), withAlpha(Color.BLACK, 0.26f * g)),
                floatArrayOf(0f, 0.5f, 1f),
                org.jetbrains.skia.GradientStyle(FilterTileMode.REPEAT, true, null),
            )
        })
        // The lead-in at the edge and the run-out next to the label.
        canvas.drawCircle(cx, cy, outer, stroke(withAlpha(Color.BLACK, 0.3f * k), r * 0.01f))
        canvas.drawCircle(cx, cy, inner - r * 0.012f, stroke(withAlpha(Color.BLACK, 0.25f * k), r * 0.02f))
    }

    /** The shine across a record: two wedges of light either side of the middle. */
    private fun vinylLight(canvas: Canvas, design: DiscDesign, cx: Float, cy: Float, r: Float) {
        val k = design.sheen.coerceIn(0f, 1f)
        if (k <= 0f) return
        val ring = Path().apply {
            fillMode = PathFillMode.EVEN_ODD
            addCircle(cx, cy, r)
            addCircle(cx, cy, r * design.label)
        }
        canvas.save()
        canvas.clipPath(ring, ClipMode.INTERSECT, true)
        canvas.rotate(design.lightAngle, cx, cy)
        val hi = withAlpha(Color.WHITE, 0.18f * k)
        canvas.drawCircle(cx, cy, r, Paint().apply {
            isAntiAlias = true
            blendMode = BlendMode.SCREEN
            shader = Shader.makeSweepGradient(cx, cy, intArrayOf(0, 0, hi, 0, 0, 0, hi, 0, 0), floatArrayOf(0f, 0.04f, 0.125f, 0.21f, 0.5f, 0.54f, 0.625f, 0.71f, 1f))
        })
        // A narrow glint in each wedge, broken into streaks by the grooves, as a lamp catches a real record.
        val bounds = Rect.makeLTRB(cx - r, cy - r, cx + r, cy + r)
        canvas.saveLayer(bounds, Paint().apply { blendMode = BlendMode.SCREEN })
        val glint = withAlpha(Color.WHITE, 0.62f * k)
        val faint = withAlpha(Color.WHITE, 0.3f * k)
        canvas.drawCircle(cx, cy, r, Paint().apply {
            isAntiAlias = true
            shader = Shader.makeSweepGradient(cx, cy, intArrayOf(0, 0, glint, 0, 0, 0, faint, 0, 0), floatArrayOf(0f, 0.1f, 0.125f, 0.15f, 0.5f, 0.6f, 0.625f, 0.65f, 1f))
        })
        val random = Random(design.id.hashCode() + 3)
        val bands = 90
        canvas.drawCircle(cx, cy, r, Paint().apply {
            isAntiAlias = true
            blendMode = BlendMode.DST_IN
            shader = Shader.makeRadialGradient(cx, cy, r, IntArray(bands) { i ->
                val inside = i / bands.toFloat() < design.label + 0.04f
                withAlpha(Color.WHITE, if (inside) 0f else 0.2f + random.nextFloat().pow(0.6f) * 0.8f)
            }, FloatArray(bands) { it / (bands - 1f) })
        })
        canvas.restore()
        canvas.restore()
        // The label is paper: a little light, no shine.
        canvas.drawCircle(cx, cy, r * design.label, Paint().apply {
            isAntiAlias = true
            shader = Shader.makeLinearGradient(cx - r * design.label, cy - r * design.label, cx + r * design.label, cy + r * design.label,
                intArrayOf(withAlpha(Color.WHITE, 0.08f * k), 0, withAlpha(Color.BLACK, 0.08f * k)), floatArrayOf(0f, 0.5f, 1f))
        })
    }

    private fun vinylMiddle(canvas: Canvas, design: DiscDesign, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx, cy, r * design.label, stroke(withAlpha(Color.BLACK, 0.18f), r * 0.004f))
        canvas.drawCircle(cx, cy, r * VinylHole, Paint().apply { isAntiAlias = true; blendMode = BlendMode.CLEAR })
        canvas.drawCircle(cx, cy, r * VinylHole, stroke(withAlpha(Color.BLACK, 0.4f), r * 0.004f))
    }

    /** A record half out of its sleeve, on the dark the CD covers sit on. */
    private fun sleeve(canvas: Canvas, design: DiscDesign, res: Resources, s: Float) {
        canvas.clear(Color.makeRGB(7, 7, 8))
        val sleeve = Rect.makeLTRB(s * 0.035f, s * 0.085f, s * 0.865f, s * 0.915f)
        val tint = design.layers.firstOrNull { it.visible && it.kind == LayerKind.FILL }?.colors?.firstOrNull()
            ?: design.layers.firstOrNull { it.visible && it.kind == LayerKind.PATTERN }?.colors?.firstOrNull()
            ?: design.labelColor
        val paper = mix(Color.makeRGB(24, 24, 27), argb(tint) or 0xFF000000.toInt(), 0.12f)
        canvas.drawRRect(RRect.makeLTRB(sleeve.left, sleeve.top, sleeve.right, sleeve.bottom, s * 0.012f), Paint().apply {
            isAntiAlias = true
            color = withAlpha(Color.BLACK, 0.6f)
            maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, s * 0.014f, true)
        })
        canvas.drawRRect(RRect.makeLTRB(sleeve.left, sleeve.top, sleeve.right, sleeve.bottom, s * 0.012f), fill(paper))
        canvas.drawRRect(RRect.makeLTRB(sleeve.left, sleeve.top, sleeve.right, sleeve.bottom, s * 0.012f), Paint().apply {
            isAntiAlias = true
            shader = Shader.makeLinearGradient(sleeve.left, sleeve.top, sleeve.right, sleeve.bottom,
                intArrayOf(withAlpha(Color.WHITE, 0.1f), withAlpha(Color.WHITE, 0.02f), withAlpha(Color.BLACK, 0.12f)), floatArrayOf(0f, 0.5f, 1f))
        })
        canvas.drawRRect(RRect.makeLTRB(sleeve.left, sleeve.top, sleeve.right, sleeve.bottom, s * 0.012f), stroke(withAlpha(Color.WHITE, 0.16f), s * 0.003f))
        drawDisc(canvas, design, res, s * 0.55f, s * 0.5f, s * 0.415f)
    }

    // ---- Layers ------------------------------------------------------------------------------

    private val alone = DiscDesign(id = "preview")

    /** One layer by itself over a disc of radius [r] at ([cx], [cy]): the editor's previews of patterns, stickers and fills. */
    fun drawLayerAlone(canvas: Canvas, layer: DiscLayer, res: Resources, cx: Float, cy: Float, r: Float) =
        drawLayer(canvas, alone, layer.copy(area = LayerArea.DISC, visible = true), res, cx, cy, r)

    private fun areaPath(design: DiscDesign, area: LayerArea, cx: Float, cy: Float, r: Float): Path? = when (area) {
        LayerArea.DISC -> null
        LayerArea.MIDDLE -> Path().addCircle(cx, cy, r * design.label)
        LayerArea.RING -> Path().apply {
            fillMode = PathFillMode.EVEN_ODD
            addCircle(cx, cy, r * 1.01f)
            addCircle(cx, cy, r * design.label)
        }
    }

    /** What a layer can draw on: the disc, or just round a picture or sticker, so its shadow is cheap to blur. */
    private fun layerBounds(layer: DiscLayer, res: Resources, cx: Float, cy: Float, r: Float): Rect {
        val disc = Rect.makeLTRB(cx - r * 1.05f, cy - r * 1.05f, cx + r * 1.05f, cy + r * 1.05f)
        if (layer.kind != LayerKind.IMAGE && layer.kind != LayerKind.STICKER && layer.kind != LayerKind.SHAPE && !layer.boxed) return disc
        val c = corners(layer, res, cx, cy, r) ?: return disc
        val margin = r * layer.scale.coerceAtMost(1.5f) * 0.35f + r * 0.08f + DiscEffects.reach(layer, r)
        val box = Rect.makeLTRB(c.minOf { it.first } - margin, c.minOf { it.second } - margin, c.maxOf { it.first } + margin, c.maxOf { it.second } + margin)
        return box.intersect(disc) ?: Rect.makeWH(1f, 1f)
    }

    private fun blendOf(blend: LayerBlend): BlendMode = when (blend) {
        LayerBlend.NORMAL -> BlendMode.SRC_OVER
        LayerBlend.MULTIPLY -> BlendMode.MULTIPLY
        LayerBlend.SCREEN -> BlendMode.SCREEN
        LayerBlend.OVERLAY -> BlendMode.OVERLAY
        LayerBlend.SOFT_LIGHT -> BlendMode.SOFT_LIGHT
        LayerBlend.ADD -> BlendMode.PLUS
        LayerBlend.DIFFERENCE -> BlendMode.DIFFERENCE
        LayerBlend.COLOR -> BlendMode.COLOR
    }

    private fun drawLayer(canvas: Canvas, design: DiscDesign, layer: DiscLayer, res: Resources, cx: Float, cy: Float, r: Float) {
        canvas.save()
        areaPath(design, layer.area, cx, cy, r)?.let { canvas.clipPath(it, ClipMode.INTERSECT, true) }
        val ox = cx + layer.x * r
        val oy = cy + layer.y * r
        val paint = Paint().apply {
            alpha = (layer.opacity.coerceIn(0f, 1f) * 255).roundToInt()
            blendMode = blendOf(layer.blend)
            val effects = DiscEffects.filter(layer, r, ox, oy)
            imageFilter = if (layer.shadow) ImageFilter.makeDropShadow(r * 0.012f, r * 0.022f, r * 0.024f, r * 0.024f, withAlpha(Color.BLACK, 0.55f), effects, null) else effects
        }
        canvas.saveLayer(layerBounds(layer, res, cx, cy, r), paint)
        canvas.translate(ox, oy)
        canvas.rotate(layer.rotation)
        val palette = layer.colors.map(::argb).ifEmpty { listOf(Color.WHITE) }
        // How far out a layer that covers the disc has to reach from where it's centred.
        val reach = r * (1.05f + hypot(layer.x, layer.y))
        when (layer.kind) {
            LayerKind.FILL -> fillLayer(canvas, layer, palette, r, reach)
            LayerKind.IMAGE -> imageLayer(canvas, layer, palette, res, r)
            LayerKind.TEXT -> textLayer(canvas, layer, palette, res, r, reach)
            LayerKind.PATTERN -> {
                val scale = layer.scale.coerceAtLeast(0.05f)
                canvas.scale(scale, scale)
                pattern(canvas, layer, palette, r, reach / scale)
            }
            LayerKind.STICKER -> stickerLayer(canvas, layer, palette, res, r)
            LayerKind.SHAPE -> shapeLayer(canvas, layer, palette, r)
        }
        canvas.restore()
        canvas.restore()
    }

    private fun fillLayer(canvas: Canvas, layer: DiscLayer, palette: List<Int>, r: Float, reach: Float) {
        val scale = layer.scale.coerceAtLeast(0.05f)
        val e = reach / scale
        canvas.scale(scale, scale)
        val colors = if (palette.size == 1) intArrayOf(palette[0], palette[0]) else palette.toIntArray()
        val stops = FloatArray(colors.size) { it / (colors.size - 1f) }
        val paint = Paint().apply {
            isAntiAlias = true
            when (layer.fill) {
                FillKind.SOLID -> color = palette[0]
                FillKind.LINEAR -> shader = Shader.makeLinearGradient(-r, 0f, r, 0f, colors, stops)
                FillKind.RADIAL -> shader = Shader.makeRadialGradient(0f, 0f, r, colors, stops)
                FillKind.SWEEP -> {
                    // Round to the first colour again, so there's no seam.
                    val loop = colors + colors[0]
                    shader = Shader.makeSweepGradient(0f, 0f, loop, FloatArray(loop.size) { it / (loop.size - 1f) })
                }
            }
        }
        canvas.drawRect(Rect.makeLTRB(-e, -e, e, e), paint)
    }

    // ---- Pictures ----------------------------------------------------------------------------

    private fun imageLayer(canvas: Canvas, layer: DiscLayer, palette: List<Int>, res: Resources, r: Float) {
        val side = 2f * r * layer.scale
        val dst = Rect.makeXYWH(-side / 2f, -side / 2f, side, side)
        val image = layer.asset?.let { runCatching { res.image(it) }.getOrNull() }
        if (layer.frame == ImageFrame.PHOTO) {
            val border = side * 0.055f
            canvas.drawRect(Rect.makeLTRB(dst.left - border, dst.top - border, dst.right + border, dst.bottom + border * 3.2f), fill(Color.makeRGB(250, 249, 245)))
        }
        if (image == null) {
            placeholder(canvas, dst, layer.frame == ImageFrame.CIRCLE)
            return
        }
        val picture = if (layer.look == ImageLook.PIXEL) pixelate(image, layer.amount) else image
        val paint = Paint().apply {
            colorFilter = lookFilter(layer, palette)
            if (layer.look == ImageLook.GLOW) imageFilter = ImageFilter.makeBlur(side * 0.03f * layer.amount, side * 0.03f * layer.amount, FilterTileMode.CLAMP, null, null)
        }
        val sampling = if (layer.look == ImageLook.PIXEL) SamplingMode.DEFAULT else SamplingMode.MITCHELL
        if (layer.frame == ImageFrame.CIRCLE) {
            canvas.save()
            canvas.clipPath(Path().addCircle(0f, 0f, side / 2f), ClipMode.INTERSECT, true)
            cover(canvas, picture, dst, paint, sampling)
            canvas.restore()
        } else {
            cover(canvas, picture, dst, paint, sampling)
        }
        if (layer.look == ImageLook.GLOW) {
            // The sharp picture again over its own glow.
            if (layer.frame == ImageFrame.CIRCLE) {
                canvas.save()
                canvas.clipPath(Path().addCircle(0f, 0f, side / 2f), ClipMode.INTERSECT, true)
            }
            cover(canvas, picture, dst, Paint().apply { alpha = 150; blendMode = BlendMode.SCREEN; colorFilter = lookFilter(layer, palette) }, sampling)
            if (layer.frame == ImageFrame.CIRCLE) canvas.restore()
        }
    }

    private fun placeholder(canvas: Canvas, dst: Rect, round: Boolean) {
        val paint = fill(withAlpha(Color.WHITE, 0.16f))
        if (round) canvas.drawCircle(dst.left + dst.width / 2f, dst.top + dst.height / 2f, dst.width / 2f, paint) else canvas.drawRect(dst, paint)
        val line = stroke(withAlpha(Color.WHITE, 0.4f), dst.width * 0.012f)
        canvas.drawLine(dst.left, dst.top, dst.right, dst.bottom, line)
        canvas.drawLine(dst.right, dst.top, dst.left, dst.bottom, line)
    }

    /** Draws [image] to cover [dst], cropping the middle. */
    private fun cover(canvas: Canvas, image: Image, dst: Rect, paint: Paint?, sampling: SamplingMode) {
        val side = min(image.width, image.height).toFloat()
        val src = Rect.makeXYWH((image.width - side) / 2f, (image.height - side) / 2f, side, side)
        canvas.drawImageRect(image, src, dst, sampling, paint, true)
    }

    private fun pixelate(image: Image, amount: Float): Image {
        val n = (64 - amount.coerceIn(0f, 1f) * 54).roundToInt().coerceAtLeast(6)
        val small = Surface.makeRasterN32Premul(n, n)
        cover(small.canvas, image, Rect.makeWH(n.toFloat(), n.toFloat()), null, SamplingMode.LINEAR)
        return small.makeImageSnapshot()
    }

    /** [matrix] eased in from no change at all, by [t]. */
    private fun towards(matrix: FloatArray, t: Float): ColorMatrix {
        val id = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        val k = t.coerceIn(0f, 1f)
        return ColorMatrix(*FloatArray(20) { id[it] + (matrix[it] - id[it]) * k })
    }

    private fun lookFilter(layer: DiscLayer, palette: List<Int>): ColorFilter? {
        val t = layer.amount
        return when (layer.look) {
            ImageLook.NONE -> null
            ImageLook.MONO -> {
                val c = 1.3f
                val o = (1 - c) / 2f
                ColorFilter.makeMatrix(towards(floatArrayOf(
                    0.2126f * c, 0.7152f * c, 0.0722f * c, 0f, o,
                    0.2126f * c, 0.7152f * c, 0.0722f * c, 0f, o,
                    0.2126f * c, 0.7152f * c, 0.0722f * c, 0f, o,
                    0f, 0f, 0f, 1f, 0f,
                ), 0.4f + t * 0.6f))
            }
            ImageLook.DUOTONE -> {
                val lo = palette.getOrElse(0) { Color.makeRGB(20, 12, 40) }
                val hi = palette.getOrElse(1) { Color.makeRGB(255, 110, 180) }
                fun row(l: Int, h: Int): FloatArray {
                    val a = l / 255f
                    val d = (h - l) / 255f
                    return floatArrayOf(0.2126f * d * 1.15f, 0.7152f * d * 1.15f, 0.0722f * d * 1.15f, 0f, a - 0.07f * d)
                }
                ColorFilter.makeMatrix(towards(
                    row(Color.getR(lo), Color.getR(hi)) + row(Color.getG(lo), Color.getG(hi)) + row(Color.getB(lo), Color.getB(hi)) + floatArrayOf(0f, 0f, 0f, 1f, 0f),
                    0.4f + t * 0.6f,
                ))
            }
            ImageLook.VINTAGE -> ColorFilter.makeMatrix(towards(floatArrayOf(
                0.393f * 0.9f, 0.769f * 0.9f, 0.189f * 0.9f, 0f, 0.06f,
                0.349f * 0.9f, 0.686f * 0.9f, 0.168f * 0.9f, 0f, 0.05f,
                0.272f * 0.9f, 0.534f * 0.9f, 0.131f * 0.9f, 0f, 0.04f,
                0f, 0f, 0f, 1f, 0f,
            ), 0.4f + t * 0.6f))
            ImageLook.INVERT -> ColorFilter.makeMatrix(towards(floatArrayOf(
                -1f, 0f, 0f, 0f, 1f,
                0f, -1f, 0f, 0f, 1f,
                0f, 0f, -1f, 0f, 1f,
                0f, 0f, 0f, 1f, 0f,
            ), 0.4f + t * 0.6f))
            ImageLook.GLOW -> {
                val s = 1.6f
                val lr = 0.2126f * (1 - s)
                val lg = 0.7152f * (1 - s)
                val lb = 0.0722f * (1 - s)
                ColorFilter.makeMatrix(ColorMatrix(lr + s, lg, lb, 0f, 0.02f, lr, lg + s, lb, 0f, 0.02f, lr, lg, lb + s, 0f, 0.02f, 0f, 0f, 0f, 1f, 0f))
            }
            ImageLook.PIXEL -> {
                // Fewer, punchier colours, like an old console's.
                val levels = ByteArray(256) { i -> ((((i / 255f) * 5).toInt().coerceAtMost(4) / 4f) * 255).toInt().toByte() }
                val lift = ColorMatrix(1.15f, 0f, 0f, 0f, 0.05f, 0f, 1.15f, 0f, 0f, 0.05f, 0f, 0f, 1.15f, 0f, 0.05f, 0f, 0f, 0f, 1f, 0f)
                ColorFilter.makeComposed(ColorFilter.makeTableARGB(null, levels, levels, levels), ColorFilter.makeMatrix(lift))
            }
        }
    }

    // ---- Text --------------------------------------------------------------------------------

    /** Text's size on the disc: scale 1 is a big heading. */
    private fun textSize(layer: DiscLayer, r: Float) = r * 0.4f * layer.scale.coerceAtLeast(0.02f)

    private class Run(val glyphs: ShortArray, val advances: FloatArray, val font: Font) {
        val width: Float = advances.sum()
    }

    private val fallbackCache = HashMap<Int, Typeface>()

    /** The layer's font, or one that has every character it needs (emoji, other scripts). */
    private fun typefaceFor(text: String, font: String, res: Resources): Typeface {
        val chosen = runCatching { res.typeface(font) }.getOrNull() ?: systemTypeface(font)
        if (chosen != null && Font(chosen, 20f).getStringGlyphs(text).none { it.toInt() == 0 }) return chosen
        val needs = text.codePoints().toArray().firstOrNull { cp -> chosen == null || Font(chosen, 20f).getStringGlyphs(String(Character.toChars(cp))).firstOrNull()?.toInt() == 0 } ?: 'A'.code
        return synchronized(fallbackCache) {
            fallbackCache.getOrPut(needs) {
                FontMgr.default.matchFamilyStyleCharacter(null, FontStyle.BOLD, null, needs)
                    ?: chosen
                    ?: FontMgr.default.matchFamilyStyle(null, FontStyle.BOLD)
                    ?: Typeface.makeEmpty()
            }
        }
    }

    private val bundledCache = HashMap<String, Typeface?>()

    /** The fonts Convx ships, by [DiscFont] id; the variable ones set to a heading's weight. */
    fun bundledTypeface(font: String): Typeface? = synchronized(bundledCache) {
        bundledCache.getOrPut(font) {
            val (file, weight) = when (font) {
                DiscFont.CHILLAX_BOLD.id -> "chillax_bold.otf" to null
                DiscFont.CHILLAX.id -> "chillax_medium.otf" to null
                DiscFont.CHILLAX_LIGHT.id -> "chillax_light.otf" to null
                DiscFont.OUTFIT.id -> "outfit.ttf" to 700f
                DiscFont.JAKARTA.id -> "plus_jakarta_sans.ttf" to 800f
                DiscFont.SF_BOLD.id -> "sf_pro_text_bold.otf" to null
                DiscFont.SF.id -> "sf_pro_text_medium.otf" to null
                DiscFont.INK_BRUSH.id -> "ma_shan_zheng.ttf" to null
                DiscFont.BLACKLETTER.id -> "unifraktur_maguntia.ttf" to null
                DiscFont.MARKER.id -> "permanent_marker.ttf" to null
                else -> return@getOrPut null
            }
            runCatching {
                val bytes = DiscArt::class.java.classLoader.getResourceAsStream("fonts/$file")?.use { it.readBytes() } ?: return@runCatching null
                val typeface = FontMgr.default.makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes)) ?: return@runCatching null
                if (weight != null) typeface.makeClone(org.jetbrains.skia.FontVariation("wght", weight)) else typeface
            }.getOrNull()
        }
    }

    private val systemCache = HashMap<String, Typeface?>()

    /** Windows' own families for the fonts Convx doesn't ship. */
    fun systemTypeface(font: String): Typeface? = synchronized(systemCache) { systemCache.getOrPut(font) { systemFamily(font) } }

    private fun systemFamily(font: String): Typeface? {
        val (families, style) = when (font) {
            DiscFont.SERIF.id -> listOf("Georgia", "Times New Roman", "DejaVu Serif", "serif") to FontStyle.BOLD
            DiscFont.MONO.id -> listOf("Consolas", "Cascadia Mono", "Courier New", "DejaVu Sans Mono", "monospace") to FontStyle.BOLD
            DiscFont.SCRIPT.id -> listOf("Segoe Script", "Brush Script MT", "Lucida Handwriting", "URW Chancery L", "cursive") to FontStyle.NORMAL
            DiscFont.IMPACT.id -> listOf("Impact", "Haettenschweiler", "Arial Black", "DejaVu Sans Condensed") to FontStyle.BOLD
            else -> listOf("Segoe UI", "Arial", "DejaVu Sans") to FontStyle.BOLD
        }
        return FontMgr.default.matchFamiliesStyle(families.toTypedArray(), style)
    }

    private fun run(text: String, layer: DiscLayer, res: Resources, size: Float): Run {
        val font = Font(typefaceFor(text, layer.font, res), size)
        val glyphs = font.getStringGlyphs(text)
        val widths = font.getWidths(glyphs)
        val extra = layer.spacing * size
        return Run(glyphs, FloatArray(widths.size) { widths[it] + if (it < widths.lastIndex) extra else 0f }, font)
    }

    private fun textPaint(layer: DiscLayer, color: Int, size: Float, outlined: Boolean = layer.outline) = Paint().apply {
        isAntiAlias = true
        this.color = color
        if (outlined) {
            mode = PaintMode.STROKE
            strokeWidth = size * 0.045f
            strokeJoin = PaintStrokeJoin.ROUND
        }
    }

    private fun textLayer(canvas: Canvas, layer: DiscLayer, palette: List<Int>, res: Resources, r: Float, reach: Float) {
        val text = layer.text.ifBlank { return }
        val size = textSize(layer, r)
        val ink = palette[0]
        when (layer.path) {
            TextPath.STRAIGHT -> {
                val lines = text.split('\n')
                val lineHeight = size * 1.08f
                val runs = lines.map { run(it, layer, res, size) }
                val cap = runs.firstOrNull()?.font?.metrics?.capHeight ?: size * 0.7f
                var y = cap / 2f - lineHeight * (lines.size - 1) / 2f
                val widest = runs.maxOf { it.width }
                for (run in runs) {
                    val x = when (layer.align) {
                        TextAlignment.LEFT -> -widest / 2f
                        TextAlignment.CENTER -> -run.width / 2f
                        TextAlignment.RIGHT -> widest / 2f - run.width
                    }
                    straight(canvas, run, x, y, textPaint(layer, ink, size))
                    y += lineHeight
                }
            }
            TextPath.VERTICAL -> vertical(canvas, layer, res, size, textPaint(layer, ink, size))
            TextPath.ARC_TOP, TextPath.ARC_BOTTOM -> {
                val run = run(text, layer, res, size)
                val cap = run.font.metrics.capHeight
                val radius = r * layer.radius
                arc(canvas, run, radius, layer.path == TextPath.ARC_BOTTOM, cap, textPaint(layer, ink, size))
            }
            TextPath.CIRCLE -> {
                val unit = run("$text  •  ", layer, res, size)
                val radius = r * layer.radius
                val cap = unit.font.metrics.capHeight
                val baseline = radius - cap / 2f
                val around = 2f * PI.toFloat() * baseline
                val n = max(1, floor(around / max(1f, unit.width)).toInt())
                val stretch = around / (n * unit.width)
                val glyphs = ShortArray(unit.glyphs.size * n)
                val xforms = ArrayList<RSXform>(glyphs.size)
                var along = 0f
                for (k in 0 until n) {
                    for (i in unit.glyphs.indices) {
                        glyphs[k * unit.glyphs.size + i] = unit.glyphs[i]
                        val w = unit.advances[i] * stretch
                        xforms += onCircle(baseline, -PI.toFloat() / 2f + (along + w / 2f) / baseline, unit.advances[i], bottom = false)
                        along += w
                    }
                }
                TextBlob.makeFromRSXform(glyphs, xforms.toTypedArray(), unit.font)?.let { canvas.drawTextBlob(it, 0f, 0f, textPaint(layer, ink, size)) }
            }
            TextPath.REPEAT -> {
                val run = run("$text  ", layer, res, size)
                if (run.width <= 0f) return
                val step = size * 0.92f
                var y = -reach
                var row = 0
                while (y < reach + step) {
                    val outlined = layer.outline && row % 2 == 1
                    val paint = textPaint(layer, ink, size, outlined)
                    var x = -reach - ((row * 0.37f) % 1f) * run.width
                    while (x < reach) {
                        straight(canvas, run, x, y, paint)
                        x += run.width
                    }
                    y += step
                    row++
                }
            }
        }
    }

    /** Vertical text's columns: one per line, each character upright on its own row. */
    private class Column(val glyphs: ShortArray, val widths: FloatArray, val font: Font)

    private fun columns(layer: DiscLayer, res: Resources, size: Float): List<Column> {
        val font = Font(typefaceFor(layer.text.replace("\n", ""), layer.font, res), size)
        return layer.text.split('\n').map { line ->
            val glyphs = font.getStringGlyphs(line)
            Column(glyphs, font.getWidths(glyphs), font)
        }
    }

    /** How far apart vertical text's rows and its columns are. */
    private fun verticalSteps(layer: DiscLayer, size: Float) = size * (1.02f + layer.spacing) to size * 1.2f

    /** The size of vertical text's block, half width and half height. */
    private fun verticalHalf(layer: DiscLayer, res: Resources, size: Float): Pair<Float, Float> {
        val (row, col) = verticalSteps(layer, size)
        val cols = columns(layer, res, size)
        return cols.size * col / 2f to (cols.maxOfOrNull { it.glyphs.size } ?: 0) * row / 2f
    }

    private fun vertical(canvas: Canvas, layer: DiscLayer, res: Resources, size: Float, paint: Paint) {
        val cols = columns(layer, res, size)
        val (row, col) = verticalSteps(layer, size)
        val tallest = cols.maxOfOrNull { it.glyphs.size } ?: return
        val metrics = cols.first().font.metrics
        val lift = -(metrics.ascent + metrics.descent) / 2f
        val top = -tallest * row / 2f
        for ((i, column) in cols.withIndex()) {
            if (column.glyphs.isEmpty()) continue
            // The first line on the right, as on a spine or a poster.
            val x = (cols.size - 1) / 2f * col - i * col
            val offset = when (layer.align) {
                TextAlignment.LEFT -> 0f
                TextAlignment.CENTER -> (tallest - column.glyphs.size) * row / 2f
                TextAlignment.RIGHT -> (tallest - column.glyphs.size) * row
            }
            val points = Array(column.glyphs.size) { j ->
                org.jetbrains.skia.Point(x - column.widths[j] / 2f, top + offset + row * (j + 0.5f) + lift)
            }
            TextBlob.makeFromPos(column.glyphs, points, column.font)?.let { canvas.drawTextBlob(it, 0f, 0f, paint) }
        }
    }

    private fun straight(canvas: Canvas, run: Run, x: Float, y: Float, paint: Paint) {
        if (run.glyphs.isEmpty()) return
        val xs = FloatArray(run.glyphs.size)
        var at = 0f
        for (i in run.glyphs.indices) {
            xs[i] = at
            at += run.advances[i]
        }
        TextBlob.makeFromPosH(run.glyphs, xs, 0f, run.font)?.let { canvas.drawTextBlob(it, x, y, paint) }
    }

    /**
     * One glyph on a circle of [radius] round (0, 0), centred at angle [theta] (radians, 0 = right),
     * upright outward for text over the top, inward for text under the bottom.
     */
    private fun onCircle(radius: Float, theta: Float, advance: Float, bottom: Boolean): RSXform {
        val px = radius * cos(theta)
        val py = radius * sin(theta)
        return if (!bottom) {
            val sc = -sin(theta)
            val ss = cos(theta)
            RSXform(sc, ss, px - sc * advance / 2f, py - ss * advance / 2f)
        } else {
            val sc = sin(theta)
            val ss = -cos(theta)
            RSXform(sc, ss, px - sc * advance / 2f, py - ss * advance / 2f)
        }
    }

    /** [run] centred on the top (or the bottom) of a circle of [radius], its letters' middles on the circle. */
    private fun arc(canvas: Canvas, run: Run, radius: Float, bottom: Boolean, cap: Float, paint: Paint) {
        if (run.glyphs.isEmpty()) return
        val baseline = if (bottom) radius + cap / 2f else radius - cap / 2f
        val r = max(baseline, 1f)
        val xforms = ArrayList<RSXform>(run.glyphs.size)
        var along = -run.width / 2f
        for (i in run.glyphs.indices) {
            val mid = along + run.advances[i] / 2f
            val theta = if (bottom) PI.toFloat() / 2f - mid / r else -PI.toFloat() / 2f + mid / r
            xforms += onCircle(r, theta, run.advances[i], bottom)
            along += run.advances[i]
        }
        TextBlob.makeFromRSXform(run.glyphs, xforms.toTypedArray(), run.font)?.let { canvas.drawTextBlob(it, 0f, 0f, paint) }
    }

    // ---- Stickers ----------------------------------------------------------------------------

    private fun stickerLayer(canvas: Canvas, layer: DiscLayer, palette: List<Int>, res: Resources, r: Float) {
        val h = r * layer.scale.coerceAtLeast(0.02f)
        if (layer.sticker.startsWith(EmojiStickerPrefix)) {
            val emoji = layer.sticker.removePrefix(EmojiStickerPrefix)
            val font = Font(typefaceFor(emoji, "emoji", res), h * 1.6f)
            val glyphs = font.getStringGlyphs(emoji)
            val widths = font.getWidths(glyphs)
            val xs = FloatArray(glyphs.size)
            var at = 0f
            for (i in glyphs.indices) {
                xs[i] = at
                at += widths[i]
            }
            val metrics = font.metrics
            if (glyphs.isNotEmpty()) {
                TextBlob.makeFromPosH(glyphs, xs, 0f, font)?.let {
                    canvas.drawTextBlob(it, -at / 2f, -(metrics.ascent + metrics.descent) / 2f, Paint().apply { isAntiAlias = true; color = Color.WHITE })
                }
            }
            return
        }
        val sticker = DiscSticker.of(layer.sticker) ?: DiscSticker.STAR
        val parts = stickerParts(sticker, palette[0])
        canvas.save()
        canvas.scale(h, h)
        if (layer.outline) {
            // A die-cut sticker's white border.
            val border = Paint().apply {
                isAntiAlias = true
                color = Color.WHITE
                mode = PaintMode.STROKE
                strokeWidth = 0.12f + 0.4f * layer.amount
                strokeJoin = PaintStrokeJoin.ROUND
                strokeCap = PaintStrokeCap.ROUND
            }
            for (part in parts) canvas.drawPath(part.path, border)
            for (part in parts) if (part.stroke == null) canvas.drawPath(part.path, fill(Color.WHITE))
        }
        for (part in parts) {
            canvas.drawPath(part.path, Paint().apply {
                isAntiAlias = true
                color = part.color
                part.stroke?.let {
                    mode = PaintMode.STROKE
                    strokeWidth = it
                    strokeCap = PaintStrokeCap.ROUND
                    strokeJoin = PaintStrokeJoin.ROUND
                }
            })
        }
        canvas.restore()
    }

    private class Part(val path: Path, val color: Int, val stroke: Float? = null)

    private fun poly(vararg xy: Float): Path = Path().addPoly(xy, true)

    private fun circle(x: Float, y: Float, r: Float): Path = Path().addCircle(x, y, r)

    private fun star(points: Int, outer: Float, inner: Float, turn: Float = 0f): Path {
        val path = Path()
        for (i in 0 until points * 2) {
            val rad = if (i % 2 == 0) outer else inner
            val a = turn + (i * PI / points).toFloat() - (PI / 2).toFloat()
            if (i == 0) path.moveTo(cos(a) * rad, sin(a) * rad) else path.lineTo(cos(a) * rad, sin(a) * rad)
        }
        return path.closePath()
    }

    private fun sparkle(): Path = Path().apply {
        moveTo(0f, -1f)
        quadTo(0.13f, -0.13f, 1f, 0f)
        quadTo(0.13f, 0.13f, 0f, 1f)
        quadTo(-0.13f, 0.13f, -1f, 0f)
        quadTo(-0.13f, -0.13f, 0f, -1f)
        closePath()
    }

    private fun heart(): Path = Path().apply {
        moveTo(0f, 0.9f)
        cubicTo(-0.5f, 0.55f, -1.05f, 0.15f, -0.98f, -0.32f)
        cubicTo(-0.92f, -0.82f, -0.25f, -0.98f, 0f, -0.5f)
        cubicTo(0.25f, -0.98f, 0.92f, -0.82f, 0.98f, -0.32f)
        cubicTo(1.05f, 0.15f, 0.5f, 0.55f, 0f, 0.9f)
        closePath()
    }

    private fun stickerParts(sticker: DiscSticker, color: Int): List<Part> {
        val dark = Color.makeRGB(40, 24, 10)
        return when (sticker) {
            DiscSticker.STAR -> listOf(Part(star(5, 1f, 0.46f), color))
            DiscSticker.SPARKLE -> listOf(Part(sparkle(), color))
            DiscSticker.HEART -> listOf(Part(heart(), color))
            DiscSticker.BOLT -> listOf(Part(poly(0.22f, -1f, -0.62f, 0.14f, -0.06f, 0.14f, -0.24f, 1f, 0.62f, -0.2f, 0.06f, -0.2f), color))
            DiscSticker.FLOWER -> {
                val petals = Path()
                for (i in 0 until 6) {
                    val a = (i * PI / 3).toFloat()
                    petals.addCircle(cos(a) * 0.52f, sin(a) * 0.52f, 0.44f)
                }
                listOf(Part(petals, color), Part(circle(0f, 0f, 0.34f), Color.makeRGB(255, 214, 64)))
            }
            DiscSticker.SMILE -> {
                val mouth = Path().apply {
                    moveTo(-0.5f, 0.18f)
                    quadTo(0f, 0.75f, 0.5f, 0.18f)
                }
                listOf(
                    Part(circle(0f, 0f, 1f), color),
                    Part(Path().addOval(Rect.makeLTRB(-0.42f, -0.48f, -0.2f, -0.06f)), dark),
                    Part(Path().addOval(Rect.makeLTRB(0.2f, -0.48f, 0.42f, -0.06f)), dark),
                    Part(mouth, dark, stroke = 0.13f),
                )
            }
            DiscSticker.MOON -> listOf(Part(Path.makeCombining(circle(0f, 0f, 1f), circle(0.48f, -0.3f, 0.86f), PathOp.DIFFERENCE) ?: circle(0f, 0f, 1f), color))
            DiscSticker.CROWN -> {
                val band = poly(-0.9f, 0.62f, -0.9f, -0.32f, -0.45f, 0.12f, 0f, -0.62f, 0.45f, 0.12f, 0.9f, -0.32f, 0.9f, 0.62f)
                val gems = Path().addCircle(-0.9f, -0.42f, 0.14f).addCircle(0f, -0.72f, 0.14f).addCircle(0.9f, -0.42f, 0.14f)
                listOf(Part(band, color), Part(gems, color), Part(poly(-0.9f, 0.4f, 0.9f, 0.4f, 0.9f, 0.62f, -0.9f, 0.62f), withAlpha(Color.BLACK, 0.22f)))
            }
            DiscSticker.BURST -> listOf(
                Part(star(16, 1f, 0.82f), color),
                Part(circle(0f, 0f, 0.62f), withAlpha(Color.WHITE, 0.85f), stroke = 0.06f),
            )
            DiscSticker.NOTE -> {
                val heads = Path()
                    .addOval(Rect.makeLTRB(-0.88f, 0.34f, -0.3f, 0.78f))
                    .addOval(Rect.makeLTRB(0.22f, 0.14f, 0.8f, 0.58f))
                val stems = poly(-0.42f, 0.55f, -0.3f, 0.55f, -0.3f, -0.62f, -0.42f, -0.62f)
                    .addPoly(floatArrayOf(0.68f, 0.35f, 0.8f, 0.35f, 0.8f, -0.84f, 0.68f, -0.84f), true)
                val beam = poly(-0.42f, -0.62f, 0.8f, -0.92f, 0.8f, -0.64f, -0.42f, -0.34f)
                listOf(Part(heads, color), Part(stems, color), Part(beam, color))
            }
            DiscSticker.CHERRY -> {
                val stems = Path().apply {
                    moveTo(-0.4f, 0.2f)
                    quadTo(-0.2f, -0.45f, 0.18f, -0.78f)
                    moveTo(0.42f, 0.32f)
                    quadTo(0.38f, -0.3f, 0.18f, -0.78f)
                }
                val leaf = Path().apply {
                    moveTo(0.18f, -0.78f)
                    quadTo(0.55f, -1.05f, 0.92f, -0.7f)
                    quadTo(0.55f, -0.5f, 0.18f, -0.78f)
                    closePath()
                }
                listOf(
                    Part(stems, Color.makeRGB(60, 120, 50), stroke = 0.09f),
                    Part(leaf, Color.makeRGB(76, 170, 70)),
                    Part(circle(-0.42f, 0.5f, 0.36f), color),
                    Part(circle(0.42f, 0.6f, 0.36f), color),
                    Part(circle(-0.53f, 0.38f, 0.08f), withAlpha(Color.WHITE, 0.6f)),
                    Part(circle(0.31f, 0.48f, 0.08f), withAlpha(Color.WHITE, 0.6f)),
                )
            }
            DiscSticker.ARROW -> listOf(Part(poly(-1f, -0.2f, 0.28f, -0.2f, 0.28f, -0.58f, 1f, 0f, 0.28f, 0.58f, 0.28f, 0.2f, -1f, 0.2f), color))
        }
    }

    // ---- Shapes ------------------------------------------------------------------------------

    /** A shape's half width and half height on a disc of radius [r]. */
    private fun shapeHalf(layer: DiscLayer, r: Float): Pair<Float, Float> {
        val hh = r * layer.scale.coerceAtLeast(0.01f)
        val hw = hh * layer.aspect.coerceIn(0.05f, 16f)
        // A line is as tall as it is thick, but still easy to grab.
        return if (layer.shape == ShapeKind.LINE) hw to max(lineWidth(layer, r), r * 0.035f) else hw to hh
    }

    private fun lineWidth(layer: DiscLayer, r: Float) = r * (0.003f + layer.amount.coerceIn(0f, 1f) * 0.03f)

    private fun shapeLayer(canvas: Canvas, layer: DiscLayer, palette: List<Int>, r: Float) {
        val hh = r * layer.scale.coerceAtLeast(0.01f)
        val hw = hh * layer.aspect.coerceIn(0.05f, 16f)
        val color = palette[0]
        val sw = lineWidth(layer, r)
        val line = stroke(color, sw).apply { strokeJoin = PaintStrokeJoin.MITER }
        val outlined = layer.outline && layer.shape.outlinable
        val paint = if (outlined) line else fill(color)
        val random = Random(layer.seed * 131 + layer.shape.ordinal)
        // Outlines can repeat inward, like a print pulled several times.
        val echoes = if (outlined) 1 + (layer.density.coerceIn(0f, 1f) * 4).roundToInt() else 1
        fun inset(i: Int) = min(hw, hh) * 0.16f * i
        when (layer.shape) {
            ShapeKind.BOX -> repeat(echoes) { i ->
                val d = inset(i)
                canvas.drawRect(Rect.makeLTRB(-hw + d, -hh + d, hw - d, hh - d), paint)
            }
            ShapeKind.CIRCLE -> repeat(echoes) { i ->
                val d = inset(i)
                canvas.drawOval(Rect.makeLTRB(-hw + d, -hh + d, hw - d, hh - d), paint)
            }
            ShapeKind.BADGE -> repeat(echoes) { i ->
                val d = inset(i)
                val oval = Rect.makeLTRB(-hw + d, -hh + d, hw - d, hh - d)
                canvas.drawPath(Path().apply {
                    moveTo(0f, 0f)
                    arcTo(oval, 35f, 290f, false)
                    closePath()
                }, paint)
            }
            ShapeKind.BLOB -> {
                val blob = blob(random)
                repeat(echoes) { i ->
                    val k = 1f - 0.16f * i
                    canvas.save()
                    canvas.scale(hw * k, hh * k)
                    canvas.drawPath(blob, if (outlined) stroke(color, sw / (min(hw, hh) * k)) else fill(color))
                    canvas.restore()
                }
            }
            ShapeKind.LINE -> canvas.drawLine(-hw, 0f, hw, 0f, line)
            ShapeKind.CROSSHAIR -> {
                val ring = min(hw, hh) * 0.5f
                canvas.drawCircle(0f, 0f, ring, line)
                val gap = ring * 0.45f
                canvas.drawLine(-hw, 0f, -gap, 0f, line)
                canvas.drawLine(gap, 0f, hw, 0f, line)
                canvas.drawLine(0f, -hh, 0f, -gap, line)
                canvas.drawLine(0f, gap, 0f, hh, line)
                canvas.drawCircle(0f, 0f, sw * 1.2f, fill(color))
                // Small ticks along the arms.
                for (k in 1..3) {
                    val t = ring + (hw - ring) * k / 4f
                    canvas.drawLine(t, -sw * 2.5f, t, sw * 2.5f, line)
                    canvas.drawLine(-t, -sw * 2.5f, -t, sw * 2.5f, line)
                }
            }
            ShapeKind.CORNERS -> {
                val l = min(hw, hh) * 0.3f
                for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                    canvas.drawPath(Path().apply {
                        moveTo(sx * hw, sy * (hh - l))
                        lineTo(sx * hw, sy * hh)
                        lineTo(sx * (hw - l), sy * hh)
                    }, line)
                }
            }
            ShapeKind.RULER -> {
                canvas.drawLine(-hw, -hh, hw, -hh, line)
                val n = 10 + (layer.density.coerceIn(0f, 1f) * 50).roundToInt()
                for (i in 0..n) {
                    val x = -hw + 2f * hw * i / n
                    val long = when {
                        i % 10 == 0 -> 2f * hh
                        i % 5 == 0 -> 1.3f * hh
                        else -> 0.7f * hh
                    }
                    canvas.drawLine(x, -hh, x, -hh + long, line)
                }
            }
            ShapeKind.BARCODE -> barcode(canvas, random, hw, hh, color)
            ShapeKind.TAPE -> {
                val teeth = 5
                val tooth = hh * 2f / teeth
                val bite = hh * 0.16f
                val tape = Path().apply {
                    moveTo(-hw, -hh)
                    lineTo(hw, -hh)
                    for (i in 0 until teeth) {
                        lineTo(hw - bite, -hh + tooth * (i + 0.5f))
                        lineTo(hw, -hh + tooth * (i + 1))
                    }
                    lineTo(-hw, hh)
                    for (i in teeth - 1 downTo 0) {
                        lineTo(-hw + bite, -hh + tooth * (i + 0.5f))
                        lineTo(-hw, -hh + tooth * i)
                    }
                    closePath()
                }
                canvas.drawPath(tape, fill(color))
                canvas.drawPath(tape, Paint().apply {
                    isAntiAlias = true
                    shader = Shader.makeLinearGradient(0f, -hh, 0f, hh, intArrayOf(withAlpha(Color.WHITE, 0.22f), 0, withAlpha(Color.BLACK, 0.08f)), floatArrayOf(0f, 0.5f, 1f))
                })
            }
            ShapeKind.SCRAP -> {
                val scrap = torn(random, hw, hh)
                canvas.drawPath(scrap, fill(color))
                // The paper's own tooth, only on the scrap.
                canvas.save()
                canvas.clipPath(scrap, ClipMode.INTERSECT, true)
                canvas.drawRect(Rect.makeLTRB(-hw * 1.1f, -hh * 1.1f, hw * 1.1f, hh * 1.1f), Paint().apply {
                    shader = DiscEffects.pattern(DiscEffects.paper, r / 300f)
                    blendMode = BlendMode.OVERLAY
                    alpha = 200
                })
                canvas.restore()
            }
        }
    }

    /** A soft, lumpy loop round (0, 0), about 1 across each way. */
    private fun blob(random: Random): Path {
        val n = 7
        val pts = List(n) { i ->
            val a = 2 * PI.toFloat() * i / n + random.nextFloat() * 0.4f
            val rr = 0.72f + random.nextFloat() * 0.28f
            cos(a) * rr to sin(a) * rr
        }
        // Catmull-Rom through the points, as curves.
        return Path().apply {
            moveTo(pts[0].first, pts[0].second)
            for (i in 0 until n) {
                val p0 = pts[(i - 1 + n) % n]
                val p1 = pts[i]
                val p2 = pts[(i + 1) % n]
                val p3 = pts[(i + 2) % n]
                cubicTo(
                    p1.first + (p2.first - p0.first) / 6f, p1.second + (p2.second - p0.second) / 6f,
                    p2.first - (p3.first - p1.first) / 6f, p2.second - (p3.second - p1.second) / 6f,
                    p2.first, p2.second,
                )
            }
            closePath()
        }
    }

    /** A rectangle with every edge torn: small random steps in and out along it. */
    private fun torn(random: Random, hw: Float, hh: Float): Path {
        val step = min(hw, hh) * 0.07f
        val jag = min(hw, hh) * 0.035f
        val path = Path()
        var first = true
        fun to(x: Float, y: Float) {
            if (first) path.moveTo(x, y) else path.lineTo(x, y)
            first = false
        }
        fun edge(x0: Float, y0: Float, x1: Float, y1: Float, nx: Float, ny: Float) {
            val len = hypot(x1 - x0, y1 - y0)
            val n = max(2, (len / step).toInt())
            for (i in 0 until n) {
                val t = i / n.toFloat()
                val j = (random.nextFloat() - 0.5f) * 2f * jag
                to(x0 + (x1 - x0) * t + nx * j, y0 + (y1 - y0) * t + ny * j)
            }
        }
        edge(-hw, -hh, hw, -hh, 0f, 1f)
        edge(hw, -hh, hw, hh, 1f, 0f)
        edge(hw, hh, -hw, hh, 0f, 1f)
        edge(-hw, hh, -hw, -hh, 1f, 0f)
        return path.closePath()
    }

    /** Bars of one to four widths, guard bars a little longer, and digits under them. */
    private fun barcode(canvas: Canvas, random: Random, hw: Float, hh: Float, color: Int) {
        val modules = 95
        val unit = 2f * hw / modules
        val digitsHeight = hh * 0.42f
        val barBottom = hh - digitsHeight
        val paint = fill(color).apply { isAntiAlias = false }
        var m = 0
        var dark = true
        while (m < modules) {
            val guard = m < 3 || m >= modules - 3 || m in 45..49
            val w = if (guard) 1 else 1 + random.nextInt(4)
            if (dark) {
                val bottom = if (guard) barBottom + digitsHeight * 0.45f else barBottom
                canvas.drawRect(Rect.makeLTRB(-hw + m * unit, -hh, -hw + (m + w) * unit, bottom), paint)
            }
            m += w
            dark = !dark
        }
        val typeface = systemTypeface(DiscFont.MONO.id) ?: return
        val font = Font(typeface, digitsHeight * 0.8f)
        val digits = buildString { repeat(13) { append(random.nextInt(10)) } }
        val width = font.measureTextWidth(digits)
        val squeeze = min(1f, (2f * hw * 0.92f) / max(1f, width))
        canvas.save()
        canvas.translate(0f, hh - digitsHeight * 0.12f)
        canvas.scale(squeeze, 1f)
        canvas.drawString(digits, -width / 2f, 0f, font, fill(color))
        canvas.restore()
    }

    // ---- Patterns ----------------------------------------------------------------------------

    private fun pattern(canvas: Canvas, layer: DiscLayer, palette: List<Int>, r: Float, e: Float) {
        val random = Random(layer.seed * 7919 + layer.pattern.ordinal)
        val d = layer.density.coerceIn(0f, 1f)
        val a = layer.amount.coerceIn(0f, 1f)
        val c0 = palette[0]
        fun color(i: Int) = palette[i.mod(palette.size)]
        // How many of a scattered thing fit: more for a bigger area.
        val area = (e / r).pow(2)
        when (layer.pattern) {
            PatternKind.STARS -> repeat(((15 + d * 90) * area).toInt()) {
                val size = r * (0.02f + random.nextFloat() * random.nextFloat() * 0.14f) * (0.5f + a)
                val x = (random.nextFloat() * 2 - 1) * e
                val y = (random.nextFloat() * 2 - 1) * e
                canvas.save()
                canvas.translate(x, y)
                canvas.rotate(random.nextFloat() * 72f)
                canvas.drawPath(star(5, size, size * 0.45f), fill(color(it)))
                canvas.restore()
            }
            PatternKind.SPARKLES -> repeat(((8 + d * 50) * area).toInt()) {
                val size = r * (0.035f + random.nextFloat() * random.nextFloat() * 0.18f) * (0.5f + a)
                val x = (random.nextFloat() * 2 - 1) * e
                val y = (random.nextFloat() * 2 - 1) * e
                canvas.save()
                canvas.translate(x, y)
                canvas.scale(size, size)
                canvas.drawPath(sparkle(), Paint().apply {
                    isAntiAlias = true
                    color = withAlpha(color(it), 0.5f)
                    maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 0.3f, true)
                })
                canvas.drawPath(sparkle(), fill(color(it)))
                canvas.restore()
            }
            PatternKind.DOTS -> {
                val step = r * (0.26f - d * 0.2f)
                val dot = step * (0.08f + a * 0.38f)
                grid(e, step, hex = true) { x, y, i -> canvas.drawCircle(x, y, dot, fill(color(i))) }
            }
            PatternKind.HALFTONE -> {
                val step = r * (0.15f - d * 0.1f)
                grid(e, step, hex = true) { x, y, i ->
                    val t = (hypot(x, y) / r).coerceIn(0f, 1.3f)
                    val dot = step * 0.62f * t.pow(1.2f) * (0.35f + a)
                    if (dot > 0.3f) canvas.drawCircle(x, y, min(dot, step * 0.62f), fill(color(i)))
                }
            }
            PatternKind.STRIPES -> {
                val step = r * (0.42f - d * 0.36f)
                val thick = step * (0.15f + a * 0.7f)
                var x = -e
                var i = 0
                while (x < e) {
                    canvas.drawRect(Rect.makeLTRB(x, -e, x + thick, e), fill(color(i)))
                    x += step
                    i++
                }
            }
            PatternKind.CHECKER -> {
                val cell = r * (0.5f - d * 0.42f)
                val n = (e / cell).toInt() + 1
                for (gx in -n..n) for (gy in -n..n) {
                    val odd = (gx + gy).mod(2) == 1
                    val paint = if (odd) palette.getOrNull(1)?.let(::fill) else fill(c0)
                    if (paint != null) canvas.drawRect(Rect.makeXYWH(gx * cell, gy * cell, cell, cell), paint)
                }
            }
            PatternKind.RAYS -> {
                val n = (8 + d * 40).toInt().coerceAtLeast(4) * 2
                val sweep = 360f / n
                val oval = Rect.makeLTRB(-e * 1.5f, -e * 1.5f, e * 1.5f, e * 1.5f)
                for (i in 0 until n step 2) {
                    canvas.drawArc(oval.left, oval.top, oval.right, oval.bottom, i * sweep, 2f * sweep * (0.25f + a * 0.5f), true, fill(color(i / 2)))
                }
            }
            PatternKind.RINGS -> {
                val step = r * (0.22f - d * 0.18f)
                val thick = step * (0.12f + a * 0.7f)
                var rr = step
                var i = 0
                while (rr < e * 1.45f) {
                    canvas.drawCircle(0f, 0f, rr, stroke(color(i), thick))
                    rr += step
                    i++
                }
            }
            PatternKind.WAVES -> {
                val step = r * (0.3f - d * 0.24f)
                val amp = step * (0.2f + a * 0.8f)
                var y = -e
                var i = 0
                while (y < e + step) {
                    val path = Path()
                    var x = -e
                    path.moveTo(x, y)
                    while (x < e) {
                        path.quadTo(x + step * 0.5f, y - amp, x + step, y)
                        path.quadTo(x + step * 1.5f, y + amp, x + step * 2f, y)
                        x += step * 2f
                    }
                    canvas.drawPath(path, stroke(color(i), step * 0.22f).apply { strokeCap = PaintStrokeCap.ROUND })
                    y += step
                    i++
                }
            }
            PatternKind.CONFETTI -> repeat(((30 + d * 220) * area).toInt()) {
                val size = r * (0.018f + a * 0.05f) * (0.6f + random.nextFloat())
                val x = (random.nextFloat() * 2 - 1) * e
                val y = (random.nextFloat() * 2 - 1) * e
                canvas.save()
                canvas.translate(x, y)
                canvas.rotate(random.nextFloat() * 360f)
                val paint = fill(color(it))
                when (random.nextInt(3)) {
                    0 -> canvas.drawRect(Rect.makeLTRB(-size, -size * 0.4f, size, size * 0.4f), paint)
                    1 -> canvas.drawCircle(0f, 0f, size * 0.55f, paint)
                    else -> canvas.drawPath(poly(0f, -size * 0.7f, size * 0.65f, size * 0.5f, -size * 0.65f, size * 0.5f), paint)
                }
                canvas.restore()
            }
            PatternKind.HEARTS -> repeat(((10 + d * 60) * area).toInt()) {
                val size = r * (0.04f + random.nextFloat() * random.nextFloat() * 0.12f) * (0.5f + a)
                val x = (random.nextFloat() * 2 - 1) * e
                val y = (random.nextFloat() * 2 - 1) * e
                canvas.save()
                canvas.translate(x, y)
                canvas.rotate(random.nextFloat() * 50f - 25f)
                canvas.scale(size, size)
                canvas.drawPath(heart(), fill(color(it)))
                canvas.restore()
            }
            PatternKind.PIXELS -> {
                val n = (6 + d * 28).toInt()
                val cell = 2f * r / n
                val count = (e / cell).toInt() + 1
                // Smooth blotches of colour, with some pixels off on their own, like a blown-up picture.
                val f1 = 0.35f + random.nextFloat() * 0.3f
                val f2 = 0.5f + random.nextFloat() * 0.4f
                val p1 = random.nextFloat() * 6f
                val p2 = random.nextFloat() * 6f
                for (gx in -count..count) for (gy in -count..count) {
                    val v = (sin(gx * f1 + p1) * cos(gy * f2 + p2) + sin((gx + gy) * 0.23f + p2) * 0.6f + 1.6f) / 3.2f
                    val noisy = if (random.nextFloat() < 0.18f + a * 0.3f) random.nextFloat() else v
                    val index = (noisy.coerceIn(0f, 0.999f) * palette.size).toInt()
                    canvas.drawRect(Rect.makeXYWH(gx * cell, gy * cell, cell + 0.6f, cell + 0.6f), fill(palette[index]))
                }
            }
            PatternKind.MARBLE -> {
                canvas.drawRect(Rect.makeLTRB(-e, -e, e, e), fill(c0))
                val vein = palette.getOrElse(1) { Color.WHITE }
                val freq = 1f / (r * (0.7f - d * 0.55f))
                val sharp = 3f + a * 6f
                canvas.drawRect(Rect.makeLTRB(-e, -e, e, e), Paint().apply {
                    shader = Shader.makeTurbulence(freq, freq * 0.8f, 4, layer.seed.toFloat())
                    colorFilter = ColorFilter.makeMatrix(ColorMatrix(
                        0f, 0f, 0f, 0f, Color.getR(vein) / 255f,
                        0f, 0f, 0f, 0f, Color.getG(vein) / 255f,
                        0f, 0f, 0f, 0f, Color.getB(vein) / 255f,
                        sharp, 0f, 0f, 0f, -sharp * 0.18f,
                    ))
                })
                palette.getOrNull(2)?.let { third ->
                    canvas.drawRect(Rect.makeLTRB(-e, -e, e, e), Paint().apply {
                        shader = Shader.makeFractalNoise(freq * 1.4f, freq * 1.4f, 3, layer.seed + 17f)
                        colorFilter = ColorFilter.makeMatrix(ColorMatrix(
                            0f, 0f, 0f, 0f, Color.getR(third) / 255f,
                            0f, 0f, 0f, 0f, Color.getG(third) / 255f,
                            0f, 0f, 0f, 0f, Color.getB(third) / 255f,
                            0f, sharp, 0f, 0f, -sharp * 0.55f,
                        ))
                    })
                }
            }
            PatternKind.SPLATTER -> repeat(((10 + d * 70) * area).toInt()) {
                val size = r * (0.012f + random.nextFloat().pow(3) * 0.14f) * (0.5f + a)
                val x = (random.nextFloat() * 2 - 1) * e
                val y = (random.nextFloat() * 2 - 1) * e
                val paint = fill(color(it))
                canvas.drawCircle(x, y, size, paint)
                // A few droplets thrown off each blob.
                repeat(2 + random.nextInt(5)) {
                    val ang = random.nextFloat() * 2f * PI.toFloat()
                    val dist = size * (1.1f + random.nextFloat() * 1.6f)
                    canvas.drawCircle(x + cos(ang) * dist, y + sin(ang) * dist, size * (0.1f + random.nextFloat() * 0.3f), paint)
                }
                repeat(3) {
                    val ang = random.nextFloat() * 2f * PI.toFloat()
                    canvas.drawCircle(x + cos(ang) * size * 0.6f, y + sin(ang) * size * 0.6f, size * (0.5f + random.nextFloat() * 0.3f), paint)
                }
            }
            // A small tile of noise repeated, rather than noise worked out for every pixel.
            PatternKind.GRAIN -> canvas.drawRect(Rect.makeLTRB(-e, -e, e, e), Paint().apply {
                shader = DiscEffects.pattern(DiscEffects.static, max(0.5f, r / 340f))
                alpha = ((0.15f + a * 0.6f) * 255).roundToInt()
            })
            PatternKind.PAPER -> {
                val box = Rect.makeLTRB(-e, -e, e, e)
                canvas.drawRect(box, fill(c0))
                // The paper's mottling and fibres only ever darken it, deeper with more.
                val depth = 0.1f + a * 0.4f
                canvas.drawRect(box, Paint().apply {
                    shader = DiscEffects.pattern(DiscEffects.paper, r / 300f * (0.5f + d))
                    blendMode = BlendMode.MULTIPLY
                    colorFilter = ColorFilter.makeMatrix(ColorMatrix(
                        depth, 0f, 0f, 0f, 1f - 0.7f * depth,
                        depth, 0f, 0f, 0f, 1f - 0.7f * depth,
                        depth, 0f, 0f, 0f, 1f - 0.7f * depth,
                        0f, 0f, 0f, 1f, 0f,
                    ))
                })
            }
            PatternKind.SCREEN_TONE -> {
                val step = r * (0.09f - d * 0.07f)
                val tile = DiscEffects.screenDot(step, step * (0.1f + a * 0.42f), c0)
                canvas.save()
                canvas.rotate(45f)
                canvas.drawRect(Rect.makeLTRB(-e * 1.5f, -e * 1.5f, e * 1.5f, e * 1.5f), Paint().apply {
                    shader = DiscEffects.pattern(tile, step / tile.width)
                })
                canvas.restore()
            }
            PatternKind.SCANLINES -> {
                val step = r * (0.05f - d * 0.04f)
                val thick = 0.12f + a * 0.6f
                canvas.drawRect(Rect.makeLTRB(-e, -e, e, e), Paint().apply {
                    shader = Shader.makeLinearGradient(
                        0f, 0f, 0f, step, intArrayOf(c0, c0, 0, 0), floatArrayOf(0f, thick, thick, 1f),
                        org.jetbrains.skia.GradientStyle(FilterTileMode.REPEAT, true, null),
                    )
                })
            }
            PatternKind.STREAKS -> repeat(((14 + d * 70) * area).toInt()) {
                val x = (random.nextFloat() * 2 - 1) * e
                val y = (random.nextFloat() * 2 - 1) * e
                val len = r * (0.15f + random.nextFloat().pow(2) * 0.9f) * (0.6f + a)
                val h = r * (0.004f + random.nextFloat().pow(3) * 0.03f) * (0.6f + a)
                val col = color(it)
                val paint = Paint().apply {
                    isAntiAlias = true
                    shader = Shader.makeLinearGradient(x - len / 2f, y, x + len / 2f, y, intArrayOf(0, col, withAlpha(col, 0.85f), 0), floatArrayOf(0f, 0.25f, 0.7f, 1f))
                    maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, h * 0.7f + 0.5f, false)
                }
                canvas.drawRRect(RRect.makeLTRB(x - len / 2f, y - h / 2f, x + len / 2f, y + h / 2f, h / 2f), paint)
            }
            PatternKind.LIGHT_LEAK -> repeat(3 + (d * 4).toInt()) {
                val x = (random.nextFloat() * 2 - 1) * e * 0.9f
                val y = (random.nextFloat() * 2 - 1) * e * 0.9f
                val rad = r * (0.35f + random.nextFloat() * 0.6f) * (0.6f + a)
                val col = color(it)
                canvas.drawCircle(x, y, rad, Paint().apply {
                    isAntiAlias = true
                    shader = Shader.makeRadialGradient(x, y, rad, intArrayOf(withAlpha(col, 0.85f), withAlpha(col, 0.35f), 0), floatArrayOf(0f, 0.45f, 1f))
                })
            }
            PatternKind.HUD -> hud(canvas, random, r, d, a, c0)
        }
    }

    /** Instrument marks: a dial of ticks round the edge, a dashed ring, crosshairs, brackets and readouts. */
    private fun hud(canvas: Canvas, random: Random, r: Float, d: Float, a: Float, color: Int) {
        val lw = r * (0.003f + a * 0.008f)
        val line = stroke(color, lw)
        val n = (36 + d * 84).toInt()
        val outer = r * 0.93f
        for (i in 0 until n) {
            val ang = 2f * PI.toFloat() * i / n
            val long = if (i % 6 == 0) r * 0.06f else r * 0.028f
            canvas.drawLine(cos(ang) * outer, sin(ang) * outer, cos(ang) * (outer - long), sin(ang) * (outer - long), line)
        }
        canvas.drawCircle(0f, 0f, r * 0.72f, stroke(withAlpha(color, 0.6f), lw).apply {
            pathEffect = org.jetbrains.skia.PathEffect.makeDash(floatArrayOf(r * 0.02f, r * 0.016f), 0f)
        })
        val gap = r * 0.5f
        canvas.drawLine(-r * 0.86f, 0f, -gap, 0f, line)
        canvas.drawLine(gap, 0f, r * 0.86f, 0f, line)
        canvas.drawLine(0f, -r * 0.86f, 0f, -gap, line)
        canvas.drawLine(0f, gap, 0f, r * 0.86f, line)
        val b = r * 0.42f
        val l = r * 0.09f
        for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
            canvas.drawPath(Path().apply {
                moveTo(sx * b, sy * (b - l))
                lineTo(sx * b, sy * b)
                lineTo(sx * (b - l), sy * b)
            }, line)
        }
        repeat(6) {
            val x = (random.nextFloat() * 2 - 1) * r * 0.75f
            val y = (random.nextFloat() * 2 - 1) * r * 0.75f
            val s = r * 0.025f
            canvas.drawLine(x - s, y, x + s, y, line)
            canvas.drawLine(x, y - s, x, y + s, line)
        }
        val typeface = systemTypeface(DiscFont.MONO.id) ?: return
        val font = Font(typeface, r * 0.045f)
        val ink = fill(color)
        val readouts = listOf(
            "N ${30 + random.nextInt(60)}.${1000 + random.nextInt(8999)}",
            "E ${10 + random.nextInt(160)}.${1000 + random.nextInt(8999)}",
            "REC ${random.nextInt(24).toString().padStart(2, '0')}:${random.nextInt(60).toString().padStart(2, '0')}",
            "ISO ${listOf(100, 200, 400, 800, 1600)[random.nextInt(5)]}",
        )
        canvas.drawString(readouts[0], -b, -b - r * 0.03f, font, ink)
        canvas.drawString(readouts[1], b - font.measureTextWidth(readouts[1]), -b - r * 0.03f, font, ink)
        canvas.drawString(readouts[2], -b, b + r * 0.07f, font, ink)
        canvas.drawString(readouts[3], b - font.measureTextWidth(readouts[3]), b + r * 0.07f, font, ink)
    }

    /** Calls [draw] at every point of a grid [step] apart over ±[e], every other row shifted for a hex grid. */
    private inline fun grid(e: Float, step: Float, hex: Boolean, draw: (Float, Float, Int) -> Unit) {
        val rowStep = if (hex) step * 0.866f else step
        var y = -e
        var row = 0
        var i = 0
        while (y <= e) {
            var x = -e + if (hex && row % 2 == 1) step / 2f else 0f
            while (x <= e) {
                draw(x, y, i++)
                x += step
            }
            y += rowStep
            row++
        }
    }

    // ---- Picking -----------------------------------------------------------------------------

    /**
     * A placed layer's outline on the disc at ([cx], [cy]) with radius [r]: its four corners, in
     * order. Null for one that covers the disc (fills, patterns, repeated text) or sits on a circle.
     */
    fun corners(layer: DiscLayer, res: Resources, cx: Float, cy: Float, r: Float): List<Pair<Float, Float>>? {
        val (hw, hh, dy) = when (layer.kind) {
            LayerKind.IMAGE -> {
                val half = r * layer.scale
                if (layer.frame == ImageFrame.PHOTO) Triple(half * 1.11f, half * 1.2f, half * 0.1f) else Triple(half, half, 0f)
            }
            LayerKind.STICKER -> Triple(r * layer.scale, r * layer.scale, 0f)
            LayerKind.SHAPE -> {
                val (hw, hh) = shapeHalf(layer, r)
                Triple(hw + r * 0.01f, hh + r * 0.01f, 0f)
            }
            LayerKind.TEXT -> {
                if (!layer.boxed || layer.text.isBlank()) return null
                val size = textSize(layer, r)
                if (layer.path == TextPath.VERTICAL) {
                    val (hw, hh) = verticalHalf(layer, res, size)
                    return box(layer, cx, cy, r, hw + size * 0.08f, hh + size * 0.08f, 0f)
                }
                val lines = layer.text.split('\n')
                val width = lines.maxOf { run(it, layer, res, size).width }
                Triple(width / 2f + size * 0.1f, size * 1.08f * lines.size / 2f + size * 0.05f, 0f)
            }
            else -> return null
        }
        return box(layer, cx, cy, r, hw, hh, dy)
    }

    private fun box(layer: DiscLayer, cx: Float, cy: Float, r: Float, hw: Float, hh: Float, dy: Float): List<Pair<Float, Float>> {
        val a = Math.toRadians(layer.rotation.toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        val ox = cx + layer.x * r
        val oy = cy + layer.y * r
        return listOf(-hw to -hh + dy, hw to -hh + dy, hw to hh + dy, -hw to hh + dy).map { (x, y) -> (ox + x * ca - y * sa) to (oy + x * sa + y * ca) }
    }

    /** Whether ([px], [py]) is on [layer]: inside its outline, or on its circle for text round the disc. */
    fun hit(layer: DiscLayer, res: Resources, cx: Float, cy: Float, r: Float, px: Float, py: Float): Boolean {
        if (!layer.visible || !layer.placed) return false
        if (layer.onCircle) {
            val ox = cx + layer.x * r
            val oy = cy + layer.y * r
            val size = textSize(layer, r)
            val dist = hypot(px - ox, py - oy)
            if (abs(dist - r * layer.radius) > size * 0.8f) return false
            if (layer.path == TextPath.CIRCLE) return true
            val run = run(layer.text, layer, res, size)
            val half = run.width / 2f / max(1f, r * layer.radius) + 0.15f
            val centre = Math.toRadians(layer.rotation.toDouble() + if (layer.path == TextPath.ARC_TOP) -90.0 else 90.0)
            val angle = atan2((py - oy).toDouble(), (px - ox).toDouble())
            var diff = angle - centre
            while (diff > PI) diff -= 2 * PI
            while (diff < -PI) diff += 2 * PI
            return abs(diff) <= half
        }
        val c = corners(layer, res, cx, cy, r) ?: return false
        // Inside a convex quad: on the same side of every edge.
        var sign = 0
        for (i in 0 until 4) {
            val (x1, y1) = c[i]
            val (x2, y2) = c[(i + 1) % 4]
            val cross = (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s != 0) {
                if (sign == 0) sign = s else if (s != sign) return false
            }
        }
        return true
    }

    // ---- Helpers -----------------------------------------------------------------------------

    fun argb(value: Long): Int = (value and 0xFFFFFFFFL).toInt()

    private fun withAlpha(color: Int, alpha: Float): Int = Color.withA(color, (alpha.coerceIn(0f, 1f) * 255).toInt())

    private fun mix(a: Int, b: Int, t: Float): Int = Color.makeARGB(
        255,
        (Color.getR(a) + (Color.getR(b) - Color.getR(a)) * t).toInt(),
        (Color.getG(a) + (Color.getG(b) - Color.getG(a)) * t).toInt(),
        (Color.getB(a) + (Color.getB(b) - Color.getB(a)) * t).toInt(),
    )

    private fun fill(color: Int) = Paint().apply { isAntiAlias = true; this.color = color }

    private fun stroke(color: Int, width: Float) = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.STROKE
        strokeWidth = width
        this.color = color
    }
}
