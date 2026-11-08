/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** Every place the content area can show. */
sealed interface Screen {
    data object Home : Screen
    /** Disc Studio: the CD and vinyl designs you make for your playlists. */
    data object DiscStudio : Screen
    data object Search : Screen
    data class SearchResults(val query: String) : Screen
    data object Explore : Screen
    data object Library : Screen
    data object Liked : Screen
    data object LocalFiles : Screen
    data object Downloads : Screen
    data object History : Screen
    data object Stats : Screen
    /** Your profile, Spotify-style: picture, name, top artists and songs, playlists. */
    data object Profile : Screen
    data object MyTop : Screen
    data object Cached : Screen
    data object Uploaded : Screen
    data object Settings : Screen
    data object Party : Screen
    /** The phone's Radio tab, and one of its stations. */
    data object Radio : Screen
    data class RadioStation(val seed: com.convx.desktop.data.Song) : Screen
    /** The phone's Recognize Music and the songs it found. */
    data object Recognition : Screen
    data object RecognitionHistory : Screen
    data class Album(val browseId: String) : Screen
    data class Artist(val browseId: String) : Screen
    data class Playlist(val id: String) : Screen
    data class LocalPlaylist(val id: String) : Screen
    data class SpotifyPlaylist(val id: String) : Screen
    data object SpotifyLiked : Screen
    data class Browse(val browseId: String, val params: String? = null, val title: String? = null) : Screen
    data class ArtistItems(val browseId: String, val params: String?, val title: String) : Screen
    /** The phone's AI DJ Mix, from a song or from nothing. */
    data class AiDj(val seed: com.convx.desktop.data.Song? = null, val concert: Boolean = false) : Screen
}

private var nextEntryKey = 0L

data class Entry(val screen: Screen, val key: Long = nextEntryKey++)

/**
 * Browser-style history: back and forward stacks, like Spotify's arrows. Each entry
 * has a key so its scroll position survives going back to it.
 */
@Stable
class Navigator(start: Screen = Screen.Home) {
    val backStack = mutableStateListOf(Entry(start))
    private val forwardStack = mutableStateListOf<Entry>()

    /** Keys whose saved state can be dropped; drained by the host. */
    val discarded = mutableStateListOf<Long>()

    val current: Entry get() = backStack.last()
    val canGoBack by derivedStateOf { backStack.size > 1 }
    val canGoForward by derivedStateOf { forwardStack.isNotEmpty() }

    /** Text in the top bar's search field; lives here so every screen can read it. */
    var searchQuery by mutableStateOf("")

    fun navigate(screen: Screen) {
        // Going somewhere from the full player's menus means seeing it, so the player steps aside.
        UiState.fullPlayer = false
        if (current.screen == screen) return
        // Replace transient search screens instead of stacking one per keystroke.
        if (screen is Screen.SearchResults && current.screen is Screen.SearchResults) {
            discarded += backStack.removeAt(backStack.lastIndex).key
        }
        backStack += Entry(screen)
        discarded += forwardStack.map { it.key }
        forwardStack.clear()
    }

    /** Top-level destinations from the top bar reset nothing, they just go there. */
    fun openRoot(screen: Screen) = navigate(screen)

    /**
     * Moves between the pages of one section (the strip's views) without stacking an entry for
     * each, so Back leaves the section rather than stepping through every view you looked at.
     */
    fun switchTo(screen: Screen) {
        if (current.screen == screen) return
        val here = current.screen.section()
        if (here == null || here != screen.section()) return navigate(screen)
        discarded += backStack.removeAt(backStack.lastIndex).key
        backStack += Entry(screen)
        discarded += forwardStack.map { it.key }
        forwardStack.clear()
    }

    fun back() {
        if (backStack.size <= 1) return
        forwardStack += backStack.removeAt(backStack.lastIndex)
    }

    fun forward() {
        if (forwardStack.isEmpty()) return
        backStack += forwardStack.removeAt(forwardStack.lastIndex)
    }
}

/** The phone's default open tab, by its id in Settings. */
fun startScreen(tab: String): Screen = when (tab) {
    "search" -> Screen.Search
    "library" -> Screen.Library
    else -> Screen.Home
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }
