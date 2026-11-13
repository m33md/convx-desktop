/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import com.convx.desktop.ui.glass.GlassShapes
import com.convx.desktop.ui.glass.LocalMenuBackdrop
import com.convx.desktop.ui.glass.MenuGlass
import com.convx.desktop.ui.glass.glassControl
import com.convx.desktop.ui.glass.liquidGlass
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxShapes
import com.convx.desktop.ui.theme.ConvxType

/** Hover + press feedback shared by every clickable surface: a hand cursor and a tiny squeeze. */
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
    pressScale: Float = 0.96f,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) pressScale else 1f)
    // The squeeze is drawn inside the hit area, so a press near the edge doesn't shrink out
    // from under the pointer and cancel itself.
    return this
        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
        .scale(scale)
}

/** Right-click (or the context-menu key path) reports where it happened. */
fun Modifier.onSecondaryClick(onClick: (Offset) -> Unit): Modifier = pointerInput(onClick) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.firstOrNull()?.let { onClick(it.position) }
            }
        }
    }
}

@Composable
fun Tooltip(text: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            // The menus' glass where there's a page under it to frost; the old grey card elsewhere.
            val backdrop = LocalMenuBackdrop.current
            Box(
                Modifier
                    .clip(ConvxShapes.Control)
                    .then(
                        if (backdrop != null) {
                            Modifier.liquidGlass(ConvxShapes.Control, MenuGlass, highlightAlpha = 0.20f, outerShadow = false, backdrop = backdrop)
                        } else {
                            Modifier.background(Color(0xFF2C2C2E)).border(0.5.dp, ConvxColors.Separator, ConvxShapes.Control)
                        },
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(text, style = ConvxType.Caption, color = Color.White)
            }
        },
        delayMillis = 550,
        tooltipPlacement = BesideCursor,
        content = content,
    )
}

/**
 * Under the cursor, or above it where the window ends, and never over it. Compose's own cursor
 * placement shifts a tip that doesn't fit below up by its height only, which puts it on the
 * pointer near the bottom of the window (the player bar); the tip then takes the click.
 */
private object BesideCursor : TooltipPlacement {
    private val Below = 18.dp
    private val Above = 8.dp
    private val Margin = 4.dp

    @Composable
    override fun positionProvider(cursorPosition: Offset): PopupPositionProvider {
        val density = LocalDensity.current
        return remember(cursorPosition, density) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset = with(density) {
                    val margin = Margin.roundToPx()
                    val cursorX = anchorBounds.left + cursorPosition.x.roundToInt()
                    val cursorY = anchorBounds.top + cursorPosition.y.roundToInt()
                    var x = cursorX
                    if (x + popupContentSize.width > windowSize.width - margin) x = cursorX - popupContentSize.width - margin
                    var y = cursorY + Below.roundToPx()
                    if (y + popupContentSize.height > windowSize.height - margin) y = cursorY - Above.roundToPx() - popupContentSize.height
                    IntOffset(
                        x.coerceIn(margin, (windowSize.width - margin - popupContentSize.width).coerceAtLeast(margin)),
                        y.coerceAtLeast(margin),
                    )
                }
            }
        }
    }
}

/** Round glyph button; [filled] gives it the glass-wash disc the phone's GlassCircleButton has. */
@Composable
fun IconButton(
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
    tint: Color = Color.White,
    filled: Boolean = false,
    enabled: Boolean = true,
    active: Boolean = false,
    activeTint: Color = tint,
    tooltip: String? = null,
    /** A player control draws the image picked for it in Player icons, if any. */
    slot: PlayerIconSlot? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            filled -> Color.Transparent
            hovered && enabled -> Color.White.copy(alpha = 0.08f)
            else -> Color.Transparent
        },
    )
    val color = when {
        !enabled -> tint.copy(alpha = 0.3f)
        active -> activeTint
        hovered -> tint
        else -> tint.copy(alpha = if (filled) 1f else 0.86f)
    }
    val button = @Composable {
        Box(
            modifier
                .size(size)
                // The filled disc is a drop of the glass, with its light and swell; the plain glyph keeps the quiet wash.
                .then(
                    if (filled) {
                        Modifier
                            // No rims: these discs sit in rows of plain glyphs (the player's top row), where a ringed one looked out of place.
                            .glassControl(GlassShapes.Capsule, enabled = enabled, rims = false)
                            .hoverable(interaction)
                            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
                    } else {
                        Modifier
                            .clip(CircleShape)
                            .background(bg)
                            .hoverable(interaction)
                            .pressable(onClick, enabled, interaction, pressScale = 0.9f)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (slot != null) PlayerGlyph(slot, icon, color, iconSize) else ConvxIcon(icon, tint = color, size = iconSize)
            if (active) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 2.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(activeTint),
                )
            }
        }
    }
    if (tooltip != null) Tooltip(tooltip) { button() } else button()
}

/** The big white disc with a black glyph: artist/album play buttons and the player bar. */
@Composable
fun PlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    color: Color = Color.White,
    glyph: Color = Color.Black,
    loading: Boolean = false,
    /** The player's own button: its glyphs follow Player icons, and a finished queue shows replay. */
    player: Boolean = false,
    ended: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered) 1.05f else 1f)
    Box(
        modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(color)
            .hoverable(interaction)
            .pressable(onClick, interaction = interaction, pressScale = 0.92f),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(color = glyph, strokeWidth = 2.5.dp, modifier = Modifier.size(size * 0.42f))
        } else if (player) {
            val slot = when {
                ended -> PlayerIconSlot.REPLAY
                isPlaying -> PlayerIconSlot.PAUSE
                else -> PlayerIconSlot.PLAY
            }
            AnimatedPlayGlyph(slot, glyph, size * 0.62f)
        } else {
            ConvxIcon(if (isPlaying) "pause_applemusic" else "play_applemusic", tint = glyph, size = size * 0.62f)
        }
    }
}

/** Capsule button: filled (white) or a quiet glass wash. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    filled: Boolean = false,
    accent: Color? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // The quiet pill is the glass itself; the white and red ones keep their paint and move like it.
    val fill = when {
        accent != null -> if (hovered) accent.copy(alpha = 0.9f) else accent
        filled -> if (hovered) Color.White.copy(alpha = 0.9f) else Color.White
        else -> null
    }
    val fg = if (filled || accent != null) Color.Black.takeIf { accent == null } ?: Color.White else Color.White
    Row(
        modifier
            .glassControl(GlassShapes.Capsule, fill = fill)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) ConvxIcon(icon, tint = fg, size = 16.dp)
        Text(text, style = ConvxType.SubheadStrong, color = fg, maxLines = 1)
    }
}

/** Filter chip row item, the phone's ChipsRow look. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .glassControl(GlassShapes.Capsule, fill = if (selected) Color.White else null)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, style = ConvxType.SubheadStrong, color = if (selected) Color.Black else Color.White, maxLines = 1)
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    /** A picture beside the title, as the phone's shelves show their artist or album; round for artists. */
    thumbnail: String? = null,
    roundThumbnail: Boolean = false,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        if (thumbnail != null) {
            Artwork(
                thumbnail,
                Modifier.align(Alignment.CenterVertically).padding(end = 12.dp).size(48.dp),
                size = 160,
                shape = if (roundThumbnail) CircleShape else ConvxShapes.Artwork,
                placeholderIcon = if (roundThumbnail) "person" else "music_note",
                iconSize = 20.dp,
            )
        }
        Column(Modifier.weight(1f)) {
            if (subtitle != null) Text(subtitle.uppercase(), style = ConvxType.CaptionStrong, color = ConvxColors.SecondaryLabel)
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (onAction != null) Modifier.hoverable(interaction).pressable(onAction, interaction = interaction) else Modifier,
            ) {
                Text(title, style = ConvxType.Title2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (onAction != null) {
                    ConvxIcon(
                        "chevron_right_px",
                        tint = if (hovered) Color.White else ConvxColors.SecondaryLabel,
                        size = 22.dp,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }
        if (trailing != null) trailing()
        else if (action != null && onAction != null) PillButton(action, onAction, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp))
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f), strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun MessageState(
    title: String,
    message: String? = null,
    icon: String = "error",
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 64.dp, horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ConvxIcon(icon, tint = ConvxColors.SecondaryLabel, size = 40.dp)
        Spacer(Modifier.height(14.dp))
        Text(title, style = ConvxType.Title3, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(6.dp))
            Text(message, style = ConvxType.Body, color = ConvxColors.SecondaryLabel, textAlign = TextAlign.Center, modifier = Modifier.width(420.dp))
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            PillButton(actionText, onAction, filled = true)
        }
    }
}

/** A 1px rim that fades from the top-left, the liquid-glass edge highlight. */
fun Modifier.glassRim(shape: Shape, alpha: Float = 0.16f): Modifier = border(
    width = 1.dp,
    brush = Brush.linearGradient(
        0f to Color.White.copy(alpha = alpha * 1.6f),
        0.45f to Color.White.copy(alpha = alpha * 0.35f),
        1f to Color.White.copy(alpha = alpha),
    ),
    shape = shape,
)

/** Translucent panel surface for the sidebar and side panels, over the ambient backdrop. */
fun Modifier.panel(shape: Shape = ConvxShapes.Panel, tint: Color = Color.Black, alpha: Float = 0.42f): Modifier =
    clip(shape).background(tint.copy(alpha = alpha)).glassRim(shape, 0.07f)

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(0.5.dp).background(ConvxColors.Separator))
}

@Composable
fun Dot() {
    Text(" • ", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
}
