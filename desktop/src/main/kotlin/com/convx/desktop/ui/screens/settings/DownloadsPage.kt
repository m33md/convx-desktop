/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.DownloadFormat
import com.convx.desktop.player.Downloads
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.chooseFolder
import java.io.File

/** Downloads: where songs come from, the folder their copies go to, and what those copies are saved as. */
@Composable
fun DownloadSettings() {
    val settings by AppSettings.state.collectAsState()
    val downloaded by Downloads.done.collectAsState()
    val modulesOn = settings.enabledModules.isNotEmpty() && settings.moduleSources.isNotEmpty()
    SettingsSection(
        footer = "Downloads play with no connection. They're in Library under Downloaded, and Privacy & Storage shows how much room they take." +
            if (modulesOn) {
                " Songs come from your modules first, as they do when they play, so a module that has a song in lossless gives FLAC."
            } else {
                " With a module on (Library & Content > Modules), songs come from it first, in lossless when it has them."
            },
    ) {
        SwitchRow("Auto download on like", "Automatically download songs when you like them", settings.autoDownloadOnLike) { v ->
            AppSettings.update { it.copy(autoDownloadOnLike = v) }
        }
        val lossless by Downloads.lossless.collectAsState()
        val fromYouTube = remember(downloaded) { Downloads.fromYouTube(downloaded.values).size }
        if (modulesOn && (fromYouTube > 0 || lossless != null)) {
            RowDivider(PlainInset)
            SettingsRow(
                "Download again from your modules",
                when (val l = lossless) {
                    null -> "Swap the ${songCount(fromYouTube)} downloaded from YouTube for your modules' copies, in lossless when they have it. Songs no module has stay as they are"
                    else -> if (!l.finished) {
                        "Checking ${l.done} of ${l.total}… ${l.swapped} swapped so far"
                    } else {
                        "Swapped ${songCount(l.swapped)}" + if (l.total > l.swapped) ", ${l.total - l.swapped} aren't on your modules" else ""
                    }
                },
                enabled = fromYouTube > 0 && lossless?.finished != false,
                onClick = { Downloads.downloadAgainFromModules() },
            ) { ConvxIcon("download", tint = SystemColors.Blue, size = 18.dp) }
        }
    }
    SectionGap()
    val folder = Downloads.exportDir
    val picked = settings.downloadFolder != null
    SettingsSection(
        "Save to a folder",
        footer = "Convx keeps its own copy of each download for playing offline either way. These copies are for other apps, phones or a NAS. " +
            "Download as… in a song's or playlist's menu saves it in another format or folder just that once.",
    ) {
        SwitchRow(
            "Save downloads to a folder",
            "Keep each download in ${folder.name.ifBlank { folder.path }} as Artist - Title, with its title, artist, album, lyrics and cover inside, playable in any app",
            settings.saveDownloadsToMusic,
        ) { v -> AppSettings.update { it.copy(saveDownloadsToMusic = v) } }
        RowDivider(PlainInset)
        SettingsRow(
            "Folder",
            folder.path,
            onClick = {
                chooseFolder("Save downloads in", folder.takeIf { it.isDirectory }?.path ?: System.getProperty("user.home")) { path ->
                    pickDownloadFolder(File(path), downloaded.size)
                }
            },
        ) { ConvxIcon("storage", tint = SystemColors.Blue, size = 18.dp) }
        if (picked) {
            RowDivider(PlainInset)
            SettingsRow(
                "Use Music${File.separator}Convx again",
                "Save downloads in your Music folder, as before you picked one",
                onClick = { pickDownloadFolder(null, downloaded.size) },
            ) { ConvxIcon("restore", tint = SystemColors.Blue, size = 18.dp) }
        }
        RowDivider(PlainInset)
        ChoiceRow(
            "Format",
            settings.downloadFormat.about,
            DownloadFormat.entries.map { it to it.label },
            settings.downloadFormat,
        ) { format -> AppSettings.update { it.copy(downloadFormat = format) } }
        RowDivider(PlainInset)
        SwitchRow(
            "Ask where to save",
            "Pick a folder each time you download a song, album or playlist from its menu",
            settings.askWhereToSave,
        ) { v -> AppSettings.update { it.copy(askWhereToSave = v) } }
        val retag by Downloads.retag.collectAsState()
        RowDivider(PlainInset)
        SettingsRow(
            "Save the songs already downloaded",
            when (val r = retag) {
                null -> if (downloaded.isEmpty()) {
                    "Nothing is downloaded yet"
                } else {
                    "Put all ${songCount(downloaded.size)} in ${folder.name.ifBlank { folder.path }}" +
                        (if (settings.downloadFormat == DownloadFormat.AUTO) "" else " as ${settings.downloadFormat.label}") +
                        ", with their tags and cover"
                }
                else -> if (!r.finished) {
                    "Saving ${r.done} of ${r.total}…"
                } else {
                    "Saved ${r.total - r.failed} songs" + if (r.failed > 0) ", ${r.failed} couldn't be" else ""
                }
            },
            enabled = downloaded.isNotEmpty() && retag?.finished != false,
            onClick = { Downloads.retagMusicFolder() },
        ) { ConvxIcon("edit", tint = SystemColors.Blue, size = 18.dp) }
    }
}

private fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"

/**
 * Saves downloads in [folder] from now on (Music\Convx when null). The songs already downloaded
 * stay where they are, and the notice offers to save them there too.
 */
private fun pickDownloadFolder(folder: File?, downloaded: Int) {
    val problem = Downloads.chooseExportFolder(folder)
    if (problem != null) {
        Overlays.toast(problem, "error")
        return
    }
    val name = Downloads.exportDir.let { it.name.ifBlank { it.path } }
    if (downloaded > 0) {
        Overlays.toast("Downloads go to $name", "storage", action = "Save ${songCount(downloaded)} there") {
            Downloads.retagMusicFolder()
        }
    } else {
        Overlays.toast("Downloads go to $name", "storage")
    }
}
