/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.data

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.math.sqrt

/**
 * "Remove the background" for Disc Studio's pictures: the plain background round a drawing or a
 * cut-out (white, one colour, or the grey-and-white squares of a picture saved with fake
 * see-through) becomes really see-through, so the disc shows round the subject.
 *
 * The background is found from the picture's edges: the colours most of the border shares, then
 * every pixel of those colours joined to the border. Parts of the same colour inside the subject,
 * closed off by its outlines, stay.
 */
internal object PictureCutout {
    /** How far (in RGB steps) a pixel can be from a background colour and still count as background. */
    private const val Tolerance = 42f

    /** Up to this many background colours: a checker pattern has two, with room for one more shade. */
    private const val MaxColours = 3

    /** The share of the border the background colours must cover between them. */
    private const val Enough = 0.45f

    /** The least share of the border a colour needs of its own to count as background. */
    private const val MinShare = 0.12f

    /** A further background colour covers at least this much of what the first one does. */
    private const val PartnerShare = 0.35f

    /** Returns the new picture's asset name; throws with a message fit to show when there is no plain background. */
    fun removeBackground(asset: String): String {
        val file = DiscDesigns.assetFile(asset)
        if (!file.isFile) error("That picture isn't there any more.")
        val image = Image.makeFromEncoded(file.readBytes())
        val w = image.width
        val h = image.height
        val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        val bitmap = Bitmap()
        try {
            bitmap.allocPixels(info)
            if (!image.readPixels(bitmap, 0, 0)) error("Couldn't read that picture.")
            val px = bitmap.readPixels(info, w * 4, 0, 0) ?: error("Couldn't read that picture.")
            val colours = borderColours(px, w, h)
            if (colours.isEmpty()) error("This picture has no plain background to remove.")
            val background = flood(px, w, h, colours)
            if (background.cardinality() == 0) error("This picture has no plain background to remove.")
            clear(px, w, h, background, colours)
            val out = Image.makeRaster(info, px, w * 4)
            val png = out.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("Couldn't save the picture.")
            return DiscDesigns.importImage(png, "png")
        } finally {
            bitmap.close()
            image.close()
        }
    }

    /** The colours that cover most of the border, or none when the border is too mixed to be a background. */
    private fun borderColours(px: ByteArray, w: Int, h: Int): List<IntArray> {
        val counts = HashMap<Int, IntArray>()
        var total = 0
        fun visit(x: Int, y: Int) {
            val i = (y * w + x) * 4
            if (alpha(px, i) < 16) return
            total++
            // 5 bits a channel groups near shades, so JPEG speckle doesn't split a colour.
            val key = ((px[i].toInt() and 0xFF) shr 3 shl 10) or ((px[i + 1].toInt() and 0xFF) shr 3 shl 5) or ((px[i + 2].toInt() and 0xFF) shr 3)
            val slot = counts.getOrPut(key) { IntArray(4) }
            slot[0]++
            slot[1] += px[i].toInt() and 0xFF
            slot[2] += px[i + 1].toInt() and 0xFF
            slot[3] += px[i + 2].toInt() and 0xFF
        }
        for (x in 0 until w) { visit(x, 0); visit(x, h - 1) }
        for (y in 1 until h - 1) { visit(0, y); visit(w - 1, y) }
        // An already see-through border needs nothing found: the flood starts from those pixels.
        if (total == 0) return listOf(IntArray(3) { -1000 })
        val picked = ArrayList<IntArray>()
        var covered = 0
        var first = 0
        for (slot in counts.values.sortedByDescending { it[0] }) {
            val n = slot[0]
            val colour = intArrayOf(slot[1] / n, slot[2] / n, slot[3] / n)
            // Shades next to one already picked add to it rather than taking a place of their own.
            // Another colour counts only with a fair share of the border and about as much of it
            // as the first (a checker's two squares), so the subject's own colours where it
            // touches the edge aren't taken for background.
            if (picked.none { distance(it, colour[0], colour[1], colour[2]) < Tolerance }) {
                if (picked.size == MaxColours || n < total * MinShare || n < first * PartnerShare) continue
                if (picked.isEmpty()) first = n
                picked.add(colour)
            }
            covered += n
        }
        return if (covered >= total * Enough) picked else emptyList()
    }

    /** Every background-coloured (or already see-through) pixel joined to the border. */
    private fun flood(px: ByteArray, w: Int, h: Int, colours: List<IntArray>): java.util.BitSet {
        val seen = java.util.BitSet(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        fun push(p: Int) {
            if (seen[p] || !isBackground(px, p * 4, colours)) return
            seen.set(p)
            queue[tail++] = p
        }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (head < tail) {
            val p = queue[head++]
            val x = p % w
            if (x > 0) push(p - 1)
            if (x < w - 1) push(p + 1)
            if (p >= w) push(p - w)
            if (p < w * (h - 1)) push(p + w)
        }
        return seen
    }

    /**
     * Clears the background, and softens the subject's rim where it touches it: a pixel there
     * fades by how close its colour is to the background, so light anti-aliasing doesn't leave a halo.
     */
    private fun clear(px: ByteArray, w: Int, h: Int, background: java.util.BitSet, colours: List<IntArray>) {
        var p = background.nextSetBit(0)
        while (p >= 0) {
            px[p * 4 + 3] = 0
            p = background.nextSetBit(p + 1)
        }
        for (y in 0 until h) for (x in 0 until w) {
            val q = y * w + x
            if (background[q]) continue
            val touches = (x > 0 && background[q - 1]) || (x < w - 1 && background[q + 1]) ||
                (y > 0 && background[q - w]) || (y < h - 1 && background[q + w])
            if (!touches) continue
            val i = q * 4
            val d = nearest(px, i, colours)
            val keep = ((d - Tolerance) / (Tolerance * 2f)).coerceIn(0.2f, 1f)
            px[i + 3] = (alpha(px, i) * keep).toInt().toByte()
        }
    }

    private fun isBackground(px: ByteArray, i: Int, colours: List<IntArray>): Boolean =
        alpha(px, i) < 16 || nearest(px, i, colours) <= Tolerance

    private fun nearest(px: ByteArray, i: Int, colours: List<IntArray>): Float {
        val r = px[i].toInt() and 0xFF
        val g = px[i + 1].toInt() and 0xFF
        val b = px[i + 2].toInt() and 0xFF
        var best = Float.MAX_VALUE
        for (c in colours) best = minOf(best, distance(c, r, g, b))
        return best
    }

    private fun distance(c: IntArray, r: Int, g: Int, b: Int): Float {
        val dr = (c[0] - r).toFloat()
        val dg = (c[1] - g).toFloat()
        val db = (c[2] - b).toFloat()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun alpha(px: ByteArray, i: Int) = px[i + 3].toInt() and 0xFF
}
