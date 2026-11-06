/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.convx.desktop.data.AppSettings
import com.convx.desktop.ui.components.GridCardHeightChoices
import com.convx.desktop.ui.components.GridColumnChoices
import com.convx.desktop.ui.components.GridSpacingChoices
import com.convx.desktop.ui.components.HomeCardCornerChoices
import com.convx.desktop.ui.components.HomeHeroHeightChoices
import com.convx.desktop.ui.shell.LibraryTab
import com.convx.desktop.ui.shell.Section
import kotlin.math.roundToInt

/** The phone's thumbnail corner presets, with Auto for each design's own. */
private val ArtworkCorners = listOf(-1 to "Auto") + listOf(0, 8, 16, 24, 32, 40).map { it to "${it}dp" }

/** The phone's sheet corner presets; the PC's menus are its sheets. */
private val MenuCorners = listOf(-1 to "Auto") + listOf(16, 24, 28, 32, 40).map { it to "${it}dp" }

/** The phone's display density steps. */
private val Densities = listOf(
    1f to "Native (100%)",
    0.85f to "Slightly Compact (85%)",
    0.75f to "Compact (75%)",
    0.65f to "Very Compact (65%)",
    0.55f to "Ultra Compact (55%)",
)

/**
 * The phone's Controls & layout page (its Advanced appearance settings), with what means
 * something on a PC. What it leaves out, and why, is in the note at the foot.
 */
@Composable
fun ControlsLayoutSettings() {
    val settings by AppSettings.state.collectAsState()
    SettingsSection("Layout & behaviour") {
        SwitchRow(
            "Enable settings popup",
            "Show a quick settings popup when you click the account button, instead of opening Settings directly",
            settings.settingsPopup,
            icon = "settings",
            tint = SystemColors.Gray,
        ) { on -> AppSettings.update { it.copy(settingsPopup = on) } }
        RowDivider()
        SwitchRow(
            "Library icons only",
            "Show simple icons instead of covers in the side bar",
            settings.libraryIconsOnly,
            icon = "grid_view",
            tint = SystemColors.Orange,
        ) { on -> AppSettings.update { it.copy(libraryIconsOnly = on) } }
    }
    SectionGap()
    SettingsSection("Navigation tabs", footer = "Search always stays, as on the phone.") {
        Section.entries.forEachIndexed { index, section ->
            if (index > 0) RowDivider()
            SwitchRow(section.label, checked = section.name !in settings.hiddenSections, icon = section.icon, tint = SystemColors.Blue) { on ->
                AppSettings.update {
                    it.copy(hiddenSections = if (on) it.hiddenSections - section.name else (it.hiddenSections + section.name).distinct())
                }
            }
        }
    }
    SectionGap()
    SettingsSection("Player") {
        SwitchRow(
            "Hide player thumbnail",
            "Replace album artwork with the app logo in the player",
            settings.hidePlayerArtwork,
            icon = "music_note",
            tint = SystemColors.Indigo,
        ) { on -> AppSettings.update { it.copy(hidePlayerArtwork = on) } }
        RowDivider()
        ChoiceRow(
            "Thumbnail corner radius",
            "Change the corner radius of the album cover in the player",
            ArtworkCorners,
            settings.playerArtworkCorner,
            icon = "tune",
            tint = SystemColors.Indigo,
        ) { corner -> AppSettings.update { it.copy(playerArtworkCorner = corner) } }
        RowDivider()
        ChoiceRow(
            "Menu corner radius",
            "The phone's sheet corner radius: on the PC, its sheets are these menus",
            MenuCorners,
            settings.menuCorner,
            icon = "tune",
            tint = SystemColors.Indigo,
        ) { corner -> AppSettings.update { it.copy(menuCorner = corner) } }
        RowDivider()
        SwitchRow(
            "Show audio quality badge",
            checked = settings.showAudioQualityBadge,
            icon = "graphic_eq",
            tint = SystemColors.Indigo,
        ) { on -> AppSettings.update { it.copy(showAudioQualityBadge = on) } }
        RowDivider()
        SwitchRow(
            "Show comment button",
            "Show a button to view the song's YouTube comments in the player",
            settings.showCommentButton,
            icon = "chat_msg",
            tint = SystemColors.Indigo,
        ) { on -> AppSettings.update { it.copy(showCommentButton = on) } }
    }
    SectionGap()
    SettingsSection("Lyrics effects") {
        SwitchRow(
            "Show play/pause on thumbnail",
            "With lyrics open in the full player, click the cover to play or pause; it dims with a play button while paused",
            settings.lyricsArtworkPlayPause,
            icon = "play",
            tint = SystemColors.Pink,
        ) { on -> AppSettings.update { it.copy(lyricsArtworkPlayPause = on) } }
        RowDivider()
        SwitchRow(
            "Collapse controls upward",
            "In full-screen lyrics, the player's controls fold into a bar along the top. Off, they go away entirely",
            settings.fullscreenLyricsCollapseTop,
            icon = "lyrics_expand",
            tint = SystemColors.Pink,
        ) { on -> AppSettings.update { it.copy(fullscreenLyricsCollapseTop = on) } }
    }
    SectionGap()
    SettingsSection("Other") {
        ChoiceRow(
            "Default library chip",
            "Which filter Library opens on",
            LibraryTab.entries.map { it.name to it.label },
            settings.defaultLibraryTab,
            icon = "library_music_outlined",
            tint = SystemColors.Orange,
        ) { tab -> AppSettings.update { it.copy(defaultLibraryTab = tab) } }
        RowDivider()
        ChoiceRow(
            "Grid cell size",
            options = listOf(false to "Big", true to "Small"),
            selected = settings.gridSmall,
            icon = "grid_view",
            tint = SystemColors.Orange,
        ) { small -> AppSettings.update { it.copy(gridSmall = small) } }
        RowDivider()
        ChoiceRow(
            "Display density",
            "Everything smaller than Windows' own scale; it changes at once, where the phone restarts",
            Densities,
            settings.displayDensity,
            icon = "grid_view",
            tint = SystemColors.Orange,
        ) { scale -> AppSettings.update { it.copy(displayDensity = scale) } }
    }
    SectionGap()
    SettingsSection(
        "Grid & cards",
        footer = "Speed Dial columns and card height aren't here: the phone saves them, but nothing there reads them. " +
            "Home grid columns and albums by year belong to the phone's local-only Home, which the PC doesn't have.",
    ) {
        SwitchRow(
            "Reduce live blur",
            "Flat colour instead of live blur on Home's cards and artist pages, and still player backgrounds, for slower PCs",
            settings.reduceLiveBlur,
            icon = "remove",
            tint = SystemColors.Teal,
        ) { on -> AppSettings.update { it.copy(reduceLiveBlur = on) } }
        RowDivider()
        SwitchRow(
            "Show shuffle button",
            "A floating shuffle button at the bottom of Home. The phone's sound search beside it isn't on the PC",
            settings.showHomeShuffle,
            icon = "shuffle",
            tint = SystemColors.Teal,
        ) { on -> AppSettings.update { it.copy(showHomeShuffle = on) } }
        RowDivider()
        SwitchRow(
            "Hide favorite icon on Home",
            "Hides the heart on liked songs in Home's rows. Library, search and playlists are unaffected",
            settings.hideHomeFavoriteIcon,
            icon = "favorite_border",
            tint = SystemColors.Teal,
        ) { on -> AppSettings.update { it.copy(hideHomeFavoriteIcon = on) } }
        RowDivider()
        StepRow("Grid columns", GridColumnChoices, settings.gridColumns, { if (it == 0) "Auto" else "$it" }) { n ->
            AppSettings.update { it.copy(gridColumns = n) }
        }
        RowDivider(PlainInset)
        StepRow("Card height", GridCardHeightChoices, settings.gridCardHeight, { if (it == 0) "Auto" else "${it}dp" }) { h ->
            AppSettings.update { it.copy(gridCardHeight = h) }
        }
        RowDivider(PlainInset)
        StepRow("Grid spacing", GridSpacingChoices, settings.gridSpacing, { "${it}dp" }) { gap ->
            AppSettings.update { it.copy(gridSpacing = gap) }
        }
        RowDivider(PlainInset)
        StepRow("Hero card height", HomeHeroHeightChoices, settings.homeHeroHeight, { if (it == 0) "Auto" else "${it}dp" }) { h ->
            AppSettings.update { it.copy(homeHeroHeight = h) }
        }
        RowDivider(PlainInset)
        StepRow("Home card corner radius", HomeCardCornerChoices, settings.homeCardCorner, { if (it == 0) "Auto" else "${it}dp" }) { r ->
            AppSettings.update { it.copy(homeCardCorner = r) }
        }
    }
    SectionGap()
    InfoNote(
        "Not on the PC: Pure Black and the background and text colours (Convx stays red on black), iOS overscroll, " +
            "the mini bar tab style and nav bar melt (there's no phone navigation bar), and the top bar blur style " +
            "(the PC's top bar is glass buttons, not a blurred band).",
    )
}

/** The phone's stepped sliders: one stop per choice, its label on the right. */
@Composable
private fun StepRow(title: String, choices: List<Int>, value: Int, label: (Int) -> String, onChange: (Int) -> Unit) {
    val index = choices.indexOf(value).coerceAtLeast(0)
    SliderRow(title, label(choices[index]), index.toFloat(), 0f..choices.lastIndex.toFloat()) { position ->
        val picked = choices[position.roundToInt().coerceIn(0, choices.lastIndex)]
        if (picked != value) onChange(picked)
    }
}
