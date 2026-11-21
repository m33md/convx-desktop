/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.studio

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.LibraryData
import com.convx.desktop.data.Settings
import com.convx.desktop.data.Song
import com.convx.desktop.player.Downloads
import com.convx.desktop.ui.components.MenuDivider
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.screens.TopFilter
import com.convx.desktop.ui.screens.cachedSongs
import com.convx.desktop.ui.screens.myTopSongs
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType

/**
 * Every playlist a disc design can go on: your own, the YouTube Music ones you saved, and the auto
 * playlists (Liked Songs, My top, Downloaded, Cached, Uploaded). Your own keep the design on the
 * playlist itself; the others by a key of their own in Library.worn, since they live on YouTube
 * or are made up as they're shown. A key never looks like one of your playlists' ids.
 */
object DiscTargets {
    const val Liked = "auto:liked"
    const val Top = "auto:top"
    const val Downloaded = "auto:downloaded"
    const val Cached = "auto:cached"
    const val Uploaded = "auto:uploaded"

    /** A saved YouTube Music playlist's key, the same with or without YouTube's "VL". */
    fun youTube(id: String) = "yt:" + id.removePrefix("VL")

    enum class Group(val label: String) { Yours("Your playlists"), YouTube("YouTube Music"), Auto("Auto playlists") }

    data class Target(val id: String, val name: String, val group: Group, val design: String?, val screen: Screen)

    fun isYours(id: String) = Library.playlist(id) != null

    /** All of them, yours first, as the "Use on" menus list them. Auto playlists show whether or not they're switched on. */
    fun all(library: LibraryData = Library.value, settings: Settings = AppSettings.value): List<Target> = buildList {
        library.localPlaylists.forEach { add(Target(it.id, it.name, Group.Yours, it.design, Screen.LocalPlaylist(it.id))) }
        library.playlists.forEach { p ->
            val key = youTube(p.id)
            add(Target(key, p.title, Group.YouTube, library.worn[key]?.design, Screen.Playlist(p.id)))
        }
        auto(settings).forEach { (key, name, screen) -> add(Target(key, name, Group.Auto, library.worn[key]?.design, screen)) }
    }

    private fun auto(settings: Settings) = listOf(
        Triple(Liked, "Liked Songs", Screen.Liked),
        Triple(Top, "My top ${settings.topSize}", Screen.MyTop),
        Triple(Downloaded, "Downloaded", Screen.Downloads),
        Triple(Cached, "Cached", Screen.Cached),
        Triple(Uploaded, "Uploaded", Screen.Uploaded),
    )

    fun find(id: String): Target? = all().firstOrNull { it.id == id }

    fun name(id: String): String? = find(id)?.name

    /** The design a playlist wears, if any. */
    fun design(id: String, library: LibraryData = Library.value): String? =
        library.localPlaylists.firstOrNull { it.id == id }?.design ?: library.worn[id]?.design

    /** The playlists wearing [design]: yours by id, the others by key. */
    fun wearing(design: String, library: LibraryData = Library.value): List<String> =
        library.localPlaylists.filter { it.design == design }.map { it.id } +
            library.worn.filterValues { it.design == design }.keys

    /** The songs whose covers a design for [id] can use, as far as they're known on this PC. */
    fun songs(id: String, library: LibraryData = Library.value): List<Song> = when (id) {
        Liked -> library.liked.map { it.song }
        Top -> myTopSongs(TopFilter.AllTime).map { it.value }
        Downloaded -> Downloads.done.value.values.sortedByDescending { it.at }.map { it.song }
        Cached -> cachedSongs(library)
        Uploaded -> library.uploaded
        else -> library.localPlaylists.firstOrNull { it.id == id }?.songs.orEmpty()
    }
}

/**
 * The "Use on a playlist" menu's rows: every playlist a design can go on, in groups, the ones
 * already wearing [design] ticked.
 */
@Composable
fun DiscTargetItems(design: String?, onPick: (DiscTargets.Target) -> Unit) {
    val library by Library.state.collectAsState()
    val settings by AppSettings.state.collectAsState()
    val targets = remember(library, settings) { DiscTargets.all(library, settings) }
    var first = true
    DiscTargets.Group.entries.forEach { group ->
        val inGroup = targets.filter { it.group == group }
        if (inGroup.isEmpty()) return@forEach
        if (!first) MenuDivider()
        first = false
        Text(group.label, style = ConvxType.Caption, color = ConvxColors.SecondaryLabel, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp))
        inGroup.forEach { target ->
            val icon = when {
                design != null && target.design == design -> "check"
                group == DiscTargets.Group.Auto -> "sparks"
                else -> "queue_music"
            }
            MenuItem(target.name, icon) { onPick(target) }
        }
    }
}

/**
 * "Design disc" for a playlist that isn't yours (a DiscTargets key): opens Disc Studio for it, and
 * once it wears a design, offers to edit it or give the playlist its own cover back.
 */
@Composable
fun DiscCoverMenuItems(key: String, songs: List<Song>? = null, dismiss: () -> Unit) {
    val worn = Library.state.collectAsState().value.worn[key]
    MenuItem(if (worn?.design != null) "Edit disc design" else "Design disc", "disc_studio") {
        dismiss()
        DiscEditor.openFor(key, songs)
    }
    if (worn != null) {
        MenuItem("Remove disc cover", "hide_image") {
            dismiss()
            Library.removeWornCover(key)
        }
    }
}
