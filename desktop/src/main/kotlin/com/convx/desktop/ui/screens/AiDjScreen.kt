/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.convx.desktop.ai.AiChat
import com.convx.desktop.ai.AiDjSet
import com.convx.desktop.ai.AiDjSetBuilder
import com.convx.desktop.core.AppScope
import com.convx.desktop.core.pluralize
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.Song
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.Chip
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.Gutter
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.LocalPlayback
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.components.MosaicArt
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.glass.GlassSwitch
import com.convx.desktop.ui.screens.settings.SettingsNav
import com.convx.desktop.ui.screens.settings.SettingsPage
import com.convx.desktop.ui.screens.settings.SystemColors
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxShapes
import com.convx.desktop.ui.theme.ConvxType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MinSongs = 4
private const val MaxSongs = 30
private const val DefaultSongs = 12
private const val MinMinutes = 15
private const val MaxMinutes = 240
private const val DefaultMinutes = 60
private const val MinutesStep = 15

/**
 * The AI DJ Mix being built or shown, kept while you look elsewhere as the phone's view model
 * keeps it, for the song it started from.
 */
object AiDj {
    data class State(
        val running: Boolean = false,
        val logs: List<String> = emptyList(),
        val result: AiDjSet? = null,
        val failed: Boolean = false,
        val savedId: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The song the state above belongs to; opening the page for another starts afresh. */
    private var seedId: String? = null
    private var job: Job? = null

    fun open(seed: Song?) {
        if (seed?.id != seedId) {
            reset()
            seedId = seed?.id
        }
    }

    fun generate(prompt: String?, seed: Song?, songCount: Int, targetSeconds: Long?, concert: Boolean) {
        if (_state.value.running) return
        _state.value = State(running = true)
        job = AppScope.launch {
            val result = AiDjSetBuilder.build(prompt, seed, songCount, targetSeconds, concert) { line ->
                _state.update { it.copy(logs = it.logs + line) }
            }
            _state.update {
                if (result == null) it.copy(running = false, failed = true, logs = it.logs + "Couldn't build a set. Try again.")
                else it.copy(running = false, result = result)
            }
        }
    }

    fun reset() {
        job?.cancel()
        _state.value = State()
    }

    fun save() {
        val result = _state.value.result ?: return
        val id = Library.createPlaylist(result.name, result.songs)
        _state.update { it.copy(savedId = id) }
        Overlays.toast("Saved to Your Library", "playlist_add")
    }
}

/**
 * The phone's AI DJ Mix: describe a vibe, start from a song, or both, and it builds a set to play
 * or keep. Concert mode asks for a show's arc instead. Sets can be counted in songs or minutes.
 */
@Composable
fun AiDjScreen(seed: Song?, startInConcert: Boolean) {
    remember(seed?.id) { AiDj.open(seed) }
    val state by AiDj.state.collectAsState()
    val result = state.result
    if (result != null) {
        AiDjResult(result, state.savedId)
        return
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PageList(tint = Color.Black) {
            item(key = "title") {
                Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("AI DJ Mix", style = ConvxType.LargeTitle, modifier = Modifier.weight(1f))
                }
            }
            item(key = "body") {
                Column(Modifier.padding(horizontal = Gutter).widthIn(max = 680.dp).padding(top = 12.dp)) {
                    if (state.running) Progress(state.logs) else AiDjForm(seed, startInConcert, state)
                }
            }
        }
    }
}

@Composable
private fun AiDjForm(seed: Song?, startInConcert: Boolean, state: AiDj.State) {
    val navigator = LocalNavigator.current
    val settings by AppSettings.state.collectAsState()
    var prompt by rememberSaveable { mutableStateOf("") }
    var songCount by rememberSaveable { mutableIntStateOf(DefaultSongs) }
    var byDuration by rememberSaveable { mutableStateOf(false) }
    var minutes by rememberSaveable { mutableIntStateOf(DefaultMinutes) }
    var concert by rememberSaveable { mutableStateOf(startInConcert) }
    val aiProblem = remember(settings) { AiChat.problem(settings) }

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (state.failed) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.logs.lastOrNull() ?: "Couldn't build a set. Try again.", style = ConvxType.Body, color = SystemColors.Red)
                // The last line is the outcome; the reason is usually a line before it.
                if (state.logs.size > 1) {
                    Text(state.logs.dropLast(1).joinToString("\n"), style = ConvxType.Footnote, color = ConvxColors.SecondaryLabel)
                }
            }
        }
        if (seed != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Artwork(seed.thumbnail, Modifier.size(44.dp), size = 120, shape = RoundedCornerShape(6.dp))
                Column(Modifier.weight(1f)) {
                    Text("Based on", style = ConvxType.Footnote, color = ConvxColors.SecondaryLabel)
                    Text(seed.title, style = ConvxType.Headline, maxLines = 1)
                }
                ConvxIcon("sparks", tint = Color.White, size = 22.dp)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Vibe", style = ConvxType.SubheadStrong)
            VibeField(
                prompt,
                { prompt = it },
                when {
                    concert -> "e.g. an artist, band, or genre for the set"
                    seed != null -> "Optional — leave blank to build from related songs only"
                    else -> "e.g. upbeat 30-minute workout mix"
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Concert mode", style = ConvxType.Body)
                Text(
                    "Builds a set with an opener, rising energy, peak, and closer — like an actual live show",
                    style = ConvxType.Footnote,
                    color = ConvxColors.SecondaryLabel,
                )
            }
            Spacer(Modifier.width(16.dp))
            GlassSwitch(concert, { concert = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("By song count", !byDuration, { byDuration = false })
            Chip("By duration", byDuration, { byDuration = true })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (byDuration) "Target length" else "Songs", style = ConvxType.Body, modifier = Modifier.weight(1f))
            IconButton("remove", {
                if (byDuration) minutes = (minutes - MinutesStep).coerceAtLeast(MinMinutes)
                else songCount = (songCount - 1).coerceAtLeast(MinSongs)
            }, filled = true)
            Text(
                if (byDuration) "$minutes min" else "$songCount",
                style = ConvxType.Headline,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(72.dp),
            )
            IconButton("add", {
                if (byDuration) minutes = (minutes + MinutesStep).coerceAtMost(MaxMinutes)
                else songCount = (songCount + 1).coerceAtMost(MaxSongs)
            }, filled = true)
        }
        val canGenerate = prompt.isNotBlank() || seed != null
        if (!canGenerate) {
            Text("Describe a vibe or start from a song to continue.", style = ConvxType.Footnote, color = ConvxColors.SecondaryLabel)
        }
        // A song alone needs no AI; a vibe or a concert does, so say where its key goes.
        if (aiProblem != null && (prompt.isNotBlank() || concert)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(aiProblem, style = ConvxType.Footnote, color = ConvxColors.SecondaryLabel, modifier = Modifier.weight(1f))
                PillButton("Settings", {
                    navigator.navigate(Screen.Settings)
                    SettingsNav.push(SettingsPage.AiTranslation)
                })
            }
        }
        PillButton(
            "Generate",
            {
                if (canGenerate) {
                    AiDj.generate(
                        prompt = prompt.takeIf { it.isNotBlank() },
                        seed = seed,
                        songCount = songCount,
                        targetSeconds = if (byDuration) minutes * 60L else null,
                        concert = concert,
                    )
                }
            },
            icon = "sparks",
            filled = true,
            modifier = Modifier.alpha(if (canGenerate) 1f else 0.4f),
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun VibeField(value: String, onChange: (String) -> Unit, placeholder: String) {
    DisposableEffect(Unit) { onDispose { UiState.typingFocusChanged("ai-dj", false) } }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(ConvxShapes.Control)
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, style = ConvxType.Body, color = ConvxColors.TertiaryLabel)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = ConvxType.Body.copy(color = Color.White),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier.fillMaxWidth().onFocusChanged { UiState.typingFocusChanged("ai-dj", it.isFocused) },
        )
    }
}

@Composable
private fun Progress(logs: List<String>) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f), strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
        Text(logs.lastOrNull() ?: "Starting…", style = ConvxType.Body, color = ConvxColors.SecondaryLabel, textAlign = TextAlign.Center)
        if (logs.size > 1) {
            Text(
                logs.dropLast(1).asReversed().joinToString("\n"),
                style = ConvxType.Footnote,
                color = ConvxColors.TertiaryLabel,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
            )
        }
        PillButton("Cancel", AiDj::reset)
    }
}

@Composable
private fun AiDjResult(result: AiDjSet, savedId: String?) {
    val navigator = LocalNavigator.current
    val playback = LocalPlayback.current
    val songs = result.songs
    val thumbs = remember(songs) { songs.mapNotNull { it.thumbnail }.distinct().take(4) }
    val playing = playback.isPlaying && songs.any { it.id == playback.currentId }
    val description = buildString {
        append("How this was built: ${result.method}")
        if (result.unmatched.isNotEmpty()) {
            append("\n${pluralize(result.unmatched.size, "song")} couldn't be found: ${result.unmatched.joinToString(", ")}")
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PageList(tint = Color.Black, topPadding = 0.dp) {
            collectionHeader(
                title = result.name,
                art = { size -> MosaicArt(thumbs, size, RoundedCornerShape(12.dp)) },
                metadata = "AI DJ Mix · ${pluralize(songs.size, "song")}",
                playing = playing,
                enabled = songs.isNotEmpty(),
                onPlay = { if (playing) PlayerController.pause() else PlayerController.playSongs(songs, 0, result.name, shuffle = false) },
                onShuffle = { PlayerController.playSongs(songs, 0, result.name, shuffle = true) },
                description = description,
                menu = { dismiss ->
                    MenuItem("Add to queue", "queue_music") {
                        PlayerController.addToQueue(songs)
                        Overlays.toast("Added to queue")
                        dismiss()
                    }
                    MenuItem("Start over", "refresh") {
                        AiDj.reset()
                        dismiss()
                    }
                },
            )
            item(key = "actions") {
                Row(Modifier.padding(horizontal = Gutter).padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Start over", AiDj::reset, icon = "refresh")
                    if (savedId != null) {
                        PillButton("Open playlist", { navigator.navigate(Screen.LocalPlaylist(savedId)) }, icon = "playlist_play", filled = true)
                    } else {
                        PillButton("Save as playlist", AiDj::save, icon = "playlist_add", filled = true)
                    }
                }
            }
            songTable(songs, result.name, "ai-dj")
        }
    }
}
