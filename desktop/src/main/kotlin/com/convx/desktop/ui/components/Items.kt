/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.core.formatSeconds
import com.convx.desktop.data.Library
import com.convx.desktop.data.Song
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxShapes
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Page edge inset inside the content panel. */
val Gutter = 24.dp

/**
 * Three bouncing bars: the "now playing" mark on the current row. They move only while music
 * plays and twenty times a second, which reads the same for bars this small and keeps a list
 * from redrawing the window on every frame.
 */
@Composable
fun EqualizerBars(color: Color, playing: Boolean, modifier: Modifier = Modifier, height: Dp = 14.dp) {
    val clock = remember { mutableLongStateOf(0L) }
    val moving = playing && com.convx.desktop.ui.shell.UiState.windowShown
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        val start = System.nanoTime() / 1_000_000 - clock.longValue
        while (true) {
            clock.longValue = System.nanoTime() / 1_000_000 - start
            delay(1000L / EqualizerFps)
        }
    }
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (index in 0..2) {
            val fraction = if (playing) {
                // Up and down over 420, 600 and 780 ms, as three reversing tweens.
                val leg = 420 + index * 180
                0.25f + 0.75f * pingPong((clock.longValue % (2L * leg)) / (2f * leg))
            } else {
                listOf(0.45f, 0.8f, 0.6f)[index]
            }
            Box(Modifier.width(3.dp).height(height * fraction).clip(RoundedCornerShape(1.dp)).background(color))
        }
    }
}

private const val EqualizerFps = 20

/** Header over a [SongRow] table: # · Title · Album · duration. */
@Composable
fun SongTableHeader(showIndex: Boolean = true, showAlbum: Boolean = true, trailingLabel: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showIndex) Text("#", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
            Spacer(Modifier.width(12.dp))
            Text("Title", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel, modifier = Modifier.weight(1f))
            if (showAlbum) Text("Album", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel, modifier = Modifier.weight(0.7f).padding(start = 16.dp))
            if (trailingLabel != null) Text(trailingLabel, style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel, modifier = Modifier.width(110.dp))
            Spacer(Modifier.width(40.dp))
            Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterEnd) {
                ConvxIcon("timer", tint = ConvxColors.SecondaryLabel, size = 16.dp)
            }
            Spacer(Modifier.width(40.dp))
        }
        Divider()
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * One song in a table: number (or play / bars), artwork, title and artist, album,
 * like, duration, more. Click plays; right-click opens the song menu.
 */
@Composable
fun SongRow(
    song: Song,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showArtwork: Boolean = true,
    showAlbum: Boolean = true,
    trailingText: String? = null,
    menuExtra: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
) {
    val navigator = LocalNavigator.current
    val accent = LocalAccent.current
    val playback = LocalPlayback.current
    val library by Library.state.collectAsState()
    val liked = remember(library.liked, song.id) { library.liked.any { it.song.id == song.id } }
    val downloads by com.convx.desktop.player.Downloads.done.collectAsState()
    val isCurrent = playback.currentId == song.id
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val rowMenu = rememberMenuAnchor()
    val buttonMenu = rememberMenuAnchor()

    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered || rowMenu.expanded || buttonMenu.expanded) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .hoverable(interaction)
            .onSecondaryClick(rowMenu.openAtProvider())
            .pressable(onPlay, interaction = interaction, pressScale = 0.995f),
    ) {
        Row(
            Modifier.fillMaxWidth().height(if (showArtwork) 58.dp else 50.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index != null) {
                Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                    when {
                        hovered -> ConvxIcon(
                            if (isCurrent && playback.isPlaying) "pause_applemusic" else "play_applemusic",
                            tint = Color.White,
                            size = 18.dp,
                            modifier = Modifier.pressable({ if (isCurrent) PlayerController.togglePlay() else onPlay() }),
                        )
                        isCurrent -> EqualizerBars(accent, playback.isPlaying)
                        else -> Text("$index", style = ConvxType.Body, color = ConvxColors.SecondaryLabel, textAlign = TextAlign.Center)
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            if (showArtwork) {
                Box(Modifier.size(42.dp)) {
                    Artwork(song.thumbnail, Modifier.fillMaxSize(), size = 120, shape = RoundedCornerShape(6.dp), iconSize = 18.dp)
                    if (index == null && (hovered || isCurrent)) {
                        Box(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (hovered) {
                                ConvxIcon(if (isCurrent && playback.isPlaying) "pause_applemusic" else "play_applemusic", tint = Color.White, size = 20.dp)
                            } else {
                                EqualizerBars(Color.White, playback.isPlaying)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = ConvxType.Body,
                    color = if (isCurrent) accent else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The phone marks a downloaded song beside its artist.
                    if (song.id in downloads) {
                        ConvxIcon("download", tint = accent, size = 13.dp, modifier = Modifier.padding(end = 4.dp))
                    }
                    if (song.explicit) {
                        ConvxIcon("explicit", tint = ConvxColors.SecondaryLabel, size = 14.dp, modifier = Modifier.padding(end = 4.dp))
                    }
                    ArtistLinks(song, ConvxType.Subhead.copy(color = ConvxColors.SecondaryLabel))
                }
            }
            if (showAlbum) {
                val album = song.album
                Box(Modifier.weight(0.7f).padding(start = 16.dp)) {
                    if (album != null) {
                        LinkText(album.name, ConvxType.Subhead.copy(color = ConvxColors.SecondaryLabel)) {
                            navigator.navigate(Screen.Album(album.id))
                        }
                    }
                }
            }
            if (trailingText != null) {
                Text(trailingText, style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel, maxLines = 1, modifier = Modifier.width(110.dp))
            }
            Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                if (liked || hovered) {
                    IconButton(
                        if (liked) "favorite" else "favorite_border",
                        { Library.toggleLike(song) },
                        size = 30.dp,
                        iconSize = 17.dp,
                        tint = if (liked) accent else ConvxColors.SecondaryLabel,
                        tooltip = if (liked) "Remove from Liked Songs" else "Add to Liked Songs",
                    )
                }
            }
            Text(
                formatSeconds(song.duration),
                style = ConvxType.Subhead,
                color = ConvxColors.SecondaryLabel,
                textAlign = TextAlign.End,
                modifier = Modifier.width(52.dp),
            )
            Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                if (hovered || buttonMenu.expanded) {
                    IconButton("more_horiz", { buttonMenu.expanded = true }, size = 30.dp, iconSize = 18.dp, tooltip = "More options")
                }
                AppMenu(buttonMenu) {
                    SongMenuContent(song, { buttonMenu.expanded = false }, menuExtra)
                }
            }
        }
        AppMenu(rowMenu) {
            SongMenuContent(song, { rowMenu.expanded = false }, menuExtra)
        }
    }
}

/** Artist names, each one a link to its artist page. */
@Composable
fun ArtistLinks(song: Song, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    Row(modifier) {
        song.artists.forEachIndexed { i, artist ->
            val id = artist.id
            val text = if (i < song.artists.lastIndex) artist.name + ", " else artist.name
            if (id != null) {
                LinkText(text, style, modifier = Modifier.weight(1f, fill = false)) { navigator.navigate(Screen.Artist(id)) }
            } else {
                Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
}

/** Text that underlines on hover, like Spotify's inline links. */
@Composable
fun LinkText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    hoverColor: Color = Color.White,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        text,
        style = style.copy(
            color = if (hovered) hoverColor else style.color,
            textDecoration = if (hovered) TextDecoration.Underline else null,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .pressable(onClick, interaction = interaction, pressScale = 1f),
    )
}

/** The phone's home shelf list row: 52dp cover, 15sp title, 13sp subtitle. */
@Composable
fun CompactSongRow(
    song: Song,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    /** The phone's heart on a liked song; Home's rows can turn it off. */
    showLiked: Boolean = true,
    /** A note at the row's end, such as YouTube's play count on an artist's top songs. */
    trailing: String? = null,
) {
    val playback = LocalPlayback.current
    val accent = LocalAccent.current
    val liked = showLiked && Library.state.collectAsState().value.liked.any { it.song.id == song.id }
    val isCurrent = playback.currentId == song.id
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val menu = rememberMenuAnchor()
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (hovered || menu.expanded) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .hoverable(interaction)
            .onSecondaryClick(menu.openAtProvider())
            .pressable(onPlay, interaction = interaction, pressScale = 0.99f),
    ) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp)) {
                Artwork(song.thumbnail, Modifier.fillMaxSize(), size = 160, iconSize = 20.dp)
                if (hovered || isCurrent) {
                    Box(
                        Modifier.fillMaxSize().clip(ConvxShapes.Artwork).background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (hovered) ConvxIcon(if (isCurrent && playback.isPlaying) "pause_applemusic" else "play_applemusic", tint = Color.White, size = 22.dp)
                        else EqualizerBars(Color.White, playback.isPlaying)
                    }
                }
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(song.title, style = ConvxType.Body, color = if (isCurrent) accent else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (liked) ConvxIcon("favorite", tint = accent, size = 13.dp, modifier = Modifier.padding(end = 4.dp))
                    if (song.explicit) ConvxIcon("explicit", tint = ConvxColors.SecondaryLabel, size = 13.dp, modifier = Modifier.padding(end = 4.dp))
                    Text(
                        listOfNotNull(song.artistText.ifBlank { null }, song.album?.name).joinToString(" • "),
                        style = ConvxType.Subhead,
                        color = ConvxColors.SecondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Text(
                    trailing,
                    style = ConvxType.Subhead,
                    color = ConvxColors.SecondaryLabel,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 10.dp, end = 4.dp),
                )
            }
            if (hovered) {
                IconButton("more_horiz", { menu.offset = androidx.compose.ui.unit.DpOffset.Zero; menu.expanded = true }, size = 30.dp, iconSize = 18.dp)
            }
        }
        AppMenu(menu) { SongMenuContent(song, { menu.expanded = false }) }
    }
}

/**
 * Album / playlist / artist / song card: square cover (round for artists), title,
 * subtitle, and the white play disc that rises in on hover.
 */
@Composable
fun ItemCard(
    item: YTItem,
    modifier: Modifier = Modifier,
    width: Dp = LocalCardWidth.current,
    onClick: (() -> Unit)? = null,
    /** What the card says, where it isn't the item's own: Stats ranks and counts. */
    title: String = item.title,
    subtitle: String = item.subtitle(),
) {
    val navigator = LocalNavigator.current
    val playback = LocalPlayback.current
    val isArtist = item is ArtistItem
    val shape: Shape = if (isArtist) CircleShape else ConvxShapes.Artwork
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val menu = rememberMenuAnchor()
    val isCurrent = item is SongItem && playback.currentId == item.id
    Box(
        modifier
            .width(width)
            .clip(ConvxShapes.Card)
            .background(if (hovered || menu.expanded) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .hoverable(interaction)
            .onSecondaryClick(menu.openAtProvider())
            .pressable(onClick ?: { ItemActions.open(item, navigator) }, interaction = interaction, pressScale = 0.98f),
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                Artwork(
                    item.thumbnail,
                    Modifier.fillMaxSize().shadow(if (hovered) 14.dp else 6.dp, shape),
                    size = 400,
                    shape = shape,
                    placeholderIcon = if (isArtist) "person" else "music_note",
                    iconSize = 40.dp,
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = hovered || isCurrent,
                    enter = fadeIn() + slideInVertically { it / 3 },
                    exit = fadeOut() + slideOutVertically { it / 3 },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                ) {
                    PlayButton(
                        isPlaying = isCurrent && playback.isPlaying,
                        onClick = { if (isCurrent) PlayerController.togglePlay() else ItemActions.play(item) },
                        size = 46.dp,
                        modifier = Modifier.shadow(10.dp, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                style = ConvxType.Body,
                color = if (isCurrent) LocalAccent.current else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = ConvxType.Subhead,
                    color = ConvxColors.SecondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        AppMenu(menu) { ItemMenuContent(item) { menu.expanded = false } }
    }
}

/** Home's "Top Picks" hero card: 242 x 323, cover on top, frosted reflection underneath. */
@Composable
fun TopPickCard(item: YTItem, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val navigator = LocalNavigator.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val menu = rememberMenuAnchor()
    val settings by AppSettings.state.collectAsState()
    // One of Home's cards, so the phone's Home card corner radius rounds it too.
    val shape = RoundedCornerShape(if (settings.homeCardCorner > 0) settings.homeCardCorner.dp else 20.dp)
    val image by rememberImage(artworkUrl(item.thumbnail, 544))
    val label = when (item) {
        is SongItem -> item.artists.firstOrNull()?.name
        is AlbumItem -> item.artists?.firstOrNull()?.name
        is PlaylistItem -> item.author?.name
        is ArtistItem -> null
    }
    val detail = when (item) {
        is AlbumItem -> item.year?.toString()
        is SongItem -> item.album?.name
        else -> null
    }
    Box(
        modifier
            .width(242.dp)
            .height(323.dp)
            .graphicsLayer {
                val s = if (hovered) 1.015f else 1f
                scaleX = s
                scaleY = s
            }
            .shadow(20.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
            .clip(shape)
            .background(ConvxColors.CardSecondary)
            .hoverable(interaction)
            .onSecondaryClick(menu.openAtProvider())
            .pressable(onClick ?: { ItemActions.open(item, navigator) }, interaction = interaction, pressScale = 0.98f),
    ) {
        val bitmap = image
        if (bitmap != null) {
            // The reflection: the cover flipped, blurred and dimmed, filling the caption strip.
            // Reduce live blur leaves the strip a flat colour.
            // On a Mac the strip is always blurred: it is a still picture, not a moving one.
            if (!settings.reduceLiveBlur || com.convx.desktop.ui.glass.IsMac) Image(
                bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.tint(Color(0xFFC7C7C7), BlendMode.Multiply),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(120.dp)
                    .graphicsLayer { scaleY = -1f }
                    .blur(28.dp),
            )
            Image(
                bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.align(Alignment.TopCenter).size(242.dp),
            )
        } else {
            Box(Modifier.align(Alignment.TopCenter).size(242.dp), contentAlignment = Alignment.Center) {
                ConvxIcon("music_note", tint = Color.White.copy(alpha = 0.28f), size = 48.dp)
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(81.dp).padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(item.title, style = ConvxType.BodyStrong, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (label != null) Text(label, style = ConvxType.Subhead, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (detail != null) Text(detail, style = ConvxType.Subhead, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        AnimatedVisibility(
            visible = hovered,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        ) {
            PlayButton(false, { ItemActions.play(item) }, size = 44.dp, modifier = Modifier.shadow(10.dp, CircleShape))
        }
        AppMenu(menu) { ItemMenuContent(item) { menu.expanded = false } }
    }
}

/**
 * A titled sideways row with Apple Music desktop's page arrows. [content] fills the
 * LazyRow; the arrows page by most of a viewport.
 */
@Composable
fun Shelf(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onTitleClick: (() -> Unit)? = null,
    spacing: Dp = 4.dp,
    trailing: @Composable (() -> Unit)? = null,
    thumbnail: String? = null,
    roundThumbnail: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val pagePx = constraints.maxWidth * 0.8f
        Column(Modifier.fillMaxWidth()) {
            SectionHeader(
                title = title,
                subtitle = subtitle,
                onAction = onTitleClick,
                modifier = Modifier.padding(horizontal = Gutter),
                thumbnail = thumbnail,
                roundThumbnail = roundThumbnail,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (trailing != null) trailing()
                        IconButton(
                            "chevron_leftpx",
                            { scope.launch { state.animateScrollBy(-pagePx) } },
                            size = 32.dp,
                            iconSize = 20.dp,
                            enabled = state.canScrollBackward,
                        )
                        IconButton(
                            "chevron_right_px",
                            { scope.launch { state.animateScrollBy(pagePx) } },
                            size = 32.dp,
                            iconSize = 20.dp,
                            enabled = state.canScrollForward,
                        )
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            // Runs out under the side bar, so cards scroll beneath its glass. It stops at the Now
            // Playing panel, whose lyrics would be hard to read over covers.
            val bleed = LocalPageInsets.current.start
            LazyRow(
                state = state,
                contentPadding = PaddingValues(start = bleed + Gutter - 10.dp, end = Gutter - 10.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                modifier = Modifier.bleedStart(bleed).fillMaxWidth(),
            ) {
                content()
            }
        }
    }
}

/** Shelf of cards for any YouTube items. */
@Composable
fun ItemShelf(
    title: String,
    items: List<YTItem>,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onTitleClick: (() -> Unit)? = null,
    cardWidth: Dp = LocalCardWidth.current,
    thumbnail: String? = null,
    roundThumbnail: Boolean = false,
    onPlayAll: (() -> Unit)? = null,
) {
    if (items.isEmpty()) return
    Shelf(
        title,
        modifier,
        subtitle,
        onTitleClick,
        trailing = if (onPlayAll != null) ({ PlayAllButton(onPlayAll) }) else null,
        thumbnail = thumbnail,
        roundThumbnail = roundThumbnail,
    ) {
        items(items.distinctBy { it.id }, key = { it.id }) { item -> ItemCard(item, width = cardWidth) }
    }
}

/** The phone's play-all button beside a shelf's title. */
@Composable
fun PlayAllButton(onClick: () -> Unit) {
    PillButton("Play all", onClick, icon = "play_applemusic", contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp))
    Spacer(Modifier.width(8.dp))
}

/** Songs four deep in sideways-paging columns, the phone's song shelf. */
@Composable
fun SongShelf(
    title: String,
    songs: List<Song>,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onTitleClick: (() -> Unit)? = null,
    /** YouTube's play count by song id, shown at each row's end (an artist's top songs). */
    plays: Map<String, String> = emptyMap(),
    onPlay: (Int) -> Unit,
) {
    if (songs.isEmpty()) return
    val columns = remember(songs) { songs.withIndex().chunked(4) }
    Shelf(
        title,
        modifier,
        subtitle,
        onTitleClick,
        spacing = 12.dp,
        trailing = { PlayAllButton { onPlay(0) } },
    ) {
        items(columns, key = { it.first().value.id + it.first().index }) { column ->
            Column(Modifier.width(380.dp).padding(start = 4.dp)) {
                column.forEach { (index, song) -> CompactSongRow(song, { onPlay(index) }, trailing = plays[song.id]) }
            }
        }
    }
}
