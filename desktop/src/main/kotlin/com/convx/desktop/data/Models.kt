/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.data

import com.music.innertube.models.Album
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.Artist
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.YTItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import kotlinx.serialization.Serializable

@Serializable
data class ArtistRef(val id: String? = null, val name: String)

@Serializable
data class AlbumRef(val id: String, val name: String)

/**
 * A track as the desktop app stores and queues it. innertube's own models are not
 * serializable, so everything that reaches the library or the queue becomes one of these.
 */
@Serializable
data class Song(
    val id: String,
    val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val album: AlbumRef? = null,
    /** Seconds. */
    val duration: Int? = null,
    val thumbnail: String? = null,
    val explicit: Boolean = false,
    val isVideo: Boolean = false,
    val setVideoId: String? = null,
) {
    val artistText: String get() = artists.joinToString(", ") { it.name }
}

@Serializable
data class SavedAlbum(
    val browseId: String,
    val playlistId: String? = null,
    val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val year: Int? = null,
    val thumbnail: String? = null,
    val savedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class SavedPlaylist(
    val id: String,
    val title: String,
    val author: String? = null,
    val thumbnail: String? = null,
    val savedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class SavedArtist(
    val id: String,
    val name: String,
    val thumbnail: String? = null,
    val savedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val songs: List<Song> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Its own picture: one chosen on this PC (PlaylistCovers) or a Spotify import's cover. Null shows the CD cover Convx draws (DiscCovers). */
    val cover: String? = null,
    /** Raised by "New cover design", so the drawn CD cover comes out differently. */
    val coverSeed: Int = 0,
    /** The Disc Studio design its [cover] was drawn from (DiscDesigns), so saving the design redraws it. */
    val design: String? = null,
)

@Serializable
data class LikedSong(val song: Song, val likedAt: Long = System.currentTimeMillis())

/**
 * One play in the history, as the phone's event: counted once the song has played for the history
 * duration, then kept up to date with how long it was listened to. Plays from before Convx kept
 * that time have none.
 */
@Serializable
data class PlayEvent(val song: Song, val at: Long = System.currentTimeMillis(), val playedMs: Long = 0L)

@Serializable
data class LibraryData(
    val liked: List<LikedSong> = emptyList(),
    val albums: List<SavedAlbum> = emptyList(),
    val playlists: List<SavedPlaylist> = emptyList(),
    val artists: List<SavedArtist> = emptyList(),
    val localPlaylists: List<LocalPlaylist> = emptyList(),
    val history: List<PlayEvent> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    /** Home's Speed dial pins, newest first. */
    val speedDial: List<SpeedDialEntry> = emptyList(),
    /** Pictures of artists you've played but don't follow, by id, as the phone keeps them for Stats. */
    val artistPictures: Map<String, String> = emptyMap(),
    /** Your YouTube Music uploads, as the account last listed them: the phone's "Uploaded" playlist. */
    val uploaded: List<Song> = emptyList(),
    /** Songs Recognize Music found, newest first: the phone's recognition history. */
    val recognitions: List<Recognition> = emptyList(),
)

/** A song Recognize Music found, with what Shazam said about it, as the phone keeps one. */
@Serializable
data class Recognition(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val coverArt: String? = null,
    val coverArtHq: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val label: String? = null,
    val shazamUrl: String? = null,
    val appleMusicUrl: String? = null,
    val spotifyUrl: String? = null,
    val isrc: String? = null,
    val youtubeVideoId: String? = null,
    val at: Long = System.currentTimeMillis(),
)

/** A Speed dial pin, as the phone keeps one: enough of the item to show it and open it again. */
@Serializable
data class SpeedDialEntry(
    val id: String,
    /** An album's playlist id. */
    val secondaryId: String? = null,
    val title: String,
    /** Artists, comma separated, or a playlist's author. */
    val subtitle: String? = null,
    val thumbnail: String? = null,
    /** SONG, ALBUM, ARTIST or PLAYLIST. */
    val type: String,
    val explicit: Boolean = false,
    val pinnedAt: Long = System.currentTimeMillis(),
)

enum class RepeatMode { OFF, ALL, ONE }

enum class AudioQuality { AUTO, HIGH, LOW }

/** The phone's five player designs, in its order. */
enum class PlayerDesign(val label: String) {
    Card("Card"),
    Vinyl("Vinyl"),
    Clover("Clover"),
    AppleMusic("Apple Music"),
    AppleMusicV2("Apple Music v2"),
    ;

    /** Apple's designs draw Apple's own progress capsule and controls; the rest take a seek bar style. */
    val classic: Boolean get() = this == Card || this == Vinyl || this == Clover
}

/** Apple Music v2's two layouts: the cover as a square, or filling the top and fading out. */
enum class PlayerLook(val label: String) { Squared("Square artwork"), Hero("Full bleed") }

/** The phone's player backgrounds, with its names. */
enum class PlayerBackground(val label: String) {
    Theme("Theme"),
    Gradient("Gradient"),
    Blur("Blur"),
    Glow("Glow"),
    Apple("Apple"),
    LiveMesh("Live mesh"),
    ColourMesh("Colour mesh"),
    Solid("Solid"),
    Custom("Custom"),
    Glass("Glass"),
}

enum class SeekBarStyle(val label: String) { Default("Default"), Wavy("Wavy"), Slim("Slim"), Waveform("Waveform") }

/** The phone's lyrics animation styles, in its order and with its names. */
enum class LyricsStyle(val label: String) {
    Flamingo("Convx Lyrics"),
    None("None"),
    Fade("Fade"),
    Glow("Glow"),
    Slide("Slide"),
    Karaoke("Karaoke"),
    Apple("Apple Music"),
    AppleV2("Apple Music V2 (Letter by Letter)"),
    ChordSync("Chord Sync"),
    ConvxFluid("Convx (Fluid)"),
    LyricsV2("Lyrics V2 (Fluid)"),
    Metro("MetroLyrics"),
    KaraokeApple("Karaoke (Apple)"),
}

enum class LyricsPosition(val label: String) { Left("Left"), Center("Center"), Right("Right") }

/**
 * The phone's Liquid Glass dials (GlassEffectSettings), with its defaults. Colours stay fixed:
 * Convx is red on black, so only how the glass bends, blurs and catches light can change.
 */
@Serializable
data class GlassSettings(
    /** dp of blur under the glass. */
    val blurRadius: Float = 3f,
    /** 0..2; 1 leaves colours as they are, 2 is the phone's richer default. */
    val vibrancy: Float = 2f,
    /** 0..1 of the lens' 48 dp: how far in from the edge the glass bends light. */
    val lensHeight: Float = 0.60f,
    /** 0..1 of 48 dp: how strongly it bends it. */
    val lensAmount: Float = 0.69f,
    val chromaticAberration: Boolean = false,
    val depthEffect: Boolean = false,
    /** How much of the dark tint the glass carries. */
    val surfaceOpacity: Float = 0.50f,
    /** The light rim along the glass' edge. */
    val highlightOpacity: Float = 0.33f,
)

/**
 * One player control's own image: its file in the player icons folder, and whether it takes the
 * control's colour, as a flat silhouette should and a photo shouldn't. [stamp] changes with each
 * pick, as a new pick for a slot is written under the same name.
 */
@Serializable
data class PlayerIconOverride(val file: String, val tint: Boolean = false, val stamp: Long = 0L)

@Serializable
data class Settings(
    val volume: Float = 0.8f,
    val muted: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    /** No longer read: Convx is always red on black. Kept so older settings files still read. */
    val accent: Long? = null,
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    /** Superseded by [ambientWindow]; kept so older settings files still read. */
    val dynamicBackground: Boolean = true,
    /** No longer read, like [accent]: pages never take a cover's colours. */
    val ambientWindow: Boolean = false,
    /** Superseded by [seekBarStyle]; kept so older settings files still read. */
    val wavyProgress: Boolean = true,
    /** The phone's player design sheet, with its defaults: Apple Music v2, square artwork, the cover's edge colours. */
    val playerDesign: PlayerDesign = PlayerDesign.AppleMusicV2,
    val playerLook: PlayerLook = PlayerLook.Squared,
    val playerBackground: PlayerBackground = PlayerBackground.Gradient,
    val seekBarStyle: SeekBarStyle = SeekBarStyle.Default,
    /** ARGB of the Solid background. */
    val playerSolidColor: Long = 0xFF1A1A1A,
    /** ARGB stops and angle (degrees) of the Custom background. */
    val playerGradientStops: List<Long> = listOf(0xFF3A1C71, 0xFFD76D77),
    val playerGradientAngle: Float = 90f,
    val artworkShadow: Boolean = false,
    val showUpNext: Boolean = false,
    val showVolumeBar: Boolean = true,
    val sidebarCollapsed: Boolean = false,
    /** Your Library's pinned items, top first, by [libraryKey]. Liked Songs starts pinned, as on Spotify. */
    val pinnedLibrary: List<String> = listOf("liked"),
    /** Your Library's order: recents, added, alphabetical, creator or custom. */
    val librarySort: String = "recents",
    /** Your Library's Custom order, by [libraryKey]; what isn't in it shows on top. */
    val libraryOrder: List<String> = emptyList(),
    val rightPanel: String? = "nowPlaying",
    val lyricsWordSync: Boolean = true,
    /** The phone's lyric source order by its ids; empty is the phone's default order. */
    val lyricsProviders: List<String> = emptyList(),
    /** Sources switched off in Sources & priority. */
    val lyricsProvidersOff: List<String> = emptyList(),
    // The phone's lyrics style and effects, with its defaults. The size is the full player's;
    // the side panel scales it down. "default" font is the app's own.
    val lyricsStyle: LyricsStyle = LyricsStyle.Flamingo,
    val lyricsPosition: LyricsPosition = LyricsPosition.Left,
    val lyricsAutoScroll: Boolean = true,
    val lyricsSwipeToChange: Boolean = false,
    val lyricsGlow: Boolean = true,
    val lyricsAppleBlur: Boolean = true,
    val lyricsStandardBlur: Boolean = false,
    val lyricsFont: String = "default",
    val lyricsCustomFont: String = "",
    val lyricsTextSize: Float = 34f,
    val lyricsLineSpacing: Float = 1.3f,
    // The phone's Romanization page: every language on, the original on top.
    val lyricsRomanizeOff: List<String> = emptyList(),
    val lyricsRomanizeAsMain: Boolean = false,
    val lyricsRomanizeCyrillicByLine: Boolean = false,
    /** The lyrics menu's karaoke vocal reduction, 0 to 1, as on the phone for every song until turned back down. */
    val vocalReduction: Float = 0f,
    // The phone's AI lyrics translation, with its defaults; the keys themselves are in Secrets.
    val aiProvider: String = "OpenRouter",
    val aiBaseUrl: String = "https://openrouter.ai/api/v1/chat/completions",
    val aiModel: String = "google/gemini-3.1-flash-lite",
    val translateLanguage: String = "en",
    val translateMode: String = "Literal",
    val deeplFormality: String = "default",
    val hideExplicit: Boolean = false,
    val hideVideos: Boolean = false,
    val autoRadio: Boolean = true,
    val country: String = "",
    val language: String = "",
    /** Only read once, to move an old plain-text cookie into Secrets; the account lives there now. */
    val cookie: String? = null,
    /** The phone's "More content": home and browse pages use your account, not just the player. */
    val youtubeMoreContent: Boolean = true,
    /** The phone's "Auto sync with account": likes, albums, artists and playlists follow YouTube Music. */
    val youtubeSync: Boolean = true,
    /** The browser the YouTube Music login opens, by its program's path; null picks Windows' default browser if it can. */
    val signInBrowser: String? = null,
    /** Folders scanned for music files; the Windows Music folder until you choose. */
    val localFolders: List<String> = com.convx.desktop.player.LocalMusic.defaultFolders(),
    val excludedFolders: List<String> = emptyList(),
    val scanLocalOnStart: Boolean = true,
    /** The phone's Network & proxy page; the proxy password lives in Secrets. */
    val ipVersion: String = "IPV4",
    val proxyEnabled: Boolean = false,
    val proxyType: String = "HTTP",
    val proxyUrl: String = "",
    val proxyUsername: String = "",
    /** The phone's Party: the name others see, and this PC's id there. [partyServer] stays only so saved settings still load; the server is built into Party. */
    val partyServer: String = "",
    val partyName: String = "",
    val partyId: String = "",
    /** "Listening to" on Discord, through the Discord app on this PC. Off until connected in Settings. */
    val discordPresence: Boolean = false,
    /** Discord's member list shows the song after "Listening to" instead of the artists. */
    val discordUseDetails: Boolean = false,
    /** The phone's advanced mode: the verb and the two buttons below are only used while it's on. */
    val discordAdvanced: Boolean = false,
    /** listening, playing, watching or competing. */
    val discordActivityType: String = "listening",
    val discordButton1: Boolean = true,
    /** Blank for "Listen on Spotify"; {song_name}, {artist_name} and {album_name} are filled in. */
    val discordButton1Text: String = "",
    val discordButton2: Boolean = true,
    /** Blank for "Visit Convx". */
    val discordButton2Text: String = "",
    /** A Discord application of your own to show the status under; blank uses the phone's ("Metrolist"). */
    val discordApplicationId: String = "",
    /** A Spotify app of your own (developer.spotify.com) to link with, instead of Spotify's shared desktop client. */
    val spotifyClientId: String = "",
    val glass: GlassSettings = GlassSettings(),
    // The phone's Layout & home page and its Arrange home feed, with its defaults.
    /** home, search or library: where Convx opens. */
    val defaultTab: String = "home",
    val homeHeroCard: Boolean = true,
    /** Home's sections shuffle each visit until you arrange them yourself, as on the phone. */
    val homeRandomOrder: Boolean = true,
    val homeSectionOrder: List<String> = emptyList(),
    val homeSectionsHidden: List<String> = emptyList(),
    val homeSignInCardDismissed: Boolean = false,
    /** The mood chips (Energize, Relax...) in Home's strip. */
    val homeMoodFilters: Boolean = true,
    /** The phone's Set quick picks: "quick_picks" from your listening, or "last_listen" from the last song alone. */
    val quickPicksMode: String = "quick_picks",
    // The phone's auto playlists: which ones Your Library shows, and how long My top is.
    val showLikedPlaylist: Boolean = true,
    val showDownloadedPlaylist: Boolean = true,
    val showTopPlaylist: Boolean = true,
    // Off on the desktop, where it puzzled people in Your Library; a new key, so the old saved "on" doesn't bring it back.
    @kotlinx.serialization.SerialName("showCachedInLibrary")
    val showCachedPlaylist: Boolean = false,
    /** Liked Songs' own picture, chosen on this PC (a [PlaylistCovers] name), instead of the red heart tile. */
    val likedSongsCover: String? = null,
    /** Playlists without their own picture get a CD case Convx draws from their songs, instead of a grid of their covers. */
    val discCovers: Boolean = true,
    val showUploadedPlaylist: Boolean = true,
    val topSize: Int = 50,
    // The phone's Controls & layout page, with its defaults where the PC has the same thing.
    /** The phone's "Hide player thumbnail": the Convx mark where the player's cover would be. */
    val hidePlayerArtwork: Boolean = false,
    /** The player's cover corners in dp; -1 keeps each design's own. */
    val playerArtworkCorner: Int = -1,
    /** Menus' corners in dp, the phone's sheet corner radius; -1 keeps the PC's own. */
    val menuCorner: Int = -1,
    val showAudioQualityBadge: Boolean = true,
    /** With lyrics open, clicking the cover beside them plays and pauses. */
    val lyricsArtworkPlayPause: Boolean = false,
    /** The lyrics button opens and closes full-screen lyrics in one click, as the phone's one-tap setting. */
    val oneTapFullscreenLyrics: Boolean = false,
    /** Full-screen lyrics keep a compact player bar at the top; off, the controls go away entirely. */
    val fullscreenLyricsCollapseTop: Boolean = true,
    /** The phone's comments button in the player: the song's YouTube comments beside the cover. */
    val showCommentButton: Boolean = true,
    /** The account button opens a quick popup instead of going straight to Settings. */
    val settingsPopup: Boolean = false,
    /**
     * Your Library's rows show a plain icon instead of each cover. On by default on the phone;
     * off here, as the PC's Your Library is the cover list you asked for.
     */
    val libraryIconsOnly: Boolean = false,
    /** Top bar sections switched off, by their names; the phone's navigation tabs. */
    val hiddenSections: List<String> = emptyList(),
    /** Which of Library's filters it opens on, by name; the phone's default library chip. */
    val defaultLibraryTab: String = "All",
    /** The phone's grid cell size: Big, or Small covers on grid pages and shelves. */
    val gridSmall: Boolean = false,
    /** 1 is Windows' own scale; the phone's display density goes down to 55%. */
    val displayDensity: Float = 1f,
    /** The phone's Reduce live blur: flat colour where hero cards blur, still backgrounds, no zoom. */
    val reduceLiveBlur: Boolean = false,
    /** The shuffle button at the bottom of Home. */
    val showHomeShuffle: Boolean = true,
    /** Hides the heart on liked songs in Home's rows. */
    val hideHomeFavoriteIcon: Boolean = false,
    // The phone's Grid & cards dials. 0 is Auto, as there.
    val gridColumns: Int = 0,
    val gridCardHeight: Int = 0,
    val gridSpacing: Int = 16,
    val homeHeroHeight: Int = 0,
    val homeCardCorner: Int = 0,
    /** The player's controls drawn with your own images, by slot name: the phone's custom player icons. */
    val playerIcons: Map<String, PlayerIconOverride> = emptyMap(),
    /** The phone's DIY: stickers over the player. Their pictures are in the app data's diy_stickers. */
    val diyLayout: com.convx.desktop.ui.player.DiyLayout = com.convx.desktop.ui.player.DiyLayout(),
    /** The phone's app font by its id; Plus Jakarta Sans, its default. */
    val appFont: String = "plus_jakarta_sans",
    /** An imported font (kept with the imported lyrics fonts) used everywhere while this is on. */
    val customFontEnabled: Boolean = false,
    val customFont: String = "",
    val customFontName: String = "",
    val equalizer: com.convx.desktop.audio.EqualizerSettings = com.convx.desktop.audio.EqualizerSettings(),
    /** Evens out loud and quiet songs with YouTube's loudness for each one, as the phone does. */
    val normalizeAudio: Boolean = true,
    val skipSilence: Boolean = false,
    /** The phone's song transitions: each song fades into the next. */
    val crossfade: Boolean = false,
    /** 1..12, Apple's range. */
    val crossfadeSeconds: Float = 5f,
    /** The phone's AutoMix transition style: mixes planned from each song's tempo, beats and key. */
    val autoMix: Boolean = false,
    /** The phone's Creative transitions: reverb, delay and stereo shaping while AutoMix blends. */
    val creativeTransitions: Boolean = false,
    val djReverb: com.convx.desktop.automix.ReverbType = com.convx.desktop.automix.ReverbType.AUTO,
    val djDelay: com.convx.desktop.automix.DelayType = com.convx.desktop.automix.DelayType.AUTO,
    /** The phone's "Automatically check for updates": looks for a newer build at launch. */
    val checkForUpdates: Boolean = true,
    /** The sound input Recognize Music listens with, by its name; null is Windows' default. */
    val recognitionInput: String? = null,
    /** Restore the last queue at launch. */
    val persistentQueue: Boolean = true,
    val rememberShuffleRepeat: Boolean = true,
    val skipOnError: Boolean = true,
    /** The phone's Downloads group: download a song when you like it, and copy downloads to Music\Convx. */
    val autoDownloadOnLike: Boolean = false,
    val saveDownloadsToMusic: Boolean = true,
    /** The phone's Privacy page: stop recording plays or searches, and keep the window out of screenshots. */
    val pauseListenHistory: Boolean = false,
    /** Seconds a song plays before it counts as a listen; the phone's History duration. */
    val historyDurationSeconds: Float = 30f,
    /** The phone's Ask before replacing the queue: keep or clear when something's playing. */
    val askBeforeReplacingQueue: Boolean = false,
    val pauseSearchHistory: Boolean = false,
    val disableScreenshot: Boolean = false,
    /** The phone's Storage page, in MB with its defaults; 0 turns a cache off and -1 (songs only) lifts the cap. */
    val maxSongCacheMb: Int = 1024,
    val maxImageCacheMb: Int = 512,
    /** The sound output by name; null follows Windows' default. */
    val outputDevice: String? = null,
    // Scrobbling, with the phone's defaults. The accounts themselves live in Secrets.
    val lastFmScrobble: Boolean = true,
    val lastFmNowPlaying: Boolean = false,
    val lastFmSendLikes: Boolean = false,
    val listenBrainzScrobble: Boolean = true,
    /** Songs this long or shorter are never scrobbled. */
    val scrobbleMinSeconds: Int = 30,
    /** A song counts after this share of it, or [scrobbleDelaySeconds], whichever comes first. */
    val scrobbleDelayPercent: Float = 0.5f,
    val scrobbleDelaySeconds: Int = 180,
    /** YouTube's guest session id; kept so recommendations stay consistent between launches. */
    val visitorData: String? = null,
    val lastQueue: List<Song> = emptyList(),
    val lastIndex: Int = 0,
    val lastPositionMs: Long = 0,
    val windowWidth: Int = 1440,
    val windowHeight: Int = 900,
    val windowMaximized: Boolean = false,
)

fun SongItem.toSong(): Song = Song(
    id = id,
    title = title,
    artists = artists.map { ArtistRef(it.id, it.name) },
    album = album?.let { AlbumRef(it.id, it.name) },
    duration = duration,
    thumbnail = thumbnail,
    explicit = explicit,
    isVideo = isVideoSong,
    setVideoId = setVideoId,
)

fun AlbumItem.toSaved(): SavedAlbum = SavedAlbum(
    browseId = browseId,
    playlistId = playlistId,
    title = title,
    artists = artists.orEmpty().map { ArtistRef(it.id, it.name) },
    year = year,
    thumbnail = thumbnail,
)

fun PlaylistItem.toSaved(): SavedPlaylist = SavedPlaylist(
    id = id,
    title = title,
    author = author?.name,
    thumbnail = thumbnail,
)

fun ArtistItem.toSaved(): SavedArtist = SavedArtist(id = id, name = title, thumbnail = thumbnail)

/** A song back as YouTube's item, for the shelves and menus that take one. */
fun Song.toItem(): SongItem = SongItem(
    id = id,
    title = title,
    artists = artists.map { Artist(it.name, it.id) },
    album = album?.let { Album(it.name, it.id) },
    duration = duration,
    // Any video type but a song's, so it still reads as a video.
    musicVideoType = if (isVideo) "MUSIC_VIDEO_TYPE_OMV" else null,
    thumbnail = thumbnail.orEmpty(),
    explicit = explicit,
    setVideoId = setVideoId,
)

fun SpeedDialEntry.toItem(): YTItem = when (type) {
    "ALBUM" -> AlbumItem(
        browseId = id,
        playlistId = secondaryId.orEmpty(),
        title = title,
        artists = subtitle?.split(", ")?.map { Artist(it, null) },
        thumbnail = thumbnail.orEmpty(),
        explicit = explicit,
    )
    "ARTIST" -> ArtistItem(id = id, title = title, thumbnail = thumbnail, shuffleEndpoint = null, radioEndpoint = null)
    "PLAYLIST" -> PlaylistItem(
        id = id,
        title = title,
        author = subtitle?.let { Artist(it, null) },
        songCountText = null,
        thumbnail = thumbnail,
        playEndpoint = null,
        shuffleEndpoint = null,
        radioEndpoint = null,
    )
    else -> SongItem(
        id = id,
        title = title,
        artists = subtitle?.split(", ")?.map { Artist(it, null) }.orEmpty(),
        thumbnail = thumbnail.orEmpty(),
        explicit = explicit,
    )
}

fun YTItem.toSpeedDial(): SpeedDialEntry = when (this) {
    is SongItem -> SpeedDialEntry(id, null, title, artists.joinToString(", ") { it.name }, thumbnail, "SONG", explicit)
    is AlbumItem -> SpeedDialEntry(browseId, playlistId, title, artists?.joinToString(", ") { it.name }, thumbnail, "ALBUM", explicit)
    is ArtistItem -> SpeedDialEntry(id, null, title, null, thumbnail, "ARTIST")
    is PlaylistItem -> SpeedDialEntry(id, null, title, author?.name, thumbnail, "PLAYLIST")
}
