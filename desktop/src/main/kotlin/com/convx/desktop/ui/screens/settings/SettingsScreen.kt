/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import com.convx.desktop.ui.glass.GlassShapes
import com.convx.desktop.ui.glass.GlassTokens
import com.convx.desktop.ui.glass.LocalGlassConfig
import com.convx.desktop.ui.glass.liquidGlass
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.integrations.spotify.SpotifyAccount
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.glass.GlassCircleButton
import com.convx.desktop.ui.components.LocalPageInsets
import com.convx.desktop.ui.shapes.ContinuousCapsule
import com.convx.desktop.ui.shapes.ContinuousRoundedRectangle
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.TrackTopBar
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent

/** A root category of Settings, as on the phone (SettingsHubs). */
@Stable
class SettingsHub(val id: String, val title: String, val icon: String, val tint: Color)

/** A page under a category, opened from one of its rows. */
enum class SettingsPage(val title: String, val hub: String) {
    PlayerDesign("Player design", "player"),
    ControlsLayout("Controls & layout", "player"),
    CustomPlayerIcons("Custom player icons", "player"),
    LyricsStyle("Lyrics style", "player"),
    LyricsEffects("Lyrics effects", "player"),
    LyricsText("Text & layout", "player"),
    LyricsSources("Sources & priority", "player"),
    Romanization("Romanization", "player"),
    AiTranslation("AI lyrics translation", "player"),
    Spotify("Spotify", "accounts"),
    Discord("Discord", "accounts"),
    LiquidGlass("Liquid Glass", "appearance"),
    Presets("Presets", "appearance"),
    Font("App Font", "appearance"),
    LayoutHome("Layout & home", "appearance"),
    HomeFeedOrder("Arrange home feed (Beta)", "appearance"),
    Equalizer("Convx Equalizer", "playback"),
    QualitySound("Quality & sound", "playback"),
    Transitions("Song transitions", "playback"),
    Queue("Queue & autoplay", "playback"),
    Output("Sound output", "playback"),
    YouTubeMusic("YouTube Music", "accounts"),
    LastFm("Last.fm", "accounts"),
    ListenBrainz("ListenBrainz", "accounts"),
    Network("Network & proxy", "accounts"),
    LocalFolders("Music folders", "library"),
    Privacy("Privacy", "privacy"),
    Storage("Storage", "privacy"),
    Backup("Backup & restore", "privacy"),
    CrashReport("Crash report", "help"),
    Updates("App updates", "help"),
}

object SettingsHubs {
    val player = SettingsHub("player", "Player & Lyrics", "play", SystemColors.Pink)
    val playback = SettingsHub("playback", "Playback & Audio", "equalizer", SystemColors.Red)
    val appearance = SettingsHub("appearance", "Appearance", "palette", SystemColors.Purple)
    val library = SettingsHub("library", "Library & Content", "music_note", SystemColors.Orange)
    val accounts = SettingsHub("accounts", "Accounts & Services", "account", SystemColors.Green)
    val privacy = SettingsHub("privacy", "Privacy & Storage", "security", SystemColors.Blue)
    val help = SettingsHub("help", "Help & About", "info", SystemColors.Gray)

    val all = listOf(player, playback, appearance, library, accounts, privacy, help)

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: player
}

/** Where Settings is: the category on the right, and the pages pushed over it. Kept across visits, like the phone's. */
object SettingsNav {
    var hub by mutableStateOf(SettingsHubs.player.id)
    val pages = mutableStateListOf<SettingsPage>()

    /** On a narrow window the root list is a page of its own; this is whether a category is open over it. */
    var hubOpen by mutableStateOf(false)

    /** Which way the last move went, for the slide. */
    internal var forward = true

    fun openHub(id: String) {
        forward = true
        hub = id
        pages.clear()
        hubOpen = true
    }

    fun push(page: SettingsPage) {
        forward = true
        if (hub != page.hub) {
            hub = page.hub
            pages.clear()
        }
        pages += page
        hubOpen = true
    }

    fun back() {
        forward = false
        if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex) else hubOpen = false
    }
}

/** Wider than this, the root becomes a sidebar with the chosen category beside it (the phone's tablet layout). */
private val SplitWidth = 820.dp
private val SidebarWidth = 360.dp

@Composable
fun SettingsScreen() {
    val insets = LocalPageInsets.current
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val split = maxWidth - insets.start - insets.end >= SplitWidth
        val rootState = rememberLazyListState()
        if (split) {
            Row(Modifier.fillMaxSize().padding(start = insets.start, end = insets.end)) {
                Box(Modifier.width(SidebarWidth).fillMaxHeight()) {
                    RootList(rootState, sidebar = true)
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    DetailPane(sidebar = true)
                }
            }
        } else {
            Box(Modifier.fillMaxSize().padding(start = insets.start, end = insets.end)) {
                if (SettingsNav.hubOpen) {
                    DetailPane(sidebar = false)
                } else {
                    TrackTopBar(rootState, Color.Black)
                    RootList(rootState, sidebar = false)
                }
            }
        }
    }
}

/** A scrolling column of settings under the top bar, with the window's scrollbar. */
@Composable
private fun SettingsList(state: LazyListState, horizontal: Dp, content: LazyListScope.() -> Unit) {
    val insets = LocalPageInsets.current
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(start = horizontal, end = horizontal, top = insets.top + 8.dp, bottom = insets.bottom + 48.dp),
            modifier = Modifier.fillMaxSize(),
            content = content,
        )
        VerticalScrollbar(
            rememberScrollbarAdapter(state),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(top = insets.top, bottom = insets.bottom + 8.dp, end = 2.dp),
        )
    }
}

/** The phone's root: the profile card, search, and the categories in their four groups. */
@Composable
private fun RootList(state: LazyListState, sidebar: Boolean) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val navigator = LocalNavigator.current
    val rootGroups = listOf(
        listOf(SettingsHubs.player, SettingsHubs.playback, SettingsHubs.appearance),
        listOf(SettingsHubs.library, SettingsHubs.accounts),
        listOf(SettingsHubs.privacy),
        listOf(SettingsHubs.help),
    )
    SettingsList(state, horizontal = 16.dp) {
        item("title") {
            Text("Settings", style = ConvxType.LargeTitle, color = ConvxColors.Label, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 14.dp))
        }
        item("search") { SearchField(query, { query = it }) }
        if (query.isNotBlank()) {
            item("results") {
                Spacer(Modifier.height(20.dp))
                SearchResults(query)
            }
            return@SettingsList
        }
        item("profile") {
            Spacer(Modifier.height(20.dp))
            ProfileCard { SettingsNav.openHub(SettingsHubs.accounts.id) }
        }
        rootGroups.forEachIndexed { i, hubs ->
            item("group$i") {
                Spacer(Modifier.height(if (i == 0) 20.dp else 28.dp))
                SettingsSection {
                    hubs.forEachIndexed { j, hub ->
                        if (j > 0) RowDivider()
                        LinkRow(
                            hub.title,
                            icon = hub.icon,
                            tint = hub.tint,
                            selected = sidebar && SettingsNav.hub == hub.id,
                        ) { SettingsNav.openHub(hub.id) }
                    }
                    if (i == 2) {
                        RowDivider()
                        LinkRow("History", icon = "music_history", tint = SystemColors.Cyan) { navigator.navigate(Screen.History) }
                    }
                }
            }
        }
    }
}

/** The account at the top of the phone's Settings; here, the accounts this PC has linked. */
@Composable
private fun ProfileCard(onClick: () -> Unit) {
    val spotify by SpotifyAccount.state.collectAsState()
    val youtube by com.convx.desktop.integrations.youtube.YouTubeAccount.state.collectAsState()
    // The phone's card is the YouTube Music account; Spotify's stands in until you sign in there.
    val youtubeProfile = (youtube as? com.convx.desktop.integrations.youtube.YouTubeAccount.State.SignedIn)?.profile
    val spotifyProfile = (spotify as? SpotifyAccount.State.SignedIn)?.profile
    val name = youtubeProfile?.name ?: spotifyProfile?.name
    val photo = youtubeProfile?.thumbnail ?: spotifyProfile?.image
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(20.dp))
            .background(ConvxColors.Card)
            .rowPress(onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (photo != null) {
            Artwork(photo, Modifier.size(60.dp), size = 160, shape = CircleShape, placeholderIcon = "person")
        } else {
            Box(Modifier.size(60.dp).clip(CircleShape).background(ConvxColors.CardTertiary), contentAlignment = Alignment.Center) {
                ConvxIcon("person", tint = Color.White.copy(alpha = 0.85f), size = 30.dp)
            }
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(name ?: "Sign in", style = ConvxType.Title3, color = ConvxColors.Label, maxLines = 1)
            Text(
                youtubeProfile?.let { it.email ?: it.handle } ?: "YouTube Music, Spotify and more",
                style = ConvxType.Subhead,
                color = ConvxColors.SecondaryLabel,
                maxLines = 1,
            )
        }
        ConvxIcon("chevron_right_px", tint = ConvxColors.TertiaryLabel, size = 14.dp)
    }
}

/** The phone's search pill, at the top here where a mouse expects it. */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            // The phone's search pill is its glass; over the page it's the tint and the lit rim.
            .clip(GlassShapes.Capsule)
            .liquidGlass(GlassShapes.Capsule, LocalGlassConfig.current.onPage(), highlightAlpha = GlassTokens.PluckAlphaNeutral)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConvxIcon("search", tint = ConvxColors.SecondaryLabel, size = 17.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search", style = ConvxType.Body, color = ConvxColors.SecondaryLabel)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = ConvxType.Body.copy(color = ConvxColors.Label),
                cursorBrush = SolidColor(LocalAccent.current),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(Modifier.size(22.dp).clip(CircleShape).rowPress({ onValueChange("") }), contentAlignment = Alignment.Center) {
                ConvxIcon("close", tint = ConvxColors.SecondaryLabel, size = 14.dp)
            }
        }
    }
}

/** One thing Settings can find: where it lives, and the words people look for it by. */
private class SearchEntry(
    val title: String,
    val subtitle: String?,
    val hub: SettingsHub,
    val page: SettingsPage? = null,
    val keywords: String = "",
)

private val searchEntries: List<SearchEntry> by lazy {
    val h = SettingsHubs
    listOf(
        SearchEntry("Player design", "Card, Vinyl, Clover, Apple Music", h.player, SettingsPage.PlayerDesign, "player look style theme vinyl record clover apple full bleed"),
        SearchEntry("Player background", "Gradient, blur, glow, mesh, glass", h.player, SettingsPage.PlayerDesign, "background gradient blur glow mesh solid colour color glass"),
        SearchEntry("Seek bar style", "Default, wavy, slim, waveform", h.player, SettingsPage.PlayerDesign, "seek bar slider squiggly wavy progress waveform"),
        SearchEntry("Show next track", "What plays next, under the controls", h.player, SettingsPage.PlayerDesign, "up next"),
        SearchEntry("Volume bar", null, h.player, SettingsPage.PlayerDesign, "volume slider"),
        SearchEntry("Controls & layout", "Thumbnail, menus, buttons and badges", h.player, SettingsPage.ControlsLayout, "advanced appearance layout controls"),
        SearchEntry("Hide player thumbnail", "Replace album artwork with the app logo in the player", h.player, SettingsPage.ControlsLayout, "artwork cover logo hide"),
        SearchEntry("Thumbnail corner radius", null, h.player, SettingsPage.ControlsLayout, "artwork cover corners rounded radius"),
        SearchEntry("Menu corner radius", "The phone's sheet corner radius", h.player, SettingsPage.ControlsLayout, "sheet menu corners rounded radius"),
        SearchEntry("Show audio quality badge", null, h.player, SettingsPage.ControlsLayout, "quality badge bitrate codec opus aac"),
        SearchEntry("Show play/pause on thumbnail", "Click the cover by the lyrics to play or pause", h.player, SettingsPage.ControlsLayout, "lyrics thumbnail cover play pause"),
        SearchEntry("Show comment button", "YouTube comments in the player", h.player, SettingsPage.ControlsLayout, "comments youtube comment button player"),
        SearchEntry("Collapse controls upward", "A compact player bar over full-screen lyrics", h.player, SettingsPage.ControlsLayout, "full screen fullscreen lyrics controls bar top collapse"),
        SearchEntry("Enable settings popup", null, h.player, SettingsPage.ControlsLayout, "account popup quick settings"),
        SearchEntry("Library icons only", null, h.player, SettingsPage.ControlsLayout, "library icons artwork thumbnails"),
        SearchEntry("Navigation tabs", "Hide Home, Explore, Radio or Library from the side bar", h.player, SettingsPage.ControlsLayout, "tabs top bar hide home explore radio library"),
        SearchEntry("Default library chip", null, h.player, SettingsPage.ControlsLayout, "library filter chip playlists albums artists"),
        SearchEntry("Grid cell size", "Big or small cards", h.player, SettingsPage.ControlsLayout, "grid cards size big small"),
        SearchEntry("Display density", "Make everything smaller", h.player, SettingsPage.ControlsLayout, "density scale compact zoom size"),
        SearchEntry("Reduce live blur", "For slower PCs", h.player, SettingsPage.ControlsLayout, "blur performance low power motion"),
        SearchEntry("Show shuffle button", "On Home", h.player, SettingsPage.ControlsLayout, "fab floating shuffle home random"),
        SearchEntry("Hide favorite icon on Home", null, h.player, SettingsPage.ControlsLayout, "heart liked favorite home"),
        SearchEntry("Grid & cards", "Columns, card height, spacing and corners", h.player, SettingsPage.ControlsLayout, "grid columns card height spacing hero corner radius"),
        SearchEntry("DIY", "Place stickers, images and emoji over the player", h.player, keywords = "diy stickers emoji images decorate player customize"),
        SearchEntry("Custom player icons", "Your own images for the player's buttons", h.player, SettingsPage.CustomPlayerIcons, "icons buttons images play pause skip next previous like heart more seek handle thumb custom"),
        SearchEntry("Lyrics style", "Animation, position, scrolling and swipe", h.player, SettingsPage.LyricsStyle, "lyrics animation karaoke word by word apple music fade glow slide metro fluid chord"),
        SearchEntry("Lyrics text position", "Left, center or right", h.player, SettingsPage.LyricsStyle, "lyrics align alignment left center right"),
        SearchEntry("Auto scroll lyrics", "Keep the current lyric in view", h.player, SettingsPage.LyricsStyle, "lyrics follow scroll"),
        SearchEntry("Swipe to change song", null, h.player, SettingsPage.LyricsStyle, "lyrics swipe gesture next previous"),
        SearchEntry("One-tap full-screen lyrics", "The lyrics button goes straight to full screen", h.player, SettingsPage.LyricsStyle, "full screen fullscreen lyrics one tap click button expand"),
        SearchEntry("Lyrics effects", "Font, size, glow and blur", h.player, SettingsPage.LyricsEffects, "convx lyrics font typeface size text glow blur"),
        SearchEntry("Lyrics font", "Choose or import a font", h.player, SettingsPage.LyricsEffects, "lyrics font typeface ttf otf import custom"),
        SearchEntry("Lyrics glow", "Glow behind the current lyric line", h.player, SettingsPage.LyricsEffects, "lyrics glow shadow"),
        SearchEntry("Apple Music Lyrics Blur", "Blur lyric lines away from the current one", h.player, SettingsPage.LyricsEffects, "lyrics blur apple"),
        SearchEntry("Standard lyrics blur", "Blur for standard lyric styles", h.player, SettingsPage.LyricsEffects, "lyrics blur"),
        SearchEntry("Lyrics text size", null, h.player, SettingsPage.LyricsEffects, "lyrics size bigger smaller"),
        SearchEntry("Lyrics line spacing", null, h.player, SettingsPage.LyricsText, "lyrics spacing line height"),
        SearchEntry("Word-by-word lyrics", "Light up each word as it's sung", h.player, SettingsPage.LyricsText, "karaoke synced lyrics"),
        SearchEntry("Lyrics provider priority", "Choose and order lyric providers", h.player, SettingsPage.LyricsSources, "lyrics provider source order youlyplus lyricsplus paxsenix lrclib musixmatch genius kugou better lyrics unison simpmusic megalobiz"),
        SearchEntry("Romanization", "Show lyrics in Latin letters", h.player, SettingsPage.Romanization, "romanize romaji pinyin japanese korean chinese cyrillic russian ukrainian hindi punjabi latin transliterate"),
        SearchEntry("Show romanized lyrics as main", null, h.player, SettingsPage.Romanization, "romanize main romaji"),
        SearchEntry("AI lyrics translation", "Provider, API key, model and language", h.player, SettingsPage.AiTranslation, "ai translate translation openrouter groq openai claude anthropic gemini mistral deepl grok perplexity api key model language transcription"),
        SearchEntry("Target language", "What lyrics are translated into", h.player, SettingsPage.AiTranslation, "translate language"),
        SearchEntry("PaxSenix API key", "For the PaxSenix Spotify and Musixmatch sources", h.player, SettingsPage.LyricsSources, "paxsenix key api spotify musixmatch"),
        SearchEntry("Spicy Lyrics key", "Word-synced lyrics from Spicy Lyrics", h.player, SettingsPage.LyricsSources, "spicy lyrics key synced word apple"),
        SearchEntry("Equalizer", "Shape the sound", h.playback, SettingsPage.Equalizer, "eq bass treble mids preset parametric autoeq dolby dirac"),
        SearchEntry("Spatial audio", "Wider stereo for headphones", h.playback, SettingsPage.Equalizer, "3d surround headphones widen"),
        SearchEntry("Audio quality", "High or low", h.playback, SettingsPage.QualitySound, "bitrate stream data"),
        SearchEntry("Audio normalization", "Even out loud and quiet songs", h.playback, SettingsPage.QualitySound, "loudness volume level"),
        SearchEntry("Skip silence", "Fast forward through silent parts", h.playback, SettingsPage.QualitySound, "silence gap"),
        SearchEntry("Song transitions", "AutoMix or crossfade between songs", h.playback, SettingsPage.Transitions, "crossfade fade automix gapless dj beat tempo analyze"),
        SearchEntry("Creative transitions", "Reverb, delay, echo freeze and tape stops in AutoMix", h.playback, SettingsPage.Transitions, "creative reverb delay echo tape stop room plate hall ping pong slapback"),
        SearchEntry("Persistent queue", "Restore your last queue", h.playback, SettingsPage.Queue, "queue restore"),
        SearchEntry("Ask before replacing the queue", "Keep or clear the queue", h.playback, SettingsPage.Queue, "queue replace keep clear undo"),
        SearchEntry("Enable similar content", "Keep going when the queue ends", h.playback, SettingsPage.Queue, "radio autoplay queue"),
        SearchEntry("Remember shuffle and repeat", null, h.playback, SettingsPage.Queue, "shuffle repeat"),
        SearchEntry("Auto download on like", "Download songs when you like them", h.playback, SettingsPage.Queue, "download offline like heart"),
        SearchEntry("Save downloads to Music folder", "Copies in Music\\Convx", h.playback, SettingsPage.Queue, "download offline music folder export m4a lrc"),
        SearchEntry("YouTube Music", "Log in, channels and sync", h.accounts, SettingsPage.YouTubeMusic, "youtube google login sign in account channel sync token"),
        SearchEntry("Login with token", null, h.accounts, SettingsPage.YouTubeMusic, "token cookie"),
        SearchEntry("More content", "Recommendations from your account", h.accounts, SettingsPage.YouTubeMusic, "login browse personalized"),
        SearchEntry("Auto sync with account", null, h.accounts, SettingsPage.YouTubeMusic, "sync likes playlists"),
        SearchEntry("Import from Spotify", "Copy playlists and Liked Songs", h.accounts, SettingsPage.Spotify, "spotify import playlists"),
        SearchEntry("Last.fm", "Scrobbling", h.accounts, SettingsPage.LastFm, "lastfm scrobble love now playing"),
        SearchEntry("ListenBrainz", "Scrobbling with a token", h.accounts, SettingsPage.ListenBrainz, "listenbrainz scrobble musicbrainz"),
        SearchEntry("Music folders", "Folders with music files", h.library, SettingsPage.LocalFolders, "local files folders scan mp3 flac"),
        SearchEntry("Scan local files", null, h.library, SettingsPage.LocalFolders, "scan local files"),
        SearchEntry("Network storage", "SMB and WebDAV shares", h.library, SettingsPage.LocalFolders, "smb webdav nas network share"),
        SearchEntry("Excluded folders", null, h.library, SettingsPage.LocalFolders, "exclude skip folder"),
        SearchEntry("Network & proxy", "IP version and proxy", h.accounts, SettingsPage.Network, "proxy ipv6 ipv4 network socks http"),
        SearchEntry("Apple Music import", "Import playlists from a link or export", h.accounts, keywords = "apple music playlist import export itunes xml"),
        SearchEntry("Sound output", "Speakers or headphones", h.playback, SettingsPage.Output, "device speaker headphones output"),
        SearchEntry("Liquid Glass", "Blur, lens and highlights", h.appearance, SettingsPage.LiquidGlass, "glass blur lens refraction vibrancy chromatic aberration rim"),
        SearchEntry("Presets", "Save, apply and share complete looks", h.appearance, SettingsPage.Presets, "preset presets look looks theme save share import export convxpreset phone"),
        SearchEntry("App Font", "App typeface", h.appearance, SettingsPage.Font, "font typeface jakarta outfit google sans flex sf pro"),
        SearchEntry("Custom font", "Install a .ttf or .otf font", h.appearance, SettingsPage.Font, "font import ttf otf"),
        SearchEntry("Layout & home", "Default tab and home", h.appearance, SettingsPage.LayoutHome, "layout home default tab start open"),
        SearchEntry("Default open tab", null, h.appearance, SettingsPage.LayoutHome, "start home search library tab"),
        SearchEntry("Hero card", "Show the large featured card at the top of Home", h.appearance, SettingsPage.LayoutHome, "featured hero card home"),
        SearchEntry("Mood filters", "Show Energize, Relax and other filters at the top of Home", h.appearance, SettingsPage.LayoutHome, "mood chips filters energize relax"),
        SearchEntry("Set quick picks", "Quick picks, or based on the last song listened", h.appearance, SettingsPage.LayoutHome, "quick picks last song listened recommendations home"),
        SearchEntry("Randomize Home Screen Order", null, h.appearance, SettingsPage.LayoutHome, "shuffle random order home sections"),
        SearchEntry("Home feed order", "Arrange home sections", h.appearance, SettingsPage.HomeFeedOrder, "arrange order sections hide home feed"),
        SearchEntry("Content country", "Charts and recommendations", h.library, keywords = "region location"),
        SearchEntry("Content language", "The language YouTube Music answers in", h.library, keywords = "locale"),
        SearchEntry("Hide explicit content", null, h.library, keywords = "explicit filter"),
        SearchEntry("Hide music videos", "Only audio tracks", h.library, keywords = "video filter"),
        SearchEntry("Auto playlists", "Liked, Downloaded, Top, Cached and Uploaded", h.library, keywords = "show liked downloaded top cached uploaded uploads playlist library auto"),
        SearchEntry("My Top list length", "How many songs My top holds", h.library, keywords = "top 50 length size most played"),
        SearchEntry("Spotify", "Your playlists and Liked Songs", h.accounts, SettingsPage.Spotify, "account login import"),
        SearchEntry("Discord", "Listening status on your profile", h.accounts, SettingsPage.Discord, "rich presence rpc status buttons"),
        SearchEntry("Max song cache size", "Songs kept for instant starts", h.privacy, SettingsPage.Storage, "storage clear cache limit size"),
        SearchEntry("Max image cache size", "Covers and artist photos", h.privacy, SettingsPage.Storage, "storage clear cache limit size"),
        SearchEntry("Clear all downloads", "Songs kept to play offline", h.privacy, SettingsPage.Storage, "downloads offline storage clear remove"),
        SearchEntry("App data", "Where Convx keeps your library", h.privacy, SettingsPage.Storage, "folder logs"),
        SearchEntry("History duration", "When a song counts as a listen", h.privacy, SettingsPage.Privacy, "history listen seconds count"),
        SearchEntry("Pause listen history", "Stop recording what you play", h.privacy, SettingsPage.Privacy, "history incognito private"),
        SearchEntry("Clear listen history", null, h.privacy, SettingsPage.Privacy, "history delete recently played"),
        SearchEntry("Pause search history", null, h.privacy, SettingsPage.Privacy, "search history incognito private"),
        SearchEntry("Clear search history", null, h.privacy, SettingsPage.Privacy, "search history delete"),
        SearchEntry("Disable screenshot", "Hide the window from screenshots and screen sharing", h.privacy, SettingsPage.Privacy, "screenshot capture record share obs discord stream"),
        SearchEntry("Backup & restore", "Save or restore your library and settings", h.privacy, SettingsPage.Backup, "backup restore export phone transfer move"),
        SearchEntry("Import an M3U playlist", null, h.privacy, SettingsPage.Backup, "m3u m3u8 playlist file import winamp foobar musicbee"),
        SearchEntry("Import a CSV playlist", "Spreadsheets and Exportify", h.privacy, SettingsPage.Backup, "csv excel exportify tunemymusic itunes txt takeout import"),
        SearchEntry("About", "Version and licence", h.help, keywords = "version license gpl"),
        SearchEntry("App updates", "Check for and install a newer build", h.help, SettingsPage.Updates, "update upgrade new version download install changelog what's new"),
        SearchEntry("Crash report", "The last time the app closed unexpectedly", h.help, SettingsPage.CrashReport, "crash error bug log report problem"),
        SearchEntry("Copy recent log", "For when something goes wrong", h.help, keywords = "log debug bug problem copy"),
    )
}

@Composable
private fun SearchResults(query: String) {
    val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
    val hits = searchEntries.filter { e ->
        val text = listOfNotNull(e.title, e.subtitle, e.keywords, e.hub.title, e.page?.title).joinToString(" ").lowercase()
        terms.isNotEmpty() && terms.all { it in text }
    } + SettingsHubs.all.filter { hub -> terms.isNotEmpty() && terms.all { it in hub.title.lowercase() } }
        .map { SearchEntry(it.title, null, it) }
    if (hits.isEmpty()) {
        Text(
            "No settings found for “${query.trim()}”",
            style = ConvxType.Body,
            color = ConvxColors.SecondaryLabel,
            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        )
        return
    }
    SettingsSection(title = if (hits.size == 1) "1 result" else "${hits.size} results") {
        hits.forEachIndexed { i, hit ->
            if (i > 0) RowDivider()
            LinkRow(
                hit.title,
                subtitle = hit.hub.title + (hit.subtitle?.let { " · $it" } ?: ""),
                icon = hit.hub.icon,
                tint = hit.hub.tint,
            ) {
                if (hit.page != null) SettingsNav.push(hit.page) else SettingsNav.openHub(hit.hub.id)
            }
        }
    }
}

/** The right side: the category, or the page pushed over it, sliding in and out as iOS pushes. */
@Composable
private fun DetailPane(sidebar: Boolean) {
    val key = SettingsNav.hub + "/" + SettingsNav.pages.joinToString("/") { it.name }
    AnimatedContent(
        targetState = key,
        transitionSpec = {
            val sameHub = initialState.substringBefore('/') == targetState.substringBefore('/')
            when {
                !sameHub -> fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                SettingsNav.forward -> push()
                else -> pop()
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { state ->
        val hub = SettingsHubs.byId(state.substringBefore('/'))
        val page = state.substringAfter('/', "").split('/').lastOrNull { it.isNotEmpty() }?.let { SettingsPage.valueOf(it) }
        val listState = rememberLazyListState()
        if (sidebar) TrackTopBar(listState, Color.Black)
        val backLabel = when {
            page != null -> SettingsNav.pages.getOrNull(SettingsNav.pages.size - 2)?.title ?: hub.title
            !sidebar -> "Settings"
            else -> null
        }
        SettingsList(listState, horizontal = if (sidebar) 28.dp else 16.dp) {
            item("header") {
                Column(Modifier.widthIn(max = 760.dp)) {
                    if (backLabel != null && (page != null || !sidebar)) {
                        BackButton(backLabel) { SettingsNav.back() }
                    }
                    Text(
                        page?.title ?: hub.title,
                        style = ConvxType.LargeTitle,
                        color = ConvxColors.Label,
                        modifier = Modifier.padding(start = 4.dp, top = if (backLabel != null && (page != null || !sidebar)) 14.dp else 4.dp, bottom = 20.dp),
                    )
                }
            }
            item("body") {
                Column(Modifier.widthIn(max = 760.dp)) {
                    if (page != null) PageContent(page) else HubContent(hub)
                }
            }
        }
    }
}

private fun push(): ContentTransform =
    (slideInHorizontally(tween(320)) { it } + fadeIn(tween(160))) togetherWith
        (slideOutHorizontally(tween(320)) { -it / 4 } + fadeOut(tween(200)))

private fun pop(): ContentTransform =
    (slideInHorizontally(tween(320)) { -it / 4 } + fadeIn(tween(200))) togetherWith
        (slideOutHorizontally(tween(320)) { it } + fadeOut(tween(160)))

/** The phone's back button: a drop of glass with a chevron, naming where it goes on hover. */
@Composable
private fun BackButton(label: String, onClick: () -> Unit) {
    GlassCircleButton(onClick, Modifier.padding(start = 2.dp, top = 4.dp), size = 40.dp, tooltip = label) {
        ConvxIcon("chevron_leftpx", size = 20.dp)
    }
}

@Composable
private fun HubContent(hub: SettingsHub) {
    when (hub.id) {
        "player" -> PlayerHub()
        "playback" -> PlaybackHub()
        "appearance" -> AppearanceHub()
        "library" -> LibraryHub()
        "accounts" -> AccountsHub()
        "privacy" -> PrivacyHub()
        else -> HelpHub()
    }
}

@Composable
private fun PageContent(page: SettingsPage) {
    when (page) {
        SettingsPage.PlayerDesign -> PlayerDesignSettings()
        SettingsPage.ControlsLayout -> ControlsLayoutSettings()
        SettingsPage.CustomPlayerIcons -> PlayerIconsSettings()
        SettingsPage.LyricsStyle -> LyricsStyleSettings()
        SettingsPage.LyricsEffects -> LyricsEffectsSettings()
        SettingsPage.LyricsText -> LyricsTextSettings()
        SettingsPage.LyricsSources -> LyricsSourcesSettings()
        SettingsPage.Romanization -> RomanizationSettings()
        SettingsPage.AiTranslation -> AiTranslationSettings()
        SettingsPage.Spotify -> SpotifySettings()
        SettingsPage.Discord -> DiscordSettings()
        SettingsPage.LiquidGlass -> LiquidGlassSettings()
        SettingsPage.Presets -> PresetsSettings()
        SettingsPage.Font -> AppFontSettings()
        SettingsPage.LayoutHome -> LayoutHomeSettings()
        SettingsPage.HomeFeedOrder -> HomeFeedOrderSettings()
        SettingsPage.Equalizer -> EqualizerPage()
        SettingsPage.QualitySound -> QualitySoundSettings()
        SettingsPage.Transitions -> SongTransitionsSettings()
        SettingsPage.Queue -> QueueSettings()
        SettingsPage.Output -> OutputSettings()
        SettingsPage.YouTubeMusic -> YouTubeAccountSettings()
        SettingsPage.LastFm -> LastFmSettings()
        SettingsPage.ListenBrainz -> ListenBrainzSettings()
        SettingsPage.Network -> NetworkSettings()
        SettingsPage.LocalFolders -> LocalFoldersSettings()
        SettingsPage.Privacy -> PrivacySettings()
        SettingsPage.Storage -> StorageSettings()
        SettingsPage.Backup -> BackupSettings()
        SettingsPage.CrashReport -> CrashReportPage()
        SettingsPage.Updates -> UpdatesPage()
    }
}

/** The gap between two sections of a page. */
@Composable
fun SectionGap() = Spacer(Modifier.height(28.dp))
