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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import com.convx.desktop.ui.glass.LocalElasticGlass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.convx.desktop.ui.glass.GlassShapes
import com.convx.desktop.ui.glass.LocalMenuBackdrop
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.RepeatMode
import com.convx.desktop.player.PlayerController
import com.convx.desktop.player.PlayerState
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.likePop
import com.convx.desktop.ui.components.AnimatedPlayGlyph
import com.convx.desktop.ui.components.PlayerGlyph
import com.convx.desktop.ui.components.PlayerIconSlot
import com.convx.desktop.ui.components.SongMenuContent
import com.convx.desktop.core.formatDuration
import com.convx.desktop.ui.components.onSecondaryClick
import com.convx.desktop.ui.components.openAtProvider
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.glass.GlassTokens
import com.convx.desktop.ui.glass.additiveChrome
import com.convx.desktop.ui.glass.elastic
import com.convx.desktop.ui.glass.rememberElasticGlass
import com.convx.desktop.ui.glass.rememberGlyphPress
import com.convx.desktop.ui.glass.liquidGlass
import com.convx.desktop.ui.shapes.NavContinuousCapsule
import com.convx.desktop.ui.shell.FullPlayerMode
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.theme.ConvxMotion
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
    // The phone's bar pills are Apple's continuous (G2) capsule, not a stadium.
    val shape = NavContinuousCapsule
    val menu = rememberMenuAnchor()
    val glass = rememberElasticGlass()

    BoxWithConstraints(
        modifier
            .height(NowPlayingPillHeight)
            // The phone pill's press swell, shadow and all. The pointer crosses a bar this long all
            // the time, so only a press moves it, and it never leans after a drag.
            .elastic(glass, hovers = false)
            .shadow(10.dp, shape)
            .clip(shape)
            .liquidGlass(shape, highlightAlpha = 0.3f)
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
                controlSize,
                iconSize,
                TransportTint.copy(alpha = if (state.shuffle) TransportTint.alpha else 0.4f),
            ) { PlayerController.toggleShuffle() }
            GlassGlyph("apple_skip_previous", controlSize, iconSize, TransportTint) { PlayerController.previous() }
            PlayPauseGlyph(state, controlSize, playIconSize)
            // With autoplay on, Next at the end of the queue fetches more songs, so it stays live
            // while a radio's songs are still arriving.
            val autoRadio = AppSettings.state.collectAsState().value.autoRadio
            GlassGlyph("apple_skip_next", controlSize, playIconSize, TransportTint, enabled = state.hasNext || autoRadio) { PlayerController.next() }
            GlassGlyph(
                if (state.repeat == RepeatMode.ONE) "repeat_one" else "repeat",
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
                    .pressable({ UiState.fullPlayer = true }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val art = 38.dp
                // The player's big cover grows out of this one.
                val artCorner = with(LocalDensity.current) { (art * 0.22f).toPx() }
                Artwork(song.thumbnail, Modifier.size(art).coverBar(artCorner), size = 200, shape = RoundedCornerShape(art * 0.22f), iconSize = 18.dp)
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
                controlSize,
                iconSize,
                if (liked) LocalAccent.current else TransportTint,
                slot = if (liked) PlayerIconSlot.LIKED else PlayerIconSlot.LIKE,
                glyphModifier = Modifier.likePop(liked),
            ) { Library.toggleLike(song) }
            GlassGlyph("flamingo_lyrics", controlSize, iconSize * 1.18f, TransportTint, bounce = true) {
                UiState.fullPlayerMode = FullPlayerMode.Lyrics
                UiState.lyricsFullscreen = AppSettings.value.oneTapFullscreenLyrics
                UiState.fullPlayer = true
            }
            // Covers queued from a menu land here.
            Box(Modifier.queueTarget()) {
                GlassGlyph("flamingo_queue", controlSize, iconSize, TransportTint, glyphModifier = Modifier.queueBump()) {
                    UiState.fullPlayerMode = FullPlayerMode.Queue
                    UiState.fullPlayer = true
                }
                QueueBadge(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp))
            }
            // Spotify's Now Playing view button, for the panel on the right.
            val panelOpen = AppSettings.state.collectAsState().value.rightPanel != null
            val panelShown = panelOpen && UiState.rightPanelFits
            GlassGlyph(
                "right_panel",
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
    val motion = rememberGlyphPress()
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .pressable({ PlayerController.togglePlay() })
            .then(if (motion != null) motion.input.then(motion.layer) else Modifier),
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

/** A bare glyph on the glass that dips under a press and wobbles back; glyphs composite additively. */
@Composable
fun GlassGlyph(
    icon: String,
    size: Dp,
    iconSize: Dp,
    tint: Color,
    enabled: Boolean = true,
    /** Plays the SF Symbols bounce when tapped, for a glyph that opens something (never transport). */
    bounce: Boolean = false,
    slot: PlayerIconSlot? = null,
    /** Extra motion for the glyph itself, such as the like's pop. */
    glyphModifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val motion = rememberGlyphPress()?.takeIf { enabled }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .pressable({
                if (bounce) motion?.bounce()
                onClick()
            }, enabled = enabled)
            .then(if (motion != null) motion.input.then(motion.layer) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        PlayerGlyph(
            slot,
            icon,
            if (enabled) tint else tint.copy(alpha = tint.alpha * 0.45f),
            iconSize,
            glyphModifier.additiveChrome(),
        )
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

/**
 * The speaker mutes on a click, and the mouse wheel over it turns the volume. Where the bar has
 * room the slider sits beside it; where it hasn't, pointing at the speaker brings a tall slider
 * up out of it on its own drop of glass.
 */
@Composable
private fun PillVolume(state: PlayerState, size: Dp, iconSize: Dp, slider: Boolean) {
    val level = if (state.muted) 0f else state.volume
    var overSpeaker by remember { mutableStateOf(false) }
    var overPopup by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(overSpeaker, overPopup, slider) {
        when {
            slider -> open = false
            overSpeaker || overPopup -> open = true
            // Long enough to cross the gap from the speaker to the slider above it.
            else -> { delay(350); open = false }
        }
    }
    // The glass grows up out of the speaker and sinks back into it; held still with Button motion off.
    val reveal = remember { Animatable(0f) }
    val moving = LocalElasticGlass.current
    LaunchedEffect(open, moving) {
        val to = if (open) 1f else 0f
        if (moving) reveal.animateTo(to, if (open) VolumeRise else VolumeSink) else reveal.snapTo(to)
    }
    val shown by remember { derivedStateOf { open || reveal.value > 0f } }
    Row(Modifier.volumeWheel(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.trackPointer { overSpeaker = it }) {
            GlassGlyph(
                when {
                    level <= 0f -> "volume_off"
                    level < 0.5f -> "volume_down"
                    else -> "volume_up"
                },
                size,
                iconSize,
                TransportTint,
            ) { PlayerController.toggleMute() }
            if (shown && !slider) VolumePopup(level, { reveal.value }) { overPopup = it }
        }
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

/** The volume on its own drop of glass above the speaker: the level on top, a tall slider under it. */
@Composable
private fun VolumePopup(level: Float, reveal: () -> Float, onPointer: (Boolean) -> Unit) {
    val density = LocalDensity.current
    // Clear of the bar's top edge: the speaker sits 14 dp below it.
    val position = remember(density) { AboveAnchor(with(density) { 24.dp.roundToPx() }) }
    val backdrop = LocalMenuBackdrop.current
    val shape = VolumeShape
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false)) {
        Column(
            Modifier
                .graphicsLayer {
                    val p = reveal()
                    alpha = p.coerceIn(0f, 1f)
                    scaleX = 0.7f + 0.3f * p
                    scaleY = 0.4f + 0.6f * p
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .trackPointer(onPointer)
                .volumeWheel()
                .shadow(12.dp, shape)
                .clip(shape)
                .then(if (backdrop != null) Modifier.liquidGlass(shape, highlightAlpha = 0.25f, backdrop = backdrop) else Modifier.background(Color(0xFF2C2C2E)))
                .padding(start = 7.dp, end = 7.dp, top = 10.dp, bottom = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${(level * 100).roundToInt()}",
                style = ConvxType.Footnote.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(alpha = GlassTokens.SecondaryLabelAlpha),
                maxLines = 1,
                modifier = Modifier.additiveChrome(),
            )
            Spacer(Modifier.height(8.dp))
            VerticalVolume(level, Modifier.width(32.dp).height(124.dp))
        }
    }
}

/**
 * Control Centre's volume: a capsule that fills with light from the bottom up to the level, with
 * the speaker at its foot. A press anywhere in it sets the level there and a drag carries it; the
 * light follows on a quick spring, so a wheel notch slides rather than jumps.
 */
@Composable
private fun VerticalVolume(level: Float, modifier: Modifier) {
    val shown by animateFloatAsState(level, VolumeFill)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val light by animateFloatAsState(if (hovered) 1f else 0.88f)
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        Canvas(
            Modifier
                .matchParentSize()
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(Unit) {
                    fun set(y: Float) = PlayerController.setVolume(1f - (y / size.height).coerceIn(0f, 1f))
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        set(down.position.y)
                        down.consume()
                        drag(down.id) { change ->
                            set(change.position.y)
                            change.consume()
                        }
                    }
                },
        ) {
            val track = RoundRect(0f, 0f, size.width, size.height, CornerRadius(size.width / 2))
            drawPath(Path().apply { addRoundRect(track) }, Color.White.copy(alpha = 0.14f))
            val filled = size.height * shown.coerceIn(0f, 1f)
            if (filled > 0f) {
                clipPath(Path().apply { addRoundRect(track) }) {
                    drawRect(Color.White.copy(alpha = light), Offset(0f, size.height - filled), Size(size.width, filled))
                }
            }
        }
        // Dark on the light once the level covers it, light on the glass below that.
        ConvxIcon(
            when {
                level <= 0f -> "volume_off"
                level < 0.5f -> "volume_down"
                else -> "volume_up"
            },
            Modifier.padding(bottom = 9.dp),
            tint = if (level >= 0.2f) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.75f),
            size = 15.dp,
        )
    }
}

private val VolumeShape = RoundedCornerShape(23.dp)
private val VolumeRise = spring<Float>(dampingRatio = 0.72f, stiffness = 520f)
private val VolumeSink = spring<Float>(dampingRatio = 1f, stiffness = 900f)
private val VolumeFill = spring<Float>(dampingRatio = 1f, stiffness = 1400f)

/** Centred above its anchor, kept inside the window. */
private class AboveAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.center.x - popupContentSize.width / 2
        val y = anchorBounds.top - popupContentSize.height - gap
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceAtLeast(0),
        )
    }
}

/** A notch of the mouse wheel: up is louder. */
private const val VolumeStep = 0.05f

private fun Modifier.volumeWheel(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Scroll) continue
            val change = event.changes.firstOrNull() ?: continue
            val notches = change.scrollDelta.y
            if (notches == 0f || change.isConsumed) continue
            PlayerController.nudgeVolume(-notches * VolumeStep)
            change.consume()
        }
    }
}

/**
 * Whether the pointer is over this, counting a press that started here and was dragged off it
 * (the slider follows a drag past its ends), until the button comes up.
 */
private fun Modifier.trackPointer(onChange: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    var inside = false
    var held = false
    var reported = false
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            when (event.type) {
                PointerEventType.Enter -> inside = true
                PointerEventType.Exit -> inside = false
                PointerEventType.Press -> { inside = true; held = true }
                PointerEventType.Release -> {
                    held = false
                    val p = event.changes.firstOrNull()?.position
                    inside = p != null && p.x >= 0f && p.y >= 0f && p.x < size.width && p.y < size.height
                }
                else -> continue
            }
            val now = inside || held
            if (now != reported) {
                reported = now
                onChange(now)
            }
        }
    }
}
