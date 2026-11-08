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
import org.jetbrains.skia.Path
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import org.jetbrains.skia.TextLine
import org.jetbrains.skia.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Playlist covers Convx draws itself: a disc in a clear CD case, its print made from the playlist's
 * own songs. The covers' colours set the palette, their pictures go on the disc, and the playlist's
 * name and its songs' names pick the kind of print (a chiptune playlist gets pixels, a rainy-night
 * one gets a prism, a rock one a duotone). Only Skia, so it draws off the UI thread.
 */
object CoverArt {
    /** Raised whenever the drawing changes, so covers an older Convx drew are drawn again. */
    const val Version = 2

    enum class Style { Collage, Type, Stars, Prism, Mono, Duotone, Pixel, Glow }

    /**
     * What a cover is made from. [covers] are the songs' pictures, the most typical first; [words]
     * are the songs' titles and artists; [seed] varies the drawing; [design] counts "New cover
     * design" presses, which move on to the next fitting print.
     */
    class Source(val name: String, val covers: List<Image>, val words: List<String>, val seed: Int, val design: Int = 0, val style: Style? = null)

    /** [display] sets the playlist's name on Type prints; null falls back to Windows' bold sans. */
    class Fonts(val display: Typeface?)

    fun render(source: Source, fonts: Fonts, size: Int = 640): Image {
        val surface = Surface.makeRasterN32Premul(size, size)
        val canvas = surface.canvas
        val random = Random(source.seed * 31 + source.name.hashCode())
        val palette = palette(source.covers, source.name)
        val style = source.style ?: pickStyle(source, palette, random)
        Case(size.toFloat()).draw(canvas) { cx, cy, r ->
            when (style) {
                Style.Collage -> collage(canvas, cx, cy, r, source, palette, random)
                Style.Type -> type(canvas, cx, cy, r, source, palette, fonts, random)
                Style.Stars -> stars(canvas, cx, cy, r, palette, random)
                Style.Prism -> prism(canvas, cx, cy, r, palette, random)
                Style.Mono -> mono(canvas, cx, cy, r, source, palette, random)
                Style.Duotone -> duotone(canvas, cx, cy, r, source, palette, random)
                Style.Pixel -> pixel(canvas, cx, cy, r, source, palette, random)
                Style.Glow -> glow(canvas, cx, cy, r, source, palette, random)
            }
        }
        return surface.makeImageSnapshot()
    }

    fun encode(image: Image): ByteArray =
        image.encodeToData(EncodedImageFormat.JPEG, 92)?.bytes ?: error("Couldn't encode the cover")

    // ---- Which print -------------------------------------------------------------------------

    private val moods: Map<Style, List<String>> = mapOf(
        Style.Pixel to listOf("chiptune", "chip tune", "8-bit", "8bit", "8 bit", "16-bit", "pixel", "retro", "arcade", "game", "gaming", "nintendo", "video game", "vgm", "bit"),
        Style.Stars to listOf("kawaii", "cute", "anime", "japan", "japanese", "jpop", "j-pop", "kpop", "k-pop", "star", "sparkle", "dream", "dreamy", "magic", "glitter", "princess", "baby", "candy", "pastel", "pop"),
        Style.Prism to listOf("night", "nights", "midnight", "rain", "raining", "rainy", "chill", "lofi", "lo-fi", "ambient", "sleep", "echo", "echoes", "space", "drive", "driving", "swing", "feel", "vibes", "vibe", "dark", "neon", "synth", "wave", "messages", "lonely"),
        Style.Mono to listOf("piano", "classical", "acoustic", "jazz", "calm", "study", "focus", "soft", "quiet", "strings", "instrumental", "radio", "sad", "alone", "here", "nostalgia", "old", "memories", "indie", "folk"),
        Style.Duotone to listOf("rock", "metal", "punk", "grunge", "strokes", "band", "guitar", "hip hop", "hiphop", "rap", "trap", "drill", "hard", "rage", "riot", "emo", "alt", "alternative"),
        Style.Type to listOf("dance", "party", "gym", "workout", "hype", "afro", "afrobeats", "reggaeton", "latin", "club", "house", "techno", "edm", "hits", "top", "mix", "summer", "banger", "bangers", "rap", "remix", "funk", "phonk"),
        Style.Collage to listOf("radio", "mix", "favorites", "favourites", "faves", "liked", "best", "collection", "random", "idk", "everything", "misc"),
        Style.Glow to listOf("love", "heart", "feel", "mood", "slow", "sunset", "warm", "soul", "r&b", "rnb", "blue"),
    )

    fun pickStyle(source: Source, palette: Palette, random: Random): Style {
        val name = source.name.lowercase()
        val words = source.words.joinToString(" ").lowercase()
        val letters = source.name.count { it.isLetter() }
        val score = Style.entries.associateWith { 1.0 }.toMutableMap()
        for ((style, keys) in moods) {
            for (key in keys) {
                if (Regex("""(^|[^\p{L}])${Regex.escape(key)}([^\p{L}]|$)""").containsMatchIn(name)) score[style] = score[style]!! + 6.0
                else if (key.length > 3 && words.contains(key)) score[style] = score[style]!! + 0.6
            }
        }
        // Songs in Japanese, Chinese or Korean lean towards the starry print.
        if ((source.name + words).any { Character.UnicodeScript.of(it.code) in Cjk }) score[Style.Stars] = score[Style.Stars]!! + 2.5
        // The print each needs: pictures for most, a name with letters for Type.
        val pictures = source.covers.size
        if (pictures == 0) for (s in listOf(Style.Collage, Style.Mono, Style.Duotone, Style.Pixel, Style.Glow)) score[s] = 0.0
        if (pictures < 4) score[Style.Collage] = score[Style.Collage]!! * 0.3
        if (pictures >= 6) score[Style.Collage] = score[Style.Collage]!! + 1.0
        if (letters < 2 || source.name.length > 28) score[Style.Type] = 0.0
        else if (source.name.trim().split(Regex("\\s+")).size <= 2 && letters <= 12) score[Style.Type] = score[Style.Type]!! + 1.5
        if (palette.saturation < 0.18f) score[Style.Mono] = score[Style.Mono]!! + 1.5
        if (palette.brightness < 0.3f) score[Style.Prism] = score[Style.Prism]!! + 0.8
        if (palette.saturation > 0.45f) score[Style.Glow] = score[Style.Glow]!! + 0.5
        // A name that says what it is (Chiptune, Rainy nights) gets that print first; "New cover
        // design" then goes through the other two likeliest. Without such a word, a weighted pick.
        val ranked = score.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(3)
        if (ranked.isNotEmpty() && ranked.first().value >= 6.0) return ranked[source.design % ranked.size].key
        val total = ranked.sumOf { it.value }
        var pick = random.nextDouble() * total
        for (entry in ranked) {
            pick -= entry.value
            if (pick <= 0) return entry.key
        }
        return ranked.firstOrNull()?.key ?: Style.Stars
    }

    private val Cjk = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)

    // ---- Palette -----------------------------------------------------------------------------

    /** The colours a cover is drawn in: the songs' most vivid hue, a deep and a pale one, and a second hue. */
    class Palette(val vivid: Int, val accent: Int, val dark: Int, val light: Int, val brightness: Float, val saturation: Float)

    fun palette(covers: List<Image>, name: String): Palette {
        val pixels = ArrayList<FloatArray>()
        for (image in covers.take(8)) {
            val n = 12
            val surface = Surface.makeRasterN32Premul(n, n)
            surface.canvas.drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeWH(n.toFloat(), n.toFloat()), SamplingMode.LINEAR, null, true)
            val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(surface.makeImageSnapshot())
            for (y in 0 until n) for (x in 0 until n) pixels += hsv(bitmap.getColor(x, y))
        }
        if (pixels.isEmpty()) {
            val hue = ((name.hashCode() and 0x7fffffff) % 360).toFloat()
            return Palette(fromHsv(hue, 0.72f, 0.92f), fromHsv((hue + 40) % 360, 0.6f, 0.9f), fromHsv(hue, 0.6f, 0.12f), fromHsv(hue, 0.08f, 0.96f), 0.5f, 0.6f)
        }
        val brightness = pixels.map { it[2] }.average().toFloat()
        val saturation = pixels.map { it[1] * it[2] }.average().toFloat()
        // Hue buckets weighted by how vivid each pixel is.
        val buckets = DoubleArray(12)
        for (p in pixels) buckets[((p[0] / 30f).toInt()) % 12] += (p[1] * p[2] * p[1]).toDouble()
        val order = buckets.indices.sortedByDescending { buckets[it] }
        fun hueOf(bucket: Int): Float {
            val inBucket = pixels.filter { ((it[0] / 30f).toInt()) % 12 == bucket && it[1] > 0.2f && it[2] > 0.2f }
            if (inBucket.isEmpty()) return bucket * 30f + 15f
            // Circular mean, so reds either side of 0 don't average to cyan.
            val sx = inBucket.sumOf { cos(it[0] * PI / 180) * it[1] }
            val sy = inBucket.sumOf { sin(it[0] * PI / 180) * it[1] }
            return ((Math.toDegrees(kotlin.math.atan2(sy, sx)) + 360) % 360).toFloat()
        }
        val greyish = buckets[order[0]] < pixels.size * 0.02
        val hue = if (greyish) ((name.hashCode() and 0x7fffffff) % 360).toFloat() else hueOf(order[0])
        val second = order.drop(1).firstOrNull { abs(it - order[0]) % 12 in 2..10 && buckets[it] > buckets[order[0]] * 0.25 }
        val hue2 = second?.let { hueOf(it) } ?: ((hue + 35) % 360)
        val vividSat = if (greyish) 0.25f else 0.78f
        return Palette(
            vivid = fromHsv(hue, vividSat, 0.95f),
            accent = fromHsv(hue2, if (greyish) 0.2f else 0.65f, 0.92f),
            dark = fromHsv(hue, if (greyish) 0.1f else 0.65f, 0.11f),
            light = fromHsv(hue, if (greyish) 0.02f else 0.1f, 0.96f),
            brightness = brightness,
            saturation = saturation,
        )
    }

    private fun hsv(color: Int): FloatArray {
        val r = Color.getR(color) / 255f
        val g = Color.getG(color) / 255f
        val b = Color.getB(color) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }.let { if (it < 0) it + 360f else it }
        return floatArrayOf(h, if (mx == 0f) 0f else d / mx, mx)
    }

    fun fromHsv(h: Float, s: Float, v: Float, alpha: Int = 255): Int {
        val c = v * s
        val x = c * (1 - abs((h / 60f) % 2 - 1))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt() % 6) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color.makeARGB(alpha, ((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.withA(color, (alpha.coerceIn(0f, 1f) * 255).toInt())

    private fun mix(a: Int, b: Int, t: Float): Int = Color.makeARGB(
        255,
        (Color.getR(a) + (Color.getR(b) - Color.getR(a)) * t).toInt(),
        (Color.getG(a) + (Color.getG(b) - Color.getG(a)) * t).toInt(),
        (Color.getB(a) + (Color.getB(b) - Color.getB(a)) * t).toInt(),
    )

    private fun luminance(color: Int): Float = (0.2126f * Color.getR(color) + 0.7152f * Color.getG(color) + 0.0722f * Color.getB(color)) / 255f

    // ---- The case ----------------------------------------------------------------------------

    /** A clear jewel case seen from the front, with its hinge on the left and the disc in its tray. */
    internal class Case(val s: Float) {
        val left = s * 0.035f
        val top = s * 0.035f
        val right = s * 0.965f
        val bottom = s * 0.965f
        val hinge = left + s * 0.072f
        val trayLeft = hinge + s * 0.012f
        val trayTop = top + s * 0.022f
        val trayRight = right - s * 0.022f
        val trayBottom = bottom - s * 0.022f
        val cx = (trayLeft + trayRight) / 2f
        val cy = (trayTop + trayBottom) / 2f
        val r = min(trayRight - trayLeft, trayBottom - trayTop) / 2f - s * 0.006f

        fun line(alpha: Float, width: Float = s * 0.0028f) = Paint().apply {
            isAntiAlias = true
            mode = PaintMode.STROKE
            strokeWidth = width
            color = withAlpha(Color.WHITE, alpha)
        }

        fun fill(color: Int) = Paint().apply { isAntiAlias = true; this.color = color }

        fun draw(canvas: Canvas, print: (cx: Float, cy: Float, r: Float) -> Unit) = frame(canvas) {
            // The disc, its shadow first.
            canvas.drawCircle(cx, cy + s * 0.008f, r, Paint().apply {
                isAntiAlias = true
                color = withAlpha(Color.BLACK, 0.7f)
                maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, s * 0.014f, true)
            })
            canvas.save()
            canvas.clipPath(Path().addCircle(cx, cy, r), ClipMode.INTERSECT, true)
            canvas.drawCircle(cx, cy, r, fill(Color.makeRGB(200, 200, 205)))
            print(cx, cy, r)
            sheen(canvas)
            canvas.restore()
            hub(canvas)
            canvas.drawCircle(cx, cy, r, line(0.45f, s * 0.003f))
            canvas.drawCircle(cx, cy, r * 0.988f, Paint().apply {
                isAntiAlias = true
                mode = PaintMode.STROKE
                strokeWidth = s * 0.004f
                color = withAlpha(Color.BLACK, 0.25f)
            })
        }

        /** The case and its tray, with [disc] drawing the disc in the tray (at [cx], [cy], radius [r]) before the hinge and the front go on. */
        fun frame(canvas: Canvas, disc: () -> Unit) {
            canvas.clear(Color.makeRGB(7, 7, 8))
            val outer = RRect.makeLTRB(left, top, right, bottom, s * 0.016f)
            // The plastic: a faint fill and a soft shadow under the case.
            canvas.drawRRect(outer, Paint().apply {
                isAntiAlias = true
                color = withAlpha(Color.BLACK, 0.6f)
                maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, s * 0.012f, true)
            })
            canvas.drawRRect(outer, fill(Color.makeRGB(22, 22, 25)))
            canvas.drawRRect(outer, Paint().apply {
                isAntiAlias = true
                shader = Shader.makeLinearGradient(left, top, right, bottom,
                    intArrayOf(withAlpha(Color.WHITE, 0.07f), withAlpha(Color.WHITE, 0.02f), withAlpha(Color.WHITE, 0.05f)), floatArrayOf(0f, 0.55f, 1f))
            })

            // The tray the disc sits in, with its corner clips and the thumb notch on the left.
            val tray = RRect.makeLTRB(trayLeft, trayTop, trayRight, trayBottom, s * 0.01f)
            canvas.drawRRect(tray, fill(Color.makeRGB(13, 13, 15)))
            canvas.drawRRect(tray, line(0.24f))
            val clip = s * 0.045f
            val clipPaint = line(0.42f, s * 0.004f).apply { strokeCap = PaintStrokeCap.ROUND }
            for ((x, y, dx, dy) in listOf(
                Quad(trayLeft, trayTop, 1f, 1f), Quad(trayRight, trayTop, -1f, 1f),
                Quad(trayLeft, trayBottom, 1f, -1f), Quad(trayRight, trayBottom, -1f, -1f),
            )) {
                val inset = s * 0.012f
                val px = x + dx * inset
                val py = y + dy * inset
                canvas.drawLine(px, py, px + dx * clip, py, clipPaint)
                canvas.drawLine(px, py, px, py + dy * clip, clipPaint)
            }
            canvas.drawArc(trayLeft - s * 0.045f, cy - s * 0.06f, trayLeft + s * 0.045f, cy + s * 0.06f, -90f, 180f, false, line(0.3f))

            disc()

            // The hinge down the left side, with its two knuckles.
            canvas.drawRect(Rect.makeLTRB(left, top, hinge, bottom), fill(withAlpha(Color.WHITE, 0.06f)))
            canvas.drawLine(hinge, top + s * 0.01f, hinge, bottom - s * 0.01f, line(0.32f))
            canvas.drawLine(hinge - s * 0.012f, top + s * 0.02f, hinge - s * 0.012f, bottom - s * 0.02f, line(0.1f))
            for (y in listOf(top + s * 0.06f, bottom - s * 0.14f)) {
                val knuckle = RRect.makeXYWH(left + s * 0.012f, y, hinge - left - s * 0.026f, s * 0.08f, s * 0.008f)
                canvas.drawRRect(knuckle, fill(withAlpha(Color.WHITE, 0.06f)))
                canvas.drawRRect(knuckle, line(0.28f))
            }

            // The case's edges and the light on its front.
            canvas.drawRRect(outer, line(0.7f, s * 0.0035f))
            canvas.drawRRect(RRect.makeLTRB(left + s * 0.008f, top + s * 0.008f, right - s * 0.008f, bottom - s * 0.008f, s * 0.011f), line(0.14f))
            canvas.save()
            canvas.clipRRect(outer, true)
            canvas.drawPaint(Paint().apply {
                shader = Shader.makeLinearGradient(left, top, right * 0.8f, bottom,
                    intArrayOf(withAlpha(Color.WHITE, 0.13f), withAlpha(Color.WHITE, 0f), withAlpha(Color.WHITE, 0f), withAlpha(Color.WHITE, 0.06f), withAlpha(Color.WHITE, 0f)),
                    floatArrayOf(0f, 0.32f, 0.62f, 0.7f, 0.82f))
            })
            canvas.drawLine(left + s * 0.03f, top + s * 0.004f, right - s * 0.03f, top + s * 0.004f, line(0.35f, s * 0.002f))
            canvas.restore()
        }

        /** A CD's rainbow and the light across it. */
        fun sheen(canvas: Canvas) {
            canvas.drawCircle(cx, cy, r, Paint().apply {
                isAntiAlias = true
                blendMode = BlendMode.SCREEN
                shader = Shader.makeSweepGradient(cx, cy, intArrayOf(
                    0, withAlpha(0xFF66E0FF.toInt(), 0.10f), 0, withAlpha(0xFFFF66CC.toInt(), 0.09f), 0, withAlpha(0xFFFFE066.toInt(), 0.08f), 0,
                ), floatArrayOf(0f, 0.12f, 0.25f, 0.45f, 0.6f, 0.78f, 1f))
            })
            canvas.drawCircle(cx, cy, r, Paint().apply {
                isAntiAlias = true
                shader = Shader.makeLinearGradient(cx - r, cy - r, cx + r, cy + r,
                    intArrayOf(withAlpha(Color.WHITE, 0.16f), withAlpha(Color.WHITE, 0f), withAlpha(Color.BLACK, 0.12f)), floatArrayOf(0f, 0.5f, 1f))
            })
        }

        /** The clear middle of the disc and the case's hub through it. */
        fun hub(canvas: Canvas) {
            clearRing(canvas)
            spindle(canvas)
        }

        /** Where the print stops: clear plastic, frosted, with the tray showing through. */
        fun clearRing(canvas: Canvas) {
            val clear = r * 0.36f
            canvas.drawCircle(cx, cy, clear, fill(Color.makeRGB(26, 26, 29)))
            canvas.drawCircle(cx, cy, clear, Paint().apply {
                isAntiAlias = true
                shader = Shader.makeRadialGradient(cx, cy, clear,
                    intArrayOf(withAlpha(Color.WHITE, 0.08f), withAlpha(Color.WHITE, 0.2f), withAlpha(Color.WHITE, 0.07f)), floatArrayOf(0.55f, 0.88f, 1f))
            })
            canvas.drawCircle(cx, cy, clear, Paint().apply {
                isAntiAlias = true
                shader = Shader.makeSweepGradient(cx, cy,
                    intArrayOf(0, withAlpha(Color.WHITE, 0.16f), 0, withAlpha(Color.WHITE, 0.1f), 0), floatArrayOf(0f, 0.15f, 0.4f, 0.65f, 1f))
            })
            canvas.drawCircle(cx, cy, clear, line(0.55f, s * 0.003f))
            canvas.drawCircle(cx, cy, r * 0.315f, line(0.14f, s * 0.006f))
            canvas.drawCircle(cx, cy, r * 0.255f, line(0.34f, s * 0.004f))
        }

        /** The hole in the disc and the case's hub through it: a ring of teeth and the button in the middle. */
        fun spindle(canvas: Canvas) {
            canvas.drawCircle(cx, cy, r * 0.2f, fill(Color.makeRGB(14, 14, 16)))
            canvas.drawCircle(cx, cy, r * 0.2f, line(0.4f))
            canvas.drawCircle(cx, cy, r * 0.185f, fill(withAlpha(Color.WHITE, 0.13f)))
            val gap = Paint().apply {
                isAntiAlias = true
                mode = PaintMode.STROKE
                strokeWidth = s * 0.004f
                color = withAlpha(Color.BLACK, 0.7f)
            }
            val teeth = 12
            for (i in 0 until teeth) {
                val a = (i * 2 * PI / teeth).toFloat()
                canvas.drawLine(cx + cos(a) * r * 0.1f, cy + sin(a) * r * 0.1f, cx + cos(a) * r * 0.185f, cy + sin(a) * r * 0.185f, gap)
            }
            canvas.drawCircle(cx, cy, r * 0.185f, line(0.4f))
            canvas.drawCircle(cx, cy, r * 0.105f, fill(Color.makeRGB(62, 62, 66)))
            canvas.drawCircle(cx, cy, r * 0.105f, Paint().apply {
                isAntiAlias = true
                shader = Shader.makeLinearGradient(cx - r * 0.1f, cy - r * 0.1f, cx + r * 0.1f, cy + r * 0.1f,
                    intArrayOf(withAlpha(Color.WHITE, 0.3f), 0, withAlpha(Color.BLACK, 0.3f)), floatArrayOf(0f, 0.5f, 1f))
            })
            canvas.drawCircle(cx, cy, r * 0.105f, line(0.5f))
            canvas.drawCircle(cx, cy, r * 0.045f, fill(Color.makeRGB(20, 20, 22)))
        }
    }

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

    // ---- Prints ------------------------------------------------------------------------------

    private fun square(cx: Float, cy: Float, r: Float) = Rect.makeLTRB(cx - r, cy - r, cx + r, cy + r)

    /** Draws [image] to cover [dst], cropping the middle. */
    private fun cover(canvas: Canvas, image: Image, dst: Rect, paint: Paint? = null, sampling: SamplingMode = SamplingMode.MITCHELL) {
        val side = min(image.width, image.height).toFloat()
        val src = Rect.makeXYWH((image.width - side) / 2f, (image.height - side) / 2f, side, side)
        canvas.drawImageRect(image, src, dst, sampling, paint, true)
    }

    private fun grain(canvas: Canvas, cx: Float, cy: Float, r: Float, strength: Float) {
        canvas.drawRect(square(cx, cy, r), Paint().apply {
            shader = Shader.makeFractalNoise(0.9f, 0.9f, 2, 7f)
            colorFilter = ColorFilter.makeMatrix(ColorMatrix(
                0f, 0f, 0f, 0f, 1f,
                0f, 0f, 0f, 0f, 1f,
                0f, 0f, 0f, 0f, 1f,
                strength, 0f, 0f, 0f, 0f,
            ))
            blendMode = BlendMode.OVERLAY
        })
    }

    private fun collage(canvas: Canvas, cx: Float, cy: Float, r: Float, source: Source, p: Palette, random: Random) {
        canvas.drawRect(square(cx, cy, r), Paint().apply { color = mix(p.light, Color.WHITE, 0.4f) })
        // Small tags and dots in the playlist's colours, like stickers on a disc.
        repeat(14) {
            val a = random.nextFloat() * 2 * PI.toFloat()
            val d = r * (0.42f + random.nextFloat() * 0.55f)
            val x = cx + cos(a) * d
            val y = cy + sin(a) * d
            val c = listOf(p.vivid, p.accent, p.dark)[random.nextInt(3)]
            canvas.save()
            canvas.rotate(random.nextFloat() * 60f - 30f, x, y)
            if (random.nextBoolean()) {
                canvas.drawRRect(RRect.makeXYWH(x, y, r * (0.12f + random.nextFloat() * 0.12f), r * 0.06f, r * 0.03f), Paint().apply { isAntiAlias = true; color = c })
            } else {
                canvas.drawCircle(x, y, r * (0.025f + random.nextFloat() * 0.03f), Paint().apply { isAntiAlias = true; color = c })
            }
            canvas.restore()
        }
        val pics = source.covers.take(9)
        val side = r * if (pics.size <= 4) 0.52f else 0.42f
        val slots = (0 until max(pics.size, 1)).map { i ->
            val a = (i * 2 * PI / pics.size + random.nextDouble() * 0.5 - PI / 2).toFloat()
            val d = r * (0.6f + random.nextFloat() * 0.12f)
            Pair(cx + cos(a) * d, cy + sin(a) * d)
        }
        pics.forEachIndexed { i, image ->
            val (x, y) = slots[i]
            canvas.save()
            canvas.rotate(random.nextFloat() * 36f - 18f, x, y)
            val rect = Rect.makeXYWH(x - side / 2, y - side / 2, side, side)
            canvas.drawRect(rect.offset(r * 0.012f, r * 0.02f), Paint().apply {
                color = withAlpha(Color.BLACK, 0.35f)
                maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, r * 0.02f, true)
            })
            val border = side * 0.06f
            canvas.drawRect(Rect.makeLTRB(rect.left - border, rect.top - border, rect.right + border, rect.bottom + border), Paint().apply { color = Color.WHITE })
            cover(canvas, image, rect)
            canvas.restore()
        }
        grain(canvas, cx, cy, r, 0.12f)
    }

    private fun typefaceFor(text: String, fonts: Fonts): Typeface {
        val display = fonts.display
        if (display != null) {
            val font = Font(display, 20f)
            if (font.getStringGlyphs(text).none { it.toInt() == 0 }) return display
        }
        val needs = text.codePoints().toArray().firstOrNull { it > 0x2FF } ?: 'A'.code
        return FontMgr.default.matchFamilyStyleCharacter(null, FontStyle.BOLD, null, needs)
            ?: FontMgr.default.matchFamilyStyle(null, FontStyle.BOLD)
            ?: Typeface.makeEmpty()
    }

    private fun type(canvas: Canvas, cx: Float, cy: Float, r: Float, source: Source, p: Palette, fonts: Fonts, random: Random) {
        val base = if (random.nextBoolean()) p.vivid else p.dark
        val ink = if (luminance(base) > 0.55f) p.dark else if (base == p.dark) p.vivid else Color.makeRGB(20, 10, 14)
        canvas.drawRect(square(cx, cy, r), Paint().apply { color = base })
        val text = source.name.trim().uppercase()
        val typeface = typefaceFor(text, fonts)
        val size = r * 0.3f
        val font = Font(typeface, size)
        val line = TextLine.make("$text  ", font)
        val step = size * 0.92f
        canvas.save()
        canvas.rotate(random.nextFloat() * 16f - 12f, cx, cy)
        var y = cy - r * 1.3f
        var row = 0
        while (y < cy + r * 1.4f) {
            val outlined = row % 2 == 1
            val paint = Paint().apply {
                isAntiAlias = true
                color = ink
                if (outlined) {
                    mode = PaintMode.STROKE
                    strokeWidth = size * 0.035f
                }
            }
            var x = cx - r * 1.4f - random.nextFloat() * line.width
            while (x < cx + r * 1.4f) {
                canvas.drawTextLine(line, x, y, paint)
                x += line.width
            }
            y += step
            row++
        }
        // The name once, large and light, across the middle.
        val big = Font(typeface, size * 1.25f)
        val hero = TextLine.make(text, big)
        val scale = min(1f, r * 1.55f / max(1f, hero.width))
        canvas.save()
        canvas.translate(cx, cy + r * 0.58f)
        canvas.scale(scale, scale)
        canvas.drawTextLine(hero, -hero.width / 2f, hero.capHeight / 2f, Paint().apply {
            isAntiAlias = true
            color = withAlpha(Color.BLACK, 0.35f)
            maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, size * 0.06f, true)
        })
        canvas.drawTextLine(hero, -hero.width / 2f, hero.capHeight / 2f, Paint().apply { isAntiAlias = true; color = if (base == p.dark) p.light else Color.WHITE })
        canvas.restore()
        canvas.restore()
        grain(canvas, cx, cy, r, 0.1f)
    }

    private fun starPath(x: Float, y: Float, outer: Float, inner: Float, points: Int, turn: Float): Path {
        val path = Path()
        for (i in 0 until points * 2) {
            val rad = if (i % 2 == 0) outer else inner
            val a = turn + (i * PI / points).toFloat() - (PI / 2).toFloat()
            val px = x + cos(a) * rad
            val py = y + sin(a) * rad
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        return path.closePath()
    }

    private fun stars(canvas: Canvas, cx: Float, cy: Float, r: Float, p: Palette, random: Random) {
        val deep = mix(p.dark, p.vivid, 0.25f)
        canvas.drawRect(square(cx, cy, r), Paint().apply {
            shader = Shader.makeLinearGradient(cx - r, cy - r, cx + r, cy + r, intArrayOf(mix(p.vivid, Color.BLACK, 0.1f), mix(p.accent, p.vivid, 0.3f), deep), floatArrayOf(0f, 0.55f, 1f))
        })
        // Soft light behind the stars.
        repeat(6) {
            canvas.drawCircle(cx + (random.nextFloat() - 0.5f) * 2 * r, cy + (random.nextFloat() - 0.5f) * 2 * r, r * (0.2f + random.nextFloat() * 0.3f), Paint().apply {
                isAntiAlias = true
                color = withAlpha(if (random.nextBoolean()) p.light else p.vivid, 0.35f)
                maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, r * 0.12f, true)
            })
        }
        val starColor = mix(p.light, Color.WHITE, 0.5f)
        repeat(34) {
            val size = r * (0.04f + random.nextFloat() * random.nextFloat() * 0.16f)
            val x = cx + (random.nextFloat() - 0.5f) * 2.1f * r
            val y = cy + (random.nextFloat() - 0.5f) * 2.1f * r
            val blurred = random.nextFloat() < 0.3f
            val sparkle = random.nextFloat() < 0.3f
            val path = if (sparkle) starPath(x, y, size, size * 0.18f, 4, 0f) else starPath(x, y, size, size * 0.45f, 5, random.nextFloat())
            canvas.drawPath(path, Paint().apply {
                isAntiAlias = true
                color = withAlpha(Color.WHITE, 0.5f)
                maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, size * 0.35f, true)
            })
            canvas.drawPath(path, Paint().apply {
                isAntiAlias = true
                color = withAlpha(starColor, if (blurred) 0.6f else 0.95f)
                if (blurred) maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, size * 0.15f, true)
            })
        }
        grain(canvas, cx, cy, r, 0.08f)
    }

    private fun prism(canvas: Canvas, cx: Float, cy: Float, r: Float, p: Palette, random: Random) {
        canvas.drawRect(square(cx, cy, r), Paint().apply { color = mix(p.dark, Color.BLACK, 0.4f) })
        val rainbow = intArrayOf(p.vivid, 0xFFFFD45C.toInt(), 0xFF5CFFB0.toInt(), 0xFF5CB8FF.toInt(), p.accent, 0xFFFF5CA8.toInt(), p.vivid)
        val bands = 3 + random.nextInt(3)
        repeat(bands) { i ->
            val path = Path()
            val y0 = cy - r + (i + 0.5f) * 2 * r / bands + (random.nextFloat() - 0.5f) * r * 0.3f
            path.moveTo(cx - r * 1.2f, y0)
            path.cubicTo(
                cx - r * 0.4f, y0 + (random.nextFloat() - 0.5f) * r * 1.4f,
                cx + r * 0.4f, y0 + (random.nextFloat() - 0.5f) * r * 1.4f,
                cx + r * 1.2f, y0 + (random.nextFloat() - 0.5f) * r * 0.6f,
            )
            val width = r * (0.12f + random.nextFloat() * 0.22f)
            val shader = Shader.makeLinearGradient(cx - r, cy - r * 0.3f, cx + r, cy + r * 0.3f, rainbow, null)
            canvas.drawPath(path, Paint().apply {
                isAntiAlias = true
                mode = PaintMode.STROKE
                strokeWidth = width * 1.8f
                strokeCap = PaintStrokeCap.ROUND
                this.shader = shader
                alpha = 110
                blendMode = BlendMode.SCREEN
                maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, width * 0.6f, true)
            })
            canvas.drawPath(path, Paint().apply {
                isAntiAlias = true
                mode = PaintMode.STROKE
                strokeWidth = width * 0.3f
                strokeCap = PaintStrokeCap.ROUND
                this.shader = shader
                blendMode = BlendMode.SCREEN
                maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, width * 0.08f, true)
            })
        }
        // One bright streak of light.
        val a = random.nextFloat() * 180f
        canvas.save()
        canvas.rotate(a, cx, cy)
        canvas.drawRect(Rect.makeLTRB(cx - r * 1.2f, cy - r * 0.012f, cx + r * 1.2f, cy + r * 0.012f), Paint().apply {
            shader = Shader.makeLinearGradient(cx - r, cy, cx + r, cy, intArrayOf(0, withAlpha(Color.WHITE, 0.8f), 0), null)
            maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, r * 0.01f, true)
        })
        canvas.restore()
        grain(canvas, cx, cy, r, 0.14f)
    }

    private fun mono(canvas: Canvas, cx: Float, cy: Float, r: Float, source: Source, p: Palette, random: Random) {
        val image = source.covers.firstOrNull() ?: return prism(canvas, cx, cy, r, p, random)
        val contrast = 1.35f
        val t = (1 - contrast) / 2f
        val grey = ColorMatrix(
            0.2126f * contrast, 0.7152f * contrast, 0.0722f * contrast, 0f, t,
            0.2126f * contrast, 0.7152f * contrast, 0.0722f * contrast, 0f, t,
            0.2126f * contrast, 0.7152f * contrast, 0.0722f * contrast, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )
        val zoom = 1f + random.nextFloat() * 0.25f
        cover(canvas, image, square(cx, cy, r * zoom), Paint().apply { colorFilter = ColorFilter.makeMatrix(grey) })
        canvas.drawRect(square(cx, cy, r), Paint().apply {
            shader = Shader.makeRadialGradient(cx, cy, r, intArrayOf(0, withAlpha(Color.BLACK, 0.45f)), floatArrayOf(0.55f, 1f))
        })
        grain(canvas, cx, cy, r, 0.22f)
    }

    private fun duotone(canvas: Canvas, cx: Float, cy: Float, r: Float, source: Source, p: Palette, random: Random) {
        val image = source.covers.getOrNull(random.nextInt(min(3, max(1, source.covers.size)))) ?: return stars(canvas, cx, cy, r, p, random)
        val lo = mix(p.dark, Color.BLACK, 0.3f)
        val hi = if (random.nextBoolean()) p.vivid else mix(p.accent, Color.WHITE, 0.2f)
        fun row(lc: Int, hc: Int): FloatArray {
            val l = lc / 255f
            val d = (hc - lc) / 255f
            return floatArrayOf(0.2126f * d * 1.2f, 0.7152f * d * 1.2f, 0.0722f * d * 1.2f, 0f, l - 0.1f * d)
        }
        val matrix = ColorMatrix(
            *row(Color.getR(lo), Color.getR(hi)),
            *row(Color.getG(lo), Color.getG(hi)),
            *row(Color.getB(lo), Color.getB(hi)),
            0f, 0f, 0f, 1f, 0f,
        )
        cover(canvas, image, square(cx, cy, r * 1.08f), Paint().apply { colorFilter = ColorFilter.makeMatrix(matrix) })
        // Halftone dots, as on a printed sleeve.
        val dot = Paint().apply { isAntiAlias = true; color = withAlpha(lo, 0.28f) }
        val step = r * 0.045f
        var y = cy - r
        var odd = false
        while (y < cy + r) {
            var x = cx - r + if (odd) step / 2 else 0f
            while (x < cx + r) {
                canvas.drawCircle(x, y, step * 0.22f, dot)
                x += step
            }
            y += step * 0.87f
            odd = !odd
        }
        grain(canvas, cx, cy, r, 0.16f)
    }

    private fun pixel(canvas: Canvas, cx: Float, cy: Float, r: Float, source: Source, p: Palette, random: Random) {
        val pics = source.covers.take(4)
        if (pics.isEmpty()) return stars(canvas, cx, cy, r, p, random)
        val n = 16
        val small = Surface.makeRasterN32Premul(n, n)
        if (pics.size >= 4 && random.nextBoolean()) {
            val h = n / 2f
            pics.forEachIndexed { i, image -> cover(small.canvas, image, Rect.makeXYWH((i % 2) * h, (i / 2) * h, h, h), sampling = SamplingMode.LINEAR) }
        } else {
            cover(small.canvas, pics[random.nextInt(pics.size)], Rect.makeWH(n.toFloat(), n.toFloat()), sampling = SamplingMode.LINEAR)
        }
        // Darks lifted, then fewer and punchier colours, like an old console's.
        val lift = ColorMatrix(
            1.2f, 0f, 0f, 0f, 0.08f,
            0f, 1.2f, 0f, 0f, 0.08f,
            0f, 0f, 1.2f, 0f, 0.08f,
            0f, 0f, 0f, 1f, 0f,
        )
        val levels = ByteArray(256) { i -> ((((i / 255f) * 5).toInt().coerceAtMost(4) / 4f) * 255).toInt().toByte() }
        val filter = ColorFilter.makeComposed(ColorFilter.makeTableARGB(null, levels, levels, levels), ColorFilter.makeMatrix(lift))
        canvas.drawRect(square(cx, cy, r), Paint().apply { color = p.dark })
        cover(canvas, small.makeImageSnapshot(), square(cx, cy, r), Paint().apply { colorFilter = filter }, SamplingMode.DEFAULT)
        val cell = 2 * r / n
        val grid = Paint().apply { color = withAlpha(Color.BLACK, 0.45f); strokeWidth = max(1f, r * 0.008f) }
        for (i in 0..n) {
            val o = cx - r + i * cell
            canvas.drawLine(o, cy - r, o, cy + r, grid)
            canvas.drawLine(cx - r, cy - r + i * cell, cx + r, cy - r + i * cell, grid)
        }
    }

    private fun glow(canvas: Canvas, cx: Float, cy: Float, r: Float, source: Source, p: Palette, random: Random) {
        val image = source.covers.firstOrNull()
        if (image == null) {
            canvas.drawRect(square(cx, cy, r), Paint().apply {
                shader = Shader.makeRadialGradient(cx, cy, r, intArrayOf(p.light, p.vivid, p.dark), floatArrayOf(0f, 0.5f, 1f))
            })
        } else {
            val saturate = 1.5f
            val s = saturate
            val lr = 0.2126f * (1 - s)
            val lg = 0.7152f * (1 - s)
            val lb = 0.0722f * (1 - s)
            val matrix = ColorMatrix(
                lr + s, lg, lb, 0f, 0f,
                lr, lg + s, lb, 0f, 0f,
                lr, lg, lb + s, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
            canvas.saveLayer(square(cx, cy, r), Paint().apply {
                imageFilter = ImageFilter.makeBlur(r * 0.14f, r * 0.14f, FilterTileMode.CLAMP, null, null)
            })
            cover(canvas, image, square(cx + (random.nextFloat() - 0.5f) * r * 0.3f, cy, r * 1.3f), Paint().apply { colorFilter = ColorFilter.makeMatrix(matrix) })
            canvas.restore()
        }
        // Fine rings, as a disc's surface catches the light.
        val ring = Paint().apply { isAntiAlias = true; mode = PaintMode.STROKE; strokeWidth = r * 0.004f; color = withAlpha(Color.WHITE, 0.08f) }
        var rr = r * 0.4f
        while (rr < r) {
            canvas.drawCircle(cx, cy, rr, ring)
            rr += r * (0.03f + random.nextFloat() * 0.04f)
        }
        grain(canvas, cx, cy, r, 0.12f)
    }
}
