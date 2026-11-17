/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.data

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Photo collages laid out in one go: a playlist's covers (or any pictures) as prints round the
 * middle, scattered, tiled into a picture disc, piled up or wound out in a spiral. Each picture is
 * a layer of its own afterwards, so any one can be moved, turned or swapped like any other.
 */
object DiscCollage {
    enum class Layout(val label: String) {
        RING("Ring"),
        SCATTER("Scatter"),
        GRID("Picture disc"),
        STACK("Pile"),
        SPIRAL("Spiral"),
    }

    /** One picture's place: centre (-1..1 across the disc), half its size (of the radius) and its turn. */
    data class Spot(val x: Float, val y: Float, val scale: Float, val rotation: Float)

    /** Where [n] pictures go, kept outside [inner] (the hub or label, a fraction of the radius). */
    fun spots(layout: Layout, n: Int, seed: Int, inner: Float = 0.36f): List<Spot> {
        val count = n.coerceIn(1, 40)
        val random = Random(seed * 7 + count)
        return when (layout) {
            Layout.RING -> {
                // One ring between the middle and the edge; a second, inner one when they don't fit.
                val rings = if (count > 11) 2 else 1
                val perOuter = if (rings == 2) ceil(count * 0.58f).toInt() else count
                val start = random.nextFloat() * 2f * PI.toFloat()
                List(count) { i ->
                    val outer = i < perOuter
                    val k = if (outer) i else i - perOuter
                    val m = if (outer) perOuter else count - perOuter
                    val mid = if (rings == 1) (inner + 1f) / 2f + 0.03f else if (outer) 0.76f else (inner + 0.76f) / 2f
                    val arc = 2f * PI.toFloat() * mid / m
                    val half = min(if (rings == 1) 0.22f else 0.15f, arc * 0.46f).coerceAtMost((1f - inner) * 0.3f)
                    val a = start + (k + random.nextFloat() * 0.3f) * 2f * PI.toFloat() / m + if (outer) 0f else 0.4f
                    val d = mid + (random.nextFloat() - 0.5f) * 0.06f
                    Spot(cos(a) * d, sin(a) * d, half * (0.92f + random.nextFloat() * 0.16f), (random.nextFloat() - 0.5f) * 28f)
                }
            }
            Layout.SCATTER -> {
                val half = (0.42f / sqrt(count.toFloat())).coerceIn(0.1f, 0.26f)
                val placed = ArrayList<Spot>()
                repeat(count) {
                    var best: Spot? = null
                    var bestGap = -1f
                    // A handful of tries each, kept where it lands furthest from the others.
                    repeat(30) {
                        val a = random.nextFloat() * 2f * PI.toFloat()
                        val d = inner + half * 0.6f + random.nextFloat() * (0.98f - inner - half * 0.6f)
                        val x = cos(a) * d
                        val y = sin(a) * d
                        val gap = placed.minOfOrNull { hypot(it.x - x, it.y - y) } ?: 9f
                        if (gap > bestGap) {
                            bestGap = gap
                            best = Spot(x, y, half * (0.8f + random.nextFloat() * 0.4f), (random.nextFloat() - 0.5f) * 40f)
                        }
                    }
                    placed += best!!
                }
                placed
            }
            Layout.GRID -> {
                // Square tiles over the whole disc, as on a picture disc; the edge cuts the outer ones.
                val side = max(3, ceil(sqrt(count * 1.3f)).toInt())
                val cell = 2.1f / side
                val out = ArrayList<Spot>()
                for (row in 0 until side) for (col in 0 until side) {
                    val x = -1.05f + cell * (col + 0.5f)
                    val y = -1.05f + cell * (row + 0.5f)
                    if (hypot(x, y) - cell * 0.7f < 1f) out += Spot(x, y, cell / 2f, 0f)
                }
                out
            }
            Layout.STACK -> List(count) { i ->
                val t = i / max(1f, count - 1f)
                val a = random.nextFloat() * 2f * PI.toFloat()
                val d = 0.5f + random.nextFloat() * 0.18f
                Spot(cos(a) * d * (0.6f + t * 0.4f), sin(a) * d * (0.6f + t * 0.4f), 0.24f + random.nextFloat() * 0.06f, (random.nextFloat() - 0.5f) * 50f)
            }
            Layout.SPIRAL -> List(count) { i ->
                val t = (i + 0.5f) / count
                val a = t * 3.6f * PI.toFloat() + seed * 0.37f
                val d = inner + 0.08f + t * (0.92f - inner - 0.08f)
                Spot(cos(a) * d, sin(a) * d, 0.07f + t * 0.13f, Math.toDegrees(a.toDouble()).toFloat() + 90f)
            }
        }
    }

    /** The collage as picture layers: [pictures] in turn (again from the start if there are more places). */
    fun layers(pictures: List<String>, layout: Layout, frame: ImageFrame, seed: Int, inner: Float = 0.36f, count: Int = pictures.size): List<DiscLayer> {
        if (pictures.isEmpty()) return emptyList()
        val spots = spots(layout, count, seed, inner)
        val tiles = layout == Layout.GRID
        return spots.mapIndexed { i, spot ->
            DiscLayer(
                kind = LayerKind.IMAGE,
                name = "Photo ${i + 1}",
                asset = pictures[i % pictures.size],
                frame = if (tiles) ImageFrame.SQUARE else frame,
                x = spot.x,
                y = spot.y,
                scale = spot.scale,
                rotation = spot.rotation,
                shadow = !tiles,
                amount = 1f,
            )
        }
    }
}
