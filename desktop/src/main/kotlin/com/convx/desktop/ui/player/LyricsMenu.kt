/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Song
import com.convx.desktop.lyrics.Lyrics
import com.convx.desktop.lyrics.LyricsService
import com.convx.desktop.lyrics.LyricsService.SourceResult
import com.convx.desktop.lyrics.LyricsState
import com.convx.desktop.lyrics.LyricsTranslator
import com.convx.desktop.lyrics.LyricsTweaks
import com.convx.desktop.lyrics.SongLyricsTweaks
import com.convx.desktop.lyrics.parseLyricsText
import com.convx.desktop.lyrics.toLyricsText
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.DialogCard
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.MenuDivider
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.glass.GlassSlider
import com.convx.desktop.ui.glass.GlassSwitch
import com.convx.desktop.ui.screens.settings.DialogField
import com.convx.desktop.ui.screens.settings.SettingsNav
import com.convx.desktop.ui.screens.settings.SettingsPage
import com.convx.desktop.ui.screens.settings.openUrl
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxShapes
import com.convx.desktop.ui.theme.ConvxType
import kotlinx.coroutines.launch
import java.net.URLEncoder
import kotlin.math.roundToInt
import com.convx.desktop.ui.components.ScrollbarColumn

/** The button that opens the phone's lyrics menu, next to the lyrics wherever they're shown. */
@Composable
fun LyricsMenuButton(song: Song?, size: Dp = 40.dp, iconSize: Dp = 22.dp, filled: Boolean = true) {
    val menu = rememberMenuAnchor()
    Box {
        IconButton("more_horiz", { if (song != null) menu.expanded = true }, size = size, iconSize = iconSize, filled = filled, enabled = song != null, tooltip = "Lyrics options")
        if (song != null) {
            AppMenu(menu) { LyricsMenuContent(song) { menu.expanded = false } }
        }
    }
}

/**
 * The phone's lyrics menu: Edit, Refetch and Search; the source the lyrics came from, which
 * opens the list of sources; karaoke vocal reduction; AI translation; the lyrics offset; and
 * whether this song is romanized.
 */
@Composable
fun LyricsMenuContent(song: Song, dismiss: () -> Unit) {
    val settings by AppSettings.state.collectAsState()
    val tweaks = LyricsTweaks.state.collectAsState().value.songs[song.id] ?: SongLyricsTweaks()
    val state by remember(song.id) { LyricsService.state(song) }.collectAsState()
    val lyrics = (state as? LyricsState.Ready)?.lyrics
    val partial by LyricsTranslator.partial.collectAsState()
    val navigator = LocalNavigator.current
    var choosingSource by remember(song.id) { mutableStateOf(false) }

    Column(Modifier.width(320.dp).animateContentSize()) {
        if (choosingSource) {
            SourceList(song, lyrics) { choosingSource = false }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionTile("Edit", "edit", Modifier.weight(1f)) {
                dismiss()
                editLyrics(song, lyrics)
            }
            ActionTile("Refetch", "sync", Modifier.weight(1f)) {
                dismiss()
                LyricsService.refetch(song)
            }
            ActionTile("Search", "search", Modifier.weight(1f)) {
                dismiss()
                searchLyrics(song)
            }
        }
        // The source in a capsule on the right, as the phone's provider selector sits.
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.CenterEnd) {
            Row(
                Modifier
                    .clip(ConvxShapes.Capsule)
                    .background(Color.White.copy(alpha = 0.1f))
                    .pressable({ choosingSource = true })
                    .heightIn(min = 38.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ConvxIcon("lyrics", tint = Color.White, size = 16.dp)
                Text(lyrics?.source ?: "Choose lyrics provider", style = ConvxType.SubheadStrong, color = Color.White, maxLines = 1)
                ConvxIcon("chevron_right_px", tint = ConvxColors.SecondaryLabel, size = 12.dp)
            }
        }
        if (lyrics != null) {
            MenuRow("Wrong lyrics", "close", subtitle = "These are another song's. Hide them; Search finds the right ones", onClick = {
                dismiss()
                LyricsService.markWrong(song)
                Overlays.toast("Lyrics hidden for this song. Search or Refetch brings lyrics back.", "lyrics")
            })
        }
        // The phone saves the reduction when the slider is let go, and it stays on for every song.
        var reduction by remember { mutableFloatStateOf(settings.vocalReduction) }
        LaunchedEffect(settings.vocalReduction) { reduction = settings.vocalReduction }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Vocal reduction", style = ConvxType.Body, color = Color.White, modifier = Modifier.weight(1f))
                Text("${(reduction * 100).roundToInt()}%", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
            }
            Spacer(Modifier.height(6.dp))
            GlassSlider(reduction, { reduction = it }, onValueChangeFinished = {
                AppSettings.update { it.copy(vocalReduction = reduction) }
            })
        }
        MenuDivider()
        val translating = partial?.songId == song.id
        val translated = translating || (lyrics != null && LyricsTranslator.saved(song.id, lyrics, settings) != null)
        if (LyricsTranslator.hasKey(settings)) {
            SwitchMenuRow("AI lyrics translation", "translate", translated) { on ->
                when {
                    !on -> LyricsTranslator.clear(song.id)
                    lyrics != null -> LyricsTranslator.translate(song, lyrics)
                    else -> Overlays.toast("No lyrics to translate", "error")
                }
            }
        } else {
            // The phone hides the switch until there's a key; here it says where the key goes.
            MenuRow("AI lyrics translation", "translate", subtitle = "Add an API key in Settings", onClick = {
                dismiss()
                UiState.fullPlayer = false
                navigator.navigate(Screen.Settings)
                SettingsNav.push(SettingsPage.AiTranslation)
            })
        }
        MenuRow("Lyrics offset", "fast_forward", value = offsetLabel(tweaks.offsetMs), onClick = {
            dismiss()
            editOffset(song)
        })
        SwitchMenuRow("Romanize current track", "language_korean_latin", tweaks.romanize) { on ->
            LyricsTweaks.update(song.id) { it.copy(romanize = on) }
        }
        Spacer(Modifier.height(4.dp))
    }
}

internal fun offsetLabel(ms: Int) = "${if (ms >= 0) "+" else ""}${ms}ms"

/** One of the three actions across the top, an icon over its name as the phone's action grid has. */
@Composable
private fun ActionTile(text: String, icon: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier
            .clip(ConvxShapes.Card)
            .background(Color.White.copy(alpha = if (hovered) 0.14f else 0.08f))
            .hoverable(interaction)
            .pressable(onClick, interaction = interaction)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ConvxIcon(icon, tint = Color.White, size = 24.dp)
        Text(text, style = ConvxType.CaptionStrong, color = Color.White)
    }
}

@Composable
private fun MenuRow(
    title: String,
    icon: String,
    subtitle: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (hovered && enabled) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .hoverable(interaction)
            .pressable(onClick, interaction = interaction, enabled = enabled)
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConvxIcon(icon, tint = Color.White, size = 18.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = ConvxType.Body, color = Color.White)
            if (subtitle != null) Text(subtitle, style = ConvxType.Caption, color = ConvxColors.SecondaryLabel)
        }
        if (value != null) Text(value, style = ConvxType.Body, color = ConvxColors.SecondaryLabel)
        trailing?.invoke()
    }
}

@Composable
private fun SwitchMenuRow(title: String, icon: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    MenuRow(title, icon, onClick = { onChange(!checked) }) {
        Spacer(Modifier.width(10.dp))
        GlassSwitch(checked, onChange)
    }
}

/** The phone's "Choose lyrics provider": every source, what it found for this song, and a click to switch. */
@Composable
private fun SourceList(song: Song, current: Lyrics?, back: () -> Unit) {
    val settings by AppSettings.state.collectAsState()
    val results by remember(song.id) { LyricsService.results(song) }.collectAsState()
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton("arrow_back_ios", back, size = 34.dp, iconSize = 16.dp, tooltip = "Back")
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text("Choose lyrics provider", style = ConvxType.Headline, color = Color.White)
            Text(song.title, style = ConvxType.Caption, color = ConvxColors.SecondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (failed) {
        Text(
            "Could not switch providers. Your current lyrics are still selected.",
            style = ConvxType.Subhead,
            color = Color(0xFFFF453A),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
    for (provider in LyricsService.providers(settings.lyricsProviders)) {
        val result = results[provider.id]
        val selected = current?.source == provider.name
        val off = provider.id in settings.lyricsProvidersOff
        val asking = result == SourceResult.Asking
        val found = (result as? SourceResult.Found)?.lyrics
        val (icon, status) = when {
            selected -> "check" to "Current lyrics"
            asking -> null to "Loading lyrics…"
            found != null -> "check" to "Available — click to switch"
            result == SourceResult.NotFound -> "close" to "No lyrics found"
            result == SourceResult.Failed -> "search" to "Lookup failed — click to retry"
            off -> "close" to "Disabled in lyrics settings"
            else -> "search" to "Click to search this provider"
        }
        val enabled = !selected && !asking && !off && result != SourceResult.NotFound
        Row(
            Modifier
                .fillMaxWidth()
                .pressable({
                    failed = false
                    if (found != null) {
                        LyricsService.use(song, found)
                        back()
                    } else {
                        scope.launch {
                            val lyrics = LyricsService.ask(song, provider.id)
                            if (lyrics != null) {
                                LyricsService.use(song, lyrics)
                                back()
                            } else {
                                failed = true
                            }
                        }
                    }
                }, enabled = enabled)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .alpha(if (enabled || selected || asking) 1f else 0.5f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                if (icon == null) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    ConvxIcon(icon, tint = Color.White, size = 18.dp)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(provider.name, style = ConvxType.Body.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal), color = Color.White)
                Text(status, style = ConvxType.Caption, color = ConvxColors.SecondaryLabel)
                val level = (found ?: current?.takeIf { selected })?.syncLevel
                if (level != null) {
                    Text(listOf("Plain lyrics", "Line synced", "Word by word")[level], style = ConvxType.CaptionStrong, color = ConvxColors.SecondaryLabel)
                }
            }
        }
    }
}

/** The phone's Edit: the lyrics as text, LRC stamps and all, saved for this song. */
private fun editLyrics(song: Song, lyrics: Lyrics?) {
    Overlays.showDialog {
        var text by remember { mutableStateOf(lyrics?.lines?.toLyricsText().orEmpty()) }
        DialogCard(song.title) {
            DialogField("Lyrics", text, lines = 14) { text = it }
            Spacer(Modifier.height(8.dp))
            Text(
                "Lines starting with a [mm:ss.xx] time are synced; anything else shows as plain text.",
                style = ConvxType.Caption,
                color = ConvxColors.SecondaryLabel,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Cancel", { Overlays.dismissDialog() })
                PillButton("Save", {
                    val lines = parseLyricsText(text)
                    if (lines.any { it.text.isNotBlank() }) {
                        LyricsService.use(song, Lyrics(lines, lyrics?.source ?: "You", lyrics?.credit))
                        Overlays.dismissDialog()
                    }
                }, filled = true)
            }
        }
    }
}

/** The phone's Search lyrics: a title and artist to look up, then every source's answer to pick from. */
internal fun searchLyrics(song: Song, searchNow: Boolean = false) {
    Overlays.showDialog {
        var title by remember { mutableStateOf(song.title) }
        var artist by remember { mutableStateOf(song.artists.joinToString { it.name }) }
        var searching by remember { mutableStateOf(searchNow) }
        if (searching) {
            SearchResults(song, title, artist)
            return@showDialog
        }
        DialogCard("Search lyrics") {
            DialogField("Song title", title) { title = it }
            Spacer(Modifier.height(12.dp))
            DialogField("Song artists", artist) { artist = it }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Cancel", { Overlays.dismissDialog() })
                PillButton("Search online", {
                    Overlays.dismissDialog()
                    openUrl("https://www.google.com/search?q=" + URLEncoder.encode("$artist $title lyrics", Charsets.UTF_8))
                })
                PillButton("Search", { if (title.isNotBlank()) searching = true }, filled = true)
            }
        }
    }
}

@Composable
private fun SearchResults(song: Song, title: String, artist: String) {
    val results = remember { mutableStateListOf<Lyrics>() }
    var loading by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(-1) }
    LaunchedEffect(Unit) {
        LyricsService.search(song, title, artist).collect { results += it }
        loading = false
    }
    DialogCard("Search lyrics") {
        ScrollbarColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
            results.forEachIndexed { index, found ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(ConvxShapes.Card)
                        .pressable({
                            LyricsService.use(song, found)
                            Overlays.dismissDialog()
                            Overlays.toast("Lyrics from ${found.source}")
                        })
                        .padding(10.dp)
                        .animateContentSize(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            found.lines.filter { it.text.isNotBlank() }.joinToString("\n") { it.text },
                            style = ConvxType.Subhead,
                            color = Color.White,
                            maxLines = if (index == expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.clip(ConvxShapes.Control).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(found.source, style = ConvxType.CaptionStrong, color = Color.White, maxLines = 1)
                            if (found.synced) {
                                Spacer(Modifier.width(4.dp))
                                ConvxIcon("sync", tint = Color.White, size = 12.dp)
                            }
                        }
                    }
                    IconButton(
                        if (index == expanded) "expand_less" else "expand_more",
                        { expanded = if (expanded == index) -1 else index },
                        size = 32.dp,
                        iconSize = 20.dp,
                    )
                }
            }
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                }
            } else if (results.isEmpty()) {
                Text("Lyrics not found", style = ConvxType.Body, color = ConvxColors.SecondaryLabel, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PillButton("Close", { Overlays.dismissDialog() })
        }
    }
}

/** The phone's Lyrics Offset sheet: type it, nudge it 50 ms at a time, or slide it in 100s. */
internal fun editOffset(song: Song) {
    Overlays.showDialog {
        val tweaks = LyricsTweaks.state.collectAsState().value.songs[song.id] ?: SongLyricsTweaks()
        val offset = tweaks.offsetMs
        fun set(ms: Int) = LyricsTweaks.update(song.id) { it.copy(offsetMs = ms) }
        var text by remember { mutableStateOf(offset.toString()) }
        LaunchedEffect(offset) { if (text.toIntOrNull() != offset && text != "-") text = offset.toString() }
        DialogCard("Lyrics offset") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) {
                    DialogField("Milliseconds", text) { typed ->
                        // As on the phone: digits and a leading minus, up to ±9999.
                        val clean = typed.filterIndexed { i, c -> c.isDigit() || (c == '-' && i == 0) }.take(if (typed.startsWith('-')) 5 else 4)
                        text = clean
                        val parsed = clean.toIntOrNull()
                        when {
                            parsed != null -> set(parsed.coerceIn(-9999, 9999))
                            clean.isEmpty() -> set(0)
                        }
                    }
                }
                if (offset != 0) {
                    Spacer(Modifier.width(8.dp))
                    IconButton("restore", { set(0) }, size = 40.dp, iconSize = 20.dp, tooltip = "Reset")
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton("remove", { set((offset - 50).coerceIn(-3000, 3000)) }, size = 36.dp, iconSize = 20.dp, tooltip = "50 ms later")
                Spacer(Modifier.width(8.dp))
                GlassSlider(
                    offset.coerceIn(-3000, 3000).toFloat(),
                    { set(((it / 100f).roundToInt() * 100)) },
                    modifier = Modifier.weight(1f),
                    valueRange = -3000f..3000f,
                )
                Spacer(Modifier.width(8.dp))
                IconButton("add", { set((offset + 50).coerceIn(-3000, 3000)) }, size = 36.dp, iconSize = 20.dp, tooltip = "50 ms sooner")
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 44.dp)) {
                Text("-3000ms", style = ConvxType.Caption, color = ConvxColors.SecondaryLabel, modifier = Modifier.weight(1f))
                Text("+3000ms", style = ConvxType.Caption, color = ConvxColors.SecondaryLabel)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Positive shows each line sooner. Saved for this song.",
                style = ConvxType.Caption,
                color = ConvxColors.SecondaryLabel,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton("Done", { Overlays.dismissDialog() }, filled = true)
            }
        }
    }
}
