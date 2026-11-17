/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.AppInfo
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.integrations.youtube.YouTubeAccount
import com.convx.desktop.integrations.youtube.YouTubeSync
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.MenuDivider
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.glass.GlassSwitch
import com.convx.desktop.ui.screens.settings.SettingsHubs
import com.convx.desktop.ui.screens.settings.SettingsNav
import com.convx.desktop.ui.screens.settings.SettingsPage
import com.convx.desktop.ui.screens.settings.openUrl
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType

/**
 * The phone's settings popup, for "Enable settings popup": who's signed in, what your library
 * holds, the account's two switches, then Settings and About, and the policy links at the foot.
 */
@Composable
fun SettingsPopup(dismiss: () -> Unit) {
    val navigator = LocalNavigator.current
    val account by YouTubeAccount.state.collectAsState()
    val identity = com.convx.desktop.ui.screens.rememberProfileIdentity()
    val settings by AppSettings.state.collectAsState()
    val library by Library.state.collectAsState()
    val signedIn = account as? YouTubeAccount.State.SignedIn
    fun openSettings(page: SettingsPage? = null) {
        dismiss()
        navigator.navigate(Screen.Settings)
        page?.let(SettingsNav::push)
    }

    Column(Modifier.width(300.dp).padding(vertical = 4.dp)) {
        // You, as your profile shows you (Spotify's picture and name when it's linked); a click opens the profile.
        Row(
            Modifier.fillMaxWidth().pressable({
                dismiss()
                navigator.navigate(Screen.Profile)
            }).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val picture = identity.picture
            if (picture != null) {
                Artwork(picture, Modifier.size(36.dp), size = 96, shape = CircleShape, placeholderIcon = "person")
            } else {
                ConvxIcon("account", tint = ConvxColors.SecondaryLabel, size = 36.dp)
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(identity.name.takeIf { identity.source != null } ?: "Anonymous", style = ConvxType.Body, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        identity.source == "Spotify" -> "View profile · Spotify"
                        signedIn != null -> signedIn.profile.email?.takeIf { it.isNotBlank() } ?: "View profile"
                        else -> "View profile"
                    },
                    style = ConvxType.Subhead,
                    color = ConvxColors.SecondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PopupRow(if (signedIn != null) "Manage account" else "Log in", "login") { openSettings(SettingsPage.YouTubeMusic) }
        MenuDivider()
        PopupRow("Playlists", "library_music_outlined", (library.playlists.size + library.localPlaylists.size).toString()) {
            dismiss()
            UiState.libraryTab = LibraryTab.Playlists
            navigator.navigate(Screen.Library)
        }
        PopupRow("Albums", "album", library.albums.size.toString()) {
            dismiss()
            UiState.libraryTab = LibraryTab.Albums
            navigator.navigate(Screen.Library)
        }
        PopupRow("Liked Songs", "favorite_border", library.liked.size.toString()) {
            dismiss()
            navigator.navigate(Screen.Liked)
        }
        if (signedIn != null) {
            PopupSwitch("Use account for browsing", "add_circle", settings.youtubeMoreContent) { YouTubeAccount.setMoreContent(it) }
            PopupSwitch("YouTube Music sync", "sync", settings.youtubeSync) { on ->
                AppSettings.update { it.copy(youtubeSync = on) }
                if (on) YouTubeSync.syncNow()
            }
        }
        MenuDivider()
        PopupRow("Settings", "settings") { openSettings() }
        PopupRow("About", "info", AppInfo.VERSION) {
            dismiss()
            navigator.navigate(Screen.Settings)
            SettingsNav.openHub(SettingsHubs.help.id)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            FooterLink("Privacy Policy") { openUrl(PolicyUrl) }
            Text("•", style = ConvxType.Footnote, color = ConvxColors.TertiaryLabel, modifier = Modifier.padding(horizontal = 6.dp))
            FooterLink("Terms of Service") { openUrl(PolicyUrl) }
        }
    }
}

/** Where the phone's popup sends both of its links. */
private const val PolicyUrl = "https://cosmictaser.de5.net"

@Composable
private fun PopupRow(title: String, icon: String, trailing: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConvxIcon(icon, tint = Color.White, size = 18.dp)
        Spacer(Modifier.width(12.dp))
        Text(title, style = ConvxType.Body, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
        if (trailing != null) Text(trailing, style = ConvxType.Body, color = ConvxColors.SecondaryLabel, maxLines = 1)
    }
}

@Composable
private fun PopupSwitch(title: String, icon: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable({ onChange(!checked) }).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConvxIcon(icon, tint = Color.White, size = 18.dp)
        Spacer(Modifier.width(12.dp))
        Text(title, style = ConvxType.Body, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
        GlassSwitch(checked, onChange)
    }
}

@Composable
private fun FooterLink(text: String, onClick: () -> Unit) {
    Text(text, style = ConvxType.Footnote, color = ConvxColors.SecondaryLabel, modifier = Modifier.pressable(onClick).padding(4.dp))
}
