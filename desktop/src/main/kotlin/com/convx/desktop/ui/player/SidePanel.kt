/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.formatSeconds
import com.convx.desktop.data.Library
import com.convx.desktop.data.Song
import com.convx.desktop.player.PlayerController
import com.convx.desktop.player.PlayerState
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.ArtistLinks
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.EqualizerBars
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.LocalPlayback
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.components.SongMenuContent
import com.convx.desktop.ui.components.onSecondaryClick
import com.convx.desktop.ui.components.openAtProvider
import com.convx.desktop.ui.components.panel
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.rememberHeroColors
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.SidePanel
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxShapes
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent
import kotlin.math.roundToInt
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.ui.glass.GlassConfig
import com.convx.desktop.ui.glass.GlassShapes
import com.convx.desktop.ui.glass.liquidGlass
import com.convx.desktop.ui.glass.rememberInteractiveHighlight
import com.convx.desktop.ui.shell.FullPlayerMode
import com.convx.desktop.ui.components.ScrollbarColumn
import com.convx.desktop.ui.components.ListScrollbar

/** How wide the Now Playing panel on the right of the window is. */
val NowPlayingPanelWidth: Dp = 340.dp

/** The live lyrics card's window onto the song. */
private val LyricsCardHeight = 330.dp

/**
 * Spotify's Now Playing view, built from the phone's pieces: the same glass as Your Library, on
 * the right of the window. Now Playing shows the cover, the song with its heart, YouTube's play
 * count, a card of lyrics that follows the song (its corner button opens the player's lyrics),
 * the album and what plays next. Lyrics and Queue fill the panel instead.
 */
@Composable
fun RightPanel(panel: SidePanel, modifier: Modifier = Modifier) {
    val state by PlayerController.state.collectAsState()
    val shape = GlassShapes.Panel
    // Measured against the whole panel, so a small radius keeps the glow near the pointer.
    val glow = rememberInteractiveHighlight(radiusScale = 0.3f)
    Column(
        modifier
            .width(NowPlayingPanelWidth)
            .fillMaxHeight()
            .clip(shape)
            .liquidGlass(shape, GlassConfig.SidePanel, highlightAlpha = 0.3f)
            .then(glow.modifier)
            .then(glow.gestureModifier),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).clip(GlassShapes.Capsule).background(Color.White.copy(alpha = 0.08f)).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (p in SidePanel.entries) {
                    val selected = p == panel
                    Box(
                        Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(GlassShapes.Capsule)
                            .background(if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                            .pressable({ UiState.openSidePanel(p) }, pressScale = 0.97f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when (p) {
                                SidePanel.NowPlaying -> "Now Playing"
                                SidePanel.Lyrics -> "Lyrics"
                                SidePanel.Queue -> "Queue"
                            },
                            style = ConvxType.SubheadStrong,
                            color = if (selected) Color.White else ConvxColors.SecondaryLabel,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            if (panel == SidePanel.Lyrics) LyricsMenuButton(state.current, size = 32.dp, iconSize = 18.dp, filled = false)
            IconButton("close", { UiState.closeSidePanel() }, size = 32.dp, iconSize = 18.dp, tooltip = "Close")
        }
        Crossfade(panel, modifier = Modifier.weight(1f)) { p ->
            when (p) {
                SidePanel.NowPlaying -> NowPlayingPanel(state)
                // The full player covers the panel; its own lyrics are the ones to keep running.
                SidePanel.Lyrics -> Box(Modifier.fillMaxSize()) {
                    if (!UiState.fullPlayer) {
                        LyricsView(
                            state.current,
                            Modifier.fillMaxSize(),
                            style = ConvxType.LyricsCompact,
                            horizontalPadding = 16.dp,
                        )
                    }
                }
                SidePanel.Queue -> QueueList(state, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun NowPlayingPanel(state: PlayerState) {
    val song = state.current
    if (song == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Nothing playing yet.\nPick something to listen to.", style = ConvxType.Body, color = ConvxColors.SecondaryLabel)
        }
        return
    }
    val library by Library.state.collectAsState()
    val settings by com.convx.desktop.data.AppSettings.state.collectAsState()
    val liked = library.liked.any { it.song.id == song.id }
    val navigator = LocalNavigator.current
    val menu = rememberMenuAnchor()
    // A bar to drag beside it, for a mouse or touchpad without a wheel.
    ScrollbarColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp)) {
        val coverShape = RoundedCornerShape(14.dp)
        val coverModifier = Modifier.fillMaxWidth().aspectRatio(1f).shadow(18.dp, coverShape).pressable({ UiState.fullPlayer = true }, pressScale = 0.99f)
        if (settings.hidePlayerArtwork) {
            HiddenArtwork(coverModifier.clip(coverShape), markSize = 96.dp)
        } else {
            Artwork(song.thumbnail, coverModifier, size = 800, shape = coverShape, iconSize = 56.dp)
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(song.title, style = ConvxType.Title2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ArtistLinks(song, ConvxType.Body.copy(color = ConvxColors.SecondaryLabel))
            }
            IconButton(
                if (liked) "favorite" else "favorite_border",
                { Library.toggleLike(song) },
                tint = if (liked) LocalAccent.current else Color.White,
                tooltip = if (liked) "Remove from Liked Songs" else "Add to Liked Songs",
                slot = if (liked) com.convx.desktop.ui.components.PlayerIconSlot.LIKED else com.convx.desktop.ui.components.PlayerIconSlot.LIKE,
            )
            Box {
                IconButton("more_horiz", { menu.expanded = true }, tooltip = "More options")
                AppMenu(menu) { SongMenuContent(song, { menu.expanded = false }) }
            }
        }
        // YouTube's own count for the video this plays: Convx has no Spotify numbers to show.
        val stream = state.stream?.takeIf { it.videoId == song.id }
        stream?.viewCount?.let { views ->
            Spacer(Modifier.height(6.dp))
            Text(
                "${java.text.NumberFormat.getIntegerInstance().format(views)} plays on YouTube",
                style = ConvxType.Subhead,
                color = ConvxColors.SecondaryLabel,
            )
        }
        stream?.takeIf { settings.showAudioQualityBadge }?.let {
            Spacer(Modifier.height(10.dp))
            QualityBadge(it.badge, it.detail, ConvxColors.SecondaryLabel)
        }
        Spacer(Modifier.height(18.dp))
        LyricsCard(song)
        song.album?.let { album ->
            Spacer(Modifier.height(12.dp))
            InfoCard("From the album") {
                Row(
                    Modifier.fillMaxWidth().pressable({ navigator.navigate(Screen.Album(album.id)) }, pressScale = 0.99f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConvxIcon("album", tint = ConvxColors.SecondaryLabel, size = 22.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(album.name, style = ConvxType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    ConvxIcon("chevron_right_px", tint = ConvxColors.SecondaryLabel, size = 20.dp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        InfoCard("Next in queue", action = "Open queue", onAction = { UiState.openSidePanel(SidePanel.Queue) }) {
            val next = state.upNext.firstOrNull()
            if (next == null) {
                Text("Nothing up next", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
            } else {
                QueueRow(next, state.index + 1, false, Modifier, removable = false)
            }
        }
        // Room to scroll the last card clear of the pill that floats over the window's foot.
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Spotify's lyrics card: the phone's lyrics in the chosen style, following the song in a short
 * window. The corner button opens them in the player, as Spotify's opens its lyrics page.
 */
@Composable
private fun LyricsCard(song: Song) {
    Column(Modifier.fillMaxWidth().clip(ConvxShapes.Card).background(Color.White.copy(alpha = 0.07f))) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Lyrics", style = ConvxType.Headline, modifier = Modifier.weight(1f))
            IconButton(
                "lyrics_expand",
                {
                    UiState.fullPlayerMode = FullPlayerMode.Lyrics
                    UiState.lyricsFullscreen = com.convx.desktop.data.AppSettings.value.oneTapFullscreenLyrics
                    UiState.fullPlayer = true
                },
                size = 32.dp,
                iconSize = 15.dp,
                tooltip = "Open lyrics",
            )
        }
        Box(Modifier.fillMaxWidth().height(LyricsCardHeight).clipToBounds()) {
            // The full player covers the panel; its own lyrics are the ones to keep running.
            if (!UiState.fullPlayer) {
                LyricsView(
                    song,
                    Modifier.fillMaxSize(),
                    // Big enough to fill the card, as Spotify's card does: about 18 letters a line.
                    style = ConvxType.LyricsCompact.copy(fontSize = 24.sp, lineHeight = 30.sp),
                    horizontalPadding = 10.dp,
                )
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, action: String? = null, onAction: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(ConvxShapes.Card).background(Color.White.copy(alpha = 0.07f)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = ConvxType.Headline, modifier = Modifier.weight(1f))
            if (action != null && onAction != null) {
                Text(
                    action,
                    style = ConvxType.SubheadStrong,
                    color = ConvxColors.SecondaryLabel,
                    modifier = Modifier.pressable(onAction),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** Now playing + Next up, with drag to reorder and hover to remove. */
@Composable
fun QueueList(state: PlayerState, modifier: Modifier = Modifier, onDark: Boolean = false) {
    val listState = rememberLazyListState()
    val upNext = state.upNext
    val current = state.current
    Box(modifier) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 14.dp, top = 4.dp, bottom = 4.dp)) {
            if (current != null) {
                sectionTitle("Now playing")
                item(key = "current") { QueueRow(current, state.index, true, Modifier, removable = false) }
            }
            if (upNext.isNotEmpty()) {
                item(key = "next_header") {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Next up", style = ConvxType.Headline)
                            state.queueTitle?.let { Text("From $it", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        PillButton("Clear", { PlayerController.clearUpNext() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp))
                    }
                }
                itemsIndexed(upNext, key = { i, song -> "q${state.index + 1 + i}:${song.id}" }) { i, song ->
                    QueueRow(song, state.index + 1 + i, false, Modifier, removable = true, draggable = true)
                }
            } else if (current == null) {
                item {
                    Text(
                        "Your queue is empty.",
                        style = ConvxType.Body,
                        color = ConvxColors.SecondaryLabel,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        ListScrollbar(listState)
    }
}

private fun LazyListScope.sectionTitle(text: String) {
    item(key = "title_$text") {
        Text(text, style = ConvxType.Headline, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 6.dp))
    }
}

@Composable
fun QueueRow(
    song: Song,
    position: Int,
    isCurrent: Boolean,
    modifier: Modifier = Modifier,
    removable: Boolean,
    draggable: Boolean = false,
) {
    val playback = LocalPlayback.current
    val accent = LocalAccent.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val menu = rememberMenuAnchor()
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 60.dp.toPx() }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = dragOffset
                shadowElevation = if (dragging) 12f else 0f
            }
            .clip(ConvxShapes.Control)
            .background(
                when {
                    dragging -> Color(0xFF3A3A3C)
                    hovered || menu.expanded -> Color.White.copy(alpha = 0.07f)
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .onSecondaryClick(menu.openAtProvider())
            .pressable({ if (isCurrent) PlayerController.togglePlay() else PlayerController.skipTo(position) }, interaction = interaction, pressScale = 0.99f),
    ) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp)) {
                Artwork(song.thumbnail, Modifier.fillMaxSize(), size = 120, shape = RoundedCornerShape(6.dp), iconSize = 18.dp)
                if (isCurrent) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        EqualizerBars(Color.White, playback.isPlaying)
                    }
                }
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                // White on the artwork-tinted panels; the equalizer on the cover already marks it.
                Text(song.title, style = if (isCurrent) ConvxType.BodyStrong else ConvxType.Body, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artistText, style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (hovered && removable) {
                IconButton("remove", { PlayerController.removeFromQueue(position) }, size = 30.dp, iconSize = 18.dp, tooltip = "Remove from queue")
            } else {
                Text(formatSeconds(song.duration), style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
            }
            if (draggable) {
                Box(
                    Modifier
                        .padding(start = 4.dp)
                        .size(28.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .pointerInput(position) {
                            detectDragGestures(
                                onDragStart = { dragging = true },
                                onDragEnd = {
                                    val steps = (dragOffset / rowHeightPx).roundToInt()
                                    if (steps != 0) PlayerController.moveInQueue(position, (position + steps).coerceAtLeast(0))
                                    dragOffset = 0f
                                    dragging = false
                                },
                                onDragCancel = {
                                    dragOffset = 0f
                                    dragging = false
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    ConvxIcon("drag_handle", tint = ConvxColors.SecondaryLabel, size = 18.dp)
                }
            }
        }
        AppMenu(menu) {
            SongMenuContent(song, { menu.expanded = false }) { dismiss ->
                if (removable) {
                    MenuItem("Remove from queue", "remove") {
                        PlayerController.removeFromQueue(position)
                        dismiss()
                    }
                }
            }
        }
    }
}
