/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.AppScope
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.PlayerDesign
import com.convx.desktop.data.PlayerIconOverride
import com.convx.desktop.player.PlayerController
import com.convx.desktop.player.PlayerState
import com.convx.desktop.ui.components.DialogCard
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.components.PlayerGlyph
import com.convx.desktop.ui.components.PlayerIconSlot
import com.convx.desktop.ui.components.PlayerIcons
import com.convx.desktop.ui.components.rememberPlayerIcon
import com.convx.desktop.ui.glass.GlassSwitch
import com.convx.desktop.ui.player.ProgressRow
import com.convx.desktop.ui.player.TransportControls
import com.convx.desktop.ui.shapes.ContinuousRoundedRectangle
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The phone's custom player icons: each control's glyph swapped for an image of your own. The
 * controls are drawn above the list at their real size, as a flower that charms at 96dp can be
 * unreadable at 32dp on the transport, and the only honest way to show that is to draw it there.
 */
@Composable
fun PlayerIconsSettings() {
    val settings by AppSettings.state.collectAsState()
    val icons by PlayerIcons.overrides.collectAsState()
    val v2 = settings.playerDesign == PlayerDesign.AppleMusicV2

    Text(
        "Click a control to reuse an image or browse for one. PNG, JPEG, WebP, GIF, BMP or SVG.",
        style = ConvxType.Subhead,
        color = ConvxColors.SecondaryLabel,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
    )
    ControlsPreview(v2, settings.playerDesign.classic, settings.seekBarStyle)
    SectionGap()
    SettingsSection(
        "Player controls",
        footer = "Play, pause, replay and the heart in the bar at the bottom follow these too. The seek bar handle shows " +
            "on the Card, Vinyl and Clover designs' Default, Wavy and Slim bars.",
    ) {
        PlayerIconSlot.entries.filter { !it.isV2 }.forEachIndexed { index, slot ->
            if (index > 0) RowDivider()
            SlotRow(slot, icons[slot.name])
        }
    }
    // As on the phone, Apple Music v2's own set only shows while it's the player in use.
    if (v2) {
        SectionGap()
        SettingsSection("V2 player icons", footer = "Customize the Apple Music v2 player's icons. Its volume icon shows with the volume bar on.") {
            PlayerIconSlot.entries.filter { it.isV2 }.forEachIndexed { index, slot ->
                if (index > 0) RowDivider()
                SlotRow(slot, icons[slot.name])
            }
        }
    }
}

/** The player's title actions, seek bar and transport at their real size, for the design in use. */
@Composable
private fun ControlsPreview(v2: Boolean, classic: Boolean, seekBar: com.convx.desktop.data.SeekBarStyle) {
    val playing by PlayerController.state.collectAsState()
    val library by Library.state.collectAsState()
    // Nothing playing: the last song played stands in, as on the design page.
    val state = remember(playing, library.history.firstOrNull()) {
        if (playing.current != null) playing
        else library.history.firstOrNull()?.song?.let { PlayerState(queue = listOf(it), index = 0) } ?: playing
    }
    val liked = state.current?.let { song -> library.liked.any { it.song.id == song.id } } ?: false
    Box(
        Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(26.dp))
            .background(Color(0xFF1C1C1E))
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 440.dp).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.current?.title ?: "Nothing playing",
                    style = ConvxType.Headline,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val like = when {
                    v2 && liked -> PlayerIconSlot.V2_LIKED
                    v2 -> PlayerIconSlot.V2_LIKE
                    liked -> PlayerIconSlot.LIKED
                    else -> PlayerIconSlot.LIKE
                }
                val more = if (v2) PlayerIconSlot.V2_MORE else PlayerIconSlot.MORE
                IconButton(like.fallback, {}, size = if (v2) 40.dp else 38.dp, iconSize = if (v2) 28.dp else 22.dp, filled = !v2, slot = like)
                Spacer(Modifier.width(if (v2) 4.dp else 8.dp))
                IconButton(more.fallback, {}, size = if (v2) 40.dp else 38.dp, iconSize = if (v2) 28.dp else 22.dp, filled = !v2, slot = more)
            }
            Spacer(Modifier.height(14.dp))
            ProgressRow(state, if (classic) seekBar else null, labelColor = Color.White.copy(alpha = 0.6f), labelsBelow = true)
            Spacer(Modifier.height(8.dp))
            TransportControls(state, compact = false, modifier = Modifier.align(Alignment.CenterHorizontally), v2 = v2)
        }
        // Over the controls, so a click here doesn't play, skip or seek.
        Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {})
    }
}

@Composable
private fun SlotRow(slot: PlayerIconSlot, icon: PlayerIconOverride?) {
    val custom = rememberPlayerIcon(slot)
    val leading: @Composable () -> Unit = {
        Box(
            Modifier.size(30.dp).clip(ContinuousRoundedRectangle(7.dp)).background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            PlayerGlyph(slot, slot.fallback, if (custom == null) ConvxColors.SecondaryLabel else Color.White, 20.dp)
        }
    }
    if (icon == null) {
        SettingsRow(slot.label, "Built-in", onClick = { chooseImage(slot) }, leading = leading)
    } else {
        SettingsRow(slot.label, "Active custom image", onClick = { chooseImage(slot) }, leading = leading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tint", style = ConvxType.Footnote, color = ConvxColors.SecondaryLabel)
                Spacer(Modifier.width(8.dp))
                GlassSwitch(icon.tint, { PlayerIcons.setTint(slot, it) })
                Spacer(Modifier.width(6.dp))
                IconButton("delete", { PlayerIcons.clear(slot) }, size = 32.dp, iconSize = 18.dp, tint = SystemColors.Red)
            }
        }
    }
}

/** The phone's picker: an image another control already has, or a new one from the PC. */
private fun chooseImage(slot: PlayerIconSlot) {
    Overlays.showDialog {
        val icons by PlayerIcons.overrides.collectAsState()
        val choices = icons.mapNotNull { (name, icon) ->
            PlayerIconSlot.entries.firstOrNull { it.name == name }?.takeIf { PlayerIcons.file(icon).isFile }?.let { it to icon }
        }
        val selected = icons[slot.name]?.file
        DialogCard("Choose an image for ${slot.label}") {
            Text(
                "Choose an image already used by another player control, or browse for a new one.",
                style = ConvxType.Subhead,
                color = ConvxColors.SecondaryLabel,
            )
            Spacer(Modifier.height(14.dp))
            if (choices.isEmpty()) {
                Text("No other player icons are saved yet.", style = ConvxType.Subhead, color = ConvxColors.TertiaryLabel)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    choices.forEach { (source, icon) ->
                        Column(Modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(if (icon.file == selected) 2.dp else 0.dp, if (icon.file == selected) SystemColors.Red else Color.Transparent, RoundedCornerShape(12.dp))
                                    .clickable {
                                        Overlays.dismissDialog()
                                        runCatching { PlayerIcons.reuse(slot, icon) }
                                            .onFailure { Overlays.toast("Couldn't copy this image", "error") }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                PlayerGlyph(source, source.fallback, Color.White, 36.dp)
                            }
                            Text(source.label, style = ConvxType.Caption, color = ConvxColors.SecondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Cancel", { Overlays.dismissDialog() })
                PillButton("Browse files", {
                    Overlays.dismissDialog()
                    browse(slot)
                }, filled = true)
            }
        }
    }
}

private fun browse(slot: PlayerIconSlot) {
    val file = pickFile("Choose an image for ${slot.label}", PlayerIcons.Extensions) ?: return
    AppScope.launch(Dispatchers.IO) {
        runCatching { PlayerIcons.import(slot, file) }
            .onFailure { Overlays.toast(it.message ?: "Couldn't use this image", "error") }
    }
}
