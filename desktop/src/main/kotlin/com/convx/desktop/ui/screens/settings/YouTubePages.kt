/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.integrations.youtube.BrowserSignIn
import com.convx.desktop.integrations.youtube.YouTubeAccount
import com.convx.desktop.integrations.youtube.YouTubeSync
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.DialogCard
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType

internal val YouTubeRed = Color(0xFFFF0033)

/** The phone's Account page: the YouTube Music login, its channels, the token and the sync switches. */
@Composable
fun YouTubeAccountSettings() {
    val state by YouTubeAccount.state.collectAsState()
    val settings by AppSettings.state.collectAsState()
    val sync by YouTubeSync.status.collectAsState()
    val signedIn = state as? YouTubeAccount.State.SignedIn
    val browsers = remember { BrowserSignIn.browsers() }
    val browser = remember(browsers, settings.signInBrowser) { BrowserSignIn.chosen(browsers) }
    LaunchedEffect(Unit) { YouTubeAccount.refresh() }

    AccountHeader(
        avatar = { AccountAvatar(signedIn?.profile?.thumbnail, "person", YouTubeRed, 64.dp) },
        title = signedIn?.profile?.name ?: "YouTube Music",
        status = when (val s = state) {
            YouTubeAccount.State.SignedOut ->
                if (browser == null) "Not logged in. Install a browser such as Chrome, Edge, Firefox or Opera to log in, or use a token below."
                else "Not logged in. You sign in on Google's own page in ${browser.name}; Convx never sees your password."
            YouTubeAccount.State.WaitingForBrowser ->
                "Log in to Google in the ${browser?.name ?: "browser"} window that opened. When YouTube Music shows your account, close that window (or press Done) and Convx finishes logging in."
            YouTubeAccount.State.Checking -> "Checking your account with YouTube Music…"
            is YouTubeAccount.State.ChoosingChannel -> "Choose which channel Convx uses."
            is YouTubeAccount.State.SignedIn -> listOfNotNull(s.profile.email, s.profile.handle).joinToString(" · ").ifEmpty { "Logged in" }
            is YouTubeAccount.State.Failed -> s.message
        },
        statusIsError = state is YouTubeAccount.State.Failed,
    ) {
        when (state) {
            is YouTubeAccount.State.SignedIn -> PillButton("Log out", { confirmLogout() })
            YouTubeAccount.State.WaitingForBrowser -> androidx.compose.foundation.layout.Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                PillButton("Cancel", { YouTubeAccount.cancelSignIn() })
                PillButton("Done", { com.convx.desktop.integrations.youtube.BrowserSignIn.finish() }, filled = true, accent = YouTubeRed)
            }
            is YouTubeAccount.State.ChoosingChannel -> PillButton("Cancel", { YouTubeAccount.cancelSignIn() })
            YouTubeAccount.State.Checking -> Unit
            else -> PillButton("Log in", { YouTubeAccount.signIn() }, accent = YouTubeRed)
        }
    }

    (state as? YouTubeAccount.State.ChoosingChannel)?.let { choosing ->
        SectionGap()
        SettingsSection(
            "Choose a channel",
            footer = "Your Google account has more than one YouTube channel. Pick the one for your library, likes and playlists; you can switch later.",
        ) {
            choosing.channels.forEachIndexed { index, channel ->
                if (index > 0) RowDivider(PlainInset + 48.dp)
                ChannelRow(channel, current = false) { YouTubeAccount.chooseChannel(channel) }
            }
        }
    }

    if (signedIn != null && signedIn.channels.size > 1) {
        SectionGap()
        SettingsSection("Accounts") {
            signedIn.channels.forEachIndexed { index, channel ->
                if (index > 0) RowDivider(PlainInset + 48.dp)
                // Only the part before "||" names the channel; the rest is the Google account.
                val current = channel.dataSyncId.substringBefore("||") == signedIn.dataSyncId?.substringBefore("||")
                ChannelRow(channel, current) { if (!current) YouTubeAccount.switchChannel(channel) }
            }
        }
    }

    SectionGap()
    SettingsSection("Player & Content") {
        SwitchRow(
            "More content",
            "Home, Explore and artist pages use your account's recommendations",
            settings.youtubeMoreContent,
            enabled = signedIn != null,
        ) { v -> YouTubeAccount.setMoreContent(v) }
        RowDivider(PlainInset)
        SwitchRow(
            "Auto sync with account",
            "Your YouTube Music likes, albums, artists and playlists come into Convx, and what you like or save here goes back",
            settings.youtubeSync,
            enabled = signedIn != null,
        ) { v ->
            AppSettings.update { it.copy(youtubeSync = v) }
            if (v) YouTubeSync.syncNow()
        }
        if (signedIn != null && settings.youtubeSync) {
            RowDivider(PlainInset)
            SettingsRow(
                "Sync now",
                when (val s = sync) {
                    YouTubeSync.Status.Idle -> "Syncs when Convx starts and every 30 minutes"
                    is YouTubeSync.Status.Running -> "Syncing ${s.step.lowercase()}…"
                    is YouTubeSync.Status.Done ->
                        "${s.liked} liked songs, ${s.albums} albums, ${s.artists} artists and ${s.playlists} playlists, synced at " +
                            java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(s.at)) +
                            if (s.skipped.isEmpty()) "" else ". Couldn't read your ${s.skipped.joinToString(" or ")} this time, so those stayed as they were"
                    is YouTubeSync.Status.Failed -> "Last sync failed: ${s.message}"
                },
                enabled = sync !is YouTubeSync.Status.Running,
                onClick = { YouTubeSync.syncNow() },
            ) { ConvxIcon("sync", tint = SystemColors.Blue, size = 18.dp) }
        }
    }

    SectionGap()
    SettingsSection("Logging in") {
        if (browsers.size > 1 && browser != null) {
            ChoiceRow(
                "Browser",
                "Where Google's login page opens",
                options = browsers.map { it.path to if (it.isDefault) "${it.name} (default)" else it.name },
                selected = browser.path,
            ) { path -> AppSettings.update { it.copy(signInBrowser = path) } }
            RowDivider(PlainInset)
        }
        SettingsRow(
            if (signedIn != null) "Show token" else "Login with token",
            if (signedIn != null) "Copy it to log in on another device"
            else "Paste the token from the phone (Settings, Account, Login with token), or a browser's cookie for music.youtube.com",
            onClick = { tokenDialog() },
        ) { ConvxIcon("chevron_right_px", tint = ConvxColors.TertiaryLabel, size = 14.dp) }
    }
    Spacer(Modifier.height(12.dp))
    InfoNote(
        "Logging in opens your browser with a new, empty profile that belongs to Convx, because Google doesn't allow " +
            "sign-ins inside apps' own browsers. Chrome, Edge, Brave, Opera, Opera GX, Vivaldi, Firefox and browsers built " +
            "on them work. Convx reads only music.youtube.com's cookies from that profile, then closes the window and " +
            "deletes it. Your login is kept on this PC, encrypted for your Windows account. Songs you liked in Convx before " +
            "logging in stay in Convx and aren't sent to YouTube Music.",
    )
}

@Composable
private fun ChannelRow(channel: YouTubeAccount.Channel, current: Boolean, onClick: () -> Unit) {
    SettingsRow(
        channel.name,
        channel.handle ?: if (current) "In use" else "Click to switch, no new login needed",
        selected = current,
        onClick = onClick,
        leading = { AccountAvatar(channel.thumbnail, "person", YouTubeRed, 34.dp) },
    ) {
        if (current) ConvxIcon("check", tint = SystemColors.Blue, size = 18.dp)
    }
}

/** The phone's logout question: keep the library you synced, or clear it. */
private fun confirmLogout() {
    Overlays.showDialog {
        DialogCard("Log out") {
            Text(
                "Do you wish to keep every library and other thing? Clear Data removes the likes, albums, artists and " +
                    "playlists that came from YouTube Music; what you saved only in Convx stays.",
                style = ConvxType.Body,
                color = ConvxColors.SecondaryLabel,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Cancel", { Overlays.dismissDialog() })
                PillButton("Clear Data", {
                    YouTubeAccount.signOut(clearData = true)
                    Overlays.dismissDialog()
                }, accent = SystemColors.Red)
                PillButton("Keep Data", {
                    YouTubeAccount.signOut(clearData = false)
                    Overlays.dismissDialog()
                }, filled = true)
            }
        }
    }
}

/** The phone's "Login with token" editor, in its exact format so tokens move between the two. */
private fun tokenDialog() {
    Overlays.showDialog {
        val clipboard = LocalClipboardManager.current
        var text by remember { mutableStateOf(if (YouTubeAccount.signedIn) YouTubeAccount.token() else YouTubeAccount.token().replace(Regex("=.*"), "=")) }
        val valid = YouTubeAccount.cookieOf(text) != null
        DialogCard("Login with token") {
            DialogField("Token", text, lines = 8) { text = it }
            Spacer(Modifier.height(10.dp))
            Text(
                "This is an ADVANCED login method. As an alternative to the web portal, you may directly enter or update " +
                    "your login token here. A browser's Cookie header for music.youtube.com works too: open YouTube Music " +
                    "signed in, open the developer tools (F12), Network, click any request to music.youtube.com and copy " +
                    "its \"cookie\" request header. Anyone with this token can use your YouTube account, so keep it private.",
                style = ConvxType.Subhead,
                color = ConvxColors.SecondaryLabel,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Copy", {
                    clipboard.setText(AnnotatedString(text))
                    Overlays.toast("Token copied", "content_copy")
                }, icon = "content_copy")
                PillButton("Cancel", { Overlays.dismissDialog() })
                PillButton("Log in", {
                    if (valid) {
                        YouTubeAccount.signInWithToken(text)
                        Overlays.dismissDialog()
                    }
                }, filled = valid)
            }
        }
    }
}
