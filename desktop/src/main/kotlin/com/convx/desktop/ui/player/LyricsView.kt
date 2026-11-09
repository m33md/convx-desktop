/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOutQuad
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Song
import com.convx.desktop.lyrics.Lyrics
import com.convx.desktop.lyrics.LyricsService
import com.convx.desktop.lyrics.Romanizer
import com.convx.desktop.lyrics.LyricsState
import com.convx.desktop.lyrics.LyricsTranslator
import com.convx.desktop.lyrics.LyricsTweaks
import com.convx.desktop.lyrics.SongLyricsTweaks
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.LoadingState
import com.convx.desktop.ui.shell.fadingEdge
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.convx.music.lyrics.bitchord.LyricAlignment
import com.convx.music.lyrics.bitchord.LyricLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.util.lerp
import com.convx.desktop.ui.components.LocalPlayback
import com.convx.desktop.ui.components.pingPong
import com.convx.desktop.ui.components.rememberLoopingPhase
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import com.convx.desktop.ui.components.FocusedFps
import com.convx.desktop.ui.components.awaitPacedFrameMillis
import com.convx.desktop.ui.components.playheadMoving
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.max
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.text.platform.Font as PlatformFont
import com.convx.desktop.data.LyricsStyle
import com.convx.desktop.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The phone's default lyrics renderer is Flamingo (Yos-X, GPL-3.0); these are its values.
private val YosEasing = CubicBezierEasing(0.75f, 0f, 0.25f, 1f)
private val Focused = Color(0xFFFFFFFF)
private val Unfocused = Color(0x2EFFFFFF)
private val LyricScrollSpec = tween<Float>(550, easing = YosEasing)

/** Flamingo lifts a word 4 px at the phone's 30 sp: about a twentieth of the type size. */
private const val LiftPerFontPx = 0.048f

/** How far either side of the fill edge the colour fades, in characters (Flamingo's px). */
private const val Feather = 0.3f

/** Pauses the follow after you scroll the lyrics yourself, as the phone does after a drag. */
internal const val FollowPauseMs = 2_000L

/**
 * The phone's lyrics, in the style chosen under Lyrics style. Flamingo, its default: lines wait
 * at a fifth of white; the line being sung fills letter by letter, each letter glowing while it
 * fills and each word rising a little as it is sung; a sung line stays lit. The current line
 * sits on a faint glass strip, the view keeps it centred, and an instrumental break counts down
 * with three dots. Karaoke (Apple) is the phone's Accompanist view, and the rest are its list
 * styles ([StyledLyrics]). Click a line to jump to it.
 *
 * [clock] is the playhead; the style page passes one that keeps running while paused.
 */
@Composable
fun LyricsView(
    song: Song?,
    modifier: Modifier = Modifier,
    style: TextStyle = ConvxType.Lyrics,
    horizontalPadding: Dp = 0.dp,
    clock: () -> Long = PlayerController::livePositionMs,
    /** When [clock] moves; null follows the player. */
    clockMoving: Flow<Boolean>? = null,
    /** A small view (the side panel), which fills its letters at a lower frame rate. */
    compact: Boolean = false,
) {
    val state by remember(song?.id) { song?.let(LyricsService::state) ?: MutableStateFlow<LyricsState>(LyricsState.Missing) }
        .collectAsState()
    val settings by AppSettings.state.collectAsState()
    val tweaks = LyricsTweaks.state.collectAsState().value.songs[song?.id]
    // The song's lyrics offset moves the playhead the lines are matched against, as on the phone.
    val currentClock by rememberUpdatedState(clock)
    val offset = rememberUpdatedState((tweaks?.offsetMs ?: 0).toLong())
    val shifted = remember { { currentClock() + offset.value } }
    val family = rememberLyricsFont(settings.lyricsFont)
    // The chosen size is the full player's; smaller callers (the side panel) keep their proportion.
    val size = settings.lyricsTextSize * (style.fontSize.value / ConvxType.Lyrics.fontSize.value)
    // Flamingo's MainTextStyle at that size: extra bold, airy, the chosen line spacing.
    val textStyle = remember(style, family, size, settings.lyricsLineSpacing) {
        style.copy(
            fontFamily = family ?: style.fontFamily,
            fontSize = size.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.05.sp,
            lineHeight = (size * settings.lyricsLineSpacing).sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        )
    }
    Box(modifier) {
        CompositionLocalProvider(LocalLyricsClockMoving provides clockMoving, LocalLyricsCompact provides compact) {
            when (val s = state) {
                LyricsState.Loading -> LoadingState()
                LyricsState.Missing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Lyrics not found",
                        style = textStyle.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
                        color = Color.White.copy(alpha = 0.5f),
                    )
                }
                // A better source landing later (word timing after line timing) cross-fades in.
                is LyricsState.Ready -> {
                    val romanized = rememberRomanized(s.lyrics, settings, enabled = tweaks?.romanize != false)
                    val translations = rememberTranslations(song, s.lyrics, settings, tweaks)
                    Crossfade(s.lyrics to settings.lyricsStyle, animationSpec = tween(300)) { (found, chosen) ->
                        // Romanized lines go under the originals, or, as the phone's "as main", over them.
                        val roman = romanized?.takeIf { it.first === found }?.second
                        val translated = translations?.takeIf { it.first === found }?.second
                        val (lyrics, subs) = remember(found, roman, translated, settings.lyricsRomanizeAsMain) {
                            val (shown, romanSubs) = when {
                                roman == null -> found to emptyList()
                                settings.lyricsRomanizeAsMain -> found.romanizedAsMain(roman)
                                else -> found to roman
                            }
                            shown to shown.lines.indices.map { i ->
                                val r = romanSubs.getOrNull(i)
                                val t = translated?.getOrNull(i)
                                if (r == null && t == null) null else LineSubs(r, t)
                            }
                        }
                        when {
                            chosen == LyricsStyle.Flamingo && lyrics.synced -> FlamingoLyrics(lyrics, subs, textStyle, settings, horizontalPadding, shifted)
                            chosen == LyricsStyle.Flamingo -> PlainLyrics(lyrics, subs, textStyle, horizontalPadding)
                            // Accompanist only earns its cost with word timing; without, the list does the same.
                            chosen == LyricsStyle.KaraokeApple && lyrics.wordSynced && settings.lyricsWordSync ->
                                KaraokeAppleLyrics(lyrics, translated, textStyle, settings, horizontalPadding, shifted)
                            else -> StyledLyrics(lyrics, subs, chosen, textStyle, settings, horizontalPadding, shifted)
                        }
                    }
                }
            }
        }
        if (song != null) TranslationStatus(song, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        // A source that asks to be named wherever its lyrics are (Spicy Lyrics) stays named, pinned
        // under the lines rather than scrolling away with them.
        (state as? LyricsState.Ready)?.lyrics?.credit?.let { credit ->
            LyricsCreditLine(credit, Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp, start = 12.dp, end = 12.dp))
        }
    }
}

/** "Lyrics from Spicy Lyrics · uploaded by A, made by B", each name opening its page. */
@Composable
private fun LyricsCreditLine(credit: com.convx.desktop.lyrics.LyricsCredit, modifier: Modifier = Modifier) {
    val style = ConvxType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
    val dim = Color.White.copy(alpha = 0.55f)
    @Composable
    fun Name(link: com.convx.desktop.lyrics.CreditLink) {
        val url = link.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        Text(
            link.name,
            style = style.copy(fontWeight = FontWeight.Bold),
            color = Color.White.copy(alpha = 0.8f),
            maxLines = 1,
            modifier = if (url != null) Modifier.clickable { com.convx.desktop.ui.screens.settings.openUrl(url) } else Modifier,
        )
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val via = if (credit.provider == "Spicy Lyrics") "" else ", via Spicy Lyrics"
        Text("Lyrics from ${credit.provider}$via", style = style, color = dim, maxLines = 1)
        credit.uploader?.let {
            Text(" · uploaded by ", style = style, color = dim, maxLines = 1)
            Name(it)
        }
        credit.maker?.let {
            Text(if (credit.uploader != null) ", made by " else " · made by ", style = style, color = dim, maxLines = 1)
            Name(it)
        }
    }
}

/** Each line's translation for [lyrics]: arriving, or kept for the language and mode in Settings. */
@Composable
private fun rememberTranslations(song: Song?, lyrics: Lyrics, settings: Settings, tweaks: SongLyricsTweaks?): Pair<Lyrics, List<String?>>? {
    val partial by LyricsTranslator.partial.collectAsState()
    return remember(song?.id, lyrics, partial, tweaks?.translation, settings.translateLanguage, settings.translateMode) {
        if (song == null) return@remember null
        val arriving = partial?.takeIf { it.songId == song.id && it.key == LyricsTranslator.key(lyrics) }
        val lines = arriving?.lines ?: LyricsTranslator.saved(song.id, lyrics, settings)?.lines ?: return@remember null
        lyrics to LyricsTranslator.align(lyrics, lines)
    }
}

/**
 * The phone's translation chip over the lyrics: translating with a spinner, done with a tick,
 * or what went wrong, which a click puts away.
 */
@Composable
private fun TranslationStatus(song: Song, modifier: Modifier = Modifier) {
    val status by LyricsTranslator.status.collectAsState()
    val playing by PlayerController.state.collectAsState()
    val shown = status.takeIf { it != LyricsTranslator.Status.Idle && playing.current?.id == song.id }
    AnimatedVisibility(shown != null, modifier, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.9f)) {
        // Keeps showing the last status while the chip fades out.
        val last = remember { arrayOf<LyricsTranslator.Status>(LyricsTranslator.Status.Translating) }
        if (shown != null) last[0] = shown
        val (text, icon) = when (val s = last[0]) {
            is LyricsTranslator.Status.Error -> s.message to "error"
            LyricsTranslator.Status.Success -> "Lyrics translated" to "check"
            else -> "Translating lyrics..." to null
        }
        Row(
            Modifier
                .clip(CircleShape)
                .background(if (last[0] is LyricsTranslator.Status.Error) Color(0xE6FF453A) else Color(0xCC2C2C2E))
                .clickable { LyricsTranslator.dismiss() }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                com.convx.desktop.ui.components.ConvxIcon(icon, tint = Color.White, size = 16.dp)
            } else {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            }
            Text(text, style = ConvxType.SubheadStrong, color = Color.White, maxLines = 2)
        }
    }
}

/** Each line's romanization for [lyrics], worked out off the UI thread; null while there's none. */
@Composable
private fun rememberRomanized(lyrics: Lyrics, settings: Settings, enabled: Boolean): Pair<Lyrics, List<String?>>? {
    val off = settings.lyricsRomanizeOff
    val byLine = settings.lyricsRomanizeCyrillicByLine
    val result by produceState<Pair<Lyrics, List<String?>>?>(null, lyrics, off, byLine, enabled) {
        value = null
        // "Romanize current track" off, or every language off.
        if (!enabled || Romanizer.languages.all { it in off }) return@produceState
        value = runCatching { Romanizer.romanize(lyrics.lines.map { it.text }, off, byLine) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()
            ?.takeIf { lines -> lines.any { it != null } }
            ?.let { lyrics to it }
    }
    return result
}

/** The phone's "Show romanized lyrics as main": romanized text on top, without word timing, the original under it. */
private fun Lyrics.romanizedAsMain(roman: List<String?>): Pair<Lyrics, List<String?>> {
    val swapped = lines.mapIndexed { i, line -> roman.getOrNull(i)?.let { line.copy(text = it, words = emptyList()) } ?: line }
    val originals = lines.mapIndexed { i, line -> if (roman.getOrNull(i) != null) line.text else null }
    return copy(lines = swapped) to originals
}

/** What goes under a lyric line: its romanization (or, as main, the original) and its translation. */
internal data class LineSubs(val roman: String?, val translation: String?)

@Composable
internal fun SubLines(subs: LineSubs, style: TextStyle, textAlign: TextAlign, modifier: Modifier = Modifier) {
    Column(modifier) {
        subs.roman?.let { SubLine(it, style, textAlign) }
        subs.translation?.let { TranslationLine(it, style, textAlign) }
    }
}

/** A line's translation, as the phone sets it: 0.7 of the lyric size, bold, 80% white. */
@Composable
internal fun TranslationLine(text: String, style: TextStyle, textAlign: TextAlign, modifier: Modifier = Modifier) {
    val size = style.fontSize * 0.7f
    val spacing = if (style.lineHeight.isSp && style.fontSize.isSp) (style.lineHeight.value / style.fontSize.value).coerceAtMost(1.3f) else 1.3f
    Text(
        text,
        style = style.copy(
            fontSize = size,
            lineHeight = size * spacing,
            fontWeight = FontWeight.Bold,
            letterSpacing = size * -0.012f,
            brush = null,
            shadow = null,
            textAlign = textAlign,
            lineHeightStyle = null,
        ),
        color = Color.White.copy(alpha = 0.8f),
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

/** The romanized (or, as main, original) line under a lyric: the phone's 18 sp at its 34 sp, light, 60% white. */
@Composable
internal fun SubLine(text: String, style: TextStyle, textAlign: TextAlign, modifier: Modifier = Modifier) {
    val size = style.fontSize * (18f / 34f)
    Text(
        text,
        style = style.copy(
            fontSize = size,
            lineHeight = size * 1.3f,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.sp,
            brush = null,
            shadow = null,
            textAlign = textAlign,
            lineHeightStyle = null,
        ),
        color = Color.White.copy(alpha = 0.6f),
        modifier = modifier.padding(top = 2.dp),
    )
}

/** The phone's lyrics fonts: the app's own, rounded, serif, mono, or one you imported. Null is the app's. */
@OptIn(ExperimentalTextApi::class)
@Composable
fun rememberLyricsFont(selection: String): FontFamily? {
    val family by produceState<FontFamily?>(null, selection) {
        value = when (selection) {
            "serif" -> FontFamily.Serif
            "monospace" -> FontFamily.Monospace
            // Windows has no system rounded face; Arial Rounded comes with Office, else Windows picks one.
            "rounded" -> FontFamily(SystemFont("Arial Rounded MT Bold"))
            else -> if (selection.startsWith("custom-")) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        require(selection.matches(Regex("custom-\\d+\\.font")))
                        val file = LyricsFonts.dir.resolve(selection)
                        // Loading checks the file is a font before Compose ever draws with it.
                        LyricsFonts.check(file)
                        FontFamily(PlatformFont(file))
                    }.getOrNull()
                }
            } else {
                null
            }
        }
    }
    return family
}

/** Where imported fonts live, for lyrics or the whole app, as on the phone: one file each, named by when they came. */
object LyricsFonts {
    val dir: java.io.File get() = com.convx.desktop.core.AppDirs.data.resolve("lyrics-fonts")

    /** Copies [source] in (8 MB at most, as the phone allows) and returns its name for the setting. */
    fun import(source: java.io.File): String {
        require(source.length() in 1..8L * 1024 * 1024) { if (source.length() == 0L) "This font file is empty" else "Choose a font smaller than 8 MB" }
        val name = "custom-${System.currentTimeMillis()}.font"
        dir.mkdirs()
        val target = dir.resolve(name)
        source.copyTo(target, overwrite = true)
        runCatching { check(target) }.onFailure {
            target.delete()
            throw IllegalArgumentException("This isn't a font Convx can read")
        }
        return name
    }

    /** Throws unless Skia can read [file] as a font. */
    fun check(file: java.io.File) {
        checkNotNull(org.jetbrains.skia.FontMgr.default.makeFromFile(file.absolutePath, 0)) { "Not a font" }
    }
}

/**
 * Whether the lyrics' clock is moving: the song playing, unless a caller (the style page's
 * preview, whose clock runs on while paused) says otherwise.
 */
internal val LocalLyricsClockMoving = staticCompositionLocalOf<Flow<Boolean>?> { null }

/** Lyrics in a small view (the side panel and its card), whose letters fill at [CompactLyricsFps]. */
internal val LocalLyricsCompact = staticCompositionLocalOf { false }

/** Frames a second for a small view's letter fill: smooth at that size, half the work of 60. */
private const val CompactLyricsFps = 30

/** How often line-timed lyrics look at the playhead; nothing between lines moves, so no frames are asked for. */
private const val LineTickMs = 50L

/**
 * The playhead while it moves, like the phone's player.currentPosition: every frame (at most 60
 * a second, 30 in a small view) when letters fill [smooth]ly, else every [LineTickMs] without
 * asking for frames, which only a line change then draws. Paused, it waits, and catches up on a seek.
 */
@Composable
internal fun rememberLiveClock(clock: () -> Long, smooth: Boolean = true): LongState {
    val live = remember { mutableLongStateOf(clock()) }
    val current by rememberUpdatedState(clock)
    val moving = LocalLyricsClockMoving.current ?: remember { playheadMoving() }
    val fps = if (LocalLyricsCompact.current) CompactLyricsFps else FocusedFps
    LaunchedEffect(moving, smooth, fps) {
        combine(moving, PlayerController.position) { on, _ -> on }.collectLatest { on ->
            live.longValue = current()
            if (!on) return@collectLatest
            while (true) {
                if (smooth) awaitPacedFrameMillis(fps) else delay(LineTickMs)
                live.longValue = current()
            }
        }
    }
    return live
}

@Composable
private fun FlamingoLyrics(lyrics: Lyrics, subs: List<LineSubs?>, style: TextStyle, settings: Settings, horizontalPadding: Dp, clock: () -> Long) {
    val wordSync = settings.lyricsWordSync
    val blur = settings.lyricsStandardBlur
    val glow = settings.lyricsGlow
    val lines = lyrics.lines
    val wordTimed = wordSync && lyrics.wordSynced

    // The playhead off the audio line, like the phone's player.currentPosition; every frame only
    // while letters fill.
    val live = rememberLiveClock(clock, smooth = wordTimed)

    val ends = remember(lines) { lines.indices.map { lineEnd(lines, it) } }
    val anchor = remember(lines) { derivedStateOf { anchorIndex(lines, live.longValue) } }
    // Word timed, every line still being sung is lit at once (duets overlap); else the anchor.
    val focused = remember(lines, wordTimed) {
        derivedStateOf {
            val now = live.longValue
            val a = anchor.value
            if (!wordTimed) return@derivedStateOf if (a >= 0) setOf(a) else emptySet()
            val active = lines.indices.filterTo(HashSet()) { now >= lines[it].timeMs && now < max(ends[it], lines[it].timeMs + 1) }
            active.ifEmpty { if (a >= 0) setOf(a) else emptySet() }
        }
    }

    val listState = rememberLazyListState()
    val measurer = rememberTextMeasurer(cacheSize = 96)
    var follow by remember { mutableStateOf(true) }
    var scrolls by remember { mutableIntStateOf(0) }
    LaunchedEffect(scrolls) {
        if (scrolls == 0) return@LaunchedEffect
        follow = false
        delay(FollowPauseMs)
        follow = true
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val half = maxHeight / 2
        LaunchedEffect(anchor.value, follow, maxHeight, settings.lyricsAutoScroll) {
            if (follow && settings.lyricsAutoScroll && anchor.value >= 0) listState.followLyricLine(anchor.value + 1)
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding, top = half, bottom = half),
            modifier = Modifier
                .fillMaxSize()
                .fadingEdge(64.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Scroll) scrolls++
                        }
                    }
                },
        ) {
            item(key = "top") { Spacer(Modifier.height(70.dp)) }
            itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                FlamingoLine(
                    line = line,
                    index = index,
                    nextTimeMs = lines.getOrNull(index + 1)?.timeMs ?: ends[index],
                    focused = focused,
                    anchor = anchor,
                    blur = blur && follow,
                    glow = glow,
                    oneByOne = wordSync && line.isWordSynced,
                    live = live,
                    style = style,
                    measurer = measurer,
                    sub = subs.getOrNull(index),
                )
            }
            item(key = "bottom") { Spacer(Modifier.height(70.dp)) }
            item(key = "credit") {
                Text(
                    "Lyrics from ${lyrics.source}",
                    style = ConvxType.Caption,
                    color = ConvxColors.TertiaryLabel,
                    modifier = Modifier.padding(horizontal = 39.dp),
                )
            }
            item(key = "extra") { Spacer(Modifier.height(500.dp)) }
        }
    }
}

/** The phone's primaryLyricLineIndex: the last line already started. */
internal fun anchorIndex(lines: List<LyricLine>, positionMs: Long): Int {
    val latest = lines.indexOfLast { it.timeMs <= positionMs }
    if (latest < 0) return -1
    val time = lines[latest].timeMs
    return lines.indexOfFirst { it.timeMs == time }
}

/** Where a line stops being sung: its last word, else the next line, else five seconds on. */
private fun lineEnd(lines: List<LyricLine>, index: Int): Long {
    val line = lines[index]
    if (line.isWordSynced) return line.endMs
    return lines.getOrNull(index + 1)?.timeMs ?: (line.timeMs + 5_000)
}

/** The phone's followLyricLine: centre the line, jumping first if it is off screen. */
internal suspend fun LazyListState.followLyricLine(itemIndex: Int) {
    // On open this runs before the list's first layout, when it has no items yet.
    snapshotFlow { layoutInfo.totalItemsCount }.first { it > 0 }
    // That answer can arrive in the middle of a layout pass; scrolling there throws, so wait for the next frame.
    withFrameNanos { }
    if (itemIndex !in 0 until layoutInfo.totalItemsCount) return
    var item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }
    if (item == null) {
        // The list's top padding is half its height, so an item scrolled to the top of the
        // content sits on the centre line, its top edge there; the animation then centres it.
        scrollToItem(itemIndex)
        withFrameNanos { }
        item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex } ?: return
    }
    val distance = item.offset + item.size / 2f - (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
    if (abs(distance) <= 1f) return
    animateScrollBy(distance, LyricScrollSpec)
}

@Composable
private fun FlamingoLine(
    line: LyricLine,
    index: Int,
    nextTimeMs: Long,
    focused: State<Set<Int>>,
    anchor: State<Int>,
    blur: Boolean,
    glow: Boolean,
    oneByOne: Boolean,
    live: LongState,
    style: TextStyle,
    measurer: TextMeasurer,
    sub: LineSubs?,
) {
    val isCurrent by remember(index) { derivedStateOf { index in focused.value } }
    val otherSide = line.alignment == LyricAlignment.End
    val viewAlign = if (otherSide) Alignment.End else Alignment.Start
    val origin = TransformOrigin(if (otherSide) 1f else 0f, 0.5f)
    val scale by animateFloatAsState(
        if (isCurrent) 1.005f else 1f,
        if (isCurrent) TweenSpec(270, 110, YosEasing) else TweenSpec(300, 45, YosEasing),
    )
    val seek: () -> Unit = {
        LyricsTweaks.seek(line.timeMs)
        PlayerController.play()
    }

    // Flamingo's LyricCard.
    Column(
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = origin
            }
            .fillMaxWidth()
            .padding(start = if (otherSide) 28.dp else 0.dp, end = if (otherSide) 0.dp else 28.dp)
            .padding(vertical = 9.dp),
        horizontalAlignment = viewAlign,
    ) {
        if (line.isGap) {
            Interlude(line, nextTimeMs, { isCurrent }, live, otherSide)
        } else {
            LineBody(line, index, isCurrent, anchor, blur, glow, oneByOne, live, style, measurer, otherSide, viewAlign, seek, sub)
        }
    }
}

@Composable
private fun LineBody(
    line: LyricLine,
    index: Int,
    isCurrent: Boolean,
    anchor: State<Int>,
    blur: Boolean,
    glow: Boolean,
    oneByOne: Boolean,
    live: LongState,
    style: TextStyle,
    measurer: TextMeasurer,
    otherSide: Boolean,
    viewAlign: Alignment.Horizontal,
    seek: () -> Unit,
    sub: LineSubs?,
) {
    // Lines away from the current one soften with distance; off on the phone by default.
    val blurTarget = if (!blur || isCurrent) 0.dp else (abs(index - anchor.value) * 2.5f).coerceAtMost(8f).dp
    val blurRadius by animateDpAsState(blurTarget, snap(if (index == anchor.value - 1) 260 else 0))
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // The glass strip under the current line; on a desktop, a fainter one under the pointer.
    val chrome by animateFloatAsState(
        when {
            isCurrent -> 1f
            hovered -> 0.5f
            else -> 0f
        },
        tween(240, easing = YosEasing),
    )
    val lineAlpha by animateFloatAsState(
        if (oneByOne || isCurrent) 1f else 0.14f,
        TweenSpec(350, if (isCurrent) 145 else 80, YosEasing),
    )
    val lineStyle = if (otherSide) style.copy(textAlign = TextAlign.End) else style

    Column(
        Modifier
            .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius, BlurredEdgeTreatment.Unbounded) else Modifier)
            .fillMaxWidth(),
        horizontalAlignment = viewAlign,
    ) {
        LineText(
            line = line,
            style = lineStyle,
            current = isCurrent,
            oneByOne = oneByOne,
            live = live,
            measurer = measurer,
            glow = glow,
            modifier = Modifier
                .graphicsLayer {
                    alpha = lineAlpha
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .padding(vertical = 4.dp)
                .padding(horizontal = 20.dp)
                .drawBehind { drawLineChrome(chrome) }
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interaction, indication = null, onClick = seek)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
        line.background?.let { bg ->
            LineText(
                line = bg,
                style = lineStyle.copy(fontSize = lineStyle.fontSize * 0.62f, lineHeight = lineStyle.lineHeight * 0.62f),
                current = isCurrent,
                oneByOne = oneByOne && bg.isWordSynced,
                live = live,
                measurer = measurer,
                glow = glow,
                modifier = Modifier
                    .graphicsLayer { alpha = lineAlpha * 0.85f }
                    .padding(horizontal = 30.dp)
                    .padding(bottom = 4.dp),
            )
        }
        if (sub != null) {
            val subAlpha by animateFloatAsState(if (isCurrent) 1f else 0.4f, TweenSpec(350, 0, YosEasing))
            SubLines(
                sub,
                style,
                if (otherSide) TextAlign.End else TextAlign.Start,
                Modifier.graphicsLayer { alpha = subAlpha }.padding(horizontal = 30.dp).padding(bottom = 4.dp),
            )
        }
    }
}

/** Flamingo's current-line strip: a faint vertical wash, a hairline rim and a lit bottom edge. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLineChrome(intensity: Float) {
    if (intensity <= 0.001f) return
    val radius = CornerRadius(size.height / 2f)
    val accent = Color(0xFFF2F2F2)
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.025f * intensity),
                accent.copy(alpha = 0.075f * intensity),
                accent.copy(alpha = 0.035f * intensity),
            ),
        ),
        cornerRadius = radius,
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.30f * intensity),
                accent.copy(alpha = 0.11f * intensity),
                Color.White.copy(alpha = 0.04f * intensity),
            ),
        ),
        cornerRadius = radius,
        style = Stroke(width = 1.dp.toPx()),
    )
    drawLine(
        brush = Brush.horizontalGradient(
            listOf(
                Color.Transparent,
                Color.White.copy(alpha = 0.38f * intensity),
                accent.copy(alpha = 0.22f * intensity),
                Color.Transparent,
            ),
        ),
        start = Offset(12.dp.toPx(), size.height - 1.dp.toPx()),
        end = Offset(size.width - 12.dp.toPx(), size.height - 1.dp.toPx()),
        strokeWidth = 1.5.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

/** The measured line, handed from layout to draw. */
private class LineLayout {
    var result: TextLayoutResult? = null
}

/**
 * One piece of a sung line with its own timing. Most are single letters, as on the phone;
 * a word in a script whose letters join or stack (Arabic, Devanagari, Thai…) stays whole so
 * it keeps its shape, and fills as one piece instead.
 */
private class Piece(
    val layout: TextLayoutResult,
    val topLeft: Offset,
    /** When the piece starts and how long it takes to fill. */
    val startMs: Float,
    val spanMs: Float,
    /** The word it belongs to, which lifts as a whole. */
    val wordStartMs: Float,
    val wordSpanMs: Float,
    /** Letters in the piece, so the fill edge feathers by a letter's width either way. */
    val letters: Int,
    val rightToLeft: Boolean,
)

@Composable
private fun LineText(
    line: LyricLine,
    style: TextStyle,
    current: Boolean,
    oneByOne: Boolean,
    live: LongState,
    measurer: TextMeasurer,
    glow: Boolean,
    modifier: Modifier,
) {
    val holder = remember(line, style) { LineLayout() }
    // Sung all the way through: stays lit, lifted (Flamingo's showHighLight).
    val sungThrough by remember(line, oneByOne) {
        derivedStateOf { oneByOne && line.words.isNotEmpty() && live.longValue >= line.words.last().endMs }
    }
    Spacer(
        modifier
            .layout { measurable, constraints ->
                val result = measurer.measure(
                    text = line.text,
                    style = style,
                    constraints = Constraints(maxWidth = constraints.maxWidth),
                    layoutDirection = layoutDirection,
                )
                holder.result = result
                val placeable = measurable.measure(Constraints.fixed(result.size.width, result.size.height))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .drawWithCache {
                val result = holder.result ?: return@drawWithCache onDrawBehind { }
                val lift = style.fontSize.toPx() * LiftPerFontPx
                when {
                    !oneByOne -> onDrawBehind { drawText(result, color = Focused) }
                    !current && sungThrough -> onDrawBehind { drawText(result, color = Focused, topLeft = Offset(0f, -lift)) }
                    !current -> onDrawBehind { drawText(result, color = Unfocused) }
                    else -> {
                        val pieces = buildPieces(line, result, style, measurer)
                        val glowBase = 3.dp.toPx()
                        val glowGrow = 3.6.dp.toPx()
                        onDrawBehind {
                            val now = live.longValue.toFloat()
                            for (p in pieces) {
                                // Letters filled, in this piece: below 0 waiting, above letters done.
                                val filled = (now - p.startMs) / p.spanMs * p.letters
                                val progress = filled / p.letters
                                val wordProgress = ((now - p.wordStartMs) / p.wordSpanMs).coerceIn(0f, 1f)
                                val rise = lift * EaseInOutQuad.transform(wordProgress)
                                // The phone's "glowing lyrics" switch turns the letters' glow off.
                                val shadow = if (glow && progress in 0f..1f) {
                                    Shadow(
                                        color = Focused.copy(alpha = (0.18f + 0.4f * progress).coerceAtMost(0.62f)),
                                        offset = Offset.Zero,
                                        blurRadius = glowBase + glowGrow * progress,
                                    )
                                } else {
                                    null
                                }
                                drawText(
                                    textLayoutResult = p.layout,
                                    brush = fillBrush(filled, p),
                                    topLeft = Offset(p.topLeft.x, p.topLeft.y - rise),
                                    shadow = shadow,
                                )
                            }
                        }
                    }
                }
            },
    )
}

/**
 * Flamingo's per-letter brush: lit up to the fill edge, waiting colour after it, the edge
 * feathered a third of a letter either way. A whole word spreads the same edge across
 * itself, right to left for a right-to-left script.
 */
private fun fillBrush(filled: Float, piece: Piece): Brush {
    val n = piece.letters.toFloat()
    val before = if (filled <= -0.5f) Unfocused else Focused
    val after = if (filled >= n) Focused else Unfocused
    val edge = filled / n
    val feather = Feather / n
    val stops = arrayOf(
        0f to before,
        (edge - feather).coerceIn(0f, 1f) to before,
        (edge + feather).coerceIn(0f, 1f) to after,
    )
    val width = piece.layout.size.width.toFloat()
    return if (piece.rightToLeft) {
        Brush.horizontalGradient(*stops, startX = width, endX = 0f)
    } else {
        Brush.horizontalGradient(*stops, startX = 0f, endX = width)
    }
}

/**
 * Splits the line the way Flamingo times it: each word runs from the end of the one before
 * (the line's start for the first) to its own end, its letters sharing that time evenly, and
 * the spaces after a word belong to it.
 */
private fun buildPieces(line: LyricLine, result: TextLayoutResult, style: TextStyle, measurer: TextMeasurer): List<Piece> {
    val text = line.text
    val spans = line.wordSpans
    val charStyle = style.copy(textAlign = TextAlign.Unspecified)
    val maxWidth = result.layoutInput.constraints.maxWidth
    val pieces = ArrayList<Piece>(text.length)
    for (i in line.words.indices) {
        val from = if (i == 0) 0 else spans[i].first
        val to = (spans.getOrNull(i + 1)?.first ?: text.length).coerceAtMost(text.length)
        if (to <= from) continue
        val wordStart = (if (i == 0) minOf(line.timeMs, line.words[0].startMs) else line.words[i - 1].endMs).toFloat()
        val wordSpan = (line.words[i].endMs - wordStart).coerceAtLeast(1f)
        val perLetter = (wordSpan / (to - from)).coerceAtLeast(1f)
        val piece = text.substring(from, to)
        if (piece.keepsWhole()) {
            // The word as one shape, sitting where its letters sit in the line.
            val inked = (from until to).filter { !text[it].isWhitespace() }
            if (inked.isEmpty()) continue
            val boxes = inked.map { result.getBoundingBox(it) }
            val word = text.substring(inked.first(), inked.last() + 1)
            pieces += Piece(
                layout = measurer.measure(word, charStyle, constraints = Constraints(maxWidth = maxWidth)),
                topLeft = Offset(boxes.minOf { it.left }, boxes.first().top),
                startMs = wordStart,
                spanMs = wordSpan,
                wordStartMs = wordStart,
                wordSpanMs = wordSpan,
                letters = word.length,
                rightToLeft = word.isRightToLeft(),
            )
        } else {
            for (j in from until to) {
                val c = text[j]
                if (c.isWhitespace()) continue
                pieces += Piece(
                    layout = measurer.measure(c.toString(), charStyle, constraints = Constraints(maxWidth = maxWidth)),
                    topLeft = result.getBoundingBox(j).topLeft,
                    startMs = wordStart + perLetter * (j - from),
                    spanMs = perLetter,
                    wordStartMs = wordStart,
                    wordSpanMs = wordSpan,
                    letters = 1,
                    rightToLeft = false,
                )
            }
        }
    }
    return pieces
}

private val JoiningScripts = setOf(
    Character.UnicodeScript.ARABIC, Character.UnicodeScript.SYRIAC, Character.UnicodeScript.THAANA,
    Character.UnicodeScript.NKO, Character.UnicodeScript.MONGOLIAN, Character.UnicodeScript.HEBREW,
    Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.BENGALI, Character.UnicodeScript.GURMUKHI,
    Character.UnicodeScript.GUJARATI, Character.UnicodeScript.ORIYA, Character.UnicodeScript.TAMIL,
    Character.UnicodeScript.TELUGU, Character.UnicodeScript.KANNADA, Character.UnicodeScript.MALAYALAM,
    Character.UnicodeScript.SINHALA, Character.UnicodeScript.THAI, Character.UnicodeScript.LAO,
    Character.UnicodeScript.TIBETAN, Character.UnicodeScript.MYANMAR, Character.UnicodeScript.KHMER,
)

/** Letters that only look right drawn together: joined or stacked scripts, marks, emoji. */
private fun String.keepsWhole(): Boolean = any { c ->
    c.isSurrogate() ||
        Character.getType(c).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt() } ||
        Character.UnicodeScript.of(c.code) in JoiningScripts
}

private fun String.isRightToLeft(): Boolean {
    for (c in this) {
        when (Character.getDirectionality(c)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        }
    }
    return false
}

/** Flamingo's instrumental break: three dots that fill in turn, breathing, on the line's card. */
@Composable
private fun Interlude(line: LyricLine, nextTimeMs: Long, isCurrent: () -> Boolean, live: LongState, otherSide: Boolean) {
    val percent by remember(line, nextTimeMs) {
        derivedStateOf {
            val span = (nextTimeMs - line.timeMs).coerceAtLeast(1L).toFloat()
            ((live.longValue - line.timeMs).coerceAtLeast(0L) / span).coerceAtMost(1f)
        }
    }
    val show by remember(line) { derivedStateOf { isCurrent() && percent > 0f } }
    val origin = TransformOrigin(if (otherSide) 1f else 0f, 0.25f)
    AnimatedVisibility(
        show,
        enter = fadeIn(TweenSpec(550, 300, YosEasing)) + scaleIn(TweenSpec(550, 300, YosEasing), initialScale = 0.85f, transformOrigin = origin),
        exit = fadeOut() + scaleOut(TweenSpec(340, easing = YosEasing), targetScale = 0.85f, transformOrigin = origin),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 10.dp)) {
            CountdownDots { percent }
        }
    }
}

@Composable
private fun CountdownDots(progress: () -> Float) {
    // Breathing in and out over 1.8 s each way, while the song plays.
    val breath by rememberLoopingPhase(3600, running = LocalPlayback.current.isPlaying)
    val scale = lerp(0.95f, 1.12f, YosEasing.transform(pingPong(breath)))
    Row(
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = 0.8f
            }
            .padding(horizontal = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (i in 1..3) {
            val third = 1f / 3f
            val mine = (progress() - (i - 1) * third) / third
            val alpha = 0.2f + (0.8f * mine).coerceIn(0f, 0.8f)
            Box(Modifier.size(11.dp).background(Color(0xFFF2F2F2).copy(alpha = alpha), CircleShape))
        }
    }
}

/** Lyrics with no timing: every line lit, read at your own pace, as Flamingo shows them. */
@Composable
private fun PlainLyrics(lyrics: Lyrics, subs: List<LineSubs?>, style: TextStyle, horizontalPadding: Dp) {
    Column(
        Modifier
            .fillMaxSize()
            .fadingEdge(64.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = horizontalPadding)
            .padding(top = 64.dp, bottom = 120.dp),
    ) {
        lyrics.lines.forEachIndexed { index, line ->
            Column(Modifier.padding(horizontal = 30.dp, vertical = 13.dp)) {
                Text(line.text, style = style, color = Focused)
                subs.getOrNull(index)?.let { SubLines(it, style, TextAlign.Start) }
            }
        }
        Text(
            "Lyrics from ${lyrics.source}",
            style = ConvxType.Caption,
            color = ConvxColors.TertiaryLabel,
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 16.dp),
        )
    }
}
