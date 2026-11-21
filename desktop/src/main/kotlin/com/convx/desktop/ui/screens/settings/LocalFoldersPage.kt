/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.player.FolderPlaylists
import com.convx.desktop.player.LocalMusic
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * The phone's Music folders, Scan local files and Network storage, together: the folders Convx
 * reads music files from, the ones it skips, and the scan itself.
 */
@Composable
fun LocalFoldersSettings() {
    val settings by AppSettings.state.collectAsState()
    val status by LocalMusic.status.collectAsState()
    val index by LocalMusic.index.collectAsState()
    val counts = remember(index, settings.localFolders) {
        index.tracks.values.groupingBy { track -> settings.localFolders.firstOrNull { FolderPlaylists.within(track.path, it) } }.eachCount()
    }
    val playlists = remember(settings.folderPlaylists) { settings.folderPlaylists.map { it.lowercase() }.toSet() }

    SettingsSection(
        "Music folders",
        footer = "Convx looks through these folders and everything inside them for music files. Make playlist turns a folder into " +
            "a playlist of its own that follows it: songs you add to the folder join it after the next scan.",
    ) {
        if (settings.localFolders.isEmpty()) {
            SettingsRow("No folders yet", "Add the folders where you keep music.", icon = "storage", tint = SystemColors.Orange)
        }
        settings.localFolders.forEachIndexed { i, folder ->
            if (i > 0) RowDivider()
            val count = counts[folder] ?: 0
            SettingsRow(
                File(folder).name.ifEmpty { folder },
                "$folder · ${if (count == 1) "1 song" else "$count songs"}",
                icon = if (folder.startsWith("\\\\")) "wifi_proxy" else "storage",
                tint = SystemColors.Orange,
            ) {
                val playlist = folder.lowercase() in playlists
                PillButton(
                    if (playlist) "Playlist" else "Make playlist",
                    {
                        FolderPlaylists.set(folder, !playlist)
                        Overlays.toast(if (playlist) "\"${FolderPlaylists.nameOf(folder)}\" isn't a playlist any more" else "\"${FolderPlaylists.nameOf(folder)}\" is in your playlists now")
                    },
                    icon = if (playlist) "check" else "playlist_add",
                    filled = playlist,
                )
                Spacer(Modifier.width(8.dp))
                PillButton("Remove", {
                    AppSettings.update { it.copy(localFolders = it.localFolders - folder) }
                    FolderPlaylists.forget(folder)
                    LocalMusic.scan()
                })
            }
        }
        RowDivider(PlainInset)
        ButtonRow("Add a folder…") {
            chooseFolder("Add a music folder") { dir ->
                if (settings.localFolders.none { it.equals(dir, ignoreCase = true) }) {
                    AppSettings.update { it.copy(localFolders = it.localFolders + dir) }
                    LocalMusic.scan()
                }
            }
        }
        RowDivider(PlainInset)
        ButtonRow("Add a network share…") { networkShare() }
    }

    SectionGap()
    SettingsSection(
        "Scan",
        footer = "A file is read again when it changes, so new tags show up by themselves. Scan now also reads again the files " +
            "that had no artist. Read tags again reads every file, for tags changed by a tool that kept the file's date.",
    ) {
        SettingsRow(
            "Scan now",
            when (val s = status) {
                LocalMusic.Status.Idle -> if (index.tracks.isEmpty()) "Find music files in your folders" else "${index.tracks.size} songs found"
                is LocalMusic.Status.Scanning -> "Scanning ${s.folder}: ${s.found} songs found…"
                is LocalMusic.Status.Done -> buildString {
                    append("${s.total} songs")
                    if (s.added > 0) append(", ${s.added} new")
                    if (s.removed > 0) append(", ${s.removed} gone")
                }
                is LocalMusic.Status.Failed -> s.message
            },
            enabled = status !is LocalMusic.Status.Scanning,
            onClick = { LocalMusic.scan(untagged = true) },
        ) { ConvxIcon("sync", tint = SystemColors.Blue, size = 18.dp) }
        RowDivider(PlainInset)
        SettingsRow(
            "Read tags again",
            "Read the artist, title, album and cover of every file again",
            enabled = status !is LocalMusic.Status.Scanning && index.tracks.isNotEmpty(),
            onClick = { LocalMusic.scan(everything = true) },
        ) { ConvxIcon("refresh", tint = SystemColors.Blue, size = 18.dp) }
        RowDivider(PlainInset)
        SwitchRow("Scan when Convx starts", "Pick up new files by themselves", settings.scanLocalOnStart) { v ->
            AppSettings.update { it.copy(scanLocalOnStart = v) }
        }
    }

    SectionGap()
    SettingsSection("Excluded folders", footer = "Files in these folders are left out, like the phone's excluded folders.") {
        if (settings.excludedFolders.isEmpty()) {
            SettingsRow("No excluded folders", null, icon = "remove", tint = SystemColors.Gray)
        }
        settings.excludedFolders.forEachIndexed { i, folder ->
            if (i > 0) RowDivider()
            SettingsRow(File(folder).name.ifEmpty { folder }, folder, icon = "remove", tint = SystemColors.Gray) {
                PillButton("Remove", {
                    AppSettings.update { it.copy(excludedFolders = it.excludedFolders - folder) }
                    LocalMusic.scan()
                })
            }
        }
        RowDivider(PlainInset)
        ButtonRow("Exclude a folder…") {
            chooseFolder("Exclude a folder") { dir ->
                AppSettings.update { it.copy(excludedFolders = (it.excludedFolders + dir).distinct()) }
                LocalMusic.scan()
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    InfoNote(
        "Network storage: Windows opens SMB shares (\\\\server\\share) and WebDAV folders mapped with \"Map network drive\" " +
            "as ordinary folders, so add them here like any other. The phone needs its own SMB and WebDAV clients for that.",
    )
}

/** Windows' folder picker, through Swing's, which can choose folders where AWT's can't. */
private fun chooseFolder(title: String, onChosen: (String) -> Unit) {
    SwingUtilities.invokeLater {
        // Windows' own look, so the picker isn't Swing's grey Metal.
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val chooser = JFileChooser(System.getProperty("user.home")).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            val dir = chooser.selectedFile?.absolutePath ?: return@invokeLater
            onChosen(dir)
        }
    }
}

/** A share by its UNC path; Windows signs in to it with your account or saved credentials. */
private fun networkShare() {
    Overlays.prompt("Network share (\\\\server\\share)", confirm = "Add") { text ->
        val path = text.trim().replace('/', '\\')
        if (!path.startsWith("\\\\") || path.length < 5) {
            Overlays.toast("Write it as \\\\server\\share", "error")
            return@prompt
        }
        if (!File(path).isDirectory) {
            Overlays.toast("Windows can't open $path. Open it once in File Explorer to sign in, then try again.", "error")
            return@prompt
        }
        AppSettings.update { it.copy(localFolders = (it.localFolders + path).distinct()) }
        LocalMusic.scan()
    }
}
