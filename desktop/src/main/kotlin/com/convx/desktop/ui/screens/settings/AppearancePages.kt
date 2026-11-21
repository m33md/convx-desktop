/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.GlassSettings
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.glass.GlassConfig
import com.convx.desktop.ui.glass.GlassShapes
import com.convx.desktop.ui.glass.GlassTokens
import com.convx.desktop.ui.glass.chromeShadow
import com.convx.desktop.ui.glass.glassSaturation
import com.convx.desktop.ui.glass.liquidGlass
import com.convx.desktop.ui.glass.of
import com.convx.desktop.ui.shapes.ContinuousRoundedRectangle
import com.convx.desktop.ui.theme.AppFont
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.roundToInt

/** Blur strength's 100%, in dp. */
private const val MaxBlur = 20f

@Composable
fun AppearanceHub() {
    val settings by AppSettings.state.collectAsState()
    SettingsSection("Theme & colors", footer = "Convx keeps its red on black everywhere; pages don't take on a cover's colours.") {
        LinkRow("Liquid Glass", "Blur, lens and highlights, with a live preview", icon = "palette", tint = SystemColors.Cyan) {
            SettingsNav.push(SettingsPage.LiquidGlass)
        }
        RowDivider()
        val presets by com.convx.desktop.data.Presets.all.collectAsState()
        androidx.compose.runtime.LaunchedEffect(Unit) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.convx.desktop.data.Presets.refresh() }
        }
        LinkRow(
            "Presets",
            "Save, apply and share complete looks",
            icon = "palette",
            tint = SystemColors.Pink,
            value = presets.size.takeIf { it > 0 }?.toString(),
        ) { SettingsNav.push(SettingsPage.Presets) }
    }
    SectionGap()
    SettingsSection("App") {
        LinkRow(
            "Font",
            "App typeface",
            icon = "alphabet_cyrillic",
            tint = SystemColors.Gray,
            value = if (settings.customFontEnabled && settings.customFont.isNotBlank()) "Custom" else AppFont.of(settings.appFont).label,
        ) { SettingsNav.push(SettingsPage.Font) }
        RowDivider()
        LinkRow("Layout & home", "Default tab and home", icon = "sliders", tint = SystemColors.Green) { SettingsNav.push(SettingsPage.LayoutHome) }
        RowDivider()
        LinkRow("Home feed order", "Arrange home sections", icon = "sliders", tint = SystemColors.Orange) { SettingsNav.push(SettingsPage.HomeFeedOrder) }
    }
}

/**
 * The phone's Liquid Glass page: a live sample of the glass on top, blur strength under it, and
 * the advanced dials behind a switch. Every glass surface in the app follows these.
 */
@Composable
fun LiquidGlassSettings() {
    val settings by AppSettings.state.collectAsState()
    val glass = settings.glass
    var advanced by rememberSaveable { mutableStateOf(false) }
    fun update(transform: (GlassSettings) -> GlassSettings) = AppSettings.update { it.copy(glass = transform(it.glass)) }

    GlassPreview(GlassConfig.of(glass))
    SectionGap()
    SettingsSection(footer = "Drag the glass in the preview to see what it does to what's under it.") {
        SliderRow("Blur strength", percent(glass.blurRadius / MaxBlur), glass.blurRadius, 0f..MaxBlur) { v ->
            update { it.copy(blurRadius = v) }
        }
        RowDivider(PlainInset)
        SwitchRow("Button motion", "Glass buttons swell under the pointer and spring back when you let go. Off saves a little CPU.", glass.buttonMotion) { v ->
            update { it.copy(buttonMotion = v) }
        }
        RowDivider(PlainInset)
        SwitchRow("Advanced glass settings", "Vibrancy, lens, tint and the light rim.", advanced) { advanced = it }
    }

    AnimatedVisibility(
        visible = advanced,
        enter = expandVertically(tween(240)) + fadeIn(tween(200)),
        exit = shrinkVertically(tween(180)) + fadeOut(tween(140)),
    ) {
        Column {
            SectionGap()
            SettingsSection("Effects") {
                SliderRow("Vibrancy", "%.1f×".format(glassSaturation(glass.vibrancy)), glass.vibrancy, 0f..2f, "How much richer colours look through the glass.") { v ->
                    update { it.copy(vibrancy = v) }
                }
                RowDivider(PlainInset)
                SliderRow("Lens height", percent(glass.lensHeight), glass.lensHeight, 0f..1f, "How far in from the edge the glass bends light.") { v ->
                    update { it.copy(lensHeight = v) }
                }
                RowDivider(PlainInset)
                SliderRow("Lens amount", percent(glass.lensAmount), glass.lensAmount, 0f..1f, "How strongly it bends it.") { v ->
                    update { it.copy(lensAmount = v) }
                }
                RowDivider(PlainInset)
                SwitchRow("Chromatic aberration", "Split colours a little at the edges, like real glass.", glass.chromaticAberration) { v ->
                    update { it.copy(chromaticAberration = v) }
                }
                RowDivider(PlainInset)
                SwitchRow("Depth effect", "Bend more toward the corners, so the glass looks thicker.", glass.depthEffect) { v ->
                    update { it.copy(depthEffect = v) }
                }
            }
            SectionGap()
            SettingsSection("Appearance") {
                SliderRow("Tint", percent(glass.surfaceOpacity), glass.surfaceOpacity, 0f..1f, "How dark the glass is.") { v ->
                    update { it.copy(surfaceOpacity = v) }
                }
                RowDivider(PlainInset)
                SliderRow("Rim light", percent(glass.highlightOpacity / 0.66f), glass.highlightOpacity, 0f..0.66f, "The light catching the glass' edge.") { v ->
                    update { it.copy(highlightOpacity = v) }
                }
            }
        }
    }

    SectionGap()
    SettingsSection {
        ButtonRow("Reset to the defaults", color = SystemColors.Red, enabled = glass != GlassSettings()) {
            update { GlassSettings() }
        }
    }
}

private fun percent(fraction: Float) = "${(fraction * 100).roundToInt().coerceIn(0, 100)}%"

/**
 * A sample of the glass with [config]: the top bar's capsule over the playing song's cover (or
 * Convx's colours), which you can drag around to watch it blur and bend what's under it.
 */
@Composable
private fun GlassPreview(config: GlassConfig) {
    val player by PlayerController.state.collectAsState()
    val cover = player.current?.thumbnail
    val backdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    var drag by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(220.dp).clip(ContinuousRoundedRectangle(26.dp)).background(Color.Black),
    ) {
        val barWidth = 300.dp
        val barHeight = 56.dp
        val maxX = with(density) { ((maxWidth - barWidth) / 2).toPx().coerceAtLeast(0f) }
        val maxY = with(density) { ((maxHeight - barHeight) / 2 - 12.dp).toPx().coerceAtLeast(0f) }

        // What the glass sees.
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            if (cover != null) {
                Artwork(cover, Modifier.fillMaxSize(), size = 1200, shape = androidx.compose.ui.graphics.RectangleShape, contentScale = ContentScale.Crop)
            } else {
                Row(Modifier.fillMaxSize()) {
                    listOf(ConvxColors.AccentRed, Color(0xFFFF9F0A), Color(0xFF0A84FF), Color(0xFFBF5AF2), Color(0xFF30D158)).forEach { c ->
                        Box(
                            Modifier.weight(1f).fillMaxHeight()
                                .background(Brush.verticalGradient(listOf(c, c.copy(alpha = 0.35f), Color.Black))),
                        )
                    }
                }
            }
            Text(
                "Convx",
                fontSize = 92.sp,
                fontWeight = FontWeight.Black,
                color = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // The glass: the top bar's capsule with its three buttons.
        Row(
            Modifier
                .align(Alignment.Center)
                .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
                .width(barWidth)
                .height(barHeight)
                .chromeShadow(GlassShapes.Capsule)
                .clip(GlassShapes.Capsule)
                .liquidGlass(GlassShapes.Capsule, config, highlightAlpha = GlassTokens.PluckAlphaNeutral, backdrop = backdrop)
                .pointerInput(maxX, maxY) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        drag = Offset(
                            (drag.x + amount.x).coerceIn(-maxX, maxX),
                            (drag.y + amount.y).coerceIn(-maxY, maxY),
                        )
                    }
                },
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConvxIcon("sf_house_fill", tint = ConvxColors.AccentRed, size = 24.dp)
            ConvxIcon("search", tint = GlassTokens.Icon, size = 22.dp)
            ConvxIcon("sf_music_note_square_stack", tint = GlassTokens.Icon, size = 24.dp)
        }

        Text(
            "Preview",
            style = ConvxType.CaptionStrong,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
        )
    }
}
