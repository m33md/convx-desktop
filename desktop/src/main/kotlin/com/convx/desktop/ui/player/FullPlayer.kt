/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.PlayerBackground
import com.convx.desktop.data.PlayerDesign
import com.convx.desktop.data.RepeatMode
import com.convx.desktop.data.Settings
import com.convx.desktop.player.PlayerState
import com.convx.desktop.ui.components.Tooltip
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.ArtistLinks
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.PlayerIconSlot
import com.convx.desktop.ui.components.SongMenuContent
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.components.rememberMeshColors
import com.convx.desktop.ui.shell.FullPlayerMode
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.shell.WindowTitleBar
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.MeshColors
import com.convx.desktop.player.PlayerController
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val BlobAnchors = listOf(Offset(0.20f, 0.25f), Offset(0.80f, 0.20f), Offset(0.75f, 0.80f), Offset(0.25f, 0.75f))
private val BlobSpeeds = listOf(1f, -0.7f, 0.85f, -1.15f)
private const val DriftRadians = (PI * 0.45).toFloat()

/**
 * The phone's player backdrop: four colours from the cover as soft blobs on a dark
 * wash of the cover's own hue, blurred together. The blobs drift when the track
 * changes and rest otherwise, so a still screen costs nothing.
 */
@Composable
fun MeshBackground(colors: List<Color>?, trackKey: Any?, modifier: Modifier = Modifier, scrim: Float = 0.38f) {
    val tuned = (colors ?: MeshColors.Fallback).let { (it + MeshColors.Fallback).take(4) }
    val spec = tween<Color>(1400, easing = FastOutSlowInEasing)
    val animated = tuned.map { animateColorAsState(it, spec) }
    val base = animateColorAsState(MeshColors.dimmed(tuned.first()), spec)
    val phase = remember { Animatable(0f) }
    LaunchedEffect(trackKey) {
        phase.animateTo(phase.value + DriftRadians, tween(8_000, easing = FastOutSlowInEasing))
    }
    Canvas(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = 1.3f
                scaleY = 1.3f
            }
            .blur(64.dp),
    ) {
        val drift = phase.value
        drawRect(base.value)
        animated.forEachIndexed { index, state ->
            val anchor = BlobAnchors[index]
            val speed = BlobSpeeds[index]
            val center = Offset(
                x = (anchor.x + 0.16f * cos(drift * speed + index * 1.7f)) * size.width,
                y = (anchor.y + 0.16f * sin(drift * speed * 0.9f + index * 2.3f)) * size.height,
            )
            val radius = size.maxDimension * 0.62f
            drawCircle(
                brush = Brush.radialGradient(listOf(state.value.copy(alpha = 0.85f), state.value.copy(alpha = 0f)), center, radius),
                radius = radius,
                center = center,
            )
        }
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = scrim * 0.26f), Color.Black.copy(alpha = scrim))))
    }
}

/** Full-window player: artwork and controls on the left, lyrics or the queue on the right. */
@Composable
fun FullPlayer(modifier: Modifier = Modifier) {
    val state by PlayerController.state.collectAsState()
    val settings by AppSettings.state.collectAsState()
    PlayerStage(state, settings, UiState.fullPlayerMode, modifier)
}

/**
 * The player in the chosen design, as the phone's player draws each one: Apple Music v2 (a square
 * cover or a full-bleed one) with Apple's capsule and bare controls, Apple Music with a full-bleed
 * cover, and Card, Vinyl and Clover with their own cover shapes, background and seek bar.
 *
 * [preview] draws it for the design page: nothing reacts and the side panes stay shut.
 */
@Composable
fun PlayerStage(
    state: PlayerState,
    settings: Settings,
    mode: FullPlayerMode,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
    /** The DIY editor draws its own, movable copies. */
    showStickers: Boolean = true,
) {
    val navigator = LocalNavigator.current
    val library by Library.state.collectAsState()
    val song = state.current
    val menu = rememberMenuAnchor()
    val design = settings.playerDesign
    val v2 = design == PlayerDesign.AppleMusicV2
    val hero = settings.heroLayout
    val apple = !design.classic
    val background = settings.effectiveBackground()
    val split = mode != FullPlayerMode.Artwork
    // The phone's full-screen lyrics: the lyrics take the whole player, under a compact bar or alone.
    val fullscreen = mode == FullPlayerMode.Lyrics && UiState.lyricsFullscreen && !preview
    val away by animateFloatAsState(if (fullscreen) 1f else 0f, tween(420, easing = FastOutSlowInEasing))
    if (!preview) DisposableEffect(Unit) { onDispose { UiState.lyricsFullscreen = false } }

    Box(
        modifier
            .fillMaxSize()
            .then(if (background == PlayerBackground.Glass) Modifier else Modifier.background(Color.Black))
            // Swallow clicks so nothing underneath reacts.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        // Reduce live blur holds the moving backgrounds still, as the phone's does.
        PlayerBackdrop(background, song?.thumbnail, state.isPlaying && !preview && !settings.reduceLiveBlur, settings, hero = hero)

        // The phone's DIY stickers: below zero behind the cover, the rest in front, popping in one
        // after another as the player opens.
        val stickers = settings.diyLayout.takeIf { showStickers && !it.isEmpty }
        val entrance = remember { androidx.compose.animation.core.Animatable(if (preview) 1f else 0f) }
        if (stickers != null) LaunchedEffect(Unit) { entrance.animateTo(1f, tween(1100, easing = androidx.compose.animation.core.LinearEasing)) }
        if (stickers != null) DiyStickerLayer(stickers, zFilter = BehindCover, entrance = { entrance.value })

        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Full bleed: the cover runs off the window's top edge and fades out at its foot, and
            // towards the lyrics or queue beside it; alone, it's centred and fades at both sides.
            if (hero) {
                val leftWidth = min((maxWidth.value - 80f) * 0.42f, 560f).dp
                val side = if (split) min(maxHeight.value * 0.82f, leftWidth.value + 160f).dp else min(maxWidth.value, maxHeight.value * 0.82f).dp
                HeroCover(
                    song?.thumbnail,
                    Modifier.align(if (split) Alignment.TopStart else Alignment.TopCenter).size(side).graphicsLayer { alpha = 1f - away },
                    fadeEnd = split,
                    fadeSides = !split && side < maxWidth,
                    hidden = settings.hidePlayerArtwork,
                )
            }
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
            // Top row
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton("expand_more", { UiState.fullPlayer = false }, size = 40.dp, iconSize = 28.dp, filled = true, tooltip = "Close (Esc)")
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    state.queueTitle?.let {
                        Text("PLAYING FROM", style = ConvxType.Footnote, color = Color.White.copy(alpha = 0.6f))
                        Text(it, style = ConvxType.SubheadStrong, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // The phone's party chip: the code and how many are listening, and the way back to it.
                    val party by com.convx.desktop.integrations.party.Party.session.collectAsState()
                    party?.let {
                        PartyChip(it.code, it.members.size) {
                            UiState.fullPlayer = false
                            navigator.navigate(Screen.Party)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                // The phone's lyrics menu sits by the lyrics while they're open.
                if (mode == FullPlayerMode.Lyrics && !preview) {
                    IconButton(
                        if (fullscreen) "lyrics_collapse" else "lyrics_expand",
                        {
                            UiState.lyricsFullscreen = !UiState.lyricsFullscreen
                            // One-tap: leaving full screen from here closes the lyrics, as on the phone.
                            if (settings.oneTapFullscreenLyrics && !UiState.lyricsFullscreen) UiState.fullPlayerMode = FullPlayerMode.Artwork
                        },
                        size = 40.dp,
                        iconSize = 18.dp,
                        filled = fullscreen,
                        tooltip = if (fullscreen) "Exit full-screen lyrics" else "Full-screen lyrics",
                    )
                    Spacer(Modifier.width(8.dp))
                    LyricsMenuButton(song)
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(
                    if (mode == FullPlayerMode.Lyrics) "lyrics_active" else "lyrics",
                    { UiState.toggleLyrics(settings.oneTapFullscreenLyrics) },
                    size = 40.dp,
                    iconSize = 22.dp,
                    filled = mode == FullPlayerMode.Lyrics,
                    tooltip = "Lyrics",
                    slot = if (v2) PlayerIconSlot.V2_LYRICS else null,
                )
                Spacer(Modifier.width(8.dp))
                // Comments are YouTube's, so a file on this PC has none to show.
                if (settings.showCommentButton && song != null && !com.convx.desktop.player.LocalMusic.isLocal(song.id)) {
                    IconButton(
                        "chat_msg",
                        { UiState.fullPlayerMode = if (mode == FullPlayerMode.Comments) FullPlayerMode.Artwork else FullPlayerMode.Comments },
                        size = 40.dp,
                        iconSize = 20.dp,
                        filled = mode == FullPlayerMode.Comments,
                        tooltip = "Comments",
                    )
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(
                    "apple_queue",
                    { UiState.fullPlayerMode = if (mode == FullPlayerMode.Queue) FullPlayerMode.Artwork else FullPlayerMode.Queue },
                    size = 40.dp,
                    iconSize = 22.dp,
                    filled = mode == FullPlayerMode.Queue,
                    tooltip = "Queue",
                    slot = if (v2) PlayerIconSlot.V2_QUEUE else null,
                )
                // Windows' minimise, maximise and close sit over this corner of the window; keep clear of them.
                if (WindowTitleBar.active && !preview) Spacer(Modifier.width((WindowTitleBar.rightInset - 32.dp).coerceAtLeast(0.dp)))
            }

            AnimatedVisibility(
                fullscreen && settings.fullscreenLyricsCollapseTop,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                LyricsControlBar(state, settings, Modifier.padding(top = 14.dp))
            }

            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val leftWidth = if (split) min(maxWidth.value * 0.42f, 560f).dp else min(maxWidth.value, 620f).dp
                // Room for the title, seek bar, controls and the optional rows under the cover.
                val below = 300f + (if (settings.showVolumeBar) 0f else -40f) + (if (settings.showUpNext && state.upNext.isNotEmpty()) 56f else 0f)
                val artSize = min(leftWidth.value - 20f, maxHeight.value - below).coerceAtLeast(160f).dp
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (split) Arrangement.Start else Arrangement.Center) {
                    // Going full screen, the cover and controls slide out to the left at their own
                    // width rather than squeezing, and the lyrics take the room.
                    Box(Modifier.width(leftWidth * (1f - away)).fillMaxHeight().clipToBounds().graphicsLayer { alpha = 1f - away }) {
                        if (away < 1f) Column(
                            Modifier.wrapContentWidth(Alignment.End, unbounded = true).requiredWidth(leftWidth).fillMaxHeight(),
                            verticalArrangement = if (hero) Arrangement.Bottom else Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (!hero) {
                                StageCover(
                                    song?.thumbnail,
                                    design,
                                    settings.artworkShadow,
                                    state.isPlaying || state.isLoading,
                                    artSize,
                                    still = preview,
                                    corner = settings.playerArtworkCorner.takeIf { it >= 0 }?.dp,
                                    hidden = settings.hidePlayerArtwork,
                                    onToggle = if (mode == FullPlayerMode.Lyrics && settings.lyricsArtworkPlayPause && !preview && song != null) {
                                        { PlayerController.togglePlay() }
                                    } else {
                                        null
                                    },
                                )
                                Spacer(Modifier.height(28.dp))
                            }
                            Column(Modifier.width(artSize.coerceAtLeast(360.dp))) {
                                if (song != null) {
                                    val liked = library.liked.any { it.song.id == song.id }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f).swipeToChangeSong(settings.lyricsSwipeToChange && mode == FullPlayerMode.Lyrics)) {
                                            // Apple Music v2 sets title and artist at one size, semibold over regular;
                                            // the other designs have a bold title over a smaller artist line.
                                            val line = if (v2) ConvxType.Headline.copy(fontSize = 21.sp, lineHeight = 27.sp) else ConvxType.Title2.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                                            val artistLine = if (v2) line.copy(color = Color.White.copy(alpha = 0.6f), fontWeight = FontWeight.Normal)
                                                else ConvxType.Body.copy(fontSize = 16.sp, lineHeight = 21.sp, color = Color.White.copy(alpha = 0.7f))
                                            Text(song.title, style = line, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            ArtistLinks(song, artistLine)
                                        }
                                        // Apple Music v2's heart and more glyphs carry their own faint disc, 28dp in a
                                        // 40dp button; the other designs set theirs on a glass disc.
                                        val likeSlot = when {
                                            v2 && liked -> PlayerIconSlot.V2_LIKED
                                            v2 -> PlayerIconSlot.V2_LIKE
                                            liked -> PlayerIconSlot.LIKED
                                            else -> PlayerIconSlot.LIKE
                                        }
                                        val moreSlot = if (v2) PlayerIconSlot.V2_MORE else PlayerIconSlot.MORE
                                        val actionSize = if (v2) 40.dp else 38.dp
                                        val actionIcon = if (v2) 28.dp else 22.dp
                                        IconButton(
                                            likeSlot.fallback,
                                            { Library.toggleLike(song) },
                                            size = actionSize,
                                            iconSize = actionIcon,
                                            filled = !v2,
                                            tooltip = if (liked) "Remove from Liked Songs" else "Add to Liked Songs",
                                            slot = likeSlot,
                                        )
                                        Spacer(Modifier.width(if (v2) 4.dp else 8.dp))
                                        Box {
                                            IconButton(moreSlot.fallback, { menu.expanded = true }, size = actionSize, iconSize = actionIcon, filled = !v2, tooltip = "More options", slot = moreSlot)
                                            AppMenu(menu) {
                                                SongMenuContent(song, { menu.expanded = false }) { dismiss ->
                                                    // The phone's player menu opens the party from here.
                                                    MenuItem("Listen together", "group") {
                                                        dismiss()
                                                        UiState.fullPlayer = false
                                                        navigator.navigate(Screen.Party)
                                                    }
                                                    MenuItem("Tempo and pitch", "speed") {
                                                        dismiss()
                                                        tempoAndPitch()
                                                    }
                                                    MenuItem("Ambient mode", "fullscreen") {
                                                        dismiss()
                                                        Ambient.on = true
                                                    }
                                                    MenuItem("Player design", "palette") {
                                                        dismiss()
                                                        UiState.fullPlayer = false
                                                        navigator.navigate(Screen.Settings)
                                                        com.convx.desktop.ui.screens.settings.SettingsNav.push(com.convx.desktop.ui.screens.settings.SettingsPage.PlayerDesign)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                ProgressRow(
                                    state,
                                    if (apple) null else settings.seekBarStyle,
                                    labelColor = Color.White.copy(alpha = 0.6f),
                                    labelsBelow = true,
                                    showQuality = settings.showAudioQualityBadge,
                                )
                                Spacer(Modifier.height(if (v2) 12.dp else 8.dp))
                                TransportControls(state, compact = false, modifier = Modifier.align(Alignment.CenterHorizontally), v2 = v2)
                                if (settings.showVolumeBar) {
                                    Spacer(Modifier.height(10.dp))
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        VolumeControl(state, sliderWidth = 220.dp, slot = if (v2) PlayerIconSlot.V2_VOLUME_DOWN else null)
                                    }
                                }
                                val next = state.upNext.firstOrNull()
                                if (settings.showUpNext && next != null) {
                                    Spacer(Modifier.height(14.dp))
                                    UpNextRow(next.title, onClick = { UiState.fullPlayerMode = FullPlayerMode.Queue })
                                }
                                if (hero) Spacer(Modifier.height(24.dp))
                            }
                        }
                    }
                    if (split && !preview) {
                        Spacer(Modifier.width(40.dp * (1f - away)))
                        AnimatedContent(
                            targetState = mode,
                            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                            contentAlignment = Alignment.TopCenter,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) { m ->
                            when (m) {
                                FullPlayerMode.Lyrics -> LyricsView(
                                    song,
                                    Modifier.fillMaxSize().widthIn(max = 900.dp),
                                    style = ConvxType.Lyrics,
                                )
                                FullPlayerMode.Queue -> Column(Modifier.fillMaxSize().padding(vertical = 12.dp)) {
                                    QueueModes(state, settings)
                                    QueueList(state, Modifier.fillMaxWidth().weight(1f), onDark = true)
                                }
                                FullPlayerMode.Comments -> if (song != null) {
                                    CommentsPanel(song.id, Modifier.fillMaxSize().widthIn(max = 760.dp).padding(vertical = 12.dp))
                                } else {
                                    Spacer(Modifier.fillMaxSize())
                                }
                                FullPlayerMode.Artwork -> Spacer(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
        // In front of the cover. It takes no clicks, so the controls under a sticker still work.
        if (stickers != null) DiyStickerLayer(stickers, zFilter = InFrontOfCover, entrance = { entrance.value })
    }
}

/**
 * The cover in the design's shape: Apple Music v2's rounded square, sinking back while paused;
 * a card; a record turning under its grooves; or a clover turning around an upright cover.
 * [corner] is the phone's thumbnail corner radius, null for the design's own; [hidden] puts the
 * Convx mark in the cover's place; [onToggle] is the phone's play/pause on the cover by the lyrics.
 */
@Composable
private fun StageCover(
    url: String?,
    design: PlayerDesign,
    shadow: Boolean,
    playing: Boolean,
    size: Dp,
    still: Boolean,
    corner: Dp? = null,
    hidden: Boolean = false,
    onToggle: (() -> Unit)? = null,
) {
    val toggle = if (onToggle != null) {
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
    } else {
        Modifier
    }
    if (design == PlayerDesign.AppleMusicV2) {
        // Apple's tell: the cover sinks back a little while paused.
        val artScale by animateFloatAsState(if (playing) 1f else 0.86f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow))
        val shape = RoundedCornerShape(corner ?: 14.dp)
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(size)
                    .graphicsLayer {
                        scaleX = artScale
                        scaleY = artScale
                    }
                    .shadow(if (playing) 40.dp else 18.dp, shape)
                    .clip(shape)
                    .then(toggle),
            ) {
                if (hidden) HiddenArtwork(Modifier.fillMaxSize()) else Artwork(url, Modifier.fillMaxSize(), size = 1000, shape = shape, iconSize = 80.dp)
                if (onToggle != null) CoverPlayPause(playing)
            }
        }
        return
    }
    val style = design.coverStyle()
    val shape = style.shape(corner ?: 16.dp)
    val angle by rememberCoverRotation(style, playing && !still)
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                rotationZ = angle
                this.shape = shape
                clip = true
                shadowElevation = if (shadow) 24.dp.toPx() else 0f
            }
            .then(toggle),
    ) {
        // A clover turns around the cover; the cover itself stays upright.
        val upright = Modifier.fillMaxSize().graphicsLayer { rotationZ = if (style.artSpins) 0f else -angle }
        if (hidden) {
            HiddenArtwork(upright)
        } else {
            Artwork(url, upright, size = 1000, shape = RectangleShape, iconSize = 80.dp)
        }
        if (style == CoverStyle.Vinyl) VinylGrooves(Modifier.fillMaxSize())
        // The glyph stays level while a record or clover has stopped mid-turn.
        if (onToggle != null) Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = -angle }) { CoverPlayPause(playing) }
    }
}

/** The cover dims with a play glyph while paused, as the phone's lyrics thumbnail does. */
@Composable
private fun BoxScope.CoverPlayPause(playing: Boolean) {
    val dim by animateFloatAsState(if (playing) 0f else 0.4f)
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = dim)))
    AnimatedVisibility(!playing, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
        ConvxIcon("play_applemusic", tint = Color.White, size = 64.dp)
    }
}

/**
 * Full-screen lyrics' compact player along the top, where the phone collapses its controls: the
 * song on the left, the transport, and the seek bar with its times.
 */
@Composable
private fun LyricsControlBar(state: PlayerState, settings: Settings, modifier: Modifier = Modifier) {
    val song = state.current ?: return
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val art = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
        if (settings.hidePlayerArtwork) HiddenArtwork(art, markSize = 24.dp)
        else Artwork(song.thumbnail, art, size = 200, shape = RoundedCornerShape(8.dp), iconSize = 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).swipeToChangeSong(settings.lyricsSwipeToChange)) {
            Text(song.title, style = ConvxType.Headline, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ArtistLinks(song, ConvxType.Subhead.copy(color = Color.White.copy(alpha = 0.7f)))
        }
        Spacer(Modifier.width(16.dp))
        TransportControls(state, compact = true, v2 = settings.playerDesign == PlayerDesign.AppleMusicV2)
        Spacer(Modifier.width(20.dp))
        ProgressRow(
            state,
            if (settings.playerDesign.classic) settings.seekBarStyle else null,
            Modifier.weight(1f),
            labelColor = Color.White.copy(alpha = 0.6f),
        )
    }
}

/**
 * The phone's "Swipe to change song" with lyrics open. A PC has no finger on the screen, so it's a
 * two-finger sideways swipe on a trackpad (or Shift and the wheel) over the song name, or dragging
 * the name sideways with the mouse: towards the left for the next song, the right for the last.
 */
private fun Modifier.swipeToChangeSong(enabled: Boolean): Modifier {
    if (!enabled) return this
    return pointerInput(Unit) {
        // One swipe changes one song; the scroll that's still coasting after it is ignored.
        var travelled = 0f
        var lastAt = 0L
        var changedAt = 0L
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.first()
                val dx = change.scrollDelta.x
                if (abs(dx) < abs(change.scrollDelta.y) || dx == 0f) continue
                change.consume()
                val now = change.uptimeMillis
                if (now - lastAt > 250) travelled = 0f
                lastAt = now
                if (now - changedAt < 600) continue
                travelled += dx
                if (abs(travelled) >= SwipeScrollLines) {
                    if (travelled > 0) PlayerController.next() else PlayerController.previous()
                    travelled = 0f
                    changedAt = now
                }
            }
        }
    }.pointerInput(Unit) {
        var dragged = 0f
        detectHorizontalDragGestures(
            onDragStart = { dragged = 0f },
            onDragEnd = {
                if (abs(dragged) >= 80.dp.toPx()) {
                    if (dragged < 0) PlayerController.next() else PlayerController.previous()
                }
            },
        ) { change, amount ->
            change.consume()
            dragged += amount
        }
    }
}

/** How far a sideways scroll goes, in wheel notches, before it counts as a swipe. */
private const val SwipeScrollLines = 2.5f

/** The phone's "Show next track": what plays next, under the controls. */
@Composable
private fun UpNextRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("UP NEXT", style = ConvxType.CaptionStrong, color = Color.White.copy(alpha = 0.6f))
        Spacer(Modifier.width(10.dp))
        Text(title, style = ConvxType.Subhead, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The phone's queue buttons: shuffle, repeat, keep playing similar songs when the queue ends, and
 * crossfade. Apple Music v2 has no shuffle or repeat under the cover, so this is where they live.
 */
@Composable
private fun QueueModes(state: PlayerState, settings: Settings) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QueueModeButton(if (state.shuffle) "shuffle_on" else "shuffle", "Shuffle", state.shuffle, Modifier.weight(1f)) { PlayerController.toggleShuffle() }
        QueueModeButton(
            when (state.repeat) {
                RepeatMode.ONE -> "repeat_one_on"
                RepeatMode.ALL -> "repeat_on"
                RepeatMode.OFF -> "repeat"
            },
            when (state.repeat) {
                RepeatMode.ONE -> "Repeat one"
                else -> "Repeat"
            },
            state.repeat != RepeatMode.OFF,
            Modifier.weight(1f),
        ) { PlayerController.cycleRepeat() }
        QueueModeButton("ic_queue_infinity", "Autoplay", settings.autoRadio, Modifier.weight(1f)) {
            AppSettings.update { it.copy(autoRadio = !it.autoRadio) }
        }
        QueueModeButton("ic_queue_crossfade", "Crossfade", settings.crossfade, Modifier.weight(1f)) {
            AppSettings.update { it.copy(crossfade = !it.crossfade) }
        }
    }
}

@Composable
private fun QueueModeButton(icon: String, label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fill by animateColorAsState(if (active) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.14f), tween(180))
    val tint = if (active) Color.Black else Color.White
    // The row's weight goes on this outer box; the tooltip wraps whatever it is given.
    Box(modifier) {
        Tooltip(label) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(fill)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                ConvxIcon(icon, tint = tint, size = 20.dp)
            }
        }
    }
}

@Composable
private fun PartyChip(code: String, members: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(top = 6.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConvxIcon("group", tint = Color.White, size = 14.dp)
        Spacer(Modifier.width(6.dp))
        Text("$code · $members", style = ConvxType.CaptionStrong, color = Color.White)
    }
}
