/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.Library
import com.convx.desktop.integrations.PlaylistFiles
import com.convx.desktop.integrations.PlaylistImport
import com.convx.desktop.integrations.spotify.SpotifyApi
import com.convx.desktop.integrations.spotify.SpotifyPlaylist
import com.convx.desktop.ui.components.Chip
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.DialogCard
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.shapes.ContinuousRoundedRectangle
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import com.convx.desktop.ui.components.ScrollbarColumn

/** The phone's Spotify import, on the Spotify page once you're connected. */
@Composable
internal fun SpotifyImportSection() {
    val state by PlaylistImport.state.collectAsState()
    SettingsSection(
        "Import",
        footer = "Import your Spotify playlists and Liked Songs into your library. Convx searches YouTube Music for the best " +
            "match of each song and mirrors them to Convx playlists. Importing a playlist again updates it.",
    ) {
        SettingsRow(
            "Import playlists",
            importStatus(state, "Spotify") ?: "Choose which playlists to copy into Convx",
            icon = "playlist_add",
            tint = SystemColors.Green,
            enabled = !PlaylistImport.busy,
            onClick = { spotifyImportDialog() },
        ) { Chevron() }
        if (state is PlaylistImport.State.Matching || state is PlaylistImport.State.Loading) {
            RowDivider()
            ButtonRow("Stop importing", color = SystemColors.Red) { PlaylistImport.cancel() }
        }
    }
}

/** A one-line account of an import from [source], or null when there's nothing to say. */
private fun importStatus(state: PlaylistImport.State, source: String): String? = when (state) {
    is PlaylistImport.State.Loading -> if (state.source == source) "Loading from $source…" else null
    is PlaylistImport.State.Matching -> if (state.source == source) {
        val list = if (state.lists > 1) " (${state.list} of ${state.lists})" else ""
        "Matching ${state.title}$list: ${state.done} of ${state.total} songs"
    } else null
    is PlaylistImport.State.Done -> state.message
    is PlaylistImport.State.Failed -> state.message
    else -> null
}

@Composable
private fun Chevron() = ConvxIcon("chevron_right_px", tint = ConvxColors.TertiaryLabel, size = 14.dp)

/** The phone's SpotifyPlaylistBottomSheet: Liked Songs and every playlist, with Select All. */
private fun spotifyImportDialog() {
    Overlays.showDialog {
        var playlists by remember { mutableStateOf<List<SpotifyPlaylist>?>(null) }
        var liked by remember { mutableStateOf<Int?>(null) }
        var failed by remember { mutableStateOf<String?>(null) }
        var selected by remember { mutableStateOf(setOf<String>()) }
        LaunchedEffect(Unit) {
            val result = SpotifyApi.playlists()
            playlists = result.getOrNull()
            failed = result.exceptionOrNull()?.let { it.message ?: "Spotify didn't answer" }
            liked = SpotifyApi.likedCount().getOrNull()
        }
        val ids = listOf(LikedKey) + playlists.orEmpty().map { it.id }
        DialogCard("Select playlists to import") {
            when {
                failed != null -> Text("Couldn't load your Spotify playlists ($failed). Check your connection and try again.", style = ConvxType.Body, color = SystemColors.Red)
                playlists == null -> Text("Loading your playlists…", style = ConvxType.Body, color = ConvxColors.SecondaryLabel)
                else -> {
                    Text("${playlists!!.size} playlists available", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
                    Spacer(Modifier.height(8.dp))
                    ScrollbarColumn(Modifier.heightIn(max = 380.dp)) {
                        CheckRow("Select All", checked = selected.size == ids.size) {
                            selected = if (selected.size == ids.size) emptySet() else ids.toSet()
                        }
                        RowDivider(PlainInset)
                        CheckRow(
                            "Liked Songs",
                            "Your Spotify Liked Songs" + (liked?.let { " · $it songs" } ?: ""),
                            checked = LikedKey in selected,
                            icon = "favorite",
                            tint = SystemColors.Indigo,
                        ) { selected = selected.toggle(LikedKey) }
                        playlists!!.forEach { playlist ->
                            RowDivider()
                            CheckRow(
                                playlist.name,
                                "${playlist.trackCount} songs · ${playlist.owner}",
                                checked = playlist.id in selected,
                                icon = "queue_music",
                                tint = SystemColors.Green,
                            ) { selected = selected.toggle(playlist.id) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Cancel", { Overlays.dismissDialog() })
                PillButton(if (selected.isEmpty()) "Import" else "Import ${selected.size}", {
                    if (selected.isNotEmpty()) {
                        val sources = buildList {
                            if (LikedKey in selected) add(PlaylistImport.SpotifySource(null))
                            playlists.orEmpty().filter { it.id in selected }.forEach { add(PlaylistImport.SpotifySource(it)) }
                        }
                        PlaylistImport.importSpotify(sources)
                        Overlays.dismissDialog()
                    }
                }, filled = selected.isNotEmpty())
            }
        }
    }
}

private const val LikedKey = "\u0000liked"

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

/** The phone's Apple Music import dialog: a public link or a Music/iTunes XML export. */
internal fun appleMusicImportDialog() {
    if (!PlaylistImport.busy && PlaylistImport.state.value !is PlaylistImport.State.Ready) PlaylistImport.dismiss()
    Overlays.showDialog {
        val state by PlaylistImport.state.collectAsState()
        var url by remember { mutableStateOf("") }
        DialogCard("Import from Apple Music") {
            ImportStages(state, "Apple Music") {
                Text(
                    "Paste a public Apple Music playlist link. Tracks will be matched to YouTube Music.",
                    style = ConvxType.Body,
                    color = ConvxColors.SecondaryLabel,
                )
                Spacer(Modifier.height(12.dp))
                DialogField("Playlist link", url) { url = it }
                (state as? PlaylistImport.State.Failed)?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it.message, style = ConvxType.Subhead, color = SystemColors.Red)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "For a private playlist, export it as XML from Music on Mac, or iTunes or Apple Music on Windows " +
                        "(File, Library, Export Playlist), then import the file here.",
                    style = ConvxType.Subhead,
                    color = ConvxColors.SecondaryLabel,
                )
                Spacer(Modifier.height(10.dp))
                PillButton("Import playlist XML", {
                    pickFile("Import an Apple Music playlist", listOf("xml", "txt"))?.let { PlaylistImport.loadAppleExport(it) }
                }, icon = "playlist_add")
                Spacer(Modifier.height(20.dp))
                DialogButtons {
                    PillButton("Cancel", {
                        PlaylistImport.dismiss()
                        Overlays.dismissDialog()
                    })
                    PillButton("Import playlist", { if (url.isNotBlank()) PlaylistImport.loadAppleLink(url) }, filled = url.isNotBlank())
                }
            }
        }
    }
}

/**
 * What every playlist import goes through once it starts: matching, then where the songs go,
 * then the result. Anything else, the idle or failed state, is [idle]'s to show.
 */
@Composable
private fun ImportStages(state: PlaylistImport.State, source: String, idle: @Composable () -> Unit) {
    val navigator = LocalNavigator.current
    var choosing by remember { mutableStateOf(false) }
    val library by Library.state.collectAsState()
    when (state) {
        is PlaylistImport.State.Loading, is PlaylistImport.State.Matching -> {
            Text(
                when {
                    state is PlaylistImport.State.Matching && state.lists > 1 ->
                        "Matching ${state.title} (${state.list} of ${state.lists}): ${state.done} of ${state.total} songs"
                    state is PlaylistImport.State.Matching -> "Matching tracks: ${state.done} of ${state.total}"
                    else -> "Loading playlist…"
                },
                style = ConvxType.Body,
                color = ConvxColors.Label,
            )
            val running = (state as? PlaylistImport.State.Matching)?.source ?: (state as PlaylistImport.State.Loading).source
            if (running != source) {
                Spacer(Modifier.height(6.dp))
                Text("Another import is running; wait for it to finish.", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
            }
            Spacer(Modifier.height(20.dp))
            DialogButtons {
                PillButton("Hide", { Overlays.dismissDialog() })
                PillButton("Cancel", { PlaylistImport.cancel() })
            }
        }
        is PlaylistImport.State.Ready -> {
            Text(state.title, style = ConvxType.Headline, color = ConvxColors.Label)
            Text("${state.songs.size} tracks matched, ${state.skipped} skipped.", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
            if (choosing) {
                Spacer(Modifier.height(10.dp))
                ScrollbarColumn(Modifier.heightIn(max = 300.dp)) {
                    library.localPlaylists.forEachIndexed { index, playlist ->
                        if (index > 0) RowDivider()
                        SettingsRow(playlist.name, "${playlist.songs.size} songs", icon = "queue_music", tint = SystemColors.Red, onClick = {
                            PlaylistImport.save(playlist.id)
                        })
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            DialogButtons {
                PillButton("Cancel", {
                    PlaylistImport.dismiss()
                    PlaylistImport.cancel()
                    Overlays.dismissDialog()
                })
                if (library.localPlaylists.isNotEmpty() && !choosing) PillButton("Choose playlist", { choosing = true })
                PillButton("Create playlist", { PlaylistImport.save(null) }, filled = state.songs.isNotEmpty())
            }
        }
        is PlaylistImport.State.Done -> {
            Text(state.message, style = ConvxType.Body, color = ConvxColors.Label)
            Spacer(Modifier.height(20.dp))
            DialogButtons {
                PillButton("Done", {
                    PlaylistImport.dismiss()
                    Overlays.dismissDialog()
                })
                state.playlistId?.let { id ->
                    PillButton("Open playlist", {
                        PlaylistImport.dismiss()
                        Overlays.dismissDialog()
                        navigator.navigate(Screen.LocalPlaylist(id))
                    }, filled = true)
                }
            }
        }
        else -> idle()
    }
}

/** A failed file import's reason, and a way out. */
@Composable
private fun FileImportIdle(state: PlaylistImport.State) {
    Text(
        (state as? PlaylistImport.State.Failed)?.message ?: "No songs found in this file.",
        style = ConvxType.Body,
        color = if (state is PlaylistImport.State.Failed) SystemColors.Red else ConvxColors.SecondaryLabel,
    )
    Spacer(Modifier.height(20.dp))
    DialogButtons {
        PillButton("Close", {
            PlaylistImport.dismiss()
            Overlays.dismissDialog()
        })
    }
}

/**
 * The phone's "Import a m3u playlist": its songs are matched to YouTube Music, then go into a new
 * playlist or one you choose.
 */
internal fun importM3uFile() {
    val file = pickFile("Import an M3U playlist", listOf("m3u", "m3u8")) ?: return
    val parsed = runCatching { PlaylistFiles.parseM3u(PlaylistFiles.read(file), file.nameWithoutExtension) }.getOrElse {
        Overlays.toast("Couldn't read ${file.name}", "error")
        return
    }
    if (!PlaylistImport.busy) PlaylistImport.dismiss()
    PlaylistImport.loadFile(parsed)
    Overlays.showDialog {
        val state by PlaylistImport.state.collectAsState()
        DialogCard("Import M3U playlist") {
            ImportStages(state, PlaylistImport.FileSource) { FileImportIdle(state) }
        }
    }
}

/**
 * Exportify's "Export All" zip, or any number of its CSV files: every playlist in them becomes a
 * Convx playlist in one go, with no column mapping, since Exportify's headers name the columns.
 */
internal fun importExportifyFiles() {
    val files = pickFiles("Choose Exportify's zip or CSV files", listOf("zip", "csv"))
    if (files.isEmpty()) return
    val lists = runCatching { PlaylistFiles.readPlaylistFiles(files) }.getOrElse {
        Overlays.toast("Couldn't read those files: ${it.message ?: "unknown error"}", "error")
        return
    }.filter { it.tracks.isNotEmpty() }
    if (lists.isEmpty()) {
        Overlays.toast("No songs found in those files", "error")
        return
    }
    if (PlaylistImport.busy) {
        Overlays.toast("Another import is running; wait for it to finish.", "error")
        return
    }
    PlaylistImport.dismiss()
    PlaylistImport.importFiles(lists)
    Overlays.showDialog {
        val state by PlaylistImport.state.collectAsState()
        DialogCard("Import from Exportify") {
            ImportStages(state, PlaylistImport.ExportifySource) { FileImportIdle(state) }
        }
    }
}

/** Exportify on the Spotify page: the way in without Premium or the shared connection. */
@Composable
internal fun ExportifyImportSection() {
    val state by PlaylistImport.state.collectAsState()
    SettingsSection(
        "Import with Exportify",
        footer = "Works without Premium and without the Spotify connection above. On exportify.net, sign in with Spotify " +
            "and press Export All, then choose the zip it downloads here. Every playlist, Liked Songs included, becomes a " +
            "Convx playlist; importing again updates them.",
    ) {
        SettingsRow(
            "Import Exportify files",
            importStatus(state, PlaylistImport.ExportifySource) ?: "The Export All zip, or one or more CSV files",
            icon = "playlist_add",
            tint = SystemColors.Green,
            enabled = !PlaylistImport.busy,
            onClick = { importExportifyFiles() },
        ) { Chevron() }
        RowDivider()
        LinkRow("Open exportify.net", icon = "link", tint = SystemColors.Gray) { openUrl("https://exportify.net") }
        if ((state as? PlaylistImport.State.Matching)?.source == PlaylistImport.ExportifySource) {
            RowDivider()
            ButtonRow("Stop importing", color = SystemColors.Red) { PlaylistImport.cancel() }
        }
    }
}

/** The phone's "Import a csv playlist", with its column mapping first. */
internal fun importCsvFile() {
    pickFile("Import a CSV playlist", listOf("csv", "tsv", "txt"))?.let(::csvImportDialog)
}

internal fun csvImportDialog(file: File) {
    val text = runCatching { PlaylistFiles.read(file) }.getOrElse {
        Overlays.toast("Couldn't read ${file.name}", "error")
        return
    }
    val preview = PlaylistFiles.previewCsv(text)
    if (preview.rows.isEmpty()) {
        Overlays.toast("${file.name} is empty", "error")
        return
    }
    if (!PlaylistImport.busy) PlaylistImport.dismiss()
    Overlays.showDialog {
        val state by PlaylistImport.state.collectAsState()
        var mapped by remember { mutableStateOf(false) }
        DialogCard(if (mapped) "Import CSV playlist" else "Map CSV columns") {
            if (!mapped) {
                CsvColumns(preview) { mapping ->
                    mapped = true
                    PlaylistImport.loadFile(PlaylistFiles.Parsed(file.nameWithoutExtension, PlaylistFiles.parseCsvTracks(text, mapping)))
                }
            } else {
                ImportStages(state, PlaylistImport.FileSource) { FileImportIdle(state) }
            }
        }
    }
}

private val ArtistTint = SystemColors.Orange
private val TitleTint = SystemColors.Blue
private val LinkTint = SystemColors.Red

/** The phone's CsvColumnMappingDialog: the first rows, then which column is the artist, the title and a YouTube link. */
@Composable
private fun CsvColumns(preview: PlaylistFiles.CsvPreview, onContinue: (PlaylistFiles.CsvMapping) -> Unit) {
    var hasHeader by remember { mutableStateOf(preview.hasHeader) }
    var artist by remember { mutableStateOf(preview.artistColumn) }
    var title by remember { mutableStateOf(preview.titleColumn) }
    var link by remember { mutableStateOf(preview.urlColumn) }
    val header = preview.rows.first()
    fun name(column: Int) = header.getOrNull(column)?.takeIf { hasHeader && it.isNotBlank() }?.take(24) ?: "Col ${column + 1}"

    Text("Choose which columns hold the artist and the song title.", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
    Spacer(Modifier.height(12.dp))
    Column(
        Modifier.fillMaxWidth().clip(ContinuousRoundedRectangle(12.dp)).background(Color.White.copy(alpha = 0.05f))
            .horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
    ) {
        preview.rows.take(5).forEachIndexed { rowIndex, row ->
            Row {
                for (column in 0 until preview.columns) {
                    val tint = when (column) {
                        artist -> ArtistTint
                        title -> TitleTint
                        link -> LinkTint
                        else -> null
                    }
                    Text(
                        row.getOrNull(column).orEmpty(),
                        style = if (rowIndex == 0 && hasHeader) ConvxType.CaptionStrong else ConvxType.Caption,
                        color = if (rowIndex == 0 && hasHeader) ConvxColors.Label else ConvxColors.SecondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(112.dp).background(tint?.copy(alpha = 0.18f) ?: Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    CheckRow("First row is header", checked = hasHeader) { hasHeader = !hasHeader }
    ColumnChoice("Artist name column", ArtistTint, preview.columns, artist, { name(it) }) { artist = it }
    ColumnChoice("Song title column", TitleTint, preview.columns, title, { name(it) }) { title = it }
    ColumnChoice("YouTube link column (optional)", LinkTint, preview.columns, link, { name(it) }, optional = true) { link = it }
    Spacer(Modifier.height(20.dp))
    DialogButtons {
        PillButton("Cancel", { Overlays.dismissDialog() })
        PillButton("Continue", {
            onContinue(
                PlaylistFiles.CsvMapping(
                    hasHeader = hasHeader,
                    artistColumn = artist,
                    titleColumn = title,
                    urlColumn = link,
                    // Lengths and Spotify ids are only found by a header's name, so they need one.
                    durationColumn = if (hasHeader) preview.durationColumn else -1,
                    spotifyColumn = if (hasHeader) preview.spotifyColumn else -1,
                ),
            )
        }, filled = true)
    }
}

@Composable
private fun ColumnChoice(
    label: String,
    tint: Color,
    columns: Int,
    selected: Int,
    name: (Int) -> String,
    optional: Boolean = false,
    onSelect: (Int) -> Unit,
) {
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(8.dp))
        Text(label, style = ConvxType.SubheadStrong, color = ConvxColors.Label)
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (optional) Chip("None", selected < 0, { onSelect(-1) })
        for (column in 0 until columns) Chip(name(column), selected == column, { onSelect(column) })
    }
}

@Composable
private fun DialogButtons(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) { content() }
}

/** Windows' own open dialog for several files at once, filtered to [extensions]. */
internal fun pickFiles(title: String, extensions: List<String>): List<File> {
    val picker = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
        isMultipleMode = true
        setFilenameFilter { _, name -> extensions.any { name.endsWith(".$it", ignoreCase = true) } }
        file = extensions.joinToString(";") { "*.$it" }
        isVisible = true
    }
    return picker.files.orEmpty().toList()
}

/** Windows' own open dialog, filtered to [extensions]. */
internal fun pickFile(title: String, extensions: List<String>): File? {
    val picker = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> extensions.any { name.endsWith(".$it", ignoreCase = true) } }
        file = extensions.joinToString(";") { "*.$it" }
        isVisible = true
    }
    val name = picker.file ?: return null
    return File(picker.directory, name)
}
