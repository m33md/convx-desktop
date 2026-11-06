/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.Settings
import com.convx.desktop.data.Song
import com.convx.desktop.data.toItem
import com.convx.desktop.data.toSong
import com.convx.desktop.integrations.youtube.YouTubeAccount
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.CompactSongRow
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.Chip
import com.convx.desktop.ui.components.Gutter
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.ItemActions
import com.convx.desktop.ui.components.ItemShelf
import com.convx.desktop.ui.components.LoadingState
import com.convx.desktop.ui.components.LocalPageInsets
import com.convx.desktop.ui.components.LocalPlayback
import com.convx.desktop.ui.components.MessageState
import com.convx.desktop.ui.components.PlayAllButton
import com.convx.desktop.ui.components.SectionHeader
import com.convx.desktop.ui.components.Shelf
import com.convx.desktop.ui.components.SongShelf
import com.convx.desktop.ui.components.TopPickCard
import com.convx.desktop.ui.components.bleedStart
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.glass.GlassCircleButton
import com.convx.desktop.ui.screens.settings.SettingsNav
import com.convx.desktop.ui.screens.settings.SettingsPage
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.theme.ConvxType
import com.music.innertube.YouTube
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import com.music.innertube.models.filterExplicit
import com.music.innertube.models.filterVideoSongs
import com.music.innertube.pages.ChartsPage
import com.music.innertube.pages.HomePage
import com.music.innertube.pages.MoodAndGenres
import java.util.Calendar

/** Applies the hide-explicit / hide-videos settings, as the phone does to every feed. */
fun <T : YTItem> List<T>.filtered(settings: Settings): List<T> =
    filterExplicit(settings.hideExplicit).filterVideoSongs(settings.hideVideos)

/** True when a shelf is mostly songs and long enough for the four-deep list columns. */
fun List<YTItem>.isSongShelf(): Boolean {
    val songs = count { it is SongItem }
    return songs >= 8 && songs * 2 >= size
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
fun HomeScreen() {
    val navigator = LocalNavigator.current
    val settings by AppSettings.state.collectAsState()
    val library by Library.state.collectAsState()
    val account by YouTubeAccount.state.collectAsState()
    val feed by HomeFeed.state.collectAsState()
    LaunchedEffect(Unit) { HomeFeed.load() }
    // The phone's mood chips, in a row under the greeting.
    val selectedChip = UiState.homeChip

    val charts = rememberPage("charts") { YouTube.getChartsPage().getOrThrow() }
    val explore = rememberPage("explore") { YouTube.explore().getOrThrow() }
    val chips = if (settings.homeMoodFilters) feed.homePage?.chips.orEmpty().filter { it.endpoint?.browseId?.isNotBlank() == true } else emptyList()
    val chipTitles = chips.map { it.title }
    LaunchedEffect(chipTitles) {
        UiState.homeChips = chipTitles
        if (selectedChip != null && selectedChip !in chipTitles) UiState.homeChip = null
    }
    val chipPage = selectedChip?.let { title ->
        val chip = chips.firstOrNull { it.title == title }
        rememberPage("home-chip-$title") {
            val endpoint = chip?.endpoint ?: error("This filter isn't available any more")
            YouTube.home(params = endpoint.params, browseId = endpoint.browseId).getOrThrow()
        }
    }

    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        PageList(state = listState) {
            item(key = "greeting") {
                Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(greeting(), style = ConvxType.LargeTitle, modifier = Modifier.weight(1f))
                    // The phone's pull to refresh: new picks, a new order.
                    if (feed.loading) {
                        CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f), strokeWidth = 2.dp, modifier = Modifier.padding(8.dp).size(20.dp))
                    } else {
                        IconButton("refresh", {
                            HomeFeed.load(force = true)
                            charts.retry()
                            explore.retry()
                        }, size = 36.dp, iconSize = 20.dp)
                    }
                }
            }

            if (chipTitles.isNotEmpty()) {
                item(key = "chips") {
                    val bleed = LocalPageInsets.current.start
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(start = bleed + Gutter, end = Gutter, top = 4.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.bleedStart(bleed).fillMaxWidth(),
                    ) {
                        item(key = "all") { Chip("All", selectedChip == null, { UiState.homeChip = null }) }
                        items(chipTitles, key = { it }) { title ->
                            Chip(title, selectedChip == title, { UiState.homeChip = if (selectedChip == title) null else title })
                        }
                    }
                }
            }

            // Apple Music's "Transfer music" card, pointed at the phone's one import: signing in to YouTube Music.
            if (selectedChip == null && account !is YouTubeAccount.State.SignedIn && !settings.homeSignInCardDismissed) {
                item(key = "sign_in_card") {
                    HomeSignInCard(
                        onGetStarted = {
                            navigator.navigate(Screen.Settings)
                            SettingsNav.push(SettingsPage.YouTubeMusic)
                        },
                        onDismiss = { AppSettings.update { it.copy(homeSignInCardDismissed = true) } },
                        modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                    )
                }
            }

            if (chipPage != null) {
                val page = chipPage.value
                when {
                    page != null -> chipSections(page.sections, settings)
                    chipPage.error != null -> item { MessageState("Couldn't load this mood", friendlyError(chipPage.error), actionText = "Try again", onAction = { chipPage.retry() }) }
                    else -> item { LoadingState() }
                }
                return@PageList
            }

            val chartsPage = charts.value
            val explorePage = explore.value
            val topPicks = chartsPage?.sections?.firstOrNull { it.chartType == ChartsPage.ChartType.TOP }?.items.orEmpty().filtered(settings)
            val trending = chartsPage?.sections?.firstOrNull { it.chartType == ChartsPage.ChartType.TRENDING }?.items.orEmpty().filtered(settings)
            val newReleases = explorePage?.newReleaseAlbums.orEmpty().filtered(settings)
            val heroItems = topPicks.ifEmpty { trending }.ifEmpty { newReleases }
            if (heroItems.isNotEmpty()) {
                item(key = "top_picks") {
                    Shelf("Top Picks for You", spacing = 12.dp, onTitleClick = { navigator.navigate(Screen.Browse("FEmusic_charts", null, "Charts")) }) {
                        items(heroItems.distinctBy { it.id }, key = { it.id }) { TopPickCard(it, Modifier.padding(start = 10.dp, top = 6.dp, bottom = 24.dp)) }
                    }
                }
            }
            if (heroItems !== newReleases && newReleases.isNotEmpty()) {
                item(key = "new_releases") {
                    ItemShelf("New Releases", newReleases, onTitleClick = { navigator.navigate(Screen.Browse("FEmusic_new_releases_albums", null, "New Releases")) })
                    Spacer(Modifier.height(20.dp))
                }
            }
            if (topPicks.isNotEmpty() && trending.isNotEmpty()) {
                item(key = "trending") {
                    TrendingShelf(trending)
                    Spacer(Modifier.height(20.dp))
                }
            }

            // Then the phone's feed, in its order, the order you arranged, or shuffled.
            val speedDial = HomeFeed.speedDial(feed, library.speedDial)
            val moods = explorePage?.moodAndGenres?.distinctBy { it.title }
            val sections = buildHomeSections(feed, settings, pinsShown = speedDial.isNotEmpty(), hasMoods = moods != null)
            sections.forEach { section ->
                item(key = section.id) {
                    HomeSectionView(section, feed, speedDial, moods.orEmpty(), account)
                }
            }

            when {
                feed.homePage != null || sections.isNotEmpty() -> Unit
                feed.homeError != null && chartsPage == null -> item(key = "offline") {
                    MessageState(
                        "Couldn't reach YouTube Music",
                        friendlyError(feed.homeError),
                        icon = "offline",
                        actionText = "Try again",
                        onAction = {
                            HomeFeed.load(force = true)
                            charts.retry()
                            explore.retry()
                        },
                    )
                }
                feed.loading -> item(key = "loading") { LoadingState() }
                else -> Unit
            }
        }

        // The phone's Home shuffle: something at random from your feed, as a radio for a song, with
        // Recognize Music's button above it.
        val canShuffle = feed.quickPicks?.isNotEmpty() == true || feed.keepListening.isNotEmpty() || feed.homePage?.sections?.isNotEmpty() == true
        AnimatedVisibility(
            visible = settings.showHomeShuffle && selectedChip == null && canShuffle && listState.isScrollingUp(),
            enter = slideInVertically { it * 2 },
            exit = slideOutVertically { it * 2 },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = Gutter, bottom = LocalPageInsets.current.bottom + 16.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GlassCircleButton(onClick = { navigator.navigate(Screen.Recognition) }, tooltip = "Recognize music") {
                    ConvxIcon("mic", tint = Color.White, size = 20.dp)
                }
                Spacer(Modifier.height(12.dp))
                GlassCircleButton(
                    onClick = {
                        when (val pick = HomeFeed.randomItem(feed)) {
                            is SongItem -> PlayerController.startRadio(pick.toSong())
                            null -> Unit
                            else -> ItemActions.play(pick)
                        }
                    },
                    size = 56.dp,
                    tooltip = "Shuffle",
                ) {
                    ConvxIcon("shuffle", tint = Color.White, size = 24.dp)
                }
            }
        }
    }
}

/** One of the phone's Home sections, drawn as the phone draws it. */
@Composable
private fun HomeSectionView(
    section: HomeSection,
    feed: HomeFeedState,
    speedDial: List<YTItem>,
    moods: List<MoodAndGenres.Item>,
    account: YouTubeAccount.State,
) {
    val navigator = LocalNavigator.current
    val playback = LocalPlayback.current
    fun playOrToggle(songs: List<Song>, index: Int, title: String) {
        if (songs[index].id == playback.currentId) PlayerController.togglePlay()
        else PlayerController.playSongs(songs, index, title)
    }
    // Mood tiles bring their own space below.
    Column(Modifier.fillMaxWidth().padding(bottom = if (section == HomeSection.MoodAndGenres) 0.dp else 20.dp)) {
        when (section) {
            HomeSection.Hero -> {
                val picks = feed.quickPicks.orEmpty()
                val top = picks.firstOrNull() ?: return@Column
                HomeHeroCard(
                    title = top.title,
                    subtitle = top.artists.joinToString { it.name },
                    thumbnail = top.thumbnail,
                    onPlay = { PlayerController.playSongs(picks, 0, top.title) },
                    onShuffle = { PlayerController.playSongs(picks.shuffled(), 0, top.title) },
                    onClick = { PlayerController.playSongs(listOf(top), 0, top.title) },
                    modifier = Modifier.padding(horizontal = Gutter),
                )
            }
            HomeSection.SpeedDial -> ListShelf(
                "Speed dial",
                speedDial.map { item -> ListEntry.Item(item) { ItemActions.open(item, navigator) } } +
                    // At the tail, as on the phone: for when nothing here appeals.
                    ListEntry.Action("speed_dial_randomize", "shuffle", "Surprise me", "Play something at random") {
                        HomeFeed.randomItem(feed)?.let { ItemActions.open(it, navigator) }
                    },
            )
            HomeSection.QuickPicks -> {
                val picks = feed.quickPicks.orEmpty().distinctBy { it.id }
                ListShelf(
                    "Quick picks",
                    // A pick starts a radio from it; Play all plays them in order.
                    picks.map { song -> ListEntry.Item(song.toItem()) { PlayerController.startRadio(song) } },
                    onPlayAll = { PlayerController.playSongs(picks, 0, "Quick picks") },
                )
            }
            HomeSection.FromTheCommunity -> ItemShelf("From the community", feed.communityPlaylists.orEmpty())
            HomeSection.DailyDiscover -> {
                val picks = feed.dailyDiscover.orEmpty().distinctBy { it.id }
                Shelf(
                    "Your daily discover",
                    spacing = 12.dp,
                    trailing = { PlayAllButton { PlayerController.playSongs(picks.map { it.toSong() }, 0, "Your daily discover") } },
                ) {
                    items(picks, key = { it.id }) { TopPickCard(it, Modifier.padding(start = 10.dp, top = 6.dp, bottom = 24.dp)) }
                }
            }
            HomeSection.ListenAgain -> feed.listenAgain?.let { RemoteSection(it, RemoteStyle.Compact) }
            HomeSection.TopSongs -> {
                val songs = feed.topSongs.orEmpty().distinctBy { it.id }.take(10)
                Shelf(
                    "Most played songs",
                    spacing = 12.dp,
                    onTitleClick = { navigator.navigate(Screen.Stats) },
                    trailing = { PlayAllButton { PlayerController.playSongs(songs, 0, "Most played songs") } },
                ) {
                    itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                        TopPickCard(song.toItem(), Modifier.padding(start = 10.dp, top = 6.dp, bottom = 24.dp)) { playOrToggle(songs, index, "Most played songs") }
                    }
                }
            }
            HomeSection.AccountPlaylists -> {
                val profile = (account as? YouTubeAccount.State.SignedIn)?.profile
                ItemShelf(
                    profile?.name ?: "YouTube Music",
                    feed.accountPlaylists.orEmpty(),
                    subtitle = "Your YouTube playlists",
                    // Blank shows the person placeholder, as the phone does without a picture.
                    thumbnail = profile?.thumbnail.orEmpty(),
                    roundThumbnail = true,
                    onTitleClick = { navigator.navigate(Screen.Library) },
                )
            }
            HomeSection.ForgottenFavorites -> {
                val songs = feed.forgottenFavorites.orEmpty().distinctBy { it.id }.take(8)
                SectionHeader(
                    "Forgotten favorites",
                    Modifier.padding(horizontal = Gutter),
                    trailing = { PlayAllButton { PlayerController.playSongs(songs, 0, "Forgotten favorites") } },
                )
                Spacer(Modifier.height(8.dp))
                // The phone's eight rows, in two columns of four at this width.
                Row(Modifier.fillMaxWidth().padding(horizontal = Gutter - 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    songs.withIndex().chunked(4).forEach { column ->
                        Column(Modifier.weight(1f)) {
                            column.forEach { (index, song) -> CompactSongRow(song, { playOrToggle(songs, index, "Forgotten favorites") }, showLiked = !AppSettings.state.collectAsState().value.hideHomeFavoriteIcon) }
                        }
                    }
                    if (songs.size <= 4) Spacer(Modifier.weight(1f))
                }
            }
            is HomeSection.SimilarRecommendation -> {
                val recommendation = feed.similar?.getOrNull(section.index) ?: return@Column
                val seed = recommendation.seed
                ItemShelf(
                    seed.title,
                    recommendation.items,
                    subtitle = "Similar to",
                    thumbnail = seed.thumbnail,
                    roundThumbnail = seed is SimilarSeed.OfArtist,
                    onTitleClick = when (seed) {
                        is SimilarSeed.OfArtist -> ({ navigator.navigate(Screen.Artist(seed.id)) })
                        is SimilarSeed.OfAlbum -> ({ navigator.navigate(Screen.Album(seed.album.id)) })
                        is SimilarSeed.OfSong -> seed.song.album?.let { album -> { navigator.navigate(Screen.Album(album.id)) } }
                    },
                )
            }
            is HomeSection.HomePageSection -> feed.homePage?.sections?.getOrNull(section.index)?.let { RemoteSection(it) }
            HomeSection.MoodAndGenres -> MoodGrid(
                "Mood and Genres",
                moods,
                onTitleClick = { navigator.navigate(Screen.Browse("FEmusic_moods_and_genres", null, "Mood and Genres")) },
            )
        }
    }
}

/** How the phone draws one of YouTube Music's shelves. */
private enum class RemoteStyle { Hero, Compact, List }

/**
 * One of YouTube Music's own shelves: its label, title and picture, Play all when it has songs,
 * and songs as list columns, the rest as cards, as the phone draws them.
 */
@Composable
private fun RemoteSection(section: HomePage.Section, style: RemoteStyle? = null) {
    val navigator = LocalNavigator.current
    val settings by AppSettings.state.collectAsState()
    val items = section.items.filtered(settings).distinctBy { it.id }
    if (items.isEmpty()) return
    val songs = items.filterIsInstance<SongItem>().map { it.toSong() }
    val onTitle = section.endpoint?.let { endpoint ->
        {
            if (endpoint.browseId == "FEmusic_moods_and_genres") navigator.navigate(Screen.Browse(endpoint.browseId, null, section.title))
            else navigator.navigate(Screen.Browse(endpoint.browseId, endpoint.params, section.title))
        }
    }
    val onPlayAll: (() -> Unit)? = if (songs.isNotEmpty()) {
        { PlayerController.playSongs(songs, 0, section.title) }
    } else {
        null
    }
    val round = section.endpoint?.isArtistEndpoint == true
    when (style ?: if (section.isListenAgain()) RemoteStyle.Compact else if (items.isSongShelf()) RemoteStyle.List else RemoteStyle.Compact) {
        RemoteStyle.List -> ListShelf(
            section.title,
            items.map { item -> ListEntry.Item(item) { ItemActions.open(item, navigator) } },
            subtitle = section.label,
            onTitleClick = onTitle,
            thumbnail = section.thumbnail,
            roundThumbnail = round,
            onPlayAll = onPlayAll,
        )
        RemoteStyle.Hero -> Shelf(
            section.title,
            subtitle = section.label,
            onTitleClick = onTitle,
            spacing = 12.dp,
            trailing = if (onPlayAll != null) ({ PlayAllButton(onPlayAll) }) else null,
            thumbnail = section.thumbnail,
            roundThumbnail = round,
        ) {
            items(items, key = { it.id }) { TopPickCard(it, Modifier.padding(start = 10.dp, top = 6.dp, bottom = 24.dp)) }
        }
        RemoteStyle.Compact -> ItemShelf(
            section.title,
            items,
            subtitle = section.label,
            onTitleClick = onTitle,
            thumbnail = section.thumbnail,
            roundThumbnail = round,
            onPlayAll = onPlayAll,
        )
    }
}

/** A mood's own feed, with the phone's mix of layouts so it doesn't read as one row repeated. */
private fun LazyListScope.chipSections(sections: List<HomePage.Section>, settings: Settings) {
    var cardShelves = 0
    sections.forEachIndexed { index, section ->
        val items = section.items.filtered(settings)
        if (items.isEmpty()) return@forEachIndexed
        val style = when {
            section.isListenAgain() -> RemoteStyle.Compact
            items.isSongShelf() -> RemoteStyle.List
            index == 0 -> RemoteStyle.Hero
            items.all { it is ArtistItem } -> RemoteStyle.Compact
            // The phone's large spotlight cards are this card here.
            cardShelves++ % 2 == 0 -> RemoteStyle.Hero
            else -> RemoteStyle.Compact
        }
        item(key = "chip_${index}_${section.title}") {
            Column(Modifier.padding(bottom = 20.dp)) { RemoteSection(section, style) }
        }
    }
}

/** Trending as a numbered chart list, two columns deep. */
@Composable
private fun TrendingShelf(items: List<YTItem>) {
    val songs = items.filterIsInstance<SongItem>().map { it.toSong() }
    if (songs.size >= 4) {
        SongShelf("Trending", songs.take(20)) { i -> PlayerController.playSongs(songs, i, "Trending") }
    } else {
        ItemShelf("Trending", items)
    }
}

