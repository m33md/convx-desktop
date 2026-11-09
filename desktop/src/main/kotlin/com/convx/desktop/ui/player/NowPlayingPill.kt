/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.RepeatMode
import com.convx.desktop.player.PlayerController
import com.convx.desktop.player.PlayerState
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.AnimatedPlayGlyph
import com.convx.desktop.ui.components.PlayerGlyph
import com.convx.desktop.ui.components.PlayerIconSlot
import com.convx.desktop.ui.components.SongMenuContent
import com.convx.desktop.ui.components.Tooltip
import com.convx.desktop.core.formatDuration
import com.convx.desktop.ui.components.onSecondaryClick
import com.convx.desktop.ui.components.openAtProvider
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.glass.GlassTokens
import com.convx.desktop.ui.glass.additiveChrome
import com.convx.desktop.ui.glass.rememberSymbolBounce
import com.convx.desktop.ui.glass.liquidGlass
import com.convx.desktop.ui.glass.elasticStretch
import com.convx.desktop.ui.glass.rememberInteractiveHighlight
import com.convx.desktop.ui.shapes.NavContinuousCapsule
import com.convx.desktop.ui.shell.FullPlayerMode
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent

/** The phone's tab-view pill: 80% of the content's width, 64 tall. */
const val NowPlayingPillWidthFraction = 0.8f
val NowPlayingPillHeight: Dp = 64.dp

/** The phone's glass content tiers: transport at 85%, play at full. */
private val TransportTint = GlassTokens.Icon
private val PlayTint = GlassTokens.Icon.copy(alpha = 1f)

/**
 * The phone's AppFloatingNowPlayingPill in its tab-bar arrangement (shuffle, previous,
 * play, next, repeat; the song; lyrics and queue), on the same glass. A desktop needs to
 * seek and set the volume without opening the player, so the song carries a thin progress
 * line and the end of the pill a volume slider.
 */
@Composable
fun NowPlayingPill(modifier: Modifier = Modifier) {
    val state by PlayerController.state.collectAsState()
    val navigator = LocalNavigator.current
    val song = state.current ?: return
    val glow = rememberInteractiveHighlight()
    // The phone's bar pills are Apple's continuous (G2) capsule, not a stadium.
    val shape = NavContinuousCapsule
    val menu = rememberMenuAnchor()

    BoxWithConstraints(
        modifier
            .height(NowPlayingPillHeight)
            // The phone pill's press swell, a couple of dp; a bar this long neither leans nor follows a drag.
            .graphicsLayer(elasticStretch(glow))
            .shadow(10.dp, shape)
            .clip(shape)
            .liquidGlass(shape, highlightAlpha = 0.3f)
            .then(glow.modifier)
            .then(glow.gestureModifier)
            .onSecondaryClick(menu.openAtProvider()),
    ) {
        val roomy = maxWidth >= 680.dp
        val controlSize = 36.dp
        val iconSize = 18.dp
        val playIconSize = 22.dp
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassGlyph(
                "shuffle",
                if (state.shuffle) "Disable shuffle" else "Enable shuffle",
                controlSize,
                iconSize,
                TransportTint.copy(alpha = if (state.shuffle) TransportTint.alpha else 0.4f),
            ) { PlayerController.toggleShuffle() }
            GlassGlyph("apple_skip_previous", "Previous", controlSize, iconSize, TransportTint) { PlayerController.previous() }
            PlayPauseGlyph(state, controlSize, playIconSize)
            // With autoplay on, Next at the end of the queue fetches more songs, so it stays live
            // while a radio's songs are still arriving.
            val autoRadio = AppSettings.state.collectAsState().value.autoRadio
            GlassGlyph("apple_skip_next", "Next", controlSize, playIconSize, TransportTint, enabled = state.hasNext || autoRadio) { PlayerController.next() }
            GlassGlyph(
                if (state.repeat == RepeatMode.ONE) "repeat_one" else "repeat",
                when (state.repeat) {
                    RepeatMode.OFF -> "Enable repeat"
                    RepeatMode.ALL -> "Enable repeat one"
                    RepeatMode.ONE -> "Disable repeat"
                },
                controlSize,
                iconSize,
                TransportTint.copy(alpha = if (state.repeat != RepeatMode.OFF) TransportTint.alpha else 0.4f),
            ) { PlayerController.cycleRepeat() }

            Spacer(Modifier.width(8.dp))

            // The song: opens the player, as tapping the phone's pill does.
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .pressable({ UiState.fullPlayer = true }, pressScale = 0.99f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val art = 38.dp
                Artwork(song.thumbnail, Modifier.size(art), size = 200, shape = RoundedCornerShape(art * 0.22f), iconSize = 18.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Both lines in one layer: their glyphs don't overlap.
                        Column(Modifier.weight(1f).additiveChrome()) {
                            Text(
                                song.title,
                                style = ConvxType.Body.copy(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.23).sp),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                song.artistText,
                                style = ConvxType.Footnote.copy(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.06.sp),
                                color = Color.White.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        PillTimes(state, Modifier.padding(start = 8.dp))
                    }
                    PillProgress(state, Modifier.padding(top = 3.dp))
                }
            }

            Spacer(Modifier.width(8.dp))

            val liked = Library.state.collectAsState().value.liked.any { it.song.id == song.id }
            GlassGlyph(
                if (liked) "favorite" else "favorite_border",
                if (liked) "Remove from Liked Songs" else "Add to Liked Songs",
                controlSize,
                iconSize,
                if (liked) LocalAccent.current else TransportTint,
                bounce = true,
                slot = if (liked) PlayerIconSlot.LIKED else PlayerIconSlot.LIKE,
            ) { Library.toggleLike(song) }
            GlassGlyph("flamingo_lyrics", "Lyrics", controlSize, iconSize * 1.18f, TransportTint, bounce = true) {
                UiState.fullPlayerMode = FullPlayerMode.Lyrics
                UiState.lyricsFullscreen = AppSettings.value.oneTapFullscreenLyrics
                UiState.fullPlayer = true
            }
            GlassGlyph("flamingo_queue", "Queue", controlSize, iconSize, TransportTint, bounce = true) {
                UiState.fullPlayerMode = FullPlayerMode.Queue
                UiState.fullPlayer = true
            }
            // Spotify's Now Playing view button, for the panel on the right.
            val panelOpen = AppSettings.state.collectAsState().value.rightPanel != null
            val panelShown = panelOpen && UiState.rightPanelFits
            GlassGlyph(
                "right_panel",
                if (panelShown) "Hide Now Playing view" else "Show Now Playing view",
                controlSize,
                iconSize,
                TransportTint.copy(alpha = if (panelShown) TransportTint.alpha else 0.4f),
                bounce = true,
            ) {
                if (UiState.rightPanelFits) {
                    UiState.toggleNowPlayingPanel()
                } else {
                    UiState.openSidePanel(com.convx.desktop.ui.shell.SidePanel.NowPlaying)
                    com.convx.desktop.ui.components.Overlays.toast("Make the window wider to fit the Now Playing view", "right_panel")
                }
            }
            PillVolume(state, controlSize, iconSize, slider = roomy)
        }
        AppMenu(menu) {
            SongMenuContent(song, { menu.expanded = false }) { dismiss ->
                // The phone's player menu opens the party from here.
                MenuItem("Listen together", "group") {
                    dismiss()
                    UiState.fullPlayer = false
                    navigator.navigate(Screen.Party)
                }
            }
        }
    }
}

@Composable
private fun PlayPauseGlyph(state: PlayerState, size: Dp, iconSize: Dp) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Tooltip(if (state.isPlaying) "Pause" else "Play") {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (hovered) 0.10f else 0f))
                .hoverable(interaction)
                .pressable({ PlayerController.togglePlay() }, interaction = interaction, pressScale = 0.86f),
            contentAlignment = Alignment.Center,
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(color = PlayTint, strokeWidth = 2.dp, modifier = Modifier.size(iconSize * 0.8f))
            } else {
                // The phone's mini player: replay once the queue has run out.
                val slot = when {
                    state.ended -> PlayerIconSlot.REPLAY
                    state.isPlaying -> PlayerIconSlot.PAUSE
                    else -> PlayerIconSlot.PLAY
                }
                AnimatedPlayGlyph(slot, PlayTint, iconSize)
            }
        }
    }
}

/** A bare glyph on the glass, lit by a soft wash under the cursor; glyphs composite additively. */
@Composable
fun GlassGlyph(
    icon: String,
    tooltip: String,
    size: Dp,
    iconSize: Dp,
    tint: Color,
    enabled: Boolean = true,
    bounce: Boolean = false,
    slot: PlayerIconSlot? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val symbolBounce = rememberSymbolBounce()
    Tooltip(tooltip) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (hovered && enabled) 0.10f else 0f))
                .hoverable(interaction)
                .pressable({
                    if (bounce) symbolBounce.play()
                    onClick()
                }, enabled = enabled, interaction = interaction, pressScale = 0.86f),
            contentAlignment = Alignment.Center,
        ) {
            PlayerGlyph(
                slot,
                icon,
                if (enabled) tint else tint.copy(alpha = tint.alpha * 0.45f),
                iconSize,
                symbolBounce.modifier.additiveChrome(),
            )
        }
    }
}

/** Elapsed and total, small and quiet beside the title. */
@Composable
private fun PillTimes(state: PlayerState, modifier: Modifier = Modifier) {
    // Whole seconds are all it shows, so it changes once a second.
    val position = rememberSmoothPosition(stepMs = 1000)
    val seconds by remember { derivedStateOf { position.value / 1000 } }
    val duration = state.durationMs.takeIf { it > 0 } ?: ((state.current?.duration ?: 0) * 1000L)
    Text(
        "${formatDuration(seconds * 1000)} / ${formatDuration(duration)}",
        style = ConvxType.Footnote.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
        color = Color.White.copy(alpha = GlassTokens.SecondaryLabelAlpha),
        maxLines = 1,
        modifier = modifier.additiveChrome(),
    )
}

/** A hairline of progress that thickens under the cursor and seeks on click or drag. */
@Composable
private fun PillProgress(state: PlayerState, modifier: Modifier = Modifier) {
    val duration = state.durationMs.takeIf { it > 0 } ?: ((state.current?.duration ?: 0) * 1000L)
    // A few hundred steps across a bar a few hundred pixels wide: it moves when a pixel would.
    val position = rememberSmoothPosition(stepMs = (duration / 800).coerceIn(100, 1000))
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val stroke by animateFloatAsState(if (hovered) 5f else 3f)
    SeekBar(
        positionMs = { position.value },
        durationMs = duration,
        onSeek = { PlayerController.seekTo(it) },
        wavy = false,
        activeColor = Color.White.copy(alpha = 0.85f),
        hoverColor = Color.White,
        trackColor = Color.White.copy(alpha = 0.18f),
        height = 9.dp,
        strokeWidth = stroke.dp,
        modifier = modifier.hoverable(interaction),
    )
}

@Composable
private fun PillVolume(state: PlayerState, size: Dp, iconSize: Dp, slider: Boolean) {
    val level = if (state.muted) 0f else state.volume
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassGlyph(
            when {
                level <= 0f -> "volume_off"
                level < 0.5f -> "volume_down"
                else -> "volume_up"
            },
            if (state.muted) "Unmute" else "Mute",
            size,
            iconSize,
            TransportTint,
        ) { PlayerController.toggleMute() }
        if (slider) {
            ValueSlider(
                value = level,
                onValueChange = { PlayerController.setVolume(it) },
                activeColor = Color.White.copy(alpha = 0.85f),
                hoverColor = Color.White,
                trackColor = Color.White.copy(alpha = 0.18f),
                strokeWidth = 3.dp,
                modifier = Modifier.width(84.dp).padding(end = 6.dp),
            )
        }
    }
}
