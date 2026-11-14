/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.flow.collectLatest
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/*
 * The phone's liquid glass (ui/component/GlassEffect.kt and theme/GlassTintTokens.kt),
 * dark appearance, with the values the phone resolves from its preference defaults
 * (AppGlassConfig.kt). Every surface runs the same effect chain on the same shaders:
 * saturation, blur, lens refraction, a specular rim, a soft shadow and a dark outer rim.
 */

/** The phone's GlassTintTokens, dark half: the desktop app is dark only, as the phone is on dark routes. */
object GlassTokens {
    /** Glass base in the dark: a white lift, carried at [BaseSurfaceOpacity]. */
    val BaseColor = Color.White
    const val BaseSurfaceOpacity = 0.15f

    /** Default surface tint and its opacity slider value (DefaultSurfaceDark, 0.5). */
    val SurfaceTint = Color(0xFF171717)
    const val SurfaceOpacity = 0.50f

    /** Specular rim (DefaultHighlightDark at its 0.33 opacity setting). */
    val Highlight = Color(0xFFE8E8E8)
    const val HighlightOpacity = 0.33f

    /** Pluck (rim) alphas when a surface takes the default rim strength. */
    const val PluckAlphaNeutral = 0.30f
    const val PluckAlphaHero = 0.48f

    /** The nav puck's wash (DefaultPuckDark) and its rest opacity. */
    val Puck = Color(0xFF2E2E2E)
    const val PuckOpacity = 0.80f

    /** Glyph and label tiers on glass. */
    const val IconDimOpacity = 0.85f
    const val TitleDimOpacity = 0.92f
    const val UnselectedTabLabelOpacity = 0.75f
    const val SecondaryLabelAlpha = 0.60f
    val Icon = Color(0xFFEBEBF5).copy(alpha = IconDimOpacity)
    val Title = Color.White.copy(alpha = TitleDimOpacity)

    /** iOS systemGray: unselected side tab labels. */
    val InactiveTab = Color(0xFF8E8E93)
}

/** The phone's GlassEffectConfig with its preference defaults. */
@Immutable
data class GlassConfig(
    val vibrancy: Float = 2.0f,
    /** dp. */
    val blurRadius: Float = 3f,
    /** 0..1 of [LensMaxDp]. */
    val lensHeight: Float = 0.60f,
    /** 0..1 of [LensMaxDp]. */
    val lensAmount: Float = 0.69f,
    val chromaticAberration: Boolean = false,
    val depthEffect: Boolean = false,
    val surfaceTint: Color = GlassTokens.SurfaceTint,
    val surfaceOpacity: Float = GlassTokens.SurfaceOpacity,
    val highlightColor: Color = GlassTokens.Highlight,
    val highlightOpacity: Float = GlassTokens.HighlightOpacity,
) {
    /** The side panel's own tuning: lighter glass, a 75% black tint. */
    fun forSidePanel(): GlassConfig = copy(
        vibrancy = 1.2f,
        blurRadius = 2f,
        lensHeight = 0.4f,
        lensAmount = 0.6f,
        surfaceTint = Color.Black,
        surfaceOpacity = 0.75f,
    )

    /** Glass inside the pages, which have nothing under them to refract: a light wash instead of the dark tint. */
    fun onPage(): GlassConfig = copy(surfaceTint = Color.White, surfaceOpacity = 0.10f)

    companion object {
        val Default = GlassConfig()
        val SidePanel = Default.forSidePanel()
    }
}

/** The refraction shader renders wrongly on macOS, so glass there is the flat tint and rim. */
internal val IsMac: Boolean = System.getProperty("os.name").lowercase().contains("mac")

/** The glass the user tuned in Settings → Appearance → Liquid Glass; every glass surface reads it. */
val LocalGlassConfig = androidx.compose.runtime.compositionLocalOf { GlassConfig.Default }

/** The tuned glass, from its saved dials. */
fun GlassConfig.Companion.of(settings: com.convx.desktop.data.GlassSettings): GlassConfig = GlassConfig(
    vibrancy = settings.vibrancy,
    blurRadius = settings.blurRadius,
    lensHeight = settings.lensHeight,
    lensAmount = settings.lensAmount,
    chromaticAberration = settings.chromaticAberration,
    depthEffect = settings.depthEffect,
    surfaceOpacity = settings.surfaceOpacity,
    highlightOpacity = settings.highlightOpacity,
)

/** Lens height and amount at a setting of 1. */
const val LensMaxDp = 48f

/** Width and default strength of the light rim; the 45° light is the phone's frozen angle. */
val EdgeHighlightWidth = 0.8.dp
const val EdgeHighlightAlpha = 0.55f
const val HighlightAngle = 45f

/** The dark outer rim that keeps glass edges visible on busy content. */
private val DarkRimWidth = 1.6.dp
private const val DarkRimAlpha = 0.32f

private const val GlassFadeMillis = 280

/** Maps the 0..2 vibrancy setting to a saturation multiplier. */
fun glassSaturation(vibrancy: Float): Float = 1f + 0.5f * vibrancy.coerceIn(0f, 2f)

/** What glass refracts: the app's content, recorded by the shell. Null draws a plain tinted surface. */
val LocalAppBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * What menus refract. Menus open in their own layer above everything, so unlike glass in the
 * chrome they may sample the pages they open over; null where that would show the wrong thing
 * (over the full player, whose own background is not in the recording).
 */
val LocalMenuBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** The pages under the full player, for its Glass background only; null everywhere else. */
val LocalPagesBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * Fast scrolling. While the pages fly past, glass over them can't be seen refracting anything, so
 * every surface over the pages turns to its plain tint and the pages stop being drawn again under
 * each one. Slow scrolling keeps the glass; it comes back as soon as the scroll slows.
 */
object GlassMotion {
    /** True while the pages scroll faster than glass is worth drawing. */
    var fast by mutableStateOf(false)
        private set

    /** Wheel notches a second (a touchpad's small steps add up the same way), fading as time passes. */
    private var rate = 0f
    private var lastNanos = 0L

    /** Notches a second above which the glass turns flat, and below which it comes back. */
    private const val FastRate = 14f
    private const val SlowRate = 6f
    private const val WindowSeconds = 0.25f

    /** A wheel or touchpad step of [notches] over the pages. */
    fun onScroll(notches: Float) {
        // On a Mac the glass stays glass while the pages scroll under it.
        if (IsMac) return
        val now = System.nanoTime()
        rate = rateAt(now) + notches / WindowSeconds
        lastNanos = now
        if (!fast && rate > FastRate) fast = true
    }

    /** Waits for the scroll to slow, then brings the glass back. */
    suspend fun settle() {
        while (fast) {
            kotlinx.coroutines.delay(60)
            val now = System.nanoTime()
            // The wheel's smooth scroll runs on for a moment after its last step.
            if (rateAt(now) < SlowRate && now - lastNanos > 200_000_000L) fast = false
        }
    }

    private fun rateAt(now: Long): Float {
        if (lastNanos == 0L) return 0f
        val seconds = (now - lastNanos) / 1e9f
        return rate * exp(-seconds / WindowSeconds)
    }
}

/** How far a glass surface has turned into its plain tint for [GlassMotion.fast]: 0 glass, 1 flat. */
@Composable
private fun rememberFlatness(): State<Float> {
    val flat = remember { Animatable(if (GlassMotion.fast) 1f else 0f) }
    LaunchedEffect(Unit) {
        snapshotFlow { GlassMotion.fast }.collectLatest { fast ->
            flat.animateTo(if (fast) 1f else 0f, tween(if (fast) FlatInMillis else FlatOutMillis))
        }
    }
    return flat.asState()
}

private const val FlatInMillis = 140
private const val FlatOutMillis = 260

/** The tint's opacity once flat: dense enough that the sharp pages under it read as a dark panel. */
private const val FlatTintAlpha = 0.9f

/** The phone's menu material: its glass with a charcoal tint at 82% and a softer blur. */
val MenuGlass = GlassConfig.Default.copy(surfaceTint = Color(0xFF1C1C1E), surfaceOpacity = 0.82f, blurRadius = 8f)

/**
 * A liquid glass surface over [backdrop], the phone's Modifier.liquidGlass. Callers clip
 * to [shape] first and add their own elevation shadow, as the phone's call sites do.
 *
 * [highlightAlpha] is the component's rim strength; [hero] marks glass over artwork-tinted
 * pages, which takes the stronger default rim.
 */
@Composable
fun Modifier.liquidGlass(
    shape: CornerBasedShape,
    config: GlassConfig = LocalGlassConfig.current,
    highlightAlpha: Float = EdgeHighlightAlpha,
    applyEdgeEffects: Boolean = true,
    outerShadow: Boolean = true,
    hero: Boolean = false,
    backdrop: Backdrop? = LocalAppBackdrop.current,
): Modifier {
    val tintAlpha = (config.surfaceTint.alpha * config.surfaceOpacity).coerceIn(0f, 1f)
    // A configured tint carries the chosen opacity on its own; the base lift only shows without one.
    val baseAlpha = if (config.surfaceTint.isSpecified) 0f else GlassTokens.BaseSurfaceOpacity
    val fade = tween<Color>(GlassFadeMillis)
    val base by animateColorAsState(GlassTokens.BaseColor.copy(alpha = baseAlpha), fade)
    val tint by animateColorAsState(config.surfaceTint.copy(alpha = tintAlpha), fade)

    if (backdrop == null) {
        // Nothing to refract (glass inside the pages, which are what the chrome refracts): the same
        // tint with the same lit rim and dark rim, so it still reads as the material.
        val flatRimAlpha = if (applyEdgeEffects) {
            val pluck = when {
                highlightAlpha != EdgeHighlightAlpha -> highlightAlpha
                hero -> GlassTokens.PluckAlphaHero
                else -> GlassTokens.PluckAlphaNeutral
            }
            (pluck * (config.highlightOpacity / EdgeHighlightAlpha)).coerceIn(0f, 1f)
        } else {
            0f
        }
        val rimColor = config.highlightColor
        val flatDarkRim = (DarkRimAlpha * (config.highlightOpacity / EdgeHighlightAlpha)).coerceIn(0f, 1f)
        return this
            .drawWithCache {
                val outline = if (flatRimAlpha > 0f) shape.createOutline(size, layoutDirection, this) else null
                val rim = Brush.linearGradient(
                    0f to rimColor.copy(alpha = flatRimAlpha),
                    0.5f to rimColor.copy(alpha = flatRimAlpha * 0.25f),
                    1f to rimColor.copy(alpha = flatRimAlpha * 0.6f),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
                val stroke = Stroke(width = EdgeHighlightWidth.toPx() * 1.5f)
                onDrawWithContent {
                    if (base.alpha > 0f) drawRect(base)
                    if (tint.alpha > 0f) drawRect(tint)
                    outline?.let { drawOutline(it, rim, style = stroke) }
                    drawContent()
                }
            }
            .then(if (applyEdgeEffects && flatDarkRim > 0f) Modifier.outerDarkRim(shape, DarkRimWidth, flatDarkRim, HighlightAngle) else Modifier)
    }

    val density = LocalDensity.current
    val blurPx = with(density) { config.blurRadius.dp.toPx() }
    val saturation = glassSaturation(config.vibrancy)
    val lensHeightPx = with(density) { (config.lensHeight * LensMaxDp).dp.toPx() }
    val lensAmountPx = with(density) { (config.lensAmount * LensMaxDp).dp.toPx() }

    val flatness = rememberFlatness()
    val flat = remember { derivedStateOf { flatness.value >= 1f } }
    val effects: BackdropEffectScope.() -> Unit = remember(
        saturation, blurPx, lensHeightPx, lensAmountPx, applyEdgeEffects, config.depthEffect, config.chromaticAberration,
    ) {
        glass@{
            // Flat, nothing is refracted, so there is nothing to blur or bend.
            if (flat.value) return@glass
            if (saturation != 1f) colorControls(saturation = saturation)
            if (blurPx > 0f) blur(blurPx)
            if (applyEdgeEffects && (lensHeightPx > 0f || lensAmountPx > 0f)) {
                lens(
                    refractionHeight = lensHeightPx,
                    refractionAmount = lensAmountPx,
                    depthEffect = config.depthEffect,
                    chromaticAberration = config.chromaticAberration,
                )
            }
        }
    }

    val baseHighlightAlpha = when {
        highlightAlpha != EdgeHighlightAlpha -> highlightAlpha
        hero -> GlassTokens.PluckAlphaHero
        else -> GlassTokens.PluckAlphaNeutral
    }
    val rimAlpha = (baseHighlightAlpha * (config.highlightOpacity / EdgeHighlightAlpha)).coerceIn(0f, 1f)
    val rim by animateColorAsState(config.highlightColor.copy(alpha = rimAlpha), fade)
    val highlight: (() -> Highlight?)? = remember(applyEdgeEffects) {
        if (applyEdgeEffects) {
            {
                Highlight(
                    width = EdgeHighlightWidth,
                    style = HighlightStyle.Default(color = rim, angle = HighlightAngle),
                )
            }
        } else {
            null
        }
    }
    val shadow: (() -> Shadow?)? = remember(applyEdgeEffects, outerShadow) {
        if (applyEdgeEffects && outerShadow) ({ Shadow.Default }) else null
    }
    // Read in the draw phase, so the light/dark fade never recomposes or rebuilds the effects.
    val surface: DrawScope.() -> Unit = remember {
        {
            if (base.alpha > 0f) drawRect(base)
            val f = flatness.value
            val shown = if (f > 0f) tint.copy(alpha = lerp(tint.alpha, maxOf(tint.alpha, FlatTintAlpha), f)) else tint
            if (shown.alpha > 0f) drawRect(shown)
        }
    }
    // The pages are drawn under the glass only while it isn't fully flat.
    val onDrawBackdrop: DrawScope.(DrawScope.() -> Unit) -> Unit = remember { { draw -> if (flatness.value < 1f) draw() } }
    val shapeBlock: () -> Shape = remember(shape) { { shape } }
    val darkRimAlpha = (DarkRimAlpha * (config.highlightOpacity / EdgeHighlightAlpha)).coerceIn(0f, 1f)

    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = shapeBlock,
            effects = effects,
            highlight = highlight,
            shadow = shadow,
            onDrawBackdrop = onDrawBackdrop,
            onDrawSurface = surface,
        )
        .then(
            if (applyEdgeEffects && darkRimAlpha > 0f) {
                Modifier.outerDarkRim(shape, DarkRimWidth, darkRimAlpha, HighlightAngle)
            } else {
                Modifier
            },
        )
}

/**
 * A plain glass puck: the phone side bar's selection capsule, cut from the panel's own
 * frost with a strong lens and dispersion, a soft shadow and the puck wash, no rim.
 */
@Composable
fun Modifier.glassPuck(
    shape: CornerBasedShape,
    blurRadius: Dp,
    wash: Color = GlassTokens.Puck.copy(alpha = GlassTokens.PuckOpacity),
    shadowAlpha: Float = 0.35f,
    backdrop: Backdrop? = LocalAppBackdrop.current,
): Modifier {
    if (backdrop == null) return this.drawWithCache { onDrawWithContent { drawOutline(shape.createOutline(size, layoutDirection, this), wash); drawContent() } }
    val density = LocalDensity.current
    val blurPx = with(density) { blurRadius.toPx() }
    val heightPx = with(density) { 32.dp.toPx() }
    val amountPx = with(density) { 28.dp.toPx() }
    val flatness = rememberFlatness()
    val flat = remember { derivedStateOf { flatness.value >= 1f } }
    val effects: BackdropEffectScope.() -> Unit = remember(blurPx, heightPx, amountPx) {
        glass@{
            if (flat.value) return@glass
            blur(blurPx)
            lens(heightPx, amountPx, chromaticAberration = true)
        }
    }
    val shapeBlock: () -> Shape = remember(shape) { { shape } }
    return drawBackdrop(
        backdrop = backdrop,
        shape = shapeBlock,
        effects = effects,
        highlight = { null },
        shadow = { Shadow(alpha = shadowAlpha) },
        onDrawBackdrop = { draw -> if (flatness.value < 1f) draw() },
        onDrawSurface = { drawRect(wash) },
    )
}

/**
 * Dark counterpart to the specular rim (the phone's ModifierExt.outerDarkRim): a thin black
 * outline on the surface's own edge, unclipped so half sits outside, drawn once per diagonal
 * axis so each side swells and slims instead of a flat line.
 */
fun Modifier.outerDarkRim(
    shape: Shape,
    width: Dp = DarkRimWidth,
    alpha: Float = DarkRimAlpha,
    angle: Float = HighlightAngle,
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val stroke = Stroke(width = width.toPx())
    val center = Offset(size.width / 2f, size.height / 2f)
    fun flankBrush(axisDeg: Float): Brush {
        val a = axisDeg * (PI / 180f).toFloat()
        val dir = Offset(cos(a), sin(a))
        val reach = dir * (abs(dir.x) * size.width + abs(dir.y) * size.height) / 2f
        return Brush.linearGradient(
            0f to Color.Black.copy(alpha = alpha),
            0.5f to Color.Transparent,
            1f to Color.Black.copy(alpha = alpha),
            start = center - reach,
            end = center + reach,
        )
    }
    val first = flankBrush(angle)
    val second = flankBrush(angle + 90f)
    onDrawWithContent {
        drawContent()
        drawOutline(outline, first, style = stroke)
        drawOutline(outline, second, style = stroke)
    }
}

/**
 * Glyphs on dark glass are composited additively, as the phone's flamingoChrome: the label
 * lifts what is behind it, so it reads as part of the material rather than ink on top.
 */
fun Modifier.additiveChrome(enabled: Boolean = true, strength: Float = 1f, passes: Int = 1, bleed: Dp = 0.dp): Modifier =
    if (!enabled) this else drawWithCache {
        val layer = obtainGraphicsLayer().apply {
            blendMode = BlendMode.Plus
            alpha = strength
        }
        val pad = bleed.toPx()
        val layerSize = IntSize(
            (size.width + 2 * pad).toInt().coerceAtLeast(1),
            (size.height + 2 * pad).toInt().coerceAtLeast(1),
        )
        onDrawWithContent {
            layer.record(layerSize) { translate(pad, pad) { this@onDrawWithContent.drawContent() } }
            translate(-pad, -pad) { repeat(passes) { drawLayer(layer) } }
        }
    }

/** Shared shapes: the side panel's corner and the capsule every pill and row uses. */
object GlassShapes {
    val Panel = RoundedCornerShape(20.dp)
    val Capsule = RoundedCornerShape(percent = 50)
}
