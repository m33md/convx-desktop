/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.core.formatDuration
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.RepeatMode
import com.convx.desktop.data.SeekBarStyle
import com.convx.desktop.data.Song
import com.convx.desktop.player.PlayerController
import com.convx.desktop.player.PlayerState
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.ArtistLinks
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.LinkText
import com.convx.desktop.ui.components.PlayButton
import com.convx.desktop.ui.components.AnimatedPlayGlyph
import com.convx.desktop.ui.components.PlayerIconSlot
import com.convx.desktop.ui.components.SongMenuContent
import com.convx.desktop.ui.components.Tooltip
import com.convx.desktop.ui.components.onSecondaryClick
import com.convx.desktop.ui.components.openAtProvider
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.SidePanel
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent

/**
 * Shuffle, previous, play/pause, next, repeat. Apple Music v2 leaves shuffle and repeat to the
 * queue, as Apple does, and draws its own bare glyphs where the classic designs set play in a
 * white disc. Each control draws the image picked for it in Player icons, if any.
 */
@Composable
fun TransportControls(
    state: PlayerState,
    compact: Boolean,
    tint: Color = Color.White,
    modifier: Modifier = Modifier,
    v2: Boolean = false,
) {
    val accent = LocalAccent.current
    val small = if (compact) 34.dp else 48.dp
    val smallIcon = if (compact) 18.dp else 24.dp
    // The phone's v2 sets 44dp glyphs in 61dp buttons.
    val skip = if (compact) 38.dp else if (v2) 61.dp else 60.dp
    val skipIcon = if (compact) 24.dp else if (v2) 44.dp else 38.dp
    val play = if (compact) 38.dp else 72.dp
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 22.dp),
    ) {
        if (!v2) IconButton(
            if (state.shuffle) "shuffle_on" else "shuffle",
            { PlayerController.toggleShuffle() },
            size = small,
            iconSize = smallIcon,
            tint = tint.copy(alpha = 0.75f),
            active = state.shuffle,
            activeTint = accent,
        )
        val previous = if (v2) PlayerIconSlot.V2_PREVIOUS else PlayerIconSlot.PREVIOUS
        IconButton(previous.fallback, { PlayerController.previous() }, size = skip, iconSize = skipIcon, tint = tint, enabled = state.current != null, slot = previous)
        if (compact || !v2) {
            PlayButton(
                isPlaying = state.isPlaying,
                onClick = { PlayerController.togglePlay() },
                size = play,
                loading = state.isLoading,
                player = true,
                ended = state.ended,
            )
        } else {
            // The phone's v2: a bare glyph, no disc, popping between play and pause.
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Box(
                Modifier
                    .size(play)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (hovered && state.current != null) 0.08f else 0f))
                    .hoverable(interaction)
                    .pressable({ PlayerController.togglePlay() }, enabled = state.current != null, interaction = interaction, pressScale = 0.86f),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedPlayGlyph(
                    if (state.isPlaying) PlayerIconSlot.V2_PAUSE else PlayerIconSlot.V2_PLAY,
                    if (state.current != null) tint else tint.copy(alpha = 0.3f),
                    44.dp,
                )
            }
        }
        val next = if (v2) PlayerIconSlot.V2_NEXT else PlayerIconSlot.NEXT
        IconButton(next.fallback, { PlayerController.next() }, size = skip, iconSize = skipIcon, tint = tint, enabled = state.current != null, slot = next)
        if (!v2) IconButton(
            when (state.repeat) {
                RepeatMode.ONE -> "repeat_one_on"
                RepeatMode.ALL -> "repeat_on"
                RepeatMode.OFF -> "repeat"
            },
            { PlayerController.cycleRepeat() },
            size = small,
            iconSize = smallIcon,
            tint = tint.copy(alpha = 0.75f),
            active = state.repeat != RepeatMode.OFF,
            activeTint = accent,
        )
    }
}

/** Elapsed · bar · total. Reads the position itself so the rest of the bar doesn't recompose 60 times a second. */
@Composable
fun ProgressRow(
    state: PlayerState,
    /** The classic designs' seek bar; null for Apple's capsule. */
    style: SeekBarStyle?,
    modifier: Modifier = Modifier,
    labelColor: Color = ConvxColors.SecondaryLabel,
    labelsBelow: Boolean = false,
    showQuality: Boolean = false,
) {
    val position = rememberSmoothPosition()
    val seconds by remember { derivedStateOf { position.value / 1000 } }
    val duration = state.durationMs.takeIf { it > 0 } ?: ((state.current?.duration ?: 0) * 1000L)
    val elapsed = formatDuration(seconds * 1000)
    val bar = @Composable { m: Modifier ->
        when (style) {
            null -> AppleSeekBar({ position.value }, duration, { PlayerController.seekTo(it) }, m)
            SeekBarStyle.Waveform -> WaveformSeekBar({ position.value }, duration, { PlayerController.seekTo(it) }, seed = state.current?.id.hashCode(), modifier = m)
            else -> SeekBar(
                positionMs = { position.value },
                durationMs = duration,
                onSeek = { PlayerController.seekTo(it) },
                wavy = style == SeekBarStyle.Wavy,
                playing = state.isPlaying,
                hoverColor = LocalAccent.current,
                strokeWidth = if (style == SeekBarStyle.Slim) 3.dp else 4.dp,
                thumb = style == SeekBarStyle.Default,
                customThumb = true,
                modifier = m,
            )
        }
    }
    if (labelsBelow) {
        Column(modifier.fillMaxWidth()) {
            bar(Modifier.fillMaxWidth())
            // The phone keeps the quality badge centred between the two times.
            Box(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(elapsed, style = ConvxType.Caption, color = labelColor, modifier = Modifier.align(Alignment.CenterStart))
                if (showQuality) state.stream?.let { QualityBadge(it.badge, it.detail, labelColor, Modifier.align(Alignment.Center)) }
                Text(
                    "-" + formatDuration((duration - seconds * 1000).coerceAtLeast(0)),
                    style = ConvxType.Caption,
                    color = labelColor,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    } else {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(elapsed, style = ConvxType.Caption, color = labelColor, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
            Spacer(Modifier.width(8.dp))
            bar(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(formatDuration(duration), style = ConvxType.Caption, color = labelColor, modifier = Modifier.width(44.dp))
        }
    }
}

/** The phone's AudioQualityBadge: 11sp on a 15% wash with 5dp corners; hover shows the detail. */
@Composable
fun QualityBadge(label: String, detail: String, tint: Color, modifier: Modifier = Modifier) {
    Box(modifier) {
        Tooltip(detail) {
            Text(
                label,
                style = ConvxType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
                color = tint,
                maxLines = 1,
                modifier = Modifier
                    .shadow(2.dp, RoundedCornerShape(5.dp), ambientColor = Color.Black.copy(alpha = 0.14f), spotColor = Color.Black.copy(alpha = 0.18f))
                    .clip(RoundedCornerShape(5.dp))
                    .background(tint.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
fun VolumeControl(
    state: PlayerState,
    sliderWidth: androidx.compose.ui.unit.Dp = 96.dp,
    tint: Color = Color.White,
    /** Apple Music v2's volume glyph can be an image of your own. */
    slot: PlayerIconSlot? = null,
) {
    val level = if (state.muted) 0f else state.volume
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            when {
                level <= 0f -> "volume_off"
                level < 0.5f -> "volume_down"
                else -> "volume_up"
            },
            { PlayerController.toggleMute() },
            size = 34.dp,
            iconSize = 19.dp,
            tint = tint,
            slot = slot,
        )
        ValueSlider(
            value = level,
            onValueChange = { PlayerController.setVolume(it) },
            hoverColor = LocalAccent.current,
            modifier = Modifier.width(sliderWidth),
        )
    }
}
