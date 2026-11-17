/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.EqualizerBars
import com.convx.desktop.ui.components.Gutter
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.ItemMenuContent
import com.convx.desktop.ui.components.LocalPlayback
import com.convx.desktop.ui.components.PlayAllButton
import com.convx.desktop.ui.components.Shelf
import com.convx.desktop.ui.components.artworkUrl
import com.convx.desktop.ui.components.onSecondaryClick
import com.convx.desktop.ui.components.openAtProvider
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.rememberImage
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.components.subtitle
import com.convx.desktop.ui.shapes.ContinuousRoundedRectangle
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import kotlinx.coroutines.launch

// The phone's iOS-style entrance curve, 340 ms.
private val MorphEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/**
 * The phone's featured card that opens Home: the cover full bleed, a frosted, darkened lower band,
 * the song and its artists, and Play and Shuffle pills. The phone draws it 4:3 at phone width; at
 * PC width that would be taller than the window, so here it keeps the width and stops at 420dp.
 */
@Composable
fun HomeHeroCard(
    title: String,
    subtitle: String,
    thumbnail: String?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by AppSettings.state.collectAsState()
    val reduced = settings.reduceLiveBlur
    val image by rememberImage(artworkUrl(thumbnail, 1200))
    val interaction = remember { MutableInteractionSource() }
    // Zooms in from 112% as it fades in, once the cover is there to show; Reduce live blur just shows it.
    val scale = remember { Animatable(1.12f) }
    val alpha = remember { Animatable(0f) }
    val ready = image != null
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        if (reduced) {
            scale.snapTo(1f)
            alpha.snapTo(1f)
            return@LaunchedEffect
        }
        launch { scale.animateTo(1f, tween(340, easing = MorphEasing)) }
        launch { alpha.animateTo(1f, tween(340, easing = MorphEasing)) }
    }
    // The phone's Home card corner radius and Hero card height, when they aren't Auto.
    val shape = ContinuousRoundedRectangle(if (settings.homeCardCorner > 0) settings.homeCardCorner.dp else 18.dp)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val height = if (settings.homeHeroHeight > 0) settings.homeHeroHeight.dp else (maxWidth * 0.75f).coerceAtMost(420.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(shape)
                .background(ConvxColors.CardSecondary)
                .hoverable(interaction)
                .pressable(onClick, interaction = interaction),
        ) {
            val bitmap = image
            if (bitmap != null) {
                Image(
                    bitmap,
                    contentDescription = "$title — $subtitle",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        val s = scale.value
                        scaleX = s
                        scaleY = s
                        this.alpha = alpha.value
                    },
                )
                // The frost: the cover again, blurred, showing only toward the bottom, as the phone bakes it;
                // with Reduce live blur, flat colour in its place.
                if (reduced) {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.3f to Color.Transparent,
                                0.55f to ConvxColors.Card.copy(alpha = 0.65f),
                                0.75f to ConvxColors.Card,
                                1f to ConvxColors.Card,
                            ),
                        ),
                    )
                } else Image(
                    bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                            this.alpha = alpha.value
                        }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.3f to Color.Transparent,
                                    0.55f to Color.Black.copy(alpha = 0.65f),
                                    0.75f to Color.Black,
                                    1f to Color.Black,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                        .blur(24.dp),
                )
            } else {
                ConvxIcon("music_note", tint = Color.White.copy(alpha = 0.28f), size = 56.dp, modifier = Modifier.align(Alignment.Center))
            }
            // Darkened apart from the frost, so the title reads on a bright cover.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.62f))))
            Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
                Text(
                    title,
                    style = ConvxType.Title1.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(subtitle, style = ConvxType.Body, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                    HeroPill("play_applemusic", "Play", onPlay)
                    HeroPill("shuffle", "Shuffle", onShuffle)
                }
            }
        }
    }
}

@Composable
private fun HeroPill(icon: String, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.22f))
            .hoverable(interaction)
            .pressable(onClick, interaction = interaction)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        ConvxIcon(icon, tint = Color.White, size = 17.dp)
        Text(label, style = ConvxType.BodyStrong.copy(fontSize = 17.sp, letterSpacing = (-0.43).sp, fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1)
    }
}

/** The phone's card asking you to sign in to YouTube Music, until you do or close it. */
@Composable
fun HomeSignInCard(onGetStarted: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Gutter)
            .clip(ContinuousRoundedRectangle(20.dp))
            .background(ConvxColors.Card)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            ConvxIcon("music_note", tint = accent, size = 44.dp, modifier = Modifier.padding(top = 8.dp).padding(6.dp))
            Text(
                "Bring your YouTube Music library, playlists and mixes here.",
                style = ConvxType.Body.copy(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.23).sp, fontWeight = FontWeight.Medium),
                color = ConvxColors.Label,
                modifier = Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, end = 8.dp),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(24.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).pressable(onDismiss),
            ) {
                ConvxIcon("close", tint = ConvxColors.SecondaryLabel, size = 12.dp)
            }
        }
        Spacer(Modifier.height(14.dp))
        val interaction = remember { MutableInteractionSource() }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(end = 4.dp)
                .fillMaxWidth()
                .height(44.dp)
                .clip(ContinuousRoundedRectangle(12.dp))
                .background(accent.copy(alpha = 0.12f))
                .hoverable(interaction)
                .pressable(onGetStarted, interaction = interaction),
        ) {
            Text("Get Started", style = ConvxType.BodyStrong.copy(fontSize = 15.sp, letterSpacing = (-0.23).sp, fontWeight = FontWeight.SemiBold), color = accent)
        }
    }
}

/** One row of a list shelf: something to open or play, or the shelf's own action (Surprise me). */
sealed interface ListEntry {
    val id: String

    class Item(val item: YTItem, val onClick: () -> Unit) : ListEntry {
        override val id get() = item.id
    }

    class Action(override val id: String, val icon: String, val title: String, val subtitle: String, val onClick: () -> Unit) : ListEntry
}

/** The phone's list shelf: rows four deep in columns that page sideways. */
@Composable
fun ListShelf(
    title: String,
    entries: List<ListEntry>,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onTitleClick: (() -> Unit)? = null,
    thumbnail: String? = null,
    roundThumbnail: Boolean = false,
    onPlayAll: (() -> Unit)? = null,
) {
    if (entries.isEmpty()) return
    val columns = remember(entries) { entries.distinctBy { it.id }.chunked(4) }
    Shelf(
        title,
        modifier,
        subtitle,
        onTitleClick,
        spacing = 12.dp,
        trailing = if (onPlayAll != null) ({ PlayAllButton(onPlayAll) }) else null,
        thumbnail = thumbnail,
        roundThumbnail = roundThumbnail,
    ) {
        items(columns, key = { it.first().id }) { column ->
            Column(Modifier.width(ListColumnWidth).padding(start = 4.dp)) {
                column.forEach { entry ->
                    when (entry) {
                        is ListEntry.Item -> CompactItemRow(entry.item, entry.onClick)
                        is ListEntry.Action -> ActionRow(entry)
                    }
                }
            }
        }
    }
}

private val ListColumnWidth: Dp = 380.dp

/** A song, album, playlist or artist as one row of a list shelf: 52dp cover, title, and who it's by. */
@Composable
fun CompactItemRow(item: YTItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val playback = LocalPlayback.current
    val isCurrent = item is SongItem && playback.currentId == item.id
    // The phone's heart on liked songs in Home's rows, unless "Hide favorite icon on Home" is on.
    val liked = item is SongItem && !AppSettings.state.collectAsState().value.hideHomeFavoriteIcon &&
        Library.state.collectAsState().value.liked.any { it.song.id == item.id }
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
            .pressable({ if (isCurrent) PlayerController.togglePlay() else onClick() }, interaction = interaction),
    ) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp)) {
                Artwork(
                    item.thumbnail,
                    Modifier.fillMaxSize(),
                    size = 160,
                    shape = RoundedCornerShape(8.dp),
                    placeholderIcon = if (item is ArtistItem) "person" else "music_note",
                    iconSize = 20.dp,
                )
                if (item is SongItem && (hovered || isCurrent)) {
                    Box(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (hovered) ConvxIcon(if (isCurrent && playback.isPlaying) "pause_applemusic" else "play_applemusic", tint = Color.White, size = 22.dp)
                        else EqualizerBars(Color.White, playback.isPlaying)
                    }
                }
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    item.title,
                    style = ConvxType.Body,
                    color = if (isCurrent) LocalAccent.current else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = item.subtitle()
                if (subtitle.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (liked) ConvxIcon("favorite", tint = LocalAccent.current, size = 13.dp, modifier = Modifier.padding(end = 4.dp))
                        if (item.explicit) ConvxIcon("explicit", tint = ConvxColors.SecondaryLabel, size = 13.dp, modifier = Modifier.padding(end = 4.dp))
                        Text(subtitle, style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (hovered) {
                IconButton("more_horiz", { menu.offset = androidx.compose.ui.unit.DpOffset.Zero; menu.expanded = true }, size = 30.dp, iconSize = 18.dp)
            }
        }
        AppMenu(menu) { ItemMenuContent(item) { menu.expanded = false } }
    }
}

/** A list shelf's own action, drawn as a row with an icon where the cover would be. */
@Composable
private fun ActionRow(entry: ListEntry.Action) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (hovered) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .hoverable(interaction)
            .pressable(entry.onClick, interaction = interaction)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(ConvxColors.CardSecondary),
            contentAlignment = Alignment.Center,
        ) {
            ConvxIcon(entry.icon, tint = LocalAccent.current, size = 22.dp)
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(entry.title, style = ConvxType.Body, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.subtitle, style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
