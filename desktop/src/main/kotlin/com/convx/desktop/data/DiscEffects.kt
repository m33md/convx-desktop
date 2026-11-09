/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.data

import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.ColorChannel
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ColorMatrix
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * A layer's [LayerEffect]s as one Skia image filter on the layer, and the small textures they and
 * the paper-like patterns share. The textures are made once, a few hundred kilobytes in all, and
 * tiled; nothing is kept per design or per frame.
 */
internal object DiscEffects {
    /** The layer's effects in order, or null if it has none. ([ox], [oy]) is the layer's centre on the canvas. */
    fun filter(layer: DiscLayer, r: Float, ox: Float, oy: Float): ImageFilter? {
        var filter: ImageFilter? = null
        for (effect in layer.effects) filter = apply(effect, filter, layer, r, ox, oy)
        return filter
    }

    /** How far past the layer's own pixels its effects can reach, so its layer is made big enough. */
    fun reach(layer: DiscLayer, r: Float): Float = min(r * 0.8f, layer.effects.sumOf { effect ->
        val a = effect.amount.coerceIn(0f, 1f)
        when (effect.kind) {
            EffectKind.BLOOM -> r * (0.04f + 0.08f * a) * 3f
            EffectKind.GLOW -> r * (0.02f + 0.08f * a) * 3f
            EffectKind.BLUR -> r * (0.004f + 0.05f * a) * 3f
            EffectKind.SMEAR -> r * (0.03f + 0.25f * a) * 3.5f
            EffectKind.GLITCH -> r * (0.04f + 0.18f * a)
            EffectKind.INK -> r * 0.06f
            else -> 0f
        }.toDouble()
    }.toFloat())

    private fun apply(effect: LayerEffect, input: ImageFilter?, layer: DiscLayer, r: Float, ox: Float, oy: Float): ImageFilter {
        val a = effect.amount.coerceIn(0f, 1f)
        val color = DiscArt.argb(effect.color)
        return when (effect.kind) {
            EffectKind.BLOOM -> bloom(input, r, a)
            EffectKind.GLOW -> glow(input, r, a, color)
            EffectKind.BLUR -> {
                val s = r * (0.004f + 0.05f * a)
                ImageFilter.makeBlur(s, s, FilterTileMode.DECAL, input, null)
            }
            EffectKind.SMEAR -> smear(input, r, a, layer.rotation, ox, oy)
            EffectKind.GLITCH -> glitch(input, r, a, layer.seed, ox, oy)
            EffectKind.HALFTONE -> halftone(input, r, a, color, ox, oy)
            EffectKind.GRAIN -> grain(input, r, a, ox, oy)
            EffectKind.INK -> ink(input, r, a, layer.rotation, ox, oy)
            EffectKind.SKETCH -> sketch(input, r, a, color)
        }
    }

    private fun matrix(vararg values: Float) = ColorFilter.makeMatrix(ColorMatrix(*values))

    private fun shaderFilter(shader: Shader) = ImageFilter.makeShader(shader, false, null)

    /** A texture's tiles laid from the layer's centre, so it moves with the layer. */
    private fun tiled(image: Image, scaleX: Float, scaleY: Float, ox: Float, oy: Float, degrees: Float = 0f, sampling: SamplingMode = Linear): Shader =
        image.makeShader(
            FilterTileMode.REPEAT,
            FilterTileMode.REPEAT,
            sampling,
            Matrix33.makeTranslate(ox, oy).makeConcat(Matrix33.makeRotate(degrees)).makeConcat(Matrix33.makeScale(scaleX, scaleY)),
        )

    private val Linear = FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)
    private val Nearest = FilterMipmap(FilterMode.NEAREST, MipmapMode.NONE)

    /** The bright parts, blurred twice (tight and wide) and added back as light. */
    private fun bloom(input: ImageFilter?, r: Float, a: Float): ImageFilter {
        // Only bright, solid pixels spill light: dark ones and faint edges drop out (their alpha goes
        // to 0), and the rest give as much as the strength says.
        val k = 0.45f + a * 0.7f
        val bright = ImageFilter.makeColorFilter(matrix(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0.2126f * 2.4f * k, 0.7152f * 2.4f * k, 0.0722f * 2.4f * k, 1.6f * k, -3f * k,
        ), input, null)
        val tight = r * (0.008f + 0.014f * a)
        val wide = r * (0.025f + 0.05f * a)
        val glows = ImageFilter.makeBlend(
            BlendMode.PLUS,
            ImageFilter.makeBlur(wide, wide, FilterTileMode.DECAL, bright, null),
            ImageFilter.makeBlur(tight, tight, FilterTileMode.DECAL, bright, null),
            null,
        )
        return ImageFilter.makeBlend(BlendMode.SCREEN, input, glows, null)
    }

    private fun glow(input: ImageFilter?, r: Float, a: Float, color: Int): ImageFilter {
        val s = r * (0.02f + 0.08f * a)
        val halo = ImageFilter.makeBlend(
            BlendMode.PLUS,
            ImageFilter.makeDropShadowOnly(0f, 0f, s, s, color, input, null),
            ImageFilter.makeDropShadowOnly(0f, 0f, s * 0.35f, s * 0.35f, color, input, null),
            null,
        )
        return ImageFilter.makeBlend(BlendMode.SRC_OVER, halo, input, null)
    }

    /** Blurred along the layer's own direction only, trailing behind it, with the sharp layer on top. */
    private fun smear(input: ImageFilter?, r: Float, a: Float, degrees: Float, ox: Float, oy: Float): ImageFilter {
        val length = r * (0.03f + 0.25f * a)
        val flat = ImageFilter.makeMatrixTransform(Matrix33.makeRotate(-degrees, ox, oy), SamplingMode.LINEAR, input)
        val streaked = ImageFilter.makeBlur(length, r * 0.0015f, FilterTileMode.DECAL, flat, null)
        val trail = ImageFilter.makeOffset(-length * 0.9f, 0f, streaked, null)
        val back = ImageFilter.makeMatrixTransform(Matrix33.makeRotate(degrees, ox, oy), SamplingMode.LINEAR, trail)
        // A blurred trail is faint; lift it so the streaks read.
        val strong = ImageFilter.makeColorFilter(matrix(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1.8f, 0f,
        ), back, null)
        return ImageFilter.makeBlend(BlendMode.SRC_OVER, strong, input, null)
    }

    /** Bands slid sideways, then the reds pulled one way and the blues the other. */
    private fun glitch(input: ImageFilter?, r: Float, a: Float, seed: Int, ox: Float, oy: Float): ImageFilter {
        val bands = shaderFilter(tiled(slices(seed), r * 0.6f, 2f * r / SliceRows, ox - r, oy - r, sampling = Nearest))
        val shifted = ImageFilter.makeDisplacementMap(ColorChannel.R, ColorChannel.G, r * (0.015f + 0.1f * a), bands, input, null)
        val d = r * (0.004f + 0.02f * a)
        val reds = ImageFilter.makeColorFilter(matrix(
            1f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ), ImageFilter.makeOffset(d, 0f, shifted, null), null)
        val blues = ImageFilter.makeColorFilter(matrix(
            0f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ), ImageFilter.makeOffset(-d, 0f, shifted, null), null)
        return ImageFilter.makeBlend(BlendMode.PLUS, reds, blues, null)
    }

    /**
     * Dots on a 45° screen: the layer in grey on white, plus a field that grows from each dot's
     * middle, cut at one level. Dark parts make big dots, light parts small ones, white none.
     */
    private fun halftone(input: ImageFilter?, r: Float, a: Float, ink: Int, ox: Float, oy: Float): ImageFilter {
        val grey = ImageFilter.makeColorFilter(matrix(
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ), input, null)
        val onWhite = ImageFilter.makeBlend(BlendMode.SRC_OVER, shaderFilter(Shader.makeColor(Color.WHITE)), grey, null)
        val step = r * (0.018f + 0.06f * a)
        val field = shaderFilter(tiled(dot, step / DotSize, step / DotSize, ox, oy, 45f))
        val sum = ImageFilter.makeBlend(BlendMode.PLUS, onWhite, field, null)
        val k = 12f
        val dots = ImageFilter.makeColorFilter(matrix(
            0f, 0f, 0f, 0f, Color.getR(ink) / 255f,
            0f, 0f, 0f, 0f, Color.getG(ink) / 255f,
            0f, 0f, 0f, 0f, Color.getB(ink) / 255f,
            -k, 0f, 0f, 0f, k * 0.95f + 0.5f,
        ), sum, null)
        return ImageFilter.makeBlend(BlendMode.DST_IN, dots, input, null)
    }

    /** Light and dark specks laid over the layer's own pixels only. */
    private fun grain(input: ImageFilter?, r: Float, a: Float, ox: Float, oy: Float): ImageFilter {
        val scale = (r / 340f).coerceAtLeast(0.5f)
        val specks = ImageFilter.makeColorFilter(matrix(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 0.25f + a * 0.85f, 0f,
        ), shaderFilter(tiled(specks, scale, scale, ox, oy, sampling = Nearest)), null)
        return ImageFilter.makeBlend(BlendMode.SRC_ATOP, input, specks, null)
    }

    /**
     * A brush's marks: edges roughened by a fine wobble, dry streaks along the layer where the
     * brush ran out, and specks of ink thrown round the strokes.
     */
    private fun ink(input: ImageFilter?, r: Float, a: Float, degrees: Float, ox: Float, oy: Float): ImageFilter {
        val wobble = shaderFilter(tiled(soft, r * 0.0016f, r * 0.0016f, ox, oy))
        val rough = ImageFilter.makeDisplacementMap(ColorChannel.R, ColorChannel.G, r * (0.006f + 0.016f * a), wobble, input, null)
        val cut = 0.7f - 0.26f * a
        val gaps = ImageFilter.makeColorFilter(matrix(
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            9f, 0f, 0f, 0f, -9f * cut,
        ), shaderFilter(tiled(streaks, r * 0.016f, r * 0.0024f, ox, oy, degrees)), null)
        val dry = ImageFilter.makeBlend(BlendMode.DST_OUT, rough, gaps, null)
        val spread = r * (0.012f + 0.02f * a)
        val around = ImageFilter.makeDilate(spread, spread, input, null)
        val scale = (r / 340f).coerceAtLeast(0.5f)
        val dots = ImageFilter.makeColorFilter(matrix(
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 14f, -14f * (0.93f - 0.05f * a),
        ), shaderFilter(tiled(specks, scale * 2.2f, scale * 2.2f, ox, oy, sampling = Linear)), null)
        val spatter = ImageFilter.makeBlend(BlendMode.DST_IN, around, dots, null)
        return ImageFilter.makeBlend(BlendMode.SRC_OVER, spatter, dry, null)
    }

    /** The layer's edges found with a small Laplacian, thickened a little, in one colour. */
    private fun sketch(input: ImageFilter?, r: Float, a: Float, color: Int): ImageFilter {
        val soft = ImageFilter.makeBlur(r * 0.0025f, r * 0.0025f, FilterTileMode.DECAL, input, null)
        // Brightness and coverage together, so dark shapes on nothing still have an edge.
        val level = ImageFilter.makeColorFilter(matrix(
            0.2126f * 0.5f, 0.7152f * 0.5f, 0.0722f * 0.5f, 0.5f, 0f,
            0.2126f * 0.5f, 0.7152f * 0.5f, 0.0722f * 0.5f, 0.5f, 0f,
            0.2126f * 0.5f, 0.7152f * 0.5f, 0.0722f * 0.5f, 0.5f, 0f,
            0f, 0f, 0f, 0f, 1f,
        ), soft, null)
        val edges = ImageFilter.makeMatrixConvolution(
            3, 3, floatArrayOf(-1f, -1f, -1f, -1f, 8f, -1f, -1f, -1f, -1f), 2.2f, 0f, 1, 1,
            FilterTileMode.CLAMP, false, level, null,
        )
        val cut = 0.32f - 0.24f * a
        val lines = ImageFilter.makeColorFilter(matrix(
            0f, 0f, 0f, 0f, Color.getR(color) / 255f,
            0f, 0f, 0f, 0f, Color.getG(color) / 255f,
            0f, 0f, 0f, 0f, Color.getB(color) / 255f,
            5f, 0f, 0f, 0f, -5f * cut,
        ), edges, null)
        val thick = r * 0.0015f * (1f + 2f * a)
        return ImageFilter.makeDilate(thick, thick, lines, null)
    }

    // ---- Textures ----------------------------------------------------------------------------

    private const val Tile = 256
    private const val DotSize = 64
    private const val SliceRows = 128

    private fun image(size: Int, height: Int = size, pixel: (Int, Int) -> Int): Image {
        val bytes = ByteArray(size * height * 4)
        for (y in 0 until height) for (x in 0 until size) {
            val c = pixel(x, y)
            val i = (y * size + x) * 4
            // Premultiplied; the greys these are make the channel order not matter.
            val alpha = c ushr 24
            val grey = ((c and 0xFF) * alpha / 255)
            bytes[i] = grey.toByte()
            bytes[i + 1] = grey.toByte()
            bytes[i + 2] = grey.toByte()
            bytes[i + 3] = alpha.toByte()
        }
        return Image.makeRaster(ImageInfo.makeN32Premul(size, height), bytes, size * 4)
    }

    private fun grey(v: Float, alpha: Float = 1f): Int {
        val g = (v.coerceIn(0f, 1f) * 255).toInt()
        val al = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return (al shl 24) or (g shl 16) or (g shl 8) or g
    }

    /** Smooth noise that tiles: random values on coarse wrapped grids, blended, several sizes summed. */
    private fun valueNoise(seed: Int, cells: IntArray, weights: FloatArray): Array<FloatArray> {
        val random = Random(seed)
        val out = Array(Tile) { FloatArray(Tile) }
        var total = 0f
        for ((o, n) in cells.withIndex()) {
            val grid = Array(n) { FloatArray(n) { random.nextFloat() } }
            val w = weights[o]
            total += w
            for (y in 0 until Tile) {
                val fy = y * n / Tile.toFloat()
                val y0 = floor(fy).toInt()
                val ty = smooth(fy - y0)
                for (x in 0 until Tile) {
                    val fx = x * n / Tile.toFloat()
                    val x0 = floor(fx).toInt()
                    val tx = smooth(fx - x0)
                    val a = grid[y0 % n][x0 % n]
                    val b = grid[y0 % n][(x0 + 1) % n]
                    val c = grid[(y0 + 1) % n][x0 % n]
                    val d = grid[(y0 + 1) % n][(x0 + 1) % n]
                    out[y][x] += w * (a + (b - a) * tx + (c - a) * ty + (a - b - c + d) * tx * ty)
                }
            }
        }
        for (row in out) for (x in row.indices) row[x] /= total
        return out
    }

    private fun smooth(t: Float) = t * t * (3 - 2 * t)

    /** Grain: mostly clear, with black and white specks (stronger the further from grey). */
    val specks: Image by lazy {
        val random = Random(17)
        image(Tile) { _, _ ->
            val v = random.nextFloat()
            val strength = (abs(v - 0.5f) * 2f).pow(2.2f)
            grey(if (v < 0.5f) 0f else 1f, strength)
        }
    }

    /** Plain grey noise, every pixel its own. */
    val static: Image by lazy {
        val random = Random(29)
        image(Tile) { _, _ -> grey(random.nextFloat()) }
    }

    /** Soft blotches, for wobbling edges and paper. */
    val soft: Image by lazy {
        val n = valueNoise(5, intArrayOf(8, 16, 32, 64), floatArrayOf(0.4f, 0.3f, 0.2f, 0.1f))
        image(Tile) { x, y -> grey(n[y][x]) }
    }

    /** Rows of grey that drift slowly along: dry-brush streaks when stretched. */
    val streaks: Image by lazy {
        val n = valueNoise(11, intArrayOf(4, 16, 64), floatArrayOf(0.25f, 0.35f, 0.4f))
        // Each row follows one column of the noise along x, so values run in long lines.
        image(Tile) { x, y -> grey(n[(x / 32 + y * 7) % Tile][y] * 0.7f + n[y][x] * 0.3f) }
    }

    /** Paper: soft mottling, fine tooth and a few fibres, centred on mid grey for overlay. */
    val paper: Image by lazy {
        val n = valueNoise(23, intArrayOf(4, 8, 32, 128), floatArrayOf(0.25f, 0.25f, 0.2f, 0.3f))
        val random = Random(31)
        val base = image(Tile) { x, y -> grey(0.5f + (n[y][x] - 0.5f) * 0.55f + (random.nextFloat() - 0.5f) * 0.12f) }
        val surface = Surface.makeRasterN32Premul(Tile, Tile)
        val canvas = surface.canvas
        canvas.drawImage(base, 0f, 0f)
        repeat(70) {
            val x = random.nextFloat() * Tile
            val y = random.nextFloat() * Tile
            val len = 8f + random.nextFloat() * 26f
            val angle = random.nextFloat() * PI.toFloat()
            val paint = Paint().apply {
                isAntiAlias = true
                mode = PaintMode.STROKE
                strokeWidth = 0.6f + random.nextFloat() * 0.8f
                color = if (random.nextBoolean()) Color.makeARGB(70, 255, 255, 255) else Color.makeARGB(55, 0, 0, 0)
            }
            // Drawn again across each edge so the tile still wraps.
            for (dx in intArrayOf(-Tile, 0, Tile)) for (dy in intArrayOf(-Tile, 0, Tile)) {
                fibre(canvas, x + dx, y + dy, len, angle, paint)
            }
        }
        surface.makeImageSnapshot()
    }

    private fun fibre(canvas: Canvas, x: Float, y: Float, len: Float, angle: Float, paint: Paint) {
        val path = org.jetbrains.skia.Path().apply {
            moveTo(x, y)
            quadTo(x + cos(angle) * len * 0.5f + sin(angle) * 3f, y + sin(angle) * len * 0.5f - cos(angle) * 3f, x + cos(angle) * len, y + sin(angle) * len)
        }
        canvas.drawPath(path, paint)
    }

    /** One halftone cell: 0 at its middle, 1 at its corners. */
    private val dot: Image by lazy {
        val half = DotSize / 2f
        image(DotSize) { x, y -> grey(hypot(x + 0.5f - half, y + 0.5f - half) / (half * 1.4142f)) }
    }

    private val sliceCache = HashMap<Int, Image>()

    /** Glitch bands: rows of red that say how far each band slides; mostly still, a few far. */
    private fun slices(seed: Int): Image = synchronized(sliceCache) {
        sliceCache.getOrPut(seed.mod(64)) {
            val random = Random(seed.mod(64) * 31 + 7)
            val values = FloatArray(SliceRows)
            var y = 0
            while (y < SliceRows) {
                val h = 1 + random.nextInt(9)
                val v = if (random.nextFloat() < 0.25f) random.nextFloat() else 0.5f
                for (i in y until min(SliceRows, y + h)) values[i] = v
                y += h
            }
            val bytes = ByteArray(4 * SliceRows * 4)
            for (row in 0 until SliceRows) for (x in 0 until 4) {
                val i = (row * 4 + x) * 4
                val red = (values[row] * 255).toInt().toByte()
                // N32 is BGRA here: the slide in red, no vertical move in green.
                bytes[i] = 128.toByte()
                bytes[i + 1] = 128.toByte()
                bytes[i + 2] = red
                bytes[i + 3] = 255.toByte()
            }
            Image.makeRaster(ImageInfo.makeN32Premul(4, SliceRows), bytes, 16)
        }
    }

    /** A tile of the screen-tone dot pattern [step] pixels across, made to that size so dots stay sharp. */
    fun screenDot(step: Float, radius: Float, color: Int): Image {
        val size = step.toInt().coerceIn(3, 160)
        val key = Triple(size, (radius / step * 200).toInt(), color)
        return synchronized(dotCache) {
            dotCache[key] ?: run {
                val surface = Surface.makeRasterN32Premul(size, size)
                surface.canvas.clear(Color.TRANSPARENT)
                val k = size / step
                surface.canvas.drawCircle(size / 2f, size / 2f, radius * k, Paint().apply { isAntiAlias = true; this.color = color })
                if (dotCache.size > 24) dotCache.clear()
                surface.makeImageSnapshot().also { dotCache[key] = it }
            }
        }
    }

    private val dotCache = HashMap<Triple<Int, Int, Int>, Image>()

    /** A texture as a shader over a pattern layer, [scale] canvas units a texel. */
    fun pattern(image: Image, scale: Float, sampling: SamplingMode = Linear): Shader =
        image.makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, sampling, Matrix33.makeScale(scale, scale))

    fun Rect.grow(by: Float): Rect = Rect.makeLTRB(left - by, top - by, right + by, bottom + by)
}
