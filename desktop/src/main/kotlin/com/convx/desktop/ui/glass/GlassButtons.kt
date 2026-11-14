/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalContentColor
import com.kyant.backdrop.Backdrop

/** A true circle: the phone keeps square glass round rather than giving it G2 corners. */
val GlassCircleShape = RoundedCornerShape(percent = 50)

/**
 * The phone's chromeShadow: one wide, soft drop under floating top chrome, 24dp nudged 4dp
 * down at 18%, so the glass reads as lifted off the page.
 */
fun Modifier.chromeShadow(shape: Shape): Modifier = dropShadow(
    shape,
    Shadow(radius = 24.dp, color = Color.Black, offset = DpOffset(0.dp, 4.dp), alpha = 0.18f),
)

/**
 * The phone's GlassCircleButton: a round drop of liquid glass with its icon composited into the
 * material, the soft chrome shadow, the pointer's light, and the elastic stretch that swells it
 * under a press and pulls it after a drag.
 */
@Composable
fun GlassCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    enabled: Boolean = true,
    // Photos (the account picture) must not be added onto the glass: Plus would wash them out.
    applyAdditiveChrome: Boolean = true,
    content: @Composable () -> Unit,
) {
    val glow = rememberInteractiveHighlight()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .graphicsLayer(elasticStretch(glow))
            .size(size)
            .chromeShadow(GlassCircleShape)
            .clip(GlassCircleShape)
            .liquidGlass(GlassCircleShape, highlightAlpha = GlassTokens.PluckAlphaNeutral)
            .then(glow.modifier)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .then(if (enabled) glow.gestureModifier else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides GlassTokens.Title) {
            Box(Modifier.additiveChrome(applyAdditiveChrome), contentAlignment = Alignment.Center) { content() }
        }
    }
}

/**
 * Any control made of the glass: the material, or [fill] for the white and red pills that keep
 * their paint, then the pointer's light and the swell and lean that answer hover and press, as on
 * GlassCircleButton. The motion is a couple of dp, so the target stays under the pointer. The
 * caller adds its own click handling after this.
 */
@Composable
fun Modifier.glassControl(
    shape: CornerBasedShape,
    glow: InteractiveHighlight = rememberInteractiveHighlight(),
    fill: Color? = null,
    enabled: Boolean = true,
    magnitude: Float = 1f,
    shadow: Boolean = false,
    highlightAlpha: Float = GlassTokens.PluckAlphaNeutral,
    /** The lit and dark rims; small discs among plain glyphs read cleaner without them. */
    rims: Boolean = true,
    backdrop: Backdrop? = LocalAppBackdrop.current,
): Modifier {
    val stretch = remember(glow, magnitude) { elasticStretch(glow, magnitude) }
    val tuned = LocalGlassConfig.current
    val surface = if (fill != null) {
        Modifier.background(fill)
    } else {
        // With nothing to refract it keeps the light wash these controls always had, now with the rim.
        Modifier.liquidGlass(shape, if (backdrop == null) tuned.onPage() else tuned, highlightAlpha = highlightAlpha, applyEdgeEffects = rims, backdrop = backdrop)
    }
    return this
        .graphicsLayer(stretch)
        .then(if (shadow) Modifier.chromeShadow(shape) else Modifier)
        .clip(shape)
        .then(surface)
        .then(glow.modifier)
        .then(if (enabled) glow.gestureModifier else Modifier)
}
