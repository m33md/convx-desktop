/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.data.Library
import com.convx.desktop.data.toSaved
import com.convx.desktop.data.toSong
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.CardGrid
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.Gutter
import com.convx.desktop.ui.components.ItemActions
import com.convx.desktop.ui.components.ItemCard
import com.convx.desktop.ui.components.ItemShelf
import com.convx.desktop.ui.components.LoadingState
import com.convx.desktop.ui.components.LocalPlayback
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.components.MessageState
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.SongShelf
import com.convx.desktop.ui.components.artworkUrl
import com.convx.desktop.ui.components.link
import com.convx.desktop.ui.components.onSecondaryClick
import com.convx.desktop.ui.components.openAtProvider
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.rememberImage
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.components.LocalPageInsets
import com.convx.desktop.ui.theme.ConvxType
import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.BrowseEndpoint
import com.music.innertube.models.SongItem
import com.music.innertube.pages.ArtistSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The phone's square artist hero: 393 wide, 405 of photo, 495 with the tail that melts into the page. */
private const val HeroExtent = 495f / 393f
private const val PhotoExtent = 405f / 393f

/** The phone's artist text tiers on the tinted page. */
private val Secondary = Color.White.copy(alpha = 0.6f)

/**
 * The phone's artist page at desktop width: the whole page takes the photo's colour, the
 * square photo sits on the left and melts into it, and the name, the info / play / star
 * buttons and the latest release card stand beside it. Top songs follow in the phone's
 * four-row columns, then the shelves and the biography.
 */
@Composable
fun ArtistScreen(browseId: String) {
    val navigator = LocalNavigator.current
    val library by Library.state.collectAsState()
    val playback = LocalPlayback.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val page = rememberPage("artist:$browseId") { YouTube.artist(browseId).getOrThrow() }
    // Convx black, not the photo's colour: the app keeps its own look on every page.
    val pageColor = Color.Black
    val listState = rememberLazyListState()
    val menu = rememberMenuAnchor()

    BoxWithConstraints(Modifier.fillMaxSize().background(pageColor)) {
        val heroSide = (maxWidth * 0.42f).coerceAtMost(380.dp)
        val nameSize = if (maxWidth < 900.dp) 34.sp else 44.sp

        PageContent(page, Modifier.padding(top = LocalPageInsets.current.top)) { data ->
            val artist = data.artist
            val followed = library.artists.any { it.id == artist.id }
            val songSection = data.sections.firstOrNull { section -> section.items.isNotEmpty() && section.items.all { it is SongItem } }
            val topSongs = remember(songSection) { songSection?.items?.filterIsInstance<SongItem>()?.distinctBy { it.id }?.map { it.toSong() }.orEmpty() }
            // What YouTube Music prints beside each top song: its plays there, not Spotify's.
            val topPlays = remember(songSection) {
                songSection?.items?.filterIsInstance<SongItem>()?.mapNotNull { s -> s.plays?.let { s.id to it } }?.toMap().orEmpty()
            }
            val featured = remember(data) { data.sections.featuredRelease() }
            val isThisPlaying = playback.isPlaying &&
                (topSongs.any { it.id == playback.currentId } || PlayerController.state.value.queueTitle == artist.title)

            val play: () -> Unit = {
                when {
                    isThisPlaying -> PlayerController.pause()
                    topSongs.isNotEmpty() -> PlayerController.playSongs(topSongs, 0, artist.title, shuffle = false)
                    artist.playEndpoint != null -> PlayerController.playEndpoint(artist.playEndpoint!!, artist.title)
                    else -> ItemActions.play(artist)
                }
            }
            val follow: () -> Unit = {
                Library.toggleArtist(artist.toSaved())
                Overlays.toast(if (followed) "Unfollowed ${artist.title}" else "Following ${artist.title}")
            }

            PageList(state = listState, tint = pageColor, topPadding = 0.dp) {
                item(key = "header") {
                    Box(Modifier.onSecondaryClick(menu.openAtProvider())) {
                        Row(Modifier.fillMaxWidth()) {
                            ArtistHeroImage(artist.thumbnail, pageColor, Modifier.width(heroSide))
                            Column(
                                Modifier
                                    .weight(1f)
                                    .height(heroSide * HeroExtent)
                                    .padding(start = 24.dp, end = 32.dp, top = LocalPageInsets.current.top),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    artist.title,
                                    style = ConvxType.Body.copy(fontSize = nameSize, lineHeight = nameSize * 1.12f, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val audience = data.monthlyListenerCount ?: data.subscriberCountText
                                if (!audience.isNullOrBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(audience, style = ConvxType.Body, color = Secondary)
                                }
                                Spacer(Modifier.height(18.dp))
                                ArtistActionRow(
                                    pageColor = pageColor,
                                    isPlaying = isThisPlaying,
                                    followed = followed,
                                    onInfo = {
                                        scope.launch { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
                                    },
                                    onPlay = play,
                                    onFollow = follow,
                                )
                                if (featured != null) {
                                    Spacer(Modifier.height(22.dp))
                                    FeaturedReleaseCard(
                                        album = featured.second,
                                        caption = featured.second.year?.toString() ?: featured.first,
                                        pageColor = pageColor,
                                        onClick = { navigator.navigate(Screen.Album(featured.second.browseId)) },
                                        modifier = Modifier.widthIn(max = 420.dp),
                                    )
                                }
                            }
                        }
                        AppMenu(menu) {
                            MenuItem("Shuffle", "shuffle") {
                                ItemActions.play(artist, shuffle = true)
                                menu.expanded = false
                            }
                            if (artist.radioEndpoint != null) {
                                MenuItem("Start artist radio", "radio") {
                                    ItemActions.radio(artist)
                                    menu.expanded = false
                                }
                            }
                            MenuItem("Copy link", "link") {
                                clipboard.setText(AnnotatedString(artist.link()))
                                Overlays.toast("Link copied", "link")
                                menu.expanded = false
                            }
                        }
                    }
                }

                if (songSection != null && topSongs.isNotEmpty()) {
                    item(key = "top-songs") {
                        Spacer(Modifier.height(8.dp))
                        SongShelf(
                            songSection.title.ifBlank { "Top Songs" },
                            topSongs,
                            onTitleClick = songSection.moreEndpoint?.let { more ->
                                { navigator.navigate(Screen.ArtistItems(more.browseId, more.params, "${artist.title}: ${songSection.title}")) }
                            },
                            plays = topPlays,
                            onPlay = { i -> PlayerController.playSongs(topSongs, i, artist.title) },
                        )
                        Spacer(Modifier.height(20.dp))
                    }
                }

                data.sections
                    .filter { it !== songSection && it.items.isNotEmpty() }
                    .forEachIndexed { index, section ->
                        item(key = "sec-$index-${section.title}") {
                            val more = section.moreEndpoint
                            ItemShelf(
                                section.title,
                                section.items,
                                onTitleClick = more?.let { { navigator.navigate(Screen.ArtistItems(it.browseId, it.params, "${artist.title}: ${section.title}")) } },
                            )
                            Spacer(Modifier.height(20.dp))
                        }
                    }

                data.description?.takeIf { it.isNotBlank() }?.let { bio ->
                    item(key = "biography") { Biography(bio) }
                }
            }
        }
    }
}

/**
 * YouTube's "Latest release" when the page has one, otherwise the first album or single
 * shelf's lead item: the phone always shows the card.
 */
private fun List<ArtistSection>.featuredRelease(): Pair<String, AlbumItem>? =
    (firstOrNull { it.title.contains("Latest", ignoreCase = true) && it.items.firstOrNull() is AlbumItem }
        ?: firstOrNull { it.items.firstOrNull() is AlbumItem })
        ?.let { it.title to it.items.first() as AlbumItem }

/** Square photo with a mirrored, blurred tail, all washed into [pageColor] toward the foot. */
@Composable
private fun ArtistHeroImage(url: String?, pageColor: Color, modifier: Modifier = Modifier) {
    val image by rememberImage(artworkUrl(url, 1200))
    val reduced = com.convx.desktop.data.AppSettings.state.collectAsState().value.reduceLiveBlur
    BoxWithConstraints(modifier) {
        val side = maxWidth
        Box(Modifier.width(side).height(side * HeroExtent).clipToBounds()) {
            val bitmap = image
            if (bitmap != null) {
                // Reduce live blur: no blurred tail, the page colour washes up to the photo instead.
                if (!reduced) Image(
                    bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset(y = side * PhotoExtent)
                        .width(side)
                        .height(side * PhotoExtent)
                        .graphicsLayer { scaleY = -1f }
                        .blur(36.dp),
                )
                Image(
                    bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(side).height(side * PhotoExtent),
                )
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFF3A3A3C), 1f to pageColor)))
            }
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(colorStops = heroWash(pageColor))))
            // The phone's photo spans the screen; beside the name it also melts sideways into the page.
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(colorStops = sideWash(pageColor))))
        }
    }
}

private fun heroWash(pageColor: Color): Array<Pair<Float, Color>> {
    val steps = 32
    return Array(steps + 1) { i ->
        val position = i / steps.toFloat()
        position to pageColor.copy(alpha = smoothstep(307f / 393f, 1.21f, position * HeroExtent))
    }
}

private fun sideWash(pageColor: Color): Array<Pair<Float, Color>> {
    val steps = 16
    return Array(steps + 1) { i ->
        val position = i / steps.toFloat()
        position to pageColor.copy(alpha = smoothstep(0.62f, 1f, position))
    }
}

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Info / play / star: 50, 73, 50 with 24 between, as on the phone. Play is white with the
 * page colour pulled 30% to black as its glyph; the side buttons are an 8% white wash.
 */
@Composable
private fun ArtistActionRow(
    pageColor: Color,
    isPlaying: Boolean,
    followed: Boolean,
    onInfo: () -> Unit,
    onPlay: () -> Unit,
    onFollow: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlCircle(50.dp, Color.White.copy(alpha = 0.08f), onInfo) {
            ConvxIcon("info", tint = Color.White, size = 22.dp)
        }
        ControlCircle(73.dp, Color.White, onPlay) {
            ConvxIcon(if (isPlaying) "pause_applemusic" else "play_applemusic", tint = lerp(pageColor, Color.Black, 0.3f), size = 36.dp)
        }
        ControlCircle(50.dp, Color.White.copy(alpha = 0.08f), onFollow) {
            ConvxIcon(if (followed) "star_fill" else "star", tint = Color.White, size = 24.dp)
        }
    }
}

@Composable
private fun ControlCircle(size: Dp, fill: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .background(if (hovered) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .hoverable(interaction)
            .pressable(onClick, interaction = interaction, pressScale = 0.92f),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * The phone's featured release: 114 tall, 22 corners, 12 padding, 90 artwork with 10 corners,
 * 15pt captions at 60% white around a 17pt semibold title, on a white wash that rises with
 * the page's brightness.
 */
@Composable
private fun FeaturedReleaseCard(
    album: AlbumItem,
    caption: String,
    pageColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val wash = (0.09f + 0.25f * pageColor.luminance()).coerceIn(0.08f, 0.14f) + if (hovered) 0.04f else 0f
    Row(
        modifier
            .fillMaxWidth()
            .height(114.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = wash))
            .border(0.33.dp, Color.White.copy(alpha = 0.10f), shape)
            .hoverable(interaction)
            .pressable(onClick, interaction = interaction, pressScale = 0.98f)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(album.thumbnail, Modifier.size(90.dp), size = 300, shape = RoundedCornerShape(10.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val small = ConvxType.Body.copy(fontSize = 15.sp, lineHeight = 20.sp)
            Text(caption, style = small, color = Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                album.title,
                style = ConvxType.Body.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            album.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = small, color = Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "Biography" as the phone sets it: 22pt bold heading, six lines of 60% white, then More. */
@Composable
private fun Biography(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = Gutter).padding(top = 12.dp, bottom = 24.dp).widthIn(max = 860.dp)) {
        Text("Biography", style = ConvxType.Title2, color = Color.White)
        Spacer(Modifier.height(14.dp))
        Text(
            text,
            style = ConvxType.Body.copy(fontSize = 16.sp, lineHeight = 23.sp),
            color = Secondary,
            maxLines = if (expanded) Int.MAX_VALUE else 6,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (expanded) "Less" else "More",
            style = ConvxType.Headline,
            color = Color.White,
            modifier = Modifier.pressable({ expanded = !expanded }, pressScale = 1f),
        )
    }
}

/** Everything in one of an artist's sections: all songs, all albums, all singles... */
@Composable
fun ArtistItemsScreen(browseId: String, params: String?, title: String) {
    val scope = rememberCoroutineScope()
    var loadingMore by remember { mutableStateOf(false) }
    val page = rememberPage("artist-items:$browseId:$params") { YouTube.artistItems(BrowseEndpoint(browseId, params)).getOrThrow() }
    PageList(
        onNearEnd = {
            val continuation = page.value?.continuation ?: return@PageList
            if (loadingMore) return@PageList
            loadingMore = true
            scope.launch {
                val more = withContext(Dispatchers.IO) { YouTube.artistItemsContinuation(continuation).getOrNull() }
                if (more != null) page.update { it.copy(items = it.items + more.items, continuation = more.continuation) }
                loadingMore = false
            }
        },
    ) {
        item(key = "title") {
            Text(page.value?.title?.ifBlank { null } ?: title, style = ConvxType.LargeTitle, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp))
        }
        val data = page.value
        when {
            data != null -> {
                val items = data.items.distinctBy { it.id }
                val songs = items.filterIsInstance<SongItem>()
                if (items.isNotEmpty() && songs.size == items.size) {
                    val mapped = songs.map { it.toSong() }
                    songTable(mapped, title, "ai")
                } else {
                    item(key = "grid") {
                        CardGrid { items.forEach { ItemCard(it) } }
                    }
                }
                if (loadingMore) item { LoadingState() }
            }
            page.error != null -> item { MessageState("Couldn't load this page", friendlyError(page.error), actionText = "Try again", onAction = { page.retry() }) }
            else -> item { LoadingState() }
        }
    }
}
