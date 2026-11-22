/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.AudioQuality
import com.convx.desktop.player.AudioEngine
import kotlin.math.roundToInt

/** The phone's Playback & Audio: sound, then the queue, then where it plays. */
@Composable
fun PlaybackHub() {
    val settings by AppSettings.state.collectAsState()
    SettingsSection("Sound") {
        LinkRow("Equalizer", "Shape the sound", icon = "equalizer", tint = SystemColors.Green, value = onOff(settings.equalizer.enabled)) {
            SettingsNav.push(SettingsPage.Equalizer)
        }
        RowDivider()
        LinkRow("Quality & sound", "Audio quality, normalization and skip silence", icon = "tune", tint = SystemColors.Blue) {
            SettingsNav.push(SettingsPage.QualitySound)
        }
        RowDivider()
        LinkRow(
            "Song transitions",
            if (settings.autoMix) "AutoMix between songs" else "Crossfade between songs",
            icon = "ic_queue_crossfade",
            tint = SystemColors.Purple,
            value = if (!settings.crossfade) onOff(false) else if (settings.autoMix) "AutoMix" else "Crossfade",
        ) {
            SettingsNav.push(SettingsPage.Transitions)
        }
    }
    SectionGap()
    SettingsSection("Queue & behaviour") {
        LinkRow("Queue & autoplay", "Persistent queue, similar songs, shuffle memory", icon = "queue_music", tint = SystemColors.Orange) {
            SettingsNav.push(SettingsPage.Queue)
        }
        RowDivider()
        LinkRow("Sound output", "Which speakers or headphones Convx plays on", icon = "speaker_apple", tint = SystemColors.Indigo, value = settings.outputDevice ?: "Default") {
            SettingsNav.push(SettingsPage.Output)
        }
    }
    SectionGap()
    val navigator = com.convx.desktop.ui.shell.LocalNavigator.current
    SettingsSection("Advanced") {
        LinkRow("AI DJ analysis", "Analyze songs and build a mix", icon = "equalizer", tint = SystemColors.Purple) {
            navigator.navigate(com.convx.desktop.ui.shell.Screen.AiDj())
        }
    }
    SectionGap()
    InfoNote(
        "The phone's offload, bit-perfect output and JioSaavn source aren't on PC. " +
            "Offload and bit-perfect are Android audio features, and JioSaavn only streams in India.",
    )
}

private fun onOff(on: Boolean) = if (on) "On" else "Off"

@Composable
fun QualitySoundSettings() {
    val settings by AppSettings.state.collectAsState()
    SettingsSection {
        ChoiceRow(
            "Audio quality",
            "High picks the best stream YouTube offers. Low saves data.",
            AudioQuality.entries.map { it to it.name.lowercase().replaceFirstChar(Char::titlecase) },
            settings.audioQuality,
            icon = "tune",
            tint = SystemColors.Blue,
        ) { q -> AppSettings.update { it.copy(audioQuality = q) } }
        RowDivider()
        SwitchRow(
            "Audio normalization",
            "Evens out loud and quiet songs with YouTube's loudness for each one. Starts with the next song.",
            settings.normalizeAudio,
            icon = "volume_up",
            tint = SystemColors.Red,
        ) { v -> AppSettings.update { it.copy(normalizeAudio = v) } }
        RowDivider()
        SwitchRow(
            "Skip silence",
            "Fast forward through silent parts of songs",
            settings.skipSilence,
            icon = "fast_forward",
            tint = SystemColors.Gray,
        ) { v -> AppSettings.update { it.copy(skipSilence = v) } }
    }
}

/** The phone's Song transitions: AutoMix, planned from each song's measurements, or a plain crossfade. */
@Composable
fun SongTransitionsSettings() {
    val settings by AppSettings.state.collectAsState()
    SettingsSection(footer = "Beginnings and endings of songs blend together. Albums and some genres still play without transitions.") {
        SwitchRow("Song transitions", checked = settings.crossfade, icon = "ic_queue_crossfade", tint = SystemColors.Purple) { v ->
            AppSettings.update { it.copy(crossfade = v) }
        }
    }
    SectionGap()
    SettingsSection("Transition style") {
        CheckRow(
            "AutoMix",
            "Songs transition at the perfect moment, based on analysis of the tempo and beats of the music.",
            checked = settings.crossfade && settings.autoMix,
            icon = "equalizer",
            tint = SystemColors.Pink,
            enabled = settings.crossfade,
        ) { AppSettings.update { it.copy(autoMix = true) } }
        RowDivider()
        CheckRow(
            "Crossfade",
            "Simple song transitions from one to the next for a set duration.",
            checked = settings.crossfade && !settings.autoMix,
            icon = "ic_queue_crossfade",
            tint = SystemColors.Purple,
            enabled = settings.crossfade,
        ) { AppSettings.update { it.copy(autoMix = false) } }
    }
    if (settings.crossfade && settings.autoMix) {
        SectionGap()
        SettingsSection {
            SwitchRow(
                "Creative transitions",
                "Adds reverb, tempo-synced delay and stereo shaping while tracks blend, with occasional echo tails and tape stops",
                settings.creativeTransitions,
                icon = "equalizer",
                tint = SystemColors.Pink,
            ) { v -> AppSettings.update { it.copy(creativeTransitions = v) } }
        }
        SectionGap()
        val progress by com.convx.desktop.automix.LibraryAnalysis.progress.collectAsState()
        val measured by com.convx.desktop.automix.TrackAnalyzer.measured.collectAsState()
        val stored = androidx.compose.runtime.remember(measured) { com.convx.desktop.automix.TrackAnalyzer.storedCount() }
        SettingsSection(
            footer = "AutoMix measures each song once, from the copy on this PC, and remembers it. " +
                "A song streamed for the first time is measured while it plays; until then its transition is a plain crossfade. " +
                "$stored songs measured so far.",
        ) {
            LinkRow(
                "Analyze local and downloaded songs for AutoMix",
                if (progress.running) "Analyzing ${progress.done} of ${progress.total}… Click again to stop."
                else "Stores beats, key and energy once, so AutoMix uses no CPU to analyze those songs while playing.",
                icon = "equalizer",
                tint = SystemColors.Pink,
            ) { com.convx.desktop.automix.LibraryAnalysis.toggle() }
        }
        if (settings.creativeTransitions) {
            SectionGap()
            SettingsSection(footer = "Automatic varies the sound between transitions. Choose a type to keep its character.") {
                ChoiceRow(
                    "Reverb",
                    options = com.convx.desktop.automix.ReverbType.entries.map { it to it.label },
                    selected = settings.djReverb,
                    icon = "equalizer",
                    tint = SystemColors.Pink,
                ) { v -> AppSettings.update { it.copy(djReverb = v) } }
                RowDivider()
                ChoiceRow(
                    "Delay",
                    options = com.convx.desktop.automix.DelayType.entries.map { it to it.label },
                    selected = settings.djDelay,
                    icon = "equalizer",
                    tint = SystemColors.Pink,
                ) { v -> AppSettings.update { it.copy(djDelay = v) } }
            }
        }
    }
    if (settings.crossfade && !settings.autoMix) {
        SectionGap()
        SettingsSection("Crossfade duration") {
            val seconds = settings.crossfadeSeconds.coerceIn(1f, 12f).roundToInt()
            SliderRow(
                if (seconds == 1) "1 second" else "$seconds seconds",
                "1s – 12s",
                seconds.toFloat(),
                1f..12f,
            ) { v -> AppSettings.update { it.copy(crossfadeSeconds = v.roundToInt().toFloat()) } }
        }
    }
}

@Composable
fun QueueSettings() {
    val settings by AppSettings.state.collectAsState()
    SettingsSection {
        SwitchRow("Persistent queue", "Restore your last queue when the app starts", settings.persistentQueue) { v ->
            AppSettings.update { it.copy(persistentQueue = v) }
        }
        RowDivider(PlainInset)
        SwitchRow(
            "Ask before replacing the queue",
            "When something is already playing, choose whether to keep or clear the queue",
            settings.askBeforeReplacingQueue,
        ) { v -> AppSettings.update { it.copy(askBeforeReplacingQueue = v) } }
        RowDivider(PlainInset)
        SwitchRow("Enable similar content", "Automatically add more similar songs when the end of the queue is reached", settings.autoRadio) { v ->
            AppSettings.update { it.copy(autoRadio = v) }
        }
        RowDivider(PlainInset)
        SwitchRow("Remember shuffle and repeat", "Remember shuffle and repeat mode when restarting the app", settings.rememberShuffleRepeat) { v ->
            AppSettings.update { it.copy(rememberShuffleRepeat = v) }
        }
        RowDivider(PlainInset)
        SwitchRow("Auto skip to next song when error occurs", "Ensure your continuous playback experience", settings.skipOnError) { v ->
            AppSettings.update { it.copy(skipOnError = v) }
        }
    }
    SectionGap()
    val modulesOn = settings.enabledModules.isNotEmpty() && settings.moduleSources.isNotEmpty()
    SettingsSection(
        "Downloads",
        footer = "Downloads play with no connection. They're in Library under Downloaded, and Privacy & Storage shows how much room they take." +
            if (modulesOn) " Songs come from your modules first, as they do when they play, so a module that has a song in lossless gives a FLAC file." else "",
    ) {
        SwitchRow("Auto download on like", "Automatically download songs when you like them", settings.autoDownloadOnLike) { v ->
            AppSettings.update { it.copy(autoDownloadOnLike = v) }
        }
        RowDivider(PlainInset)
        SwitchRow(
            "Save downloads to Music folder",
            "Also keep each download in Music\\Convx as Artist - Title, tagged with its title, artist, album, lyrics and cover, playable in any app",
            settings.saveDownloadsToMusic,
        ) { v ->
            AppSettings.update { it.copy(saveDownloadsToMusic = v) }
        }
        if (settings.saveDownloadsToMusic) {
            val retag by com.convx.desktop.player.Downloads.retag.collectAsState()
            val downloaded by com.convx.desktop.player.Downloads.done.collectAsState()
            RowDivider(PlainInset)
            SettingsRow(
                "Tag the songs already there",
                when (val r = retag) {
                    null -> "Write the tags and cover into songs saved before Convx did"
                    else -> if (!r.finished) {
                        "Tagging ${r.done} of ${r.total}…"
                    } else {
                        "Tagged ${r.total - r.failed} songs" + if (r.failed > 0) ", ${r.failed} couldn't be" else ""
                    }
                },
                enabled = downloaded.isNotEmpty() && retag?.finished != false,
                onClick = { com.convx.desktop.player.Downloads.retagMusicFolder() },
            ) { com.convx.desktop.ui.components.ConvxIcon("edit", tint = SystemColors.Blue, size = 18.dp) }
        }
        val lossless by com.convx.desktop.player.Downloads.lossless.collectAsState()
        val downloaded by com.convx.desktop.player.Downloads.done.collectAsState()
        val fromYouTube = remember(downloaded) { com.convx.desktop.player.Downloads.fromYouTube(downloaded.values).size }
        if (modulesOn && (fromYouTube > 0 || lossless != null)) {
            RowDivider(PlainInset)
            SettingsRow(
                "Download again from your modules",
                when (val l = lossless) {
                    null -> "Swap the ${songCount(fromYouTube)} downloaded from YouTube for your modules' copies, in lossless when they have it. Songs no module has stay as they are"
                    else -> if (!l.finished) {
                        "Checking ${l.done} of ${l.total}… ${l.swapped} swapped so far"
                    } else {
                        "Swapped ${songCount(l.swapped)}" + if (l.total > l.swapped) ", ${l.total - l.swapped} aren't on your modules" else ""
                    }
                },
                enabled = fromYouTube > 0 && lossless?.finished != false,
                onClick = { com.convx.desktop.player.Downloads.downloadAgainFromModules() },
            ) { com.convx.desktop.ui.components.ConvxIcon("download", tint = SystemColors.Blue, size = 18.dp) }
        }
    }
}

private fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"

/** The PC's answer to the phone's Bluetooth & output: pick the speakers or headphones. */
@Composable
fun OutputSettings() {
    val settings by AppSettings.state.collectAsState()
    var devices by remember { mutableStateOf(AudioEngine.outputDevices()) }
    val chosen = settings.outputDevice
    SettingsSection(
        "Play on",
        footer = "Plugged in something new? Refresh the list. The phone's resume-on-Bluetooth and Google Cast are phone-only.",
    ) {
        CheckRow("Windows default", "Follows the output chosen in Windows' sound settings", chosen == null, icon = "speaker_apple", tint = SystemColors.Indigo) {
            AppSettings.update { it.copy(outputDevice = null) }
        }
        devices.forEach { name ->
            RowDivider()
            CheckRow(name, checked = chosen == name, icon = "volume_up", tint = SystemColors.Gray) {
                AppSettings.update { it.copy(outputDevice = name) }
            }
        }
        if (chosen != null && chosen !in devices) {
            RowDivider()
            CheckRow(chosen, "Not connected; Convx is using the Windows default until it's back", true, icon = "volume_off", tint = SystemColors.Gray) {}
        }
    }
    SectionGap()
    SettingsSection {
        ButtonRow("Refresh") { devices = AudioEngine.outputDevices() }
    }
}
