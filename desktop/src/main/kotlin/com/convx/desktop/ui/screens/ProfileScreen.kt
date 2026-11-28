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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.core.pluralize
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.integrations.spotify.SpotifyAccount
import com.convx.desktop.integrations.spotify.SpotifyApi
import com.convx.desktop.integrations.spotify.SpotifyPlaylist
import com.convx.desktop.integrations.youtube.YouTubeAccount
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.Gutter
import com.convx.desktop.ui.components.ItemCard
import com.convx.desktop.ui.components.LocalPageInsets
import com.convx.desktop.ui.components.MosaicArt
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.components.SectionHeader
import com.convx.desktop.ui.components.Shelf
import com.convx.desktop.ui.components.SongRow
import com.convx.desktop.ui.screens.settings.SettingsNav
import com.convx.desktop.ui.screens.settings.SettingsPage
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.screens.settings.SystemColors
import com.music.innertube.models.ArtistItem

/** Who the profile is: your Spotify account when one is linked, else YouTube Music's, else just you. */
data class ProfileIdentity(val name: String, val picture: String?, val source: String?)

@Composable
fun rememberProfileIdentity(): ProfileIdentity {
    val spotify by SpotifyAccount.state.collectAsState()
    val youtube by YouTubeAccount.state.collectAsState()
    val spotifyProfile = (spotify as? SpotifyAccount.State.SignedIn)?.profile
    val youtubeProfile = (youtube as? YouTubeAccount.State.SignedIn)?.profile
    return when {
        spotifyProfile != null -> ProfileIdentity(spotifyProfile.name, spotifyProfile.image, "Spotify")
        youtubeProfile != null -> ProfileIdentity(youtubeProfile.name, youtubeProfile.thumbnail, "YouTube Music")
        else -> ProfileIdentity("You", null, null)
    }
}

/** Convx's red, darkened, where Spotify tints a profile from its picture: Convx keeps its own colour. */
private val ProfileGlow = listOf(Color(0xFF7A1424), Color(0xFF3A0B12), Color.Black)

/**
 * Your profile, laid out as Spotify's is: your picture and name (from Spotify when it's linked),
 * then your top artists and top songs this month (the phone's My top), whom you follow, and your
 * playlists, with Spotify's beside Convx's.
 */
@Composable
fun ProfileScreen() {
    val navigator = LocalNavigator.current
    val library by Library.state.collectAsState()
    val settings by AppSettings.state.collectAsState()
    val spotify by SpotifyAccount.state.collectAsState()
    val identity = rememberProfileIdentity()
    val month = remember(library.history) { Library.listeningStats(TopFilter.Month.since()) }
    val topSongs = remember(month, settings.topSize, settings.hideVideos) {
        month.songs.filter { !settings.hideVideos || !it.value.isVideo }.take(settings.topSize)
    }
    val minutes = remember(month) { month.songs.sumOf { it.playedMs } / 60_000 }
    val spotifySignedIn = spotify is SpotifyAccount.State.SignedIn
    // Opening the profile asks Spotify again for a name and picture it hasn't given yet.
    LaunchedEffect(Unit) {
        if ((SpotifyAccount.state.value as? SpotifyAccount.State.SignedIn)?.profile == null && spotifySignedIn) SpotifyAccount.refreshProfile()
    }
    var spotifyLists by remember { mutableStateOf<List<SpotifyPlaylist>?>(null) }
    var spotifyProblem by remember { mutableStateOf<String?>(null) }
    var reloads by remember { mutableStateOf(0) }
    LaunchedEffect(spotifySignedIn, reloads) {
        spotifyLists = null
        spotifyProblem = null
        if (!spotifySignedIn) return@LaunchedEffect
        SpotifyApi.playlists(refresh = reloads > 0)
            .onSuccess { spotifyLists = it }
            .onFailure { spotifyProblem = it.message ?: "Spotify didn't answer" }
    }
    val playlistCount = library.localPlaylists.size + library.playlists.size + (spotifyLists?.size ?: 0)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PageList(state = rememberLazyListState(), tint = Color(0xFF2A0A10), topPadding = 0.dp) {
            item(key = "header") {
                ProfileHeader(
                    identity,
                    listOfNotNull(
                        pluralize(playlistCount, "playlist"),
                        pluralize(library.artists.size, "following", "following"),
                        pluralize(library.liked.size, "liked song"),
                        minutes.takeIf { it > 0 }?.let { "${pluralize(it.toInt(), "minute")} this month" },
                    ).joinToString(" • "),
                )
            }
            // Linked, but Spotify won't hand over the profile: say so instead of a silent "You".
            (spotify as? SpotifyAccount.State.SignedIn)?.takeIf { it.profile == null }?.let { linked ->
                item(key = "spotify-unanswered") {
                    val waiting by SpotifyApi.waiting.collectAsState()
                    Text(
                        linked.problem?.let { "Your Spotify account is linked, but Spotify won't answer Convx ($it). Settings > Spotify shows more and how to fix it." }
                            ?: waiting
                            ?: "Loading your Spotify profile…",
                        style = ConvxType.Subhead,
                        color = if (linked.problem != null) SystemColors.Red else ConvxColors.SecondaryLabel,
                        modifier = Modifier.padding(start = Gutter, end = Gutter, top = 12.dp),
                    )
                }
            }
            item(key = "actions") {
                Row(
                    Modifier.padding(horizontal = Gutter, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (topSongs.isNotEmpty()) {
                        PillButton("Play your top songs", {
                            PlayerController.playSongs(topSongs.map { it.value }, 0, "My top ${settings.topSize}", shuffle = false)
                        }, icon = "play", filled = true)
                    }
                    PillButton("Settings", { navigator.navigate(Screen.Settings) }, icon = "settings")
                    if (!spotifySignedIn) {
                        PillButton("Link Spotify", {
                            navigator.navigate(Screen.Settings)
                            SettingsNav.push(SettingsPage.Spotify)
                        }, icon = "spotify", accent = SpotifyGreen)
                    } else {
                        PillButton("Spotify account", {
                            navigator.navigate(Screen.Settings)
                            SettingsNav.push(SettingsPage.Spotify)
                        }, icon = "spotify")
                    }
                }
            }
            if (month.artists.isNotEmpty()) {
                item(key = "artists") {
                    Shelf(
                        "Top artists this month",
                        Modifier.padding(top = 8.dp),
                        subtitle = "Only visible to you",
                        onTitleClick = { navigator.navigate(Screen.Stats) },
                    ) {
                        items(month.artists.take(12), key = { it.value.id!! }) { ranked ->
                            val id = ranked.value.id!!
                            val picture = library.artists.firstOrNull { it.id == id }?.thumbnail ?: library.artistPictures[id]
                            LaunchedEffect(id, picture) { if (picture == null) ArtistPictureLookup.request(id) }
                            ItemCard(
                                ArtistItem(id = id, title = ranked.value.name, thumbnail = picture, shuffleEndpoint = null, radioEndpoint = null),
                                subtitle = "Artist",
                            )
                        }
                    }
                }
            }
            item(key = "top-title") {
                SectionHeader(
                    "Top tracks this month",
                    Modifier.padding(start = Gutter, end = Gutter, top = 28.dp, bottom = 8.dp),
                    subtitle = "Only visible to you",
                    action = if (topSongs.size > 5) "Show all" else null,
                    onAction = { navigator.navigate(Screen.MyTop) },
                )
            }
            if (topSongs.isEmpty()) {
                item(key = "top-empty") {
                    Text(
                        "Songs you play this month rank here by how long you listen.",
                        style = ConvxType.Body,
                        color = ConvxColors.SecondaryLabel,
                        modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
                    )
                }
            } else {
                val shown = topSongs.take(5).map { it.value }
                itemsIndexed(shown, key = { _, song -> "top-${song.id}" }) { index, song ->
                    SongRow(
                        song,
                        onPlay = { PlayerController.playSongs(shown, index, "My top ${settings.topSize}") },
                        index = index + 1,
                        trailingText = statsLine(topSongs[index].plays, topSongs[index].playedMs),
                    )
                }
            }
            if (library.artists.isNotEmpty()) {
                item(key = "following") {
                    Shelf("Following", Modifier.padding(top = 28.dp), onTitleClick = {
                        com.convx.desktop.ui.shell.UiState.libraryTab = com.convx.desktop.ui.shell.LibraryTab.Artists
                        navigator.navigate(Screen.Library)
                    }) {
                        items(library.artists, key = { "following-${it.id}" }) { artist ->
                            ItemCard(
                                ArtistItem(id = artist.id, title = artist.name, thumbnail = artist.thumbnail, shuffleEndpoint = null, radioEndpoint = null),
                                subtitle = "Artist",
                            )
                        }
                    }
                }
            }
            item(key = "playlists") {
                Shelf("Playlists", Modifier.padding(top = 28.dp), onTitleClick = {
                    com.convx.desktop.ui.shell.UiState.libraryTab = com.convx.desktop.ui.shell.LibraryTab.Playlists
                    navigator.navigate(Screen.Library)
                }) {
                    items(library.localPlaylists, key = { "local-${it.id}" }) { p ->
                        SavedCard(
                            p.name,
                            "Playlist • ${pluralize(p.songs.size, "song")}",
                            art = { size -> com.convx.desktop.ui.components.PlaylistCoverArt(p, size, RoundedCornerShape(8.dp)) },
                            onClick = { navigator.navigate(Screen.LocalPlaylist(p.id)) },
                            onPlay = if (p.songs.isEmpty()) null else ({ PlayerController.playSongs(p.songs, 0, p.name) }),
                        )
                    }
                    items(library.playlists, key = { "saved-${it.id}" }) { p ->
                        SavedCard(
                            p.title,
                            listOfNotNull("Playlist", p.author).joinToString(" • "),
                            thumbnail = p.thumbnail,
                            onClick = { navigator.navigate(Screen.Playlist(p.id)) },
                        )
                    }
                    items(spotifyLists.orEmpty(), key = { "spotify-${it.id}" }) { p ->
                        SavedCard(
                            p.name,
                            listOfNotNull("Spotify", p.trackCount.takeIf { it >= 0 }?.let { pluralize(it, "song") } ?: p.owner.ifBlank { null }).joinToString(" • "),
                            thumbnail = p.image,
                            onClick = { navigator.navigate(Screen.SpotifyPlaylist(p.id)) },
                        )
                    }
                }
            }
            spotifyProblem?.let { problem ->
                item(key = "spotify-problem") {
                    Row(
                        Modifier.padding(horizontal = Gutter, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Couldn't read your Spotify playlists: $problem",
                            style = ConvxType.Subhead,
                            color = ConvxColors.SecondaryLabel,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        PillButton("Try again", {
                            SpotifyApi.clearCache()
                            reloads++
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(identity: ProfileIdentity, line: String) {
    val top = LocalPageInsets.current.top
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(ProfileGlow)),
    ) {
        Row(
            Modifier.padding(start = Gutter, end = Gutter, top = top + 36.dp, bottom = 24.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Artwork(
                identity.picture,
                Modifier.size(200.dp).shadow(28.dp, CircleShape),
                size = 640,
                shape = CircleShape,
                placeholderIcon = "person",
                iconSize = 72.dp,
            )
            Spacer(Modifier.width(24.dp))
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(
                    identity.source?.let { "Profile · $it" } ?: "Profile",
                    style = ConvxType.SubheadStrong,
                    color = Color.White,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    identity.name,
                    style = ConvxType.Hero.copy(fontSize = 72.sp, lineHeight = 80.sp, fontWeight = FontWeight.Black),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(line, style = ConvxType.Subhead, color = Color.White.copy(alpha = 0.8f), maxLines = 2)
            }
        }
    }
}
