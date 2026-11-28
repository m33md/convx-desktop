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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.convx.desktop.integrations.Backup
import com.convx.desktop.integrations.PlaylistImport
import com.convx.desktop.ui.components.DialogCard
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * The phone's Backup and restore: a backup file of the library and settings, restoring one (a
 * phone's included), and its playlist imports from M3U, CSV, Spotify and Apple Music.
 */
@Composable
fun BackupSettings() {
    val state by Backup.state.collectAsState()
    val importing by PlaylistImport.state.collectAsState()
    val working = state is Backup.State.Working

    SettingsSection(
        "Backup",
        footer = "A backup holds your library, playlists, history and settings. Sign-ins stay on this PC, so they aren't in it.",
    ) {
        SettingsRow("Backup", "Save your library and settings to a file", icon = "backup", tint = SystemColors.Blue, enabled = !working, onClick = {
            saveBackupTo()?.let { Backup.create(it) }
        })
        RowDivider()
        SettingsRow("Restore", "From a backup made here or on your phone", icon = "restore", tint = SystemColors.Green, enabled = !working, onClick = {
            restoreDialog()
        })
    }
    when (val s = state) {
        is Backup.State.Working -> Status(s.what, ConvxColors.SecondaryLabel)
        is Backup.State.Done -> Status(s.message, ConvxColors.SecondaryLabel)
        is Backup.State.Failed -> Status(s.message, SystemColors.Red)
        Backup.State.Idle -> Unit
    }

    SectionGap()
    SettingsSection(
        "Move playlists to another device",
        footer = "Exported playlists are .m3u files with each song's YouTube Music link. Convx on another PC imports them " +
            "with the same songs, and a playlist it already has only gets the songs it's missing. The phone's Import a m3u " +
            "playlist reads them too, finding each song by its name.",
    ) {
        SettingsRow("Export playlists", "Save all your playlists, or the ones you pick, as files", icon = "ios_share", tint = SystemColors.Blue, onClick = {
            exportPlaylistsDialog()
        })
        RowDivider()
        val convxStatus = (importing as? PlaylistImport.State.Matching)?.takeIf { it.source == PlaylistImport.ConvxSource }
            ?.let { "Matching ${it.title} (${it.list} of ${it.lists}): ${it.done} of ${it.total} songs" }
        SettingsRow(
            "Import playlists from Convx",
            convxStatus ?: "Files exported from Convx on this PC or another one",
            icon = "playlist_add",
            tint = SystemColors.Indigo,
            enabled = !PlaylistImport.busy,
            onClick = { importConvxFiles() },
        )
    }

    SectionGap()
    SettingsSection(
        "Import playlists",
        footer = "Each song is found on YouTube Music by its title, artist and length. Songs with no close match are skipped and counted.",
    ) {
        val fileStatus = (importing as? PlaylistImport.State.Matching)?.takeIf { it.source == PlaylistImport.FileSource }
            ?.let { "Matching ${it.title}: ${it.done} of ${it.total} songs" }
        SettingsRow(
            "Import an M3U playlist",
            fileStatus ?: "From a music player's .m3u or .m3u8 file",
            icon = "playlist_add",
            tint = SystemColors.Orange,
            onClick = { importM3uFile() },
        )
        RowDivider()
        SettingsRow(
            "Import a CSV playlist",
            "From a spreadsheet, Exportify, TuneMyMusic, or iTunes' text export",
            icon = "playlist_add",
            tint = SystemColors.Green,
            onClick = { importCsvFile() },
        )
        RowDivider()
        SettingsRow(
            "Import Exportify playlists",
            "Exportify's Export All zip, or several CSV files at once",
            icon = "playlist_add",
            tint = SystemColors.Green,
            onClick = { importExportifyFiles() },
        )
        RowDivider()
        LinkRow("Import from Spotify", icon = "spotify", tint = SystemColors.Green) { SettingsNav.push(SettingsPage.Spotify) }
        RowDivider()
        LinkRow("Import from Apple Music", icon = "playlist_add", tint = SystemColors.Red) { appleMusicImportDialog() }
    }
    SectionGap()
    InfoNote(
        "A phone backup restores here as an import: its liked songs, playlists, saved albums and artists, and history join this " +
            "PC's library. Its settings stay on the phone, as do songs that were files on the phone.",
    )
}

@Composable
private fun Status(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = ConvxType.Subhead, color = color, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
}

/** Restoring a PC backup replaces the library, so it says so before the file is chosen. */
private fun restoreDialog() {
    Overlays.showDialog {
        DialogCard("Restore a backup") {
            Text(
                "A backup made on a PC replaces your library and settings here; what's here now is kept as " +
                    "${Backup.BeforeRestore} in the app data folder first. A backup from your phone only adds to your library.",
                style = ConvxType.Body,
                color = ConvxColors.SecondaryLabel,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Cancel", { Overlays.dismissDialog() })
                PillButton("Choose backup", {
                    Overlays.dismissDialog()
                    pickFile("Restore a Convx backup", listOf("backup", "zip"))?.let { Backup.restore(it) }
                }, filled = true)
            }
        }
    }
}

/** Windows' save dialog, named the way the phone names its backups. */
private fun saveBackupTo(): File? {
    val picker = FileDialog(null as Frame?, "Back up Convx", FileDialog.SAVE).apply {
        file = Backup.suggestedName()
        isVisible = true
    }
    val name = picker.file ?: return null
    return File(picker.directory, if (name.contains('.')) name else "$name.backup")
}
