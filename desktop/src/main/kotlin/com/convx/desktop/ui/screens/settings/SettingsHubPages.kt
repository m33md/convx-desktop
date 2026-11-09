/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.AppInfo
import com.convx.desktop.core.applyLocale
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.AudioQuality
import com.convx.desktop.integrations.DiscordPresence
import com.convx.desktop.integrations.spotify.SpotifyAccount
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.screens.PageCache
import com.convx.desktop.ui.shapes.ContinuousRoundedRectangle
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType

private val Countries = listOf(
    "" to "Automatic", "US" to "United States", "GB" to "United Kingdom", "CA" to "Canada", "AU" to "Australia",
    "IN" to "India", "SA" to "Saudi Arabia", "AE" to "United Arab Emirates", "EG" to "Egypt", "MA" to "Morocco",
    "TR" to "Türkiye", "DE" to "Germany", "FR" to "France", "ES" to "Spain", "IT" to "Italy", "NL" to "Netherlands",
    "SE" to "Sweden", "BR" to "Brazil", "MX" to "Mexico", "AR" to "Argentina", "JP" to "Japan", "KR" to "South Korea",
    "ID" to "Indonesia", "PH" to "Philippines", "NG" to "Nigeria", "ZA" to "South Africa", "PK" to "Pakistan",
)

private val Languages = listOf(
    "" to "Automatic", "en" to "English", "ar" to "العربية", "es" to "Español", "fr" to "Français", "de" to "Deutsch",
    "it" to "Italiano", "pt" to "Português", "tr" to "Türkçe", "hi" to "हिन्दी", "ur" to "اردو", "id" to "Bahasa Indonesia",
    "ja" to "日本語", "ko" to "한국어", "ru" to "Русский", "nl" to "Nederlands",
)

@Composable
fun PlayerHub() {
    val settings by AppSettings.state.collectAsState()
    SettingsSection("Player") {
        LinkRow("Player design", "Card, Vinyl, Clover, Apple Music, with live previews", icon = "play", tint = SystemColors.Pink, value = settings.playerDesign.label) {
            SettingsNav.push(SettingsPage.PlayerDesign)
        }
        RowDivider()
        LinkRow("Controls & layout", "Thumbnail, menus, buttons and badges", icon = "sliders", tint = SystemColors.Indigo) {
            SettingsNav.push(SettingsPage.ControlsLayout)
        }
        RowDivider()
        LinkRow("Custom player icons", "Click a control to use your own image", icon = "tune", tint = SystemColors.Purple) {
            SettingsNav.push(SettingsPage.CustomPlayerIcons)
        }
        RowDivider()
        val stickers = settings.diyLayout.stickers.size
        LinkRow("DIY", "Place stickers, images and emoji over the player", icon = "sparks", tint = SystemColors.Orange, value = if (stickers > 0) "$stickers" else null) {
            com.convx.desktop.ui.player.DiyEditor.open()
        }
    }
    SectionGap()
    SettingsSection("Lyrics", footer = "Lyrics come from the same sources as on the phone, and are looked up as the song starts.") {
        LinkRow("Lyrics style", "Animation, position, scrolling and swipe, with a live preview", icon = "lyrics", tint = SystemColors.Pink, value = settings.lyricsStyle.label) {
            SettingsNav.push(SettingsPage.LyricsStyle)
        }
        RowDivider()
        LinkRow("Lyrics effects", "Style, fonts, size, glow and blur", icon = "lyrics", tint = SystemColors.Pink) {
            SettingsNav.push(SettingsPage.LyricsEffects)
        }
        RowDivider()
        LinkRow("Text & layout", "Text size, spacing and word-by-word", icon = "lyrics", tint = SystemColors.Orange) {
            SettingsNav.push(SettingsPage.LyricsText)
        }
        RowDivider()
        LinkRow("Sources & priority", "Choose and order lyric providers", icon = "lyrics", tint = SystemColors.Blue) {
            SettingsNav.push(SettingsPage.LyricsSources)
        }
        RowDivider()
        LinkRow("Romanization", "Show lyrics in Latin letters", icon = "translate", tint = SystemColors.Teal) {
            SettingsNav.push(SettingsPage.Romanization)
        }
        RowDivider()
        LinkRow("AI lyrics translation", "Translate lyrics with your own API key", icon = "translate", tint = SystemColors.Indigo, value = settings.aiProvider) {
            SettingsNav.push(SettingsPage.AiTranslation)
        }
    }
}

@Composable
fun LibraryHub() {
    val settings by AppSettings.state.collectAsState()
    SettingsSection("Region & filters") {
        ChoiceRow("Content country", "Which country's charts and recommendations you see.", Countries, settings.country) { code ->
            AppSettings.update { it.copy(country = code) }
            applyLocale(AppSettings.value)
            PageCache.clear()
        }
        RowDivider(PlainInset)
        ChoiceRow("Content language", "The language YouTube Music answers in.", Languages, settings.language) { code ->
            AppSettings.update { it.copy(language = code) }
            applyLocale(AppSettings.value)
            PageCache.clear()
        }
        RowDivider(PlainInset)
        SwitchRow("Hide explicit content", checked = settings.hideExplicit) { v -> AppSettings.update { it.copy(hideExplicit = v) } }
        RowDivider(PlainInset)
        SwitchRow("Hide music videos", "Show only audio tracks in feeds and search.", settings.hideVideos) { v -> AppSettings.update { it.copy(hideVideos = v) } }
    }
    SectionGap()
    // The phone's Auto playlists switches and My Top's length, kept together here.
    SettingsSection("Auto playlists") {
        SwitchRow("Show \"Liked\" playlist", checked = settings.showLikedPlaylist, icon = "favorite", tint = SystemColors.Pink) { v ->
            AppSettings.update { it.copy(showLikedPlaylist = v) }
        }
        RowDivider()
        SwitchRow("Show \"Downloaded\" playlist", checked = settings.showDownloadedPlaylist, icon = "download", tint = SystemColors.Green) { v ->
            AppSettings.update { it.copy(showDownloadedPlaylist = v) }
        }
        RowDivider()
        SwitchRow("Show \"Top\" playlist", checked = settings.showTopPlaylist, icon = "trending_up", tint = SystemColors.Orange) { v ->
            AppSettings.update { it.copy(showTopPlaylist = v) }
        }
        RowDivider()
        SwitchRow("Show \"Cached\" playlist", checked = settings.showCachedPlaylist, icon = "cached", tint = SystemColors.Cyan) { v ->
            AppSettings.update { it.copy(showCachedPlaylist = v) }
        }
        RowDivider()
        SwitchRow(
            "Show \"Uploaded\" playlist",
            "Songs you uploaded to YouTube Music. Needs you signed in.",
            settings.showUploadedPlaylist,
            icon = "backup",
            tint = SystemColors.Purple,
        ) { v -> AppSettings.update { it.copy(showUploadedPlaylist = v) } }
        RowDivider(PlainInset)
        SliderRow("My Top list length", "${settings.topSize}", settings.topSize.toFloat(), 1f..100f) { v ->
            AppSettings.update { it.copy(topSize = v.roundToInt().coerceIn(1, 100)) }
        }
    }
    SectionGap()
    SettingsSection("Playlist covers") {
        SwitchRow(
            "CD covers",
            "Playlists without their own picture get a CD case drawn from their songs. Off shows a grid of their covers.",
            settings.discCovers,
            icon = "album",
            tint = SystemColors.Pink,
        ) { v -> AppSettings.update { it.copy(discCovers = v) } }
    }
    SectionGap()
    val localIndex by com.convx.desktop.player.LocalMusic.index.collectAsState()
    SettingsSection("Local files") {
        LinkRow(
            "Music folders",
            "Choose folders to scan, including network shares",
            icon = "storage",
            tint = SystemColors.Orange,
            value = localIndex.tracks.size.takeIf { it > 0 }?.let { "$it songs" },
        ) { SettingsNav.push(SettingsPage.LocalFolders) }
    }
    SectionGap()
    SettingsSection {
        LinkRow(
            "Modules",
            "Music sources and add-ons",
            icon = "link",
            tint = SystemColors.Blue,
            value = settings.enabledModules.size.takeIf { it > 0 }?.let { "$it on" },
        ) { SettingsNav.push(SettingsPage.Modules) }
    }
}

@Composable
fun AccountsHub() {
    val spotify by SpotifyAccount.state.collectAsState()
    val settings by AppSettings.state.collectAsState()
    val discord by DiscordPresence.status.collectAsState()
    val youtube by com.convx.desktop.integrations.youtube.YouTubeAccount.state.collectAsState()
    SettingsSection("Accounts") {
        LinkRow(
            "YouTube Music",
            (youtube as? com.convx.desktop.integrations.youtube.YouTubeAccount.State.SignedIn)?.profile?.name
                ?: "Your library, likes and recommendations",
            icon = "play",
            tint = YouTubeRed,
            value = when (youtube) {
                is com.convx.desktop.integrations.youtube.YouTubeAccount.State.SignedIn -> "Logged in"
                com.convx.desktop.integrations.youtube.YouTubeAccount.State.SignedOut -> "Off"
                is com.convx.desktop.integrations.youtube.YouTubeAccount.State.Failed -> "Not logged in"
                else -> "Logging in"
            },
        ) { SettingsNav.push(SettingsPage.YouTubeMusic) }
        RowDivider()
        LinkRow(
            "Spotify",
            "Your playlists and Liked Songs in the side bar",
            icon = "spotify",
            tint = SystemColors.Green,
            value = when (spotify) {
                is SpotifyAccount.State.SignedIn -> "Connected"
                SpotifyAccount.State.SigningIn -> "Signing in"
                is SpotifyAccount.State.Failed -> "Not connected"
                SpotifyAccount.State.SignedOut -> "Off"
            },
        ) { SettingsNav.push(SettingsPage.Spotify) }
        RowDivider()
        LinkRow(
            "Apple Music import",
            "Import playlists from a link or export",
            icon = "playlist_add",
            tint = SystemColors.Red,
        ) { appleMusicImportDialog() }
        RowDivider()
        LinkRow(
            "Discord",
            "Listening status on your profile",
            icon = "discord",
            tint = SystemColors.Indigo,
            value = when {
                !settings.discordPresence -> "Off"
                discord == DiscordPresence.Status.Connected -> "On"
                discord == DiscordPresence.Status.NotRunning -> "Waiting"
                else -> "On"
            },
        ) { SettingsNav.push(SettingsPage.Discord) }
        RowDivider()
        val lastFm by com.convx.desktop.integrations.LastFm.state.collectAsState()
        LinkRow(
            "Last.fm",
            "Scrobbling",
            icon = "music_note",
            tint = SystemColors.Red,
            value = when (lastFm) {
                is com.convx.desktop.integrations.LastFm.State.SignedIn -> if (settings.lastFmScrobble) "On" else "Paused"
                com.convx.desktop.integrations.LastFm.State.WaitingForBrowser -> "Signing in"
                else -> "Off"
            },
        ) { SettingsNav.push(SettingsPage.LastFm) }
        RowDivider()
        val brainz by com.convx.desktop.core.Secrets.values.collectAsState()
        LinkRow(
            "ListenBrainz",
            "Open scrobbling with a token",
            icon = "music_history",
            tint = SystemColors.Orange,
            value = if (brainz.containsKey("listenbrainz.token") && settings.listenBrainzScrobble) "On" else "Off",
        ) { SettingsNav.push(SettingsPage.ListenBrainz) }
    }
    SectionGap()
    SettingsSection("Services") {
        val navigator = com.convx.desktop.ui.shell.LocalNavigator.current
        val party by com.convx.desktop.integrations.party.Party.session.collectAsState()
        LinkRow(
            "Listen Together",
            "A party with friends on phones or PCs",
            icon = "group",
            tint = SystemColors.Cyan,
            value = party?.let { "In ${it.code}" },
        ) { navigator.navigate(com.convx.desktop.ui.shell.Screen.Party) }
        RowDivider()
        LinkRow("Network & proxy", "IP version and proxy", icon = "wifi_proxy", tint = SystemColors.Gray) {
            SettingsNav.push(SettingsPage.Network)
        }
    }
    SectionGap()
    InfoNote("On the phone ListenBrainz sits inside the YouTube Music account page; here it has its own row.")
}

@Composable
fun PrivacyHub() {
    val backup by com.convx.desktop.integrations.Backup.state.collectAsState()
    val settings by AppSettings.state.collectAsState()
    SettingsSection {
        LinkRow(
            "Privacy",
            "History and screenshots",
            icon = "security",
            tint = SystemColors.Blue,
            value = if (settings.pauseListenHistory || settings.pauseSearchHistory) "History paused" else null,
        ) { SettingsNav.push(SettingsPage.Privacy) }
        RowDivider()
        LinkRow("Storage", "Downloads and caches", icon = "storage", tint = SystemColors.Gray) { SettingsNav.push(SettingsPage.Storage) }
        RowDivider()
        LinkRow(
            "Backup & restore",
            "Save or restore your library",
            icon = "restore",
            tint = SystemColors.Green,
            value = if (backup is com.convx.desktop.integrations.Backup.State.Working) "Working" else null,
        ) { SettingsNav.push(SettingsPage.Backup) }
    }
}

@Composable
fun HelpHub() {
    Row(
        Modifier.fillMaxWidth().clip(ContinuousRoundedRectangle(26.dp)).background(ConvxColors.Card).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(ContinuousRoundedRectangle(15.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            ConvxIcon("convx_logo", tint = ConvxColors.AccentRed, size = 40.dp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                com.convx.desktop.ui.components.ConvxWordmark(22.dp)
                Spacer(Modifier.width(8.dp))
                Text("for Windows", style = ConvxType.Title3, color = ConvxColors.Label)
            }
            Spacer(Modifier.height(4.dp))
            Text("Version ${AppInfo.VERSION}", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
        }
    }
    SectionGap()
    val update by com.convx.desktop.core.Updater.state.collectAsState()
    SettingsSection {
        LinkRow(
            "App updates",
            "Check for and install a newer build",
            icon = "download",
            tint = SystemColors.Blue,
            value = when (update) {
                is com.convx.desktop.core.Updater.State.Available -> "Update available"
                com.convx.desktop.core.Updater.State.UpToDate -> "Up to date"
                else -> null
            },
            badge = if (update is com.convx.desktop.core.Updater.State.Available) 1 else 0,
        ) { SettingsNav.push(SettingsPage.Updates) }
    }
    SectionGap()
    SettingsSection(
        footer = "The Windows version of Convx, the music app by Aryan (Cosmic Taser), maintained by m33md and " +
            "built from the phone app's own YouTube Music and lyrics code. Free software under GPL-3.0, with no warranty.",
    ) {
        LinkRow("Convx on GitHub", "The phone app's project", icon = "link", tint = SystemColors.Gray) {
            openUrl("https://github.com/cosmictaserdev-creator/convx")
        }
        RowDivider()
        LinkRow("Source code", "Convx for Windows, under GPL-3.0", icon = "link", tint = SystemColors.Gray) {
            openUrl("https://github.com/m33md/convx-desktop")
        }
        RowDivider()
        LinkRow("Logs", "For when something goes wrong", icon = "bug_report", tint = SystemColors.Orange) { openFolder(AppDirs.logs) }
        RowDivider()
        LinkRow("Copy recent log", "Paste it into a message to show what went wrong", icon = "content_copy", tint = SystemColors.Blue) {
            copyText(com.convx.desktop.core.Log.recent())
            Overlays.toast("Recent log copied")
        }
        RowDivider()
        LinkRow("Crash report", "The last time the app closed unexpectedly", icon = "warning", tint = SystemColors.Red) {
            SettingsNav.push(SettingsPage.CrashReport)
        }
    }
}

/**
 * The phone's System update page: this build, a check for a newer one with what it adds, and
 * installing it, which closes Convx and opens it again when done.
 */
@Composable
fun UpdatesPage() {
    val state by com.convx.desktop.core.Updater.state.collectAsState()
    val settings by AppSettings.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (state is com.convx.desktop.core.Updater.State.Idle) com.convx.desktop.core.Updater.check()
    }
    SettingsSection(footer = "Convx ${AppInfo.VERSION}, built from ${AppInfo.COMMIT.take(7)}.") {
        when (val s = state) {
            is com.convx.desktop.core.Updater.State.Available -> {
                SettingsRow("Convx ${s.release.version} is available", "Installing closes Convx for a moment and opens it again.", icon = "download", tint = SystemColors.Blue)
                RowDivider()
                if (com.convx.desktop.core.Updater.canInstall) {
                    ButtonRow("Download and install") { scope.launch { com.convx.desktop.core.Updater.downloadAndInstall() } }
                } else {
                    ButtonRow("Download the installer") { openUrl(s.release.installerUrl) }
                }
            }
            is com.convx.desktop.core.Updater.State.Downloading ->
                SettingsRow("Downloading ${s.release.version}… ${(s.progress * 100).roundToInt()}%", "Convx closes and installs it when the download is done.", icon = "download", tint = SystemColors.Blue)
            com.convx.desktop.core.Updater.State.Checking -> SettingsRow("Checking for updates…", icon = "refresh", tint = SystemColors.Gray)
            com.convx.desktop.core.Updater.State.UpToDate -> {
                SettingsRow("Convx is up to date", icon = "check", tint = SystemColors.Green)
                RowDivider()
                ButtonRow("Check again") { scope.launch { com.convx.desktop.core.Updater.check() } }
            }
            is com.convx.desktop.core.Updater.State.Failed -> {
                SettingsRow("Couldn't update", s.message, icon = "error", tint = SystemColors.Red)
                RowDivider()
                ButtonRow("Try again") { scope.launch { com.convx.desktop.core.Updater.check() } }
            }
            com.convx.desktop.core.Updater.State.Idle -> ButtonRow("Check for updates") { scope.launch { com.convx.desktop.core.Updater.check() } }
        }
    }
    val changes = (state as? com.convx.desktop.core.Updater.State.Available)?.release?.changes
        ?: (state as? com.convx.desktop.core.Updater.State.Downloading)?.release?.changes
    if (!changes.isNullOrEmpty()) {
        SectionGap()
        SettingsSection("What's new") {
            changes.take(40).forEachIndexed { index, change ->
                if (index > 0) RowDivider(PlainInset)
                SettingsRow(change)
            }
        }
    }
    SectionGap()
    SettingsSection {
        SwitchRow("Automatically check for updates", "Looks for a newer build each time Convx opens", settings.checkForUpdates, icon = "refresh", tint = SystemColors.Gray) { v ->
            AppSettings.update { it.copy(checkForUpdates = v) }
        }
    }
}

/** The phone's Crash report page: the latest report, to copy into a message. */
@Composable
fun CrashReportPage() {
    val report = androidx.compose.runtime.remember {
        runCatching { com.convx.desktop.core.Log.crashFile.takeIf { it.isFile }?.readText() }.getOrNull()
    }
    if (report == null) {
        SettingsSection {
            SettingsRow("No crash report yet", "A report will be saved here if the app closes unexpectedly.", icon = "check", tint = SystemColors.Green)
        }
        return
    }
    SettingsSection {
        LinkRow("Copy report", "Paste it into a message to show what went wrong", icon = "content_copy", tint = SystemColors.Blue) {
            copyText(report)
            Overlays.toast("Crash report copied")
        }
        RowDivider()
        LinkRow("Open logs folder", null, icon = "bug_report", tint = SystemColors.Orange) { openFolder(AppDirs.logs) }
    }
    SectionGap()
    SettingsSection("Latest report") {
        Text(
            report.lines().take(60).joinToString("\n"),
            style = ConvxType.Footnote,
            color = ConvxColors.SecondaryLabel,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

internal fun copyText(text: String) {
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
    }.onFailure { Overlays.toast("Couldn't copy", "error") }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "…"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

internal fun openFolder(dir: java.io.File) {
    runCatching {
        dir.mkdirs()
        java.awt.Desktop.getDesktop().open(dir)
    }.onFailure { Overlays.toast("Couldn't open ${dir.absolutePath}", "error") }
}

internal fun openUrl(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
        .onFailure { Overlays.toast("Couldn't open your browser", "error") }
}

/** A round avatar, or a coloured disc with a glyph while there's no picture. */
@Composable
internal fun AccountAvatar(url: String?, icon: String, color: Color, size: androidx.compose.ui.unit.Dp) {
    if (url != null) {
        com.convx.desktop.ui.components.Artwork(url, Modifier.size(size), size = 256, shape = CircleShape, placeholderIcon = icon, iconSize = size * 0.45f)
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            ConvxIcon(icon, tint = Color.White, size = size * 0.55f)
        }
    }
}
