/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.AppScope
import com.convx.desktop.core.pluralize
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.toItem
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.Chip
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.Gutter
import com.convx.desktop.ui.components.ItemCard
import com.convx.desktop.ui.components.LocalPlayback
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.components.MessageState
import com.convx.desktop.ui.components.Shelf
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.glass.GlassCircleButton
import com.convx.desktop.ui.screens.replay.Replay
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxShapes
import com.convx.desktop.ui.theme.ConvxType
import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.Artist
import com.music.innertube.models.ArtistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections

/** The phone's ways of slicing Stats: rolling periods back from now, or calendar weeks, months and years. */
enum class StatsOption(val label: String) { Continuous("Continuous"), Weeks("Weeks"), Months("Months"), Years("Years") }

/** The chosen option and chip, kept while you go elsewhere and back, as the phone's view model keeps them. */
object StatsSelection {
    var option by mutableStateOf(StatsOption.Continuous)
    var chip by mutableIntStateOf(0)
}

/** One chip: its label and the plays it covers, after [from] and up to [to]. */
private class StatsPeriod(val label: String, val from: Long, val to: Long)

/** The chips for [option], back to the first play there is, labelled as the phone labels them. */
private fun statsPeriods(option: StatsOption, firstPlay: Long?, nowMs: Long): List<StatsPeriod> {
    val zone = ZoneId.systemDefault()
    val now = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
    fun LocalDate.ms() = atStartOfDay(zone).toInstant().toEpochMilli()
    fun LocalDateTime.ms() = atZone(zone).toInstant().toEpochMilli()
    if (option == StatsOption.Continuous) {
        return listOf(
            StatsPeriod("1 week", now.minusWeeks(1).ms(), nowMs),
            StatsPeriod("1 month", now.minusMonths(1).ms(), nowMs),
            StatsPeriod("3 months", now.minusMonths(3).ms(), nowMs),
            StatsPeriod("6 months", now.minusMonths(6).ms(), nowMs),
            StatsPeriod("1 year", now.minusMonths(12).ms(), nowMs),
            StatsPeriod("All", 0L, nowMs),
        )
    }
    val first = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(firstPlay ?: return emptyList()), zone).toLocalDate()
    val today = now.toLocalDate()
    return when (option) {
        // Seven days at a time, ending today. The phone's own weeks run a day off their labels,
        // and its first one is only the last day; these cover exactly what they say.
        StatsOption.Weeks -> {
            val day = DateTimeFormatter.ofPattern("dd MMM")
            generateSequence(0L) { it + 1 }
                .map { t -> today.minusWeeks(t).minusDays(6) to today.minusWeeks(t) }
                .takeWhile { (_, end) -> !end.isBefore(first) }
                .take(520)
                .mapIndexed { t, (start, end) ->
                    val label = when {
                        start.year != today.year -> "${day.format(start)}, ${start.year} - ${day.format(end)}, ${end.year}"
                        start.month != end.month -> "${day.format(start)} - ${day.format(end)}"
                        else -> "${start.dayOfMonth} - ${day.format(end)}"
                    }
                    StatsPeriod(label, start.ms(), if (t == 0) nowMs else end.plusDays(1).ms())
                }.toList()
        }
        StatsOption.Months -> {
            val month = DateTimeFormatter.ofPattern("MMM")
            val firstMonth = YearMonth.from(first)
            generateSequence(YearMonth.from(today)) { it.minusMonths(1) }
                .takeWhile { !it.isBefore(firstMonth) }
                .mapIndexed { t, m ->
                    val label = if (m.year != today.year) "${month.format(m)} ${m.year}" else month.format(m)
                    StatsPeriod(label, m.atDay(1).ms(), if (t == 0) nowMs else m.plusMonths(1).atDay(1).ms())
                }.toList()
        }
        StatsOption.Years -> (today.year downTo first.year).mapIndexed { t, year ->
            StatsPeriod("$year", LocalDate.of(year, 1, 1).ms(), if (t == 0) nowMs else LocalDate.of(year + 1, 1, 1).ms())
        }
        StatsOption.Continuous -> emptyList()
    }
}

/** The phone's makeTimeString: m:ss, h:mm:ss, or d:hh:mm:ss for a lot of listening. */
private fun listenedTime(ms: Long): String {
    var sec = ms / 1000
    val day = sec / 86400
    sec %= 86400
    val hour = sec / 3600
    sec %= 3600
    val minute = sec / 60
    sec %= 60
    return when {
        day > 0 -> "%d:%02d:%02d:%02d".format(day, hour, minute, sec)
        hour > 0 -> "%d:%02d:%02d".format(hour, minute, sec)
        else -> "%d:%02d".format(minute, sec)
    }
}

/** "3 times • 12:34", or just the plays for history from before time played was kept. */
internal fun statsLine(plays: Int, playedMs: Long): String =
    listOfNotNull(pluralize(plays, "time"), playedMs.takeIf { it > 0 }?.let(::listenedTime)).joinToString(" • ")

/**
 * Pictures for played artists you don't follow, looked up a few at a time and kept, as the phone's
 * Stats fills in its artists. Only the ones on screen are asked for.
 */
internal object ArtistPictureLookup {
    private val asked = Collections.synchronizedSet(HashSet<String>())
    private val requests = Semaphore(3)

    fun request(id: String) {
        if (Library.artistPicture(id) != null || !asked.add(id)) return
        AppScope.launch(Dispatchers.IO) {
            val picture = requests.withPermit { YouTube.artist(id).getOrNull() }?.artist?.thumbnail ?: return@launch
            Library.rememberArtistPictures(mapOf(id to picture))
        }
    }
}

/**
 * The phone's Stats: your most played songs, artists and albums for a rolling period or a calendar
 * week, month or year, ranked by time listened, with the shuffle button for the songs and the
 * sparks button that opens Replay.
 */
@Composable
fun StatsScreen() {
    val library by Library.state.collectAsState()
    val settings by AppSettings.state.collectAsState()
    val playback = LocalPlayback.current
    val option = StatsSelection.option
    val chip = StatsSelection.chip
    val firstPlay = remember(library.history) { library.history.minOfOrNull { it.at } }
    val periods = remember(option, firstPlay) { statsPeriods(option, firstPlay, System.currentTimeMillis()) }
    val period = periods.getOrNull(chip) ?: periods.firstOrNull()
    val stats = remember(library.history, period?.from, period?.to) {
        if (period == null) Library.ListeningStats(emptyList(), emptyList(), emptyList()) else Library.listeningStats(period.from, period.to)
    }
    val songs = remember(stats, settings.hideVideos) { if (settings.hideVideos) stats.songs.filterNot { it.value.isVideo } else stats.songs }
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PageList(state = listState, tint = Color.Black) {
            item(key = "title") {
                Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Stats", style = ConvxType.LargeTitle, modifier = Modifier.weight(1f))
                    // The phone's floating shuffle, in the header here, where it can't cover a shelf's arrows.
                    if (songs.isNotEmpty()) {
                        GlassCircleButton(
                            onClick = { PlayerController.playSongs(songs.map { it.value }.shuffled(), 0, "Most played songs") },
                            tooltip = "Shuffle",
                        ) {
                            ConvxIcon("shuffle", tint = Color.White, size = 22.dp)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    // The phone's sparks button, which opens Replay.
                    GlassCircleButton(onClick = Replay::show, tooltip = "Replay ’${java.time.Year.now().value % 100}") {
                        ConvxIcon("sparks", tint = Color.White, size = 22.dp)
                    }
                }
            }
            item(key = "chips") {
                StatsChips(
                    option = option,
                    onOption = {
                        StatsSelection.option = it
                        StatsSelection.chip = 0
                    },
                    periods = periods,
                    selected = periods.indexOf(period),
                    onSelect = { StatsSelection.chip = it },
                )
            }
            if (songs.isNotEmpty()) {
                item(key = "songs") {
                    Shelf("${songs.size} Songs", Modifier.padding(top = 20.dp)) {
                        itemsIndexed(songs, key = { _, ranked -> ranked.value.id }) { index, ranked ->
                            val song = ranked.value
                            ItemCard(
                                song.toItem(),
                                title = "${index + 1}. ${song.title}",
                                subtitle = statsLine(ranked.plays, ranked.playedMs),
                                onClick = {
                                    if (song.id == playback.currentId) PlayerController.togglePlay() else PlayerController.startRadio(song)
                                },
                            )
                        }
                    }
                }
            }
            if (stats.artists.isNotEmpty()) {
                item(key = "artists") {
                    Shelf("${stats.artists.size} Artists", Modifier.padding(top = 20.dp)) {
                        itemsIndexed(stats.artists, key = { _, ranked -> ranked.value.id!! }) { index, ranked ->
                            val id = ranked.value.id!!
                            val picture = library.artists.firstOrNull { it.id == id }?.thumbnail ?: library.artistPictures[id]
                            LaunchedEffect(id, picture) { if (picture == null) ArtistPictureLookup.request(id) }
                            ItemCard(
                                ArtistItem(id = id, title = ranked.value.name, thumbnail = picture, shuffleEndpoint = null, radioEndpoint = null),
                                title = "${index + 1}. ${ranked.value.name}",
                                subtitle = statsLine(ranked.plays, ranked.playedMs),
                            )
                        }
                    }
                }
            }
            if (stats.albums.isNotEmpty()) {
                item(key = "albums") {
                    Shelf("${stats.albums.size} Albums", Modifier.padding(top = 20.dp)) {
                        itemsIndexed(stats.albums, key = { _, ranked -> ranked.value.id }) { index, ranked ->
                            val album = ranked.value
                            ItemCard(
                                AlbumItem(
                                    browseId = album.id,
                                    playlistId = "",
                                    title = album.name,
                                    artists = ranked.song.artists.map { Artist(it.name, it.id) },
                                    thumbnail = ranked.song.thumbnail.orEmpty(),
                                ),
                                title = "${index + 1}. ${album.name}",
                                subtitle = statsLine(ranked.plays, ranked.playedMs),
                            )
                        }
                    }
                }
            }
            if (songs.isEmpty() && stats.artists.isEmpty() && stats.albums.isEmpty()) {
                item(key = "empty") {
                    MessageState(
                        if (firstPlay == null) "No plays yet" else "Nothing played then",
                        if (firstPlay == null) "Songs count here once they've played for ${settings.historyDurationSeconds.toInt()} seconds." else "Pick another period.",
                        icon = "stats",
                    )
                }
            }
        }
    }
}

/**
 * The phone's Stats chips: the Continuous / Weeks / Months / Years picker, a divider, then the
 * periods to choose between.
 */
@Composable
private fun StatsChips(
    option: StatsOption,
    onOption: (StatsOption) -> Unit,
    periods: List<StatsPeriod>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Gutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val menu = rememberMenuAnchor()
        Box {
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val turn by animateFloatAsState(if (menu.expanded) 180f else 0f, tween(400), label = "chevron")
            Row(
                Modifier
                    .clip(ConvxShapes.Capsule)
                    .background(Color.White.copy(alpha = if (hovered || menu.expanded) 0.16f else 0.08f))
                    .hoverable(interaction)
                    .pressable({
                        menu.offset = DpOffset(0.dp, 36.dp)
                        menu.expanded = true
                    }, interaction = interaction)
                    .padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ConvxIcon("sliders", tint = Color.White, size = 16.dp)
                Text(option.label, style = ConvxType.SubheadStrong, color = Color.White, maxLines = 1)
                ConvxIcon("expand_more", tint = Color.White, size = 16.dp, modifier = Modifier.graphicsLayer { rotationZ = turn })
            }
            AppMenu(menu) {
                StatsOption.entries.forEach { entry ->
                    MenuItem(entry.label, if (entry == option) "check" else null) {
                        menu.expanded = false
                        onOption(entry)
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).height(20.dp).background(ConvxColors.Separator))
        periods.forEachIndexed { index, period -> Chip(period.label, index == selected, { onSelect(index) }) }
        Spacer(Modifier.width(1.dp))
    }
}
