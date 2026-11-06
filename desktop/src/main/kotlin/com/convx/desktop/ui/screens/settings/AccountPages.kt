/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.core.formatDuration
import com.convx.desktop.data.AppSettings
import com.convx.desktop.integrations.DiscordPresence
import com.convx.desktop.integrations.spotify.SpotifyAccount
import com.convx.desktop.integrations.spotify.SpotifyApi
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.screens.SpotifyGreen
import com.convx.desktop.ui.shapes.ContinuousRoundedRectangle
import com.convx.desktop.ui.shell.LibraryTab
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType

private val DiscordBlurple = Color(0xFF5865F2)

/**
 * The account card that heads an account's page: who you are there, how it's going, and the
 * one button that connects or disconnects.
 */
@Composable
internal fun AccountHeader(
    avatar: @Composable () -> Unit,
    title: String,
    status: String,
    statusIsError: Boolean = false,
    action: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(ContinuousRoundedRectangle(26.dp)).background(ConvxColors.Card).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        avatar()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = ConvxType.Title3, color = ConvxColors.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(status, style = ConvxType.Subhead, color = if (statusIsError) SystemColors.Red else ConvxColors.SecondaryLabel)
        }
        action()
    }
}

@Composable
fun SpotifySettings() {
    val state by SpotifyAccount.state.collectAsState()
    val navigator = LocalNavigator.current
    val profile = (state as? SpotifyAccount.State.SignedIn)?.profile
    AccountHeader(
        avatar = { AccountAvatar(profile?.image, "spotify", Color.Black, 64.dp) },
        title = profile?.name ?: "Spotify",
        status = when (val s = state) {
            is SpotifyAccount.State.SignedIn -> when {
                s.profile == null && s.problem != null -> "Linked, but Spotify won't answer Convx: ${s.problem}"
                s.profile?.premium == true -> "Connected · Premium"
                else -> "Connected"
            }
            SpotifyAccount.State.SigningIn -> "Finish signing in to Spotify in your browser, then come back here."
            is SpotifyAccount.State.Failed -> s.message
            SpotifyAccount.State.SignedOut -> "Not connected. You sign in on Spotify's own page in your browser; Convx never sees your password."
        },
        statusIsError = state is SpotifyAccount.State.Failed || (state as? SpotifyAccount.State.SignedIn)?.problem != null,
    ) {
        when (state) {
            is SpotifyAccount.State.SignedIn -> PillButton("Disconnect", { SpotifyAccount.signOut() })
            SpotifyAccount.State.SigningIn -> PillButton("Cancel", { SpotifyAccount.cancelSignIn() })
            else -> PillButton("Connect", {
                SpotifyAccount.signIn { uri ->
                    runCatching { java.awt.Desktop.getDesktop().browse(uri) }
                        .onFailure { Overlays.toast("Couldn't open your browser", "error") }
                }
            }, filled = true, accent = SpotifyGreen)
        }
    }

    if (state is SpotifyAccount.State.SignedIn) {
        var liked by remember { mutableStateOf<Int?>(null) }
        var playlists by remember { mutableStateOf<Int?>(null) }
        var problem by remember { mutableStateOf<String?>(null) }
        var reloads by remember { mutableStateOf(0) }
        LaunchedEffect(reloads) {
            val lists = SpotifyApi.playlists(refresh = reloads > 0)
            val count = SpotifyApi.likedCount()
            playlists = lists.getOrNull()?.size
            liked = count.getOrNull()
            // Shown rather than an endless "…", so it's clear what Spotify said.
            problem = (lists.exceptionOrNull() ?: count.exceptionOrNull())?.let { it.message ?: "Spotify didn't answer" }
        }
        val waiting by SpotifyApi.waiting.collectAsState()
        val loading = problem == null && (liked == null || playlists == null)
        SectionGap()
        SettingsSection(
            "In Your Library",
            footer = problem?.let { "Couldn't read your Spotify library: $it" }
                ?: waiting?.takeIf { loading }
                ?: "Your Spotify lists sit under Playlists in the side bar next to your Convx ones, marked with Spotify's logo.",
        ) {
            LinkRow("Liked Songs", icon = "favorite", tint = Color(0xFF6A3DE8), value = liked?.toString() ?: if (problem != null) "–" else "…") {
                navigator.navigate(Screen.SpotifyLiked)
            }
            RowDivider()
            LinkRow("Playlists", icon = "queue_music", tint = SystemColors.Green, value = playlists?.toString() ?: if (problem != null) "–" else "…") {
                UiState.libraryTab = LibraryTab.Playlists
                navigator.switchTo(Screen.Library)
            }
            RowDivider()
            ButtonRow("Reload from Spotify", color = SpotifyGreen, enabled = !loading) {
                SpotifyApi.clearCache()
                liked = null
                playlists = null
                problem = null
                reloads++
            }
        }
        SectionGap()
        SpotifyImportSection()
    }
    SectionGap()
    ExportifyImportSection()
    SectionGap()
    SpotifyOwnAppSection()
    SectionGap()
    InfoNote(
        "Spotify doesn't let other apps play its audio, so Convx finds each Spotify song on YouTube Music by its title, " +
            "artist and length, as the phone's Spotify import does, and remembers the match. A song with no close match is skipped.",
    )
}

/**
 * Linking through a Spotify app of your own. Spotify's desktop client, which Convx uses by
 * default, is shared by every tool that borrows it, and Spotify can stop answering it when they
 * all ask too much. An app of your own has its own allowance.
 */
@Composable
private fun SpotifyOwnAppSection() {
    val settings by AppSettings.state.collectAsState()
    val own = SpotifyAccount.usesOwnApp
    SettingsSection(
        "Your own Spotify app",
        footer = "If Spotify won't answer Convx, link through an app of your own. Open developer.spotify.com/dashboard, sign in, " +
            "click Create app, give it any name, and under Redirect URIs add ${SpotifyAccount.OwnAppRedirect} exactly. Tick Web API, " +
            "save, then copy the Client ID from the app's Settings here and press Connect again. Spotify only lets an app's owner " +
            "use it if they have Premium, and it reads the songs only of playlists you made or collaborate on.",
    ) {
        SettingsRow(
            "Client ID",
            if (own) settings.spotifyClientId.trim() else "Spotify's shared desktop client",
            icon = "spotify",
            tint = SpotifyGreen,
            onClick = {
                Overlays.prompt("Spotify Client ID", settings.spotifyClientId, confirm = "Use it") { text ->
                    val id = text.trim().lowercase().filter { it.isLetterOrDigit() }
                    if (id.length == 32 && id.all { it in '0'..'9' || it in 'a'..'f' }) {
                        AppSettings.update { it.copy(spotifyClientId = id) }
                        SpotifyAccount.signOut()
                        Overlays.toast("Saved. Press Connect to link through your app.", "spotify")
                    } else {
                        Overlays.toast("That isn't a Client ID. It's 32 letters and numbers on your app's Settings page.", "error")
                    }
                }
            },
        ) {
            if (own) PillButton("Use the shared one", {
                AppSettings.update { it.copy(spotifyClientId = "") }
                SpotifyAccount.signOut()
            })
        }
        RowDivider()
        LinkRow("Open the Spotify Developer Dashboard", icon = "link", tint = SystemColors.Gray) {
            openUrl("https://developer.spotify.com/dashboard")
        }
    }
}

@Composable
fun DiscordSettings() {
    val settings by AppSettings.state.collectAsState()
    val status by DiscordPresence.status.collectAsState()
    val user by DiscordPresence.user.collectAsState()
    val on = settings.discordPresence
    val connected = on && status == DiscordPresence.Status.Connected

    AccountHeader(
        avatar = { AccountAvatar(user?.avatar?.takeIf { connected }, "discord", DiscordBlurple, 64.dp) },
        title = if (connected) user?.name ?: "Discord" else "Discord",
        status = when {
            !on -> "Not connected. Convx talks to the Discord app on this PC, so there's nothing to sign in to."
            status == DiscordPresence.Status.Connected -> "Connected. Your profile shows what's playing while music plays."
            status == DiscordPresence.Status.NotRunning -> "Waiting for Discord. Open the Discord app on this PC and Convx connects by itself."
            else -> "Connecting to Discord…"
        },
    ) {
        if (on) {
            PillButton("Disconnect", { AppSettings.update { it.copy(discordPresence = false) } })
        } else {
            PillButton("Connect", {
                AppSettings.update { it.copy(discordPresence = true) }
                DiscordPresence.reconnectNow()
            }, filled = true, accent = DiscordBlurple)
        }
    }

    SectionGap()
    SettingsSection("Options") {
        SwitchRow("Enable Rich Presence", checked = on) { v ->
            AppSettings.update { it.copy(discordPresence = v) }
            if (v) DiscordPresence.reconnectNow()
        }
        RowDivider(PlainInset)
        SwitchRow(
            "Use details instead of state",
            "Show the song title after \"Listening to\" in the member list, instead of the artists.",
            settings.discordUseDetails,
            enabled = on,
        ) { v -> AppSettings.update { it.copy(discordUseDetails = v) } }
        RowDivider(PlainInset)
        SwitchRow(
            "Advanced mode",
            "Choose the activity type and the buttons under your status.",
            settings.discordAdvanced,
            enabled = on,
        ) { v -> AppSettings.update { it.copy(discordAdvanced = v) } }
    }

    AnimatedVisibility(
        visible = on && settings.discordAdvanced,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column {
            SectionGap()
            SettingsSection("Presence") {
                ChoiceRow(
                    "Activity type",
                    "What Discord says you're doing.",
                    listOf("listening" to "Listening", "playing" to "Playing", "watching" to "Watching", "competing" to "Competing"),
                    settings.discordActivityType,
                ) { v -> AppSettings.update { it.copy(discordActivityType = v) } }
            }
            SectionGap()
            SettingsSection("Buttons") {
                ButtonTextRow(
                    "Button 1",
                    settings.discordButton1Text,
                    DiscordPresence.Look.DefaultButton1,
                    settings.discordButton1,
                    onText = { t -> AppSettings.update { it.copy(discordButton1Text = t) } },
                    onShown = { v -> AppSettings.update { it.copy(discordButton1 = v) } },
                )
                RowDivider(PlainInset)
                ButtonTextRow(
                    "Button 2",
                    settings.discordButton2Text,
                    DiscordPresence.Look.DefaultButton2,
                    settings.discordButton2,
                    onText = { t -> AppSettings.update { it.copy(discordButton2Text = t) } },
                    onShown = { v -> AppSettings.update { it.copy(discordButton2 = v) } },
                )
            }
            Spacer(Modifier.height(10.dp))
            InfoNote("Button text can use {song_name}, {artist_name} and {album_name}. Discord shows up to 32 characters.")
            Spacer(Modifier.height(10.dp))
            InfoNote(
                "The phone can also set your online status and rename the activity, because it signs in to Discord with your account. " +
                    "On a PC Convx goes through the Discord app instead, which keeps your login out of Convx but doesn't allow those two.",
            )
        }
    }

    SectionGap()
    SettingsSection(
        "Name on Discord",
        footer = "Discord shows the name of the Discord application a status belongs to. The phone's is registered as \"Metrolist\": " +
            "the phone renames it through your Discord login, but the Discord app on a PC doesn't allow that. To read \"Convx\", make an " +
            "application of your own: open discord.com/developers/applications, click New Application, name it Convx, " +
            "optionally give it the Convx icon, then copy its Application ID here.",
    ) {
        val own = DiscordPresence.usesOwnApplication(settings)
        SettingsRow(
            "Discord application ID",
            if (own) settings.discordApplicationId.trim() else "The phone's (shows as Metrolist)",
            icon = "discord",
            tint = DiscordBlurple,
            onClick = {
                Overlays.prompt("Discord application ID", settings.discordApplicationId, confirm = "Use it") { text ->
                    val id = text.filter { it.isDigit() }
                    if (id.length in 17..20) {
                        AppSettings.update { it.copy(discordApplicationId = id) }
                        Overlays.toast("Discord status now uses your application")
                    } else {
                        Overlays.toast("That isn't an application ID. It's a long number on the app's General Information page.", "error")
                    }
                }
            },
        ) {
            if (own) PillButton("Use the phone's", { AppSettings.update { it.copy(discordApplicationId = "") } })
        }
        RowDivider()
        LinkRow("Open the Discord Developer Portal", icon = "link", tint = SystemColors.Gray) {
            openUrl("https://discord.com/developers/applications")
        }
    }

    SectionGap()
    Text(
        "Preview",
        style = ConvxType.SubheadStrong,
        color = ConvxColors.SecondaryLabel,
        modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
    )
    RichPresencePreview(DiscordPresence.Look.of(settings))
}

/** A button's row: its text (click to change it) and whether it shows. */
@Composable
private fun ButtonTextRow(
    title: String,
    text: String,
    default: String,
    shown: Boolean,
    onText: (String) -> Unit,
    onShown: (Boolean) -> Unit,
) {
    SettingsRow(
        title,
        text.ifBlank { default },
        onClick = { Overlays.prompt(title, initial = text.ifBlank { default }) { onText(if (it.trim() == default) "" else it.trim()) } },
    ) {
        com.convx.desktop.ui.glass.GlassSwitch(shown, onShown)
    }
}

/**
 * What your Discord profile shows, drawn the way Discord draws it, with the song that's
 * playing now: the phone's RichPresence preview.
 */
@Composable
private fun RichPresencePreview(look: DiscordPresence.Look) {
    val player by PlayerController.state.collectAsState()
    val position by PlayerController.position.collectAsState()
    val song = player.current
    // What Discord's member list says: the artists or the song where Discord can show them, else the application's name.
    val settings by AppSettings.state.collectAsState()
    val name = if (DiscordPresence.usesOwnApplication(settings)) "Convx" else "Metrolist"
    val verb = when (look.type) {
        "playing" -> "Playing $name"
        "watching" -> "Watching $name"
        "competing" -> "Competing in $name"
        else -> "Listening to $name"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(18.dp))
            .background(Color(0xFF232428))
            .padding(16.dp),
    ) {
        Text(verb, style = ConvxType.SubheadStrong.copy(fontWeight = FontWeight.ExtraBold), color = Color(0xFFDBDEE1))
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Top) {
            if (song != null) {
                Artwork(song.thumbnail, Modifier.size(96.dp), size = 256, shape = RoundedCornerShape(6.dp))
            } else {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(6.dp)).border(2.dp, Color(0xFF4E5058), RoundedCornerShape(6.dp)))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                val title = song?.title ?: "Song title"
                val artists = song?.artistText?.ifBlank { null } ?: "Artist"
                Text(
                    title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFFF2F3F5),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(artists, fontSize = 14.sp, color = Color(0xFFB5BAC1), maxLines = 1, overflow = TextOverflow.Ellipsis)
                song?.album?.name?.let {
                    Text(it, fontSize = 14.sp, color = Color(0xFFB5BAC1), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (song != null && look.typeCode == 2) {
                    val duration = player.durationMs.takeIf { it > 0 } ?: ((song.duration ?: 0) * 1000L)
                    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF4E5058))) {
                        Box(Modifier.fillMaxWidth(progress).height(4.dp).background(Color(0xFFF2F3F5)))
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text(formatDuration(position), fontSize = 12.sp, color = Color(0xFFB5BAC1), modifier = Modifier.weight(1f))
                        Text(formatDuration(duration), fontSize = 12.sp, color = Color(0xFFB5BAC1))
                    }
                }
            }
        }
        listOfNotNull(look.button1, look.button2).forEach { label ->
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF4E5058)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    song?.let { DiscordPresence.resolveVariables(label, it) } ?: label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFF2F3F5),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (song == null) {
            Spacer(Modifier.height(12.dp))
            Text("Play a song to see it here.", style = ConvxType.Subhead, color = Color(0xFFB5BAC1))
        }
    }
}
