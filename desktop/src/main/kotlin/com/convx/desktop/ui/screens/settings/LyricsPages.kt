/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import com.convx.desktop.core.Secrets
import com.convx.desktop.lyrics.LyricsService
import com.convx.desktop.lyrics.Romanizer
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.DialogCard
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.core.AppScope
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.LyricsPosition
import com.convx.desktop.data.LyricsStyle
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.glass.GlassSwitch
import com.convx.desktop.ui.player.LyricsFonts
import com.convx.desktop.ui.player.LyricsView
import com.convx.desktop.ui.player.NowPlayingBackground
import com.convx.desktop.ui.player.rememberLyricsFont
import com.convx.desktop.ui.shapes.ContinuousCapsule
import com.convx.desktop.ui.shapes.ContinuousRoundedRectangle
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The phone's Lyrics style sheet: the song that's playing in the chosen style, still singing while
 * paused so every style can be watched moving, the styles to pick from, and where the lines sit.
 */
@Composable
fun LyricsStyleSettings() {
    val settings by AppSettings.state.collectAsState()
    val playing by PlayerController.state.collectAsState()
    val library by Library.state.collectAsState()
    val song = playing.current ?: library.history.firstOrNull()?.song
    val accent = SystemColors.Red

    Text(
        "Click a style to preview and apply it",
        style = ConvxType.Footnote,
        color = ConvxColors.SecondaryLabel,
        modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
    )
    if (song != null) {
        // Paused (or nothing loaded), a clock carries on from where the song stopped and loops at its end.
        var paused by remember { mutableLongStateOf(0L) }
        val live = playing.isPlaying && playing.current?.id == song.id
        LaunchedEffect(live, song.id) {
            if (live) return@LaunchedEffect
            val from = if (playing.current?.id == song.id) PlayerController.livePositionMs().coerceAtLeast(0L) else 0L
            val length = ((song.duration ?: 0) * 1000L).coerceAtLeast(30_000L)
            val startedAt = System.currentTimeMillis()
            while (true) {
                withFrameMillis { }
                paused = (from + System.currentTimeMillis() - startedAt) % length
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(380.dp)
                .clip(ContinuousRoundedRectangle(24.dp)),
        ) {
            NowPlayingBackground(song.thumbnail, hero = false, modifier = Modifier.fillMaxSize())
            LyricsView(
                song,
                Modifier.fillMaxSize(),
                style = ConvxType.LyricsCompact,
                horizontalPadding = 8.dp,
                clock = { if (live) PlayerController.livePositionMs() else paused },
            )
            Text(
                song.title,
                color = Color.White.copy(alpha = 0.6f),
                style = ConvxType.CaptionStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
            )
        }
    } else {
        SampleLyrics(settings.lyricsStyle, settings.lyricsPosition, accent)
    }
    Spacer(Modifier.height(18.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LyricsStyle.entries.forEach { style ->
            val active = style == settings.lyricsStyle
            val fill by animateColorAsState(if (active) accent else Color.White.copy(alpha = 0.12f), tween(220))
            Text(
                style.label,
                color = Color.White,
                style = ConvxType.Subhead,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .clip(ContinuousCapsule)
                    .background(fill)
                    .clickable { AppSettings.update { it.copy(lyricsStyle = style) } }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
    SectionGap()
    SettingsSection {
        SettingsRow("Lyrics text position") {
            SegmentedControl(
                LyricsPosition.entries.map { it.label },
                LyricsPosition.entries.indexOf(settings.lyricsPosition),
                Modifier.width(240.dp),
            ) { i -> AppSettings.update { it.copy(lyricsPosition = LyricsPosition.entries[i]) } }
        }
        RowDivider(PlainInset)
        SwitchRow("Auto scroll lyrics", "Keep the line being sung in the middle", settings.lyricsAutoScroll) { v ->
            AppSettings.update { it.copy(lyricsAutoScroll = v) }
        }
        RowDivider(PlainInset)
        SwitchRow(
            "Swipe to change song",
            "Swipe left or right with two fingers on the song name in full-screen lyrics to change the song",
            settings.lyricsSwipeToChange,
        ) { v -> AppSettings.update { it.copy(lyricsSwipeToChange = v) } }
        RowDivider(PlainInset)
        SwitchRow(
            "One-tap full-screen lyrics",
            "The lyrics button opens and closes full-screen lyrics in one click. Off uses the classic two steps: open the lyrics, then the expand button for full screen",
            settings.oneTapFullscreenLyrics,
        ) { v -> AppSettings.update { it.copy(oneTapFullscreenLyrics = v) } }
        RowDivider(PlainInset)
        SwitchRow(
            "Lyrics in the Now Playing view",
            "The lyrics card in the panel on the right. Off hides it; the Lyrics tab and the player still show lyrics",
            settings.nowPlayingLyrics,
        ) { v -> AppSettings.update { it.copy(nowPlayingLyrics = v) } }
    }
}

/** With nothing played yet: the phone's sample, three lines with the middle one in the chosen style. */
@Composable
private fun SampleLyrics(selected: LyricsStyle, position: LyricsPosition, accent: Color) {
    val transition = rememberInfiniteTransition()
    val progress by transition.animateFloat(0.18f, 0.82f, infiniteRepeatable(tween(1900), RepeatMode.Reverse))
    val align = when (position) {
        LyricsPosition.Left -> TextAlign.Start
        LyricsPosition.Center -> TextAlign.Center
        LyricsPosition.Right -> TextAlign.End
    }
    val fill = selected in setOf(LyricsStyle.Flamingo, LyricsStyle.Karaoke, LyricsStyle.KaraokeApple, LyricsStyle.Apple, LyricsStyle.AppleV2, LyricsStyle.ChordSync, LyricsStyle.LyricsV2)
    val ground = Color(0xFF111217)
    Box(
        Modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(ContinuousRoundedRectangle(24.dp))
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.38f).compositeOver(ground), ground))),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
            Text("Where the light meets the sea", color = Color.White.copy(alpha = 0.38f), style = ConvxType.Subhead, textAlign = align, maxLines = 1, modifier = Modifier.fillMaxWidth())
            val base = ConvxType.Title2.copy(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
            Text(
                "We find our way back home",
                style = when {
                    fill -> base.copy(
                        brush = Brush.horizontalGradient(
                            0f to accent,
                            progress to accent,
                            (progress + 0.001f).coerceAtMost(1f) to Color.White.copy(alpha = 0.4f),
                            1f to Color.White.copy(alpha = 0.4f),
                        ),
                    )
                    selected == LyricsStyle.Glow -> base.copy(color = accent, shadow = Shadow(accent, blurRadius = 8f + progress * 22f))
                    else -> base.copy(color = accent)
                },
                textAlign = align,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    when (selected) {
                        LyricsStyle.None -> Unit
                        LyricsStyle.Fade -> alpha = 0.45f + progress * 0.55f
                        LyricsStyle.Slide -> {
                            translationX = (1f - progress) * 28.dp.toPx() * if (position == LyricsPosition.Right) -1f else 1f
                            alpha = 0.5f + progress * 0.5f
                        }
                        else -> {
                            scaleX = 0.94f + progress * 0.06f
                            scaleY = scaleX
                            transformOrigin = TransformOrigin(
                                when (position) {
                                    LyricsPosition.Left -> 0f
                                    LyricsPosition.Center -> 0.5f
                                    LyricsPosition.Right -> 1f
                                },
                                0.5f,
                            )
                        }
                    }
                },
            )
            Text("Under the open sky", color = Color.White.copy(alpha = 0.38f), style = ConvxType.Subhead, textAlign = align, maxLines = 1, modifier = Modifier.fillMaxWidth())
        }
        Text("SAMPLE", color = Color.White.copy(alpha = 0.45f), style = ConvxType.CaptionStrong, modifier = Modifier.align(Alignment.TopEnd).padding(14.dp))
    }
}

/**
 * The phone's Lyrics page: the style in use, the lyrics font and size, and the glow and two blurs.
 */
@Composable
fun LyricsEffectsSettings() {
    val settings by AppSettings.state.collectAsState()
    SettingsSection("Word-by-word animation style") {
        LinkRow(
            settings.lyricsStyle.label,
            "Smooth centered scrolling, with word animation when timing is available",
            icon = "lyrics",
            tint = SystemColors.Pink,
        ) { SettingsNav.push(SettingsPage.LyricsStyle) }
    }
    SectionGap()
    LyricsFontSection()
    SectionGap()
    SettingsSection("Lyrics effects") {
        SwitchRow("Enable glowing lyrics effect", "Add glowing animation and bounce effect to active lyrics", settings.lyricsGlow, icon = "lyrics", tint = SystemColors.Pink) { v ->
            AppSettings.update { it.copy(lyricsGlow = v) }
        }
        RowDivider()
        SwitchRow(
            "Apple Music Lyrics Blur",
            "Apply blur to inactive lyrics lines for a premium focus effect",
            settings.lyricsAppleBlur,
            icon = "lyrics",
            tint = SystemColors.Pink,
        ) { v -> AppSettings.update { it.copy(lyricsAppleBlur = v) } }
        RowDivider()
        SwitchRow(
            "Standard lyrics blur",
            "Apply blur to inactive lyrics lines for a premium focus effect",
            settings.lyricsStandardBlur,
            icon = "lyrics",
            tint = SystemColors.Pink,
        ) { v -> AppSettings.update { it.copy(lyricsStandardBlur = v) } }
    }
}

/** The phone's lyrics font picker: four faces, one you import, the size, and a line to judge them by. */
@Composable
private fun LyricsFontSection() {
    val settings by AppSettings.state.collectAsState()
    val family = rememberLyricsFont(settings.lyricsFont)
    // The size moves live under the slider and is saved when it's let go, as the phone does.
    var size by remember(settings.lyricsTextSize) { mutableFloatStateOf(settings.lyricsTextSize) }
    SettingsSection("Lyrics font", footer = "Text size is the full player's; the side panel keeps its smaller proportion.") {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("default" to "Default", "rounded" to "Rounded", "serif" to "Serif", "monospace" to "Mono").forEach { (id, label) ->
                    FontChip(label, settings.lyricsFont == id) { AppSettings.update { it.copy(lyricsFont = id) } }
                }
                if (settings.lyricsCustomFont.isNotBlank()) {
                    FontChip(if (settings.lyricsFont == settings.lyricsCustomFont) "Imported font" else "Use imported font", settings.lyricsFont == settings.lyricsCustomFont) {
                        AppSettings.update { it.copy(lyricsFont = it.lyricsCustomFont) }
                    }
                }
                PillButton("Import a TTF or OTF font", { importFont() }, icon = "add")
            }
            Text(
                "Your lyrics, your way",
                style = ConvxType.Lyrics.copy(fontFamily = family ?: ConvxType.Lyrics.fontFamily, fontSize = size.sp, lineHeight = (size * 1.3f).sp),
                color = Color.White,
            )
        }
        RowDivider(PlainInset)
        SliderRow(
            "Text size",
            "${size.roundToInt()} sp",
            size,
            18f..60f,
            onChangeFinished = { AppSettings.update { it.copy(lyricsTextSize = size) } },
        ) { size = it.roundToInt().toFloat() }
    }
}

@Composable
private fun FontChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(if (selected) SystemColors.Red else Color.White.copy(alpha = 0.12f), tween(180))
    Row(
        Modifier.clip(ContinuousCapsule).background(fill).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = ConvxType.Subhead, color = Color.White, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

private fun importFont() {
    val file = pickFile("Import a TTF or OTF font", listOf("ttf", "otf")) ?: return
    AppScope.launch(Dispatchers.IO) {
        runCatching { LyricsFonts.import(file) }
            .onSuccess { name ->
                AppSettings.update { it.copy(lyricsCustomFont = name, lyricsFont = name) }
                Overlays.toast("Font imported", "lyrics")
            }
            .onFailure { Overlays.toast(it.message ?: "Couldn't import this font", "error") }
    }
}

/** The phone's "Text & layout" for lyrics: where they sit, how they scroll, and how they open. */
@Composable
fun LyricsTextSettings() {
    val settings by AppSettings.state.collectAsState()
    var spacing by remember(settings.lyricsLineSpacing) { mutableFloatStateOf(settings.lyricsLineSpacing) }
    SettingsSection("Lyrics layout") {
        LinkRow("Lyrics text position", icon = "lyrics", tint = SystemColors.Orange, value = settings.lyricsPosition.label) { SettingsNav.push(SettingsPage.LyricsStyle) }
        RowDivider()
        LinkRow("Word-by-word animation style", icon = "lyrics", tint = SystemColors.Orange, value = settings.lyricsStyle.label) { SettingsNav.push(SettingsPage.LyricsStyle) }
        RowDivider()
        SwitchRow("Auto scroll lyrics", checked = settings.lyricsAutoScroll, icon = "lyrics", tint = SystemColors.Orange) { v -> AppSettings.update { it.copy(lyricsAutoScroll = v) } }
        RowDivider()
        SwitchRow(
            "Swipe to change song",
            "Swipe left or right with two fingers on the song name in full-screen lyrics to change the song",
            settings.lyricsSwipeToChange,
            icon = "swipe",
            tint = SystemColors.Orange,
        ) { v -> AppSettings.update { it.copy(lyricsSwipeToChange = v) } }
        RowDivider()
        SwitchRow(
            "Word-by-word lyrics",
            "Light up each word as it's sung, when the lyrics have word timing",
            settings.lyricsWordSync,
            icon = "lyrics",
            tint = SystemColors.Orange,
        ) { v -> AppSettings.update { it.copy(lyricsWordSync = v) } }
    }
    SectionGap()
    SettingsSection("Text", footer = "Font and size are under Lyrics effects.") {
        SliderRow(
            "Line spacing",
            "%.1f×".format(java.util.Locale.ROOT, spacing),
            spacing,
            1f..2f,
            onChangeFinished = { AppSettings.update { it.copy(lyricsLineSpacing = spacing) } },
        ) { spacing = (it * 10).roundToInt() / 10f }
        RowDivider(PlainInset)
        LinkRow("Lyrics font and size", icon = "lyrics", tint = SystemColors.Pink) { SettingsNav.push(SettingsPage.LyricsEffects) }
    }
}

private val SourceRowHeight = 52.dp

/**
 * The phone's lyrics provider priority: every source in the order it's tried, dragged up or down
 * to reorder, each one switched on or off, and the PaxSenix key two of them need.
 */
@Composable
fun LyricsSourcesSettings() {
    val settings by AppSettings.state.collectAsState()
    val secrets by Secrets.values.collectAsState()
    val names = remember { LyricsService.providers(emptyList()).associate { it.id to it.name } }
    val order = remember { mutableStateListOf<String>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    // Follows Settings, except while a row is held, so a drag isn't undone under the pointer.
    LaunchedEffect(settings.lyricsProviders, dragging) {
        if (dragging == null) {
            val ids = LyricsService.providers(settings.lyricsProviders).map { it.id }
            if (order.toList() != ids) {
                order.clear()
                order.addAll(ids)
            }
        }
    }
    val rowPx = with(LocalDensity.current) { SourceRowHeight.toPx() }
    fun drop() {
        if (dragging != null && order.toList() != LyricsService.providers(AppSettings.value.lyricsProviders).map { it.id }) {
            AppSettings.update { it.copy(lyricsProviders = order.toList()) }
            LyricsService.forget()
        }
        dragging = null
        offset = 0f
    }
    SettingsSection(
        "Lyrics provider priority",
        footer = "Drag to reorder which provider is tried first. All the sources that are on are asked at once; " +
            "word timing beats line timing, and between two that are alike the higher one wins.",
    ) {
        Column(
            Modifier.pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { at ->
                        // The switch keeps its own clicks; anywhere else on a row picks it up.
                        dragging = if (at.x < size.width - 80.dp.toPx()) order.getOrNull((at.y / rowPx).toInt()) else null
                        offset = 0f
                    },
                    onDragEnd = { drop() },
                    onDragCancel = { drop() },
                ) { change, amount ->
                    val id = dragging ?: return@detectDragGestures
                    change.consume()
                    offset += amount.y
                    val from = order.indexOf(id)
                    if (offset > rowPx / 2 && from < order.lastIndex) {
                        order.add(from + 1, order.removeAt(from))
                        offset -= rowPx
                    } else if (offset < -rowPx / 2 && from > 0) {
                        order.add(from - 1, order.removeAt(from))
                        offset += rowPx
                    }
                }
            },
        ) {
            order.forEachIndexed { index, id ->
                key(id) {
                    val held = dragging == id
                    val on = id !in settings.lyricsProvidersOff
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(SourceRowHeight)
                            .zIndex(if (held) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (held) offset else 0f
                                shadowElevation = if (held) 16.dp.toPx() else 0f
                            }
                            .background(if (held) ConvxColors.Card else Color.Transparent),
                    ) {
                        SettingsRow(
                            names[id] ?: id,
                            leading = { ConvxIcon("drag_handle", tint = ConvxColors.TertiaryLabel, size = 20.dp) },
                            titleColor = if (on) ConvxColors.Label else ConvxColors.SecondaryLabel,
                            onClick = { toggleSource(id) },
                        ) {
                            GlassSwitch(on, { toggleSource(id) })
                        }
                        if (index < order.lastIndex && !held) {
                            Box(Modifier.align(Alignment.BottomStart)) { RowDivider(48.dp) }
                        }
                    }
                }
            }
        }
    }
    SectionGap()
    val hasKey = !secrets[LyricsService.PaxSenixKey].isNullOrBlank()
    SettingsSection("PaxSenix", footer = "Used by PaxSenix Spotify and Musixmatch sources. It stays on this PC, encrypted for your Windows account.") {
        LinkRow("PaxSenix API key", icon = "lock", tint = SystemColors.Gray, value = if (hasKey) "Set" else "Not set") { editPaxSenixKey() }
        if (hasKey) {
            RowDivider()
            ButtonRow("Remove key", color = SystemColors.Red) {
                LyricsService.setPaxSenixKey(null)
                Overlays.toast("PaxSenix key removed")
            }
        }
    }
    SectionGap()
    val hasSpicyKey = !secrets[com.convx.desktop.lyrics.SpicyLyrics.KeyName].isNullOrBlank()
    val spicyProblem by com.convx.desktop.lyrics.SpicyLyrics.problem.collectAsState()
    SettingsSection(
        "Spicy Lyrics",
        footer = spicyProblem ?: ("Word-synced lyrics from Spicy Lyrics' community, Apple Music and Spotify. The key is free: " +
            "make an application at developers.spicylyrics.org, turn on client access with \"No origin header\", and paste its " +
            "sl_pk_ key here. Songs are found by their Spotify ID, through your Spotify link or song.link. As its terms ask, " +
            "its lyrics always show who they're from and aren't saved as .lrc files."),
    ) {
        LinkRow("Spicy Lyrics key", icon = "lock", tint = SystemColors.Gray, value = if (hasSpicyKey) "Set" else "Not set") { editSpicyLyricsKey() }
        RowDivider()
        LinkRow("Get a key at developers.spicylyrics.org", icon = "link", tint = SystemColors.Gray) {
            openUrl("https://developers.spicylyrics.org/dashboard")
        }
        if (hasSpicyKey) {
            RowDivider()
            ButtonRow("Remove key", color = SystemColors.Red) {
                LyricsService.setSpicyLyricsKey(null)
                Overlays.toast("Spicy Lyrics key removed")
            }
        }
    }
    SectionGap()
    SettingsSection {
        ButtonRow("Reset to the phone's order") {
            AppSettings.update { it.copy(lyricsProviders = emptyList(), lyricsProvidersOff = emptyList()) }
            LyricsService.forget()
        }
    }
}

private fun toggleSource(id: String) {
    AppSettings.update { s ->
        s.copy(lyricsProvidersOff = if (id in s.lyricsProvidersOff) s.lyricsProvidersOff - id else s.lyricsProvidersOff + id)
    }
    LyricsService.forget()
}

private fun editPaxSenixKey() {
    Overlays.showDialog {
        var key by remember { mutableStateOf("") }
        DialogCard("PaxSenix API key") {
            DialogField("API key", key, secret = true) { key = it }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Cancel", { Overlays.dismissDialog() })
                PillButton("Save", {
                    if (key.isNotBlank()) {
                        LyricsService.setPaxSenixKey(key)
                        Overlays.dismissDialog()
                        Overlays.toast("PaxSenix key saved")
                    }
                }, filled = key.isNotBlank())
            }
        }
    }
}

private fun editSpicyLyricsKey() {
    Overlays.showDialog {
        var key by remember { mutableStateOf("") }
        val trimmed = key.trim()
        val valid = trimmed.startsWith("sl_pk_") || trimmed.startsWith("sl_sk_")
        DialogCard("Spicy Lyrics key") {
            DialogField("sl_pk_...", key, secret = true) { key = it }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Cancel", { Overlays.dismissDialog() })
                PillButton("Save", {
                    if (valid) {
                        LyricsService.setSpicyLyricsKey(trimmed)
                        Overlays.dismissDialog()
                        Overlays.toast("Spicy Lyrics key saved. It's asked from the next song.")
                    } else if (trimmed.isNotEmpty()) {
                        Overlays.toast("That isn't a Spicy Lyrics key. It starts with sl_pk_.", "error")
                    }
                }, filled = valid)
            }
        }
    }
}

/** The phone's Romanization page: lyrics in Latin letters under (or instead of) the original, by language. */
@Composable
fun RomanizationSettings() {
    val settings by AppSettings.state.collectAsState()
    fun language(name: String) {
        AppSettings.update { s ->
            s.copy(lyricsRomanizeOff = if (name in s.lyricsRomanizeOff) s.lyricsRomanizeOff - name else s.lyricsRomanizeOff + name)
        }
    }
    val cyrillic = Romanizer.languages.drop(5)
    SettingsSection("Romanization") {
        SwitchRow("Show romanized lyrics as main", checked = settings.lyricsRomanizeAsMain, icon = "lyrics", tint = SystemColors.Teal) { v ->
            AppSettings.update { it.copy(lyricsRomanizeAsMain = v) }
        }
        Romanizer.languages.take(5).forEach { name ->
            RowDivider()
            SwitchRow("Romanize $name lyrics", checked = name !in settings.lyricsRomanizeOff, icon = "translate", tint = SystemColors.Teal) { language(name) }
        }
    }
    SectionGap()
    SettingsSection("Cyrillic") {
        cyrillic.forEachIndexed { i, name ->
            if (i > 0) RowDivider()
            SwitchRow("Romanize $name lyrics", checked = name !in settings.lyricsRomanizeOff, icon = "alphabet_cyrillic", tint = SystemColors.Teal) { language(name) }
        }
        RowDivider()
        SwitchRow(
            "EXPERIMENTAL: Detect language line by line",
            "The Cyrillic language will be detected line by line instead of the entire song.",
            settings.lyricsRomanizeCyrillicByLine,
            icon = "alphabet_cyrillic",
            tint = SystemColors.Orange,
        ) { v -> AppSettings.update { it.copy(lyricsRomanizeCyrillicByLine = v) } }
    }
}
