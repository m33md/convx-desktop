/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.ui.components.LocalPlayback
import com.convx.desktop.ui.components.rememberLoopingPhase
import com.convx.desktop.data.LyricsPosition
import com.convx.desktop.data.LyricsStyle
import com.convx.desktop.data.Settings
import com.convx.desktop.lyrics.Lyrics
import com.convx.desktop.lyrics.LyricsTweaks
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.shell.fadingEdge
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.convx.music.lyrics.bitchord.LyricAlignment
import com.convx.music.lyrics.bitchord.LyricLine
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The phone's lyric ink: white over every player background Convx draws. */
private val Ink = Color.White

/** Apple's "vibrant" grey: mid grey, colour-dodged onto what's behind (the phone's VibrantChromeInk). */
private val VibrantInk = Color(0xFF808080)

/** The styles that light word by word inside one string, as the phone's list does. */
private val WordSpanStyles = setOf(LyricsStyle.None, LyricsStyle.Fade, LyricsStyle.Glow, LyricsStyle.Slide, LyricsStyle.Karaoke, LyricsStyle.Apple)

/**
 * The phone's list styles: every style but Flamingo and Karaoke (Apple). One line per row, the
 * one being sung at full strength and a touch larger, the rest at half; each style lights the
 * sung line its own way. Lines a singer sings on the other side sit on that side, and the
 * chosen position places the rest.
 */
@Composable
internal fun StyledLyrics(lyrics: Lyrics, subs: List<LineSubs?>, lyricsStyle: LyricsStyle, base: TextStyle, settings: Settings, horizontalPadding: Dp, clock: () -> Long) {
    val lines = lyrics.lines
    val synced = lyrics.synced
    val live = rememberLiveClock(clock)
    val anchor = remember(lines, synced) { derivedStateOf { if (synced) anchorIndex(lines, live.longValue) else -1 } }
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    var scrolls by remember { mutableIntStateOf(0) }
    LaunchedEffect(scrolls) {
        if (scrolls == 0) return@LaunchedEffect
        follow = false
        delay(FollowPauseMs)
        follow = true
    }
    // The phone's list sizes: the chosen size and spacing, its 1.3 cap on wrapped lines.
    val size = base.fontSize.value
    val spacing = settings.lyricsLineSpacing
    val look = LineLook(
        base = base.copy(letterSpacing = 0.sp, lineHeightStyle = null, textMotion = TextMotion.Animated),
        size = size,
        spacing = spacing,
        glow = settings.lyricsGlow,
        position = settings.lyricsPosition,
        wordTimed = settings.lyricsWordSync,
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val half = maxHeight / 2
        LaunchedEffect(anchor.value, follow, maxHeight, settings.lyricsAutoScroll) {
            // The list's first item is the source credit, so line i is item i + 1.
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
            item(key = "credit") {
                Text(
                    "Lyrics from ${lyrics.source}",
                    style = ConvxType.Caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    color = Ink.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                val active by remember(index, lines) {
                    derivedStateOf { anchor.value >= 0 && lines[index].timeMs == lines[anchor.value].timeMs }
                }
                val distance by remember(index) { derivedStateOf { if (anchor.value < 0) 0 else abs(index - anchor.value) } }
                val seek: () -> Unit = {
                    if (synced) {
                        LyricsTweaks.seek(line.timeMs)
                        PlayerController.play()
                        follow = true
                    }
                }
                val nextTime = lines.getOrNull(index + 1)?.timeMs
                if ((lyricsStyle == LyricsStyle.ChordSync || lyricsStyle == LyricsStyle.ConvxFluid) && line.isWordSynced && look.wordTimed) {
                    ConvxLine(line, nextTime, live, synced, active, distance, look, settings.lyricsAppleBlur && follow, seek, subs.getOrNull(index))
                } else {
                    StyledLine(line, index, anchor, nextTime, live, synced, active, distance, lyricsStyle, look, settings.lyricsStandardBlur && follow, seek, subs.getOrNull(index))
                }
            }
            item(key = "extra") { Spacer(Modifier.height(200.dp)) }
        }
    }
}

/** What every row needs to draw itself. */
private class LineLook(
    val base: TextStyle,
    val size: Float,
    val spacing: Float,
    val glow: Boolean,
    val position: LyricsPosition,
    val wordTimed: Boolean,
) {
    val lineHeight get() = (size * spacing.coerceAtMost(1.3f)).sp

    fun align(line: LyricLine, background: Boolean = false): Alignment.Horizontal = when {
        background -> Alignment.CenterHorizontally
        line.alignment == LyricAlignment.End -> Alignment.End
        else -> when (position) {
            LyricsPosition.Left -> Alignment.Start
            LyricsPosition.Center -> Alignment.CenterHorizontally
            LyricsPosition.Right -> Alignment.End
        }
    }

    fun textAlign(line: LyricLine, background: Boolean = false): TextAlign = when (align(line, background)) {
        Alignment.CenterHorizontally -> TextAlign.Center
        Alignment.End -> TextAlign.Right
        else -> TextAlign.Left
    }

    fun arrangement(line: LyricLine, background: Boolean = false): Arrangement.Horizontal = when (align(line, background)) {
        Alignment.CenterHorizontally -> Arrangement.Center
        Alignment.End -> Arrangement.End
        else -> Arrangement.Start
    }

    fun origin(line: LyricLine): TransformOrigin = when (align(line)) {
        Alignment.CenterHorizontally -> TransformOrigin(0.5f, 0.5f)
        Alignment.End -> TransformOrigin(1f, 0.5f)
        else -> TransformOrigin(0f, 0.5f)
    }
}

/** A word's text with the spacing that follows it in the line, as the phone's wordsWithSpacing. */
private fun LyricLine.spacedWords(): List<Triple<String, Long, Long>> {
    val spans = wordSpans
    return words.mapIndexed { i, word ->
        val from = if (i == 0) 0 else spans[i].first
        val to = (spans.getOrNull(i + 1)?.first ?: text.length).coerceIn(from, text.length)
        Triple(text.substring(from, to).ifEmpty { word.text }, word.startMs, word.endMs)
    }
}

/**
 * Words with times relative to the line, from the line's own words or, without them, spread over
 * its time by letter count (the phone's estimate for plain LRC).
 */
private fun LyricLine.relativeWords(nextTimeMs: Long?, useTiming: Boolean): List<Triple<String, Long, Long>> {
    val duration = if (nextTimeMs != null) nextTimeMs - timeMs else 4000L
    // Leave 5% of the line for the change to the next one.
    val active = (duration * 0.95).toLong().coerceAtLeast(300L)
    if (useTiming && words.isNotEmpty()) {
        return spacedWords().map { (text, start, end) ->
            val s = (start - timeMs).coerceAtLeast(0L)
            Triple(text, s, (end - timeMs).coerceAtLeast(s + 50L))
        }
    }
    val split = text.split(" ").filter { it.isNotEmpty() }
    if (split.isEmpty()) return listOf(Triple(text, 0L, active))
    val total = text.length
    var at = 0L
    return split.mapIndexed { i, word ->
        val spaced = i < split.lastIndex
        val chars = if (spaced) word.length + 1 else word.length
        val span = if (total > 0) (active * chars.toFloat() / total).toLong() else active
        val start = at
        at += span
        Triple(if (spaced) "$word " else word, start, start + span)
    }
}

private fun smooth(t: Float) = t * t * (3f - 2f * t)

/**
 * The phone's list row for every style but Chord Sync and Convx (Fluid): full strength and 5%
 * larger while sung, half strength otherwise, softened with distance when Standard lyrics blur
 * is on.
 */
@Composable
private fun StyledLine(
    line: LyricLine,
    index: Int,
    anchor: State<Int>,
    nextTimeMs: Long?,
    live: LongState,
    synced: Boolean,
    active: Boolean,
    distance: Int,
    lyricsStyle: LyricsStyle,
    look: LineLook,
    standardBlur: Boolean,
    seek: () -> Unit,
    sub: LineSubs?,
) {
    val alpha by animateFloatAsState(if (!synced || active) 1f else 0.5f, tween(400))
    val scale by animateFloatAsState(if (active) 1.05f else 1f, tween(400))
    // 2.5 dp a line away, at most 8, so the lines nearby stay readable.
    val blur by animateFloatAsState(if (!standardBlur || active) 0f else (distance * 2.5f).coerceAtMost(8f), tween(300, easing = FastOutSlowInEasing))
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .hoverable(interaction)
            .pointerHoverIcon(if (synced) PointerIcon.Hand else PointerIcon.Default)
            .clickable(interaction, indication = null, onClick = seek)
            .padding(horizontal = 32.dp, vertical = (8 * look.spacing).dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = look.origin(line)
            }
            .then(if (blur > 0.01f) Modifier.blur(blur.dp, BlurredEdgeTreatment.Unbounded) else Modifier),
        horizontalAlignment = look.align(line),
    ) {
        if (line.isGap) {
            Spacer(Modifier.height(4.dp))
            return@Column
        }
        LineContent(line, nextTimeMs, live, active && synced, index < anchor.value, lyricsStyle, look, background = false)
        line.background?.let { bg ->
            Box(Modifier.fillMaxWidth().graphicsLayer { this.alpha = 0.8f; scaleX = 0.85f; scaleY = 0.85f }) {
                LineContent(bg, nextTimeMs, live, active && synced, index < anchor.value, lyricsStyle, look, background = true)
            }
        }
        sub?.let { SubLines(it, look.base.copy(fontSize = look.size.sp), look.textAlign(line)) }
    }
}

/** One line in [lyricsStyle], the phone's branches in its order. */
@Composable
private fun LineContent(
    line: LyricLine,
    nextTimeMs: Long?,
    live: LongState,
    active: Boolean,
    past: Boolean,
    lyricsStyle: LyricsStyle,
    look: LineLook,
    background: Boolean,
) {
    val align = look.textAlign(line, background)
    val wordTimed = look.wordTimed && line.isWordSynced
    when {
        wordTimed && lyricsStyle in WordSpanStyles -> WordSpanLine(line, live, active, past, lyricsStyle, look, align)
        lyricsStyle == LyricsStyle.AppleV2 -> LetterLine(line, nextTimeMs, live, active, look, background, wordTimed)
        lyricsStyle == LyricsStyle.LyricsV2 -> FluidLine(line, live, active, past, look, background, wordTimed)
        active && look.glow -> GlowingLine(line, look, align)
        active -> Text(line.text, style = look.base.copy(fontSize = look.size.sp, lineHeight = look.lineHeight, fontWeight = FontWeight.ExtraBold), color = Ink, textAlign = align, modifier = Modifier.fillMaxWidth())
        else -> Text(
            line.text,
            style = look.base.copy(fontSize = look.size.sp, lineHeight = look.lineHeight, fontWeight = FontWeight.Bold),
            color = VibrantInk.copy(alpha = if (background) 0.5f else 0.7f),
            textAlign = align,
            modifier = Modifier.fillMaxWidth().graphicsLayer { blendMode = BlendMode.ColorDodge },
        )
    }
}

/**
 * None, Fade, Glow, Slide, Karaoke and Apple Music: the line as one string, each word coloured,
 * weighted and lit by how far through it the singer is.
 */
@Composable
private fun WordSpanLine(line: LyricLine, live: LongState, active: Boolean, past: Boolean, lyricsStyle: LyricsStyle, look: LineLook, align: TextAlign) {
    // Only the line being sung reads the clock, so the rest don't redraw every frame.
    val now = if (active) live.longValue else 0L
    val lineColor = Ink.copy(alpha = 0.7f)
    val text = buildAnnotatedString {
        line.words.forEachIndexed { i, word ->
            val start = word.startMs
            val end = word.endMs
            val span = end - start
            when (lyricsStyle) {
                LyricsStyle.None, LyricsStyle.Fade, LyricsStyle.Glow, LyricsStyle.Apple -> {
                    val inWord = if (lyricsStyle == LyricsStyle.Apple) now >= start && now < end else now in start..end
                    val sung = if (lyricsStyle == LyricsStyle.Apple) {
                        (active && now >= end) || (!active && past)
                    } else {
                        active && now > end
                    }
                    val playing = active && inWord
                    val raw = when {
                        !active && lyricsStyle != LyricsStyle.Apple -> 0f
                        sung -> 1f
                        playing && span > 0 -> ((now - start).toFloat() / span).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    val progress = smooth(raw)
                    val style = when (lyricsStyle) {
                        LyricsStyle.None -> SpanStyle(
                            color = Ink.copy(alpha = when { !active -> 0.7f; sung -> 1f; playing -> 0.5f + 0.5f * progress; else -> 0.35f }),
                            fontWeight = when { !active || sung -> FontWeight.Bold; playing -> FontWeight.ExtraBold; else -> FontWeight.Medium },
                        )
                        LyricsStyle.Fade -> SpanStyle(
                            color = Ink.copy(alpha = when { !active -> 0.55f; sung -> 1f; playing -> 0.4f + 0.6f * progress; else -> 0.4f }),
                            fontWeight = when { !active || sung -> FontWeight.Bold; playing -> FontWeight.ExtraBold; else -> FontWeight.Medium },
                            shadow = when {
                                playing && progress > 0.2f -> Shadow(Ink.copy(alpha = 0.35f * progress), Offset.Zero, 10f * progress)
                                sung -> Shadow(Ink.copy(alpha = 0.15f), Offset.Zero, 6f)
                                else -> null
                            },
                        )
                        LyricsStyle.Glow -> {
                            val glow = progress * progress
                            SpanStyle(
                                color = when { !active -> Ink.copy(alpha = 0.5f); playing || sung -> Ink.copy(alpha = 0.45f + 0.55f * progress); else -> Ink.copy(alpha = 0.35f) },
                                fontWeight = when { !active -> FontWeight.Bold; playing -> FontWeight.ExtraBold; sung -> FontWeight.Bold; else -> FontWeight.Medium },
                                shadow = when {
                                    playing && glow > 0.05f -> Shadow(Ink.copy(alpha = 0.5f + 0.3f * glow), Offset.Zero, 16f + 12f * glow)
                                    sung -> Shadow(Ink.copy(alpha = 0.25f), Offset.Zero, 8f)
                                    else -> null
                                },
                            )
                        }
                        else -> {
                            val glow = progress * progress
                            SpanStyle(
                                color = Ink.copy(alpha = when { !active -> 0.55f; sung -> 1f; playing -> 0.55f + 0.45f * progress; else -> 0.4f }),
                                fontWeight = when { !active -> FontWeight.SemiBold; sung -> FontWeight.Bold; playing -> FontWeight.ExtraBold; else -> FontWeight.Normal },
                                shadow = when {
                                    !look.glow -> null
                                    playing -> Shadow(Ink.copy(alpha = 0.2f + 0.4f * glow), Offset.Zero, 10f + 12f * glow)
                                    sung && active -> Shadow(Ink.copy(alpha = 0.2f), Offset.Zero, 8f)
                                    else -> null
                                },
                            )
                        }
                    }
                    withStyle(style) { append(word.text) }
                }
                LyricsStyle.Slide, LyricsStyle.Karaoke -> {
                    val playing = active && now >= start && now < end
                    val sung = (active && now >= end) || (!active && past)
                    if (playing && span > 0) {
                        val elapsed = now - start
                        val linear = (elapsed.toFloat() / span).coerceIn(0f, 1f)
                        if (lyricsStyle == LyricsStyle.Slide) {
                            val breathe = (sin(((elapsed % 3000) / 3000.0) * PI * 2.0) * 0.03).toFloat().coerceIn(0f, 0.03f)
                            val glow = (0.3f + linear * 0.7f + breathe).coerceIn(0f, 1.1f)
                            withStyle(
                                SpanStyle(
                                    brush = Brush.horizontalGradient(
                                        0f to Ink,
                                        (linear * 0.95f).coerceIn(0f, 1f) to Ink,
                                        linear to Ink.copy(alpha = 0.9f),
                                        (linear + 0.02f).coerceIn(0f, 1f) to Ink.copy(alpha = 0.5f),
                                        (linear + 0.08f).coerceIn(0f, 1f) to Ink.copy(alpha = 0.35f),
                                        1f to Ink.copy(alpha = 0.35f),
                                    ),
                                    fontWeight = FontWeight.ExtraBold,
                                    shadow = Shadow(Ink.copy(alpha = (0.4f * glow).coerceIn(0f, 1f)), Offset.Zero, 14f + 4f * linear),
                                ),
                            ) { append(word.text) }
                        } else {
                            val fill = smooth(linear)
                            val glow = fill * fill
                            withStyle(
                                SpanStyle(
                                    brush = Brush.horizontalGradient(
                                        0f to Ink.copy(alpha = 0.4f),
                                        (fill * 0.6f).coerceIn(0f, 1f) to Ink.copy(alpha = 0.75f),
                                        (fill * 0.85f).coerceIn(0f, 1f) to Ink.copy(alpha = 0.95f),
                                        fill to Ink,
                                        (fill + 0.03f).coerceIn(0f, 1f) to Ink.copy(alpha = 0.85f),
                                        (fill + 0.1f).coerceIn(0f, 1f) to Ink.copy(alpha = 0.5f),
                                        1f to Ink.copy(alpha = if (fill >= 0.9f) 0.95f else 0.4f),
                                    ),
                                    fontWeight = FontWeight.ExtraBold,
                                    shadow = if (look.glow) Shadow(Ink.copy(alpha = 0.5f + 0.3f * glow), Offset.Zero, 16f + 12f * glow) else null,
                                ),
                            ) { append(word.text) }
                        }
                    } else if (sung && active) {
                        val shadow = when {
                            lyricsStyle == LyricsStyle.Slide -> Shadow(Ink.copy(alpha = 0.4f), Offset.Zero, 12f)
                            look.glow -> Shadow(Ink.copy(alpha = 0.25f), Offset.Zero, 8f)
                            else -> null
                        }
                        withStyle(SpanStyle(color = Ink, fontWeight = FontWeight.Bold, shadow = shadow)) { append(word.text) }
                    } else {
                        val color = if (!active) lineColor else Ink.copy(alpha = if (lyricsStyle == LyricsStyle.Slide) 0.35f else 0.4f)
                        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium)) { append(word.text) }
                    }
                }
                else -> append(word.text)
            }
            if (i < line.words.lastIndex) append(" ")
        }
    }
    val height = if (lyricsStyle == LyricsStyle.Slide) (look.size * look.spacing).sp else look.lineHeight
    Text(text, style = look.base.copy(fontSize = look.size.sp, lineHeight = height), textAlign = align, modifier = Modifier.fillMaxWidth())
}

/** The phone's line without word timing while it's sung: it fills in over 1.2 s, glows, and breathes. */
@Composable
private fun GlowingLine(line: LyricLine, look: LineLook, align: TextAlign) {
    val fill = remember { Animatable(0f) }
    // It breathes every three seconds while the song plays, and holds its breath while paused.
    val pulse by rememberLoopingPhase(3000, running = LocalPlayback.current.isPlaying)
    LaunchedEffect(line) {
        fill.snapTo(0f)
        fill.animateTo(1f, tween(1200, easing = FastOutSlowInEasing))
    }
    val f = fill.value
    val pulseEffect = (sin(pulse * PI.toFloat()) * 0.15f).coerceIn(0f, 0.15f)
    val glow = (f + pulseEffect).coerceIn(0f, 1.2f)
    val text = buildAnnotatedString {
        withStyle(
            SpanStyle(
                shadow = Shadow(Ink.copy(alpha = (0.8f * glow).coerceIn(0f, 1f)), Offset.Zero, 28f * (1f + pulseEffect)),
                brush = Brush.horizontalGradient(
                    0f to Ink.copy(alpha = 0.3f),
                    (f * 0.7f).coerceIn(0f, 1f) to Ink.copy(alpha = 0.9f),
                    f to Ink,
                    (f + 0.1f).coerceIn(0f, 1f) to Ink.copy(alpha = 0.7f),
                    1f to Ink.copy(alpha = if (f >= 1f) 1f else 0.3f),
                ),
            ),
        ) { append(line.text) }
    }
    val bounce = if (f < 0.3f) 1f + sin(f * 3.33f * PI.toFloat()) * 0.03f else 1f
    Text(
        text,
        style = look.base.copy(fontSize = look.size.sp, lineHeight = look.lineHeight, fontWeight = FontWeight.ExtraBold),
        textAlign = align,
        modifier = Modifier.fillMaxWidth().graphicsLayer {
            scaleX = bounce
            scaleY = bounce
        },
    )
}

/** Apple Music V2: letter by letter, each letter brightening over its share of its word. */
@Composable
private fun LetterLine(line: LyricLine, nextTimeMs: Long?, live: LongState, active: Boolean, look: LineLook, background: Boolean, wordTimed: Boolean) {
    val words = remember(line, nextTimeMs, wordTimed) { line.relativeWords(nextTimeMs, wordTimed) }
    val gap = with(LocalDensity.current) { (look.size * (look.spacing.coerceAtMost(1.3f) - 1f)).sp.toDp() }
    val letterStyle = look.base.copy(fontSize = look.size.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = look.arrangement(line, background), verticalArrangement = Arrangement.spacedBy(gap)) {
        val rel = if (active) (live.longValue - line.timeMs).coerceAtLeast(0L) else 0L
        words.forEachIndexed { i, (word, start, end) ->
            val letters = word.trimEnd()
            val span = end - start
            Row {
                letters.forEachIndexed { c, char ->
                    val each = if (letters.isNotEmpty()) span / letters.length else 0L
                    val from = start + c * each
                    val until = from + each
                    val progress = when {
                        !active -> 0f
                        rel >= until -> 1f
                        rel < from -> 0f
                        each <= 0L -> 1f
                        else -> (rel - from).toFloat() / each
                    }
                    Text(char.toString(), style = letterStyle, color = Ink.copy(alpha = if (!active || progress >= 1f) 1f else 0.3f + 0.7f * progress))
                }
                if (i < words.lastIndex) Text(" ", style = letterStyle)
            }
        }
    }
}

/**
 * Lyrics V2 (Fluid): every word has a dim copy under a lit one; the lit one is wiped in with a
 * soft edge while the word floats up a few pixels and glows.
 */
@Composable
private fun FluidLine(line: LyricLine, live: LongState, active: Boolean, past: Boolean, look: LineLook, background: Boolean, wordTimed: Boolean) {
    val inactiveAlpha = 0.35f
    val size = if (background) look.size * 0.85f else look.size
    val height = (look.size * look.spacing).coerceAtMost(look.size * 1.3f).sp
    val style = look.base.copy(
        fontSize = size.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (size * 0.012f).sp,
        fontStyle = if (background) FontStyle.Italic else FontStyle.Normal,
        lineHeight = height,
    )
    if (!wordTimed) {
        Text(line.text, style = style, color = Ink.copy(alpha = if (active) 1f else inactiveAlpha), textAlign = look.textAlign(line, background), modifier = Modifier.fillMaxWidth())
        return
    }
    val words = remember(line) { line.spacedWords() }
    val density = LocalDensity.current
    val gap = with(density) { (look.size * (look.spacing.coerceAtMost(1.3f) - 1f)).sp.toDp() }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = look.arrangement(line, background), verticalArrangement = Arrangement.spacedBy(gap)) {
        words.forEach { (text, start, end) ->
            val trailing = text.takeLastWhile { it.isWhitespace() }.length
            Box(Modifier.padding(end = with(density) { (look.size * 0.25f * trailing).sp.toDp() })) {
                // The phone lands words a beat early (150 ms) so they feel on the beat.
                FluidWord(text.trimEnd(), start, end, live, active, past, style, background, inactiveAlpha)
            }
        }
    }
}

@Composable
private fun FluidWord(text: String, startMs: Long, endMs: Long, live: LongState, lineActive: Boolean, linePast: Boolean, style: TextStyle, background: Boolean, inactiveAlpha: Float) {
    val now = if (lineActive) live.longValue + 150L else 0L
    val span = (endMs - startMs).coerceAtLeast(1L)
    val complete = linePast || (lineActive && now >= endMs)
    val playing = lineActive && now in startMs until endMs
    val progress = when {
        complete -> 1f
        !lineActive || now <= startMs -> 0f
        else -> ((now - startMs).toFloat() / span).coerceIn(0f, 1f)
    }
    val sinProgress = sin(progress * PI).toFloat()
    val wordScale = 1f + 0.015f * sinProgress
    val float by animateFloatAsState(if (playing) -4f * sinProgress else 0f, tween(if (playing) 50 else 350, easing = FastOutSlowInEasing))
    val glowProgress = (progress * 2f).coerceAtMost(1f)
    val glowAlpha = if (playing) glowProgress * 0.45f else 0f
    val glowRadius = if (playing) glowProgress * 12f else 0f
    Box(
        Modifier.graphicsLayer {
            translationY = float * density
            scaleX = wordScale
            scaleY = wordScale
        },
    ) {
        Text(text, style = style, color = Ink.copy(alpha = if (background) inactiveAlpha * 0.7f else inactiveAlpha))
        if (complete || playing) {
            Text(
                text,
                style = style.copy(shadow = if (glowAlpha > 0f) Shadow(Ink.copy(alpha = glowAlpha), Offset.Zero, glowRadius.coerceAtLeast(1f)) else null),
                color = Ink.copy(alpha = if (background) 0.75f else 1f),
                modifier = if (playing) {
                    Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val edge = 8.dp.toPx()
                            val center = (size.width + edge * 2) * progress - edge
                            drawRect(
                                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = center - edge, endX = center + edge),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                } else {
                    Modifier
                },
            )
        }
    }
}

/**
 * Chord Sync and Convx (Fluid), the phone's ConvxLyricsLine: lines fade with distance from the
 * sung one (and blur, with Apple Music lyrics blur on), and each word fills with a soft edge and
 * a glow that grows as it's sung.
 */
@Composable
private fun ConvxLine(
    line: LyricLine,
    nextTimeMs: Long?,
    live: LongState,
    synced: Boolean,
    active: Boolean,
    distance: Int,
    look: LineLook,
    appleBlur: Boolean,
    seek: () -> Unit,
    sub: LineSubs?,
) {
    val blur by animateFloatAsState(
        if (!appleBlur || active || !synced) 0f else when (distance) {
            1, 2 -> 0f
            3 -> 2f
            4 -> 4f
            else -> 6f
        },
        tween(1000),
    )
    val alpha by animateFloatAsState(
        when {
            !synced || active -> 1f
            distance == 1 -> 0.65f
            distance == 2 -> 0.45f
            else -> 0.35f
        },
        tween(300),
    )
    val scale by animateFloatAsState(if (active) 1.05f else 1f, tween(400))
    // The phone times Hindi by letters even when words are timed: its word splits don't match the script.
    val hindi = remember(line) { line.text.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.DEVANAGARI } }
    val words = remember(line, nextTimeMs, hindi) { line.relativeWords(nextTimeMs, useTiming = !hindi) }
    val density = LocalDensity.current
    val gap = with(density) { (look.size * (look.spacing.coerceAtMost(1.3f) - 1f)).sp.toDp() }
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = look.origin(line)
            }
            .clip(RoundedCornerShape(16.dp))
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interaction, indication = null, onClick = seek)
            .padding(horizontal = 24.dp, vertical = (8 * look.spacing).dp)
            .then(if (blur > 0.01f) Modifier.blur(blur.dp, BlurredEdgeTreatment.Unbounded) else Modifier),
        horizontalAlignment = look.align(line),
    ) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = look.arrangement(line), verticalArrangement = Arrangement.spacedBy(gap)) {
            val rel = (live.longValue - line.timeMs).coerceAtLeast(0L).takeIf { active } ?: if (synced && distance > 0 && !active) -1L else 0L
            words.forEach { (text, start, end) ->
                val target = when {
                    rel < 0L -> 0f
                    rel >= end -> 1f
                    rel < start -> 0f
                    else -> (rel - start).toFloat() / (end - start).coerceAtLeast(1L)
                }
                val progress by animateFloatAsState(target, tween(150, easing = LinearEasing))
                val trailing = text.takeLastWhile { it.isWhitespace() }.length
                Text(
                    text.trimEnd(),
                    modifier = Modifier.padding(end = with(density) { (look.size * 0.25f * trailing).sp.toDp() }),
                    style = look.base.copy(
                        fontSize = look.size.sp,
                        brush = Brush.horizontalGradient(
                            0f to Ink,
                            (progress - 0.05f).coerceAtLeast(0f) to Ink,
                            (progress + 0.05f).coerceAtMost(1f) to Ink.copy(alpha = 0.45f),
                            1f to Ink.copy(alpha = 0.45f),
                        ),
                        fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Bold,
                        letterSpacing = (look.size * 0.012f).sp,
                        lineHeight = look.lineHeight,
                        textAlign = look.textAlign(line),
                        shadow = Shadow(Ink.copy(alpha = 0.6f * progress), Offset.Zero, (12f * progress).coerceAtLeast(0.1f)),
                    ),
                )
            }
        }
        sub?.let { SubLines(it, look.base.copy(fontSize = look.size.sp), look.textAlign(line)) }
    }
}

/**
 * Karaoke (Apple), the phone's Accompanist view: syllables lit as they're sung, lines either side
 * of the sung one sinking back and blurring, duets on two sides and background vocals riding the
 * line they're sung under.
 */
@Composable
internal fun KaraokeAppleLyrics(lyrics: Lyrics, translations: List<String?>?, base: TextStyle, settings: Settings, horizontalPadding: Dp, clock: () -> Long) {
    val synced = remember(lyrics, translations) { lyrics.lines.toSyncedLyrics(translations) }
    val size = base.fontSize.value
    val spacing = settings.lyricsLineSpacing
    val shadow = settings.lyricsGlow
    val normal = TextStyle(
        fontFamily = base.fontFamily,
        fontSize = size.sp,
        lineHeight = (size * spacing).sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (size * 0.012f).sp,
        shadow = if (shadow) Shadow(Ink.copy(alpha = 0.24f), Offset.Zero, 10f) else null,
    )
    val accompaniment = TextStyle(
        fontFamily = base.fontFamily,
        fontSize = (size * 0.82f).sp,
        lineHeight = (size * 0.82f * spacing).sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (size * 0.82f * 0.012f).sp,
        shadow = if (shadow) Shadow(Ink.copy(alpha = 0.18f), Offset.Zero, 8f) else null,
    )
    val phonetic = TextStyle(fontFamily = base.fontFamily, fontSize = (size * 0.5f).sp, lineHeight = (size * 0.5f * spacing).sp, fontWeight = FontWeight.Medium)
    val live = rememberLiveClock(clock)
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = horizontalPadding)) {
        val offset = maxHeight * 0.5f - with(LocalDensity.current) { (size * spacing / 2f).sp.toDp() }
        key(synced) {
            KaraokeLyricsView(
                listState = rememberLazyListState(),
                lyrics = synced,
                currentPosition = { live.longValue.toInt() },
                onLineClicked = { line: ISyncedLine ->
                    if (line.start > 0) {
                        LyricsTweaks.seek(line.start.toLong())
                        PlayerController.play()
                    }
                },
                onLinePressed = {},
                textColor = Ink,
                normalLineTextStyle = normal,
                accompanimentLineTextStyle = accompaniment,
                phoneticTextStyle = phonetic,
                blendMode = BlendMode.SrcOver,
                showTranslation = translations != null,
                showPhonetic = false,
                offset = offset,
                keepAliveZone = 72.dp,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** A word-less line may stay lit this long before the next arrives (the phone's MaxLineHoldMs). */
private const val MaxLineHoldMs = 4_000L

/**
 * Convx's lines in Accompanist's shape: word-timed lines become karaoke lines with their
 * background vocals riding along, the rest plain synced lines; breaks are left out.
 */
private fun List<LyricLine>.toSyncedLyrics(translations: List<String?>?): SyncedLyrics {
    val out = ArrayList<ISyncedLine>()
    forEachIndexed { index, line ->
        if (line.isGap || line.text.isBlank() && line.words.isEmpty()) return@forEachIndexed
        val alignment = if (line.alignment == LyricAlignment.End) KaraokeAlignment.End else KaraokeAlignment.Start
        val syllables = line.syllables()
        if (syllables.isEmpty()) {
            val next = getOrNull(index + 1)?.timeMs?.minus(1L) ?: (line.timeMs + MaxLineHoldMs)
            val end = minOf(next, line.timeMs + MaxLineHoldMs).coerceAtLeast(line.timeMs + 1L)
            out += SyncedLine(content = line.text, translation = translations?.getOrNull(index), start = line.timeMs.toInt(), end = end.toInt())
            return@forEachIndexed
        }
        val start = syllables.first().start
        val end = syllables.last().end
        if (end <= start) return@forEachIndexed
        val background = line.background?.syllables()?.takeIf { it.isNotEmpty() }?.let { bg ->
            val bgStart = bg.first().start
            val bgEnd = bg.last().end
            if (bgEnd <= bgStart) null else listOf(KaraokeLine.AccompanimentKaraokeLine(bg, null, alignment, bgStart, bgEnd, null))
        }
        out += KaraokeLine.MainKaraokeLine(syllables, translations?.getOrNull(index), alignment, start, end, null, background)
    }
    return SyncedLyrics(lines = out.sortedBy { it.start })
}

/** Words as syllables, clamped so none outlives the next one's start (the phone's toSyllables). */
private fun LyricLine.syllables(): List<KaraokeSyllable> {
    val words = spacedWords()
    return words.mapIndexed { i, (text, startMs, endMs) ->
        val start = startMs.toInt().coerceAtLeast(0)
        val nextStart = words.getOrNull(i + 1)?.second?.toInt()
        val rawEnd = endMs.toInt().coerceAtLeast(0)
        val end = nextStart?.let { minOf(rawEnd, it) } ?: rawEnd
        KaraokeSyllable(content = text, start = start, end = end.coerceAtLeast(start + 1))
    }
}

