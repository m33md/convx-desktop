/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.ui.components.LocalPageInsets
import com.convx.desktop.ui.screens.settings.SettingsScreen
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.TrackTopBar
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun ScreenHost(screen: Screen) {
    when (screen) {
        Screen.Home -> HomeScreen()
        Screen.DiscStudio -> com.convx.desktop.ui.studio.DiscStudioScreen()
        Screen.Search -> SearchScreen()
        is Screen.SearchResults -> SearchResultsScreen(screen.query)
        Screen.Explore -> ExploreScreen()
        Screen.Library -> LibraryScreen()
        Screen.Liked -> LikedScreen()
        Screen.LocalFiles -> LocalFilesScreen()
        Screen.Downloads -> DownloadsScreen()
        Screen.History -> HistoryScreen()
        Screen.Stats -> StatsScreen()
        Screen.Profile -> ProfileScreen()
        Screen.MyTop -> MyTopScreen()
        Screen.Cached -> CachedScreen()
        Screen.Uploaded -> UploadedScreen()
        Screen.Settings -> SettingsScreen()
        Screen.Party -> PartyScreen()
        Screen.Radio -> RadioScreen()
        is Screen.RadioStation -> RadioStationScreen(screen.seed)
        Screen.Recognition -> RecognitionScreen()
        Screen.RecognitionHistory -> RecognitionHistoryScreen()
        is Screen.Album -> AlbumScreen(screen.browseId)
        is Screen.Artist -> ArtistScreen(screen.browseId)
        is Screen.Playlist -> PlaylistScreen(screen.id)
        is Screen.LocalPlaylist -> LocalPlaylistScreen(screen.id)
        is Screen.SpotifyPlaylist -> SpotifyPlaylistScreen(screen.id)
        Screen.SpotifyLiked -> SpotifyLikedScreen()
        is Screen.Browse -> BrowseScreen(screen.browseId, screen.params, screen.title)
        is Screen.ArtistItems -> ArtistItemsScreen(screen.browseId, screen.params, screen.title)
        is Screen.AiDj -> AiDjScreen(screen.seed, screen.concert)
    }
}

/**
 * A scrolling page under the top bar, with a scrollbar and the top bar fading in
 * as the page scrolls. [background] draws behind the list (hero gradients).
 */
@Composable
fun PageList(
    state: LazyListState = rememberLazyListState(),
    tint: Color = Color(0xFF161618),
    topPadding: Dp = LocalPageInsets.current.top + 8.dp,
    background: (@Composable BoxScope.() -> Unit)? = null,
    onNearEnd: (() -> Unit)? = null,
    /** On the list itself, such as [com.convx.desktop.ui.components.reorderGestures]. */
    listModifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    TrackTopBar(state, tint)
    if (onNearEnd != null) {
        val callback = rememberUpdatedState(onNearEnd)
        LaunchedEffect(state) {
            snapshotFlow {
                val info = state.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                // Keyed by item count, so a page that grows but is still near its end asks again.
                if (info.totalItemsCount > 0 && last >= info.totalItemsCount - 4) info.totalItemsCount else -1
            }.distinctUntilChanged().filter { it >= 0 }.collect { callback.value() }
        }
    }
    val insets = LocalPageInsets.current
    Box(Modifier.fillMaxSize()) {
        background?.invoke(this)
        // The list spans the whole window and scrolls under the glass; its padding keeps rows clear of it.
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(start = insets.start, end = insets.end, top = topPadding, bottom = insets.bottom + 40.dp),
            modifier = Modifier.fillMaxSize().then(listModifier),
        ) {
            content()
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(state),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(top = insets.top, bottom = insets.bottom + 8.dp, end = insets.end + 2.dp),
        )
    }
}
