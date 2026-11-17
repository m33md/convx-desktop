/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.replay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.shell.windowDragArea
import com.convx.desktop.ui.theme.ConvxType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.NumberFormat
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Orange = Color(0xFFFF7A00)
private val Lime = Color(0xFF3DD68C)
private val Magenta = Color(0xFFD81B60)
private val Pink = Color(0xFFFF4F8B)
private val Graphite = Color(0xFF3A3A3C)
private val Ink = Color(0xFF1C1C1E)
private val Cobalt = Color(0xFF1437D6)
private val Sky = Color(0xFF2F6BFF)
private val Yellow = Color(0xFFE8E23A)

/** The phone's pages in its order, each with its two colours and how long it stays. */
enum class ReplayPage(val a: Color, val b: Color, val durationMs: Int) {
    Intro(Graphite, Ink, 4_500),
    LotOfMusic(Orange, Lime, 5_500),
    Minutes(Orange, Yellow, 7_000),
    Songs(Magenta, Pink, 6_000),
    TopSong(Magenta, Pink, 7_000),
    ArtistBubbles(Graphite, Ink, 6_500),
    TopArtist(Graphite, Ink, 6_500),
    ArtistTable(Ink, Graphite, 7_000),
    AlbumOrbit(Cobalt, Sky, 6_000),
    TopAlbum(Cobalt, Sky, 7_000),
    Summary(Orange, Magenta, 9_000),
}

/** iOS-style ease the Replay type rides in on. */
private val ReplayEase = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)

/** The phone the pages were laid out on: every page is laid out at this size, then scaled to fit. */
private val StoryWidth = 412.dp
private val StoryHeight = 732.dp

/**
 * Replay over the whole window, the phone's Apple Music Replay story. The story keeps the
 * phone's shape, a 9:16 card scaled to fit, so each page sits as it does there; its colours
 * fill the window around it, with arrows beside it for a mouse. Click the right two thirds to
 * go on and the left third to go back, hold to pause, as on the phone. The arrow keys, Space,
 * M and Esc do the same from the keyboard.
 */
@Composable
fun ReplayOverlay() {
    AnimatedVisibility(
        visible = Replay.open,
        enter = fadeIn(tween(260)) + scaleIn(tween(320, easing = ReplayEase), initialScale = 0.97f),
        exit = fadeOut(tween(200)),
    ) {
        ReplayScreen()
    }
}

@Composable
private fun ReplayScreen() {
    val ready = Replay.data != null
    val index = Replay.page
    val page = ReplayPage.entries[index]
    val progress = remember { Animatable(0f) }
    // Which page the bar belongs to: a pause keeps its fill, a new page starts it again.
    val progressPage = remember { intArrayOf(-1) }
    LaunchedEffect(index, Replay.paused, ready) {
        if (!ready) return@LaunchedEffect
        if (progressPage[0] != index) {
            progress.snapTo(0f)
            progressPage[0] = index
        }
        if (Replay.paused) return@LaunchedEffect
        val remaining = ((1f - progress.value) * page.durationMs).toInt().coerceAtLeast(1)
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        if (index < ReplayPage.entries.lastIndex) Replay.go(index + 1)
    }
    val a by animateColorAsState(if (ready) page.a else Lime, tween(900), label = "replayA")
    val b by animateColorAsState(if (ready) page.b else Yellow, tween(900), label = "replayB")

    // The window is the page's colours, dimmed, so the story stands out of its own light.
    Box(Modifier.fillMaxSize().windowDragArea().background(Color.Black).flowingGradient(a, b)) {
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f)))
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(start = 96.dp, end = 96.dp, top = 44.dp, bottom = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            val scale = minOf(maxWidth / StoryWidth, maxHeight / StoryHeight).coerceAtMost(1.3f)
            val shape = RoundedCornerShape(22.dp * scale)
            Box(Modifier.size(StoryWidth * scale, StoryHeight * scale).shadow(30.dp, shape).clip(shape)) {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
                    Story(page, ready, a, b) { progress.value }
                }
            }
            Box(Modifier.size(StoryWidth * scale + 136.dp, StoryHeight * scale)) {
                if (ready && index > 0) {
                    NavArrow("chevron_leftpx", "Previous", Modifier.align(Alignment.CenterStart)) { Replay.go(Replay.page - 1) }
                }
                if (ready && index < ReplayPage.entries.lastIndex) {
                    NavArrow("chevron_right_px", "Next", Modifier.align(Alignment.CenterEnd)) { Replay.go(Replay.page + 1) }
                }
            }
        }
    }
}

@Composable
private fun Story(page: ReplayPage, ready: Boolean, a: Color, b: Color, progress: () -> Float) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .flowingGradient(a, b)
            .pointerInput(ready) {
                if (!ready) return@pointerInput
                detectTapGestures(
                    onPress = {
                        Replay.held = true
                        tryAwaitRelease()
                        Replay.held = false
                    },
                    onTap = { offset -> Replay.go(if (offset.x < size.width / 3f) Replay.page - 1 else Replay.page + 1) },
                )
            },
    ) {
        AnimatedContent(
            targetState = page.takeIf { ready },
            transitionSpec = { fadeIn(tween(420, delayMillis = 120)) togetherWith fadeOut(tween(260)) },
            label = "replayPage",
            modifier = Modifier.fillMaxSize(),
        ) { shown ->
            val replay = Replay.data
            Box(Modifier.fillMaxSize()) {
                if (shown == null || replay == null) LoadingPulse() else PageContent(shown, replay)
            }
        }
        ReplayChrome(ready, progress)
    }
}

@Composable
private fun BoxScope.PageContent(page: ReplayPage, s: ReplayData) {
    val nf = remember { NumberFormat.getIntegerInstance() }
    val topSong = s.songs.firstOrNull()
    val topArtist = s.artists.firstOrNull()
    val topAlbum = s.albums.firstOrNull()
    when (page) {
        ReplayPage.Intro -> IntroPage(s.songs.mapNotNull { it.value.thumbnail }.take(6), s.year)
        ReplayPage.LotOfMusic -> Headline("A lot of music\ncan happen in\na year.")
        ReplayPage.Minutes -> MinutesPage(s.minutes, nf)
        ReplayPage.Songs -> SongsGridPage(s.songCount, s.songs.map { it.value.thumbnail }, nf)
        ReplayPage.TopSong -> HeroPage(
            label = "Top Song",
            title = topSong?.value?.title ?: "—",
            subtitle = topSong?.value?.artistText.orEmpty(),
            detail = count(nf, topSong?.plays?.toLong() ?: 0L, "Play"),
            art = topSong?.value?.thumbnail,
            circle = false,
        )
        ReplayPage.ArtistBubbles -> ArtistBubblesPage(s.artists.map { it.picture })
        ReplayPage.TopArtist -> HeroPage(
            label = "Top Artist",
            title = topArtist?.ref?.name ?: "—",
            subtitle = null,
            detail = count(nf, (topArtist?.playedMs ?: 0L) / 60_000, "Minute"),
            art = topArtist?.picture,
            circle = true,
        )
        ReplayPage.ArtistTable -> ArtistTablePage(s.artists, nf)
        ReplayPage.AlbumOrbit -> AlbumOrbitPage(s.albums.map { it.song.thumbnail } + s.songs.map { it.value.thumbnail }.take(6))
        ReplayPage.TopAlbum -> HeroPage(
            label = "Top Album",
            title = topAlbum?.value?.name ?: "—",
            subtitle = topAlbum?.song?.artists?.firstOrNull()?.name,
            detail = count(nf, (topAlbum?.playedMs ?: 0L) / 60_000, "Minute"),
            art = topAlbum?.song?.thumbnail,
            circle = false,
        )
        ReplayPage.Summary -> SummaryPage(s, nf)
    }
}

/** "1 Minute", "12 Minutes": the phone always says the plural. */
private fun count(nf: NumberFormat, n: Long, unit: String) = "${nf.format(n)} $unit${if (n == 1L) "" else "s"}"

// Chrome

@Composable
private fun ReplayChrome(ready: Boolean, progress: () -> Float) {
    val clipboard = LocalClipboardManager.current
    val year = Replay.data?.year
    Box(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ready) {
                    val index = Replay.page
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(ReplayPage.entries.size) { i ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(2.5.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.3f))
                                    .drawBehind {
                                        val f = when {
                                            i < index -> 1f
                                            i == index -> progress()
                                            else -> 0f
                                        }
                                        drawRect(Color.White, size = size.copy(width = size.width * f))
                                    },
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.width(10.dp))
                ChromeCircle("close", "Close", Replay::close)
            }
            if (ready && year != null) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text("Replay’${yy(year)}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("Music", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (ready) {
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp)) {
                ChromeCircle(if (Replay.muted) "volume_off" else "volume_up", if (Replay.muted) "Unmute (M)" else "Mute (M)", Replay::toggleMute)
                Spacer(Modifier.weight(1f))
                // A PC has no share sheet, so the phone's shared text goes on the clipboard.
                ChromeCircle("ios_share", "Copy your Replay") {
                    val replay = Replay.data ?: return@ChromeCircle
                    clipboard.setText(AnnotatedString(Replay.shareText(replay)))
                    Overlays.toast("Copied your Replay", "content_copy")
                }
            }
        }
    }
}

private fun yy(year: Int) = (year % 100).toString().padStart(2, '0')

@Composable
private fun ChromeCircle(icon: String, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.22f))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ConvxIcon(icon, tint = Color.White, size = 16.dp, contentDescription = description)
    }
}

/** Beside the story, for a mouse: the phone's taps on either side, where a pointer can see them. */
@Composable
private fun NavArrow(icon: String, description: String, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ConvxIcon(icon, tint = Color.White, size = 22.dp, contentDescription = description)
    }
}

// Background

/**
 * Two soft colour fields drifting past each other, Replay's summary-background look. Plain radial
 * gradients moved in draw: no shader, no offscreen layer.
 */
@Composable
private fun Modifier.flowingGradient(a: Color, b: Color): Modifier {
    val t by rememberInfiniteTransition(label = "replayFlow").animateFloat(
        0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "flow",
    )
    return drawBehind {
        drawRect(a)
        val angle = t * 2f * Math.PI.toFloat()
        val c1 = Offset(size.width * (0.5f + 0.45f * cos(angle)), size.height * (0.62f + 0.25f * sin(angle)))
        val c2 = Offset(size.width * (0.5f - 0.4f * cos(angle * 1.3f)), size.height * (0.3f - 0.2f * sin(angle * 0.7f)))
        drawRect(Brush.radialGradient(listOf(b, b.copy(alpha = 0f)), center = c1, radius = size.maxDimension * 0.55f))
        drawRect(Brush.radialGradient(listOf(lerp(a, b, 0.5f), Color.Transparent), center = c2, radius = size.maxDimension * 0.45f))
    }
}

// Type

/** The phone's big white type, in the app font. */
private fun headline(size: TextUnit = 34.sp, line: TextUnit = 38.sp, weight: FontWeight = FontWeight.Bold) =
    TextStyle(fontFamily = ConvxType.family, color = Color.White, fontSize = size, lineHeight = line, fontWeight = weight)

/** Text rises ~28dp and fades in on the Replay ease. */
@Composable
private fun Rise(delayMs: Int = 0, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        p.animateTo(1f, tween(650, easing = ReplayEase))
    }
    Box(Modifier.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 28.dp.toPx() }) { content() }
}

@Composable
private fun BoxScope.Headline(text: String, delayMs: Int = 150) {
    Box(Modifier.align(Alignment.TopStart).padding(start = 20.dp, end = 20.dp, top = 72.dp)) {
        Rise(delayMs) { Text(text, style = headline()) }
    }
}

@Composable
private fun BoxScope.LoadingPulse() {
    val a by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Reverse), label = "a",
    )
    Text("This is your Replay.", style = headline(24.sp, 28.sp), modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = a })
}

// Pages

@Composable
private fun BoxScope.IntroPage(arts: List<String>, year: Int) {
    // Blurred artwork cycling behind a big "Replay'yy" (text-album-transitions opening).
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(arts) {
        while (isActive && arts.size > 1) {
            delay(600)
            i = (i + 1) % arts.size
        }
    }
    if (arts.isNotEmpty()) {
        AnimatedContent(arts[i], transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) }, label = "introArt") { url ->
            Artwork(
                url,
                Modifier.fillMaxSize().graphicsLayer { renderEffect = BlurEffect(40f, 40f); alpha = 0.85f },
                shape = RectangleShape,
            )
        }
    }
    Box(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 110.dp)) {
        Rise(200) { Text("Replay’${yy(year)}", style = headline(52.sp, 56.sp)) }
    }
}

@Composable
private fun BoxScope.MinutesPage(minutes: Long, nf: NumberFormat) {
    Headline("This year you\nlistened for\n${nf.format(minutes)} minutes.")
    // The giant number slides across from the right and off the left (text-album-transitions).
    BoxWithConstraints(Modifier.fillMaxWidth().align(Alignment.Center).padding(top = 120.dp)) {
        val w = constraints.maxWidth.toFloat()
        val x = remember { Animatable(1f) }
        LaunchedEffect(Unit) {
            delay(900)
            x.animateTo(0f, tween(900, easing = ReplayEase))
            delay(1_400)
            x.animateTo(-1.6f, tween(1_600, easing = CubicBezierEasing(0.5f, 0f, 0.8f, 0.6f)))
        }
        Text(
            nf.format(minutes),
            style = headline(110.sp, 110.sp, FontWeight.Black),
            softWrap = false,
            modifier = Modifier.offset { IntOffset((x.value * w).roundToInt(), 0) }.padding(start = 16.dp),
        )
    }
}

@Composable
private fun BoxScope.SongsGridPage(count: Int, arts: List<String?>, nf: NumberFormat) {
    Headline("${nf.format(count)} songs\nmade up your\nsoundtrack.")
    // Rows of covers drifting sideways in opposite directions (top-song-zoom opening).
    Column(Modifier.align(Alignment.BottomStart).padding(bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val drift by rememberInfiniteTransition(label = "rows").animateFloat(
            0f, 1f, infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "d",
        )
        repeat(3) { row ->
            val dir = if (row % 2 == 0) -1f else 1f
            Row(
                Modifier.graphicsLayer { translationX = dir * drift * 240.dp.toPx() - if (dir > 0) 240.dp.toPx() else 0f },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(8) { col ->
                    val url = arts.getOrNull((row * 3 + col) % arts.size.coerceAtLeast(1))
                    Rise(100 + row * 120 + col * 40) {
                        Artwork(url, Modifier.size(96.dp), size = 226, shape = RoundedCornerShape(6.dp))
                    }
                }
            }
        }
    }
}

/** Top song, artist or album: the art zooms up from small into a hero, its title set big above. */
@Composable
private fun BoxScope.HeroPage(label: String, title: String, subtitle: String?, detail: String, art: String?, circle: Boolean) {
    val zoom = remember { Animatable(0.35f) }
    LaunchedEffect(Unit) { zoom.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 120f)) }
    Column(Modifier.align(Alignment.TopStart).padding(start = 20.dp, end = 20.dp, top = 64.dp)) {
        Rise(250) {
            Column {
                Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(title, style = headline(40.sp, 44.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(detail, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            }
        }
    }
    Artwork(
        art,
        Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 90.dp)
            .size(280.dp)
            .graphicsLayer { scaleX = zoom.value; scaleY = zoom.value; alpha = zoom.value.coerceIn(0f, 1f) },
        size = 720,
        shape = if (circle) CircleShape else RoundedCornerShape(8.dp),
        placeholderIcon = if (circle) "artist" else "music_note",
        iconSize = 72.dp,
    )
}

@Composable
private fun BoxScope.ArtistBubblesPage(arts: List<String?>) {
    Headline("A new artist\nrose to the top.")
    // Bubbles float up from below at staggered sizes, then bob (artist-bubble-animation).
    val spots = remember {
        listOf(0.18f to 0.55f, 0.72f to 0.5f, 0.45f to 0.68f, 0.2f to 0.82f, 0.78f to 0.78f, 0.55f to 0.9f)
    }
    val sizes = listOf(110, 90, 150, 80, 120, 70)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        spots.forEachIndexed { i, (fx, fy) ->
            val url = arts.getOrNull(i % arts.size.coerceAtLeast(1))
            val rise = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                delay(150L + i * 110)
                rise.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 90f))
            }
            val bob by rememberInfiniteTransition(label = "bob$i").animateFloat(
                -1f, 1f, infiniteRepeatable(tween(2_400 + i * 300, easing = LinearEasing), RepeatMode.Reverse), label = "b",
            )
            val d = sizes[i].dp
            Artwork(
                url,
                Modifier
                    .offset(maxWidth * fx - d / 2, maxHeight * fy - d / 2)
                    .size(d)
                    .graphicsLayer {
                        translationY = (1f - rise.value) * 500.dp.toPx() + bob * 6.dp.toPx()
                        alpha = rise.value.coerceIn(0f, 1f)
                    },
                size = 360,
                shape = CircleShape,
                placeholderIcon = "artist",
            )
        }
    }
}

@Composable
private fun BoxScope.ArtistTablePage(artists: List<ReplayArtist>, nf: NumberFormat) {
    // Avatars settle into a ranked table (top-artist-table-animation).
    Column(Modifier.align(Alignment.TopStart).padding(start = 20.dp, end = 20.dp, top = 64.dp)) {
        Rise(100) { Text("Top Artists", style = headline()) }
        Spacer(Modifier.height(18.dp))
        artists.take(5).forEachIndexed { i, artist ->
            Rise(350 + i * 140) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
                    Artwork(artist.picture, Modifier.size(56.dp), size = 226, shape = CircleShape, placeholderIcon = "artist", iconSize = 22.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(artist.ref.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(count(nf, artist.playedMs / 60_000, "Minute"), color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.AlbumOrbitPage(arts: List<String?>) {
    // Covers burst outward onto a circle and orbit it (top-album-circular-path-animation).
    val burst = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(200)
        burst.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 70f))
    }
    val spin by rememberInfiniteTransition(label = "orbit").animateFloat(
        0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "s",
    )
    val n = arts.size.coerceIn(1, 10)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val radius = maxWidth * 0.36f
        repeat(n) { i ->
            val angle = Math.toRadians(spin + i * 360.0 / n)
            Artwork(
                arts.getOrNull(i),
                Modifier
                    .graphicsLayer {
                        translationX = (cos(angle) * radius.toPx() * burst.value).toFloat()
                        translationY = (sin(angle) * radius.toPx() * burst.value).toFloat()
                        scaleX = 0.4f + 0.6f * burst.value
                        scaleY = scaleX
                    }
                    .size(92.dp),
                size = 226,
                shape = RoundedCornerShape(6.dp),
            )
        }
    }
    Headline("You kept coming\nback to these.")
}

@Composable
private fun BoxScope.SummaryPage(s: ReplayData, nf: NumberFormat) {
    Column(Modifier.align(Alignment.TopStart).padding(start = 20.dp, end = 20.dp, top = 64.dp)) {
        Rise(100) { Text("Your Replay", style = headline()) }
        Spacer(Modifier.height(20.dp))
        val rows = listOf(
            "Minutes" to nf.format(s.minutes),
            "Songs" to nf.format(s.songCount),
            "Artists" to nf.format(s.artistCount),
            "Albums" to nf.format(s.albumCount),
        )
        rows.forEachIndexed { i, (k, v) ->
            Rise(300 + i * 120) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(k, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(v, style = headline(40.sp, 44.sp))
                }
            }
        }
    }
    val state = Replay.playlist
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 210.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(enabled = state != ReplayPlaylist.Added, onClick = Replay::addPlaylist)
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Text(
            when (state) {
                ReplayPlaylist.Add -> "Add Replay Playlist"
                ReplayPlaylist.Update -> "Update Replay Playlist"
                ReplayPlaylist.Added -> "Added to Library"
            },
            color = Color.Black,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
    // A drifting collage of top covers along the bottom.
    Row(
        Modifier.align(Alignment.BottomStart).padding(bottom = 70.dp),
        horizontalArrangement = Arrangement.spacedBy((-18).dp),
    ) {
        s.songs.take(5).forEachIndexed { i, ranked ->
            Rise(900 + i * 110) {
                Artwork(
                    ranked.value.thumbnail,
                    Modifier.size(110.dp).graphicsLayer { rotationZ = (i - 2) * 6f },
                    size = 226,
                    shape = RoundedCornerShape(8.dp),
                )
            }
        }
    }
}
