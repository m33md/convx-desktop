/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.components.awaitPacedFrameMillis
import com.convx.desktop.ui.components.rememberLoopingPhase
import com.convx.desktop.data.PlayerBackground
import com.convx.desktop.data.PlayerDesign
import com.convx.desktop.data.Settings
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.ImageCache
import com.convx.desktop.ui.components.artworkUrl
import com.convx.desktop.ui.components.rememberImage
import com.convx.desktop.ui.components.rememberMeshColors
import com.convx.desktop.ui.glass.LocalGlassConfig
import com.convx.desktop.ui.glass.LocalPagesBackdrop
import com.convx.desktop.ui.glass.liquidGlass
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.rgbToHsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// ---- colours ---------------------------------------------------------------------------------

/** What the phone's player backgrounds take from a cover. */
class CoverSwatches(
    /** Top and bottom of the iOS Now Playing gradient, for the square layout and for full bleed. */
    val edgeSquare: Pair<Color, Color>,
    val edgeHero: Pair<Color, Color>,
    /** The Gradient background: the cover's strongest colour, a darker step of it, then black. */
    val gradient: List<Color>,
    /** The Glow background's six swatches. */
    val glow: List<Color>,
)

private val swatchCache = ConcurrentHashMap<String, CoverSwatches>()

/** The cover's swatches, read from a small copy of it and kept per cover. */
@Composable
fun rememberCoverSwatches(url: String?): State<CoverSwatches?> {
    val small = artworkUrl(url, 120)
    return produceState(small?.let { swatchCache[it] }, small) {
        if (small == null) {
            value = null
            return@produceState
        }
        swatchCache[small]?.let {
            value = it
            return@produceState
        }
        val bitmap = ImageCache.load(small) ?: return@produceState
        value = withContext(Dispatchers.Default) { CoverColors.swatches(bitmap) }.also { swatchCache[small] = it }
    }
}

object CoverColors {
    fun swatches(bitmap: ImageBitmap): CoverSwatches {
        val pixels = bitmap.toPixelMap()
        val w = pixels.width
        val h = pixels.height
        val band = (h * 0.15f).toInt().coerceAtLeast(1)
        fun rows(from: Int, to: Int): Color {
            var r = 0f
            var g = 0f
            var b = 0f
            var n = 0
            for (y in from until to) for (x in 0 until w) {
                val c = pixels[x, y]
                r += c.red
                g += c.green
                b += c.blue
                n++
            }
            return if (n == 0) Color.Black else Color(r / n, g / n, b / n)
        }
        val top = rows(0, band)
        val bottom = rows(h - band, h)
        val swatches = paletteOf(pixels.let { map -> (0 until h step max(1, h / 40)).flatMap { y -> (0 until w step max(1, w / 40)).map { x -> map[x, y] } } })
        return CoverSwatches(
            edgeSquare = shade(top, maxValue = 0.47f, scale = 0.80f) to shade(bottom, maxValue = 0.28f, scale = 0.50f),
            edgeHero = shade(bottom, maxValue = 0.47f, scale = 0.85f) to shade(bottom, maxValue = 0.28f, scale = 0.45f),
            gradient = swatches.gradient,
            glow = swatches.glow,
        )
    }

    private class Found(val gradient: List<Color>, val glow: List<Color>)

    /**
     * AndroidX Palette's six targets (vibrant, light and dark vibrant, muted, light and dark
     * muted), each the most common colour near its saturation and lightness, as the phone reads them.
     */
    private fun paletteOf(samples: List<Color>): Found {
        // Pool into 4-bit buckets so a sleeve's colours gather.
        val buckets = HashMap<Int, FloatArray>()
        for (c in samples) {
            val key = ((c.red * 15).toInt() shl 8) or ((c.green * 15).toInt() shl 4) or (c.blue * 15).toInt()
            val acc = buckets.getOrPut(key) { FloatArray(4) }
            acc[0] += 1f
            acc[1] += c.red
            acc[2] += c.green
            acc[3] += c.blue
        }
        val colors = buckets.values.map { Color(it[1] / it[0], it[2] / it[0], it[3] / it[0]) to it[0] }
        val total = samples.size.coerceAtLeast(1).toFloat()
        val hsv = FloatArray(3)
        val weightedSaturation = colors.sumOf { (c, n) ->
            rgbToHsv(c.red, c.green, c.blue, hsv)
            (hsv[1] * n).toDouble()
        } / total
        val dominant = colors.maxByOrNull { it.second }?.first ?: Color(0xFF2C2C2E)

        fun hsl(c: Color): FloatArray {
            val mx = max(c.red, max(c.green, c.blue))
            val mn = min(c.red, min(c.green, c.blue))
            val l = (mx + mn) / 2f
            val s = if (mx == mn) 0f else (mx - mn) / (1f - kotlin.math.abs(2f * l - 1f))
            return floatArrayOf(s.coerceIn(0f, 1f), l)
        }
        data class Target(val sMin: Float, val sTarget: Float, val sMax: Float, val lMin: Float, val lTarget: Float, val lMax: Float)
        val targets = listOf(
            Target(0.35f, 1f, 1f, 0.3f, 0.5f, 0.7f), // vibrant
            Target(0.35f, 1f, 1f, 0.55f, 0.74f, 1f), // light vibrant
            Target(0.35f, 1f, 1f, 0f, 0.26f, 0.45f), // dark vibrant
            Target(0f, 0.3f, 0.4f, 0.3f, 0.5f, 0.7f), // muted
            Target(0f, 0.3f, 0.4f, 0.55f, 0.74f, 1f), // light muted
            Target(0f, 0.3f, 0.4f, 0f, 0.26f, 0.45f), // dark muted
        )
        val maxPopulation = colors.maxOfOrNull { it.second } ?: 1f
        val picked = targets.map { t ->
            colors.filter { (c, _) ->
                val (s, l) = hsl(c).let { it[0] to it[1] }
                s in t.sMin..t.sMax && l in t.lMin..t.lMax
            }.maxByOrNull { (c, n) ->
                val (s, l) = hsl(c).let { it[0] to it[1] }
                (1f - kotlin.math.abs(s - t.sTarget)) * 0.24f + (1f - kotlin.math.abs(l - t.lTarget)) * 0.52f + (n / maxPopulation) * 0.24f
            }?.first
        }
        val vibrantFirst = listOfNotNull(picked[0], picked[2], picked[1], picked[3], picked[5], picked[4])
        val best = (vibrantFirst.firstOrNull() ?: dominant)
        val achromatic = weightedSaturation < 0.12
        val primary = if (achromatic) {
            val grey = (best.red + best.green + best.blue) / 3f
            Color(grey, grey, grey)
        } else {
            vivid(best, if (picked[0] != null || picked[2] != null || picked[1] != null) 1.3f else 1.1f)
        }
        val gradient = listOf(primary, Color(primary.red * 0.6f, primary.green * 0.6f, primary.blue * 0.6f), Color.Black)
        val glow = picked.map { it ?: dominant }.distinct().ifEmpty { listOf(dominant) }
        return Found(gradient, glow)
    }

    private fun vivid(color: Color, factor: Float): Color {
        val hsv = FloatArray(3)
        rgbToHsv(color.red, color.green, color.blue, hsv)
        return Color.hsv(hsv[0], (hsv[1] * factor).coerceIn(0f, 1f), hsv[2].coerceIn(0.25f, 0.9f))
    }

    /** The phone's NowPlayingGradient shade: value scaled and capped, saturation nudged up, kept dark enough for white. */
    private fun shade(color: Color, maxValue: Float, scale: Float): Color {
        val channels = floatArrayOf(color.red * 255f, color.green * 255f, color.blue * 255f)
        val high = channels.max()
        val low = channels.min()
        val saturation = if (high == 0f) 0f else ((high - low) / high * 1.15f).coerceAtMost(1f)
        val value = (high * scale).coerceAtMost(maxValue * 255f)
        fun channel(i: Int): Int {
            val hue = if (high == low) 0f else (channels[i] - low) / (high - low)
            return (value * (1f - saturation + saturation * hue)).roundToInt().coerceIn(0, 255)
        }
        var r = channel(0)
        var g = channel(1)
        var b = channel(2)
        fun linear(c: Int): Double = (c / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        while (0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b) > 0.183) {
            r = (r * 0.98f).toInt()
            g = (g * 0.98f).toInt()
            b = (b * 0.98f).toInt()
        }
        return Color(r, g, b)
    }
}

// ---- backgrounds -----------------------------------------------------------------------------

/**
 * iOS 26's Now Playing background as the phone draws it: no blur and no mesh, one vertical
 * gradient from the cover's top edge colour to its bottom edge colour, darkened for the controls.
 * With [hero] the cover fills the top itself, so the gradient starts from its foot.
 */
@Composable
fun NowPlayingBackground(url: String?, hero: Boolean, modifier: Modifier = Modifier) {
    val swatches by rememberCoverSwatches(url)
    val edge = swatches?.let { if (hero) it.edgeHero else it.edgeSquare }
    val top by animateColorAsState(edge?.first ?: Color(0xFF2C2C2E), tween(800))
    val bottom by animateColorAsState(edge?.second ?: Color(0xFF121214), tween(800))
    Box(
        modifier.drawBehind {
            drawRect(Brush.verticalGradient(0f to top, (if (hero) 0.62f else 0.606f) to top, 1f to bottom))
        },
    )
}

/** Which background a design paints: Apple's designs keep their own, the rest take the chosen one. */
fun Settings.effectiveBackground(): PlayerBackground = when {
    playerDesign == PlayerDesign.AppleMusic -> PlayerBackground.Apple
    playerDesign == PlayerDesign.AppleMusicV2 && (playerLook == com.convx.desktop.data.PlayerLook.Hero || playerBackground == PlayerBackground.Gradient) ->
        PlayerBackground.Apple
    playerBackground == PlayerBackground.Apple -> PlayerBackground.Gradient
    else -> playerBackground
}

/** Full bleed: Apple Music, and Apple Music v2's second layout. */
val Settings.heroLayout: Boolean
    get() = playerDesign == PlayerDesign.AppleMusic || (playerDesign == PlayerDesign.AppleMusicV2 && playerLook == com.convx.desktop.data.PlayerLook.Hero)

/**
 * The player's background for [style], as the phone's player draws each one. [hero] is for
 * Apple's style, whose gradient then starts from the foot of a full-bleed cover.
 */
@Composable
fun PlayerBackdrop(
    style: PlayerBackground,
    url: String?,
    playing: Boolean,
    settings: Settings,
    modifier: Modifier = Modifier,
    hero: Boolean = false,
) {
    Box(modifier.fillMaxSize()) {
        when (style) {
            // Convx's theme is black.
            PlayerBackground.Theme -> Box(Modifier.fillMaxSize().background(Color.Black))
            PlayerBackground.Gradient -> {
                val swatches by rememberCoverSwatches(url)
                val colors = swatches?.gradient
                val a by animateColorAsState(colors?.getOrNull(0) ?: Color(0xFF2C2C2E), tween(800))
                val b by animateColorAsState(colors?.getOrNull(1) ?: Color(0xFF1A1A1C), tween(800))
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        drawRect(Brush.verticalGradient(0f to a, 0.5f to b, 1f to Color.Black))
                        drawRect(Color.Black.copy(alpha = 0.2f))
                    },
                )
            }
            PlayerBackground.Blur -> {
                Box(Modifier.fillMaxSize().background(Color.Black))
                CoverImage(url, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.2f; scaleY = 1.2f }.blur(150.dp), size = 120)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
            }
            PlayerBackground.Glow -> {
                val swatches by rememberCoverSwatches(url)
                GlowBackground(swatches?.glow.orEmpty(), playing)
            }
            PlayerBackground.Apple -> NowPlayingBackground(url, hero = hero, modifier = Modifier.fillMaxSize())
            PlayerBackground.LiveMesh -> LiveMeshBackground(url, playing)
            PlayerBackground.ColourMesh -> {
                val mesh by rememberMeshColors(url?.let { artworkUrl(it, 226) })
                MeshBackground(mesh, url)
            }
            PlayerBackground.Solid -> Box(Modifier.fillMaxSize().background(Color(settings.playerSolidColor)))
            PlayerBackground.Custom -> {
                val stops = settings.playerGradientStops.map { Color(it) }.ifEmpty { listOf(Color(0xFF3A1C71), Color(0xFFD76D77)) }
                Box(Modifier.fillMaxSize().tiltedGradient(stops, settings.playerGradientAngle))
            }
            // The app under the player, through frosted glass; the phone multiplies the glass blur for it.
            PlayerBackground.Glass -> {
                val config = LocalGlassConfig.current
                Box(
                    Modifier
                        .fillMaxSize()
                        .liquidGlass(
                            RoundedCornerShape(0.dp),
                            config.copy(blurRadius = (config.blurRadius * PlayerBlurMultiplier).coerceIn(24f, 100f)),
                            applyEdgeEffects = false,
                            outerShadow = false,
                            backdrop = LocalPagesBackdrop.current,
                        )
                        .background(Color.Black.copy(alpha = 0.18f)),
                )
            }
        }
    }
}

private const val PlayerBlurMultiplier = 8f

/** A linear gradient at [angleDegrees] (90 runs top to bottom), across the whole box. */
fun Modifier.tiltedGradient(colors: List<Color>, angleDegrees: Float): Modifier = drawBehind {
    val radians = (angleDegrees - 90f) / 180f * PI.toFloat()
    val dx = cos(radians + PI.toFloat() / 2f)
    val dy = sin(radians + PI.toFloat() / 2f)
    val half = (kotlin.math.abs(dx * size.width) + kotlin.math.abs(dy * size.height)) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val colorList = if (colors.size == 1) listOf(colors[0], colors[0]) else colors
    drawRect(Brush.linearGradient(colorList, start = center - Offset(dx * half, dy * half), end = center + Offset(dx * half, dy * half)))
}

@Composable
private fun CoverImage(url: String?, modifier: Modifier, size: Int, colorFilter: ColorFilter? = null, alignment: Alignment = Alignment.Center) {
    val image by rememberImage(artworkUrl(url, size))
    image?.let { Image(it, null, modifier, alignment = alignment, contentScale = ContentScale.Crop, colorFilter = colorFilter) }
}

/** The phone's Glow: six soft blobs in the cover's swatches, drifting through them while music plays. */
@Composable
private fun GlowBackground(colors: List<Color>, playing: Boolean) {
    val drift = rememberLoopingPhase(20_000, running = playing)
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(Color(0xFF050505))
            if (colors.isEmpty()) return@drawBehind
            val p = drift.value
            fun colorAt(i: Int): Color {
                val idx = i + p * colors.size
                val a = floor(idx).toInt() % colors.size
                return lerp(colors[a], colors[(a + 1) % colors.size], idx - floor(idx))
            }
            fun osc(lo: Float, hi: Float, phase: Float): Float = lo + (hi - lo) * ((sin(2f * PI.toFloat() * (p + phase)) + 1f) / 2f)
            val blobs = listOf(
                floatArrayOf(osc(0f, 1f, 0f), osc(0f, .5f, .07f), osc(.8f, 1.6f, .12f), .85f),
                floatArrayOf(osc(1f, 0f, .2f), osc(.5f, 1f, .25f), osc(.7f, 1.5f, .18f), .8f),
                floatArrayOf(osc(.2f, .8f, .33f), osc(.8f, .2f, .36f), osc(.6f, 1.4f, .29f), .75f),
                floatArrayOf(osc(.3f, .7f, .44f), osc(.2f, .8f, .41f), osc(.9f, 1.7f, .47f), .7f),
                floatArrayOf(osc(.4f, .6f, .55f), osc(0f, 1f, .51f), osc(.7f, 1.5f, .58f), .65f),
                floatArrayOf(osc(0f, 1f, .66f), osc(.5f, .7f, .62f), osc(.8f, 1.8f, .69f), .6f),
            )
            // The phone sizes blobs to a portrait screen's width; a window is wide, so the shorter side stands in.
            val unit = min(size.width, size.height * 0.75f)
            blobs.forEachIndexed { i, (x, y, r, a) ->
                val c = colorAt(i)
                drawRect(
                    Brush.radialGradient(
                        listOf(c.copy(alpha = a), c.copy(alpha = a * 0.6f), Color.Transparent),
                        center = Offset(size.width * x, size.height * y),
                        radius = unit * r,
                    ),
                )
            }
        },
    )
}

/** The phone's Live mesh: three blurred, saturated copies of the cover turning at different speeds. */
@Composable
private fun LiveMeshBackground(url: String?, playing: Boolean) {
    // Each turns at its own speed while music plays and holds its angle while paused.
    val anchor = rememberLoopingPhase(80_000, running = playing)
    val fast = rememberLoopingPhase(40_000, running = playing)
    val slow = rememberLoopingPhase(60_000, running = playing)
    val filter = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.8f) }) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = 1.7f; scaleY = 1.7f }.blur(32.dp)) {
            CoverImage(url, Modifier.fillMaxSize().graphicsLayer { rotationZ = -360f * anchor.value }.blur(100.dp), 160, filter)
            CoverImage(
                url,
                Modifier.fillMaxSize().graphicsLayer { rotationZ = 360f * fast.value; alpha = 0.6f }.blur(120.dp),
                160,
                filter,
                Alignment.TopStart,
            )
            CoverImage(
                url,
                Modifier.fillMaxSize().graphicsLayer { rotationZ = 360f * slow.value; alpha = 0.5f }.blur(120.dp),
                160,
                filter,
                Alignment.BottomEnd,
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.25f)))))
    }
}

// ---- covers --------------------------------------------------------------------------------------

/**
 * The full-bleed cover: it fills its box from the top and fades out at its foot (the phone's
 * hero fade, from 78% down), and towards [fadeEnd] when it sits beside lyrics or the queue.
 */
@Composable
fun HeroCover(url: String?, modifier: Modifier = Modifier, fadeEnd: Boolean = false, fadeSides: Boolean = false, hidden: Boolean = false) {
    val foot = remember { heroFadeBrush(vertical = true) }
    val side = remember { heroFadeBrush(vertical = false) }
    val sides = remember {
        Brush.horizontalGradient(0f to Color.Transparent, 0.14f to Color.Black, 0.86f to Color.Black, 1f to Color.Transparent)
    }
    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(foot, blendMode = BlendMode.DstIn)
                if (fadeEnd) drawRect(side, blendMode = BlendMode.DstIn)
                if (fadeSides) drawRect(sides, blendMode = BlendMode.DstIn)
            },
    ) {
        if (hidden) HiddenArtwork(Modifier.fillMaxSize(), markSize = 160.dp) else CoverImage(url, Modifier.fillMaxSize(), 1200)
    }
}

/** The phone's "Hide player thumbnail": the Convx mark on a plain tile where the cover would be. */
@Composable
fun HiddenArtwork(modifier: Modifier = Modifier, markSize: Dp = 120.dp) {
    Box(modifier.background(ConvxColors.CardSecondary), contentAlignment = Alignment.Center) {
        ConvxIcon("convx_logo", tint = Color.White.copy(alpha = 0.7f), size = markSize)
    }
}

/** Opaque until 78%, then an eased fade to nothing, as the phone's heroFadeOutBrush. */
private fun heroFadeBrush(vertical: Boolean, start: Float = 0.78f, steps: Int = 24): Brush {
    val stops = ArrayList<Pair<Float, Color>>(steps + 2)
    stops += 0f to Color.Black
    stops += start to Color.Black
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val eased = t * t * (3f - 2f * t)
        stops += (start + (1f - start) * t) to Color.Black.copy(alpha = 1f - eased)
    }
    return if (vertical) Brush.verticalGradient(*stops.toTypedArray()) else Brush.horizontalGradient(*stops.toTypedArray())
}

/** How the classic designs shape and move their cover: a card, a turning record, a turning clover. */
enum class CoverStyle(val spins: Boolean, val artSpins: Boolean) {
    Card(false, true),
    Vinyl(true, true),
    Clover(true, false),
}

fun PlayerDesign.coverStyle(): CoverStyle = when (this) {
    PlayerDesign.Vinyl -> CoverStyle.Vinyl
    PlayerDesign.Clover -> CoverStyle.Clover
    else -> CoverStyle.Card
}

/** One turn every 20 seconds, from the frame clock so every copy of the cover agrees on the angle. */
@Composable
fun rememberCoverRotation(style: CoverStyle, playing: Boolean): State<Float> {
    val angle = remember { mutableFloatStateOf(0f) }
    if (playing && style.spins && UiState.windowShown) {
        LaunchedEffect(Unit) {
            while (true) {
                val frame = awaitPacedFrameMillis()
                angle.floatValue = (frame % CoverTurnMillis) / CoverTurnMillis.toFloat() * 360f
            }
        }
    }
    return angle
}

private const val CoverTurnMillis = 20_000L

/** A record's grooves over the cover: a black ring leaves the art in the middle as the label. */
@Composable
fun VinylGrooves(modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Color.Black.copy(alpha = 0.82f), radius = r * 0.67f, center = c, style = Stroke(width = r * 0.66f))
        for (i in 1..7) {
            drawCircle(Color.White.copy(alpha = 0.05f), radius = r * (0.40f + i * 0.083f), center = c, style = Stroke(width = 1.dp.toPx()))
        }
        drawCircle(Color.Black, radius = r * 0.055f, center = c)
    }
}

/**
 * Material 3's Clover8Leaf: eight rounded lobes. Drawn as a smooth curve through the polar radius
 * rather than Material's polygon, which looks the same at cover size.
 */
object CloverShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(cx, cy)
        val steps = 240
        for (i in 0..steps) {
            val t = i / steps.toFloat() * 2f * PI.toFloat()
            // Lobes peak at the full radius and dip to ~0.8 between them, with round tips.
            val lobe = (cos(8f * t) + 1f) / 2f
            val r = radius * (0.80f + 0.20f * lobe.pow(0.6f))
            val x = cx + r * cos(t - PI.toFloat() / 2f)
            val y = cy + r * sin(t - PI.toFloat() / 2f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

fun CoverStyle.shape(corner: androidx.compose.ui.unit.Dp): Shape = when (this) {
    CoverStyle.Card -> RoundedCornerShape(corner)
    CoverStyle.Vinyl -> CircleShape
    CoverStyle.Clover -> CloverShape
}
