/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.data

import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Path
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Made-up album covers ("sample:0" to "sample:11") for designs that show photos before any are
 * added: a preset's collage, the Disc Studio page. Only shapes and colour, no faces or words, so
 * they read as covers without being anyone's. Each is painted once, 320 pixels, and kept.
 */
object DiscSamples {
    const val Count = 12
    private const val Size = 320
    private val cache = HashMap<Int, Image>()

    fun id(n: Int) = "$SampleAssetPrefix${n.mod(Count)}"

    fun image(asset: String): Image? {
        val n = asset.removePrefix(SampleAssetPrefix).toIntOrNull()?.mod(Count) ?: return null
        return synchronized(cache) { cache.getOrPut(n) { paint(n) } }
    }

    private fun paint(n: Int): Image {
        val surface = Surface.makeRasterN32Premul(Size, Size)
        val c = surface.canvas
        val s = Size.toFloat()
        val random = Random(n * 977 + 13)
        when (n) {
            0 -> sunset(c, s)
            1 -> glowBlob(c, s, 0xFF0B0B12.toInt(), 0xFFFF4D8D.toInt(), 0xFF7A5CFF.toInt())
            2 -> bauhaus(c, s, random)
            3 -> halftoneSun(c, s)
            4 -> minimalLines(c, s)
            5 -> ocean(c, s)
            6 -> clouds(c, s, random)
            7 -> neonSplit(c, s)
            8 -> fieldStar(c, s)
            9 -> redRecord(c, s)
            10 -> filmLeak(c, s, random)
            else -> glowBlob(c, s, 0xFFE9E4F5.toInt(), 0xFF9C6BFF.toInt(), 0xFF3FD0C9.toInt())
        }
        grain(c, s, random)
        return surface.makeImageSnapshot()
    }

    private fun fill(color: Int) = Paint().apply { isAntiAlias = true; this.color = color }

    private fun sunset(c: Canvas, s: Float) {
        c.drawRect(Rect.makeWH(s, s), Paint().apply {
            shader = Shader.makeLinearGradient(0f, 0f, 0f, s, intArrayOf(0xFF2B1B4D.toInt(), 0xFFE8476A.toInt(), 0xFFFFB25B.toInt()), floatArrayOf(0f, 0.55f, 1f))
        })
        c.drawCircle(s * 0.5f, s * 0.62f, s * 0.22f, Paint().apply {
            isAntiAlias = true
            shader = Shader.makeLinearGradient(0f, s * 0.4f, 0f, s * 0.84f, intArrayOf(0xFFFFF1A8.toInt(), 0xFFFF7A59.toInt()), null)
        })
        // Bands cut through the sun, and the sea below.
        for (i in 0 until 5) c.drawRect(Rect.makeXYWH(0f, s * (0.64f + i * 0.045f), s, s * (0.008f + i * 0.004f)), fill(0xFF2B1B4D.toInt()))
        c.drawRect(Rect.makeLTRB(0f, s * 0.84f, s, s), fill(0xFF1B1238.toInt()))
    }

    private fun glowBlob(c: Canvas, s: Float, bg: Int, a: Int, b: Int) {
        c.drawRect(Rect.makeWH(s, s), fill(bg))
        c.drawCircle(s * 0.42f, s * 0.45f, s * 0.3f, Paint().apply { isAntiAlias = true; color = a; maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, s * 0.1f, true) })
        c.drawCircle(s * 0.62f, s * 0.6f, s * 0.24f, Paint().apply { isAntiAlias = true; color = b; blendMode = BlendMode.SCREEN; maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, s * 0.09f, true) })
        c.drawCircle(s * 0.5f, s * 0.5f, s * 0.07f, Paint().apply { isAntiAlias = true; color = 0xCCFFFFFF.toInt(); maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, s * 0.03f, true) })
    }

    private fun bauhaus(c: Canvas, s: Float, random: Random) {
        val colors = intArrayOf(0xFFE63946.toInt(), 0xFF1D3557.toInt(), 0xFFF1C40F.toInt(), 0xFFF4EBD9.toInt(), 0xFF2A9D8F.toInt())
        c.drawRect(Rect.makeWH(s, s), fill(0xFFF4EBD9.toInt()))
        val cell = s / 3f
        for (y in 0 until 3) for (x in 0 until 3) {
            val l = x * cell
            val t = y * cell
            val col = colors[random.nextInt(colors.size)]
            when (random.nextInt(4)) {
                0 -> c.drawCircle(l + cell / 2, t + cell / 2, cell * 0.42f, fill(col))
                1 -> c.drawArc(l, t, l + cell * 2, t + cell * 2, 180f, 90f, true, fill(col))
                2 -> c.drawRect(Rect.makeXYWH(l, t, cell, cell), fill(col))
                else -> c.drawPath(Path().moveTo(l, t + cell).lineTo(l + cell, t + cell).lineTo(l + cell, t).closePath(), fill(col))
            }
        }
    }

    private fun halftoneSun(c: Canvas, s: Float) {
        c.drawRect(Rect.makeWH(s, s), fill(0xFFFFD23F.toInt()))
        val step = s / 26f
        for (y in 0..26) for (x in 0..26) {
            val dx = x * step - s * 0.5f
            val dy = y * step - s * 0.5f
            val d = kotlin.math.hypot(dx, dy) / (s * 0.5f)
            val dot = step * 0.48f * (1f - d).coerceIn(0f, 1f)
            if (dot > 0.4f) c.drawCircle(x * step, y * step, dot, fill(0xFFE4572E.toInt()))
        }
    }

    private fun minimalLines(c: Canvas, s: Float) {
        c.drawRect(Rect.makeWH(s, s), fill(0xFF121214.toInt()))
        val line = Paint().apply { isAntiAlias = true; mode = PaintMode.STROKE; strokeWidth = s * 0.006f; color = 0xFFEDEDED.toInt() }
        for (i in 0 until 22) {
            val y = s * 0.2f + i * s * 0.028f
            val wave = Path().moveTo(s * 0.12f, y)
            var x = s * 0.12f
            while (x < s * 0.88f) {
                val k = (x - s * 0.12f) / (s * 0.76f)
                val bump = sin(k * PI.toFloat()) * s * 0.06f * sin(i * 0.5f + k * 9f)
                x += s * 0.01f
                wave.lineTo(x, y - bump.coerceAtLeast(0f) * 2.2f)
            }
            c.drawPath(wave, line)
        }
    }

    private fun ocean(c: Canvas, s: Float) {
        c.drawRect(Rect.makeWH(s, s), Paint().apply {
            shader = Shader.makeLinearGradient(0f, 0f, 0f, s, intArrayOf(0xFFBFE6FF.toInt(), 0xFF5AA9E6.toInt(), 0xFF0B3C5D.toInt()), floatArrayOf(0f, 0.45f, 1f))
        })
        for (i in 0 until 9) {
            val y = s * (0.45f + i * 0.065f)
            val path = Path().moveTo(0f, y)
            var x = 0f
            while (x < s) {
                path.quadTo(x + s * 0.06f, y - s * 0.03f, x + s * 0.12f, y)
                x += s * 0.12f
            }
            c.drawPath(path, Paint().apply { isAntiAlias = true; mode = PaintMode.STROKE; strokeWidth = s * 0.012f; color = 0x88FFFFFF.toInt() })
        }
    }

    private fun clouds(c: Canvas, s: Float, random: Random) {
        c.drawRect(Rect.makeWH(s, s), Paint().apply {
            shader = Shader.makeLinearGradient(0f, 0f, 0f, s, intArrayOf(0xFFFFC8DD.toInt(), 0xFFBDE0FE.toInt()), null)
        })
        repeat(5) {
            val x = random.nextFloat() * s
            val y = s * (0.2f + random.nextFloat() * 0.7f)
            val w = s * (0.12f + random.nextFloat() * 0.1f)
            val p = fill(0xEEFFFFFF.toInt()).apply { maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, s * 0.012f, true) }
            c.drawCircle(x, y, w, p)
            c.drawCircle(x + w * 0.9f, y + w * 0.2f, w * 0.75f, p)
            c.drawCircle(x - w * 0.9f, y + w * 0.25f, w * 0.7f, p)
        }
    }

    private fun neonSplit(c: Canvas, s: Float) {
        c.drawRect(Rect.makeWH(s, s), fill(0xFF0C0A1E.toInt()))
        c.drawPath(Path().moveTo(0f, s).lineTo(s, 0f).lineTo(s, s).closePath(), Paint().apply {
            isAntiAlias = true
            shader = Shader.makeLinearGradient(s, 0f, 0f, s, intArrayOf(0xFF00E5FF.toInt(), 0xFFFF2BD6.toInt()), null)
        })
        c.drawCircle(s * 0.5f, s * 0.5f, s * 0.2f, Paint().apply { isAntiAlias = true; mode = PaintMode.STROKE; strokeWidth = s * 0.035f; color = Color.WHITE })
    }

    private fun fieldStar(c: Canvas, s: Float) {
        c.drawRect(Rect.makeWH(s, s), Paint().apply {
            shader = Shader.makeLinearGradient(0f, 0f, 0f, s, intArrayOf(0xFFA8D5BA.toInt(), 0xFF3E7C59.toInt(), 0xFF1E3D2B.toInt()), floatArrayOf(0f, 0.6f, 1f))
        })
        val star = Path()
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) s * 0.2f else s * 0.08f
            val a = (i * PI / 5 - PI / 2).toFloat()
            val x = s * 0.5f + cos(a) * rad
            val y = s * 0.42f + sin(a) * rad
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        c.drawPath(star.closePath(), fill(0xFFFFF7E0.toInt()))
    }

    private fun redRecord(c: Canvas, s: Float) {
        c.drawRect(Rect.makeWH(s, s), fill(0xFFD62828.toInt()))
        c.drawCircle(s * 0.58f, s * 0.5f, s * 0.36f, fill(0xFF111111.toInt()))
        for (i in 1..8) c.drawCircle(s * 0.58f, s * 0.5f, s * (0.12f + i * 0.028f), Paint().apply { isAntiAlias = true; mode = PaintMode.STROKE; strokeWidth = 1.2f; color = 0x33FFFFFF })
        c.drawCircle(s * 0.58f, s * 0.5f, s * 0.11f, fill(0xFFF7E3AF.toInt()))
        c.drawCircle(s * 0.58f, s * 0.5f, s * 0.012f, fill(0xFF111111.toInt()))
    }

    private fun filmLeak(c: Canvas, s: Float, random: Random) {
        c.drawRect(Rect.makeWH(s, s), Paint().apply {
            shader = Shader.makeLinearGradient(0f, 0f, s, s, intArrayOf(0xFF2F4858.toInt(), 0xFF86BBD8.toInt(), 0xFFF6AE2D.toInt()), floatArrayOf(0f, 0.6f, 1f))
        })
        c.drawCircle(s * (0.75f + random.nextFloat() * 0.1f), s * 0.2f, s * 0.35f, Paint().apply {
            isAntiAlias = true
            shader = Shader.makeRadialGradient(s * 0.8f, s * 0.2f, s * 0.35f, intArrayOf(0xCCFF6B35.toInt(), 0), null)
            blendMode = BlendMode.SCREEN
        })
        c.drawRect(Rect.makeLTRB(0f, s * 0.7f, s, s), fill(0xAA1B2631.toInt()))
    }

    private fun grain(c: Canvas, s: Float, random: Random) {
        val p = Paint()
        repeat(900) {
            p.color = if (random.nextBoolean()) 0x14FFFFFF else 0x14000000
            c.drawRect(Rect.makeXYWH(random.nextFloat() * s, random.nextFloat() * s, 1.5f, 1.5f), p)
        }
    }
}
